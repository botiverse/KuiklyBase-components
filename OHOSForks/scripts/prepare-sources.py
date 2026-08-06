#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Optional


class PreparationError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PreparationError(message)


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PreparationError(f"cannot read JSON {path}: {error}") from error
    require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(
    arguments: list[str],
    cwd: Optional[Path] = None,
    environment: Optional[dict[str, str]] = None,
    capture: bool = True,
) -> bytes:
    result = subprocess.run(
        arguments,
        cwd=str(cwd) if cwd is not None else None,
        env=environment,
        check=False,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="replace").strip()
        raise PreparationError(f"command failed ({' '.join(arguments[:3])}): {stderr}")
    return result.stdout if capture else b""


def git(checkout: Path, *arguments: str, environment: Optional[dict[str, str]] = None) -> bytes:
    return run(["git", "-C", str(checkout), *arguments], environment=environment)


def require_hash(value: Any, width: int, context: str) -> str:
    require(
        isinstance(value, str) and re.fullmatch(rf"[0-9a-f]{{{width}}}", value) is not None,
        f"{context} must be exactly {width} lowercase hexadecimal characters",
    )
    return value


def safe_relative(value: Any, context: str) -> Path:
    require(isinstance(value, str) and value != "", f"{context} must be a non-empty path")
    path = Path(value)
    require(not path.is_absolute() and ".." not in path.parts, f"{context} must stay inside its root")
    return path


def prepare_source(
    name: str,
    entry: dict[str, Any],
    fork_root: Path,
    output_root: Path,
    scratch_root: Path,
) -> dict[str, str]:
    require(
        set(entry)
        == {
            "repository",
            "commit",
            "patch",
            "patchSha256",
            "preparedTree",
            "preparedTreeManifestSha256",
            "positiveControl",
        },
        f"source-lock field set mismatch for {name}: {sorted(entry)}",
    )
    repository = entry.get("repository")
    require(
        isinstance(repository, str) and re.fullmatch(r"https://github\.com/[^/]+/[^/]+\.git", repository) is not None,
        f"{name} repository must be an HTTPS GitHub .git URL",
    )
    commit = require_hash(entry.get("commit"), 40, f"{name} commit")
    patch_sha256 = require_hash(entry.get("patchSha256"), 64, f"{name} patch SHA-256")
    expected_tree = require_hash(entry.get("preparedTree"), 40, f"{name} prepared tree")
    expected_manifest = require_hash(
        entry.get("preparedTreeManifestSha256"),
        64,
        f"{name} prepared tree-manifest SHA-256",
    )
    patch_relative = safe_relative(entry.get("patch"), f"{name} patch path")
    positive_control = safe_relative(entry.get("positiveControl"), f"{name} positive-control path")
    patch_path = fork_root / patch_relative
    require(patch_path.is_file() and not patch_path.is_symlink(), f"{name} patch is missing or unsafe: {patch_path}")
    actual_patch_sha256 = sha256(patch_path)
    require(
        actual_patch_sha256 == patch_sha256,
        f"{name} patch checksum mismatch: expected {patch_sha256}, got {actual_patch_sha256}",
    )

    checkout = output_root / name
    run(["git", "init", "--quiet", str(checkout)])
    git(checkout, "remote", "add", "origin", repository)
    git(checkout, "-c", "protocol.version=2", "fetch", "--quiet", "--no-tags", "--depth=1", "origin", commit)
    git(checkout, "-c", "advice.detachedHead=false", "checkout", "--quiet", "--detach", "FETCH_HEAD")

    actual_commit = git(checkout, "rev-parse", "HEAD").decode("ascii").strip()
    require(actual_commit == commit, f"{name} checkout mismatch: expected {commit}, got {actual_commit}")
    require(git(checkout, "status", "--porcelain=v1") == b"", f"{name} checkout was dirty before applying the patch")
    git(checkout, "apply", "--check", "--whitespace=nowarn", str(patch_path))
    git(checkout, "apply", "--whitespace=nowarn", str(patch_path))
    control_path = checkout / positive_control
    require(control_path.is_file() and not control_path.is_symlink(), f"{name} patch positive control is missing: {positive_control}")
    require(git(checkout, "status", "--porcelain=v1") != b"", f"{name} patch produced no source delta")
    require(
        git(checkout, "rev-parse", "HEAD").decode("ascii").strip() == commit,
        f"{name} patch unexpectedly changed checkout identity",
    )

    index_path = scratch_root / f"{name}.index"
    environment = os.environ.copy()
    environment["GIT_INDEX_FILE"] = str(index_path)
    git(checkout, "read-tree", "HEAD", environment=environment)
    git(checkout, "add", "-A", environment=environment)
    actual_tree = git(checkout, "write-tree", environment=environment).decode("ascii").strip()
    tree_listing = git(checkout, "ls-tree", "-r", "-z", actual_tree)
    actual_manifest = hashlib.sha256(tree_listing).hexdigest()
    require(
        actual_tree == expected_tree,
        f"{name} prepared tree mismatch: expected {expected_tree}, got {actual_tree}",
    )
    require(
        actual_manifest == expected_manifest,
        f"{name} prepared tree-manifest mismatch: expected {expected_manifest}, got {actual_manifest}",
    )
    return {
        "repository": repository,
        "commit": actual_commit,
        "patchSha256": actual_patch_sha256,
        "preparedTree": actual_tree,
        "preparedTreeManifestSha256": actual_manifest,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch and verify the exact patched AtomicFU and coroutines source trees")
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()
    fork_root = Path(__file__).resolve().parent.parent
    lock_path = fork_root / "source-lock.json"
    try:
        lock = load_json(lock_path)
        require(lock.get("schema") == 1, "unsupported source-lock schema")
        sources = lock.get("sources")
        require(isinstance(sources, dict) and set(sources) == {"atomicfu", "coroutines"}, "source-lock component set mismatch")
        output_root = arguments.output.resolve()
        require(not output_root.exists(), f"output path already exists: {output_root}")
        output_root.mkdir(parents=True)
        with tempfile.TemporaryDirectory(prefix="ohos-forks-source-index.") as scratch:
            receipts = {
                name: prepare_source(name, sources[name], fork_root, output_root, Path(scratch))
                for name in ("atomicfu", "coroutines")
            }
        receipt = {
            "schema": 1,
            "lockSha256": sha256(lock_path),
            "sources": receipts,
        }
        receipt_path = output_root / "prepared-sources.json"
        receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"prepare-sources: verified atomicfu and coroutines into {output_root}")
        print(json.dumps(receipt, sort_keys=True, separators=(",", ":")))
        return 0
    except PreparationError as error:
        print(f"prepare-sources: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
