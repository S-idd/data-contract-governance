#!/usr/bin/env python3
"""Assemble a clean Ubuntu 24.04 evaluator bundle from accepted binary inputs."""

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


def extract_package(archive: Path, destination: Path) -> Path:
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
    require(build_info.get("target") == "x86_64-unknown-linux-gnu", "DCG package is not Linux x86-64")
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
    target = destination / "iems"
    target.mkdir()
    shutil.copy2(iems_jar, target / "iems.jar")
    for name in ("contracts", "postman"):
        source = iems_root / name
        require(source.is_dir(), f"IEMS input is missing {name}/")
        copy_tree(source, target / name)
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


def assemble(args: argparse.Namespace) -> None:
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
    parser.add_argument("--dcg-archive", type=Path, required=True)
    parser.add_argument("--dcg-acceptance-report", type=Path, required=True)
    parser.add_argument("--iems-jar", type=Path, required=True)
    parser.add_argument("--iems-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--archive", type=Path)
    return parser.parse_args()


if __name__ == "__main__":
    assemble(parse_args())
