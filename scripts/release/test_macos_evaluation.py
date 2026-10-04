"""Portability, provenance and failure-cleanup regressions; no real services."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch, MagicMock

ROOT = Path(__file__).resolve().parents[2]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


assembler = module("mac_assembler", ROOT / "scripts/release/assemble-ubuntu-evaluation.py")
ubuntu = module("ubuntu_acceptance", ROOT / "evaluation/ubuntu-24.04/scripts/ubuntu-wsl2-acceptance.py")
mac = module("mac_acceptance", ROOT / "evaluation/macos-arm64/scripts/macos-acceptance.py")


class MacEvaluationTests(unittest.TestCase):
    def test_preflight_rejects_intel_or_rosetta_before_startup(self):
        with tempfile.TemporaryDirectory() as directory:
            mock = Path(directory) / "bash-env"
            mock.write_text('uname() { if [[ "$1" == -s ]]; then echo Darwin; else echo x86_64; fi; }\n')
            result = subprocess.run(["/bin/bash", str(assembler.MAC_TEMPLATE / "scripts/verify-install.sh")],
                                    env={**os.environ, "BASH_ENV": str(mock)}, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Intel/Rosetta is not supported", result.stderr)

    def test_mac_partial_startup_attempts_cleanup(self):
        with tempfile.TemporaryDirectory(prefix="mac smoke ") as directory:
            root = Path(directory)
            bundle = root / "bundle"
            bundle.mkdir()
            (bundle / "bundle-info.json").write_text("{}")
            evidence = root / "evidence"
            calls = []

            def fake_run(command, **kwargs):
                name = Path(command[1]).name
                calls.append(name)
                code = 7 if name == "start-dcg.sh" else 0
                text = ""
                if name == "dcg":
                    code = 1 if any(str(a).endswith("fail.json") for a in command) else 0
                    text = "Schema compatibility: " + ("FAIL" if code else "PASS")
                return subprocess.CompletedProcess(command, code, text, "")

            sampler = MagicMock()
            sampler.finish.return_value = {"errors": [], "samples": []}
            sampler.begin_workload.return_value = {}

            def fake_popen(command, **kwargs):
                result = fake_run(command)
                process = MagicMock()
                process.pid = 999999
                process.returncode = result.returncode
                process.communicate.return_value = (result.stdout, None)
                return process
            with patch.object(mac, "BUNDLE", bundle), patch.object(mac, "SCRIPTS", bundle / "scripts"), \
                 patch.object(mac, "STATE", bundle / "workspace"), \
                 patch.object(mac.platform, "system", return_value="Darwin"), \
                 patch.object(mac.platform, "machine", return_value="arm64"), \
                 patch.object(mac, "port_free", return_value=True), patch.object(mac, "memory_snapshot", return_value={}), \
                 patch.object(mac, "output", return_value=str(8 * 1024**3)), \
                 patch.object(mac.subprocess, "run", side_effect=fake_run), \
                 patch.object(mac.subprocess, "check_output", return_value="Java 21"), \
                 patch.object(mac.subprocess, "Popen", side_effect=fake_popen), patch.object(mac, "MemorySampler", return_value=sampler), \
                 patch.object(mac.time, "sleep"), \
                 patch("sys.argv", ["runner", "--evidence", str(evidence)]):
                self.assertEqual(mac.main(), 1)
            report = json.loads((evidence / "results.json").read_text())
            self.assertIn("stop-dcg.sh", calls)
            self.assertEqual(report["cleanup"][0]["status"], "PASS")
            self.assertIn("start-no-ai", report["error"])

    def test_mac_templates_parse(self):
        for path in assembler.MAC_TEMPLATE.rglob("*.sh"):
            subprocess.run(["/bin/bash", "-n", str(path)], check=True)
        for path in assembler.MAC_TEMPLATE.rglob("*.py"):
            compile(path.read_text(), str(path), "exec")

    def test_assembly_reuses_binary_excludes_matrix_and_preserves_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "existing"
            source.mkdir()
            artifacts = {"bin/dcgaimodel": b"\xcf\xfa\xed\xfe\x0c\x00\x00\x01" + b"native-fixture",
                         "lib/contract-cli-4.0.0-rc.1-all.jar": b"cli",
                         "lib/contract-service-4.0.0-rc.1.jar": b"service"}
            for name, data in artifacts.items():
                path = source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            info = {"target": "aarch64-apple-darwin", "rust_commit": "test-pin",
                    "artifacts": {n: assembler.sha256(source / n) for n in artifacts}}
            (source / "build-info.json").write_text(json.dumps(info))
            assembler.write_manifest(source)
            original = (source / "SHA256SUMS").read_bytes()
            # Unlisted private state must never enter the output.
            (source / "private-password").write_text("not-an-input")
            (root / "scripts/release").mkdir(parents=True)
            (root / "scripts/release/release-pins.json").write_text(json.dumps({"rust_commit": "test-pin", "frozen_artifacts": {}}))
            shutil.copytree(ROOT / "packaging/local/bin", root / "packaging/local/bin")
            args = argparse.Namespace(output=root / "bundle", dcg_package=source, archive=root / "bundle.tar.gz")
            with patch.object(assembler, "ROOT", root):
                assembler.assemble_macos(args)
            self.assertEqual((source / "SHA256SUMS").read_bytes(), original)
            self.assertEqual((args.output / "dcg/bin/dcgaimodel").read_bytes(), artifacts["bin/dcgaimodel"])
            self.assertEqual((args.output / "dcg/build-info.json").read_bytes(), (source / "build-info.json").read_bytes())
            for path in ("docker", "iems", "dcg/private-password", "scripts/start-iems.sh", "scripts/ubuntu-wsl2-acceptance.py"):
                self.assertFalse((args.output / path).exists(), path)
            assembler.verify_manifest(args.output, args.output / "SHA256SUMS")
            (source / "bin/dcgaimodel").write_bytes(b"tampered")
            args.output = root / "second"
            with patch.object(assembler, "ROOT", root), self.assertRaisesRegex(ValueError, "checksum mismatch"):
                assembler.assemble_macos(args)

    def test_iems_timeout_preserves_pid_and_success_removes_it(self):
        for stubborn in (True, False):
            with self.subTest(stubborn=stubborn), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                scripts = root / "scripts"
                scripts.mkdir()
                for name in ("lib.sh", "stop-iems.sh"):
                    shutil.copy2(ROOT / "evaluation/ubuntu-24.04/scripts" / name, scripts / name)
                pidfile = root / "workspace/state/iems/pid"
                pidfile.parent.mkdir(parents=True)
                pidfile.write_text("999999\n")
                mock = root / "bash-env"
                mock.write_text('''
terminated=0
kill() {
  if [[ "$1" == -TERM ]]; then terminated=1; return 0; fi
  [[ "$STUB_STUBBORN" == true || "$terminated" == 0 ]]
}
sleep() { :; }
ps() { printf '%s/iems/iems.jar\\n' "$STUB_ROOT"; }
''')
                result = subprocess.run(["/bin/bash", str(scripts / "stop-iems.sh")], capture_output=True, text=True,
                                        env={**os.environ, "BASH_ENV": str(mock), "STUB_ROOT": str(root.resolve()),
                                             "STUB_STUBBORN": str(stubborn).lower()})
                self.assertEqual(result.returncode, 1 if stubborn else 0, result.stderr)
                self.assertEqual(pidfile.exists(), stubborn)
                if stubborn:
                    self.assertIn("preserving", result.stderr)

    def test_ubuntu_partial_startup_always_attempts_cleanup(self):
        cases = (("start-dcg-no-ai", "stop-dcg.sh"), ("start-dcg-ai", "stop-dcg.sh"),
                 ("start-iems-sqlite", "stop-iems.sh"), ("database-up", "database-down.sh"),
                 ("start-iems-postgres", "stop-iems.sh"), ("start-iems-mysql", "stop-iems.sh"))
        for failure, cleanup_script in cases:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "examples/contracts/orders.created").mkdir(parents=True)
                (root / "workspace/contracts").mkdir(parents=True)
                evidence = root / "evidence"
                calls = []
                dcg_starts = 0

                def fake_run(command, **kwargs):
                    nonlocal dcg_starts
                    script = Path(command[0]).name
                    name = script.removesuffix(".sh")
                    if script == "start-dcg.sh":
                        dcg_starts += 1
                        name += "-no-ai" if dcg_starts == 1 else "-ai"
                    elif script == "start-iems.sh":
                        name += "-" + command[1]
                    calls.append(script)
                    return subprocess.CompletedProcess(command, 7 if name == failure else 0,
                        "Deterministic enforcement: ACTIVE\nAI advisory mode: DISABLED\nAI advisory mode: AVAILABLE\n")

                original_read = Path.read_text

                def read(path, *args, **kwargs):
                    if str(path) == "/proc/sys/kernel/osrelease":
                        return "microsoft"
                    return original_read(path, *args, **kwargs)

                with patch.object(ubuntu, "BUNDLE", root), patch.object(ubuntu, "SCRIPTS", root / "scripts"), \
                     patch.object(ubuntu.platform, "system", return_value="Linux"), \
                     patch.object(ubuntu.platform, "machine", return_value="x86_64"), \
                     patch.object(ubuntu, "os_release", return_value={"ID": "ubuntu", "VERSION_ID": "24.04"}), \
                     patch.object(ubuntu, "port_free", return_value=True), patch.object(Path, "read_text", read), \
                     patch.object(ubuntu.subprocess, "run", side_effect=fake_run), \
                     patch("sys.argv", ["runner", "--evidence", str(evidence)]):
                    self.assertEqual(ubuntu.main(), 1)
                report = json.loads((evidence / "results.json").read_text())
                self.assertIn(cleanup_script.removesuffix(".sh"), [c["action"] for c in report["cleanup"]])


if __name__ == "__main__":
    unittest.main()
