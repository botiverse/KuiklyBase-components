#!/usr/bin/env python3
"""Verify the built KNOI HAR, its Aki notices, and its native addon."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile


SCRIPT = Path(__file__).resolve()
KNOI_ROOT = SCRIPT.parent.parent
AKI_ROOT = KNOI_ROOT / "third_party" / "aki"
ADDON_VERIFIER = SCRIPT.parent / "verify-built-addon.sh"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fail(message: str) -> None:
    raise SystemExit(f"KNOI_HAR_FAIL: {message}")


def read_member(bundle: tarfile.TarFile, name: str) -> bytes:
    try:
        member = bundle.getmember(name)
    except KeyError:
        fail(f"missing member: {name}")
    if not member.isfile():
        fail(f"member is not a file: {name}")
    stream = bundle.extractfile(member)
    if stream is None:
        fail(f"could not read member: {name}")
    return stream.read()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--har", required=True, type=Path)
    parser.add_argument("--mode", choices=("aki", "legacy"), default="aki")
    args = parser.parse_args()

    har = args.har.resolve()
    if not har.is_file():
        fail(f"HAR not found: {har}")

    try:
        bundle = tarfile.open(har, "r:gz")
    except tarfile.TarError as error:
        fail(f"HAR is not a gzip tar archive: {error}")

    with bundle:
        files = {member.name for member in bundle.getmembers() if member.isfile()}
        native_libraries = sorted(name for name in files if name.endswith(".so"))
        expected_native_libraries = [
            "package/libs/arm64-v8a/libc++_shared.so",
            "package/libs/arm64-v8a/libknoi.so",
        ]
        if native_libraries != expected_native_libraries:
            fail(f"unexpected native library inventory: {native_libraries}")
        addon_paths = sorted(name for name in files if name.endswith("/libknoi.so"))
        if addon_paths != ["package/libs/arm64-v8a/libknoi.so"]:
            fail(f"unexpected libknoi.so inventory: {addon_paths}")
        aki_shared = sorted(
            name for name in files if Path(name).name.startswith("libaki")
        )
        if aki_shared:
            fail(f"Aki escaped as a second shared runtime: {aki_shared}")

        license_bytes = read_member(bundle, "package/LICENSE")
        notice_bytes = read_member(bundle, "package/NOTICE")
        if license_bytes != (AKI_ROOT / "LICENSE").read_bytes():
            fail("package/LICENSE differs from the pinned Aki license")
        if notice_bytes != (AKI_ROOT / "NOTICE").read_bytes():
            fail("package/NOTICE differs from the pinned Aki notice")

        package_json = json.loads(read_member(bundle, "package/oh-package.json5"))
        if package_json.get("name") != "@kuiklybase/knoi":
            fail("package identity drift")
        if package_json.get("version") != "0.0.4":
            fail("phase one must not publish or bump the KNOI coordinate")
        if package_json.get("license") != "Apache-2.0":
            fail("package license metadata drift")

        addon_bytes = read_member(bundle, addon_paths[0])

    with tempfile.TemporaryDirectory(prefix="knoi-har-") as temporary:
        addon = Path(temporary) / "libknoi.so"
        addon.write_bytes(addon_bytes)
        environment = os.environ.copy()
        subprocess.run(
            [
                "bash",
                str(ADDON_VERIFIER),
                "--library",
                str(addon),
                "--mode",
                args.mode,
            ],
            check=True,
            env=environment,
        )

    print(
        "KNOI_HAR_PASS "
        f"mode={args.mode} har_sha256={digest(har.read_bytes())} "
        f"addon_sha256={digest(addon_bytes)} "
        f"license_sha256={digest(license_bytes)} notice_sha256={digest(notice_bytes)}"
    )


if __name__ == "__main__":
    main()
