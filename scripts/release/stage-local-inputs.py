#!/usr/bin/env python3
"""Stage Step 5 build outputs and observed provenance; does not compile or publish."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
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


def verify_export(repo, revision, directory):
    archive = subprocess.check_output(["git", "-C", str(repo), "archive", revision])
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        for member in source:
            if member.isfile():
                if source.extractfile(member).read() != (directory / member.name).read_bytes():
                    raise ValueError(f"Build source differs from pinned revision: {member.name}")


def stage(args):
    work, platform = args.workspace.resolve(), args.platform
    target = assembly.TARGETS[platform]
    java_source, rust_source = work / "java-source", work / "rust-source"
    verify_export(args.java_repo, assembly.JAVA_SHA, java_source)
    verify_export(args.rust_repo, assembly.RUST_SHA, rust_source)
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
    if platform.startswith("macos"):
        binary = rust_source / "target" / target / "release/dcgaimodel"
        rustc = output("rustc", "+1.96.0", "-Vv")
        cargo = output("cargo", "+1.96.0", "-V")
        build_log = work / f"rust-{platform}.log"
        builder = {"os": output("sw_vers"), "architecture": output("uname", "-m"),
                   "native_load_commands": output("xcrun", "vtool", "-show-build", str(binary)).split("\n", 1)[1]}
    else:
        binary = work / platform / "dcgaimodel"
        rustc = (work / platform / "rustc.txt").read_text().strip()
        cargo = (work / platform / "cargo.txt").read_text().strip()
        build_log = work / f"rust-{platform}-pinned.log"
        builder = {"image_manifest": "sha256:5e2214abe154fe26e39f64488952e5c991eeed1d6d6da7cc8381ae83927f0cfc",
                   "os": "Debian Bookworm; builder glibc 2.36", "toolchain_override": "RUSTUP_TOOLCHAIN=1.96.0",
                   "emulated": platform == "linux-x64"}
    if assembly.RUSTC_SHA not in rustc or "release: 1.96.0" not in rustc or "Finished `release`" not in build_log.read_text():
        raise ValueError("Rust compiler/build evidence mismatch")
    paths = {f"lib/{assembly.CLI}": java_source / "contract-cli/target" / assembly.CLI,
             f"lib/{assembly.SERVICE}": java_source / "contract-service/target" / assembly.SERVICE,
             "bin/dcgaimodel": binary}
    assembly.validate_binary(binary.read_bytes(), platform)
    evidence = work / f"evidence-{platform}"
    audit = json.loads((evidence / "license-audit.json").read_text())
    if audit["missing_license_text"]:
        raise ValueError("Unresolved license-text inventory")
    provenance = {
        "version": assembly.VERSION, "java_build_commit": assembly.JAVA_SHA,
        "rust_commit": assembly.RUST_SHA, "target": target,
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
    stage(parser.parse_args())
