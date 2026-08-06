#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path
from typing import Any, Callable, Optional

from publication_http import PublicationError, RAFT_ARTIFACTS_BASE_URL, RepositoryClient, require


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PublicationError(f"cannot read JSON {path}: {error}") from error
    require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def load_manifest(path: Path) -> dict[str, Any]:
    manifest = load_json(path)
    require(manifest.get("schema") == 1, "unsupported release-manifest schema")
    carrier_sha = manifest.get("carrierSha")
    require(
        isinstance(carrier_sha, str) and re.fullmatch(r"[0-9a-f]{40}", carrier_sha) is not None,
        "release manifest has an invalid carrier SHA",
    )
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
            f"unsafe release-manifest path: {relative!r}",
        )
        require(relative not in paths, f"duplicate release-manifest path: {relative}")
        paths.add(relative)
        require(isinstance(entry.get("size"), int) and entry["size"] > 0, f"invalid release size: {relative}")
        require(
            isinstance(entry.get("sha256"), str) and re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]) is not None,
            f"invalid release SHA-256: {relative}",
        )
    return manifest


Fetch = Callable[[str], Optional[bytes]]


def classify_files(entries: list[dict[str, Any]], fetch: Fetch, destination: str) -> dict[str, Any]:
    missing: list[str] = []
    existing: list[str] = []
    for entry in entries:
        relative = entry["path"]
        body = fetch(relative)
        if body is None:
            missing.append(relative)
            continue
        require(
            len(body) == entry["size"],
            f"{destination} existing file size diverges from the immutable manifest: {relative}",
        )
        require(
            sha256_bytes(body) == entry["sha256"],
            f"{destination} existing file checksum diverges from the immutable manifest: {relative}",
        )
        existing.append(relative)
    if not missing:
        state = "complete"
    elif not existing:
        state = "absent"
    else:
        state = "partial-exact"
    return {"state": state, "existing": existing, "missing": missing}


def repository_clients() -> dict[str, RepositoryClient]:
    raft_username = os.environ.get("RAFT_ARTIFACTS_USERNAME", "raft-ci")
    raft_token = os.environ.get("RAFT_ARTIFACTS_PUBLISH_TOKEN", "")
    return {
        "raft": RepositoryClient(
            "Raft Artifacts",
            os.environ.get("RAFT_ARTIFACTS_URL", RAFT_ARTIFACTS_BASE_URL),
            raft_username,
            raft_token,
        ),
    }


def inspect_repositories(manifest: dict[str, Any]) -> dict[str, Any]:
    clients = repository_clients()
    positive_control = os.environ.get(
        "OHOS_FORKS_POSITIVE_CONTROL_PATH",
        "com/tencent/kuiklybase/network/0.1.0-raft.32/network-0.1.0-raft.32.pom",
    )
    require(
        isinstance(positive_control, str)
        and positive_control
        and all(entry["path"] != positive_control for entry in manifest["files"]),
        "repository positive control must be a distinct known-existing path",
    )
    for client in clients.values():
        client.positive_control(positive_control)
    return {
        name: classify_files(manifest["files"], client.fetch, client.name)
        for name, client in clients.items()
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Plan and verify exact-byte immutable Raft Artifacts publication")
    subparsers = parser.add_subparsers(dest="mode", required=True)
    plan_parser = subparsers.add_parser("plan")
    plan_parser.add_argument("--manifest", type=Path, required=True)
    plan_parser.add_argument("--output", type=Path, required=True)
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--manifest", type=Path, required=True)
    arguments = parser.parse_args()

    try:
        manifest_path = arguments.manifest.resolve()
        manifest = load_manifest(manifest_path)
        destinations = inspect_repositories(manifest)
        if arguments.mode == "plan":
            output = arguments.output.resolve()
            require(not output.exists(), f"publication plan output already exists: {output}")
            plan = {
                "schema": 1,
                "carrierSha": manifest["carrierSha"],
                "manifestSha256": sha256_file(manifest_path),
                "destinations": destinations,
            }
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(plan, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            for name, state in destinations.items():
                print(
                    f"publication-state: {name}={state['state']} "
                    f"existing={len(state['existing'])} missing={len(state['missing'])}"
                )
            return 0

        for name, state in destinations.items():
            require(state["state"] == "complete", f"{name} did not converge: {state['state']}, missing={len(state['missing'])}")
        print("publication-state: exact-byte readback converged for all 20 files in Raft Artifacts")
        return 0
    except PublicationError as error:
        print(f"publication-state: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
