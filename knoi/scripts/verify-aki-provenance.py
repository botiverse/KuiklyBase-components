#!/usr/bin/env python3
"""Verify that the vendored Aki subset exactly matches its frozen Git blobs."""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import sys


SCRIPT = Path(__file__).resolve()
KNOI_ROOT = SCRIPT.parent.parent
AKI_ROOT = KNOI_ROOT / "third_party" / "aki"
MANIFEST = AKI_ROOT / "UPSTREAM_TREE.manifest"
METADATA = AKI_ROOT / "UPSTREAM.md"
IGNORED = {"UPSTREAM.md", "UPSTREAM_TREE.manifest"}


def git_blob_id(data: bytes) -> str:
    header = f"blob {len(data)}\0".encode()
    return hashlib.sha1(header + data).hexdigest()


def fail(message: str) -> None:
    print(f"AKI_PROVENANCE_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    expected: dict[str, tuple[str, str]] = {}
    for number, raw_line in enumerate(MANIFEST.read_text().splitlines(), 1):
        match = re.fullmatch(r"(100644|100755) blob ([0-9a-f]{40})\t(.+)", raw_line)
        if not match:
            fail(f"invalid manifest line {number}: {raw_line!r}")
        mode, blob, relative = match.groups()
        if relative in expected:
            fail(f"duplicate manifest path: {relative}")
        expected[relative] = (mode, blob)

    actual = {
        path.relative_to(AKI_ROOT).as_posix()
        for path in AKI_ROOT.rglob("*")
        if path.is_file() and path.relative_to(AKI_ROOT).as_posix() not in IGNORED
    }
    expected_paths = set(expected)
    if actual != expected_paths:
        missing = sorted(expected_paths - actual)
        extra = sorted(actual - expected_paths)
        fail(f"inventory drift; missing={missing}, extra={extra}")

    for relative, (mode, expected_blob) in sorted(expected.items()):
        path = AKI_ROOT / relative
        actual_blob = git_blob_id(path.read_bytes())
        if actual_blob != expected_blob:
            fail(f"blob drift: {relative}: expected {expected_blob}, got {actual_blob}")
        executable = bool(path.stat().st_mode & 0o100)
        if executable != (mode == "100755"):
            fail(f"mode drift: {relative}: expected {mode}")

    metadata = METADATA.read_text()
    required_facts = (
        "eed486c81bc3404df336d1fee94f989f827cbb57",
        "5eb9196485033bfc37069aecf1418b62c60d16da",
        "1.3.1",
        "Apache-2.0",
    )
    for fact in required_facts:
        if fact not in metadata:
            fail(f"UPSTREAM.md lost required fact: {fact}")

    cmake = (AKI_ROOT / "CMakeLists.txt").read_text()
    version = (AKI_ROOT / "include" / "aki" / "version.h").read_text()
    if "project(aki VERSION 1.3.1" not in cmake:
        fail("CMake project version is not 1.3.1")
    for define in (
        "#define JSB_MAJOR_VERSION 1",
        "#define JSB_MINOR_VERSION 3",
        "#define JSB_PATCH_LEVEL 1",
    ):
        if define not in version:
            fail(f"version header mismatch: {define}")

    print(
        "AKI_PROVENANCE_PASS "
        f"files={len(expected)} commit=eed486c81bc3404df336d1fee94f989f827cbb57 "
        "tree=5eb9196485033bfc37069aecf1418b62c60d16da version=1.3.1"
    )


if __name__ == "__main__":
    main()
