#!/usr/bin/env python3
"""Assert a readback receipt before it is uploaded.

This lives in a file rather than inline in the workflow so the teeth can feed
it crafted receipts: an assertion that only ever runs on a real dispatch is an
assertion nothing has tested. Every expected value is supplied by the caller,
so a receipt that names a different run, attempt, ref or source tree fails here
rather than travelling on as authority.

Usage: assert-readback-receipt.py <receipt.json>
Env:   EXPECT_VERSION, EXPECT_COUNT, EXPECT_MANIFEST_EXACT,
       EXPECT_READBACK_EXACT, EXPECT_READBACK_REF, EXPECT_RUN_ID,
       EXPECT_RUN_ATTEMPT
"""
import json
import os
import sys

REQUIRED_PROVENANCE = (
    "manifestSourceExact",
    "readbackSourceExact",
    "readbackRef",
    "runId",
    "runAttempt",
)


def fail(message):
    raise SystemExit(f"RECEIPT FAIL: {message}")


def main():
    if len(sys.argv) != 2:
        fail("usage: assert-readback-receipt.py <receipt.json>")
    try:
        receipt = json.load(open(sys.argv[1]))
    except (OSError, ValueError) as exc:
        fail(f"unreadable receipt: {exc}")

    def expect(name):
        value = os.environ.get(name)
        if not value:
            fail(f"missing expectation {name}")
        return value

    if receipt.get("status") != "complete":
        fail(f"status is {receipt.get('status')!r}")
    if receipt.get("version") != expect("EXPECT_VERSION"):
        fail(f"version is {receipt.get('version')!r}")

    count = int(expect("EXPECT_COUNT"))
    files = receipt.get("files")
    if not isinstance(files, list):
        fail("files is not a list")
    if receipt.get("fileCount") != count or len(files) != count:
        fail(f"expected {count} files, receipt says {receipt.get('fileCount')} with {len(files)} entries")

    seen = set()
    for entry in files:
        path, digest = entry.get("path", ""), entry.get("sha256", "")
        if len(digest) != 64 or not all(c in "0123456789abcdef" for c in digest):
            fail(f"bad digest for {path!r}")
        if not path or path.startswith("/") or ".." in path.split("/"):
            fail(f"unsafe path {path!r}")
        if path in seen:
            fail(f"duplicate path {path!r}")
        seen.add(path)

    provenance = receipt.get("provenance")
    if not isinstance(provenance, dict):
        fail("receipt carries no provenance")
    for key in REQUIRED_PROVENANCE:
        if not provenance.get(key):
            fail(f"empty or missing provenance field {key}")
    # Every identity field is compared. A correct run id with a wrong attempt is
    # still the wrong run.
    for key, env in (
        ("manifestSourceExact", "EXPECT_MANIFEST_EXACT"),
        ("readbackSourceExact", "EXPECT_READBACK_EXACT"),
        ("readbackRef", "EXPECT_READBACK_REF"),
        ("runId", "EXPECT_RUN_ID"),
        ("runAttempt", "EXPECT_RUN_ATTEMPT"),
    ):
        if provenance[key] != expect(env):
            fail(f"provenance {key} is {provenance[key]!r}, expected {expect(env)!r}")

    print(f"receipt ok: {count} files, provenance bound to {provenance['readbackSourceExact']}")


if __name__ == "__main__":
    main()
