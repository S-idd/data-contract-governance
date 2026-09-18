"""Opt-in real Java/SQLite rehearsal; never launches the Rust model.

DCG_TEST_PACKAGE=/path/to/extracted/package IEMS_TEST_ROOT=/path/to/iems \
  python3 -m unittest discover -s scripts/release -p test_no_ai_foundation.py -v
Uses temporary package/project copies and requires Java 21 and free port 8080.
"""
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import time
import unittest
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = os.environ.get("DCG_TEST_PACKAGE")
IEMS = os.environ.get("IEMS_TEST_ROOT")
VERSION = "4.0.0-rc.1"


@unittest.skipUnless(PACKAGE and IEMS, "Set DCG_TEST_PACKAGE and IEMS_TEST_ROOT for live Java tests")
class NoAiFoundationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="dcg no ai ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.package = self.root / "package"
        self.data = self.root / "state"
        self.package.mkdir()
        launcher_root = Path(PACKAGE) if os.environ.get("DCG_TEST_INSTALLED_LAUNCHERS") == "true" else ROOT / "packaging/local"
        (self.package / "bin").mkdir()
        for name in ("dcg", "start", "status", "stop"):
            shutil.copy2(launcher_root / "bin" / name, self.package / "bin" / name)
        shutil.copytree(launcher_root / "config", self.package / "config")
        shutil.copytree(Path(PACKAGE) / "contracts", self.package / "contracts")
        (self.package / "lib").mkdir()
        for name in (f"contract-cli-{VERSION}-all.jar", f"contract-service-{VERSION}.jar"):
            shutil.copyfile(Path(PACKAGE) / "lib" / name, self.package / "lib" / name)
        self.env = {**os.environ, "DCG_HOME": str(self.package), "DCG_DATA_DIR": str(self.data),
                    "DCG_AI_ENABLED": "false", "SHADOW_INFERENCE_ENABLED": "true"}
        if self.env.get("JAVA_HOME"):
            self.env["PATH"] = self.env["JAVA_HOME"] + "/bin" + os.pathsep + self.env["PATH"]
        self.evidence = Path(os.environ.get("DCG_TEST_EVIDENCE", ROOT / "target/phase1-no-ai")) / self._testMethodName
        self.evidence.mkdir(parents=True, exist_ok=True)
        self.commands = []
        self.addCleanup(self.save_evidence)

    def save_evidence(self):
        if (self.data / "logs/java.log").exists():
            shutil.copyfile(self.data / "logs/java.log", self.evidence / "java.log")
        (self.evidence / "commands.json").write_text(json.dumps(self.commands, indent=2))

    def command(self, args, expected=0, env=None):
        result = subprocess.run([str(x) for x in args], env=env or self.env,
                                capture_output=True, text=True, timeout=100)
        self.commands.append({"command": [str(x) for x in args], "exit": result.returncode,
                              "stdout": result.stdout, "stderr": result.stderr})
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def test_package_lifecycle_without_ai_files_and_with_occupied_ai_port(self):
        # No Rust binary or model directory exists in this package copy.
        self.assertFalse((self.package / "bin/dcgaimodel").exists())
        self.assertFalse((self.package / "model").exists())
        with socket.socket() as occupied:
            occupied.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            occupied.bind(("127.0.0.1", 8081))
            occupied.listen()
            self.addCleanup(lambda: self.command([self.package / "bin/stop"]))
            result = self.command([self.package / "bin/start"])
            self.assertIn("no Rust model process started", result.stdout)
            self.assertFalse((self.data / "run/rust.pid").exists())
            self.assertFalse((self.data / "logs/rust.log").exists())
            pid = (self.data / "run/java.pid").read_text().splitlines()[0]
            process = self.command(["ps", "-p", pid, "-o", "args="]).stdout
            self.assertIn("--shadow.inference.enabled=false", process)
            self.command([self.package / "bin/start"])
            # Status/stop must use saved instance mode even without the env flag.
            default_env = dict(self.env)
            default_env.pop("DCG_AI_ENABLED")
            status = self.command([self.package / "bin/status"], env=default_env)
            self.assertIn("rust: disabled", status.stdout)
            self.command([self.package / "bin/start"], expected=1, env=default_env)
            self.command([self.package / "bin/stop"], env=default_env)
            occupied.getsockname()  # An unrelated AI-port listener is untouched.
            self.command([self.package / "bin/start"], expected=1,
                         env={**self.env, "DCG_AI_ENABLED": "invalid"})
        # Default AI-enabled mode is advisory-safe even without a Rust binary.
        result = self.command([self.package / "bin/start"], env=default_env)
        self.assertIn("AI advisory: unavailable", result.stdout)
        self.command([self.package / "bin/status"], env=default_env)
        self.command([self.package / "bin/stop"], env=default_env)

    def stage_iems(self, breaking=False):
        app = self.root / "iems"
        app.mkdir()
        source = Path(IEMS)
        shutil.copytree(source / "scripts", app / "scripts", ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copytree(source / "contracts", app / "contracts")
        (app / "target").mkdir()
        jar = app / "target/inclusive-education-management-system-1.0.0-SNAPSHOT.jar"
        shutil.copyfile(source / "target" / jar.name, jar)
        if breaking:
            shutil.copyfile(source / "scripts/dcg/fixtures/breaking.json",
                            app / "contracts/iems.enrollment/candidate.json")
        self.env.update({"GIT_DIR": str(source / ".git"),
                         "DCG_SQLITE_PATH": str(self.root / "checks.db"),
                         "IEMS_JDBC_URL": "jdbc:sqlite:" + str(self.root / "iems.db"),
                         "IEMS_JWT_SECRET": "phase1-test-secret-" * 4,
                         "IEMS_DEMO_ADMIN_PASSWORD": "phase1-local-test-password"})
        return app

    def check_history(self, breaking):
        with sqlite3.connect(self.env["DCG_SQLITE_PATH"]) as db:
            rows = db.execute("SELECT contract_id,status FROM check_runs ORDER BY contract_id").fetchall()
        self.assertEqual(len(rows), 4)
        self.assertEqual([x for x in rows if x[1] == "FAIL"],
                         [("iems.enrollment", "FAIL")] if breaking else [])
        (self.evidence / "check-results.json").write_text(json.dumps(rows, indent=2))

    def run_iems_dispatch(self, breaking):
        app = self.stage_iems(breaking)
        tools = self.root / "tools"
        tools.mkdir()
        marker = self.root / "java-started"
        real_java = shutil.which("java", path=self.env["PATH"])
        # Real packaged CLI/engine; only the final IEMS process is a dispatch probe.
        shim = tools / "java"
        shim.write_text('#!/bin/bash\nif [[ "$*" == *inclusive-education-management-system* ]]; then\n'
                        '  printf started > "$JAVA_STARTED_MARKER"\n  exit 0\nfi\n'
                        'exec "$REAL_JAVA" "$@"\n')
        shim.chmod(0o755)
        self.env.update({"PATH": str(tools) + os.pathsep + self.env["PATH"],
                         "JAVA_STARTED_MARKER": str(marker), "REAL_JAVA": real_java})
        # Tripwire: any attempted Rust invocation fails and leaves proof.
        rust = self.package / "bin/dcgaimodel"
        rust.write_text('#!/bin/sh\nprintf attempted > "$RUST_ATTEMPT_MARKER"\nexit 99\n')
        rust.chmod(0o755)
        self.env["RUST_ATTEMPT_MARKER"] = str(self.root / "rust-attempted")
        self.command([app / "scripts/database/run_demo.sh", "sqlite"], expected=1 if breaking else 0)
        self.assertEqual(marker.exists(), not breaking)
        self.assertFalse((self.root / "rust-attempted").exists())
        self.check_history(breaking)
        (self.evidence / "process-proof.json").write_text(json.dumps({
            "iems_dispatch_probe_started": marker.exists(), "rust_invocation_attempted": False,
            "model_artifacts_present": False}))

    def test_compatible_dispatch_exits_zero_without_ai(self):
        self.run_iems_dispatch(False)

    def test_breaking_blocks_iems_dispatch_without_ai(self):
        self.run_iems_dispatch(True)

    def test_breaking_blocks_iems_dispatch_with_ai_requested_but_unavailable(self):
        self.env["DCG_AI_ENABLED"] = "true"
        self.run_iems_dispatch(True)

    def test_real_iems_starts_without_ai_artifacts(self):
        app = self.stage_iems()
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            port = available.getsockname()[1]
        self.env["IEMS_PORT"] = str(port)
        log_path = self.evidence / "iems.log"
        with log_path.open("w") as log:
            process = subprocess.Popen([str(app / "scripts/database/run_demo.sh"), "sqlite"],
                                       env=self.env, stdout=log, stderr=log)
            try:
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                for _ in range(120):
                    self.assertIsNone(process.poll(), log_path.read_text())
                    try:
                        with opener.open(f"http://127.0.0.1:{port}/actuator/health", timeout=1) as response:
                            if response.status == 200:
                                break
                    except OSError:
                        pass
                    time.sleep(.5)
                else:
                    self.fail("IEMS did not become healthy: " + log_path.read_text())
                args = self.command(["ps", "-p", str(process.pid), "-o", "args="]).stdout
                self.assertIn("inclusive-education-management-system", args)
                self.check_history(False)
            finally:
                process.terminate()
                process.wait(timeout=30)
                (self.evidence / "process-proof.json").write_text(json.dumps({
                    "pid": process.pid, "intentional_shutdown_exit": process.returncode,
                    "rust_binary_present": False, "model_directory_present": False}))

    def test_cli_missing_ai_files_preserves_pass_and_fail(self):
        for name, expected in (("compatible", 0), ("breaking", 1)):
            self.command([self.package / "bin/dcg", "check-compat", "--base",
                          Path(IEMS) / "contracts/iems.enrollment/v1.json", "--candidate",
                          Path(IEMS) / f"scripts/dcg/fixtures/{name}.json"], expected=expected)


if __name__ == "__main__":
    unittest.main()
