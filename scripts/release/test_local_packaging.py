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


if __name__ == "__main__":
    unittest.main()
