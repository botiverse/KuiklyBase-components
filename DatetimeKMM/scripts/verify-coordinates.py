#!/usr/bin/env python3
"""Exact coordinate / variant-reference validation for the DatetimeKMM readback.

Parses every downloaded Gradle module (.module) and Maven POM (.pom) and asserts
exact group/artifact/version (not mere well-formedness), and parses the root
modules' structured variants[*].available-at to prove the exact expected target
set (Android + all three iOS for the normal root; OHOS arm64 for the -ohos root)
with exact group/version/url plus the negative normal-vs-OHOS cross-tree
boundary. A wrong-coordinate but well-formed POM/module fails.

Usage: verify-coordinates.py <m2-root> <version>
"""
import json
import os
import sys
import xml.etree.ElementTree as ET

GROUP = "build.raft.kuiklybase"


def fail(msg):
    print(f"COORDINATE FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def ok(msg):
    print(f"  OK   {msg}")


def m2_path(m2, artifact, version, file):
    return os.path.join(m2, "build", "raft", "kuiklybase", artifact, version, file)


def load_module(m2, artifact, version):
    path = m2_path(m2, artifact, version, f"{artifact}-{version}.module")
    if not os.path.isfile(path):
        fail(f"missing module metadata: {path}")
    with open(path) as f:
        return json.load(f)


def check_component(doc, artifact, version, label):
    comp = doc.get("component", {})
    if comp.get("group") != GROUP:
        fail(f"{label}: component group {comp.get('group')} != {GROUP}")
    if comp.get("module") != artifact:
        fail(f"{label}: component module {comp.get('module')} != {artifact}")
    if comp.get("version") != version:
        fail(f"{label}: component version {comp.get('version')} != {version}")
    ok(f"{label} component {GROUP}:{artifact}:{version}")


def check_pom(m2, artifact, version, label):
    path = m2_path(m2, artifact, version, f"{artifact}-{version}.pom")
    if not os.path.isfile(path):
        fail(f"missing POM: {path}")
    root = ET.parse(path).getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}

    def text(tag):
        el = root.find(f"m:{tag}", ns)
        if el is None:  # some POMs are emitted without the namespace
            el = root.find(tag)
        return el.text.strip() if el is not None and el.text else None

    gid, aid, ver = text("groupId"), text("artifactId"), text("version")
    if gid != GROUP:
        fail(f"{label} POM groupId {gid} != {GROUP}")
    if aid != artifact:
        fail(f"{label} POM artifactId {aid} != {artifact}")
    if ver != version:
        fail(f"{label} POM version {ver} != {version}")
    ok(f"{label} POM {GROUP}:{artifact}:{version}")


def available_at_modules(doc):
    mods = {}
    for variant in doc.get("variants", []):
        avail = variant.get("available-at")
        if not avail:
            continue
        mods[avail.get("module")] = avail
    return mods


def check_root_targets(doc, label, expected, forbidden):
    mods = available_at_modules(doc)
    for module, avail in mods.items():
        if avail.get("group") != GROUP:
            fail(f"{label}: available-at {module} group {avail.get('group')} != {GROUP}")
        if avail.get("version") is None:
            fail(f"{label}: available-at {module} missing version")
    present = set(mods.keys())
    missing = expected - present
    if missing:
        fail(f"{label}: missing expected variant references {sorted(missing)}")
    illegal = present & forbidden
    if illegal:
        fail(f"{label}: illegal cross-tree variant references {sorted(illegal)}")
    # Exact group/version/url for each expected target.
    for module in expected:
        avail = mods[module]
        if avail.get("group") != GROUP:
            fail(f"{label}: target {module} group {avail.get('group')} != {GROUP}")
        url = avail.get("url", "")
        if f"{module}-{avail.get('version')}.module" not in url:
            fail(f"{label}: target {module} available-at url {url} does not point at its .module")
    ok(f"{label} variant references exact: {sorted(expected)}; cross-tree clean")


def main():
    m2, version = sys.argv[1], sys.argv[2]
    ohos_version = f"{version}-ohos"

    # Normal root metadata: component + Android + all three iOS targets, no OHOS.
    normal_root = load_module(m2, "datetime", version)
    check_component(normal_root, "datetime", version, "normal-root")
    check_pom(m2, "datetime", version, "normal-root")
    check_root_targets(
        normal_root,
        "normal-root",
        expected={"datetime-android", "datetime-iosx64", "datetime-iosarm64", "datetime-iossimulatorarm64"},
        forbidden={"datetime-ohosarm64"},
    )

    # OHOS root metadata: component + OHOS arm64 target, no Android/iOS.
    ohos_root = load_module(m2, "datetime", ohos_version)
    check_component(ohos_root, "datetime", ohos_version, "ohos-root")
    check_pom(m2, "datetime", ohos_version, "ohos-root")
    check_root_targets(
        ohos_root,
        "ohos-root",
        expected={"datetime-ohosarm64"},
        forbidden={"datetime-android", "datetime-iosx64", "datetime-iosarm64", "datetime-iossimulatorarm64"},
    )

    # Every platform publication: exact module component + POM coordinates.
    for artifact, ver in [
        ("datetime-android", version),
        ("datetime-iosx64", version),
        ("datetime-iosarm64", version),
        ("datetime-iossimulatorarm64", version),
        ("datetime-ohosarm64", ohos_version),
    ]:
        doc = load_module(m2, artifact, ver)
        check_component(doc, artifact, ver, artifact)
        check_pom(m2, artifact, ver, artifact)

    print("COORDINATE_PASS")


if __name__ == "__main__":
    main()
