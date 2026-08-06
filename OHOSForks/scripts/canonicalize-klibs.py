#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import stat
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any


class CanonicalizationError(RuntimeError):
    pass


CANONICAL_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
CANONICAL_EXTRA = b""
# zipfile replaces a zero external_attr with its implicit 0600 file mode while
# opening an entry for writing.  Set that value explicitly so both the local
# and central directory records are independent of the input archive.
CANONICAL_EXTERNAL_ATTR = 0o600 << 16


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CanonicalizationError(message)


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CanonicalizationError(f"cannot read JSON {path}: {error}") from error
    require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def digest_bytes(value: bytes, algorithm: str = "sha256") -> str:
    digest = hashlib.new(algorithm)
    digest.update(value)
    return digest.hexdigest()


def digest_file(path: Path, algorithm: str = "sha256") -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_regular_file(path: Path, context: str) -> None:
    require(path.is_file() and not path.is_symlink(), f"{context} is missing or unsafe: {path}")
    require(stat.S_ISREG(path.stat().st_mode), f"{context} is not a regular file: {path}")


def validate_entry_name(name: str, archive: Path) -> None:
    require(name and "\\" not in name, f"unsafe KLIB entry name in {archive}: {name!r}")
    candidate = PurePosixPath(name)
    require(not candidate.is_absolute(), f"absolute KLIB entry name in {archive}: {name!r}")
    require(
        all(part not in {"", ".", ".."} for part in candidate.parts),
        f"unsafe KLIB entry name in {archive}: {name!r}",
    )


def read_payloads(source: Path | io.BytesIO, context: Path) -> list[tuple[str, bool, bytes]]:
    try:
        with zipfile.ZipFile(source, "r") as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            require(names, f"KLIB archive is empty: {context}")
            require(len(names) == len(set(names)), f"duplicate KLIB entry in {context}")
            payloads: list[tuple[str, bool, bytes]] = []
            for info in infos:
                validate_entry_name(info.filename, context)
                is_directory = info.is_dir()
                require(
                    is_directory == info.filename.endswith("/"),
                    f"ambiguous KLIB directory entry in {context}: {info.filename}",
                )
                payload = archive.read(info)
                require(not is_directory or payload == b"", f"KLIB directory entry has payload: {context}:{info.filename}")
                payloads.append((info.filename, is_directory, payload))
            return payloads
    except (OSError, zipfile.BadZipFile, KeyError, RuntimeError) as error:
        if isinstance(error, CanonicalizationError):
            raise
        raise CanonicalizationError(f"cannot inspect KLIB {context}: {error}") from error


def canonical_archive_bytes(payloads: list[tuple[str, bool, bytes]]) -> bytes:
    ordered = sorted(payloads, key=lambda item: item[0].encode("utf-8"))
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", allowZip64=True) as archive:
        for name, is_directory, payload in ordered:
            info = zipfile.ZipInfo(filename=name, date_time=CANONICAL_TIMESTAMP)
            info.compress_type = zipfile.ZIP_STORED if is_directory else zipfile.ZIP_DEFLATED
            info.comment = b""
            info.extra = CANONICAL_EXTRA
            info.create_system = 0
            info.create_version = 10 if is_directory else 20
            info.extract_version = 10 if is_directory else 20
            info.flag_bits = 0
            info.volume = 0
            info.internal_attr = 0
            info.external_attr = CANONICAL_EXTERNAL_ATTR
            archive.writestr(
                info,
                payload,
                compress_type=info.compress_type,
                compresslevel=None if is_directory else 9,
            )
    return output.getvalue()


