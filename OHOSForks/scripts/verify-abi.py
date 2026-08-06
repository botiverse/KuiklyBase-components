#!/usr/bin/env python3
from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


class VerificationError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"cannot read JSON {path}: {error}") from error
    require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_control(url: str, destination: Path) -> None:
    require(re.fullmatch(r"https://[^\s]+", url) is not None, f"ABI control URL must be HTTPS: {url!r}")
    result = subprocess.run(
        [
            "curl",
            "--fail",
            "--location",
            "--silent",
            "--show-error",
            "--retry",
            "3",
            "--proto",
            "=https",
            "--output",
            str(destination),
            url,
        ],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    require(
        result.returncode == 0,
        f"failed to download ABI control {url}: {result.stderr.decode('utf-8', errors='replace').strip()}",
    )
    require(destination.is_file() and not destination.is_symlink(), f"ABI control download is missing: {destination}")
    require(destination.stat().st_size > 0, f"ABI control download is empty: {destination}")


def dump_normalized_abi(klib: Path, library: Path, raw_path: Path, normalized_path: Path) -> bytes:
    result = subprocess.run(
        [str(klib), "dump-abi", str(library)],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    require(
        result.returncode == 0,
        f"klib dump-abi failed for {library}: {result.stderr.decode('utf-8', errors='replace').strip()}",
    )
    raw = result.stdout
    require(raw, f"klib dump-abi returned no output for {library}")
    first_newline = raw.find(b"\n")
    require(first_newline >= 0, f"klib dump-abi output has no CLI banner boundary for {library}")
    banner = raw[:first_newline].rstrip(b"\r")
    require(
        banner.startswith(b"klib dump-abi ") and len(banner) > len(b"klib dump-abi "),
        f"unexpected klib dump-abi CLI banner for {library}: {banner!r}",
    )
    normalized = raw[first_newline + 1 :]
    require(normalized.startswith(b"// Rendering settings:\n"), f"normalized ABI lacks its rendering-settings positive control: {library}")
    require(b"// Library unique name:" in normalized, f"normalized ABI lacks its library-name positive control: {library}")
    raw_path.write_bytes(raw)
    normalized_path.write_bytes(normalized)
    return normalized


def verify_abi(repository: Path, klib: Path, output: Path) -> dict[str, Any]:
    fork_root = Path(__file__).resolve().parent.parent
    spec = load_json(fork_root / "release-spec.json")
    require(spec.get("schema") == 1, "unsupported release-spec schema")
    require(repository.is_dir() and not repository.is_symlink(), f"staging repository is missing: {repository}")
    require(klib.is_file() and not klib.is_symlink(), f"pinned klib command is missing or unsafe: {klib}")
    require(not output.exists(), f"ABI output path already exists: {output}")
    output.mkdir(parents=True)

    receipts: dict[str, Any] = {}
    components = spec.get("components")
    require(isinstance(components, dict) and set(components) == {"atomicfu", "coroutines"}, "release spec component set mismatch")
    for name in ("atomicfu", "coroutines"):
        component = components[name]
        control = component.get("abiControl")
        require(isinstance(component, dict) and isinstance(control, dict), f"ABI control is missing for {name}")
        group = component.get("group")
        artifact = component.get("targetArtifact")
        version = component.get("version")
        require(all(isinstance(value, str) and value for value in (group, artifact, version)), f"invalid release identity for {name}")
        candidate_relative = Path(*group.split(".")) / artifact / version / f"{artifact}-{version}.klib"
        candidate = repository / candidate_relative
        require(candidate.is_file() and not candidate.is_symlink(), f"candidate ABI KLIB is missing: {candidate}")

        expected_control_sha = control.get("sha256")
        expected_normalized_sha = control.get("normalizedAbiSha256")
        require(
            isinstance(expected_control_sha, str) and re.fullmatch(r"[0-9a-f]{64}", expected_control_sha) is not None,
            f"invalid ABI control checksum for {name}",
        )
        require(
            isinstance(expected_normalized_sha, str)
            and re.fullmatch(r"[0-9a-f]{64}", expected_normalized_sha) is not None,
            f"invalid normalized ABI checksum for {name}",
        )

        control_path = output / f"{name}-control.klib"
        download_control(control.get("url"), control_path)
        actual_control_sha = sha256(control_path)
        require(
            actual_control_sha == expected_control_sha,
            f"ABI control checksum mismatch for {name}: expected {expected_control_sha}, got {actual_control_sha}",
        )

        control_normalized = dump_normalized_abi(
            klib,
            control_path,
            output / f"{name}-control.abi",
            output / f"{name}-control.normalized.abi",
        )
        candidate_normalized = dump_normalized_abi(
            klib,
            candidate,
            output / f"{name}-candidate.abi",
            output / f"{name}-candidate.normalized.abi",
        )
        control_normalized_sha = hashlib.sha256(control_normalized).hexdigest()
        candidate_normalized_sha = hashlib.sha256(candidate_normalized).hexdigest()
        require(
            control_normalized_sha == expected_normalized_sha,
            f"normalized ABI control digest mismatch for {name}: expected {expected_normalized_sha}, got {control_normalized_sha}",
        )
        require(
            candidate_normalized_sha == expected_normalized_sha,
            f"candidate normalized ABI digest mismatch for {name}: expected {expected_normalized_sha}, got {candidate_normalized_sha}",
        )

        diff_path = output / f"{name}.diff"
        if candidate_normalized != control_normalized:
            difference = difflib.unified_diff(
                control_normalized.decode("utf-8").splitlines(keepends=True),
                candidate_normalized.decode("utf-8").splitlines(keepends=True),
                fromfile=f"{name}-control",
                tofile=f"{name}-candidate",
            )
            diff_path.write_text("".join(difference), encoding="utf-8")
            raise VerificationError(f"normalized ABI differs from its pinned control for {name}: {diff_path}")
        diff_path.write_bytes(b"")

        line_count = len(candidate_normalized.splitlines())
        require(line_count > 0, f"normalized ABI line count is empty for {name}")
        receipts[name] = {
            "coordinate": f"{group}:{artifact}:{version}",
            "candidatePath": candidate_relative.as_posix(),
            "candidateSha256": sha256(candidate),
            "controlCoordinate": control.get("coordinate"),
            "controlSha256": actual_control_sha,
            "normalizedAbiSha256": candidate_normalized_sha,
            "normalizedAbiLines": line_count,
            "diffLines": 0,
        }

    receipt = {
        "schema": 1,
        "kotlinVersion": spec.get("kotlinVersion"),
        "components": receipts,
    }
    (output / "abi-receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare staged OHOS KLIB ABIs with checksum-pinned live KBA controls")
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--klib", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        receipt = verify_abi(arguments.repository.resolve(), arguments.klib.resolve(), arguments.output.resolve())
    except VerificationError as error:
        print(f"verify-abi: {error}", file=sys.stderr)
        return 1
    print(
        "verify-abi: exact normalized ABI equality for "
        + ", ".join(
            f"{name} ({component['normalizedAbiLines']} lines, {component['normalizedAbiSha256']})"
            for name, component in receipt["components"].items()
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
