#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import io
import json
import os
import shutil
import sqlite3
import subprocess
import sys
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("assemble-ubuntu-evaluation.py")
SPEC = importlib.util.spec_from_file_location("assembler", SCRIPT)
assembler = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(assembler)


class UbuntuEvaluationAssemblerTest(unittest.TestCase):
    def test_iems_archive_contains_seed_without_iems_source_helper(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, destination = root / "source", root / "bundle"
            for directory in (source / "target", source / "contracts", source / "postman",
                              destination / "examples"):
                directory.mkdir(parents=True)
            jar = source / "target/inclusive-education-management-system-1.0.0-SNAPSHOT.jar"
            jar.write_bytes(b"jar")
            (destination / "examples/policy-packs.json").write_text("{}")
            self.assertFalse((source / "scripts/postman/seed_notification.py").exists())
            with patch.object(assembler, "git_identity", return_value="test-commit"):
                assembler.copy_iems(jar, source, destination)
            helper = Path("iems/postman/seed_notification.py")
            expected = (assembler.ROOT / "scripts/release/fixtures/iems/seed_notification.py").read_bytes()
            self.assertEqual((destination / helper).read_bytes(), expected)
            assembler.write_manifest(destination)
            assembler.verify_manifest(destination, destination / "SHA256SUMS")
            self.assertIn(str(helper), (destination / "SHA256SUMS").read_text())
            archive = root / "bundle.tar.gz"
            assembler.create_archive(destination, archive)
            with tarfile.open(archive) as archived:
                self.assertEqual(archived.extractfile("bundle/" + str(helper)).read(), expected)

    @unittest.skipUnless(shutil.which("jq"), "jq is required by the Newman runner")
    def test_runner_reseeds_consumed_fixture_and_stops_on_seed_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bundle = root / "bundle"
            scripts = bundle / "scripts"
            scripts.mkdir(parents=True)
            for name in ("lib.sh", "run-iems-postman.sh"):
                shutil.copy2(assembler.TEMPLATE / "scripts" / name, scripts / name)
            postman = bundle / "iems/postman"
            postman.mkdir(parents=True)
            shutil.copy2(assembler.ROOT / "scripts/release/fixtures/iems/seed_notification.py", postman)
            (postman / "iems-demo.postman_environment.json").write_text(
                '{"values":[{"key":"adminPassword","value":""}]}')
            state = bundle / "workspace/state/iems"
            state.mkdir(parents=True)
            (state / "admin-password").write_text("test-password")
            (state / "backend").write_text("sqlite")
            database = bundle / "workspace/iems-data/iems.db"
            database.parent.mkdir()
            with sqlite3.connect(database) as db:
                db.executescript("CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT); "
                                 "INSERT INTO users VALUES (7, 'demo-admin'); "
                                 "CREATE TABLE notifications (id INTEGER PRIMARY KEY, user_id INTEGER, "
                                 "title TEXT, message TEXT, type TEXT, is_read INTEGER, created_at INTEGER);")
            tools = root / "tools"
            tools.mkdir()
            # Stand in for Newman only: exercise the real shell runner and seed
            # helper. The collection consumes its unread notification fixture.
            npx = tools / "npx"
            npx.write_text("#!" + sys.executable + "\n" +
                           "import os, sqlite3\nfrom pathlib import Path\n" +
                           "with sqlite3.connect(os.environ['TEST_DB']) as db:\n" +
                           "    assert db.execute('SELECT user_id, is_read FROM notifications').fetchall() == [(7, 0)]\n" +
                           "    db.execute('DELETE FROM notifications')\n" +
                           "with Path(os.environ['TEST_CALLS']).open('a') as out: out.write('newman\\n')\n")
            npx.chmod(0o755)
            calls = root / "calls"
            env = {**os.environ, "PATH": str(tools) + os.pathsep + os.environ["PATH"],
                   "TEST_DB": str(database), "TEST_CALLS": str(calls)}
            command = ["bash", str(scripts / "run-iems-postman.sh")]
            for _ in range(2):
                result = subprocess.run(command, env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("Created a notification fixture", result.stdout)
            self.assertEqual(calls.read_text().splitlines(), ["newman", "newman"])
            with sqlite3.connect(database) as db:
                db.execute("DELETE FROM users")
            result = subprocess.run(command, env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("create demo-admin", result.stderr)
            self.assertEqual(calls.read_text().splitlines(), ["newman", "newman"])
            database.unlink()
            result = subprocess.run(command, env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Database does not exist", result.stderr)
            self.assertFalse(database.exists())

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
