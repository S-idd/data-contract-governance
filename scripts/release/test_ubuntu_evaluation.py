#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("assemble-ubuntu-evaluation.py")
SPEC = importlib.util.spec_from_file_location("assembler", SCRIPT)
assembler = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(assembler)


class UbuntuEvaluationAssemblerTest(unittest.TestCase):
    def test_rejects_traversal_and_links(self) -> None:
        bad = tarfile.TarInfo("../escape")
        with self.assertRaisesRegex(ValueError, "traversal"):
            assembler.validate_member(bad)
        link = tarfile.TarInfo("root/link")
        link.type = tarfile.SYMTYPE
        with self.assertRaisesRegex(ValueError, "unsupported"):
            assembler.validate_member(link)

    def test_manifest_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "a").write_text("one", encoding="utf-8")
            (root / "directory").mkdir()
            (root / "directory" / "b").write_text("two", encoding="utf-8")
            assembler.write_manifest(root)
            assembler.verify_manifest(root, root / "SHA256SUMS")
            (root / "a").write_text("changed", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                assembler.verify_manifest(root, root / "SHA256SUMS")

    def test_all_shell_templates_parse(self) -> None:
        import subprocess
        for script in assembler.TEMPLATE.rglob("*.sh"):
            completed = subprocess.run(["bash", "-n", str(script)], capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, f"{script}: {completed.stderr}")

    def test_all_python_templates_compile(self) -> None:
        import py_compile
        with tempfile.TemporaryDirectory() as temp:
            for index, script in enumerate(assembler.TEMPLATE.rglob("*.py")):
                py_compile.compile(str(script), cfile=str(Path(temp) / f"{index}.pyc"), doraise=True)

    def test_acceptance_report_must_match_archive(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "dcg.tar.gz"
            archive.write_bytes(b"accepted bytes")
            report = root / "report.json"
            valid = {
                "status": "PASS",
                "archive": archive.name,
                "archive_sha256": assembler.sha256(archive),
                "checks": [{"check": "real host", "status": "PASS"}],
            }
            report.write_text(json.dumps(valid), encoding="utf-8")
            self.assertEqual(assembler.acceptance_summary(report, archive)["status"], "PASS")
            valid["archive_sha256"] = "0" * 64
            report.write_text(json.dumps(valid), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "hash does not match"):
                assembler.acceptance_summary(report, archive)


if __name__ == "__main__":
    unittest.main()
