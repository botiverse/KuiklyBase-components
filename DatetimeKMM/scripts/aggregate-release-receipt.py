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
import subprocess
import sys
from pathlib import Path


def fail(message: str) -> None:
    print(f"AGGREGATE FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


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
    publish_receipt = load_json(root / "datetime-raft-receipt-publish" / "publish-receipt.json")
    publish_token = load_json(root / "datetime-raft-receipt-publish" / "publish-token-receipt.json")
    token_receipt = load_json(Path(args.terminal_token_receipt))

    if publish_receipt.get("status") != "complete":
        fail("publish receipt incomplete")
    for field in ("version", "sourceSha"):
        if publish_receipt.get(field) != merged.get(field):
            fail(f"publish receipt {field} drift")
    covered = sorted(f["path"] for f in publish_receipt.get("files", []))
    expected = sorted(f["path"] for f in merged.get("files", []))
    if not expected:
        fail("merged manifest carries no files")
    if covered != expected:
        fail("publish receipt does not cover the merged manifest exactly")

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

    prefixes = {
        plan.get("tokenHashPrefix"),
        publish_token.get("hashPrefix"),
        token_receipt.get("hashPrefix"),
    }
    if len(prefixes) != 1 or prefixes == {""} or None in prefixes:
        fail(f"token identity drifted across jobs: {sorted(p for p in prefixes if p)}")
    for label, record in (("publish", publish_token), ("terminal", token_receipt)):
        if record.get("fullHashMatchedLocally") is not True:
            fail(f"{label} token receipt lacks a local full-hash match")
        if record.get("revokedAtAbsent") is not True:
            fail(f"{label} token was revoked")

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
