#!/usr/bin/env python3
"""Merge real Maven/Cargo inventories and preserve upstream license/notice text.

Input: CycloneDX Maven 2.9.2 aggregate BOM (compile/runtime, includeLicenseText=true)
and cargo +1.96.0 metadata --locked --filter-platform TARGET. No legal attestation.
"""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile
from urllib.parse import parse_qs, urlsplit


def sha(data):
    return hashlib.sha256(data).hexdigest()


def notice_name(name):
    return bool(re.match(r"^(licen[cs]e|notice|copying|copyright|authors)([._-].*)?$", Path(name).name, re.I))


def collect(args):
    java = args.java_source
    bom = json.loads((java / "target/bom.json").read_text())
    metadata = json.loads(args.cargo_metadata.read_text())
    supplements = json.loads(Path(__file__).with_name("license-sources.json").read_text())
    report = {"maven_components": 0, "cargo_components": 0, "missing_license_text": [],
              "named_or_copyleft_licenses": [], "source_texts": []}
    parts = ["DCG LOCAL DEMO — THIRD-PARTY NOTICES\n\n"
             "Generated from the actual resolved Maven artifacts and target-filtered Cargo metadata.\n"
             "Upstream license/copyright/notice text is preserved below. Build-only Cargo dependencies\n"
             "are marked excluded in the SBOM; their notices are included conservatively.\n"
             "This inventory is not a legal approval or a source-code distribution offer.\n"
             "Review copyleft/exception conditions before public redistribution. Java is not bundled.\n"]

    def add(origin, data):
        if not data.strip():
            return False
        report["source_texts"].append({"origin": origin, "sha256": sha(data)})
        parts.append(f"\n{'=' * 72}\n{origin}\nSHA-256: {sha(data)}\n{'=' * 72}\n" + data.decode("utf-8", "replace"))
        return True

    for component in bom["components"]:
        report["maven_components"] += 1
        group, name, version = component["group"], component["name"], component["version"]
        purl = component["purl"]
        if group == "com.ideas.contracts":
            component["licenses"] = [{"license": {"id": "Apache-2.0"}}]
            component["externalReferences"] = [{"type": "vcs", "url": "https://github.com/S-idd/data-contract-governance/tree/4ca0fa42c769749f37fd1d5306bbf5b1c0054aa0"}]
            add(purl + " / LICENSE", (java / "LICENSE").read_bytes())
            continue
        qualifiers = parse_qs(urlsplit(purl).query)
        classifier = qualifiers.get("classifier", [""])[0]
        suffix = "-" + classifier if classifier else ""
        jar_path = args.maven_repo / group.replace(".", "/") / name / version / f"{name}-{version}{suffix}.jar"
        jar_data = jar_path.read_bytes()
        declared = [h["content"] for h in component.get("hashes", []) if h["alg"] == "SHA-256"]
        if declared and sha(jar_data) not in declared:
            raise ValueError(f"Maven artifact differs from generated BOM: {purl}")
        license_summary = [item.get("license", {}).get("id", item.get("license", {}).get("name", item.get("expression", ""))) for item in component.get("licenses", [])]
        parts.append(f"\nComponent: {purl}\nDeclared licenses: {'; '.join(license_summary)}\n")
        if any("GPL" in text or "GNU" in text or "EPL" in text for text in license_summary):
            report["named_or_copyleft_licenses"].append({"purl": purl, "licenses": license_summary})
        found = False
        with zipfile.ZipFile(jar_path) as jar:
            for entry in sorted(jar.namelist()):
                if not entry.endswith("/") and notice_name(entry):
                    found = add(purl + " / " + entry, jar.read(entry)) or found
        for item in component.get("licenses", []):
            license = item.get("license", {})
            text = license.get("text", {})
            if text.get("content"):
                data = base64.b64decode(text["content"]) if text.get("encoding") == "base64" else text["content"].encode()
                found = add(purl + " / BOM license " + license.get("id", license.get("name", "")), data) or found
        if not found:
            report["missing_license_text"].append(purl)

    # Spring Boot repackage injects launcher/jarmode code outside the ordinary Maven
    # runtime dependency graph. Inventory and verify those actual packaged bytes too.
    service_path = java / "contract-service/target/contract-service-4.0.0-alpha.1.jar"
    service_ref = "pkg:maven/com.ideas.contracts/contract-service@4.0.0-alpha.1?type=jar"
    with zipfile.ZipFile(service_path) as service:
        manifest = service.read("META-INF/MANIFEST.MF").decode()
        boot_version = re.search(r"^Spring-Boot-Version: ([^\r\n]+)", manifest, re.M).group(1)
        tools_path = args.maven_repo / "org/springframework/boot/spring-boot-loader-tools" / boot_version / f"spring-boot-loader-tools-{boot_version}.jar"
        with zipfile.ZipFile(tools_path) as tools:
            for name, entry in [("spring-boot-loader", "META-INF/loader/spring-boot-loader.jar"),
                                ("spring-boot-jarmode-tools", "META-INF/jarmode/spring-boot-jarmode-tools.jar")]:
                data = tools.read(entry)
                if name.endswith("jarmode-tools"):
                    if service.read(f"BOOT-INF/lib/{name}-{boot_version}.jar") != data:
                        raise ValueError("Packaged jarmode differs from build plugin resource")
                purl = f"pkg:maven/org.springframework.boot/{name}@{boot_version}?type=jar"
                with zipfile.ZipFile(io.BytesIO(data)) as embedded:
                    for path in embedded.namelist():
                        if name == "spring-boot-loader" and path.endswith(".class"):
                            if embedded.read(path) != service.read(path):
                                raise ValueError(f"Packaged launcher differs: {path}")
                        if notice_name(path):
                            add(purl + " / " + path, embedded.read(path))
                bom["components"].append({"type": "library", "group": "org.springframework.boot", "name": name,
                                          "version": boot_version, "purl": purl, "bom-ref": purl,
                                          "hashes": [{"alg": "SHA-256", "content": sha(data)}],
                                          "licenses": [{"license": {"id": "Apache-2.0"}}],
                                          "properties": [{"name": "dcg:packaging-origin", "value": "Spring Boot repackage injected resource"}]})
                for node in bom["dependencies"]:
                    if node["ref"] == service_ref:
                        node["dependsOn"].append(purl)
                report["maven_components"] += 1
        declared_jars = {f"{c['name']}-{c['version']}.jar" for c in bom["components"]}
        missing = [n for n in service.namelist() if n.startswith("BOOT-INF/lib/") and n.endswith(".jar") and Path(n).name not in declared_jars]
        if missing:
            raise ValueError(f"Packaged dependencies missing from BOM: {missing}")

    # Traverse resolved normal/build edges, never root dev/test dependencies. Mark build-only
    # and proc-macro closures excluded, instead of pretending these are linked runtime code.
    packages = {p["id"]: p for p in metadata["packages"]}
    nodes = {n["id"]: n for n in metadata["resolve"]["nodes"]}
    visited = {}
    pending = [(metadata["resolve"]["root"], False)]
    while pending:
        package_id, build_only = pending.pop()
        package = packages[package_id]
        build_only = build_only or any("proc-macro" in t["kind"] for t in package["targets"])
        if package_id in visited and (visited[package_id] is False or build_only):
            continue
        visited[package_id] = build_only
        for dep in nodes[package_id]["deps"]:
            kinds = [k["kind"] for k in dep["dep_kinds"] if k["kind"] != "dev"]
            if kinds:
                pending.append((dep["pkg"], build_only or all(k == "build" for k in kinds)))
    rust_refs = {}
    for package_id, build_only in sorted(visited.items()):
        package = packages[package_id]
        purl = f"pkg:cargo/{package['name']}@{package['version']}"
        rust_refs[package_id] = purl
        expression = (package.get("license") or "").replace("/", " OR ")
        crate = Path(package["manifest_path"]).parent
        component = {"type": "library", "name": package["name"], "version": package["version"],
                     "bom-ref": purl, "purl": purl, "scope": "excluded" if build_only else "required"}
        if expression:
            component["licenses"] = [{"expression": expression}]
        if package.get("repository"):
            component["externalReferences"] = [{"type": "vcs", "url": package["repository"]}]
        checksum_file = crate / ".cargo-checksum.json"
        if checksum_file.exists():
            checksum = json.loads(checksum_file.read_text()).get("package")
            if checksum:
                component["hashes"] = [{"alg": "SHA-256", "content": checksum}]
                component["properties"] = [{"name": "dcg:hash-subject", "value": "upstream .crate source archive"}]
        bom["components"].append(component)
        report["cargo_components"] += 1
        parts.append(f"\nComponent: {purl}\nDeclared license: {expression}\nBuild-only: {build_only}\n")
        found = False
        for path in sorted(crate.rglob("*")):
            # Compiler caches are never license inputs, including for the root crate.
            relative = path.relative_to(crate)
            if "target" in relative.parts or ".git" in relative.parts:
                continue
            if path.is_file() and not path.is_symlink() and notice_name(path.name):
                found = add(purl + " / " + relative.as_posix(), path.read_bytes()) or found
        for supplement in supplements:
            if f"{package['name']}@{package['version']}" in supplement["crates"]:
                vcs = json.loads((crate / ".cargo_vcs_info.json").read_text())
                if vcs["git"]["sha1"] != supplement["commit"]:
                    raise ValueError(f"Supplemental license source revision mismatch: {purl}")
                data = (args.license_supplements / supplement["file"]).read_bytes()
                if sha(data) != supplement["sha256"]:
                    raise ValueError(f"Supplemental license checksum mismatch: {purl}")
                found = add(purl + " / " + supplement["url"], data) or found
        if not found:
            report["missing_license_text"].append(purl)
    for package_id in visited:
        depends = sorted({rust_refs[d["pkg"]] for d in nodes[package_id]["deps"] if d["pkg"] in rust_refs
                          and any(k["kind"] != "dev" for k in d["dep_kinds"])})
        bom.setdefault("dependencies", []).append({"ref": rust_refs[package_id], "dependsOn": depends})
    std_ref = "pkg:generic/rust-standard-library@1.96.0"
    bom["components"].append({"type": "library", "name": "rust-standard-library", "version": "1.96.0",
                              "bom-ref": std_ref, "purl": std_ref,
                              "licenses": [{"expression": "MIT OR Apache-2.0"}]})
    for node in bom["dependencies"]:
        if node["ref"] == rust_refs[metadata["resolve"]["root"]]:
            node["dependsOn"].append(std_ref)
    rust_docs = args.rust_sysroot / "share/doc/rust"
    add("Rust 1.96.0 standard library / COPYRIGHT-library.html", (rust_docs / "COPYRIGHT-library.html").read_bytes())
    for path in sorted((rust_docs / "licenses").glob("*.txt")):
        add("Rust 1.96.0 / licenses/" + path.name, path.read_bytes())
    bom["metadata"]["component"] = {"type": "application", "name": "dcg-local-demo", "version": "4.0.0-alpha.1",
                                      "bom-ref": "dcg-local-demo-4.0.0-alpha.1"}
    bom["dependencies"].append({"ref": "dcg-local-demo-4.0.0-alpha.1", "dependsOn": [
        "pkg:maven/com.ideas.contracts/contract-cli@4.0.0-alpha.1?type=jar",
        "pkg:maven/com.ideas.contracts/contract-service@4.0.0-alpha.1?type=jar", rust_refs[metadata["resolve"]["root"]]]})
    # The original Maven aggregate root is no longer the combined application root.
    bom["dependencies"] = [d for d in bom["dependencies"] if "data-contract-governance@" not in d["ref"]]
    bom["metadata"].setdefault("properties", []).extend([
        {"name": "dcg:rust-target", "value": args.target},
        {"name": "dcg:inventory-boundary", "value": "Maven compile/runtime dependencies; Cargo normal/build closure with build-only excluded; Rust standard library. OS dynamic libraries and external JDK are not distributed."}])
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "license-audit.json").write_text(json.dumps(report, indent=2) + "\n")
    if report["missing_license_text"]:
        raise ValueError("Missing upstream license text: " + ", ".join(report["missing_license_text"]))
    (args.output / "sbom.cdx.json").write_text(json.dumps(bom, indent=2) + "\n")
    (args.output / "THIRD-PARTY-NOTICES.txt").write_text("\n".join(parts) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k != "source_texts"}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ["java-source", "cargo-metadata", "maven-repo", "rust-sysroot", "license-supplements", "output"]:
        parser.add_argument("--" + field, type=Path, required=True)
    parser.add_argument("--target", required=True)
    collect(parser.parse_args())
