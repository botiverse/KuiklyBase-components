#!/usr/bin/env python3
"""Offline teeth for raft-mirror.py.

Every gate of the mirror is driven against a fake transport: no network, no
credentials, no real repository. A gate that no test can turn red is not a
gate. Run: python3 DatetimeKMM/scripts/test-raft-mirror.py
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import sys
import tempfile
import types
import urllib.error
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("raft_mirror", HERE / "raft-mirror.py")
mirror = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mirror)

VERSION = mirror.AUTHORITY_VERSION
EXPECTED_SOURCE = mirror.EXPECTED_SOURCE_EXACT
TOTAL = mirror.EXPECTED_TOTAL

PASS = 0
FAIL = 0
FAILURES: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    global PASS, FAIL
    if condition:
        PASS += 1
        print(f"  ok {name}")
    else:
        FAIL += 1
        FAILURES.append(name)
        print(f"  FAIL {name} {detail}")


def expect_raises(name: str, fn, needle: str = "") -> None:
    try:
        fn()
    except mirror.MirrorError as error:
        check(name, needle in str(error), f"(message was: {error})")
        return
    except Exception as error:  # noqa: BLE001 - teeth must see every escape path
        check(name, False, f"(escaped as {type(error).__name__}: {error})")
        return
    check(name, False, "(no error raised)")


class FakeClient:
    """In-memory stand-in for RepositoryClient with failure injection."""

    def __init__(self) -> None:
        self.store: dict[str, bytes] = {}
        self.calls: list[tuple[str, str]] = []
        self.head_status_override: dict[str, int] = {}
        self.put_fail_status: dict[str, int] = {}

    def request(self, relative: str, method: str, body: bytes | None = None):
        self.calls.append((method, relative))
        if method == "HEAD":
            if relative in self.head_status_override:
                return self.head_status_override[relative], b""
            return (200, b"") if relative in self.store else (404, b"")
        if method == "GET":
            if relative not in self.store:
                return 404, b""
            return 200, self.store[relative]
        if method == "PUT":
            if relative in self.put_fail_status:
                return self.put_fail_status[relative], b""
            assert body is not None
            self.store[relative] = body
            return 201, b""
        raise AssertionError(f"unexpected method {method}")


def make_files() -> list[dict[str, str]]:
    """The 34-entry synthetic receipt set across all seven publications."""
    entries = []
    groups = [
        ("datetime", VERSION, 5),
        ("datetime-android", VERSION, 4),
        ("datetime-iosx64", VERSION, 5),
        ("datetime-iosarm64", VERSION, 5),
        ("datetime-iossimulatorarm64", VERSION, 5),
        ("datetime", VERSION + "-ohos", 5),
        ("datetime-ohosarm64", VERSION + "-ohos", 5),
    ]
    for artifact, version, count in groups:
        for index in range(count):
            path = f"build/raft/kuiklybase/{artifact}/{version}/{artifact}-{version}-{index}.bin"
            entries.append({"path": path, "sha256": hashlib.sha256(path.encode()).hexdigest()})
    assert len(entries) == TOTAL, len(entries)
    return entries


def staged_bytes(entry: dict[str, str]) -> bytes:
    # The byte content each staged file must carry: sha256(content) == entry sha.
    # make_files derives the digest from the path string, so content = path.
    return entry["path"].encode()


def write_receipt(directory: Path, files: list[dict[str, str]], **overrides) -> Path:
    receipt = {
        "status": "complete",
        "repository": "botiverse/KuiklyBase-components",
        "version": VERSION,
        "fileCount": len(files),
        "provenance": {
            "manifestSourceExact": EXPECTED_SOURCE,
            "readbackSourceExact": "3c47d977590e41fee5ec34e0ca4977210ee95979",
            "readbackRef": "refs/heads/master",
            "runId": "31153770824",
            "runAttempt": "1",
        },
        "files": files,
    }
    receipt.update(overrides)
    path = directory / "readback-receipt.json"
    directory.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(receipt), encoding="utf-8")
    return path


def stage_bytes(root: Path, files: list[dict[str, str]]) -> Path:
    bytes_dir = root / "bytes"
    for entry in files:
        dest = bytes_dir / entry["path"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(staged_bytes(entry))
    return bytes_dir


def run_command(argv: list[str], client: FakeClient) -> int:
    """Drive mirror.main with client_from_env stubbed to the fake."""
    original = mirror.client_from_env
    mirror.client_from_env = lambda: client
    try:
        return mirror.main(argv)
    finally:
        mirror.client_from_env = original


def run_direct(argv: list[str], client: FakeClient) -> int:
    """Same dispatch but without the CLI error veil: MirrorError propagates."""
    original = mirror.client_from_env
    mirror.client_from_env = lambda: client
    try:
        args = mirror.build_parser().parse_args(argv)
        return args.func(args)
    finally:
        mirror.client_from_env = original


def main() -> int:
    files = make_files()

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        receipt = write_receipt(root, files)
        bytes_dir = stage_bytes(root, files)
        plan_path = root / "plan.json"

        # 1. all-absent -> publish decision covering exactly the receipt set
        client = FakeClient()
        code = run_command(["plan", "--receipt", str(receipt), "--output", str(plan_path)], client)
        plan = json.loads(plan_path.read_text())
        check("plan_all_absent_decision_publish", code == 0 and plan["decision"] == "publish")
        check("plan_all_absent_covers_receipt", sorted(plan["missing"]) == sorted(f["path"] for f in files))

        # 2. all-present identical -> noop-complete
        full = FakeClient()
        for entry in files:
            full.store[entry["path"]] = staged_bytes(entry)
        code = run_command(["plan", "--receipt", str(receipt), "--output", str(plan_path)], full)
        plan = json.loads(plan_path.read_text())
        check("plan_all_present_identical_noop", code == 0 and plan["decision"] == "noop-complete")

        # 3. partial state fails closed even when the present bytes match
        partial = FakeClient()
        partial.store[files[0]["path"]] = staged_bytes(files[0])
        expect_raises(
            "plan_partial_state_fails",
            lambda: run_direct(["plan", "--receipt", str(receipt), "--output", str(plan_path)], partial),
            "partial publication state",
        )

        # 4. an existing path with different bytes stops the run, never repairs
        foreign = FakeClient()
        foreign.store[files[0]["path"]] = b"foreign-bytes"
        expect_raises(
            "plan_existing_wrong_bytes_fails",
            lambda: run_direct(["plan", "--receipt", str(receipt), "--output", str(plan_path)], foreign),
            "DIFFERENT bytes",
        )

        # 5. unexpected HEAD status fails closed
        odd = FakeClient()
        odd.head_status_override[files[0]["path"]] = 500
        expect_raises(
            "plan_unexpected_head_status_fails",
            lambda: run_direct(["plan", "--receipt", str(receipt), "--output", str(plan_path)], odd),
            "unexpected HEAD status 500",
        )

        # 6-13. receipt validation teeth
        expect_raises(
            "receipt_rejects_wrong_count",
            lambda: mirror.load_receipt(write_receipt(root / "a", files[:-1])),
            "fileCount is not",
        )
        bad_traversal = [dict(f) for f in files]
        bad_traversal[0] = {"path": "build/raft/kuiklybase/datetime/../evil.bin", "sha256": files[0]["sha256"]}
        expect_raises(
            "receipt_rejects_traversal",
            lambda: mirror.load_receipt(write_receipt(root / "b", bad_traversal)),
            "unsafe receipt path",
        )
        bad_absolute = [dict(f) for f in files]
        bad_absolute[0] = {"path": "/" + files[0]["path"], "sha256": files[0]["sha256"]}
        expect_raises(
            "receipt_rejects_absolute",
            lambda: mirror.load_receipt(write_receipt(root / "c", bad_absolute)),
            "unsafe receipt path",
        )
        dup = [dict(f) for f in files]
        dup[1] = dict(dup[0])
        expect_raises(
            "receipt_rejects_duplicate",
            lambda: mirror.load_receipt(write_receipt(root / "d", dup)),
            "duplicate receipt path",
        )
        bad_prefix = [dict(f) for f in files]
        bad_prefix[0] = {
            "path": f"org/jetbrains/kotlinx/atomicfu/0.23.2-raft.1/atomicfu-0.23.2-raft.1.module",
            "sha256": files[0]["sha256"],
        }
        expect_raises(
            "receipt_rejects_foreign_lane",
            lambda: mirror.load_receipt(write_receipt(root / "e", bad_prefix)),
            "outside the datetime lane",
        )
        bad_version = [dict(f) for f in files]
        bad_version[0] = {
            "path": files[0]["path"].replace(f"/{VERSION}/", "/0.1.0-raft.9/"),
            "sha256": files[0]["sha256"],
        }
        expect_raises(
            "receipt_rejects_wrong_version",
            lambda: mirror.load_receipt(write_receipt(root / "f", bad_version)),
            "not the frozen authority version",
        )
        bad_sha = [dict(f) for f in files]
        bad_sha[0] = {"path": files[0]["path"], "sha256": "z" * 64}
        expect_raises(
            "receipt_rejects_bad_sha",
            lambda: mirror.load_receipt(write_receipt(root / "g", bad_sha)),
            "invalid receipt sha256",
        )
        expect_raises(
            "receipt_rejects_incomplete_status",
            lambda: mirror.load_receipt(write_receipt(root / "h", files, status="partial")),
            "not complete",
        )
        expect_raises(
            "receipt_rejects_wrong_source_exact",
            lambda: mirror.load_receipt(
                write_receipt(
                    root / "i", files,
                    provenance={"manifestSourceExact": "0" * 40},
                )
            ),
            "frozen publication exact",
        )

        # 14. publish refuses a noop plan
        run_command(["plan", "--receipt", str(receipt), "--output", str(plan_path)], full)
        expect_raises(
            "publish_refuses_noop_plan",
            lambda: run_direct(
                ["publish", "--receipt", str(receipt), "--bytes-dir", str(bytes_dir), "--plan", str(plan_path)],
                full,
            ),
            "not publish",
        )

        # 15. publish refuses staged bytes that do not match the receipt
        tampered_root = root / "tampered"
        tampered_bytes = stage_bytes(tampered_root, files)
        victim = tampered_bytes / files[3]["path"]
        victim.write_bytes(b"tampered")
        empty = FakeClient()
        run_command(["plan", "--receipt", str(receipt), "--output", str(plan_path)], empty)
        expect_raises(
            "publish_refuses_tampered_staging",
            lambda: run_direct(
                ["publish", "--receipt", str(receipt), "--bytes-dir", str(tampered_bytes), "--plan", str(plan_path)],
                empty,
            ),
            "do not match the verified receipt",
        )

        # 16. publish aborts on PUT failure and does not silently continue
        failing = FakeClient()
        failing.put_fail_status[files[10]["path"]] = 500
        expect_raises(
            "publish_aborts_on_put_failure",
            lambda: run_direct(
                ["publish", "--receipt", str(receipt), "--bytes-dir", str(bytes_dir), "--plan", str(plan_path)],
                failing,
            ),
            "PUT failed with HTTP 500",
        )
        check(
            "publish_abort_stopped_at_failure",
            len([c for c in failing.calls if c[0] == "PUT"]) == 11,
        )

        # 17. end-to-end: empty store -> plan -> publish -> verify
        e2e = FakeClient()
        run_command(["plan", "--receipt", str(receipt), "--output", str(plan_path)], e2e)
        code = run_command(
            ["publish", "--receipt", str(receipt), "--bytes-dir", str(bytes_dir), "--plan", str(plan_path)],
            e2e,
        )
        check("e2e_publish_34", code == 0 and len(e2e.store) == TOTAL)
        puts = [c for c in e2e.calls if c[0] == "PUT"]
        check("e2e_publish_exact_paths", sorted(p for _, p in puts) == sorted(f["path"] for f in files))
        mirror_out = root / "mirror-receipt.json"
        code = run_command(["verify", "--receipt", str(receipt), "--output", str(mirror_out)], e2e)
        check("e2e_verify_34", code == 0)

        # 18. mirror receipt carries no secrets and binds run identity
        os.environ["RAFT_ARTIFACTS_PUBLISH_TOKEN"] = "secret-token-probe"
        os.environ["GITHUB_RUN_ID"] = "12345"
        code = run_command(["verify", "--receipt", str(receipt), "--output", str(mirror_out)], e2e)
        blob = mirror_out.read_text()
        check("mirror_receipt_has_no_token", "secret-token-probe" not in blob and "Authorization" not in blob)
        check("mirror_receipt_binds_run", '"runId": "12345"' in blob)
        del os.environ["RAFT_ARTIFACTS_PUBLISH_TOKEN"]
        del os.environ["GITHUB_RUN_ID"]

        # 19. verify catches a corrupted remote byte
        e2e.store[files[0]["path"]] = b"corrupted"
        expect_raises(
            "verify_fails_on_corrupted_remote",
            lambda: run_direct(["verify", "--receipt", str(receipt)], e2e),
            "digest mismatch",
        )

        # 20. verify catches a missing remote file
        del e2e.store[files[1]["path"]]
        e2e.store[files[0]["path"]] = staged_bytes(files[0])
        expect_raises(
            "verify_fails_on_missing_remote",
            lambda: run_direct(["verify", "--receipt", str(receipt)], e2e),
            "GET HTTP 404",
        )

        # 21. a second plan over the completed store is a no-op, and publish
        #     refuses to run against it (immutability)
        e2e.store[files[1]["path"]] = staged_bytes(files[1])
        code = run_command(["plan", "--receipt", str(receipt), "--output", str(plan_path)], e2e)
        plan = json.loads(plan_path.read_text())
        check("second_run_noop_complete", code == 0 and plan["decision"] == "noop-complete")
        expect_raises(
            "second_run_publish_refused",
            lambda: run_direct(
                ["publish", "--receipt", str(receipt), "--bytes-dir", str(bytes_dir), "--plan", str(plan_path)],
                e2e,
            ),
            "not publish",
        )

        # 22. transport guards
        expect_raises(
            "transport_rejects_other_origin",
            lambda: mirror.RepositoryClient("https://evil.example.com", "u", "p"),
            "reviewed endpoint",
        )
        expect_raises(
            "transport_rejects_http",
            lambda: mirror.RepositoryClient("http://maven.artifacts.botiverse.dev", "u", "p"),
            "HTTPS",
        )
        expect_raises(
            "transport_rejects_credentialed_url",
            lambda: mirror.RepositoryClient("https://u:p@maven.artifacts.botiverse.dev", "u", "p"),
            "credentials",
        )
        expect_raises(
            "transport_rejects_empty_credentials",
            lambda: mirror.RepositoryClient(mirror.RAFT_ARTIFACTS_BASE_URL, "", ""),
            "credentials are missing",
        )
        client_real = mirror.RepositoryClient(mirror.RAFT_ARTIFACTS_BASE_URL, "u", "p")
        expect_raises("url_rejects_dotdot", lambda: client_real._url("build/../x"), "unsafe repository path")
        expect_raises("url_rejects_absolute", lambda: client_real._url("/build/x"), "unsafe repository path")
        expect_raises("method_rejected", lambda: client_real.request("x", "DELETE"), "unsupported repository method")

        # 23. redirect rejected before another request
        handler = mirror.RejectRedirectHandler()
        fake_request = types.SimpleNamespace(full_url="https://maven.artifacts.botiverse.dev/a")
        expect_raises(
            "redirect_rejected",
            lambda: handler.redirect_request(fake_request, None, 302, "Found", {}, "https://elsewhere.example/b"),
            "redirect rejected",
        )

    print(f"\n{PASS} teeth green, {FAIL} red")
    if FAILURES:
        print("red teeth:", ", ".join(FAILURES))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
