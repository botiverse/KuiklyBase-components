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
    verify_receipt_path = root / "datetime-raft-receipt-publish" / "verify-receipt.json"

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
    manifest_prefixes = sorted({f["path"].rsplit("/", 1)[0] + "/" for f in merged_files})
    if sorted(plan["ownedPrefixes"]) != manifest_prefixes:
        fail("plan ownedPrefixes do not equal the manifest-derived prefixes")
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

    # Decision ↔ receipt status is a hard binding, not a soft option set:
    #   publish        → status=committed + full atomic identity
    #   noop-verified  → status=complete  (byte re-read of already-present set)
    # Accepting "complete" for a publish plan would green-light a path that
    # never claimed/staged/committed (the exact fail-open B1 closed).
    decision = plan["decision"]
    status = publish_receipt.get("status")
    if decision == "publish":
        require_publish_committed = True
        if require_publish_committed:
            if status != "committed":
                fail("publish plan requires a committed publish receipt")
            for key in ("claimId", "taskId", "manifestDigest"):
                if not isinstance(publish_receipt.get(key), str) or not publish_receipt[key]:
                    fail(f"committed publish receipt carries no {key}")
            if SHA256_RE.fullmatch(publish_receipt["manifestDigest"]) is None:
                fail("committed publish receipt manifestDigest is not 64-hex")
            # Independently recompute the object-manifest digest from the exact
            # merged {path,sha256,size} set (same algorithm as the release client
            # and the server). Tar/bundle digest is a different quantity.
            import atomic_release as ar
            recomputed_digest = ar.manifest_digest(
                [{"path": f["path"], "sha256": f["sha256"], "size": f["size"]} for f in merged_files]
            )
            if publish_receipt["manifestDigest"] != recomputed_digest:
                fail("publish receipt manifestDigest does not equal recomputed object-manifest digest")
            expected_task = os.environ.get("RAFT_RELEASE_TASK_ID", "")
            if not expected_task:
                fail("RAFT_RELEASE_TASK_ID is required to bind the publish receipt taskId")
            if publish_receipt["taskId"] != expected_task:
                fail("publish receipt taskId does not equal RAFT_RELEASE_TASK_ID")
            commit_receipt = publish_receipt.get("commitReceipt")
            if not isinstance(commit_receipt, dict) or not commit_receipt:
                fail("committed publish receipt carries no commitReceipt")
            if commit_receipt.get("state") != "committed":
                fail("commitReceipt.state is not committed")
            if not isinstance(publish_receipt.get("ownedPrefixes"), list) or not publish_receipt["ownedPrefixes"]:
                fail("committed publish receipt carries no ownedPrefixes")
            if sorted(publish_receipt["ownedPrefixes"]) != sorted(plan["ownedPrefixes"]):
                fail("committed publish receipt ownedPrefixes do not equal the plan")
            # Readback proof is mandatory on the publish path: workflow writes
            # verify-receipt.json separately so it cannot overwrite the committed
            # atomic receipt, and the aggregate must consume it.
            require_verify_readback = True
            if require_verify_readback:
                if not verify_receipt_path.is_file():
                    fail("publish path requires verify-receipt.json readback proof")
                verify_receipt = load_json(verify_receipt_path)
                if verify_receipt.get("status") != "complete":
                    fail("verify receipt status is not complete")
                for field in ("version", "sourceSha"):
                    if verify_receipt.get(field) != merged.get(field):
                        fail(f"verify receipt {field} drift")
                verify_files = validate_file_entries(verify_receipt.get("files"), "verify receipt")
                if verify_receipt.get("fileCount") != len(verify_files):
                    fail("verify receipt fileCount does not equal its file list")
                if len(verify_files) != len(merged_files):
                    fail("verify receipt fileCount does not equal the merged manifest")
                verify_by_path = {f["path"]: f for f in verify_files}
                for entry in merged_files:
                    twin = verify_by_path.get(entry["path"])
                    if twin is None:
                        fail(f"verify receipt missing path: {entry['path']}")
                    if twin["sha256"] != entry["sha256"] or twin["size"] != entry["size"]:
                        fail(f"verify receipt byte binding mismatch for {entry['path']}")
                if sorted(verify_by_path) != sorted(f["path"] for f in merged_files):
                    fail("verify receipt does not cover the merged manifest exactly")
    elif decision == "noop-verified":
        # All-present may still carry a recovered atomic receipt when a prior
        # publish committed without durable receipt (exhausted unknown). Treat
        # status=committed with full identity as the recovered publish path.
        if status == "committed":
            require_publish_committed = True
            if require_publish_committed:
                for key in ("claimId", "taskId", "manifestDigest"):
                    if not isinstance(publish_receipt.get(key), str) or not publish_receipt[key]:
                        fail(f"recovered committed receipt carries no {key}")
                if SHA256_RE.fullmatch(publish_receipt["manifestDigest"]) is None:
                    fail("recovered committed receipt manifestDigest is not 64-hex")
                import atomic_release as ar
                recomputed_digest = ar.manifest_digest(
                    [{"path": f["path"], "sha256": f["sha256"], "size": f["size"]} for f in merged_files]
                )
                if publish_receipt["manifestDigest"] != recomputed_digest:
                    fail("recovered receipt manifestDigest does not equal recomputed object-manifest digest")
                expected_task = os.environ.get("RAFT_RELEASE_TASK_ID", "")
                if not expected_task:
                    fail("RAFT_RELEASE_TASK_ID is required to bind the recovered publish receipt taskId")
                if publish_receipt["taskId"] != expected_task:
                    fail("recovered publish receipt taskId does not equal RAFT_RELEASE_TASK_ID")
                commit_receipt = publish_receipt.get("commitReceipt")
                if not isinstance(commit_receipt, dict) or not commit_receipt:
                    fail("recovered committed receipt carries no commitReceipt")
                if commit_receipt.get("state") != "committed":
                    fail("recovered commitReceipt.state is not committed")
                if not isinstance(publish_receipt.get("ownedPrefixes"), list) or not publish_receipt["ownedPrefixes"]:
                    fail("recovered committed receipt carries no ownedPrefixes")
                if sorted(publish_receipt["ownedPrefixes"]) != sorted(plan["ownedPrefixes"]):
                    fail("recovered committed receipt ownedPrefixes do not equal the plan")
                require_verify_readback = True
                if require_verify_readback:
                    if not verify_receipt_path.is_file():
                        fail("recovered committed path requires verify-receipt.json readback proof")
                    verify_receipt = load_json(verify_receipt_path)
                    if verify_receipt.get("status") != "complete":
                        fail("verify receipt status is not complete")
                    for field in ("version", "sourceSha"):
                        if verify_receipt.get(field) != merged.get(field):
                            fail(f"verify receipt {field} drift")
                    verify_files = validate_file_entries(verify_receipt.get("files"), "verify receipt")
                    if verify_receipt.get("fileCount") != len(verify_files):
                        fail("verify receipt fileCount does not equal its file list")
                    if len(verify_files) != len(merged_files):
                        fail("verify receipt fileCount does not equal the merged manifest")
                    verify_by_path = {f["path"]: f for f in verify_files}
                    for entry in merged_files:
                        twin = verify_by_path.get(entry["path"])
                        if twin is None:
                            fail(f"verify receipt missing path: {entry['path']}")
                        if twin["sha256"] != entry["sha256"] or twin["size"] != entry["size"]:
                            fail(f"verify receipt byte binding mismatch for {entry['path']}")
                    if sorted(verify_by_path) != sorted(f["path"] for f in merged_files):
                        fail("verify receipt does not cover the merged manifest exactly")
        elif status == "complete":
            # A complete receipt must not pretend to be atomic.
            for key in ("claimId", "taskId", "manifestDigest", "commitReceipt"):
                if key in publish_receipt and publish_receipt[key] not in (None, "", {}, []):
                    fail(f"complete publish receipt must not carry atomic field {key}")
        else:
            fail("noop-verified plan requires a complete or recovered-committed publish receipt")
    else:
        fail("plan decision is not a known value")
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

    # Terminal status follows the plan decision only after the bindings above
    # have been enforced. A fail-open regression that skips publish→committed
    # binding would still emit status=committed here — the negative teeth catch
    # that by requiring SystemExit before this point.
    # Recovered atomic receipt on an all-present (noop-verified) plan is still
    # terminal status=committed (original claim provenance).
    recovered_atomic = (
        decision == "noop-verified" and publish_receipt.get("status") == "committed"
    )
    aggregate = {
        "status": "complete" if (decision == "noop-verified" and not recovered_atomic) else "committed",
        "version": merged["version"],
        "sourceSha": merged["sourceSha"],
        "destination": merged.get("destination", ""),
        "fileCount": merged.get("fileCount"),
        "files": merged["files"],
        "plan": plan,
        "publishReceiptStatus": status,
        "tokenIdentities": sorted(prefixes),
        "receipts": ["plan", "publish", "verify", "receipt"]
        if (decision == "publish" or recovered_atomic)
        else ["plan", "publish", "receipt"],
    }
    if (decision == "publish" or recovered_atomic) and status == "committed" and all(
        isinstance(publish_receipt.get(k), str) and publish_receipt.get(k)
        for k in ("claimId", "taskId", "manifestDigest")
    ) and isinstance(publish_receipt.get("commitReceipt"), dict):
        aggregate["claimId"] = publish_receipt["claimId"]
        aggregate["taskId"] = publish_receipt["taskId"]
        aggregate["manifestDigest"] = publish_receipt["manifestDigest"]
        aggregate["commitReceipt"] = publish_receipt["commitReceipt"]
        aggregate["verifyReceiptStatus"] = "complete"
    Path(args.output).write_text(json.dumps(aggregate, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        f"aggregate receipt: {merged['fileCount']} files, version={merged['version']}, "
        f"source={merged['sourceSha'][:12]}, token={sorted(prefixes)[0]}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