def payload_manifest(payloads: list[tuple[str, bool, bytes]]) -> tuple[list[dict[str, Any]], str]:
    entries = [
        {
            "path": name,
            "directory": is_directory,
            "size": len(payload),
            "sha256": digest_bytes(payload),
        }
        for name, is_directory, payload in sorted(payloads, key=lambda item: item[0].encode("utf-8"))
    ]
    encoded = json.dumps(entries, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return entries, digest_bytes(encoded)


def verify_canonical_metadata(value: bytes, path: Path) -> None:
    try:
        with zipfile.ZipFile(io.BytesIO(value), "r") as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            require(
                names == sorted(names, key=lambda name: name.encode("utf-8")),
                f"canonical KLIB entry order drifted: {path}",
            )
            for info in infos:
                is_directory = info.is_dir()
                try:
                    info.filename.encode("ascii")
                    expected_flags = 0
                except UnicodeEncodeError:
                    expected_flags = 0x800
                require(info.date_time == CANONICAL_TIMESTAMP, f"canonical KLIB timestamp drifted: {path}:{info.filename}")
                require(info.extra == CANONICAL_EXTRA, f"canonical KLIB extra metadata drifted: {path}:{info.filename}")
                require(info.comment == b"", f"canonical KLIB comment drifted: {path}:{info.filename}")
                require(info.create_system == 0, f"canonical KLIB creator drifted: {path}:{info.filename}")
                require(info.flag_bits == expected_flags, f"canonical KLIB flags drifted: {path}:{info.filename}")
                require(
                    info.internal_attr == 0 and info.external_attr == CANONICAL_EXTERNAL_ATTR,
                    f"canonical KLIB attributes drifted: {path}:{info.filename}",
                )
                expected_compression = zipfile.ZIP_STORED if is_directory else zipfile.ZIP_DEFLATED
                require(
                    info.compress_type == expected_compression,
                    f"canonical KLIB compression drifted: {path}:{info.filename}",
                )
    except (OSError, zipfile.BadZipFile) as error:
        raise CanonicalizationError(f"cannot verify canonical KLIB {path}: {error}") from error


def atomic_write(path: Path, value: bytes, mode: int) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, stat.S_IMODE(mode))
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def canonicalize_archive(path: Path, repository: Path) -> dict[str, Any]:
    require_regular_file(path, "KLIB archive")
    original_mode = path.stat().st_mode
    original_payloads = read_payloads(path, path)
    entries, payload_manifest_sha256 = payload_manifest(original_payloads)
    canonical = canonical_archive_bytes(original_payloads)
    verify_canonical_metadata(canonical, path)

    canonical_payloads = read_payloads(io.BytesIO(canonical), path)
    canonical_entries, canonical_manifest_sha256 = payload_manifest(canonical_payloads)
    require(canonical_entries == entries, f"KLIB payload changed while canonicalizing: {path}")
    require(canonical_manifest_sha256 == payload_manifest_sha256, f"KLIB payload manifest changed: {path}")
    require(
        canonical_archive_bytes(canonical_payloads) == canonical,
        f"KLIB canonicalization is not idempotent: {path}",
    )

    atomic_write(path, canonical, original_mode)
    require(path.read_bytes() == canonical, f"canonical KLIB readback mismatch: {path}")
    return {
        "path": path.relative_to(repository).as_posix(),
        "entryCount": len(entries),
        "payloadManifestSha256": payload_manifest_sha256,
        "sha256": digest_bytes(canonical),
        "size": len(canonical),
    }


