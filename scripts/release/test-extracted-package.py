#!/usr/bin/env python3
"""Standalone real-host acceptance runner. Never builds, publishes, or uses a source checkout."""
import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import socket
import shutil
import sqlite3
import subprocess
import tarfile
import time
import urllib.error
import urllib.request


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def host_target(system, machine):
    return {("Darwin", "arm64"): "aarch64-apple-darwin", ("Darwin", "x86_64"): "x86_64-apple-darwin",
            ("Linux", "aarch64"): "aarch64-unknown-linux-gnu", ("Linux", "x86_64"): "x86_64-unknown-linux-gnu"}.get((system, machine))


def check_files(package):
    expected = {}
    for line in (package / "SHA256SUMS").read_text().splitlines():
        checksum, name = line.split("  ", 1)
        require(name not in expected and not PurePosixPath(name).is_absolute() and ".." not in PurePosixPath(name).parts, "Invalid checksum path")
        require(sha(package / name) == checksum, f"Checksum mismatch: {name}")
        expected[name] = checksum
    actual = {p.relative_to(package).as_posix() for p in package.rglob("*") if p.is_file()}
    require(len(expected) == 23 and actual == set(expected) | {"SHA256SUMS"}, "Unexpected package file inventory")
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True, help="New private test directory outside source checkouts")
    parser.add_argument("--machine-description", required=True, help="Operator description of the actual target machine; do not claim Docker/emulation as real-host testing")
    args = parser.parse_args()
    archive, work = args.archive.resolve(), args.work_dir.resolve()
    require(not work.exists(), "Use a new work directory; existing user data is never touched")
    for parent in [work.parent, *work.parents]:
        require(not (parent / ".git").exists() and not (parent / "pom.xml").exists() and not (parent / "Cargo.toml").exists(), "Work directory must be outside source checkouts")
    require(not Path("/.dockerenv").exists() and not Path("/run/.containerenv").exists(), "Containers do not qualify as real-target acceptance")
    if platform.system() == "Darwin":
        translated = subprocess.run(["sysctl", "-n", "sysctl.proc_translated"], capture_output=True, text=True)
        require(translated.stdout.strip() != "1", "Rosetta/emulation does not qualify as native target acceptance")
    is_wsl = platform.system() == "Linux" and "microsoft" in platform.release().lower()
    if is_wsl:
        require("wsl2" in platform.release().lower() or "microsoft-standard" in platform.release().lower(), "Use WSL2, not WSL1, for this Linux runtime test")
    os.umask(0o077)
    work.mkdir(parents=True)
    report = {"started_at": datetime.datetime.now(datetime.timezone.utc).isoformat(), "archive": archive.name,
              "archive_sha256": sha(archive), "machine": args.machine_description,
              "system": platform.system(), "machine_arch": platform.machine(), "os_release": platform.release(),
              "execution_environment": "WSL2 (not bare-metal Linux)" if is_wsl else "native target host (operator-described)",
              "runner_sha256": sha(Path(__file__)), "checks": [], "status": "RUNNING"}
    package = None
    restored = None
    manual = None
    sentinel = None
    state = work / "persistent state"
    env = {**os.environ, "JAVA_HOME": str(args.java_home.resolve()), "DCG_DATA_DIR": str(state)}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def passed(name, **evidence):
        report["checks"].append({"check": name, "status": "PASS", **evidence})
        print("PASS:", name, flush=True)

    def run(name, *arguments, expected=0):
        result = subprocess.run([str(package / "bin" / name), *arguments], env=env, cwd=work,
                                capture_output=True, text=True, timeout=220)
        require(result.returncode == expected, f"{name}: exit {result.returncode}; {result.stdout}; {result.stderr}")
        return result.stdout + result.stderr

    def http(path, payload=None, auth=False):
        request = urllib.request.Request("http://127.0.0.1:8080" + path,
                                         data=json.dumps(payload).encode() if payload is not None else None)
        if payload is not None:
            request.add_header("Content-Type", "application/json")
        if auth:
            secret = base64.b64encode(("demo:" + (state / "password").read_text()).encode()).decode()
            request.add_header("Authorization", "Basic " + secret)
        try:
            with opener.open(request, timeout=5) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def wait_until(action, message, timeout=45):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = action()
            if result:
                return result
            time.sleep(0.25)
        raise AssertionError(message)

    def contract_check(label, event):
        code, body = http("/checks", {"contractId": "orders.created", "baseVersion": "v1", "candidateVersion": "v2",
                                    "mode": "BACKWARD", "commitSha": "step6-" + label, "triggeredBy": "local-acceptance"}, True)
        require(code == 202, f"Check submission failed: {code}")
        run_id = json.loads(body)["runId"]
        def finished():
            code, body = http("/checks/" + run_id, auth=True)
            require(code == 200, "Cannot read submitted check")
            result = json.loads(body)
            return result if result["status"] in {"PASS", "FAIL", "ERROR"} else None
        result = wait_until(finished, "Authoritative check did not finish")
        require(result["status"] in {"PASS", "FAIL"}, "Authoritative check errored")
        def observation():
            for line in (state / "logs/java.log").read_text().splitlines():
                if f"event={event} " in line and f"run_id={run_id} " in line:
                    return line
            return None
        line = wait_until(observation, f"Missing {event} for {run_id}")
        passed(label, run_id=run_id, authoritative_status=result["status"], shadow_event=event,
               shadow_evidence=line)
        return {key: result[key] for key in ["status", "breakingChanges", "warnings"]}

    def clean_shutdown():
        for port in [8080, 8081]:
            with socket.socket() as sock:
                sock.settimeout(1)
                require(sock.connect_ex(("127.0.0.1", port)) != 0, f"Port {port} still listening")
        require(not list((state / "run").glob("*.pid")), "PID files remain after clean stop")
        require(not (state / "run/lock").exists(), "Launcher lock remains after stop")

    try:
        java_version = subprocess.check_output([str(args.java_home.resolve() / "bin/java"), "-version"], stderr=subprocess.STDOUT, text=True)
        require("Temurin-21.0.12.1+1" in java_version, "Acceptance requires the pinned Temurin 21.0.12.1+1 runtime")
        report["java_runtime_evidence"] = java_version
        matches = [line.split("  ", 1)[0] for line in archive.with_name("SHA256SUMS").read_text().splitlines()
                   if line.endswith("  " + archive.name)]
        require(matches == [report["archive_sha256"]], "External archive checksum mismatch")
        extraction = work / "extracted package"
        extraction.mkdir()
        with tarfile.open(archive) as tar:
            roots = set()
            for member in tar:
                path = PurePosixPath(member.name)
                require(not path.is_absolute() and ".." not in path.parts and (member.isfile() or member.isdir()), "Unsafe archive entry")
                roots.add(path.parts[0])
            require(len(roots) == 1, "Archive must have one root")
            if hasattr(tarfile, "data_filter"):
                tar.extractall(extraction, filter="data")
            else:
                # Members were checked above; only ordinary files/directories under one root.
                tar.extractall(extraction)
        package = extraction / roots.pop()
        relocated = work / "relocated and renamed demo"
        package.rename(relocated)
        package = relocated
        report["package_relocated_before_start"] = True
        checksums = check_files(package)
        info = json.loads((package / "build-info.json").read_text())
        require(info["target"] == host_target(platform.system(), platform.machine()), "Archive does not match the actual host architecture/OS")
        report["target"] = info["target"]
        passed("archive checksum, extraction and native-target match")
        no_java = work / "path without java"
        no_java.mkdir()
        for utility in ("bash", "dirname", "mkdir", "ps", "curl", "lsof"):
            (no_java / utility).symlink_to(shutil.which(utility))
        missing_env = {**env, "PATH": str(no_java)}
        missing_env.pop("JAVA_HOME", None)
        missing = subprocess.run([str(package / "bin/start")], env=missing_env, cwd=work,
                                 capture_output=True, text=True, timeout=10)
        require(missing.returncode != 0 and "Install Java 21 or set JAVA_HOME" in missing.stderr,
                "Missing Java must produce an actionable installation error")
        passed("Java absent produces actionable error", diagnostic=missing.stderr.strip())
        sentinel = subprocess.Popen(["/bin/sleep", "600"])
        require("check-compat" in run("dcg", "--help"), "CLI did not start")
        run("status", expected=1)
        run("start")
        run("status")
        listeners = subprocess.check_output(["lsof", "-nP", "-iTCP:8080", "-iTCP:8081", "-sTCP:LISTEN"], text=True)
        for port in [8080, 8081]:
            require(f"127.0.0.1:{port}" in listeners and f"*:{port}" not in listeners, "Non-loopback/missing listener")
        require(http("/ui")[0] == 401 and http("/ui", auth=True)[0] == 200, "Authentication behavior incorrect")
        passed("CLI, Java/Rust readiness, loopback listeners and authentication", listeners=listeners)
        identity = (state / "run/java.pid").read_bytes()
        require("already running" in run("start"), "Repeat start is not idempotent")
        require(identity == (state / "run/java.pid").read_bytes(), "Repeat start replaced Java")
        passed("repeat start leaves original processes running")
        baseline = contract_check("healthy AI prediction", "shadow_inference_prediction")
        subprocess.run(["bash", "-c", 'source "$1"; state_init; lock; stop_one rust', "acceptance", str(package / "bin/dcg")],
                       env=env, cwd=work, check=True, timeout=40)
        outage_status = run("status")
        require("AI advisory mode: UNAVAILABLE" in outage_status
                and "Rust advisory process: STOPPED" in outage_status,
                "Advisory outage not reported while deterministic enforcement remains active")
        outage_start = run("start")
        require("AI advisory: unavailable" in outage_start and "already running" in outage_start,
                "Repeat start did not preserve the deterministic service during an advisory outage")
        require(identity == (state / "run/java.pid").read_bytes(),
                "Advisory outage repeat start replaced Java")
        unavailable = contract_check("AI outage preserves authoritative result", "shadow_inference_call_failed")
        require(unavailable == baseline, "AI outage changed authoritative output")
        require(identity == (state / "run/java.pid").read_bytes() and http("/actuator/health")[0] == 200, "Java did not survive model outage")
        with (state / "logs/rust.log").open("ab") as log:
            restored = subprocess.Popen([str(package / "bin/dcgaimodel"), "serve-shadow-inference", "--artifact-root", str(package / "model"),
                                         "--bind", "127.0.0.1:8081"], cwd=state, stdin=subprocess.DEVNULL, stdout=log, stderr=log)
        subprocess.run(["bash", "-c", 'source "$1"; state_init; lock; remember rust "$2"; wait_ready rust http://127.0.0.1:8081/health/ready',
                        "acceptance", str(package / "bin/dcg"), str(restored.pid)], env=env, cwd=work, check=True, timeout=140)
        recovered = contract_check("AI recovers without restarting Java", "shadow_inference_prediction")
        require(recovered == baseline and identity == (state / "run/java.pid").read_bytes(), "Recovery changed Java or its authoritative output")
        password = (state / "password").read_bytes()
        sample = (state / "contracts/orders.created/v1.json").read_bytes()
        before = subprocess.check_output(["ps", "-axo", "pid=,ppid=,comm="], text=True)
        run("stop")
        after = subprocess.check_output(["ps", "-axo", "pid=,ppid=,comm="], text=True)
        require(sentinel.poll() is None, "Unrelated sentinel was stopped")
        passed("scoped shutdown preserves unrelated sentinel", sentinel_pid=sentinel.pid,
               before_processes=before, after_processes=after)
        restored.wait(timeout=5)
        clean_shutdown()
        run("stop")
        clean_shutdown()
        passed("clean and repeated shutdown releases ports and removes process records")
        for cycle in range(2):
            run("start")
            run("status")
            run("stop")
            clean_shutdown()
            run("status", expected=1)
            passed(f"additional start/stop cycle {cycle + 1}")
        with (state / "logs/rust.log").open("ab") as log:
            manual = subprocess.Popen(["./bin/dcgaimodel", "serve-shadow-inference", "--artifact-root", str(package / "model"),
                                       "--bind", "127.0.0.1:8081"], cwd=package, stdin=subprocess.DEVNULL, stdout=log, stderr=log)
        wait_until(lambda: bool(subprocess.run(["curl", "--noproxy", "*", "-fsS", "http://127.0.0.1:8081/health/ready"],
                                               capture_output=True).returncode == 0), "Manual Rust process did not become ready")
        require("Rust advisory process: NOT OWNED" in run("status", expected=1),
                "Relative-path Rust listener without a PID record was not reported")
        run("stop")
        manual.wait(timeout=10)
        clean_shutdown()
        passed("relative-path manual package Rust listener is detected and stopped")
        require(password == (state / "password").read_bytes() and sample == (state / "contracts/orders.created/v1.json").read_bytes(), "Persistent data changed unexpectedly")
        with sqlite3.connect(f"file:{state / 'checks.db'}?mode=ro", uri=True) as db:
            require(db.execute("PRAGMA integrity_check").fetchone() == ("ok",), "SQLite integrity check failed")
        require(check_files(package) == checksums, "Package was modified by execution")
        passed("persistent data integrity and immutable package files")
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"
        report["error"] = str(error)
        raise
    finally:
        try:
            if package is not None and (state / "run").is_dir():
                run("stop")
                clean_shutdown()
        except Exception as error:
            report["status"] = "FAIL"
            report["cleanup_error"] = str(error)
        if restored is not None and restored.poll() is None:
            restored.terminate()  # This Popen child belongs only to this test.
            restored.wait(timeout=10)
        if manual is not None and manual.poll() is None:
            manual.terminate()  # This Popen child belongs only to this test.
            manual.wait(timeout=10)
        if sentinel is not None and sentinel.poll() is None:
            sentinel.terminate()
            sentinel.wait(timeout=10)
        report["finished_at"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (work / "acceptance-report.json").write_text(json.dumps(report, indent=2) + "\n")
        print("Report:", work / "acceptance-report.json", flush=True)
        require(report["status"] == "PASS", "Acceptance failed; inspect the report and private logs")


if __name__ == "__main__":
    main()
