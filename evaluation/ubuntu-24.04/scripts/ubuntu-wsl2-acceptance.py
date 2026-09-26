#!/usr/bin/env python3
"""Run the complete evaluator bundle flow on fresh Ubuntu 24.04 under WSL2."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import socket
import subprocess
import sys
import time


BUNDLE = Path(__file__).resolve().parents[1]
SCRIPTS = BUNDLE / "scripts"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def os_release() -> dict[str, str]:
    values: dict[str, str] = {}
    for line in Path("/etc/os-release").read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            values[key] = value.strip().strip('"')
    return values


def port_free(port: int) -> bool:
    with socket.socket() as sock:
        return sock.connect_ex(("127.0.0.1", port)) != 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    evidence = args.evidence.resolve()
    require(not evidence.exists(), f"Evidence directory already exists: {evidence}")
    evidence.mkdir(parents=True)
    checks: list[dict] = []
    cleanup: list[dict] = []
    started_dcg = started_iems = databases_started = False
    started = time.time()

    def run(name: str, command: list[str], expected: int = 0, env: dict | None = None) -> subprocess.CompletedProcess:
        begin = time.time()
        completed = subprocess.run(
            command,
            cwd=BUNDLE,
            env={**os.environ, **(env or {})},
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        (evidence / f"{len(checks) + 1:02d}-{name}.log").write_text(completed.stdout, encoding="utf-8")
        item = {
            "check": name,
            "status": "PASS" if completed.returncode == expected else "FAIL",
            "exit_code": completed.returncode,
            "expected_exit_code": expected,
            "duration_seconds": round(time.time() - begin, 3),
        }
        checks.append(item)
        require(completed.returncode == expected, f"{name} exited {completed.returncode}; inspect its private log")
        return completed

    def clean(name: str, command: list[str]) -> None:
        completed = subprocess.run(command, cwd=BUNDLE, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (evidence / f"cleanup-{name}.log").write_text(completed.stdout, encoding="utf-8")
        cleanup.append({"action": name, "status": "PASS" if completed.returncode == 0 else "FAIL"})

    report = {
        "status": "INCOMPLETE",
        "target": "Ubuntu 24.04 x86_64 under WSL2",
        "bundle": BUNDLE.name,
        "checks": checks,
        "cleanup": cleanup,
    }
    try:
        require(platform.system() == "Linux", "Linux is required")
        require(platform.machine() == "x86_64", "Linux x86-64 is required")
        release = os_release()
        require(release.get("ID") == "ubuntu" and release.get("VERSION_ID") == "24.04", "Ubuntu 24.04 is required")
        require("microsoft" in Path("/proc/sys/kernel/osrelease").read_text(encoding="utf-8").lower(), "WSL2 is required")
        for port in (8080, 8081, 8090, 54329, 33069):
            require(port_free(port), f"Port {port} is already occupied")

        run("verify-install", [str(SCRIPTS / "verify-install.sh")])
        run("initialize-workspace", [str(SCRIPTS / "init-workspace.sh")])
        target = BUNDLE / "workspace" / "contracts" / "orders.created"
        require(not target.exists(), "orders.created already exists in the clean workspace")
        shutil.copytree(BUNDLE / "examples" / "contracts" / "orders.created", target)
        run("sqlite-contract-check", [str(SCRIPTS / "check-contract.sh"), "orders.created", "BACKWARD", "sqlite"])

        run("start-dcg-no-ai", [str(SCRIPTS / "start-dcg.sh")], env={"DCG_AI_ENABLED": "false"})
        started_dcg = True
        status = run("status-dcg-no-ai", [str(SCRIPTS / "status-dcg.sh")])
        require("Deterministic enforcement: ACTIVE" in status.stdout and "AI advisory mode: DISABLED" in status.stdout,
                "No-AI status did not report active deterministic enforcement")
        run("stop-dcg-no-ai", [str(SCRIPTS / "stop-dcg.sh")])
        started_dcg = False

        run("start-dcg-ai", [str(SCRIPTS / "start-dcg.sh")], env={"DCG_AI_ENABLED": "true"})
        started_dcg = True
        status = run("status-dcg-ai", [str(SCRIPTS / "status-dcg.sh")])
        require("Deterministic enforcement: ACTIVE" in status.stdout and "AI advisory mode: AVAILABLE" in status.stdout,
                "AI advisory did not become available")

        run("start-iems-sqlite", [str(SCRIPTS / "start-iems.sh"), "sqlite"])
        started_iems = True
        run("newman-iems-api", [str(SCRIPTS / "run-iems-postman.sh")])
        run("stop-iems-sqlite", [str(SCRIPTS / "stop-iems.sh")])
        started_iems = False

        run("database-up", [str(SCRIPTS / "database-up.sh")])
        databases_started = True
        run("postgres-contract-check", [str(SCRIPTS / "check-contract.sh"), "orders.created", "BACKWARD", "postgres"])
        run("mysql-contract-check", [str(SCRIPTS / "check-contract.sh"), "orders.created", "BACKWARD", "mysql"])
        for store in ("postgres", "mysql"):
            run(f"start-iems-{store}", [str(SCRIPTS / "start-iems.sh"), store])
            started_iems = True
            run(f"stop-iems-{store}", [str(SCRIPTS / "stop-iems.sh")])
            started_iems = False

        run("stop-dcg-ai", [str(SCRIPTS / "stop-dcg.sh")])
        started_dcg = False
        run("database-down", [str(SCRIPTS / "database-down.sh")])
        databases_started = False
        run("remove-disposable-database-volumes", [str(SCRIPTS / "database-reset-demo-data.sh"), "--confirm-disposable-data"])

        for port in (8080, 8081, 8090, 54329, 33069):
            require(port_free(port), f"Port {port} remained occupied after cleanup")
        report["status"] = "PASS"
        return_code = 0
    except Exception as exc:
        report["error"] = f"{type(exc).__name__}: {exc}"
        return_code = 1
    finally:
        if started_iems:
            clean("stop-iems", [str(SCRIPTS / "stop-iems.sh")])
        if started_dcg:
            clean("stop-dcg", [str(SCRIPTS / "stop-dcg.sh")])
        if databases_started:
            clean("database-down", [str(SCRIPTS / "database-down.sh")])
        report["duration_seconds"] = round(time.time() - started, 3)
        (evidence / "results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"{report['status']}: {evidence / 'results.json'}")
    return return_code


if __name__ == "__main__":
    sys.exit(main())
