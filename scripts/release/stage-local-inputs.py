#!/usr/bin/env python3
"""Stage Step 5 build outputs and observed provenance; does not compile or publish."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import platform as host_platform
import shutil
import subprocess
import tarfile

spec = importlib.util.spec_from_file_location("assembly", Path(__file__).with_name("assemble-local.py"))
assembly = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assembly)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def output(*command):
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT).strip()


def tracked_inventory(repo):
    data = subprocess.check_output(["git", "-C", str(repo), "ls-files", "-s"])
    return hashlib.sha256(data).hexdigest()


def verify_clean_checkout(repo, source):
    if output("git", "-C", str(repo), "rev-parse", "HEAD") != source["commit"]:
        raise ValueError("Packaging checkout HEAD differs from development metadata")
    if output("git", "-C", str(repo), "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Current-source development packaging checkout is not clean")
    if tracked_inventory(repo) != source["worktree_inventory_sha256"]:
        raise ValueError("Packaging checkout inventory differs from development metadata")


def verify_export(repo, revision, directory):
    archive = subprocess.check_output(["git", "-C", str(repo), "archive", revision])
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        for member in source:
            if member.isfile():
                if source.extractfile(member).read() != (directory / member.name).read_bytes():
                    raise ValueError(f"Build source differs from pinned revision: {member.name}")


def stage(args):
    work, platform_name = args.workspace.resolve(), args.platform
    target = assembly.TARGETS[platform_name]
    development = json.loads(args.development_info.read_text()) if args.development_info else None
    if development is not None:
        assembly.development_identity(development)
    java_commit, rust_commit, current_source = assembly.development_commits(development)
    if current_source:
        verify_clean_checkout(args.java_repo, development["packaging_source"])
    java_source, rust_source = work / "java-source", work / "rust-source"
    verify_export(args.java_repo, java_commit, java_source)
    verify_export(args.rust_repo, rust_commit, rust_source)
    settings = output(str(args.java_home / "bin/java"), "-XshowSettings:properties", "-version")
    java_version = "\n".join(line.strip() for line in settings.splitlines()
                             if any(key in line for key in ["java.vendor =", "java.version =", "java.runtime.version =", "openjdk version", "OpenJDK Runtime", "OpenJDK 64-Bit"]))
    if "Temurin-21.0.12.1+1" not in java_version or "java.vendor = Eclipse Adoptium" not in java_version:
        raise ValueError("Observed JDK is not the pinned Temurin build")
    jdk_sha = digest(args.jdk_archive)
    if jdk_sha not in assembly.JDK_HASHES:
        raise ValueError("JDK archive checksum differs from verified release assets")
    if "BUILD SUCCESS" not in (work / "java-build.log").read_text():
        raise ValueError("Java build did not succeed")
    if platform_name.startswith("macos"):
        binary = rust_source / "target" / target / "release/dcgaimodel"
        rustc = output("rustc", "+1.96.0", "-Vv")
        cargo = output("cargo", "+1.96.0", "-V")
        build_log = work / f"rust-{platform_name}.log"
        builder = {"os": output("sw_vers"), "architecture": output("uname", "-m"),
                   "native_load_commands": output("xcrun", "vtool", "-show-build", str(binary)).split("\n", 1)[1]}
    else:
        binary = work / platform_name / "dcgaimodel"
        rustc = (work / platform_name / "rustc.txt").read_text().strip()
        cargo = (work / platform_name / "cargo.txt").read_text().strip()
        build_log = work / f"rust-{platform_name}-pinned.log"
        native_linux = host_platform.system() == "Linux" and host_platform.machine() == "x86_64"
        if current_source and not native_linux:
            raise ValueError("Current-source linux-x64 staging must run natively on Linux x86-64")
        builder = {"os": output("uname", "-a") if native_linux else "Debian Bookworm; builder glibc 2.36",
                   "architecture": output("uname", "-m") if native_linux else "x86_64",
                   "execution_environment": ("WSL2" if native_linux and "microsoft" in host_platform.release().lower()
                                             else "native Linux" if native_linux else "cross-target builder"),
                   "toolchain_override": "RUSTUP_TOOLCHAIN=1.96.0", "emulated": not native_linux}
    if assembly.RUSTC_SHA not in rustc or "release: 1.96.0" not in rustc or "Finished `release`" not in build_log.read_text():
        raise ValueError("Rust compiler/build evidence mismatch")
    paths = {f"lib/{assembly.CLI}": java_source / "contract-cli/target" / assembly.CLI,
             f"lib/{assembly.SERVICE}": java_source / "contract-service/target" / assembly.SERVICE,
             "bin/dcgaimodel": binary}
    assembly.validate_binary(binary.read_bytes(), platform_name)
    evidence = work / f"evidence-{platform_name}"
    audit = json.loads((evidence / "license-audit.json").read_text())
    if audit["missing_license_text"]:
        raise ValueError("Unresolved license-text inventory")
    provenance = {
        "version": assembly.VERSION, "java_build_commit": java_commit,
        "rust_commit": rust_commit, "target": target,
        "java_vendor": "Eclipse Temurin", "java_system_vendor": "Eclipse Adoptium",
        "java_version": "21.0.12.1+1", "jdk_archive_sha256": jdk_sha,
        "java_runtime_evidence": java_version, "rustc_verbose": rustc, "cargo_version": cargo,
        "cargo_lock_sha256": digest(rust_source / "Cargo.lock"), "rust_builder": builder,
        "java_build_command": "JAVA_HOME=<verified Temurin> ./mvnw -B -ntp -pl contract-cli,contract-service -am package org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeAggregateBom -DincludeTestScope=false -DincludeProvidedScope=false -DincludeSystemScope=false -DincludeLicenseText=true -DoutputFormat=json",
        "rust_build_command": f"cargo +1.96.0 build --release --locked --target {target}",
        "release_pins_sha256": digest(assembly.PIN_FILE),
        "java_build_log_sha256": digest(work / "java-build.log"), "rust_build_log_sha256": digest(build_log),
        "artifacts": {name: digest(path) for name, path in paths.items()},
        "dependency_evidence": {"sbom_sha256": digest(evidence / "sbom.cdx.json"),
                                "notices_sha256": digest(evidence / "THIRD-PARTY-NOTICES.txt"),
                                "audit_sha256": digest(evidence / "license-audit.json"),
                                "collector_sha256": digest(Path(__file__).with_name("collect-dependency-evidence.py")),
                                "supplement_sources_sha256": digest(Path(__file__).with_name("license-sources.json"))},
        "publication_status": "Local-demo package only; public redistribution/license review and full platform acceptance remain pending",
    }
    if current_source:
        provenance["development_source"] = {
            "java_build_commit": java_commit, "rust_commit": rust_commit,
            "packaging_commit": development["packaging_source"]["commit"], "clean": True}
        provenance["publication_status"] = "Unpublished local development build input; no RC acceptance implied"
    args.output.mkdir(parents=True, exist_ok=False)
    for path in paths.values():
        shutil.copy2(path, args.output / path.name)
    for name in ["sbom.cdx.json", "THIRD-PARTY-NOTICES.txt"]:
        shutil.copy2(evidence / name, args.output / name)
    (args.output / "build-info.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(args.output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["workspace", "java-repo", "rust-repo", "java-home", "jdk-archive", "output"]:
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--platform", choices=assembly.TARGETS, required=True)
    parser.add_argument("--development-info", type=Path,
                        help="Frozen clean development identity used by staging and assembly")
    stage(parser.parse_args())
