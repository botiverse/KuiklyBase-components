#!/usr/bin/env python3
"""Generate a synthetic but structurally-valid DatetimeKMM publication matrix for
terminal-verifier self-tests. Produces every publication's full immutable file
set (per scripts/publish-lib.sh publication_urls) with valid zip archives
carrying the real legal bytes and well-formed module/POM metadata carrying exact
coordinates, so verify-published.sh can run end-to-end on any host (no iOS/OHOS
toolchain needed).

Usage: gen-test-publication.py <out-m2-root> <version> <legal-dir>
"""
import json
import os
import sys
import zipfile

GROUP = "build.raft.kuiklybase"
LEGAL_FILES = ["LICENSE.txt", "NOTICE.txt", "PROVENANCE.md"]


def write_archive(path, legal_dir):
    """Create a valid zip archive carrying the legal files under META-INF/."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for f in LEGAL_FILES:
            z.write(os.path.join(legal_dir, f), f"META-INF/{f}")


def write_aar(path, legal_dir):
    """Create a valid AAR whose classes.jar is itself a jar carrying the legal
    files under META-INF/ (mirrors the real Android publication, where the legal
    bytes live inside the AAR's classes.jar)."""
    import io
    os.makedirs(os.path.dirname(path), exist_ok=True)
    classes_buf = io.BytesIO()
    with zipfile.ZipFile(classes_buf, "w", zipfile.ZIP_DEFLATED) as cj:
        for f in LEGAL_FILES:
            cj.write(os.path.join(legal_dir, f), f"META-INF/{f}")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("classes.jar", classes_buf.getvalue())
        z.writestr("AndroidManifest.xml", "<manifest/>\n")
        z.writestr("R.txt", "")


def write_text(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(content)


def module_json(artifact, version, available_at=None):
    doc = {
        "formatVersion": "1.1",
        "component": {"group": GROUP, "module": artifact, "version": version,
                      "attributes": {"org.gradle.status": "release"}},
        "createdBy": {"gradle": {"version": "8.13"}},
        "variants": [],
    }
    for module, ver in (available_at or []):
        doc["variants"].append({
            "name": f"{module}ApiElements",
            "attributes": {"org.gradle.category": "library"},
            "available-at": {
                "url": f"../../{module}/{ver}/{module}-{ver}.module",
                "group": GROUP, "module": module, "version": ver,
            },
        })
    return json.dumps(doc, indent=2)


def pom_xml(artifact, version):
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{GROUP}</groupId>
  <artifactId>{artifact}</artifactId>
  <version>{version}</version>
  <packaging>pom</packaging>
</project>
"""


def pub(out, artifact, version, files, legal_dir, available_at=None):
    base = os.path.join(out, "build", "raft", "kuiklybase", artifact, version)
    p = f"{artifact}-{version}"
    for kind in files:
        if kind == "main-jar":
            write_archive(os.path.join(base, f"{p}.jar"), legal_dir)
        elif kind == "main-aar":
            write_aar(os.path.join(base, f"{p}.aar"), legal_dir)
        elif kind == "main-klib":
            write_archive(os.path.join(base, f"{p}.klib"), legal_dir)
        elif kind == "sources":
            write_archive(os.path.join(base, f"{p}-sources.jar"), legal_dir)
        elif kind == "metadata-jar":
            write_archive(os.path.join(base, f"{p}-metadata.jar"), legal_dir)
        elif kind == "cinterop":
            write_archive(os.path.join(base, f"{p}-cinterop-timeService.klib"), legal_dir)
        elif kind == "tooling":
            write_text(os.path.join(base, f"{p}-kotlin-tooling-metadata.json"),
                       json.dumps({"formatVersion": "1.1", "component": {"group": GROUP, "module": artifact, "version": version}}))
    write_text(os.path.join(base, f"{p}.module"), module_json(artifact, version, available_at))
    write_text(os.path.join(base, f"{p}.pom"), pom_xml(artifact, version))


def main():
    out, version, legal_dir = sys.argv[1], sys.argv[2], sys.argv[3]
    ohos = f"{version}-ohos"
    ios_targets = ["datetime-iosx64", "datetime-iosarm64", "datetime-iossimulatorarm64"]

    pub(out, "datetime", version,
        ["main-jar", "sources", "tooling"], legal_dir,
        available_at=[("datetime-android", version)] + [(t, version) for t in ios_targets])
    pub(out, "datetime-android", version, ["main-aar", "sources"], legal_dir)
    for t in ios_targets:
        pub(out, t, version, ["main-klib", "metadata-jar", "sources"], legal_dir)
    pub(out, "datetime", ohos,
        ["main-jar", "sources", "tooling"], legal_dir,
        available_at=[("datetime-ohosarm64", ohos)])
    pub(out, "datetime-ohosarm64", ohos, ["main-klib", "sources", "cinterop"], legal_dir)
    print(f"generated synthetic publication matrix at {out} (version={version})")


if __name__ == "__main__":
    main()
