#!/usr/bin/env python3
"""Assemble an allowlisted local-demo archive from already-built, reviewed inputs.

This does not build binaries, generate legal attestations, or publish a release.
"""
import argparse
import gzip
import hashlib
import io
import json
import re
from datetime import datetime
from pathlib import Path
import subprocess
import tarfile
import tempfile
import zipfile

PIN_FILE = Path(__file__).with_name("release-pins.json")
PINS = json.loads(PIN_FILE.read_text())
VERSION = PINS["version"]
JAVA_SHA = PINS["java_build_commit"]
RUST_SHA = PINS["rust_commit"]
RUSTC_SHA = PINS["rustc_commit"]
TARGETS = PINS["targets"]
JDK_HASHES = set(PINS["jdk_archive_sha256"])
FROZEN = PINS["frozen_artifacts"]
CLI = f"contract-cli-{VERSION}-all.jar"
SERVICE = f"contract-service-{VERSION}.jar"
TEMPLATES = Path(__file__).resolve().parents[2] / "packaging/local"
RUNNER = Path(__file__).with_name("test-extracted-package.py")
TOOLING = {
    "scripts/release/assemble-local.py": Path(__file__),
    "scripts/release/stage-local-inputs.py": Path(__file__).with_name("stage-local-inputs.py"),
    "scripts/release/collect-dependency-evidence.py": Path(__file__).with_name("collect-dependency-evidence.py"),
    "scripts/release/test-extracted-package.py": RUNNER,
    "scripts/release/release-pins.json": PIN_FILE,
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def git_file(repo, revision, name):
    return subprocess.check_output(["git", "-C", str(repo), "show", f"{revision}:{name}"])


def git_file_optional(repo, revision, name):
    result = subprocess.run(["git", "-C", str(repo), "show", f"{revision}:{name}"],
                            capture_output=True)
    return result.stdout if result.returncode == 0 else None


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


def development_identity(info):
    version = info.get("version", "")
    require(bool(re.fullmatch(r"[0-9][A-Za-z0-9.-]*-dev(?:\.[A-Za-z0-9-]+)*", version))
            and "rc" not in version.lower(), "Development version must be distinct, end in -dev[.identifier], and not be an RC")
    require(info.get("base_version") == VERSION, "Development base version mismatch")
    require(info.get("assembled_at", "").endswith("Z"), "Explicit UTC assembly timestamp required")
    datetime.fromisoformat(info["assembled_at"].replace("Z", "+00:00"))
    source = info.get("packaging_source", {})
    require(bool(re.fullmatch(r"[0-9a-f]{40}", source.get("commit", "")))
            and isinstance(source.get("dirty"), bool)
            and bool(re.fullmatch(r"[0-9a-f]{64}", source.get("worktree_inventory_sha256", ""))),
            "Explicit packaging commit, dirty state and working-tree inventory digest required")
    java_commit, rust_commit = info.get("java_build_commit"), info.get("rust_commit")
    require((java_commit is None) == (rust_commit is None),
            "Development Java and Rust build commits must be supplied together")
    if java_commit is not None:
        require(bool(re.fullmatch(r"[0-9a-f]{40}", java_commit)),
                "Development Java build commit must be a full SHA")
        require(rust_commit == RUST_SHA,
                "Development Rust commit must match the frozen model/runtime pin")
        require(source["dirty"] is False,
                "Current-source development assembly requires a clean packaging commit")
        require(source["commit"] == java_commit,
                "Current-source Java and packaging commits must match")
    return version


def development_commits(info):
    """Return build commits plus whether current-source development mode is active."""
    if info is None or info.get("java_build_commit") is None:
        return JAVA_SHA, RUST_SHA, False
    return info["java_build_commit"], info["rust_commit"], True


def template_data(name, java_repo, development):
    """Read source-controlled packaging bytes for clean current-source development."""
    if development is None or development.get("java_build_commit") is None:
        return template_path(name).read_bytes()
    candidates = [f"packaging/local/{name}"]
    if name in {"bin/dcg", "bin/start", "bin/stop", "bin/status"}:
        candidates.append(f"packaging/local/{name}.sh")
    found = [data for candidate in candidates
             if (data := git_file_optional(java_repo, development["packaging_source"]["commit"], candidate)) is not None]
    require(len(found) == 1, f"Expected one source-controlled packaging template for {name}")
    return found[0]


def verify_packaging_tooling(java_repo, revision):
    for name, path in TOOLING.items():
        require(git_file(java_repo, revision, name) == path.read_bytes(),
                f"Packaging tool differs from recorded commit: {name}")


def status_protocol(data):
    text = data.decode("utf-8")
    if "AI advisory mode:" in text and "Rust advisory process:" in text:
        return "advisory-v2"
    if "stopped (or stale identity record)" in text and "package-owned listener without PID record" in text:
        return "legacy-v1"
    raise ValueError("Unsupported packaged bin/status protocol")


def check_developer_paths(name, data):
    # Inspect compressed Java members too; a raw archive scan misses those paths.
    # Do not flag upstream URL paths such as /home/standards or upstream CI
    # constants. Check this host's home plus macOS developer/temp path roots.
    for prefix in (str(Path.home()).rstrip("/").encode() + b"/", b"/Users/",
                   b"/private/var/folders/", b"/var/folders/"):
        require(prefix not in data, f"Developer absolute path in {name}")
    if name.endswith((".jar", ".zip")):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for entry in archive.infolist():
                check_developer_paths(name + "!" + entry.filename, archive.read(entry))


def payload(args):
    inputs = Path(args.inputs)
    development_path = getattr(args, "development_info", None)
    development = json.loads(Path(development_path).read_bytes()) if development_path else None
    if development is not None:
        development_identity(development)
    java_commit, rust_commit, current_source = development_commits(development)
    if current_source:
        verify_packaging_tooling(args.java_repo, development["packaging_source"]["commit"])
    files = {f"lib/{CLI}": read_input(inputs, CLI), f"lib/{SERVICE}": read_input(inputs, SERVICE),
             "bin/dcgaimodel": read_input(inputs, "dcgaimodel")}
    validate_jar(files[f"lib/{CLI}"], "contract-cli")
    validate_jar(files[f"lib/{SERVICE}"], "contract-service")
    validate_binary(files["bin/dcgaimodel"], args.platform)
    provenance = json.loads(read_input(inputs, "build-info.json"))
    for key, expected in {"version": VERSION, "java_build_commit": java_commit,
                          "rust_commit": rust_commit, "target": TARGETS[args.platform],
                          "java_vendor": "Eclipse Temurin", "java_version": "21.0.12.1+1",
                          "release_pins_sha256": digest(PIN_FILE.read_bytes())}.items():
        require(provenance.get(key) == expected, f"Provenance mismatch: {key}")
    if current_source:
        require(provenance.get("development_source") == {
                    "java_build_commit": java_commit, "rust_commit": rust_commit,
                    "packaging_commit": development["packaging_source"]["commit"], "clean": True},
                "Provenance mismatch: development_source")
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
    if current_source:
        own = [component for component in sbom.get("components", [])
               if component.get("group") == "com.ideas.contracts"]
        expected_ref = f"https://github.com/S-idd/data-contract-governance/tree/{java_commit}"
        require(own and all(expected_ref in [ref.get("url") for ref in component.get("externalReferences", [])]
                            for component in own),
                "SBOM does not identify the development Java build commit")
    notices = read_input(inputs, "THIRD-PARTY-NOTICES.txt")
    require(len(notices.strip()) > 80, "Dependency notices missing/empty")
    if development is not None or "dependency_evidence" in provenance:
        for key, data in [("notices_sha256", notices), ("sbom_sha256", sbom_data)]:
            require(provenance.get("dependency_evidence", {}).get(key) == digest(data),
                    f"Dependency evidence digest mismatch: {key}")
    for name in ["bin/dcg", "bin/start", "bin/stop", "bin/status", "README.md", "RELEASE-NOTES.md",
                 "config/application-local-demo.properties.example"]:
        files[name] = template_data(name, args.java_repo, development)
    for name in ["LICENSE", "contracts/policy-packs.json", "contracts/orders.created/metadata.yaml",
                 "contracts/orders.created/v1.json", "contracts/orders.created/v2.json"]:
        files[name] = git_file(args.java_repo, java_commit, name)
    if not current_source:
        require(digest(files["contracts/policy-packs.json"]) == "83cf0b7c20fee50f7090529505d2981d0eec731ff40ec4c5f5b31fe016cc6bad", "Java policy hash mismatch")
    files["licenses/dcgaimodel-LICENSE"] = git_file(args.rust_repo, rust_commit, "LICENSE")
    for name, expected in FROZEN.items():
        data = git_file(args.rust_repo, rust_commit, name)
        require(digest(data) == expected, f"Frozen source hash mismatch: {name}")
        files[f"model/{name}"] = data
    if not current_source:
        provenance["java_preparation_commit"] = "71e0823ac359f63c536ef19f0b79352ba81c1ef5"
    provenance["source_artifacts"] = {name: digest(data) for name, data in files.items()
                                      if name.startswith(("model/", "contracts/"))}
    provenance["packaging_inputs"] = {name: digest(data) for name, data in files.items()
                                      if name in ["bin/dcg", "bin/start", "bin/stop", "bin/status", "README.md", "RELEASE-NOTES.md"] or name.startswith("config/")}
    provenance["assembler_sha256"] = digest(Path(__file__).read_bytes())
    if current_source:
        provenance["packaging_tooling_sha256"] = {
            name: digest(path.read_bytes()) for name, path in sorted(TOOLING.items())}
    provenance["transformations"] = "Source files copied verbatim; bin modes 0755, others 0644; archive uid/gid/mtime normalized to zero."
    if development is not None:
        version = development_identity(development)
        provenance.update(base_version=VERSION, version=version, development=development,
                          publication_status="Unpublished local development package; not an accepted RC")
        # Runtime artifact filenames retain their verified embedded Maven version.
        old = f"/.local/share/dcg/{VERSION}".encode()
        require(files["bin/dcg"].count(old) == 1, "Expected one default state-directory placeholder")
        files["bin/dcg"] = files["bin/dcg"].replace(old, f"/.local/share/dcg/{version}".encode())
        for name in ("README.md", "RELEASE-NOTES.md"):
            files[name] = (f"# Development package {version}\n\nUnpublished local development assembly. "
                           f"Runtime artifacts retain base version {VERSION}. "
                           "No RC acceptance is implied. See build-info.json for provenance.\n\n"
                           "The following base-package documentation is retained for reference.\n\n").encode() + files[name]
        provenance["transformations"] += " Development-only: isolate default state directory and prepend development identity to README/release notes."
        provenance["packaged_launcher_sha256"] = {name: digest(files[name]) for name in
                                                   ("bin/dcg", "bin/start", "bin/stop", "bin/status")}
    files["THIRD-PARTY-NOTICES.txt"] = notices
    files["sbom.cdx.json"] = sbom_data
    files["build-info.json"] = (json.dumps(provenance, indent=2, sort_keys=True) + "\n").encode()
    if development is not None:
        for name, data in files.items():
            check_developer_paths(name, data)
    files["SHA256SUMS"] = "".join(f"{digest(data)}  {name}\n" for name, data in sorted(files.items())).encode()
    return files


def compatibility_manifest(archive, archive_sha256, files):
    info = json.loads(files["build-info.json"])
    status_sha256 = digest(files["bin/status"])
    entry = {
        "archive_sha256": archive_sha256,
        "java_build_commit": info["java_build_commit"],
        "rust_commit": info["rust_commit"],
        "status_launcher_sha256": status_sha256,
        "status_protocol": status_protocol(files["bin/status"]),
    }
    if info.get("development", {}).get("packaging_source", {}).get("commit"):
        entry["packaging_commit"] = info["development"]["packaging_source"]["commit"]
    return {"schema_version": 1, "runner_sha256": digest(RUNNER.read_bytes()),
            "archives": {archive.name: entry}}


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


def publish_output(files, output, version, platform):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    require(not any(output.iterdir()), "Output directory must be empty; do not overwrite prior artifacts")
    root = f"dcg-{version}-{platform}"
    archive = output / f"{root}.tar.gz"
    checksum = write_archive(files, archive, root)
    manifest = output / "archive-runner-compatibility.json"
    manifest.write_text(json.dumps(compatibility_manifest(archive, checksum, files), indent=2,
                                   sort_keys=True) + "\n")
    (output / "SHA256SUMS").write_text(
        f"{checksum}  {archive.name}\n{digest(manifest.read_bytes())}  {manifest.name}\n",
        encoding="ascii")
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ["inputs", "java-repo", "rust-repo", "output"]:
        parser.add_argument(f"--{option}", required=True)
    parser.add_argument("--platform", choices=TARGETS, required=True)
    parser.add_argument("--development-info",
                        help="Frozen development identity/provenance JSON; may select a clean Java source commit while retaining the base artifact version and Rust pin")
    args = parser.parse_args()
    files = payload(args)
    version = development_identity(json.loads(Path(args.development_info).read_bytes())) if args.development_info else VERSION
    archive = publish_output(files, args.output, version, args.platform)
    print(archive)


if __name__ == "__main__":
    main()
