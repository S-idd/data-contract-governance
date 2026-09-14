#!/usr/bin/env python3
"""Assemble an allowlisted local-demo archive from already-built, reviewed inputs.

This does not build binaries, generate legal attestations, or publish a release.
"""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import zipfile

VERSION = "4.0.0-alpha.1"
JAVA_SHA = "dac3ed509d03e1bef75c47b497ca80bbdd1f2e04"
RUST_SHA = "ef819fe58865c643a240ee067cf61d506f04a778"
RUSTC_SHA = "ac68faa20c58cbccd01ee7208bf3b6e93a7d7f96"
TARGETS = {"macos-arm64": "aarch64-apple-darwin", "macos-x64": "x86_64-apple-darwin",
           "linux-arm64": "aarch64-unknown-linux-gnu", "linux-x64": "x86_64-unknown-linux-gnu"}
JDK_HASHES = {
    "3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998",
    "44db0f08196daf19a47f90d13388b0c943b67663cb537f998fe29e836fa842ce",
    "23e37e026f12f3e706f18938ff611db3032d075b09d0879a25d06718c773e223",
    "ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94",
}
FROZEN = {
    "data/inference/frozen-v9/policy-packs-v5-compositional.json": "8f82b058f81ace43c89180803c7ec26ac734b84d0092036a77115688337e1bb6",
    "data/experiments/v9-multiclass-cpu-100e/models/seed-20260826-normal-family-split.json": "5da2fedbee5d1b3c84c79cb75e2cd10c0b3462066b66571570e93fb7ccd84988",
    "data/experiments/v9-multiclass-cpu-100e/models/seed-20260827-normal-family-split.json": "bd464322c272b8ec1d5ab88605022d4a48e3738b63ab1791a4c7e56f37222ac8",
    "data/experiments/v9-multiclass-cpu-100e/models/seed-20260828-normal-family-split.json": "24ec9e4e758370093c228af34e3b05ac65820b3fd413ca80a269206ce1edd2c0",
}
CLI = f"contract-cli-{VERSION}-all.jar"
SERVICE = f"contract-service-{VERSION}.jar"
TEMPLATES = Path(__file__).resolve().parents[2] / "packaging/local"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def git_file(repo, revision, name):
    return subprocess.check_output(["git", "-C", str(repo), "show", f"{revision}:{name}"])


def read_input(root, name):
    path = root / name
    require(path.is_file() and not path.is_symlink(), f"Missing regular input file: {path}")
    return path.read_bytes()


def template_path(name):
    """Source scripts may carry .sh; the archive contract remains extensionless."""
    candidates = [TEMPLATES / name]
    if name in {"bin/dcg", "bin/start", "bin/stop", "bin/status"}:
        candidates.append(TEMPLATES / (name + ".sh"))
    existing = [path for path in candidates if path.is_file()]
    require(len(existing) == 1, f"Expected one unambiguous template for {name}: {existing}")
    return existing[0]


def validate_jar(data, artifact):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        props = jar.read(f"META-INF/maven/com.ideas.contracts/{artifact}/pom.properties").decode()
        require(f"version={VERSION}" in props.splitlines(), f"Wrong embedded Maven version: {artifact}")
        manifest = jar.read("META-INF/MANIFEST.MF").decode()
        require("Main-Class:" in manifest, f"Not an executable JAR: {artifact}")


def validate_binary(data, platform):
    if platform.startswith("linux-"):
        require(data[:6] == b"\x7fELF\x02\x01", "Expected little-endian 64-bit ELF")
        machine = int.from_bytes(data[18:20], "little")
        require(machine == (183 if platform.endswith("arm64") else 62), "Wrong ELF architecture")
    else:
        require(data[:4] == b"\xcf\xfa\xed\xfe", "Expected thin 64-bit Mach-O")
        cpu = int.from_bytes(data[4:8], "little")
        require(cpu == (0x100000c if platform.endswith("arm64") else 0x1000007), "Wrong Mach-O architecture")


