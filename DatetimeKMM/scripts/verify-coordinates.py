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


def available_at_records(doc):
    """Return EVERY variants[*].available-at record as a list (no collapsing).
    Real Gradle root metadata emits multiple records per target (one per variant
    view), so collapsing module->record would silently drop a bad record behind a
    good one."""
    records = []
    for variant in doc.get("variants", []):
        avail = variant.get("available-at")
        if avail:
            records.append(avail)
    return records


def check_root_targets(doc, label, root_version, expected):
    # Validate every available-at record individually (exact group/module/version/
    # url); a single bad record among duplicates must fail the whole readback.
    records = available_at_records(doc)
    seen_modules = set()
    for avail in records:
        module = avail.get("module")
        group = avail.get("group")
        version = avail.get("version")
        url = avail.get("url", "")
        if group != GROUP:
            fail(f"{label}: available-at {module} group {group} != {GROUP}")
        if version != root_version:
            fail(f"{label}: available-at {module} version {version} != {root_version}")
        expected_url = f"../../{module}/{version}/{module}-{version}.module"
        if url != expected_url:
            fail(f"{label}: available-at {module} url {url} != {expected_url}")
        seen_modules.add(module)
    # Strict set equality: the unique referenced module set must be exactly the
    # expected target set — no missing target and no unexpected/foreign target
    # (e.g. an extra datetime-jvm, or a cross-tree datetime-ohosarm64 in the
    # normal root).
    missing = expected - seen_modules
    unexpected = seen_modules - expected
    if missing:
        fail(f"{label}: missing expected variant references {sorted(missing)}")
    if unexpected:
        fail(f"{label}: unexpected/foreign variant references {sorted(unexpected)}")
    ok(f"{label} available-at exact: {sorted(expected)} "
       f"({len(records)} records, unique modules strict-equal)")


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
        version,
        expected={"datetime-android", "datetime-iosx64", "datetime-iosarm64", "datetime-iossimulatorarm64"},
    )

    # OHOS root metadata: component + OHOS arm64 target, no Android/iOS.
    ohos_root = load_module(m2, "datetime", ohos_version)
    check_component(ohos_root, "datetime", ohos_version, "ohos-root")
    check_pom(m2, "datetime", ohos_version, "ohos-root")
    check_root_targets(
        ohos_root,
        "ohos-root",
        ohos_version,
        expected={"datetime-ohosarm64"},
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
