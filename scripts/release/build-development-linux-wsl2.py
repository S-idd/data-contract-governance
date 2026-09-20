#!/usr/bin/env python3
"""Build, reproduce, and optionally accept a current-source Linux x64 package on WSL2."""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import socket
import subprocess
import sys
import tarfile


SCRIPT = Path(__file__).resolve()
REPO_ROOT = SCRIPT.parents[2]
RELEASE_DIR = SCRIPT.parent
PINS_PATH = RELEASE_DIR / "release-pins.json"
LICENSE_SOURCES_PATH = RELEASE_DIR / "license-sources.json"
TARGET = "x86_64-unknown-linux-gnu"
PLATFORM = "linux-x64"
RUST_REMAP_DESTINATION = "/dcg-build-home"


def fail(message):
    raise RuntimeError(message)


def require(condition, message):
    if not condition:
        fail(message)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def output(*command, cwd=None, env=None):
    return subprocess.check_output(command, cwd=cwd, env=env, text=True,
                                   stderr=subprocess.STDOUT).strip()


def run(*command, cwd=None, env=None, log=None):
    print("+", " ".join(str(item) for item in command), flush=True)
    if log is None:
        subprocess.run(command, cwd=cwd, env=env, check=True)
        return
    with log.open("wb") as destination:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT)
        for line in iter(process.stdout.readline, b""):
            destination.write(line)
            destination.flush()
            sys.stdout.buffer.write(line)
            sys.stdout.buffer.flush()
        require(process.wait() == 0, f"Command failed; inspect {log}")


def require_command(name):
    require(shutil.which(name), f"Required command is unavailable: {name}")


def require_clean_commit(repo, expected=None, label="repository"):
    require((repo / ".git").exists(), f"{label} is not a Git checkout: {repo}")
    commit = output("git", "-C", str(repo), "rev-parse", "HEAD")
    if expected:
        require(commit == expected, f"{label} HEAD must be {expected}; found {commit}")
    status = output("git", "-C", str(repo), "status", "--porcelain", "--untracked-files=all")
    require(not status, f"{label} must be clean, including untracked files:\n{status}")
    return commit


def tracked_inventory(repo):
    inventory = subprocess.check_output(["git", "-C", str(repo), "ls-files", "-s"])
    return hashlib.sha256(inventory).hexdigest()


def export_commit(repo, commit, destination):
    destination.mkdir()
    git = subprocess.Popen(["git", "-C", str(repo), "archive", "--format=tar", commit],
                           stdout=subprocess.PIPE)
    try:
        extracted = subprocess.run(["tar", "-xf", "-", "-C", str(destination)],
                                   stdin=git.stdout)
    finally:
        git.stdout.close()
    require(extracted.returncode == 0 and git.wait() == 0,
            f"Could not export {repo} at {commit}")


def download_license_supplements(destination):
    destination.mkdir()
    sources = json.loads(LICENSE_SOURCES_PATH.read_text())
    for source in sources:
        target = destination / source["file"]
        print(f"Downloading pinned license text: {source['file']}", flush=True)
        subprocess.run(["curl", "--fail", "--location", "--proto", "=https", "--tlsv1.2",
                        "--silent", "--show-error", "--output", str(target), source["url"]],
                       check=True)
        require(sha256(target) == source["sha256"],
                f"Pinned license checksum mismatch: {source['file']}")


def require_wsl2_x64():
    release = platform.release().lower()
    require(platform.system() == "Linux" and platform.machine() == "x86_64",
            "Run this workflow on Linux x86-64")
    require("microsoft" in release and ("wsl2" in release or "microsoft-standard" in release),
            "Run this workflow inside WSL2; WSL1 and non-WSL hosts are rejected")
    require(not Path("/.dockerenv").exists() and not Path("/run/.containerenv").exists(),
            "Run on the real WSL2 host, not inside a container")


