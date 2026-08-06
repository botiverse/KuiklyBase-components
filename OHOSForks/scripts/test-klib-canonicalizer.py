#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import warnings
import zipfile
from pathlib import Path
from typing import Any, Callable


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


COMPONENTS = {
    "atomicfu": {
        "group": "org.jetbrains.kotlinx",
        "targetArtifact": "atomicfu-ohosarm64",
        "version": "0.23.2-raft.1",
    },
    "coroutines": {
        "group": "org.jetbrains.kotlinx",
        "targetArtifact": "kotlinx-coroutines-core-ohosarm64",
        "version": "1.8.0-raft.1",
    },
}


def coordinate_directory(repository: Path, component: dict[str, str]) -> Path:
    return repository.joinpath(*component["group"].split("."), component["targetArtifact"], component["version"])


def archive_paths(repository: Path, component: dict[str, str]) -> list[Path]:
    directory = coordinate_directory(repository, component)
    prefix = f'{component["targetArtifact"]}-{component["version"]}'
    return [directory / f"{prefix}.klib", directory / f"{prefix}-cinterop-interop.klib"]


def write_archive(path: Path, marker: str, reverse: bool, alternate_metadata: bool) -> None:
    entries = [
        ("default/", b""),
        ("default/ir/", b""),
        ("default/ir/files.knf", f"files:{marker}".encode("utf-8")),
        ("default/linkdata/", b""),
        ("default/linkdata/module", f"module:{marker}".encode("utf-8")),
        ("default/linkdata/κ.knm", f"unicode:{marker}".encode("utf-8")),
        ("default/manifest", f"manifest:{marker}".encode("utf-8")),
    ]
    if reverse:
        entries.reverse()
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", allowZip64=True) as archive:
        for index, (name, payload) in enumerate(entries):
            timestamp = (2025, 7, 1, 1, 2, 4) if alternate_metadata else (1980, 1, 1, 0, 0, 0)
            info = zipfile.ZipInfo(name, timestamp)
            info.compress_type = zipfile.ZIP_STORED if name.endswith("/") else zipfile.ZIP_DEFLATED
            info.comment = b"alternate" if alternate_metadata and index == 0 else b""
            info.external_attr = 0x20 if alternate_metadata else 0
            archive.writestr(info, payload, compress_type=info.compress_type, compresslevel=1 if alternate_metadata else 6)


def write_module(path: Path, archives: list[Path]) -> None:
    value = {
        "formatVersion": "1.1",
        "variants": [
            {
                "name": "ohosArm64ApiElements-published",
                "files": [
                    {
                        "name": archive.name,
                        "url": archive.name,
                        "size": 0,
                        "sha512": "stale",
                        "sha256": "stale",
                        "sha1": "stale",
                        "md5": "stale",
                    }
                    for archive in archives
                ],
            }
        ],
    }
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def make_repository(repository: Path, reverse: bool, alternate_metadata: bool) -> None:
    for name, component in COMPONENTS.items():
        archives = archive_paths(repository, component)
        for index, archive in enumerate(archives):
            write_archive(archive, f"{name}-{index}", reverse, alternate_metadata)
        prefix = f'{component["targetArtifact"]}-{component["version"]}'
        write_module(archives[0].parent / f"{prefix}.module", archives)


def run_canonicalizer(script: Path, repository: Path, spec: Path, receipt: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(script),
            "--repository",
            str(repository),
            "--release-spec",
            str(spec),
            "--receipt",
            str(receipt),
        ],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def release_paths(repository: Path) -> list[Path]:
    paths: list[Path] = []
    for component in COMPONENTS.values():
        archives = archive_paths(repository, component)
        prefix = f'{component["targetArtifact"]}-{component["version"]}'
        paths.extend(archives)
        paths.append(archives[0].parent / f"{prefix}.module")
    return sorted(paths, key=lambda path: path.relative_to(repository).as_posix())


def verify_canonical_positive_control(repository: Path) -> None:
    observed_files = 0
    for component in COMPONENTS.values():
        for path in archive_paths(repository, component):
            with zipfile.ZipFile(path, "r") as archive:
                infos = archive.infolist()
                names = [info.filename for info in infos]
                require(names == sorted(names, key=lambda name: name.encode("utf-8")), f"entry order is not canonical: {path}")
                for info in infos:
                    try:
                        info.filename.encode("ascii")
                        expected_flags = 0
                    except UnicodeEncodeError:
                        expected_flags = 0x800
                    require(info.date_time == (1980, 1, 1, 0, 0, 0), f"timestamp is not canonical: {path}")
                    require(info.flag_bits == expected_flags, f"flags are not canonical: {path}")
                    require(info.create_system == 0, f"creator is not canonical: {path}")
                    if not info.is_dir():
                        require(archive.read(info), f"file positive control is empty: {path}:{info.filename}")
                        observed_files += 1
    require(observed_files == 16, f"canonical positive control observed {observed_files} files instead of 16")


