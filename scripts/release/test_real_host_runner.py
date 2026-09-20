import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("real_host", Path(__file__).with_name("test-extracted-package.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RealHostRunnerTests(unittest.TestCase):
    def test_host_target_mapping(self):
        self.assertEqual(runner.host_target("Darwin", "arm64"), "aarch64-apple-darwin")
        self.assertEqual(runner.host_target("Darwin", "x86_64"), "x86_64-apple-darwin")
        self.assertEqual(runner.host_target("Linux", "aarch64"), "aarch64-unknown-linux-gnu")
        self.assertEqual(runner.host_target("Linux", "x86_64"), "x86_64-unknown-linux-gnu")
        self.assertIsNone(runner.host_target("Windows", "AMD64"))
        self.assertIsNone(runner.host_target("Linux", "armv7l"))

    def fixture(self, directory):
        path = Path(directory)
        lines = []
        for index in range(23):
            entry = path / f"fixture-{index}"
            entry.write_text(str(index))
            lines.append(f"{runner.sha(entry)}  {entry.name}\n")
        (path / "SHA256SUMS").write_text("".join(lines))
        return path

    def test_checksums_and_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.fixture(directory)
            self.assertEqual(len(runner.check_files(path)), 23)
            (path / "fixture-0").write_text("changed")
            with self.assertRaisesRegex(AssertionError, "Checksum mismatch"):
                runner.check_files(path)

    def test_extra_file_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.fixture(directory)
            (path / "extra").write_text("unexpected")
            with self.assertRaisesRegex(AssertionError, "inventory"):
                runner.check_files(path)

    def test_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.fixture(directory)
            (path / "SHA256SUMS").write_text("0" * 64 + "  ../outside\n")
            with self.assertRaisesRegex(AssertionError, "Invalid checksum path"):
                runner.check_files(path)

    def test_status_protocols_and_expected_output(self):
        current = b"printf 'AI advisory mode: AVAILABLE\\nRust advisory process: RUNNING\\n'"
        legacy = (b"printf '%s: stopped (or stale identity record)\\n' rust; "
                  b"printf 'rust: ready (package-owned listener without PID record, PID %s)\\n' 123")
        self.assertEqual(runner.status_protocol(current), "advisory-v2")
        self.assertEqual(runner.status_protocol(legacy), "legacy-v1")
        runner.require_status("advisory-v2", "outage",
                              "Java service: RUNNING\nDeterministic enforcement: ACTIVE\n"
                              "AI advisory mode: UNAVAILABLE\nRust advisory process: STOPPED\n")
        runner.require_status("legacy-v1", "manual",
                              "java: stopped (or stale identity record)\n"
                              "rust: ready (package-owned listener without PID record, PID 123)\n")

    def test_unknown_status_protocol_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "Unsupported bin/status protocol"):
            runner.status_protocol(b"echo unknown")

    def test_compatibility_manifest_pins_runner_and_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "test-extracted-package.py"
            script.write_text("runner")
            archive_sha = "a" * 64
            manifest = {
                "schema_version": 1,
                "runner_sha256": hashlib.sha256(script.read_bytes()).hexdigest(),
                "archives": {"package.tar.gz": {"archive_sha256": archive_sha}},
            }
            (root / runner.COMPATIBILITY_MANIFEST).write_text(json.dumps(manifest))
            self.assertEqual(runner.compatibility_entry(script, "package.tar.gz", archive_sha),
                             manifest["archives"]["package.tar.gz"])
            with self.assertRaisesRegex(AssertionError, "Archive checksum"):
                runner.compatibility_entry(script, "package.tar.gz", "b" * 64)


if __name__ == "__main__":
    unittest.main()