def require_outside_windows_mount(path):
    resolved = path.expanduser().resolve()
    require(not str(resolved).startswith("/mnt/"),
            "Use the WSL ext4 filesystem under $HOME, not /mnt, for the build workspace")
    return resolved


def is_within(path, parent):
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def require_ports_free():
    for port in (8080, 8081):
        with socket.socket() as listener:
            try:
                listener.bind(("127.0.0.1", port))
            except OSError as error:
                fail(f"Port {port} is unavailable; stop the owning service before acceptance: {error}")


def verify_external_checksums(directory):
    for line in (directory / "SHA256SUMS").read_text(encoding="ascii").splitlines():
        expected, name = line.split("  ", 1)
        require("/" not in name and name not in {"", ".", ".."},
                f"Unsafe checksum entry: {name}")
        require(sha256(directory / name) == expected, f"Checksum mismatch: {name}")


def rust_path_remapping(builder_home):
    return {
        "schema_version": 1,
        "applied": True,
        "source_prefix": "<builder-home>",
        "destination_prefix": RUST_REMAP_DESTINATION,
        "reason": "Prevent developer-specific absolute source paths in the packaged Rust executable",
        "rustflags": f"--remap-path-prefix=<builder-home>={RUST_REMAP_DESTINATION}",
    }, f"--remap-path-prefix={builder_home}={RUST_REMAP_DESTINATION}"


def parse_args():
    today = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rust-repo", type=Path, required=True,
                        help="Clean dcgaimodel checkout at the frozen Rust commit")
    parser.add_argument("--java-home", type=Path, required=True,
                        help="Extracted Eclipse Temurin 21.0.12.1+1 home")
    parser.add_argument("--jdk-archive", type=Path, required=True,
                        help="Original checksum-pinned Temurin archive")
    parser.add_argument("--work-dir", type=Path, required=True,
                        help="New disposable directory under the WSL home filesystem")
    parser.add_argument("--java-repo", type=Path, default=REPO_ROOT,
                        help=f"Clean DCG checkout (default: {REPO_ROOT})")
    parser.add_argument("--maven-repo", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--version", default=f"4.0.0-phase1-phase2-dev.{today}",
                        help="Development package identity; never an RC version")
    parser.add_argument("--assembled-at",
                        help="Frozen UTC timestamp; defaults once per invocation")
    parser.add_argument("--machine-description", default="AlmaLinux x86-64 under WSL2")
    parser.add_argument("--skip-acceptance", action="store_true",
                        help="Build and reproduce the archive without starting its services")
    return parser.parse_args()


