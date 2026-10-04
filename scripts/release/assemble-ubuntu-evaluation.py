#!/usr/bin/env python3
"""Assemble Ubuntu evaluation or macOS ARM64 Milestone 1 from existing binaries."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import tarfile
import tempfile


ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = ROOT / "evaluation" / "ubuntu-24.04"
MAC_TEMPLATE = ROOT / "evaluation" / "macos-arm64"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_member(member: tarfile.TarInfo) -> None:
    path = PurePosixPath(member.name)
    require(not path.is_absolute(), f"Archive contains absolute path: {member.name}")
    require(".." not in path.parts, f"Archive contains traversal path: {member.name}")
    require(member.isfile() or member.isdir(), f"Archive contains unsupported link or device: {member.name}")


def extract_package(archive: Path, destination: Path, target: str = "x86_64-unknown-linux-gnu") -> Path:
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        for member in members:
            validate_member(member)
        roots = {PurePosixPath(member.name).parts[0] for member in members if member.name}
        require(len(roots) == 1, "DCG archive must contain exactly one top-level directory")
        source.extractall(destination, members=members, filter="data")
    package = destination / next(iter(roots))
    require((package / "bin" / "dcg").is_file(), "DCG package is missing bin/dcg")
    require((package / "SHA256SUMS").is_file(), "DCG package is missing SHA256SUMS")
    verify_manifest(package, package / "SHA256SUMS")
    build_info = json.loads((package / "build-info.json").read_text(encoding="utf-8"))
    require(build_info.get("target") == target, f"DCG package target must be {target}")
    return package


def manifest_entries(manifest: Path) -> list[tuple[str, str]]:
    entries: list[tuple[str, str]] = []
    for line in manifest.read_text(encoding="utf-8").splitlines():
        digest, separator, relative = line.partition("  ")
        require(separator == "  " and len(digest) == 64 and relative, f"Invalid checksum line: {line}")
        require(not Path(relative).is_absolute() and ".." not in Path(relative).parts, "Unsafe checksum path")
        entries.append((digest, relative))
    return entries


def verify_manifest(root: Path, manifest: Path) -> None:
    for expected, relative in manifest_entries(manifest):
        path = root / relative
        require(path.is_file(), f"Package manifest file is missing: {relative}")
        require(sha256(path) == expected, f"Package checksum mismatch: {relative}")


def copy_tree(source: Path, destination: Path) -> None:
    shutil.copytree(
        source,
        destination,
        copy_function=shutil.copy2,
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", ".DS_Store"),
    )


def copy_examples(package: Path, destination: Path) -> None:
    source_contracts = package / "contracts"
    policy = source_contracts / "policy-packs.json"
    require(policy.is_file(), "Accepted DCG package has no policy-packs.json")
    (destination / "examples").mkdir(parents=True, exist_ok=True)
    shutil.copy2(policy, destination / "examples" / "policy-packs.json")
    contract_sources = sorted(path for path in source_contracts.iterdir() if path.is_dir())
    require(contract_sources, "Accepted DCG package contains no example contract")
    source = contract_sources[0]
    target = destination / "examples" / "contracts" / source.name
    target.mkdir(parents=True)
    for name in ("metadata.yaml", "v1.json"):
        require((source / name).is_file(), f"Example contract is missing {name}")
        shutil.copy2(source / name, target / name)
    candidate = source / "v2.json"
    require(candidate.is_file(), "Example contract is missing v2.json")
    shutil.copy2(candidate, target / "candidate.json")
    workspace = destination / "workspace" / "contracts"
    workspace.mkdir(parents=True)
    shutil.copy2(policy, workspace / "policy-packs.json")


def git_identity(repository: Path) -> str:
    try:
        commit = subprocess.run(
            ["git", "-C", str(repository), "rev-parse", "HEAD"],
            check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        ).stdout.strip()
        status = subprocess.run(
            ["git", "-C", str(repository), "status", "--porcelain", "--untracked-files=all"],
            check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ValueError(f"IEMS root is not a readable Git checkout: {repository}") from exc
    require(not status.strip(), "IEMS source checkout must be clean")
    return commit


def copy_iems(iems_jar: Path, iems_root: Path, destination: Path) -> str:
    require(iems_jar.is_file(), f"IEMS JAR does not exist: {iems_jar}")
    require(iems_root.is_dir(), f"IEMS root does not exist: {iems_root}")
    expected_jar = iems_root / "target" / "inclusive-education-management-system-1.0.0-SNAPSHOT.jar"
    require(iems_jar.resolve() == expected_jar.resolve(), "IEMS JAR must be the standard target output inside --iems-root")
    commit = git_identity(iems_root)
    # Keep this evaluator fixture in DCG so older IEMS checkouts need no helper patch.
    seed = ROOT / "scripts/release/fixtures/iems/seed_notification.py"
    require(seed.is_file(), f"Bundled IEMS notification fixture helper is missing: {seed}")
    target = destination / "iems"
    target.mkdir()
    shutil.copy2(iems_jar, target / "iems.jar")
    for name in ("contracts", "postman"):
        source = iems_root / name
        require(source.is_dir(), f"IEMS input is missing {name}/")
        copy_tree(source, target / name)

    shutil.copy2(seed, target / "postman" / seed.name)
    shutil.copy2(destination / "examples" / "policy-packs.json", target / "contracts" / "policy-packs.json")
    return commit


def acceptance_summary(report_path: Path, archive: Path) -> dict:
    require(report_path.is_file(), f"Acceptance report does not exist: {report_path}")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    require(report.get("status") == "PASS", "DCG acceptance report is not PASS")
    require(report.get("archive") == archive.name, "Acceptance report names a different archive")
    require(report.get("archive_sha256") == sha256(archive), "Acceptance report archive hash does not match")
    checks = report.get("checks")
    require(isinstance(checks, list) and checks, "Acceptance report has no checks")
    require(all(item.get("status") == "PASS" for item in checks), "Acceptance report contains a non-PASS check")
    return {
        "status": "PASS",
        "archive": report["archive"],
        "archive_sha256": report["archive_sha256"],
        "status_protocol": report.get("status_protocol"),
        "compatibility_manifest_verified": report.get("compatibility_manifest_verified"),
        "checks": [{"check": item.get("check"), "status": item.get("status")} for item in checks],
    }


def make_executable_scripts(destination: Path) -> None:
    for path in (destination / "scripts").glob("*.sh"):
        path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    for path in (destination / "docker" / "init").glob("*.sh"):
        path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def write_manifest(destination: Path) -> None:
    excluded = {Path("SHA256SUMS")}
    files = sorted(
        path for path in destination.rglob("*")
        if path.is_file()
        and path.relative_to(destination) not in excluded
        and path.relative_to(destination).parts[0] != "workspace"
        and path.relative_to(destination) != Path("docker/.env")
    )
    lines = [f"{sha256(path)}  {path.relative_to(destination).as_posix()}" for path in files]
    (destination / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")


def tar_filter(info: tarfile.TarInfo) -> tarfile.TarInfo:
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    info.mtime = 0
    return info


def create_archive(destination: Path, archive: Path) -> None:
    require(not archive.exists(), f"Refusing to overwrite archive: {archive}")
    with archive.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w") as output:
                output.add(destination, arcname=destination.name, recursive=True, filter=tar_filter)
    archive.with_name(archive.name + ".sha256").write_text(
        f"{sha256(archive)}  {archive.name}\n", encoding="utf-8"
    )


def assemble_macos(args: argparse.Namespace) -> None:
    """Reuse manifest-listed inputs; never compile or copy mutable runtime state."""
    destination = args.output.resolve()
    require(not destination.exists(), f"Refusing to overwrite output: {destination}")
    require(args.dcg_package is not None, "macos-arm64 requires --dcg-package")
    package = args.dcg_package.resolve()
    manifest = package / "SHA256SUMS"
    entries = manifest_entries(manifest)
    require(len(entries) == len({name for _, name in entries}), "Duplicate input manifest entry")
    for _, name in entries:
        path = package / name
        require(not path.is_symlink() and path.resolve().is_relative_to(package), "Linked input is forbidden")
    verify_manifest(package, manifest)
    info = json.loads((package / "build-info.json").read_text())
    require(info.get("target") == "aarch64-apple-darwin", "Milestone 1 requires native Apple Silicon inputs")
    pins = json.loads((ROOT / "scripts/release/release-pins.json").read_text())
    require(info.get("rust_commit") == pins["rust_commit"], "Unexpected Rust source pin")
    names = {name for _, name in entries}
    required = {"build-info.json", "bin/dcgaimodel", "lib/contract-cli-4.0.0-rc.1-all.jar",
                "lib/contract-service-4.0.0-rc.1.jar"}
    require(required <= names, "Input manifest omits required artifacts")
    for name in required - {"build-info.json"}:
        require(info.get("artifacts", {}).get(name) == sha256(package / name), f"Provenance mismatch: {name}")
    binary = (package / "bin/dcgaimodel").read_bytes()
    require(binary[:4] == b"\xcf\xfa\xed\xfe" and int.from_bytes(binary[4:8], "little") == 0x100000c,
            "Rust executable is not Mach-O ARM64")
    for name, digest in pins["frozen_artifacts"].items():
        require("model/" + name in names and sha256(package / "model" / name) == digest,
                f"Frozen model mismatch: {name}")
    copy_tree(MAC_TEMPLATE, destination)
    dcg = destination / "dcg"
    for _, name in entries:
        target = dcg / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(package / name, target)
    # Original build-info describes the reused binaries, not these new launchers.
    transformations = {}
    for name in ("dcg", "start", "stop", "status"):
        target = dcg / "bin" / name
        shutil.copy2(ROOT / "packaging/local/bin" / name, target)
        target.chmod(0o755)
        transformations[f"dcg/bin/{name}"] = sha256(target)
    write_manifest(dcg)
    bundle_info = {
        "bundle": destination.name, "target": "aarch64-apple-darwin", "scope": "milestone-1",
        "bundle_revision": "milestone-1-telemetry-v2", "results_schema_version": 2,
        "telemetry_sha256": {name: sha256(destination / "scripts" / name)
                             for name in ("macos-acceptance.py", "memory_telemetry.py")},
        "source_package": package.name, "source_manifest_sha256": sha256(manifest),
        "source_build_info_sha256": sha256(package / "build-info.json"),
        "reused_artifacts": info["artifacts"], "rust_rebuilt": False, "java_rebuilt": False,
        "launcher_overrides": transformations,
        "assembler_sha256": sha256(Path(__file__)),
        "acceptance": "Run scripts/macos-acceptance.py on the extracted archive; no prior acceptance inferred",
    }
    (destination / "bundle-info.json").write_text(json.dumps(bundle_info, indent=2) + "\n")
    make_executable_scripts(destination)
    write_manifest(destination)
    verify_manifest(destination, destination / "SHA256SUMS")
    if args.archive:
        create_archive(destination, args.archive.resolve())
    print(destination)


def assemble(args: argparse.Namespace) -> None:
    if getattr(args, "platform", "ubuntu-24.04") == "macos-arm64":
        assemble_macos(args)
        return
    destination = args.output.resolve()
    require(not destination.exists(), f"Refusing to overwrite output: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="dcg-evaluation-") as temp:
        package = extract_package(args.dcg_archive.resolve(), Path(temp))
        accepted = acceptance_summary(args.dcg_acceptance_report.resolve(), args.dcg_archive.resolve())
        copy_tree(TEMPLATE, destination)
        shutil.copytree(package, destination / "dcg", copy_function=shutil.copy2)
        copy_examples(package, destination)
        iems_commit = copy_iems(args.iems_jar.resolve(), args.iems_root.resolve(), destination)
        verification = destination / "verification"
        verification.mkdir()
        (verification / "dcg-acceptance-summary.json").write_text(
            json.dumps(accepted, indent=2) + "\n", encoding="utf-8"
        )
        make_executable_scripts(destination)
        info = {
            "bundle": destination.name,
            "target": "ubuntu-24.04-x86_64",
            "dcg_archive": args.dcg_archive.name,
            "dcg_archive_sha256": sha256(args.dcg_archive),
            "iems_jar": args.iems_jar.name,
            "iems_jar_sha256": sha256(args.iems_jar),
            "iems_commit": iems_commit,
            "workspace_contracts": "empty except for required policy-packs.json",
        }
        (destination / "bundle-info.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
        write_manifest(destination)
        verify_manifest(destination, destination / "SHA256SUMS")
    if args.archive:
        create_archive(destination, args.archive.resolve())
    print(destination)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=("ubuntu-24.04", "macos-arm64"), default="ubuntu-24.04")
    parser.add_argument("--dcg-package", type=Path, help="Existing manifest-verified ARM64 package; macOS only")
    parser.add_argument("--dcg-archive", type=Path)
    parser.add_argument("--dcg-acceptance-report", type=Path)
    parser.add_argument("--iems-jar", type=Path)
    parser.add_argument("--iems-root", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    if args.platform == "ubuntu-24.04":
        for name in ("dcg_archive", "dcg_acceptance_report", "iems_jar", "iems_root"):
            if getattr(args, name) is None:
                parser.error("Ubuntu evaluation requires --" + name.replace("_", "-"))
        if args.dcg_package:
            parser.error("--dcg-package is only supported for macos-arm64")
    elif not args.dcg_package or any((args.dcg_archive, args.dcg_acceptance_report, args.iems_jar, args.iems_root)):
        parser.error("macos-arm64 requires --dcg-package and excludes Ubuntu/IEMS inputs")
    return args


if __name__ == "__main__":
    assemble(parse_args())
