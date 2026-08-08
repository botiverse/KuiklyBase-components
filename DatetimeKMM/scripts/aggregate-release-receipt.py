#!/usr/bin/env python3
"""Aggregate release receipt for the Raft-only DatetimeKMM lane (task #106).

Runs in the terminal receipt job. Cross-binds, in one artifact:
  - the merged manifest (staged byte set) and the global plan;
  - the single writer's publish receipt (must cover the manifest exactly);
  - the landed-release identity: live current master == dispatch SHA ==
    manifest sourceSha == committed mavenVersion;
  - one token identity across plan / publish / this terminal job (the
    16-hex introspection prefixes must all match; A->B->A goes red here).

Every input is a file produced upstream in the same run; every failure exits
nonzero with a named reason. The token itself and full hashes never appear in
the output.

Usage: aggregate-release-receipt.py --artifacts DIR --output FILE
DIR layout (as produced by the workflow's download step):
  datetime-raft-global-plan/{merged-manifest.json,global-plan.json}
  datetime-raft-receipt-publish/{publish-receipt.json,publish-token-receipt.json}
  token-receipt.json must sit directly under DIR.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path


def fail(message: str) -> None:
    print(f"AGGREGATE FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


SHA256_RE = re.compile(r"[0-9a-f]{64}")
HEX16_RE = re.compile(r"[0-9a-f]{16}")


def validate_file_entries(files: object, label: str) -> list[dict]:
    if not isinstance(files, list) or not files:
        fail(f"{label}: files must be a non-empty list")
    seen: set[str] = set()
    for entry in files:
        if not isinstance(entry, dict) or set(entry) != {"path", "sha256", "size"}:
            fail(f"{label}: invalid file entry keys")
        path = entry["path"]
        if (
            not isinstance(path, str)
            or not path
            or path.startswith("/")
            or ".." in path.split("/")
            or "//" in path
        ):
            fail(f"{label}: unsafe path {path!r}")
        if path in seen:
            fail(f"{label}: duplicate path {path}")
        seen.add(path)
        if not isinstance(entry["sha256"], str) or SHA256_RE.fullmatch(entry["sha256"]) is None:
            fail(f"{label}: invalid sha256 for {path}")
        if not isinstance(entry["size"], int) or entry["size"] <= 0:
            fail(f"{label}: invalid size for {path}")
    return files


def validate_token_receipt(record: object, label: str) -> dict:
    if not isinstance(record, dict):
        fail(f"{label}: token receipt is not an object")
    if not isinstance(record.get("hashPrefix"), str) or HEX16_RE.fullmatch(record["hashPrefix"]) is None:
        fail(f"{label}: hashPrefix is not 16-hex")
    if record.get("fullHashMatchedLocally") is not True:
        fail(f"{label}: lacks a local full-hash match")
    if record.get("revokedAtAbsent") is not True:
        fail(f"{label}: token was revoked")
    expires_at = record.get("expiresAt")
    if not isinstance(expires_at, int) or expires_at == 0:
        fail(f"{label}: token has no expiry")
    if expires_at <= int(time.time() * 1000):
        fail(f"{label}: token receipt is expired")
    grants = record.get("grants")
    if not isinstance(grants, list) or len(grants) != 1:
        fail(f"{label}: token must carry exactly one minimal grant")
    grant = grants[0]
    if not isinstance(grant, dict) or grant.get("scope") != "build.raft.kuiklybase":
        fail(f"{label}: grant scope is not exactly the lane prefix")
    permissions = grant.get("permissions")
    if not isinstance(permissions, list) or "publish" not in permissions or not set(permissions) <= {"read", "publish"}:
        fail(f"{label}: grant permissions are not the minimal lane cap")
    principal = grant.get("principal")
    if not isinstance(principal, dict) or principal.get("kind") != "agent" or not principal.get("id"):
        fail(f"{label}: grant principal is not an agent principal")
    expected_principal = os.environ.get("RAFT_ARTIFACTS_EXPECT_PRINCIPAL", "")
    if not expected_principal:
        fail(f"{label}: RAFT_ARTIFACTS_EXPECT_PRINCIPAL is not set (required)")
    if principal.get("id") != expected_principal:
        fail(f"{label}: grant principal is not the expected release principal")
    return record


def load_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")
    if not isinstance(value, dict):
        fail(f"not a JSON object: {path}")
    return value


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", required=True)
    parser.add_argument("--terminal-token-receipt", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)

    root = Path(args.artifacts)
    merged = load_json(root / "datetime-raft-global-plan" / "merged-manifest.json")
    plan = load_json(root / "datetime-raft-global-plan" / "global-plan.json")
    plan_token = load_json(root / "datetime-raft-global-plan" / "token-receipt.json")
    publish_receipt = load_json(root / "datetime-raft-receipt-publish" / "publish-receipt.json")
    publish_token = load_json(root / "datetime-raft-receipt-publish" / "publish-token-receipt.json")
    token_receipt = load_json(Path(args.terminal_token_receipt))

    merged_files = validate_file_entries(merged.get("files"), "merged manifest")
    publish_files = validate_file_entries(publish_receipt.get("files"), "publish receipt")

    # Full plan schema: known decision, count match, all binding fields present
    # and well-formed (owned prefixes, missing list, bundle digest, token prefix).
    if plan.get("decision") not in {"publish", "noop-verified"}:
        fail("plan decision is not a known value")
    if plan.get("fileCount") != len(merged_files):
        fail("plan fileCount does not equal the merged manifest")
    for key, pattern in (("bundleSha256", SHA256_RE), ("tokenHashPrefix", HEX16_RE)):
        if not isinstance(plan.get(key), str) or pattern.fullmatch(plan[key]) is None:
            fail(f"plan carries no valid {key}")
    if not isinstance(plan.get("ownedPrefixes"), list) or not plan["ownedPrefixes"]:
        fail("plan carries no ownedPrefixes")
    if not isinstance(plan.get("missing"), list):
        fail("plan carries no missing list")
    # decision/shape coherence: publish means every file was missing;
    # noop-verified means none was. A contradictory plan is not a plan.
    if plan["decision"] == "publish" and sorted(plan["missing"]) != sorted(f["path"] for f in merged_files):
        fail("plan decision=publish but its missing set is not the whole manifest")
    if plan["decision"] == "noop-verified" and plan["missing"]:
        fail("plan decision=noop-verified but its missing set is non-empty")
    if merged.get("fileCount") != len(merged_files):
        fail("merged manifest fileCount does not equal its file list")
    if publish_receipt.get("fileCount") != len(publish_files):
        fail("publish receipt fileCount does not equal its file list")

    if publish_receipt.get("status") != "complete":
        fail("publish receipt incomplete")
    for field in ("version", "sourceSha"):
        if publish_receipt.get(field) != merged.get(field):
            fail(f"publish receipt {field} drift")
    if publish_receipt.get("fileCount") != merged.get("fileCount"):
        fail("publish receipt fileCount does not equal the merged manifest")
    # Per-entry byte binding: path + sha256 + size must be identical between
    # the publish receipt and the merged manifest -- not just the path set.
    merged_by_path = {f["path"]: f for f in merged_files}
    for entry in publish_files:
        twin = merged_by_path.get(entry["path"])
        if twin is None:
            fail(f"publish receipt path outside the manifest: {entry['path']}")
        if twin["sha256"] != entry["sha256"] or twin["size"] != entry["size"]:
            fail(f"publish receipt byte binding mismatch for {entry['path']}")
    covered = sorted(f["path"] for f in publish_files)
    expected = sorted(merged_by_path)
    if not expected:
        fail("merged manifest carries no files")
    if covered != expected:
        fail("publish receipt does not cover the merged manifest exactly")

    # The frozen bundle must be present and match the plan digest + manifest set.
    bundle_path = root / "datetime-raft-global-plan" / "frozen-bundle.tar"
    if not bundle_path.is_file():
        fail("frozen bundle artifact is missing")
    import hashlib
    bundle_sha = hashlib.sha256(bundle_path.read_bytes()).hexdigest()
    if bundle_sha != plan["bundleSha256"]:
        fail("frozen bundle digest does not match the plan")
    import tarfile
    with tarfile.open(bundle_path) as _tar:
        member_names = sorted(m.name for m in _tar.getmembers() if m.isfile())
    if member_names != sorted(merged_by_path.keys()):
        fail("frozen bundle members do not equal the manifest primary set")

    validate_token_receipt(plan_token, "plan")
    validate_token_receipt(publish_token, "publish")
    validate_token_receipt(token_receipt, "terminal")

    # Landed-release binding: a FRESH fetch, then live current master ==
    # dispatch SHA == the sourceSha baked into every staged POM == receipts.
    fetch = subprocess.run(["git", "fetch", "--quiet", "origin", "master"], check=False)
    if fetch.returncode != 0:
        fail("cannot fetch origin master for the landed-release binding")
    live_master = subprocess.run(
        ["git", "rev-parse", "origin/master"], check=True, capture_output=True, text=True
    ).stdout.strip()
    dispatch_sha = os.environ.get("GITHUB_SHA", "")
    if not (live_master == dispatch_sha == merged.get("sourceSha")):
        fail("landed-release binding drift (master != dispatch != sourceSha)")
    committed = subprocess.run(
        ["grep", "^mavenVersion=", "gradle.properties"], check=True, capture_output=True, text=True
    ).stdout.strip().split("=", 1)[1]
    if committed != merged.get("version"):
        fail("manifest version is not the committed mavenVersion")

    # One token identity must serve plan, publish and this terminal job alike
    # (A->B->A goes red here).
    prefixes = {
        plan.get("tokenHashPrefix"),
        plan_token.get("hashPrefix"),
        publish_token.get("hashPrefix"),
        token_receipt.get("hashPrefix"),
    }
    if len(prefixes) != 1 or prefixes == {""} or None in prefixes:
        fail(f"token identity drifted across jobs: {sorted(p for p in prefixes if p)}")

    aggregate = {
        "status": "complete",
        "version": merged["version"],
        "sourceSha": merged["sourceSha"],
        "destination": merged.get("destination", ""),
        "fileCount": merged.get("fileCount"),
        "files": merged["files"],
        "plan": plan,
        "tokenIdentities": sorted(prefixes),
        "receipts": ["plan", "publish", "receipt"],
    }
    Path(args.output).write_text(json.dumps(aggregate, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        f"aggregate receipt: {merged['fileCount']} files, version={merged['version']}, "
        f"source={merged['sourceSha'][:12]}, token={sorted(prefixes)[0]}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
