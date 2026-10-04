#!/usr/bin/env python3
"""Native ARM64 Milestone 1: deterministic DCG and AI advisory, no external services."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import signal
import socket
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request

BUNDLE = Path(__file__).resolve().parents[1]
SCRIPTS = BUNDLE / "scripts"
STATE = BUNDLE / "workspace"
# Imports must never create __pycache__ inside the immutable extracted bundle.
sys.dont_write_bytecode = True
sys.path.insert(0, str(SCRIPTS))
from memory_telemetry import MemorySampler, POLICY, classify_memory, exit_code, memory_snapshot


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def port_free(port):
    with socket.socket() as sock:
        sock.settimeout(1)
        return sock.connect_ex(("127.0.0.1", port)) != 0


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory():
    return {str(p.relative_to(BUNDLE)): digest(p) for p in BUNDLE.rglob("*")
            if p.is_file() and p.relative_to(BUNDLE).parts[0] != "workspace"}


def output(command):
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT, timeout=15).strip()



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True, help="New private directory outside the bundle")
    args = parser.parse_args()
    evidence = args.evidence.resolve()
    require(not evidence.exists(), "Evidence directory already exists")
    require(not evidence.is_relative_to(BUNDLE), "Evidence must be outside the immutable bundle")
    os.umask(0o077)
    evidence.mkdir(parents=True)
    report = {"status": "INCOMPLETE", "scope": "milestone-1", "target": "aarch64-apple-darwin",
              "schema_version": 2, "checks": [], "cleanup": [], "bundle_info_sha256": digest(BUNDLE / "bundle-info.json")}
    started_at = time.monotonic()
    pending_cleanup = False
    sampler = None
    sentinel = None
    env = dict(os.environ)
    if env.get("JAVA_HOME"):
        env["PATH"] = env["JAVA_HOME"] + "/bin:" + env["PATH"]
    env.setdefault("JAVA_TOOL_OPTIONS", "-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m")
    env["DCG_DATA_DIR"] = str(STATE)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def passed(name, **data):
        report["checks"].append({"check": name, "status": "PASS", **data})

    def run(name, command, expected=0, extra=None):
        begin = time.monotonic()
        process = subprocess.Popen(command, cwd=BUNDLE, env={**env, **(extra or {})},
                                   text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            if sampler is not None:
                sampler.register(process.pid, name)
            try:
                stdout, _ = process.communicate(timeout=360)
            except (subprocess.TimeoutExpired, KeyboardInterrupt):
                process.terminate()
                try:
                    process.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate()
                raise
        finally:
            if sampler is not None:
                sampler.capture()
                sampler.unregister(process.pid)
        (evidence / f"{len(report['checks']) + 1:02d}-{name}.log").write_text(stdout)
        require(process.returncode == expected, f"{name}: expected exit {expected}, got {process.returncode}")
        passed(name, exit_code=process.returncode, expected_exit_code=expected,
               duration_seconds=round(time.monotonic() - begin, 3))
        return stdout

    def shell(name, script, expected=0, extra=None):
        return run(name, ["/bin/bash", str(SCRIPTS / script)], expected, extra)

    def http(path, payload=None, auth=True):
        request = urllib.request.Request("http://127.0.0.1:8080" + path,
                                        data=json.dumps(payload).encode() if payload is not None else None)
        if payload is not None:
            request.add_header("Content-Type", "application/json")
        if auth:
            token = base64.b64encode(("demo:" + (STATE / "password").read_text().strip()).encode()).decode()
            request.add_header("Authorization", "Basic " + token)
        try:
            with opener.open(request, timeout=10) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def service_check(ai):
        code, body = http("/checks", {"contractId": "orders.created", "baseVersion": "v1",
                                     "candidateVersion": "v2", "mode": "BACKWARD",
                                     "commitSha": "macos-milestone-1", "triggeredBy": "acceptance"})
        require(code == 202, f"Contract submission failed: HTTP {code}")
        run_id = json.loads(body)["runId"]
        deadline = time.monotonic() + 45
        result = None
        while time.monotonic() < deadline:
            code, body = http("/checks/" + run_id)
            require(code == 200, "Cannot read submitted check")
            result = json.loads(body)
            if result["status"] in ("PASS", "FAIL", "ERROR"):
                break
            time.sleep(0.25)
        require(result and result["status"] == "PASS", "Bundled deterministic contract check did not pass")
        if ai:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                lines = (STATE / "logs/java.log").read_text().splitlines()
                if any("event=shadow_inference_prediction " in line and f"run_id={run_id} " in line for line in lines):
                    break
                time.sleep(0.25)
            else:
                raise RuntimeError("AI is ready but no advisory prediction was observed for the check")
        passed("service-contract-check-ai" if ai else "service-contract-check-no-ai", run_id=run_id,
               authoritative_status=result["status"], advisory_prediction_observed=ai)
        return run_id, {k: result[k] for k in ("status", "breakingChanges", "warnings")}

    def listeners(ai):
        expected = {8080: "java"}
        if ai:
            expected[8081] = "rust"
        for port, role in expected.items():
            lines = output(["lsof", "-nP", "-iTCP:" + str(port), "-sTCP:LISTEN", "-FpPn"])
            names = [line[1:] for line in lines.splitlines() if line.startswith("n")]
            pid = (STATE / "run" / f"{role}.pid").read_text().splitlines()[0]
            pids = {line[1:] for line in lines.splitlines() if line.startswith("p")}
            require(names and all(n == f"127.0.0.1:{port}" for n in names) and pids == {pid},
                    f"Port {port} is not exclusively the tracked loopback service")
        if not ai:
            require(port_free(8081) and not (STATE / "run/rust.pid").exists(), "Rust started in no-AI mode")

    def stopped():
        require(all(port_free(p) for p in (8080, 8081)), "Service ports remain occupied")
        require(not any((STATE / "run" / f"{r}.pid").exists() for r in ("java", "rust")), "PID files remain")
        require(sentinel.poll() is None, "Shutdown killed unrelated sentinel")

    def interrupted(signum, frame):
        raise KeyboardInterrupt(f"Interrupted by signal {signum}")

    previous_term = signal.signal(signal.SIGTERM, interrupted)
    try:
        require(platform.system() == "Darwin" and platform.machine() == "arm64", "Native Darwin/arm64 required")
        require(" " in str(BUNDLE), "Extract into a path containing spaces before acceptance")
        require(not any((p / ".git").exists() for p in (BUNDLE, *BUNDLE.parents)), "Extract outside source checkouts")
        require(not STATE.exists(), "Use a fresh extraction: workspace already exists")
        require(all(port_free(p) for p in (8080, 8081)), "Ports 8080/8081 must be free before acceptance")
        shell("verify-install", "verify-install.sh")
        before = inventory()
        ram = int(output(["sysctl", "-n", "hw.memsize"]))
        report["host"] = {"macos": output(["sw_vers"]), "model": output(["sysctl", "-n", "hw.model"]),
                          "architecture": platform.machine(), "ram_bytes": ram,
                          "is_8gb_target": ram == 8 * 1024**3,
                          "bash": output(["/bin/bash", "--version"]).splitlines()[0],
                          "java": subprocess.check_output(["java", "-version"], env=env, stderr=subprocess.STDOUT, text=True)}
        report["jvm_options"] = env["JAVA_TOOL_OPTIONS"]
        sampler = MemorySampler(STATE)
        sampler.thread.start()
        print("Collecting 30-second memory baseline (read-only)...", flush=True)
        time.sleep(POLICY["baseline_seconds"])
        report["memory_before"] = sampler.begin_workload()
        print("Running deterministic and AI-enabled acceptance...", flush=True)
        for case, expected in (("pass", 0), ("fail", 1)):
            text = run("cli-" + case, ["/bin/bash", str(BUNDLE / "dcg/bin/dcg"), "check-compat",
                        "--base", str(BUNDLE / "fixtures/base.json"), "--candidate", str(BUNDLE / f"fixtures/{case}.json"),
                        "--mode", "BACKWARD"], expected)
            require("Schema compatibility: " + ("PASS" if expected == 0 else "FAIL") in text,
                    "CLI exit did not match a deterministic result")
        sentinel = subprocess.Popen(["/bin/sleep", "600"])
        pending_cleanup = True  # Set before startup, including partial failures.
        shell("start-no-ai", "start-dcg.sh", extra={"DCG_AI_ENABLED": "false"})
        status = shell("status-no-ai", "status-dcg.sh")
        require("Deterministic enforcement: ACTIVE" in status and "AI advisory mode: DISABLED" in status, "Wrong no-AI status")
        listeners(False)
        require(http("/ui", auth=False)[0] == 401 and http("/ui")[0] == 200, "Authentication check failed")
        passed("loopback-authentication-and-no-rust")
        old_identity = (STATE / "run/java.pid").read_text()
        shell("repeat-start-no-ai", "start-dcg.sh", extra={"DCG_AI_ENABLED": "false"})
        require((STATE / "run/java.pid").read_text() == old_identity, "Repeat start changed Java identity")
        passed("repeat-start-pid-stable")
        run_id, deterministic = service_check(False)
        retained = {str(p.relative_to(STATE)): digest(p) for p in (STATE / "contracts").rglob("*") if p.is_file()}
        retained["password"] = digest(STATE / "password")
        shell("stop-no-ai", "stop-dcg.sh")
        stopped()
        pending_cleanup = False
        shell("repeat-stop", "stop-dcg.sh")
        shell("status-stopped", "status-dcg.sh", expected=1)
        pending_cleanup = True
        shell("restart-with-ai", "start-dcg.sh", extra={"DCG_AI_ENABLED": "true"})
        status = shell("status-ai", "status-dcg.sh")
        require("Deterministic enforcement: ACTIVE" in status and "AI advisory mode: AVAILABLE" in status, "AI unavailable")
        listeners(True)
        require(http("/checks/" + run_id)[0] == 200, "Check history did not survive restart")
        require(all(digest(STATE / p) == h for p, h in retained.items()), "Credentials/contracts changed across restart")
        passed("restart-retains-history-credentials-contracts")
        _, with_ai = service_check(True)
        require(with_ai == deterministic, "AI changed the authoritative deterministic result")
        passed("ai-preserves-deterministic-result")
        time.sleep(2)  # Obtain steady-state samples for both tracked processes.
        shell("stop-ai", "stop-dcg.sh")
        stopped()
        pending_cleanup = False
        with sqlite3.connect(f"file:{STATE / 'checks.db'}?mode=ro", uri=True) as db:
            require(db.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "Embedded SQLite integrity failed")
        passed("scoped-shutdown-and-embedded-state-integrity")
        shell("verify-final-hashes", "verify-install.sh")
        require(before == inventory(), "Package inventory or bytes changed")
        passed("immutable-package")
        report["status"] = "PASS"
    except (Exception, KeyboardInterrupt) as exc:
        report["status"] = "FAIL"
        report["error"] = f"{type(exc).__name__}: {exc}"
        report["checks"].append({"check": "acceptance-error", "status": "FAIL", "error": report["error"]})
    finally:
        if pending_cleanup:
            try:
                result = subprocess.run(["/bin/bash", str(SCRIPTS / "stop-dcg.sh")], cwd=BUNDLE,
                                        env=env, capture_output=True, text=True, timeout=90)
                (evidence / "cleanup.log").write_text(result.stdout + result.stderr)
                ok = result.returncode == 0 and all(port_free(p) for p in (8080, 8081))
                report["cleanup"].append({"action": "stop-partial-or-running-dcg", "status": "PASS" if ok else "FAIL"})
                if not ok:
                    report["status"] = "FAIL"
            except Exception as exc:
                report["cleanup"].append({"action": "stop-dcg", "status": "FAIL", "error": str(exc)})
                report["status"] = "FAIL"
        if sentinel is not None:
            sentinel.terminate()
            sentinel.wait(timeout=5)
        if sampler is not None:
            report["telemetry"] = sampler.finish()
        if platform.system() == "Darwin":
            try:
                report["memory_after"] = memory_snapshot()
            except Exception as exc:
                report["measurement_error"] = str(exc)
        report["functional_status"] = report["status"]
        report.update(classify_memory(report.get("telemetry"), report.get("memory_before"),
                                      report.get("memory_after"), report["functional_status"],
                                      report.get("host", {}).get("is_8gb_target", False)))
        report["duration_seconds"] = round(time.monotonic() - started_at, 3)
        (evidence / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        signal.signal(signal.SIGTERM, previous_term)
        print(f"{report['status']}: {evidence / 'results.json'}")
        print(f"Functional: {report['functional_status']}; memory: {report['memory_qualification']}; "
              f"8GB qualification: {report['eight_gb_qualification']}")
    return exit_code(report["status"])


if __name__ == "__main__":
    sys.exit(main())
