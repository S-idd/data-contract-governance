"""Local unit/regression checks; synthetic bytes are never release artifacts."""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("assembly", Path(__file__).with_name("assemble-local.py"))
assembly = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(assembly)
STAGE_SPEC = importlib.util.spec_from_file_location("staging", Path(__file__).with_name("stage-local-inputs.py"))
staging = importlib.util.module_from_spec(STAGE_SPEC)
STAGE_SPEC.loader.exec_module(staging)
WSL_SPEC = importlib.util.spec_from_file_location("wsl_workflow", Path(__file__).with_name("build-development-linux-wsl2.py"))
wsl_workflow = importlib.util.module_from_spec(WSL_SPEC)
WSL_SPEC.loader.exec_module(wsl_workflow)
ROOT = Path(__file__).resolve().parents[2]


class PackagingTests(unittest.TestCase):
    def test_release_pins_loaded_from_manifest(self):
        pins = json.loads(assembly.PIN_FILE.read_text())
        self.assertEqual(assembly.VERSION, pins["version"])
        self.assertEqual(assembly.JAVA_SHA, pins["java_build_commit"])
        self.assertEqual(assembly.RUST_SHA, pins["rust_commit"])
        self.assertEqual(assembly.RUSTC_SHA, pins["rustc_commit"])
        self.assertEqual(assembly.TARGETS, pins["targets"])
        self.assertEqual(assembly.JDK_HASHES, set(pins["jdk_archive_sha256"]))
        self.assertEqual(assembly.FROZEN, pins["frozen_artifacts"])
        for name in (assembly.JAVA_SHA, assembly.RUST_SHA, assembly.RUSTC_SHA):
            self.assertRegex(name, r"^[0-9a-f]{40}$")

    def jar(self, version="4.0.0-rc.1", executable=True):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as jar:
            jar.writestr("META-INF/maven/com.ideas.contracts/contract-cli/pom.properties", f"version={version}\n")
            jar.writestr("META-INF/MANIFEST.MF", "Main-Class: Test\n" if executable else "Manifest-Version: 1.0\n")
        return output.getvalue()

    def test_embedded_version(self):
        assembly.validate_jar(self.jar(), "contract-cli")
        with self.assertRaises(ValueError):
            assembly.validate_jar(self.jar("0.1.0-SNAPSHOT"), "contract-cli")

    def test_executable_jar_required(self):
        with self.assertRaises(ValueError):
            assembly.validate_jar(self.jar(executable=False), "contract-cli")

    def test_binary_architectures(self):
        for platform in assembly.TARGETS:
            data = bytearray(64)
            if platform.startswith("linux"):
                data[:6] = b"\x7fELF\x02\x01"
                data[18:20] = (183 if platform.endswith("arm64") else 62).to_bytes(2, "little")
            else:
                data[:4] = b"\xcf\xfa\xed\xfe"
                data[4:8] = (0x100000c if platform.endswith("arm64") else 0x1000007).to_bytes(4, "little")
            assembly.validate_binary(data, platform)
            other = platform.replace("arm64", "x64") if platform.endswith("arm64") else platform.replace("x64", "arm64")
            with self.assertRaises(ValueError):
                assembly.validate_binary(data, other)

    def test_not_a_binary(self):
        for platform in assembly.TARGETS:
            with self.assertRaises(ValueError):
                assembly.validate_binary(b"#!/bin/sh\n", platform)

    def test_missing_and_symlink_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            with self.assertRaises(ValueError):
                assembly.read_input(path, "missing")
            (path / "real").write_text("data")
            (path / "link").symlink_to(path / "real")
            with self.assertRaises(ValueError):
                assembly.read_input(path, "link")

    def test_deterministic_archive_modes_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            files = {"bin/dcg": b"launcher", "lib/test.jar": b"jar"}
            first = assembly.write_archive(files, path / "first.tar.gz", "dcg-test")
            second = assembly.write_archive(files, path / "second.tar.gz", "dcg-test")
            self.assertEqual(first, second)
            with self.assertRaises(FileExistsError):
                assembly.write_archive(files, path / "first.tar.gz", "dcg-test")
            with tarfile.open(path / "first.tar.gz") as archive:
                self.assertEqual({m.name for m in archive if m.isfile()}, {"dcg-test/bin/dcg", "dcg-test/lib/test.jar"})
                self.assertEqual(archive.getmember("dcg-test/bin/dcg").mode, 0o755)
                self.assertEqual(archive.getmember("dcg-test/lib/test.jar").mode, 0o644)
                for member in archive:
                    self.assertEqual(member.mtime, 0)
                    self.assertEqual(member.uid, 0)

    def test_manifest_matches_document(self):
        text = (ROOT / "docs/local-prerelease-packaging.md").read_text()
        documented = set(re.findall(r"^\| `([^`]+)` \|", text, re.M))
        # Historical alpha manifest fixes the layout; substitute only the active JAR version.
        documented = {name.replace("4.0.0-alpha.1", assembly.VERSION) for name in documented}
        # Ignore the later source-hash table, whose names are prefixed with Rust/Java.
        names = {f"lib/{assembly.CLI}", f"lib/{assembly.SERVICE}", "bin/dcgaimodel",
                 "bin/dcg", "bin/start", "bin/stop", "bin/status", "README.md", "RELEASE-NOTES.md",
                 "config/application-local-demo.properties.example", "LICENSE", "licenses/dcgaimodel-LICENSE",
                 "contracts/policy-packs.json", "contracts/orders.created/metadata.yaml",
                 "contracts/orders.created/v1.json", "contracts/orders.created/v2.json",
                 "THIRD-PARTY-NOTICES.txt", "sbom.cdx.json", "build-info.json", "SHA256SUMS"}
        names.update("model/" + name for name in assembly.FROZEN)
        self.assertEqual(names, documented)

    def test_shell_syntax(self):
        for script in (ROOT / "packaging/local/bin").iterdir():
            subprocess.run(["bash", "-n", str(script)], check=True)

    def test_wsl_development_workflow_help_is_host_independent(self):
        result = subprocess.run(
            ["python3", str(ROOT / "scripts/release/build-development-linux-wsl2.py"), "--help"],
            capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--skip-acceptance", result.stdout)
        self.assertIn("--jdk-archive", result.stdout)

    def test_wsl_development_workflow_verifies_external_checksums(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            artifact = path / "artifact.tar.gz"
            artifact.write_bytes(b"verified artifact")
            (path / "SHA256SUMS").write_text(
                f"{hashlib.sha256(artifact.read_bytes()).hexdigest()}  {artifact.name}\n")
            wsl_workflow.verify_external_checksums(path)
            artifact.write_bytes(b"tampered")
            with self.assertRaisesRegex(RuntimeError, "Checksum mismatch"):
                wsl_workflow.verify_external_checksums(path)

    def test_wsl_development_workflow_rejects_windows_mount_workspace(self):
        with patch.object(Path, "resolve", return_value=Path("/mnt/c/dcg-build")):
            with self.assertRaisesRegex(RuntimeError, "WSL ext4 filesystem"):
                wsl_workflow.require_outside_windows_mount(Path("fixture"))

    def test_java_wrong_major_rejected(self):
        with tempfile.TemporaryDirectory(prefix="dcg test ") as directory:
            path = Path(directory)
            (path / "bin").mkdir()
            java = path / "bin/java"
            java.write_text('#!/bin/sh\necho \'openjdk version "17.0.1"\' >&2\n')
            java.chmod(0o755)
            import os
            result = subprocess.run(["bash", str(assembly.template_path("bin/dcg")), "--help"],
                                    env={**os.environ, "JAVA_HOME": directory}, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(b"Java 21 is required", result.stderr)

    def test_stale_pid_never_killed(self):
        with tempfile.TemporaryDirectory() as directory:
            process = subprocess.Popen(["sleep", "30"])
            try:
                result = subprocess.run(["bash", "-c", '''
source "$1"
STATE=$2
printf '%s\n%s\n' "$3" "$(signature "$3")" > "$STATE/java.pid"
stop_one java
''', "test", str(assembly.template_path("bin/dcg")), directory, str(process.pid)], capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIsNone(process.poll())
            finally:
                process.terminate()
                process.wait()

    def test_untracked_rust_resolves_executable_and_requires_exact_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            bin_dir = path / "bin"
            bin_dir.mkdir()
            (bin_dir / "dcg").write_bytes(assembly.template_path("bin/dcg").read_bytes())
            fake_tools = path / "tools"
            fake_tools.mkdir()
            fake_ps = fake_tools / "ps"
            fake_ps.write_text('#!/bin/sh\nprintf "%s\\n" "$MOCK_PROCESS_ARGS"\n')
            fake_ps.chmod(0o755)
            fake_lsof = fake_tools / "lsof"
            fake_lsof.write_text('#!/bin/sh\nprintf "p1234\\nftxt\\nn%s\\n" "$MOCK_EXE_PATH"\n')
            fake_lsof.chmod(0o755)
            root = path.resolve()
            expected_args = f"serve-shadow-inference --artifact-root {root}/model --bind 127.0.0.1:8081"
            command = ["bash", "-c", 'source "$1"; owned_rust_listener 1234', "test", str(bin_dir / "dcg")]
            environment = {**os.environ, "PATH": str(fake_tools) + os.pathsep + os.environ["PATH"]}
            result = subprocess.run(command, env={**environment, "MOCK_EXE_PATH": f"{root}/bin/dcgaimodel",
                                                  "MOCK_PROCESS_ARGS": f"./bin/dcgaimodel {expected_args}"})
            self.assertEqual(result.returncode, 0)
            result = subprocess.run(command, env={**environment, "MOCK_EXE_PATH": "/other/dcgaimodel",
                                                  "MOCK_PROCESS_ARGS": f"./bin/dcgaimodel {expected_args}"})
            self.assertNotEqual(result.returncode, 0)
            result = subprocess.run(command, env={**environment, "MOCK_EXE_PATH": f"{root}/bin/dcgaimodel",
                                                  "MOCK_PROCESS_ARGS": f"./bin/dcgaimodel {expected_args} --extra"})
            self.assertNotEqual(result.returncode, 0)

    def fixture_inputs(self, path):
        # Deliberately synthetic unit-test artifacts; never published or claimed as an SBOM.
        for name, artifact in [(assembly.CLI, "contract-cli"), (assembly.SERVICE, "contract-service")]:
            output = io.BytesIO()
            with zipfile.ZipFile(output, "w") as jar:
                jar.writestr(f"META-INF/maven/com.ideas.contracts/{artifact}/pom.properties", "version=4.0.0-rc.1\n")
                jar.writestr("META-INF/MANIFEST.MF", "Main-Class: TestFixture\n")
            (path / name).write_bytes(output.getvalue())
        binary = bytearray(64)
        binary[:4] = b"\xcf\xfa\xed\xfe"
        binary[4:8] = (0x100000c).to_bytes(4, "little")
        (path / "dcgaimodel").write_bytes(binary)
        provenance = {"version": assembly.VERSION, "java_build_commit": assembly.JAVA_SHA,
                      "rust_commit": assembly.RUST_SHA, "target": "aarch64-apple-darwin",
                      "java_vendor": "Eclipse Temurin", "java_version": "21.0.12.1+1",
                      "release_pins_sha256": assembly.digest(assembly.PIN_FILE.read_bytes()),
                      "jdk_archive_sha256": sorted(assembly.JDK_HASHES)[0],
                      "rustc_verbose": assembly.RUSTC_SHA, "cargo_version": "cargo 1.96.0 (fixture)",
                      "artifacts": {f"lib/{name}": assembly.digest((path / name).read_bytes()) for name in [assembly.CLI, assembly.SERVICE]}}
        provenance["artifacts"]["bin/dcgaimodel"] = assembly.digest(binary)
        (path / "build-info.json").write_text(json.dumps(provenance))
        (path / "sbom.cdx.json").write_text(json.dumps({"bomFormat": "CycloneDX", "components": [
            {"purl": "pkg:maven/test/fixture@1"}, {"purl": "pkg:cargo/fixture@1"}]}))
        (path / "THIRD-PARTY-NOTICES.txt").write_text("SYNTHETIC UNIT TEST NOTICE — NOT FOR RELEASE. " * 4)
        return argparse.Namespace(inputs=path, java_repo="fixture-java", rust_repo="fixture-rust", platform="macos-arm64")

    def test_payload_checksums_and_allowlist(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            args = self.fixture_inputs(path)
            (path / "secret.env").write_text("must not be packaged")
            def source(repo, revision, name):
                self.assertEqual(revision, assembly.JAVA_SHA if repo == "fixture-java" else assembly.RUST_SHA)
                if name == "contracts/policy-packs.json":
                    return (ROOT / name).read_bytes()
                return b"synthetic source fixture"
            frozen = {name: assembly.digest(b"synthetic source fixture") for name in assembly.FROZEN}
            with patch.object(assembly, "git_file", side_effect=source), patch.object(assembly, "FROZEN", frozen):
                files = assembly.payload(args)
            self.assertEqual(len(files), 24)
            self.assertNotIn("secret.env", files)
            lines = files["SHA256SUMS"].decode().splitlines()
            self.assertEqual(len(lines), len(files) - 1)
            names = []
            for line in lines:
                checksum, name = line.split("  ", 1)
                self.assertEqual(checksum, assembly.digest(files[name]))
                names.append(name)
            self.assertEqual(names, sorted(names))
            archive = path / "fixture.tar.gz"
            assembly.write_archive(files, archive, "fixture")
            with tarfile.open(archive) as package:
                for name, data in files.items():
                    self.assertEqual(package.extractfile("fixture/" + name).read(), data)

    def test_provenance_and_tampering_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            args = self.fixture_inputs(path)
            (path / "dcgaimodel").write_bytes((path / "dcgaimodel").read_bytes() + b"tampered")
            with self.assertRaisesRegex(ValueError, "Input digest mismatch"):
                assembly.payload(args)
            args = self.fixture_inputs(path)
            info = json.loads((path / "build-info.json").read_text())
            info["java_build_commit"] = "main"
            (path / "build-info.json").write_text(json.dumps(info))
            with self.assertRaisesRegex(ValueError, "java_build_commit"):
                assembly.payload(args)

    def test_missing_rust_sbom_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            args = self.fixture_inputs(path)
            (path / "sbom.cdx.json").write_text('{"bomFormat":"CycloneDX","components":[]}')
            with self.assertRaisesRegex(ValueError, "both Maven and Cargo"):
                assembly.payload(args)

    def test_superseded_java_pin_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            args = self.fixture_inputs(path)
            info = json.loads((path / "build-info.json").read_text())
            info["java_build_commit"] = "dac3ed509d03e1bef75c47b497ca80bbdd1f2e04"
            (path / "build-info.json").write_text(json.dumps(info))
            with self.assertRaisesRegex(ValueError, "java_build_commit"):
                assembly.payload(args)

    def development_info(self):
        return {"version": "4.0.0-phase1-no-ai-dev.20260917", "base_version": assembly.VERSION,
                "assembled_at": "2026-09-17T00:00:00Z", "packaging_source": {
                    "commit": "a" * 40, "dirty": True, "worktree_inventory_sha256": "b" * 64}}

    def test_development_identity_rejects_rc_and_unsafe_names(self):
        info = self.development_info()
        self.assertEqual(assembly.development_identity(info), info["version"])
        for version in (assembly.VERSION, "4.0.0-rc.1-dev", "../dev", "4.0.0"):
            with self.assertRaises(ValueError):
                assembly.development_identity({**info, "version": version})
        with self.assertRaises(ValueError):
            assembly.development_identity({**info, "packaging_source": {}})
        current = {**info, "java_build_commit": "c" * 40, "rust_commit": assembly.RUST_SHA,
                   "packaging_source": {**info["packaging_source"], "dirty": False}}
        self.assertEqual(assembly.development_commits(current), ("c" * 40, assembly.RUST_SHA, True))
        with self.assertRaisesRegex(ValueError, "clean packaging commit"):
            assembly.development_identity({**current, "packaging_source": info["packaging_source"]})
        with self.assertRaisesRegex(ValueError, "frozen model/runtime pin"):
            assembly.development_identity({**current, "rust_commit": "d" * 40})
        with self.assertRaisesRegex(ValueError, "must match"):
            assembly.development_identity({**current, "packaging_source": {
                **current["packaging_source"], "commit": "d" * 40}})

    def test_development_paths_detected_inside_nested_jar(self):
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, "w", compression=zipfile.ZIP_DEFLATED) as jar:
            jar.writestr("test.class", b"/Users/example/source.rs")
        outer = io.BytesIO()
        with zipfile.ZipFile(outer, "w", compression=zipfile.ZIP_DEFLATED) as jar:
            jar.writestr("BOOT-INF/lib/test.jar", inner.getvalue())
        with self.assertRaisesRegex(ValueError, "Developer absolute path"):
            assembly.check_developer_paths("service.jar", outer.getvalue())

    def test_notice_tampering_rejected_before_assembly(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            args = self.fixture_inputs(path)
            info = json.loads((path / "build-info.json").read_text())
            info["dependency_evidence"] = {"notices_sha256": assembly.digest((path / "THIRD-PARTY-NOTICES.txt").read_bytes()),
                                           "sbom_sha256": assembly.digest((path / "sbom.cdx.json").read_bytes())}
            (path / "build-info.json").write_text(json.dumps(info))
            (path / "THIRD-PARTY-NOTICES.txt").write_bytes(b"changed notice " * 20)
            with self.assertRaisesRegex(ValueError, "notices_sha256"):
                assembly.payload(args)

    def test_development_payload_retains_runtime_pins_and_notice_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            args = self.fixture_inputs(path)
            notice = b"Mixed upstream notice\r\n" * 10 + b"lone CR\r"
            (path / "THIRD-PARTY-NOTICES.txt").write_bytes(notice)
            info = json.loads((path / "build-info.json").read_text())
            info["dependency_evidence"] = {"notices_sha256": assembly.digest(notice),
                                           "sbom_sha256": assembly.digest((path / "sbom.cdx.json").read_bytes())}
            (path / "build-info.json").write_text(json.dumps(info))
            args.development_info = path / "development.json"
            args.development_info.write_text(json.dumps(self.development_info()))
            def source(repo, revision, name):
                return (ROOT / name).read_bytes() if name == "contracts/policy-packs.json" else b"fixture"
            with patch.object(assembly, "git_file", side_effect=source), patch.object(assembly, "FROZEN", {n: assembly.digest(b"fixture") for n in assembly.FROZEN}):
                files = assembly.payload(args)
            self.assertEqual(files["THIRD-PARTY-NOTICES.txt"], notice)
            self.assertIn(b"VERSION=4.0.0-rc.1", files["bin/dcg"])
            self.assertIn(b"/.local/share/dcg/4.0.0-phase1-no-ai-dev.20260917", files["bin/dcg"])
            metadata = json.loads(files["build-info.json"])
            self.assertEqual(metadata["version"], self.development_info()["version"])
            self.assertTrue(metadata["development"]["packaging_source"]["dirty"])
            for line in files["SHA256SUMS"].decode().splitlines():
                sha, name = line.split("  ", 1)
                self.assertEqual(sha, assembly.digest(files[name]))

    def test_clean_development_payload_uses_explicit_source_commits(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            args = self.fixture_inputs(path)
            current_java = "c" * 40
            development = {**self.development_info(), "java_build_commit": current_java,
                           "rust_commit": assembly.RUST_SHA,
                           "packaging_source": {"commit": current_java, "dirty": False,
                                                "worktree_inventory_sha256": "e" * 64}}
            args.development_info = path / "development.json"
            args.development_info.write_text(json.dumps(development))
            info = json.loads((path / "build-info.json").read_text())
            info["java_build_commit"] = current_java
            info["development_source"] = {"java_build_commit": current_java,
                                          "rust_commit": assembly.RUST_SHA,
                                          "packaging_commit": current_java, "clean": True}
            info["dependency_evidence"] = {
                "notices_sha256": assembly.digest((path / "THIRD-PARTY-NOTICES.txt").read_bytes()),
                "sbom_sha256": "pending"}
            sbom = json.loads((path / "sbom.cdx.json").read_text())
            sbom["components"].append({
                "group": "com.ideas.contracts", "name": "contract-service",
                "purl": "pkg:maven/com.ideas.contracts/contract-service@4.0.0-rc.1",
                "externalReferences": [{"type": "vcs",
                                        "url": f"https://github.com/S-idd/data-contract-governance/tree/{current_java}"}]})
            (path / "sbom.cdx.json").write_text(json.dumps(sbom))
            info["dependency_evidence"]["sbom_sha256"] = assembly.digest((path / "sbom.cdx.json").read_bytes())
            (path / "build-info.json").write_text(json.dumps(info))

            def source(repo, revision, name):
                self.assertEqual(revision, current_java if repo == "fixture-java" else assembly.RUST_SHA)
                if repo == "fixture-java" and name in assembly.TOOLING:
                    return assembly.TOOLING[name].read_bytes()
                if name == "contracts/policy-packs.json":
                    return (ROOT / name).read_bytes()
                return b"current source fixture"

            def packaging(repo, revision, name):
                self.assertEqual((repo, revision), ("fixture-java", current_java))
                relative = name.removeprefix("packaging/local/")
                candidate = ROOT / "packaging/local" / relative
                return candidate.read_bytes() if candidate.is_file() else None

            frozen = {name: assembly.digest(b"current source fixture") for name in assembly.FROZEN}
            with patch.object(assembly, "git_file", side_effect=source), \
                    patch.object(assembly, "git_file_optional", side_effect=packaging), \
                    patch.object(assembly, "FROZEN", frozen):
                files = assembly.payload(args)
            metadata = json.loads(files["build-info.json"])
            self.assertEqual(metadata["java_build_commit"], current_java)
            self.assertEqual(metadata["development_source"]["packaging_commit"], current_java)
            self.assertNotIn("java_preparation_commit", metadata)
            self.assertEqual(metadata["development"], development)
            self.assertEqual(set(metadata["packaging_tooling_sha256"]), set(assembly.TOOLING))
            self.assertEqual(assembly.status_protocol(files["bin/status"]), "advisory-v2")

    def test_compatibility_manifest_pins_generated_archive_and_runner(self):
        files = {
            "bin/status": assembly.template_path("bin/status").read_bytes(),
            "build-info.json": json.dumps({
                "java_build_commit": "c" * 40, "rust_commit": assembly.RUST_SHA,
                "development": {"packaging_source": {"commit": "d" * 40}},
            }).encode(),
        }
        archive = Path("dcg-development-linux-x64.tar.gz")
        manifest = assembly.compatibility_manifest(archive, "a" * 64, files)
        entry = manifest["archives"][archive.name]
        self.assertEqual(manifest["runner_sha256"], assembly.digest(assembly.RUNNER.read_bytes()))
        self.assertEqual(entry["archive_sha256"], "a" * 64)
        self.assertEqual(entry["status_protocol"], "advisory-v2")
        self.assertEqual(entry["packaging_commit"], "d" * 40)

    def test_current_source_rejects_uncommitted_packaging_tooling(self):
        with patch.object(assembly, "git_file", return_value=b"different"):
            with self.assertRaisesRegex(ValueError, "Packaging tool differs"):
                assembly.verify_packaging_tooling("fixture-java", "c" * 40)

    def test_publish_output_checksums_archive_and_compatibility_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            files = {
                "bin/status": assembly.template_path("bin/status").read_bytes(),
                "build-info.json": json.dumps({
                    "java_build_commit": "c" * 40, "rust_commit": assembly.RUST_SHA,
                }).encode(),
            }
            archive = assembly.publish_output(files, output, "4.0.0-test-dev.1", "linux-x64")
            manifest = output / "archive-runner-compatibility.json"
            lines = (output / "SHA256SUMS").read_text().splitlines()
            self.assertEqual(lines, [f"{assembly.digest(archive.read_bytes())}  {archive.name}",
                                     f"{assembly.digest(manifest.read_bytes())}  {manifest.name}"])
            self.assertEqual(json.loads(manifest.read_text())["archives"][archive.name]["archive_sha256"],
                             assembly.digest(archive.read_bytes()))
            with self.assertRaisesRegex(ValueError, "must be empty"):
                assembly.publish_output(files, output, "4.0.0-test-dev.1", "linux-x64")

    def test_clean_packaging_checkout_inventory_is_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Packaging Test"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.email", "packaging@example.invalid"], check=True)
            (repo / "tracked.txt").write_text("tracked\n")
            subprocess.run(["git", "-C", str(repo), "add", "tracked.txt"], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)
            source = {"commit": subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"],
                                                        text=True).strip(),
                      "worktree_inventory_sha256": staging.tracked_inventory(repo)}
            staging.verify_clean_checkout(repo, source)
            (repo / "untracked.txt").write_text("dirty\n")
            with self.assertRaisesRegex(ValueError, "not clean"):
                staging.verify_clean_checkout(repo, source)
            (repo / "untracked.txt").unlink()
            with self.assertRaisesRegex(ValueError, "inventory"):
                staging.verify_clean_checkout(repo, {**source, "worktree_inventory_sha256": "0" * 64})


if __name__ == "__main__":
    unittest.main()
