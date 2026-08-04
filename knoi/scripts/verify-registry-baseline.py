#!/usr/bin/env python3
"""Verify the immutable @kuiklybase/knoi 0.0.4 registry byte baseline."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from urllib.error import URLError
from urllib.request import Request, urlopen


SCRIPT = Path(__file__).resolve()
KNOI_ROOT = SCRIPT.parent.parent
BASELINE_PATH = KNOI_ROOT / "baselines" / "registry-0.0.4.json"


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def fail(message: str) -> None:
    print(f"KNOI_REGISTRY_BASELINE_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def parse_readelf(library: Path, baseline: dict) -> None:
    readelf = shutil.which("readelf") or shutil.which("llvm-readelf")
    if readelf is None:
        fail("readelf/llvm-readelf is required for the ELF baseline gate")

    notes = subprocess.check_output([readelf, "-n", str(library)], text=True)
    match = re.search(r"Build ID:\s*([0-9a-f]+)", notes)
    if not match or match.group(1) != baseline["native"]["build_id"]:
        fail(f"Build ID drift: {match.group(1) if match else 'missing'}")

    dynamic = subprocess.check_output([readelf, "-d", str(library)], text=True)
    needed = sorted(re.findall(r"Shared library: \[([^]]+)\]", dynamic))
    if needed != sorted(baseline["native"]["needed"]):
        fail(f"DT_NEEDED drift: {needed}")

    symbols = subprocess.check_output([readelf, "--dyn-syms", "--wide", str(library)], text=True)
    defined: set[str] = set()
    for line in symbols.splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].rstrip(":").isdigit():
            continue
        _, _, _, symbol_type, binding, _, section, name = fields[:8]
        if section != "UND" and binding in {"GLOBAL", "WEAK"} and name:
            defined.add(name.split("@", 1)[0])
    if sorted(defined) != sorted(baseline["native"]["defined_dynamic_symbols"]):
        fail(f"defined dynsym drift: {sorted(defined)}")


def download(url: str, destination: Path) -> None:
    request = Request(url, headers={"User-Agent": "KuiklyBase-KNOI-baseline/1"})
    last_error = None
    for attempt in range(1, 4):
        try:
            with urlopen(request, timeout=30) as response:
                destination.write_bytes(response.read())
            return
        except (OSError, URLError) as error:
            last_error = error
            if attempt < 3:
                time.sleep(attempt)
    fail(f"registry archive download failed after 3 attempts: {last_error}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--require-elf", action="store_true")
    args = parser.parse_args()

    baseline = json.loads(BASELINE_PATH.read_text())
    if baseline["source_provenance"]["status"] != "unbound":
        fail("0.0.4 provenance gap was silently reclassified")

    with tempfile.TemporaryDirectory(prefix="knoi-registry-baseline-") as temporary:
        root = Path(temporary)
        archive = args.archive
        if archive is None:
            archive = root / "knoi-0.0.4.har"
            download(baseline["registry_url"], archive)
        archive = archive.resolve()
        if digest(archive) != baseline["archive_sha256"]:
            fail(f"archive SHA-256 drift: {digest(archive)}")

        with tarfile.open(archive, "r:gz") as bundle:
            file_members = [member for member in bundle.getmembers() if member.isfile()]
            actual_files = {member.name for member in file_members}
            if len(actual_files) != len(file_members):
                fail("registry archive contains duplicate file paths")
            expected_files = set(baseline["files"])
            if actual_files != expected_files:
                fail(
                    f"file inventory drift; missing={sorted(expected_files - actual_files)}, "
                    f"extra={sorted(actual_files - expected_files)}"
                )

            native_bytes = None
            for member in file_members:
                stream = bundle.extractfile(member)
                if stream is None:
                    fail(f"could not read archive member: {member.name}")
                data = stream.read()
                actual = hashlib.sha256(data).hexdigest()
                if actual != baseline["files"][member.name]:
                    fail(f"file SHA-256 drift: {member.name}: {actual}")
                if member.name == baseline["native"]["path"]:
                    native_bytes = data

        if args.require_elf:
            if native_bytes is None:
                fail("native library is missing from the registry archive")
            library = root / "libknoi.so"
            library.write_bytes(native_bytes)
            parse_readelf(library, baseline)

    print(
        "KNOI_REGISTRY_BASELINE_PASS "
        f"archive={baseline['archive_sha256']} build_id={baseline['native']['build_id']} "
        f"files={len(baseline['files'])} provenance={baseline['source_provenance']['status']}"
    )


if __name__ == "__main__":
    main()