def expect_failure(result: subprocess.CompletedProcess[str], reason: str, name: str) -> None:
    output = result.stdout + result.stderr
    require(result.returncode != 0, f"mutation unexpectedly passed: {name}")
    require(reason in output, f"mutation failed without its named reason {reason!r}: {name}:\n{output}")


def mutate_duplicate_entry(repository: Path) -> None:
    path = archive_paths(repository, COMPONENTS["atomicfu"])[0]
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("default/manifest", b"one")
            archive.writestr("default/manifest", b"two")


def mutate_unsafe_entry(repository: Path) -> None:
    path = archive_paths(repository, COMPONENTS["atomicfu"])[0]
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("../escape", b"unsafe")


def mutate_missing_descriptor(repository: Path) -> None:
    component = COMPONENTS["atomicfu"]
    archives = archive_paths(repository, component)
    prefix = f'{component["targetArtifact"]}-{component["version"]}'
    module_path = archives[0].parent / f"{prefix}.module"
    value = json.loads(module_path.read_text(encoding="utf-8"))
    value["variants"][0]["files"].pop()
    module_path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    script = Path(__file__).resolve().with_name("canonicalize-klibs.py")
    require(script.is_file(), f"canonicalizer is missing: {script}")
    with tempfile.TemporaryDirectory(prefix="ohos-forks-klib-canonicalizer.") as temporary:
        root = Path(temporary)
        spec = root / "release-spec.json"
        spec.write_text(json.dumps({"schema": 1, "components": COMPONENTS}, indent=2) + "\n", encoding="utf-8")
        left = root / "left"
        right = root / "right"
        make_repository(left, reverse=False, alternate_metadata=False)
        make_repository(right, reverse=True, alternate_metadata=True)

        left_receipt = root / "left-receipt.json"
        right_receipt = root / "right-receipt.json"
        left_result = run_canonicalizer(script, left, spec, left_receipt)
        right_result = run_canonicalizer(script, right, spec, right_receipt)
        require(left_result.returncode == 0, f"left baseline failed:\n{left_result.stdout}{left_result.stderr}")
        require(right_result.returncode == 0, f"right baseline failed:\n{right_result.stdout}{right_result.stderr}")
        left_paths = release_paths(left)
        right_paths = release_paths(right)
        require(len(left_paths) == len(right_paths) == 6, "baseline release path count drifted")
        for left_path, right_path in zip(left_paths, right_paths):
            require(
                left_path.relative_to(left) == right_path.relative_to(right),
                "baseline relative release path drifted",
            )
            require(left_path.read_bytes() == right_path.read_bytes(), f"permuted inputs did not converge: {left_path}")
        require(left_receipt.read_bytes() == right_receipt.read_bytes(), "permuted inputs produced different receipts")
        verify_canonical_positive_control(left)
        print("test-klib-canonicalizer: PASS permuted order and metadata converge with non-empty payload controls")

        before = {path.relative_to(left): sha256(path) for path in left_paths}
        left_receipt.unlink()
        idempotent = run_canonicalizer(script, left, spec, left_receipt)
        require(idempotent.returncode == 0, f"idempotence run failed:\n{idempotent.stdout}{idempotent.stderr}")
        after = {path.relative_to(left): sha256(path) for path in left_paths}
        require(before == after, "second canonicalization changed release bytes")
        print("test-klib-canonicalizer: PASS idempotent release bytes")

        mutations: list[tuple[str, Callable[[Path], None], str]] = [
            ("duplicate archive entry", mutate_duplicate_entry, "duplicate KLIB entry"),
            ("unsafe archive entry", mutate_unsafe_entry, "unsafe KLIB entry name"),
            ("missing module descriptor", mutate_missing_descriptor, "KLIB descriptor set mismatch"),
        ]
        for index, (name, mutate, reason) in enumerate(mutations, 1):
            case = root / f"mutation-{index}"
            shutil.copytree(left, case)
            mutate(case)
            result = run_canonicalizer(script, case, spec, root / f"mutation-{index}-receipt.json")
            expect_failure(result, reason, name)
            print(f"test-klib-canonicalizer: PASS mutation {index}/{len(mutations)}: {name}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"test-klib-canonicalizer: {error}", file=sys.stderr)
        raise SystemExit(1)
