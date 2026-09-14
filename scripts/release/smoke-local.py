#!/usr/bin/env python3
"""Explicit local runtime smoke test. Requires free ports 8080/8081; retains test data/logs."""
import argparse
import base64
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True, help="New test-only directory outside the package")
    args = parser.parse_args()
    package, data = args.package.resolve(), args.data.resolve()
    if data.exists():
        raise ValueError("Smoke test requires a new data directory; existing user data is never used")
    env = {**os.environ, "JAVA_HOME": str(args.java_home.resolve()), "DCG_DATA_DIR": str(data)}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def run(name, *arguments, expected=0):
        result = subprocess.run([str(package / "bin" / name), *arguments], cwd="/tmp", env=env,
                                capture_output=True, text=True, timeout=220)
        if result.returncode != expected:
            raise AssertionError(f"{name}: exit {result.returncode}\n{result.stdout}\n{result.stderr}")
        return result.stdout

    def http(path, auth=False):
        request = urllib.request.Request("http://127.0.0.1:8080" + path)
        if auth:
            credentials = ("demo:" + (data / "password").read_text()).encode()
            request.add_header("Authorization", "Basic " + base64.b64encode(credentials).decode())
        try:
            with opener.open(request, timeout=5) as response:
                return response.status
        except urllib.error.HTTPError as error:
            return error.code

    try:
        assert "check-compat" in run("dcg", "--help")
        run("status", expected=1)
        run("start")
        assert "already running" in run("start")
        assert "java: ready" in run("status")
        listeners = subprocess.check_output(["lsof", "-nP", "-iTCP:8080", "-iTCP:8081", "-sTCP:LISTEN"], text=True)
        assert "127.0.0.1:8080" in listeners and "127.0.0.1:8081" in listeners
        assert "*:8080" not in listeners and "*:8081" not in listeners
        conflict = subprocess.run([str(package / "bin/start")], env={**env, "DCG_DATA_DIR": str(data) + "-conflict"},
                                  capture_output=True, text=True, timeout=15)
        assert conflict.returncode != 0 and "already in use" in conflict.stderr
        assert http("/ui") == 401
        assert http("/ui", auth=True) == 200
        password = (data / "password").read_bytes()
        sample = (data / "contracts/orders.created/v1.json").read_bytes()
        # Simulate model outage through the same ownership-checked stop function.
        subprocess.run(["bash", "-c", 'source "$1"; state_init; lock; stop_one rust',
                        "smoke", str(package / "bin/dcg")], env=env, check=True, timeout=40)
        assert "rust: stopped" in run("status", expected=1)
        assert http("/actuator/health") == 200
        run("stop")
        run("stop")
        run("start")
        run("status")
        assert password == (data / "password").read_bytes()
        assert sample == (data / "contracts/orders.created/v1.json").read_bytes()
        assert (data / "checks.db").is_file()
        print("PASS: CLI, paired readiness, loopback binding, port conflict, repeat start, auth, model outage, repeat stop, restart and persistence")
    finally:
        if data.exists():
            run("stop")


if __name__ == "__main__":
    main()
