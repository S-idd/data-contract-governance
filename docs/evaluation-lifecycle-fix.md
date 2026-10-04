# Evaluation lifecycle investigation

The Ubuntu acceptance runner invokes `scripts/start-dcg.sh` with captured stdout/stderr
and `DCG_AI_ENABLED=false`. That wrapper sets `DCG_DATA_DIR` to the bundle workspace
and execs the packaged launcher. There is no separate Python HTTP poll. Both manual
and acceptance startup poll `http://127.0.0.1:8080/actuator/health`; Java is explicitly
bound to IPv4 loopback on port 8080. `nohup` execs Java at the recorded PID, without an
intermediate shell or a launcher-created descendant tree. Shutdown must target that
process, not a process group or whichever PID happens to own a port.

The previous probe already supplied `--noproxy '*'`. Docker daemon proxy settings do
not establish the calling shell's proxy environment, and inherited proxy variables
alone do not reproduce this failure. The user's precise readiness cause remains
unconfirmed without the failing bundle's probe output/environment. A reproducible
probe failure is user curl configuration changing the request method: the old helper
fails against a healthy server; `curl -q` succeeds. Probes now ignore curlrc, explicitly
use IPv4, enforce elapsed-time budgets, return early on lost process identity, and
include a final direct HTTP diagnostic on timeout. Full-width `ps` output avoids
identity checks depending on terminal width or captured-output behavior. The final
one-second diagnostic can recognize late readiness rather than killing that service.
No application behavior was changed.

Previously stop sent TERM, waited 30 iterations, and failed without escalation. A
Spring web-server graceful-shutdown log does not prove JVM shutdown hooks have
finished or the process has exited. Stop now waits on saved process start identity
(independent of changing argv), revalidates ownership before KILL after 30 seconds,
and allows five seconds for exit. It does not kill descendants or unrelated port
owners. Zombies count as exited because they cannot retain listeners. Failed exit
keeps the identity record, and the public stop command still rejects a remaining
8080 listener. Acceptance now attempts cleanup even when DCG startup partially fails.
The particular JVM hook behind the reported hang has not been identified; no hook
or application code has been changed.

## Re-verification

Apply the lifecycle commit to the evaluation branch, preserving the separate Newman
fixture fix. Rebuild the DCG package **and** evaluation archive using
`docs/ubuntu-24.04-evaluation-build.md`; changing only the acceptance script or
reusing the previous DCG archive will not update the launcher. Use fresh output and
extraction directories so checksum manifests describe the actual patched bytes.

From the newly extracted evaluation bundle:

```bash
python3 scripts/ubuntu-wsl2-acceptance.py --evidence /absolute/new/private-evidence
```

Require a PASS report, both no-AI and AI stop checks passing, and no remaining owned
listeners on 8080/8081. If startup fails, retain the start log (now including the
HTTP diagnostic), `workspace/logs/java.log`, and `results.json`. A KILL fallback is
explicitly reported, so successful cleanup does not conceal a slow JVM shutdown.

Targeted source checks:

```bash
python3 -m unittest discover -s scripts/release -p test_launcher_lifecycle.py -v
python3 -m unittest discover -s scripts/release -p test_local_packaging.py -v
python3 -m unittest discover -s scripts/release -p test_ubuntu_evaluation.py -v
```

Set `DCG_TEST_SERVICE_JAR` to an existing service JAR to additionally run two real Java
start/status/stop cycles with captured output and inherited proxy variables. The test
uses a temporary package/data directory and skips if port 8080 is occupied.

## Validation performed

- Six socket/process regression tests passed on macOS and Debian Linux ARM64.
- Seven lifecycle tests, including two real DCG Java start/status/stop cycles, passed
  in an isolated Ubuntu 22.04 ARM64 container with Temurin 21.0.12+8.
- All 29 local packaging tests and all five evaluation-template tests passed.
- Native macOS real-Java test skipped because port 8080 was occupied; that listener
  was not touched.

This is not Ubuntu 24.04 x86-64 WSL2 acceptance, and does not validate the exact
Temurin 21.0.12.1+1 build or the user's 5 GB / 2 CPU resource configuration. The full
Docker/IEMS/evaluation flow must still be run on the target WSL2 host.