def payload(args):
    inputs = Path(args.inputs)
    files = {f"lib/{CLI}": read_input(inputs, CLI), f"lib/{SERVICE}": read_input(inputs, SERVICE),
             "bin/dcgaimodel": read_input(inputs, "dcgaimodel")}
    validate_jar(files[f"lib/{CLI}"], "contract-cli")
    validate_jar(files[f"lib/{SERVICE}"], "contract-service")
    validate_binary(files["bin/dcgaimodel"], args.platform)
    provenance = json.loads(read_input(inputs, "build-info.json"))
    for key, expected in {"version": VERSION, "java_build_commit": JAVA_SHA,
                          "rust_commit": RUST_SHA, "target": TARGETS[args.platform],
                          "java_vendor": "Eclipse Temurin", "java_version": "21.0.12.1+1"}.items():
        require(provenance.get(key) == expected, f"Provenance mismatch: {key}")
    require(provenance.get("jdk_archive_sha256") in JDK_HASHES, "Unverified JDK archive digest")
    require(RUSTC_SHA in provenance.get("rustc_verbose", ""), "Wrong/missing full Rust compiler identity")
    require(provenance.get("cargo_version", "").startswith("cargo 1.96.0 "), "Wrong Cargo version")
    for path, data in files.items():
        require(provenance.get("artifacts", {}).get(path) == digest(data), f"Input digest mismatch: {path}")
    sbom_data = read_input(inputs, "sbom.cdx.json")
    sbom = json.loads(sbom_data)
    require(sbom.get("bomFormat") == "CycloneDX", "SBOM must be CycloneDX JSON")
    purls = [c.get("purl", "") for c in sbom.get("components", [])]
    require(any(p.startswith("pkg:maven/") for p in purls) and any(p.startswith("pkg:cargo/") for p in purls),
            "SBOM must describe both Maven and Cargo runtime components")
    notices = read_input(inputs, "THIRD-PARTY-NOTICES.txt")
    require(len(notices.strip()) > 80, "Dependency notices missing/empty")
    for name in ["bin/dcg", "bin/start", "bin/stop", "bin/status", "README.md", "RELEASE-NOTES.md",
                 "config/application-local-demo.properties.example"]:
        files[name] = template_path(name).read_bytes()
    for name in ["LICENSE", "contracts/policy-packs.json", "contracts/orders.created/metadata.yaml",
                 "contracts/orders.created/v1.json", "contracts/orders.created/v2.json"]:
        files[name] = git_file(args.java_repo, JAVA_SHA, name)
    require(digest(files["contracts/policy-packs.json"]) == "83cf0b7c20fee50f7090529505d2981d0eec731ff40ec4c5f5b31fe016cc6bad", "Java policy hash mismatch")
    files["licenses/dcgaimodel-LICENSE"] = git_file(args.rust_repo, RUST_SHA, "LICENSE")
    for name, expected in FROZEN.items():
        data = git_file(args.rust_repo, RUST_SHA, name)
        require(digest(data) == expected, f"Frozen source hash mismatch: {name}")
        files[f"model/{name}"] = data
    provenance["java_preparation_commit"] = "71e0823ac359f63c536ef19f0b79352ba81c1ef5"
    provenance["source_artifacts"] = {name: digest(data) for name, data in files.items()
                                      if name.startswith(("model/", "contracts/"))}
    provenance["packaging_inputs"] = {name: digest(data) for name, data in files.items()
                                      if name in ["bin/dcg", "bin/start", "bin/stop", "bin/status", "README.md", "RELEASE-NOTES.md"] or name.startswith("config/")}
    provenance["assembler_sha256"] = digest(Path(__file__).read_bytes())
    provenance["transformations"] = "Source files copied verbatim; bin modes 0755, others 0644; archive uid/gid/mtime normalized to zero."
    files["THIRD-PARTY-NOTICES.txt"] = notices
    files["sbom.cdx.json"] = sbom_data
    files["build-info.json"] = (json.dumps(provenance, indent=2, sort_keys=True) + "\n").encode()
    files["SHA256SUMS"] = "".join(f"{digest(data)}  {name}\n" for name, data in sorted(files.items())).encode()
    return files


def write_archive(files, destination, root):
    # Construct before publishing; never expose a partially written archive or replace an existing one.
    with tempfile.TemporaryFile() as temporary:
        with gzip.GzipFile(fileobj=temporary, mode="wb", filename="", mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
                directories = {root}
                for name in files:
                    directories.update(str(p) for p in (Path(root) / name).parents if str(p) != ".")
                for name in sorted(directories):
                    entry = tarfile.TarInfo(name)
                    entry.type, entry.mode = tarfile.DIRTYPE, 0o755
                    archive.addfile(entry)
                for name, data in sorted(files.items()):
                    entry = tarfile.TarInfo(f"{root}/{name}")
                    entry.size, entry.mode = len(data), 0o755 if name.startswith("bin/") else 0o644
                    archive.addfile(entry, io.BytesIO(data))
        temporary.seek(0)
        data = temporary.read()
        with destination.open("xb") as output:
            output.write(data)
    return digest(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ["inputs", "java-repo", "rust-repo", "output"]:
        parser.add_argument(f"--{option}", required=True)
    parser.add_argument("--platform", choices=TARGETS, required=True)
    args = parser.parse_args()
    files = payload(args)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    # One invocation owns a fresh output directory, avoiding races updating release checksums.
    require(not any(output.iterdir()), "Output directory must be empty; do not overwrite prior artifacts")
    root = f"dcg-{VERSION}-{args.platform}"
    archive = output / f"{root}.tar.gz"
    checksum = write_archive(files, archive, root)
    (output / "SHA256SUMS").write_text(f"{checksum}  {archive.name}\n", encoding="ascii")
    print(archive)


if __name__ == "__main__":
    main()
