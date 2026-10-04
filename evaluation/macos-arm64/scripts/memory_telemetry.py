"""Read-only macOS memory sampling and versioned Milestone 1 qualification policy."""
from __future__ import annotations

import json
import math
import os
from pathlib import Path
import re
import subprocess
import threading
import time

MIB = 1024**2
POLICY = {
    "version": "macos-m1-memory-v1",
    "baseline_seconds": 30,
    "sample_interval_seconds": 1,
    "max_sample_gap_seconds": 2.5,
    "rss_pass_max_bytes": 1024 * MIB,
    "rss_fail_min_bytes": 1536 * MIB,
    "swap_pass_max_bytes": 128 * MIB,
    "swap_fail_min_bytes": 256 * MIB,
    "swapout_pass_max_bytes": 128 * MIB,
    "swapout_fail_min_bytes": 256 * MIB,
    "warning_fail_seconds": 5,
    "critical_fail_observations": 1,
    "basis": "Conservative project smoke-test budgets, not Apple limits or a sustained-load certification",
}


def output(command):
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT, timeout=5,
                                   env={**os.environ, "LC_ALL": "C"}).strip()


def parse_memory(pressure, swap, vm_stat, pressure_level):
    level = int(pressure_level.strip())
    if level not in (1, 2, 4):
        raise ValueError(f"Unknown macOS pressure level: {level}")
    used = re.search(r"\bused\s*=\s*([\d.]+)([KMGT])\b", swap)
    page = re.search(r"page size of (\d+) bytes", vm_stat)
    if not used or not page:
        raise ValueError("Cannot parse swap used or VM page size")
    counters = {}
    for line in vm_stat.splitlines()[1:]:
        match = re.fullmatch(r'\s*([^:]+):\s*(\d+)\.\s*', line)
        if match:
            counters[match[1].strip().strip('"')] = int(match[2])
    if "Swapouts" not in counters:
        raise ValueError("vm_stat is missing Swapouts")
    return {"pressure": pressure, "swap": swap, "vm_stat": vm_stat,
            "pressure_level": level, "pressure_state": {1: "normal", 2: "warn", 4: "critical"}[level],
            "swap_used_bytes": round(float(used[1]) * 1024**("KMGT".index(used[2]) + 1)),
            "page_size_bytes": int(page[1]), "vm_counters": counters}


def memory_snapshot():
    # Read-only: never invoke the memory_pressure -l/-p/-S stress modes.
    return parse_memory(output(["memory_pressure", "-Q"]), output(["sysctl", "vm.swapusage"]),
                        output(["vm_stat"]), output(["sysctl", "-n", "kern.memorystatus_vm_pressure_level"]))


def process_snapshot():
    """One ps process-table snapshot; omit full command arguments and credentials."""
    rows = {}
    for line in output(["ps", "-axo", "pid=,ppid=,rss=,lstart=,comm="]).splitlines():
        fields = line.split(None, 8)
        if len(fields) != 9:
            raise ValueError("Cannot parse ps process row")
        pid, ppid, rss = map(int, fields[:3])
        rows[pid] = {"pid": pid, "ppid": ppid, "rss_kib": rss,
                     "start": " ".join(fields[3:8]), "command": fields[8]}
    return rows


def select_workload(rows, roots, known):
    """Keep PID/start identity through reparenting, reject recycled PIDs, deduplicate."""
    selected = {}
    for pid, (start, phase) in {**known, **roots}.items():
        if pid in rows and rows[pid]["start"] == start:
            selected[pid] = phase
    changed = True
    while changed:
        changed = False
        for pid, row in rows.items():
            if pid not in selected and row["ppid"] in selected:
                selected[pid] = selected[row["ppid"]]
                changed = True
    processes = []
    identities = {}
    for pid, phase in sorted(selected.items()):
        row = rows[pid]
        name = Path(row["command"]).name
        role = "cli" if name == "java" and phase.startswith("cli-") else (
            "java" if name == "java" else "rust" if name == "dcgaimodel" else "helper")
        processes.append({**row, "role": role, "origin_phase": phase})
        identities[pid] = (row["start"], phase)
    return processes, identities