def main():
    args = parse_args()
    require_wsl2_x64()
    for command in ("bash", "cargo", "curl", "git", "python3", "rustc", "tar"):
        require_command(command)
    if not args.skip_acceptance:
        for command in ("lsof", "ps"):
            require_command(command)

    java_repo = args.java_repo.expanduser().resolve()
    rust_repo = args.rust_repo.expanduser().resolve()
    java_home = args.java_home.expanduser().resolve()
    jdk_archive = args.jdk_archive.expanduser().resolve()
    maven_repo = args.maven_repo.expanduser().resolve()
    work = require_outside_windows_mount(args.work_dir)
    require(not work.exists(), "Use a new --work-dir; existing data is never overwritten")
    require(not is_within(work, java_repo) and not is_within(work, rust_repo),
            "--work-dir must be outside both source checkouts")
    require(not is_within(maven_repo, java_repo) and not is_within(maven_repo, rust_repo),
            "--maven-repo must be outside both source checkouts")
    require(java_home.joinpath("bin/java").is_file(), f"Java executable is missing under {java_home}")
    require(jdk_archive.is_file(), f"JDK archive is missing: {jdk_archive}")
    require(bool(re.fullmatch(r"[0-9][A-Za-z0-9.-]*-dev(?:\.[A-Za-z0-9-]+)*", args.version))
            and "rc" not in args.version.lower(),
            "--version must be distinct, end in -dev[.identifier], and not be an RC")

    pins = json.loads(PINS_PATH.read_text())
    require(sha256(jdk_archive) in pins["jdk_archive_sha256"],
            "JDK archive does not match a checksum-pinned Temurin release asset")
    java_commit = require_clean_commit(java_repo, label="DCG repository")
    rust_commit = require_clean_commit(rust_repo, pins["rust_commit"], "dcgaimodel repository")
    java_identity = output(str(java_home / "bin/java"), "-XshowSettings:properties", "-version")
    require("Temurin-21.0.12.1+1" in java_identity and "java.vendor = Eclipse Adoptium" in java_identity,
            "--java-home must be Eclipse Temurin 21.0.12.1+1")
    rustc_identity = output("rustc", "+1.96.0", "-Vv")
    require("release: 1.96.0" in rustc_identity and pins["rustc_commit"] in rustc_identity,
            "Rust 1.96.0 does not match the pinned compiler commit")
    cargo_identity = output("cargo", "+1.96.0", "-V")
    require(cargo_identity.startswith("cargo 1.96.0 "), "Cargo 1.96.0 is required")
    if not args.skip_acceptance:
        require_ports_free()

    assembled_at = args.assembled_at or datetime.datetime.now(
        datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    require(assembled_at.endswith("Z"), "--assembled-at must be an explicit UTC timestamp ending in Z")
    datetime.datetime.fromisoformat(assembled_at.replace("Z", "+00:00"))

    os.umask(0o077)
    work.mkdir(parents=True)
    java_source = work / "java-source"
    rust_source = work / "rust-source"
    print(f"Exporting DCG commit {java_commit}", flush=True)
    export_commit(java_repo, java_commit, java_source)
    print(f"Exporting dcgaimodel commit {rust_commit}", flush=True)
    export_commit(rust_repo, rust_commit, rust_source)

    development = {
        "version": args.version,
        "base_version": pins["version"],
        "assembled_at": assembled_at,
        "java_build_commit": java_commit,
        "rust_commit": rust_commit,
        "packaging_source": {
            "commit": java_commit,
            "dirty": False,
            "worktree_inventory_sha256": tracked_inventory(java_repo),
        },
    }
    development_info = work / "development-info.json"
    development_info.write_text(json.dumps(development, indent=2) + "\n")

    remapping, rustflags = rust_path_remapping(Path.home().resolve())
    build_env = {**os.environ, "JAVA_HOME": str(java_home),
                 "PATH": str(java_home / "bin") + os.pathsep + os.environ["PATH"],
                 "RUSTUP_TOOLCHAIN": "1.96.0", "RUSTFLAGS": rustflags}
    build_env.pop("CARGO_ENCODED_RUSTFLAGS", None)
    maven_command = [
        "./mvnw", "-B", "-ntp", "-pl", "contract-cli,contract-service", "-am", "package",
        "org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeAggregateBom",
        "-DincludeTestScope=false", "-DincludeProvidedScope=false", "-DincludeSystemScope=false",
        "-DincludeLicenseText=true", "-DoutputFormat=json", f"-Dmaven.repo.local={maven_repo}",
    ]
    run(*maven_command, cwd=java_source, env=build_env, log=work / "java-build.log")
    require((java_source / "target/bom.json").is_file(), "Maven aggregate CycloneDX BOM was not generated")
    require(maven_repo.is_dir(), f"Maven did not populate the selected repository: {maven_repo}")

    rust_log = work / "rust-linux-x64-pinned.log"
    run("cargo", "+1.96.0", "build", "--release", "--locked", "--target", TARGET,
        cwd=rust_source, env=build_env, log=rust_log)
    rust_platform = work / PLATFORM
    rust_platform.mkdir()
    rust_binary = rust_source / "target" / TARGET / "release/dcgaimodel"
    require(rust_binary.is_file(), f"Rust binary was not generated: {rust_binary}")
    builder_home_prefix = str(Path.home().resolve()).rstrip("/").encode() + b"/"
    require(builder_home_prefix not in rust_binary.read_bytes(),
            "Rust path remapping failed; the executable still contains the builder home")
    shutil.copy2(rust_binary, rust_platform / "dcgaimodel")
    (rust_platform / "rustc.txt").write_text(rustc_identity + "\n")
    (rust_platform / "cargo.txt").write_text(cargo_identity + "\n")
    (rust_platform / "path-remap.json").write_text(json.dumps(remapping, indent=2) + "\n")

    cargo_metadata = work / "cargo-linux-x64.json"
    with cargo_metadata.open("wb") as metadata:
        subprocess.run(["cargo", "+1.96.0", "metadata", "--locked", "--filter-platform", TARGET,
                        "--format-version", "1"], cwd=rust_source, env=build_env,
                       stdout=metadata, check=True)
    supplements = work / "license-supplements"
    download_license_supplements(supplements)
    rust_sysroot = output("rustc", "+1.96.0", "--print", "sysroot", env=build_env)
    evidence = work / "evidence-linux-x64"
    run("python3", str(RELEASE_DIR / "collect-dependency-evidence.py"),
        "--java-source", str(java_source), "--cargo-metadata", str(cargo_metadata),
        "--maven-repo", str(maven_repo), "--rust-sysroot", rust_sysroot,
        "--license-supplements", str(supplements), "--output", str(evidence),
        "--target", TARGET, "--java-build-commit", java_commit)

    staged = work / "staged-inputs"
    run("python3", str(RELEASE_DIR / "stage-local-inputs.py"),
        "--workspace", str(work), "--java-repo", str(java_repo), "--rust-repo", str(rust_repo),
        "--java-home", str(java_home), "--jdk-archive", str(jdk_archive),
        "--output", str(staged), "--platform", PLATFORM,
        "--development-info", str(development_info))

    assemblies = [work / "assembly-a", work / "assembly-b"]
    for assembly in assemblies:
        run("python3", str(RELEASE_DIR / "assemble-local.py"),
            "--inputs", str(staged), "--java-repo", str(java_repo), "--rust-repo", str(rust_repo),
            "--output", str(assembly), "--platform", PLATFORM,
            "--development-info", str(development_info))
        verify_external_checksums(assembly)
    first_archive = next(assemblies[0].glob("*.tar.gz"))
    for name in (first_archive.name, "archive-runner-compatibility.json", "SHA256SUMS"):
        require((assemblies[0] / name).read_bytes() == (assemblies[1] / name).read_bytes(),
                f"Reproducibility mismatch: {name}")

    handoff = work / "acceptance-handoff"
    handoff.mkdir()
    for name in (first_archive.name, "archive-runner-compatibility.json", "SHA256SUMS"):
        shutil.copy2(assemblies[0] / name, handoff / name)
    runner = handoff / "test-extracted-package.py"
    shutil.copy2(RELEASE_DIR / runner.name, runner)

    acceptance_status = "SKIPPED"
    acceptance_report = work / "acceptance" / "acceptance-report.json"
    if not args.skip_acceptance:
        run("python3", str(runner), "--archive", str(handoff / first_archive.name),
            "--java-home", str(java_home), "--work-dir", str(work / "acceptance"),
            "--machine-description", args.machine_description)
        acceptance = json.loads(acceptance_report.read_text())
        require(acceptance.get("status") == "PASS", "Target-host acceptance did not pass")
        acceptance_status = "PASS"

    summary = {
        "status": "PASS",
        "archive": str(handoff / first_archive.name),
        "archive_sha256": sha256(handoff / first_archive.name),
        "java_build_commit": java_commit,
        "rust_commit": rust_commit,
        "development_info": str(development_info),
        "reproducibility": "PASS",
        "acceptance": acceptance_status,
        "acceptance_report": str(acceptance_report) if acceptance_report.is_file() else None,
    }
    summary_path = work / "workflow-summary.json"
    summary_path.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
