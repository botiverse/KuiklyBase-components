#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path
from typing import Any

from publication_http import PublicationError, RAFT_ARTIFACTS_BASE_URL, RepositoryClient, require


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PublicationError(f"cannot read JSON {path}: {error}") from error
    require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def publication_priority(relative: str) -> tuple[int, int, str]:
    metadata = relative.endswith(".pom") or relative.endswith(".module")
    target = "-ohosarm64/" in relative
    if not metadata:
        return 0, 0, relative
    if target:
        return 1, 0, relative
    return 2, 0, relative


def validate_contract(repository: Path, manifest_path: Path, plan_path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    manifest = load_json(manifest_path)
    plan = load_json(plan_path)
    require(manifest.get("schema") == 1 and plan.get("schema") == 1, "unsupported publication schema")
    require(plan.get("carrierSha") == manifest.get("carrierSha"), "publication plan carrier does not match the manifest")
    require(plan.get("manifestSha256") == sha256(manifest_path), "publication plan is not bound to the exact manifest bytes")
    files = manifest.get("files")
    require(isinstance(files, list) and len(files) == 20, "release manifest must contain exactly 20 files")
    paths: set[str] = set()
    for entry in files:
        require(isinstance(entry, dict) and set(entry) == {"path", "size", "sha256"}, "invalid release-manifest file entry")
        relative = entry.get("path")
        require(
            isinstance(relative, str)
            and re.fullmatch(r"[A-Za-z0-9._/-]+", relative) is not None
            and not relative.startswith("/")
            and ".." not in Path(relative).parts,
            f"unsafe release path: {relative!r}",
        )
        require(relative not in paths, f"duplicate release path: {relative}")
        paths.add(relative)
        artifact = repository / relative
        require(artifact.is_file() and not artifact.is_symlink(), f"staged release file is missing or unsafe: {artifact}")
        require(artifact.stat().st_size == entry.get("size"), f"staged release size drift: {relative}")
        require(sha256(artifact) == entry.get("sha256"), f"staged release checksum drift: {relative}")

    destinations = plan.get("destinations")
    require(isinstance(destinations, dict) and set(destinations) == {"raft"}, "publication plan destination set mismatch")
    for name, destination in destinations.items():
        require(isinstance(destination, dict), f"invalid publication plan for {name}")
        require(set(destination) == {"state", "existing", "missing"}, f"publication plan field set mismatch for {name}")
        existing = destination.get("existing")
        missing = destination.get("missing")
        require(isinstance(existing, list) and isinstance(missing, list), f"publication plan paths are invalid for {name}")
        require(len(existing) == len(set(existing)) and len(missing) == len(set(missing)), f"publication plan duplicates paths for {name}")
        require(set(existing).isdisjoint(missing), f"publication plan overlaps existing and missing paths for {name}")
        require(set(existing) | set(missing) == paths, f"publication plan does not cover the exact manifest for {name}")
        expected_state = "complete" if not missing else "absent" if not existing else "partial-exact"
        require(destination.get("state") == expected_state, f"publication plan state is inconsistent for {name}")
    return manifest, plan


def main() -> int:
    parser = argparse.ArgumentParser(description="Upload only exact planned missing files from one immutable staging repository")
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--plan", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        repository = arguments.repository.resolve()
        manifest_path = arguments.manifest.resolve()
        plan_path = arguments.plan.resolve()
        require(repository.is_dir() and not repository.is_symlink(), f"staging repository is missing: {repository}")
        manifest, plan = validate_contract(repository, manifest_path, plan_path)
        entries = {entry["path"]: entry for entry in manifest["files"]}

        client = RepositoryClient(
            "Raft Artifacts",
            os.environ.get("RAFT_ARTIFACTS_URL", RAFT_ARTIFACTS_BASE_URL),
            os.environ.get("RAFT_ARTIFACTS_USERNAME", "raft-ci"),
            os.environ.get("RAFT_ARTIFACTS_PUBLISH_TOKEN", ""),
        )

        uploaded = {"raft": 0}
        missing = plan["destinations"]["raft"]["missing"]
        for relative in sorted(missing, key=publication_priority):
            require(relative in entries, f"publication plan references an unknown path for raft: {relative}")
            artifact = repository / relative
            require(artifact.stat().st_size == entries[relative]["size"], f"staged release changed before upload: {relative}")
            require(sha256(artifact) == entries[relative]["sha256"], f"staged release changed before upload: {relative}")
            client.upload(relative, artifact.read_bytes())
            uploaded["raft"] += 1

        print(
            "publish-staging: uploaded exact staged bytes "
            f"raft={uploaded['raft']} carrier={manifest['carrierSha']}"
        )
        return 0
    except PublicationError as error:
        print(f"publish-staging: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