class MemorySampler:
    def __init__(self, state):
        self.state = Path(state)
        self.samples = []
        self.errors = []
        self.roots = {}
        self.known = {}
        self.phase = "baseline"
        self.started = time.monotonic()
        self.baseline_seconds = 0.0
        self.lock = threading.RLock()
        self.done = threading.Event()
        self.thread = threading.Thread(target=self.collect, daemon=True)

    def capture(self):
        with self.lock:
            try:
                begin = time.monotonic()
                rows = process_snapshot()
                roots = dict(self.roots)
                # nohup services can reparent before a sample; launcher identities cover that case.
                for role in ("java", "rust"):
                    path = self.state / "run" / f"{role}.pid"
                    try:
                        lines = path.read_text().splitlines()
                    except FileNotFoundError:
                        continue
                    if len(lines) >= 2:
                        roots[int(lines[0])] = (" ".join(lines[1].split()), "service-" + role)
                processes, self.known = select_workload(rows, roots, self.known)
                memory = memory_snapshot()
                self.samples.append({"time": time.time(), "elapsed_seconds": begin - self.started,
                                     "phase": self.phase, "collection_seconds": time.monotonic() - begin,
                                     "combined_rss_kib": sum(p["rss_kib"] for p in processes),
                                     "processes": processes, "memory": memory})
            except Exception as exc:
                self.errors.append({"elapsed_seconds": time.monotonic() - self.started,
                                    "phase": self.phase, "error": f"{type(exc).__name__}: {exc}"})

    def register(self, pid, phase):
        with self.lock:
            try:
                rows = process_snapshot()
                if pid in rows:
                    self.roots[pid] = (rows[pid]["start"], phase)
            except Exception as exc:
                self.errors.append({"phase": phase, "error": str(exc)})
            self.capture()

    def unregister(self, pid):
        with self.lock:
            self.roots.pop(pid, None)

    def begin_workload(self):
        with self.lock:
            self.baseline_seconds = time.monotonic() - self.started
            self.phase = "workload"
            self.capture()
            return self.samples[-1]["memory"] if self.samples and self.samples[-1]["phase"] == "workload" else None

    def collect(self):
        while not self.done.is_set():
            begin = time.monotonic()
            self.capture()
            self.done.wait(max(0, POLICY["sample_interval_seconds"] - (time.monotonic() - begin)))

    def finish(self):
        self.done.set()
        self.thread.join(timeout=30)
        if self.thread.is_alive():
            self.errors.append({"error": "Sampler failed to stop"})
        else:
            self.capture()
        return {"policy_version": POLICY["version"], "baseline_seconds": self.baseline_seconds,
                "sample_interval_seconds": POLICY["sample_interval_seconds"], "rss_units": "KiB",
                "scope": "registered CLI/launcher roots, PID-file Java/Rust roots and observed descendants; excludes monitor and unrelated processes",
                "samples": self.samples, "errors": self.errors}