def update_module(module_path: Path, archive_paths: list[Path]) -> dict[str, Any]:
    require_regular_file(module_path, "target Gradle module metadata")
    module = load_json(module_path)
    expected = {path.name: path for path in archive_paths}
    descriptors: dict[str, dict[str, Any]] = {}
    variants = module.get("variants")
    require(isinstance(variants, list), f"Gradle module variants are missing: {module_path}")
    for variant in variants:
        require(isinstance(variant, dict), f"invalid Gradle module variant: {module_path}")
        files = variant.get("files") or []
        require(isinstance(files, list), f"invalid Gradle module file list: {module_path}")
        for descriptor in files:
            require(isinstance(descriptor, dict), f"invalid Gradle module file descriptor: {module_path}")
            url = descriptor.get("url")
            if isinstance(url, str) and url.endswith(".klib"):
                require(url in expected, f"unexpected KLIB descriptor in {module_path}: {url}")
                require(url not in descriptors, f"duplicate KLIB descriptor in {module_path}: {url}")
                descriptors[url] = descriptor
    require(set(descriptors) == set(expected), f"KLIB descriptor set mismatch in {module_path}")

    for name, path in expected.items():
        descriptor = descriptors[name]
        descriptor["size"] = path.stat().st_size
        for field in ("sha512", "sha256", "sha1", "md5"):
            descriptor[field] = digest_file(path, field)

    encoded = (json.dumps(module, indent=2) + "\n").encode("utf-8")
    atomic_write(module_path, encoded, module_path.stat().st_mode)
    readback = load_json(module_path)
    require(readback == module, f"Gradle module metadata readback mismatch: {module_path}")
    return {
        "path": module_path.name,
        "sha256": digest_bytes(encoded),
        "size": len(encoded),
    }


def component_directory(repository: Path, group: str, artifact: str, version: str) -> Path:
    return repository.joinpath(*group.split("."), artifact, version)


def main() -> int:
    parser = argparse.ArgumentParser(description="Canonicalize KLIB ZIP order and bind Gradle module checksums")
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--release-spec", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    arguments = parser.parse_args()

    repository = arguments.repository.resolve()
    release_spec = arguments.release_spec.resolve()
    receipt_path = arguments.receipt.resolve()
    require(repository.is_dir() and not repository.is_symlink(), f"staging repository is missing: {repository}")
    require_regular_file(release_spec, "release spec")
    spec = load_json(release_spec)
    require(spec.get("schema") == 1, "unsupported release-spec schema")
    components = spec.get("components")
    require(
        isinstance(components, dict) and set(components) == {"atomicfu", "coroutines"},
        "release spec component set mismatch",
    )

    archives: list[dict[str, Any]] = []
    modules: list[dict[str, Any]] = []
    seen_paths: set[Path] = set()
    for name in ("atomicfu", "coroutines"):
        component = components[name]
        require(isinstance(component, dict), f"component must be an object: {name}")
        group = component.get("group")
        target = component.get("targetArtifact")
        version = component.get("version")
        require(
            all(isinstance(value, str) and value for value in (group, target, version)),
            f"component identity is incomplete: {name}",
        )
        require(re.fullmatch(r"[A-Za-z0-9_.-]+", group) is not None, f"unsafe component group: {name}")
        require(re.fullmatch(r"[A-Za-z0-9_.-]+", target) is not None, f"unsafe target artifact: {name}")
        require(re.fullmatch(r"[A-Za-z0-9_.-]+", version) is not None, f"unsafe version: {name}")

        directory = component_directory(repository, group, target, version)
        require(directory.is_dir() and not directory.is_symlink(), f"target coordinate is missing: {directory}")
        prefix = f"{target}-{version}"
        archive_paths = [directory / f"{prefix}.klib", directory / f"{prefix}-cinterop-interop.klib"]
        for archive_path in archive_paths:
            require(archive_path not in seen_paths, f"duplicate KLIB target in release spec: {archive_path}")
            seen_paths.add(archive_path)
            archives.append(canonicalize_archive(archive_path, repository))
        module_path = directory / f"{prefix}.module"
        module_receipt = update_module(module_path, archive_paths)
        module_receipt["path"] = module_path.relative_to(repository).as_posix()
        modules.append(module_receipt)

    receipt = {
        "schema": 1,
        "releaseSpecSha256": digest_file(release_spec),
        "archives": sorted(archives, key=lambda item: item["path"]),
        "modules": sorted(modules, key=lambda item: item["path"]),
    }
    require(not receipt_path.exists(), f"canonicalization receipt already exists: {receipt_path}")
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"canonicalize-klibs: canonicalized {len(archives)} KLIBs and rebound {len(modules)} modules")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CanonicalizationError as error:
        print(f"canonicalize-klibs: {error}", file=sys.stderr)
        raise SystemExit(1)
