import importlib.util
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


if __name__ == "__main__":
    unittest.main()