def classify_memory(telemetry, before, after, functional_status, is_8gb_target):
    """Fail evidence wins; missing/invalid measurements can never produce PASS."""
    reasons = []
    metrics = {}

    def reason(level, code, message):
        reasons.append({"level": level, "code": code, "message": message})

    def budget(value, pass_max, fail_min, code):
        if value >= fail_min:
            reason("FAIL", code, f"{value} bytes >= fail threshold {fail_min}")
        elif value > pass_max:
            reason("REVIEW", code, f"{value} bytes exceeds pass budget {pass_max}")

    def nonnegative(value):
        return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0

    samples = telemetry.get("samples", []) if isinstance(telemetry, dict) else []
    if not samples:
        reason("REVIEW", "missing_samples", "No telemetry samples")
    if not isinstance(telemetry, dict) or telemetry.get("errors"):
        reason("REVIEW", "sampling_errors", "Telemetry has missing or failed observations")
    baseline, active, valid = [], [], []
    coverage = set()
    peak = 0
    warning_start = None
    longest_warning = 0.0
    previous = None
    max_gap = 0.0
    invalid = False
    for sample in samples:
        try:
            elapsed = sample["elapsed_seconds"]
            level = sample["memory"]["pressure_level"]
            if not nonnegative(elapsed) or level not in (1, 2, 4):
                raise ValueError("Bad timestamp or pressure level")
            if previous is not None and elapsed < previous:
                raise ValueError("Timestamps decreased")
            gap = elapsed - previous if previous is not None else 0
            max_gap = max(max_gap, gap)
            previous = elapsed
            valid.append(sample)
            if sample["phase"] == "baseline":
                baseline.append(sample)
                continue
            if sample["phase"] != "workload":
                raise ValueError("Unknown phase")
            active.append(sample)
            # Derive the sum ourselves; don't trust a precomputed peak or total.
            processes = sample["processes"]
            pids = [p["pid"] for p in processes]
            if len(pids) != len(set(pids)) or any(not nonnegative(p["rss_kib"]) for p in processes):
                raise ValueError("Duplicate process or invalid RSS")
            combined = sum(p["rss_kib"] for p in processes)
            if combined != sample["combined_rss_kib"]:
                raise ValueError("Combined RSS does not match process snapshot")
            peak = max(peak, combined * 1024)
            coverage.update(p["role"] for p in processes)
            if level == 4:
                reason("FAIL", "critical_pressure", f"Critical pressure observed at {elapsed:.3f}s")
            if level == 2:
                if warning_start is None or gap > POLICY["max_sample_gap_seconds"]:
                    warning_start = elapsed
                longest_warning = max(longest_warning, elapsed - warning_start)
            else:
                warning_start = None
        except (KeyError, TypeError, ValueError, OverflowError):
            invalid = True
            warning_start = None
    if invalid:
        reason("REVIEW", "invalid_sample", "One or more samples are incomplete or inconsistent")
    if max_gap > POLICY["max_sample_gap_seconds"]:
        reason("REVIEW", "sampling_gap", f"Largest observation gap: {max_gap:.3f}s")
    duration = telemetry.get("baseline_seconds", 0) if isinstance(telemetry, dict) else 0
    if (not nonnegative(duration) or duration < POLICY["baseline_seconds"] or len(baseline) < 2
            or baseline[-1]["elapsed_seconds"] - baseline[0]["elapsed_seconds"] < 28):
        reason("REVIEW", "short_baseline", "A continuously observed 30-second baseline is required")
    if any(s["memory"]["pressure_level"] != 1 for s in baseline):
        reason("REVIEW", "pressured_baseline", "Baseline was not normal throughout; repeat with an otherwise quiet host")
    if not active or not {"cli", "java", "rust"} <= coverage:
        reason("REVIEW", "missing_process_coverage", "Need observed CLI, Java service and Rust RSS")
    budget(peak, POLICY["rss_pass_max_bytes"], POLICY["rss_fail_min_bytes"], "combined_rss")
    if longest_warning >= POLICY["warning_fail_seconds"]:
        reason("FAIL", "sustained_warning", f"Warning observed continuously for {longest_warning:.3f}s")
    elif any(s["memory"]["pressure_level"] == 2 for s in active):
        reason("REVIEW", "transient_warning", "Warning observed for less than five continuously measured seconds")
    metrics.update(peak_combined_rss_bytes=peak, longest_warning_seconds=longest_warning,
                   max_sample_gap_seconds=max_gap, baseline_sample_count=len(baseline),
                   workload_sample_count=len(active), observed_roles=sorted(coverage))
    for label, snapshot in (("before", before), ("after", after)):
        level = snapshot.get("pressure_level") if isinstance(snapshot, dict) else None
        if level == 4:
            reason("FAIL", "critical_pressure", f"Critical pressure in the {label} workload snapshot")
        elif level == 2:
            reason("REVIEW", "endpoint_warning", f"Warning pressure in the {label} workload snapshot")
        elif level != 1:
            reason("REVIEW", "invalid_pressure_snapshot", f"Missing/unknown {label} pressure level")
    try:
        if before["page_size_bytes"] != after["page_size_bytes"] or not nonnegative(before["page_size_bytes"]) or before["page_size_bytes"] == 0:
            raise ValueError("Page size mismatch")
        swap_before, swap_after = before["swap_used_bytes"], after["swap_used_bytes"]
        out_before, out_after = before["vm_counters"]["Swapouts"], after["vm_counters"]["Swapouts"]
        if not all(nonnegative(v) for v in (swap_before, swap_after, out_before, out_after)) or out_after < out_before:
            raise ValueError("Invalid/reset counters")
        swap_delta = swap_after - swap_before
        swapout_delta = (out_after - out_before) * before["page_size_bytes"]
        peak_swap_delta = max([max(0, swap_delta)] + [max(0, s["memory"]["swap_used_bytes"] - swap_before) for s in active])
        metrics.update(swap_used_delta_bytes=swap_delta, peak_swap_used_increase_bytes=peak_swap_delta,
                       swapout_delta_bytes=swapout_delta)
        budget(max(0, swap_delta), POLICY["swap_pass_max_bytes"], POLICY["swap_fail_min_bytes"], "swap_used_delta")
        budget(swapout_delta, POLICY["swapout_pass_max_bytes"], POLICY["swapout_fail_min_bytes"], "swapout_traffic")
        # A large transient swap increase is not hidden by a later decrease.
        budget(peak_swap_delta, POLICY["swap_pass_max_bytes"], POLICY["swap_fail_min_bytes"], "peak_swap_increase")
    except (KeyError, TypeError, ValueError):
        reason("REVIEW", "invalid_vm_counters", "Missing, reset or incompatible swap/VM observations")
    if functional_status != "PASS":
        reason("REVIEW", "incomplete_workload", "Functional acceptance did not complete successfully")
    qualification = "FAIL" if any(r["level"] == "FAIL" for r in reasons) else "REVIEW" if reasons else "PASS"
    overall = "FAIL" if functional_status != "PASS" or qualification == "FAIL" else qualification
    return {"memory_qualification": qualification, "memory_reasons": reasons,
            "memory_metrics": metrics, "memory_policy": dict(POLICY), "status": overall,
            "eight_gb_qualification": overall if is_8gb_target else "NOT_TARGET"}


def exit_code(status):
    return {"PASS": 0, "FAIL": 1, "REVIEW": 2}[status]


def main():
    """Offline classifier replay; never labels synthetic data as a live run."""
    import argparse
    import hashlib
    parser = argparse.ArgumentParser(description="Replay memory classification; does not run services")
    parser.add_argument("--classify-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(args.classify_report.read_text())
    result = classify_memory(data.get("telemetry"), data.get("memory_before"), data.get("memory_after"),
                             data.get("functional_status", "INCOMPLETE"),
                             data.get("host", {}).get("is_8gb_target", False))
    result["classification_source"] = "offline-replay-not-a-live-acceptance-run"
    result["input_sha256"] = hashlib.sha256(args.classify_report.read_bytes()).hexdigest()
    result["synthetic_input"] = data.get("synthetic", False)
    with args.output.open("x") as stream:
        json.dump(result, stream, indent=2)
        stream.write("\n")
    print(f"{result['memory_qualification']}: {args.output}")
    return exit_code(result["status"])


if __name__ == "__main__":
    raise SystemExit(main())
