#!/usr/bin/env python3
"""Offline teeth for raft-publish.py (Raft-only lane, task #106).

Every gate is driven against a fake transport and fixture staging dirs: no
network, no credentials, no Gradle. A gate that no test can turn red is not a
gate. Run: python3 DatetimeKMM/scripts/test-raft-publish.py
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import re
import sys
import tempfile
import types
import urllib.error
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("raft_publish", HERE / "raft-publish.py")
pub = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pub)

VERSION = "0.2.0-raft.0"
SOURCE_SHA = "a" * 40
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
    except pub.PublishError as error:
        check(name, needle in str(error), f"(message was: {error})")
        return
    except Exception as error:  # noqa: BLE001 - teeth must see every escape path
        check(name, False, f"(escaped as {type(error).__name__}: {error})")
        return
    check(name, False, "(no error raised)")


class FakeClient:
    """In-memory stand-in for RepositoryClient; models the server's
    create-if-absent (PUT to an occupied key answers 409, bytes preserved)."""

    def __init__(self) -> None:
        self.store: dict[str, bytes] = {}
        self.calls: list[tuple[str, str]] = []
        self.put_fail_status: dict[str, int] = {}
        self.listing_extra: list[str] = []
        self.listing_drop: set[str] = set()

    def request(self, relative: str, method: str, body: bytes | None = None):
        self.calls.append((method, relative))
        if method == "HEAD":
            return (200, b"") if relative in self.store else (404, b"")
        if method == "GET":
            if relative not in self.store:
                return 404, b""
            return 200, self.store[relative]
        if method == "PUT":
            if relative in self.put_fail_status:
                return self.put_fail_status[relative], b""
            assert body is not None
            if relative in self.store:
                return 409, b""
            self.store[relative] = body
            return 201, b""
        raise AssertionError(f"unexpected method {method}")

    def listing(self) -> list[dict]:
        items = [{"key": k} for k in sorted(self.store) if k not in self.listing_drop]
        items.extend({"key": k} for k in self.listing_extra)
        return items


# File set for one small publication (root-metadata kind shape).
LANE = "build/raft/kuiklybase/datetime"


def expected_paths() -> list[str]:
    base = f"{LANE}/datetime/{VERSION}"
    p = f"datetime-{VERSION}"
    return [
        f"{base}/{p}.jar",
        f"{base}/{p}.pom",
        f"{base}/{p}.module",
        f"{base}/{p}-sources.jar",
        f"{base}/{p}-kotlin-tooling-metadata.json",
    ]


def pom_text(sha: str, version: str = VERSION) -> str:
    return (
        "<project>"
        "<groupId>build.raft.kuiklybase</groupId>"
        "<artifactId>datetime</artifactId>"
        f"<version>{version}</version>"
        "<properties>"
        f"<dev.raft.sourceSha>{sha}</dev.raft.sourceSha>"
        "</properties><scm>"
        f"<tag>{sha}</tag>"
        "</scm></project>"
    )


def content_for(rel: str) -> bytes:
    return (pom_text(SOURCE_SHA) if rel.endswith(".pom") else f"bytes-of-{rel}").encode()


def make_staging(root: Path, paths: list[str], sha: str = SOURCE_SHA) -> Path:
    staging = root / "staging"
    for rel in paths:
        dest = staging / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        if rel.endswith(".pom"):
            dest.write_text(pom_text(sha), encoding="utf-8")
        else:
            dest.write_bytes(f"bytes-of-{rel}".encode())
    return staging


def write_expect(root: Path, paths: list[str]) -> Path:
    f = root / "expect.txt"
    f.write_text("\n".join(paths) + "\n", encoding="utf-8")
    return f


FAKE_TOKEN_RECEIPT = {
    "hashPrefix": "f" * 16,
    "fullHashMatchedLocally": True,
    "principal": "agents/test",
    "grants": [],
    "expiresAt": 1,
    "revokedAtAbsent": True,
}


def run_cmd(argv: list[str], client: FakeClient, env: dict[str, str] | None = None) -> int:
    original_client = pub.client_from_env
    original_listing = pub.list_primaries_from_env
    original_token = pub.fetch_token_self_receipt
    saved_env: dict[str, str] = {}
    env = env or {}
    pub.client_from_env = lambda: client
    pub.list_primaries_from_env = client.listing
    pub.fetch_token_self_receipt = lambda *a, **k: dict(FAKE_TOKEN_RECEIPT)
    for key, value in env.items():
        saved_env[key] = os.environ.get(key, "")
        os.environ[key] = value
    try:
        return pub.main(argv)
    finally:
        pub.client_from_env = original_client
        pub.list_primaries_from_env = original_listing
        pub.fetch_token_self_receipt = original_token
        for key, value in saved_env.items():
            if value == "":
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def run_direct(argv: list[str], client: FakeClient, env: dict[str, str] | None = None) -> int:
    original_client = pub.client_from_env
    original_listing = pub.list_primaries_from_env
    original_token = pub.fetch_token_self_receipt
    saved_env: dict[str, str] = {}
    env = env or {}
    pub.client_from_env = lambda: client
    pub.list_primaries_from_env = client.listing
    pub.fetch_token_self_receipt = lambda *a, **k: dict(FAKE_TOKEN_RECEIPT)
    for key, value in env.items():
        saved_env[key] = os.environ.get(key, "")
        os.environ[key] = value
    try:
        args = pub.build_parser().parse_args(argv)
        return args.func(args)
    finally:
        pub.client_from_env = original_client
        pub.list_primaries_from_env = original_listing
        pub.fetch_token_self_receipt = original_token
        for key, value in saved_env.items():
            if value == "":
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def main() -> int:
    env = {"DATETIME_SOURCE_SHA": SOURCE_SHA}
    paths = expected_paths()

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        staging = make_staging(root, paths)
        expect = write_expect(root, paths)
        manifest_path = root / "manifest.json"
        plan_path = root / "plan.json"

        # 1. manifest green path
        client = FakeClient()
        code = run_cmd(["manifest", "--staging", str(staging), "--expect", str(expect),
                        "--version", VERSION, "--output", str(manifest_path)], client, env)
        manifest = json.loads(manifest_path.read_text())
        check("manifest_green", code == 0 and manifest["fileCount"] == 5)
        check("manifest_binds_source", manifest["sourceSha"] == SOURCE_SHA)

        # 2. manifest contamination / shape teeth
        dirty = make_staging(root / "c1", paths)
        (dirty / LANE / "datetime" / VERSION / "maven-metadata.xml").write_text("<metadata/>", encoding="utf-8")
        expect_raises(
            "manifest_rejects_metadata_contamination",
            lambda: run_direct(["manifest", "--staging", str(dirty), "--expect", str(expect),
                                "--version", VERSION, "--output", str(root / "m1.json")], FakeClient(), env),
            "outside the expected publication set",
        )
        # ...but Gradle's legitimate local aux IS allowed, never uploaded:
        # artifact-level maven-metadata.xml (+ companions) and primary sidecars.
        auxok = make_staging(root / "c1b", paths)
        artifact_dir = auxok / LANE / "datetime"
        (artifact_dir / "maven-metadata.xml").write_text("<metadata/>", encoding="utf-8")
        (artifact_dir / "maven-metadata.xml.sha256").write_text("x" * 64, encoding="utf-8")
        primary = paths[0]
        (auxok / (primary + ".sha1")).write_text("y" * 40, encoding="utf-8")
        (auxok / (primary + ".md5")).write_text("z" * 32, encoding="utf-8")
        code = run_cmd(["manifest", "--staging", str(auxok), "--expect", str(expect),
                        "--version", VERSION, "--output", str(root / "m1b.json")], FakeClient(), env)
        check("manifest_allows_local_aux", code == 0)
        # an aux-shaped file whose primary does not exist is contamination
        orphana = make_staging(root / "c1c", paths)
        (orphana / (paths[0] + ".sha512".replace(".sha512", "-ghost.jar.sha1"))).write_text("q" * 40, encoding="utf-8")
        expect_raises(
            "manifest_rejects_orphan_sidecar",
            lambda: run_direct(["manifest", "--staging", str(orphana), "--expect", str(expect),
                                "--version", VERSION, "--output", str(root / "m1c.json")], FakeClient(), env),
            "outside the expected publication set",
        )
        short = expected_paths()[:-1]
        expect_raises(
            "manifest_rejects_missing_file",
            lambda: run_direct(["manifest", "--staging", str(make_staging(root / "c2", short)),
                                "--expect", str(expect), "--version", VERSION,
                                "--output", str(root / "m2.json")], FakeClient(), env),
            "were not staged",
        )
        wrong_sha = make_staging(root / "c3", paths, sha="b" * 40)
        expect_raises(
            "manifest_rejects_pom_source_mismatch",
            lambda: run_direct(["manifest", "--staging", str(wrong_sha), "--expect", str(expect),
                                "--version", VERSION, "--output", str(root / "m3.json")], FakeClient(), env),
            "lacks the dispatch sourceSha",
        )
        expect_raises(
            "manifest_requires_dispatch_sha_env",
            lambda: run_direct(["manifest", "--staging", str(staging), "--expect", str(expect),
                                "--version", VERSION, "--output", str(root / "m4.json")], FakeClient(),
                               {"DATETIME_SOURCE_SHA": "not-a-sha"}),
            "40-hex",
        )
        bad_expect = root / "bad-expect.txt"
        bad_expect.write_text(paths[0] + "\n../evil\n", encoding="utf-8")
        expect_raises(
            "expect_rejects_traversal",
            lambda: run_direct(["manifest", "--staging", str(staging), "--expect", str(bad_expect),
                                "--version", VERSION, "--output", str(root / "m5.json")], FakeClient(), env),
            "unsafe expected path",
        )
        foreign_expect = root / "foreign-expect.txt"
        foreign_expect.write_text(
            "\n".join(paths + ["org/jetbrains/kotlinx/atomicfu/0.23.2-raft.1/atomicfu-0.23.2-raft.1.module"]) + "\n",
            encoding="utf-8",
        )
        expect_raises(
            "expect_rejects_foreign_lane",
            lambda: run_direct(["manifest", "--staging", str(staging), "--expect", str(foreign_expect),
                                "--version", VERSION, "--output", str(root / "m6.json")], FakeClient(), env),
            "outside the datetime lane",
        )

        # 3. classify: all-absent -> publish covering exactly the manifest
        code = run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], client, env)
        plan = json.loads(plan_path.read_text())
        check("classify_all_absent_publish", code == 0 and plan["decision"] == "publish")
        check("classify_covers_manifest", sorted(plan["missing"]) == sorted(paths))

        # 4. classify: all-present identical -> noop-verified
        full = FakeClient()
        for rel in paths:
            full.store[rel] = content_for(rel)
        code = run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], full, env)
        plan = json.loads(plan_path.read_text())
        check("classify_all_present_noop_verified", code == 0 and plan["decision"] == "noop-verified")

        # 5. classify: partial state fails closed
        partial = FakeClient()
        partial.store[paths[0]] = content_for(paths[0])
        expect_raises(
            "classify_partial_fails",
            lambda: run_direct(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], partial, env),
            "partial publication state",
        )

        # 6. classify: existing path with different bytes stops the run
        foreign = FakeClient()
        foreign.store[paths[1]] = b"foreign"
        expect_raises(
            "classify_existing_wrong_bytes_fails",
            lambda: run_direct(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], foreign, env),
            "DIFFERENT bytes",
        )

        # 7. classify: unexpected owned-prefix primary is a conflict
        surprise = FakeClient()
        surprise.listing_extra.append(f"{LANE}/datetime/{VERSION}/datetime-{VERSION}-surprise.bin")
        expect_raises(
            "classify_unexpected_owned_prefix_conflict",
            lambda: run_direct(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], surprise, env),
            "unexpected carriers",
        )
        outsider = FakeClient()
        outsider.listing_extra.append(f"{LANE}/datetime/9.9.9-other/datetime-9.9.9-other.pom")
        code = run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], outsider, env)
        check("classify_ignores_outside_prefix", code == 0)

        # 8-12. bundle-model publish teeth. freeze helper: plan + bundle with
        # the digest recorded in the plan, exactly as the plan job does it.
        def freeze_into(staging_dir: Path, plan_file: Path, out_name: str) -> Path:
            bundle = root / out_name
            digest_out = root / (out_name + ".sha")
            run_cmd(["freeze", "--manifest", str(manifest_path), "--staging-roots", str(staging_dir),
                     "--output", str(bundle), "--digest-out", str(digest_out)], FakeClient(), env)
            plan = json.loads(plan_file.read_text())
            plan["bundleSha256"] = digest_out.read_text().strip()
            plan_file.write_text(json.dumps(plan), encoding="utf-8")
            return bundle

        # 8. publish refuses a noop plan
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], full, env)
        noop_bundle = freeze_into(staging, plan_path, "noop.tar")
        expect_raises(
            "publish_refuses_noop_plan",
            lambda: run_direct(["publish", "--manifest", str(manifest_path),
                                "--plan", str(plan_path), "--bundle", str(noop_bundle),
                                "--token-receipt-out", str(root / "tr1.json")], full, env),
            "not publish",
        )

        # 9. bundle digest must match the plan (not the same bundle -> red, 0 PUT)
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], client, env)
        good_bundle = freeze_into(staging, plan_path, "good.tar")
        wrong_plan = root / "wrong-plan.json"
        wrong_plan.write_text(json.dumps({**json.loads(plan_path.read_text()), "bundleSha256": "0" * 64}), encoding="utf-8")
        digest_fail = FakeClient()
        expect_raises(
            "publish_rejects_wrong_bundle_digest",
            lambda: run_direct(["publish", "--manifest", str(manifest_path),
                                "--plan", str(wrong_plan), "--bundle", str(good_bundle),
                                "--token-receipt-out", str(root / "tr2.json")], digest_fail, env),
            "not the same bundle",
        )
        check("wrong_digest_zero_puts", not [c for c in digest_fail.calls if c[0] == "PUT"])

        # 9b. bundle member tampered after freeze (digest re-recorded to match
        # the tampered bundle) -> member-vs-manifest check catches it, 0 PUT
        import tarfile as _tf
        evil_bundle = root / "evil.tar"
        with _tf.open(good_bundle) as src_tar, _tf.open(evil_bundle, "w") as dst_tar:
            for m in src_tar.getmembers():
                body = src_tar.extractfile(m).read()
                if m.name == paths[1]:
                    # same length, different bytes: must trip the SHA guard,
                    # not the size guard
                    body = bytes(b ^ 0xFF for b in body)
                info = _tf.TarInfo(m.name)
                info.size = len(body)
                dst_tar.addfile(info, __import__("io").BytesIO(body))
        evil_plan = root / "evil-plan.json"
        evil_plan.write_text(json.dumps({**json.loads(plan_path.read_text()),
                                          "bundleSha256": hashlib.sha256(evil_bundle.read_bytes()).hexdigest()}), encoding="utf-8")
        evil_client = FakeClient()
        expect_raises(
            "publish_rejects_tampered_bundle_member",
            lambda: run_direct(["publish", "--manifest", str(manifest_path),
                                "--plan", str(evil_plan), "--bundle", str(evil_bundle),
                                "--token-receipt-out", str(root / "tr3.json")], evil_client, env),
            "do not match the manifest",
        )
        check("tampered_bundle_zero_puts", not [c for c in evil_client.calls if c[0] == "PUT"])

        # 9c. remote no longer absent at the barrier -> red, 0 PUT
        occupied = FakeClient()
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], occupied, env)
        barrier_bundle = freeze_into(staging, plan_path, "barrier.tar")
        occupied.store[paths[2]] = b"occupied-by-someone-else"
        expect_raises(
            "publish_fails_when_remote_occupied_at_barrier",
            lambda: run_direct(["publish", "--manifest", str(manifest_path),
                                "--plan", str(plan_path), "--bundle", str(barrier_bundle),
                                "--token-receipt-out", str(root / "tr4.json")], occupied, env),
            "no longer absent",
        )
        check("occupied_barrier_zero_puts", not [c for c in occupied.calls if c[0] == "PUT"])
        check("occupied_bytes_preserved", occupied.store[paths[2]] == b"occupied-by-someone-else")

        # 10. 409 race still fail-closed even after the barrier (belt and
        # suspenders: the re-probe passed, then a foreign write wins the PUT race)
        class LateOccupier(FakeClient):
            def request(self, relative, method, body=None):
                if method == "PUT" and relative == paths[0]:
                    self.store[relative] = b"race-winner"
                    return 409, b""
                return super().request(relative, method, body)

        racer = LateOccupier()
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], racer, env)
        race_bundle = freeze_into(staging, plan_path, "race.tar")
        expect_raises(
            "publish_never_overwrites_post_plan_foreign_bytes",
            lambda: run_direct(["publish", "--manifest", str(manifest_path),
                                "--plan", str(plan_path), "--bundle", str(race_bundle),
                                "--token-receipt-out", str(root / "tr5.json")], racer, env),
            "foreign write, stopping",
        )
        check("race_foreign_bytes_preserved", racer.store[paths[0]] == b"race-winner")

        # 11. PUT failure aborts
        failing = FakeClient()
        failing.put_fail_status[paths[1]] = 500
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], failing, env)
        fail_bundle = freeze_into(staging, plan_path, "fail.tar")
        expect_raises(
            "publish_aborts_on_put_failure",
            lambda: run_direct(["publish", "--manifest", str(manifest_path),
                                "--plan", str(plan_path), "--bundle", str(fail_bundle),
                                "--token-receipt-out", str(root / "tr6.json")], failing, env),
            "PUT failed with HTTP 500",
        )

        # 12. e2e: empty -> classify -> freeze -> publish -> verify + receipt;
        # THE precision tooth: staging files tampered AFTER freeze do not
        # change what the remote receives (frozen in-memory bytes only).
        e2e = FakeClient()
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], e2e, env)
        e2e_bundle = freeze_into(staging, plan_path, "e2e.tar")
        for rel in paths:
            (staging / rel).write_bytes(b"tampered-after-freeze")
        code = run_cmd(["publish", "--manifest", str(manifest_path),
                        "--plan", str(plan_path), "--bundle", str(e2e_bundle),
                        "--token-receipt-out", str(root / "tr7.json")], e2e, env)
        check("e2e_publish_all", code == 0 and len(e2e.store) == len(paths))
        check(
            "publish_sends_frozen_bytes_despite_later_staging_tamper",
            e2e.store[paths[0]] == content_for(paths[0]),
        )
        # restore for the verify pass
        for rel in paths:
            (staging / rel).write_bytes(content_for(rel))
        os.environ["GITHUB_RUN_ID"] = "777"
        receipt_path = root / "receipt.json"
        code = run_cmd(["verify", "--manifest", str(manifest_path), "--output", str(receipt_path)], e2e, env)
        check("e2e_verify_all", code == 0)
        blob = receipt_path.read_text()
        receipt = json.loads(blob)
        check("receipt_cross_binds", receipt["sourceSha"] == SOURCE_SHA and receipt["version"] == VERSION)
        check("receipt_binds_run", receipt["provenance"]["runId"] == "777")
        check("receipt_has_no_secret", "Authorization" not in blob and "raft-ci:" not in blob)
        del os.environ["GITHUB_RUN_ID"]

        # 12b. freeze packs ONLY primaries: local aux never enters the bundle.
        aux_bundle = root / "aux.tar"
        aux_digest = root / "aux.sha"
        run_cmd(["freeze", "--manifest", str(manifest_path), "--staging-roots", str(auxok),
                 "--output", str(aux_bundle), "--digest-out", str(aux_digest)], FakeClient(), env)
        import tarfile as _tf2
        with _tf2.open(aux_bundle) as t:
            names = sorted(m.name for m in t.getmembers() if m.isfile())
        check("freeze_packs_only_primaries", names == sorted(paths))

        # 12c. audit-staging (preflight pattern gate): primaries+aux green,
        # third-kind file red, empty red.
        code = run_cmd(["audit-staging", "--staging", str(auxok)], FakeClient(), env)
        check("audit_staging_green_with_aux", code == 0)
        third = make_staging(root / "c1d", paths)
        (third / "unrelated.txt").write_text("nope", encoding="utf-8")
        expect_raises(
            "audit_staging_rejects_third_kind",
            lambda: run_direct(["audit-staging", "--staging", str(third)], FakeClient(), env),
            "neither lane primaries nor local aux",
        )
        empty_dir = root / "c1e"
        empty_dir.mkdir()
        expect_raises(
            "audit_staging_rejects_empty",
            lambda: run_direct(["audit-staging", "--staging", str(empty_dir)], FakeClient(), env),
            "empty",
        )

        # 13. verified no-op second run: zero PUTs, verify still green
        second = FakeClient()
        for rel in paths:
            second.store[rel] = content_for(rel)
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], second, env)
        plan = json.loads(plan_path.read_text())
        check("second_run_noop_verified", plan["decision"] == "noop-verified")
        calls_before = len(second.calls)
        code = run_cmd(["verify", "--manifest", str(manifest_path)], second, env)
        check(
            "second_run_zero_put_green_verify",
            code == 0 and not any(c[0] == "PUT" for c in second.calls[calls_before:]),
        )

        # 14. verify catches corruption / deletion / listing skew
        e2e.store[paths[0]] = b"corrupted"
        expect_raises(
            "verify_fails_on_corrupted_remote",
            lambda: run_direct(["verify", "--manifest", str(manifest_path)], e2e, env),
            "digest mismatch",
        )
        e2e.store[paths[0]] = content_for(paths[0])
        del e2e.store[paths[1]]
        expect_raises(
            "verify_fails_on_missing_remote",
            lambda: run_direct(["verify", "--manifest", str(manifest_path)], e2e, env),
            "GET HTTP 404",
        )
        e2e.store[paths[1]] = content_for(paths[1])
        e2e.listing_extra.append(f"{LANE}/datetime/{VERSION}/datetime-{VERSION}-stowaway.jar")
        expect_raises(
            "verify_fails_on_extra_listing_primary",
            lambda: run_direct(["verify", "--manifest", str(manifest_path)], e2e, env),
            "unexpected carriers present",
        )
        e2e.listing_extra.clear()
        e2e.listing_drop.add(paths[2])
        expect_raises(
            "verify_fails_on_listing_missing_primary",
            lambda: run_direct(["verify", "--manifest", str(manifest_path)], e2e, env),
            "missing from listing",
        )

        # 15. transport guards
        expect_raises(
            "transport_rejects_other_origin",
            lambda: pub.RepositoryClient("https://evil.example.com", "u", "p"),
            "reviewed endpoint",
        )
        expect_raises(
            "transport_rejects_http",
            lambda: pub.RepositoryClient("http://maven.artifacts.botiverse.dev", "u", "p"),
            "HTTPS",
        )
        expect_raises(
            "transport_rejects_empty_credentials",
            lambda: pub.RepositoryClient(pub.RAFT_ARTIFACTS_BASE_URL, "", ""),
            "credentials are missing",
        )
        real = pub.RepositoryClient(pub.RAFT_ARTIFACTS_BASE_URL, "u", "p")
        expect_raises("url_rejects_dotdot", lambda: real._url("build/../x"), "unsafe repository path")
        expect_raises("method_rejected", lambda: real.request("x", "DELETE"), "unsupported repository method")
        handler = pub.RejectRedirectHandler()
        fake_request = types.SimpleNamespace(full_url="https://maven.artifacts.botiverse.dev/a")
        expect_raises(
            "redirect_rejected",
            lambda: handler.redirect_request(fake_request, None, 302, "Found", {}, "https://elsewhere.example/b"),
            "redirect rejected",
        )

        # 16. request headers: stable UA on every call; Authorization only on
        # the pinned Maven origin (never the control plane).
        class CapResponse:
            def __init__(self, status: int, body: bytes) -> None:
                self.status = status
                self._body = body

            def read(self) -> bytes:
                return self._body

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return None

        class CapOpener:
            def __init__(self, status: int = 404, body: bytes = b"{}") -> None:
                self.status = status
                self.body = body
                self.seen: list[tuple[str, str, dict[str, str]]] = []

            def open(self, request, timeout=0):
                self.seen.append((request.get_method(), request.full_url, {k.lower(): v for k, v in request.header_items()}))
                return CapResponse(self.status, self.body)

        maven_opener = CapOpener()
        wired = pub.RepositoryClient(pub.RAFT_ARTIFACTS_BASE_URL, "raft-ci", "probe", opener=maven_opener)
        for method in ("HEAD", "GET", "PUT"):
            wired.request(f"{LANE}/x", method, b"x" if method == "PUT" else None)
        check(
            "all_methods_carry_stable_ua",
            all(h.get("user-agent") == pub.PUBLISH_USER_AGENT for _, _, h in maven_opener.seen),
        )
        check(
            "authorization_only_to_pinned_origin",
            all(
                h.get("authorization", "").startswith("Basic ") and url.startswith(pub.RAFT_ARTIFACTS_BASE_URL + "/")
                for _, url, h in maven_opener.seen
            ),
        )
        cp_opener = CapOpener(200, json.dumps({"artifacts": []}).encode())
        pub.list_scope_primaries(cp_opener)
        _, cp_url, cp_headers = cp_opener.seen[0]
        check(
            "control_plane_has_ua_no_credentials",
            cp_headers.get("user-agent") == pub.PUBLISH_USER_AGENT
            and "authorization" not in cp_headers
            and cp_url.startswith(pub.CONTROL_PLANE_BASE_URL),
        )

        # 16b. B2 hardening coverage note: late-digest / added-file / occupied-
        # remote zero-PUT cases are now asserted by the bundle barrier teeth
        # above (wrong_digest_zero_puts, tampered_bundle_zero_puts,
        # occupied_barrier_zero_puts, race + the frozen-bytes precision tooth).
        # publish never re-reads mutable paths, so a post-freeze staging
        # mutation cannot influence the upload set by construction.

        # 16c. merge teeth: green union, version/source disagreement red,
        # path collision red.
        shard_a = root / "shardA.json"
        shard_b = root / "shardB.json"
        m_a = json.loads(manifest_path.read_text())
        m_b = json.loads(manifest_path.read_text())
        other_base = f"{LANE}/datetime-android/{VERSION}"
        m_b["files"] = [
            {"path": f"{other_base}/datetime-android-{VERSION}.{ext}", "sha256": hashlib.sha256(ext.encode()).hexdigest(), "size": 3}
            for ext in ("aar", "pom", "module", "sources.jar")
        ]
        m_b["fileCount"] = len(m_b["files"])
        shard_a.write_text(json.dumps(m_a), encoding="utf-8")
        shard_b.write_text(json.dumps(m_b), encoding="utf-8")
        merged_out = root / "merged.json"
        code = run_cmd(["merge", "--manifests", str(shard_a), str(shard_b), "--output", str(merged_out)], FakeClient(), env)
        merged = json.loads(merged_out.read_text())
        check("merge_green_union", code == 0 and merged["fileCount"] == 9)
        m_bad = json.loads(manifest_path.read_text())
        m_bad["version"] = "0.0.0-drift"
        bad_shard = root / "shardBad.json"
        bad_shard.write_text(json.dumps(m_bad), encoding="utf-8")
        expect_raises(
            "merge_rejects_version_drift",
            lambda: run_direct(["merge", "--manifests", str(shard_a), str(bad_shard), "--output", str(root / "mx.json")], FakeClient(), env),
            "disagree on version",
        )
        expect_raises(
            "merge_rejects_path_collision",
            lambda: run_direct(["merge", "--manifests", str(shard_a), str(shard_a), "--output", str(root / "my.json")], FakeClient(), env),
            "owned by two shard manifests",
        )

        # 16d. GAV/version binding teeth (review B3): a mismatched --version,
        # a POM without GAV, or a POM whose version disagrees with its
        # directory all turn the manifest red.
        expect_raises(
            "manifest_rejects_wrong_version_param",
            lambda: run_direct(["manifest", "--staging", str(staging), "--expect", str(expect),
                                "--version", "9.9.9-wrong", "--output", str(root / "mv.json")], FakeClient(), env),
            "not the release version",
        )
        nogav = make_staging(root / "nogav", paths)
        for pom_path in nogav.rglob("*.pom"):
            pom_path.write_text(pom_text(SOURCE_SHA).replace("<groupId>build.raft.kuiklybase</groupId>", ""), encoding="utf-8")
        expect_raises(
            "manifest_rejects_pom_without_groupid",
            lambda: run_direct(["manifest", "--staging", str(nogav), "--expect", str(expect),
                                "--version", VERSION, "--output", str(root / "mw.json")], FakeClient(), env),
            "lacks its own groupId",
        )
        driftv = make_staging(root / "driftv", paths)
        for pom_path in driftv.rglob("*.pom"):
            pom_path.write_text(pom_text(SOURCE_SHA, version="0.0.0-drift"), encoding="utf-8")
        drifta = make_staging(root / "drifta", paths)
        for pom_path in drifta.rglob("*.pom"):
            pom_path.write_text(pom_text(SOURCE_SHA).replace("<artifactId>datetime</artifactId>", "<artifactId>wrong-artifact</artifactId>"), encoding="utf-8")
        evilg = make_staging(root / "evilg", paths)
        for pom_path in evilg.rglob("*.pom"):
            pom_path.write_text(pom_text(SOURCE_SHA).replace("<groupId>build.raft.kuiklybase</groupId>", "<groupId>evil.example</groupId>"), encoding="utf-8")
        expect_raises(
            "manifest_rejects_pom_groupid_drift",
            lambda: run_direct(["manifest", "--staging", str(evilg), "--expect", str(expect),
                                "--version", VERSION, "--output", str(root / "mg.json")], FakeClient(), env),
            "groupId is not the lane group",
        )
        expect_raises(
            "manifest_rejects_pom_artifactid_drift",
            lambda: run_direct(["manifest", "--staging", str(drifta), "--expect", str(expect),
                                "--version", VERSION, "--output", str(root / "ma.json")], FakeClient(), env),
            "artifactId does not match its path",
        )
        expect_raises(
            "manifest_rejects_pom_version_drift",
            lambda: run_direct(["manifest", "--staging", str(driftv), "--expect", str(expect),
                                "--version", VERSION, "--output", str(root / "mz.json")], FakeClient(), env),
            "does not match its directory",
        )

        # 16e. revalidate barrier teeth (review B1): the release-wide
        # pre-classification rehash catches tampered, added, or missing shard
        # bytes -- this is what runs before any writer job exists.
        code = run_cmd(["revalidate", "--manifest", str(manifest_path), "--staging-roots", str(staging)], FakeClient(), env)
        check("revalidate_green", code == 0)
        tampered_root = make_staging(root / "reval-t", paths)
        (tampered_root / paths[2]).write_bytes(b"drifted")
        expect_raises(
            "revalidate_catches_drifted_bytes",
            lambda: run_direct(["revalidate", "--manifest", str(manifest_path), "--staging-roots", str(tampered_root)], FakeClient(), env),
            "changed after manifest",
        )
        added_root = make_staging(root / "reval-a", paths)
        (added_root / LANE / "datetime" / VERSION / "datetime-extra.jar").write_bytes(b"new")
        expect_raises(
            "revalidate_catches_added_primary",
            lambda: run_direct(["revalidate", "--manifest", str(manifest_path), "--staging-roots", str(added_root)], FakeClient(), env),
            "unknown files",
        )
        short_root = make_staging(root / "reval-m", paths[:-1])
        expect_raises(
            "revalidate_catches_missing_primary",
            lambda: run_direct(["revalidate", "--manifest", str(manifest_path), "--staging-roots", str(short_root)], FakeClient(), env),
            "expected exactly 1",
        )

        # 16f. aggregate release receipt teeth (drives the extracted script in
        # a throwaway git repo: offline, no backdoors).
        import subprocess as _sp
        agg_spec = importlib.util.spec_from_file_location("agg", HERE / "aggregate-release-receipt.py")
        agg = importlib.util.module_from_spec(agg_spec)
        agg_spec.loader.exec_module(agg)

        def run_agg_case(base: Path, plan_prefix="a" * 16, publish_prefix="a" * 16, terminal_prefix="a" * 16, drop_last_file: bool = False):
            """One self-contained aggregate-receipt case: temp git repo whose
            HEAD is the release exact (master == dispatch == fixture sha)."""
            repo = base / "repo"
            repo.mkdir(parents=True, exist_ok=True)
            _sp.run(["git", "init", "-q"], cwd=repo, check=True)
            (repo / "gradle.properties").write_text(f"mavenVersion={VERSION}\n", encoding="utf-8")
            _sp.run(["git", "add", "gradle.properties"], cwd=repo, check=True)
            _sp.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "x"], cwd=repo, check=True)
            sha = _sp.run(["git", "rev-parse", "HEAD"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()
            _sp.run(["git", "update-ref", "refs/remotes/origin/master", sha], cwd=repo, check=True)

            artifacts = base / "artifacts"
            (artifacts / "datetime-raft-global-plan").mkdir(parents=True)
            (artifacts / "datetime-raft-receipt-publish").mkdir(parents=True)
            files = [{"path": pth, "sha256": hashlib.sha256(pth.encode()).hexdigest(), "size": 3} for pth in paths]
            merged = {"schema": 1, "version": VERSION, "sourceSha": sha,
                      "destination": pub.RAFT_ARTIFACTS_BASE_URL, "fileCount": len(paths), "files": files}
            json.dump(merged, open(artifacts / "datetime-raft-global-plan" / "merged-manifest.json", "w"))
            json.dump({"decision": "publish", "fileCount": len(paths), "tokenHashPrefix": plan_prefix},
                      open(artifacts / "datetime-raft-global-plan" / "global-plan.json", "w"))
            receipt_files = files[:-1] if drop_last_file else files
            json.dump({"status": "complete", "version": VERSION, "sourceSha": sha,
                       "fileCount": len(receipt_files), "files": receipt_files},
                      open(artifacts / "datetime-raft-receipt-publish" / "publish-receipt.json", "w"))
            json.dump(dict(FAKE_TOKEN_RECEIPT, hashPrefix=publish_prefix),
                      open(artifacts / "datetime-raft-receipt-publish" / "publish-token-receipt.json", "w"))
            json.dump(dict(FAKE_TOKEN_RECEIPT, hashPrefix=terminal_prefix), open(base / "terminal-token.json", "w"))

            argv = ["--artifacts", str(artifacts), "--terminal-token-receipt", str(base / "terminal-token.json"),
                    "--output", str(base / "agg.json")]
            old_cwd = os.getcwd()
            old_sha = os.environ.get("GITHUB_SHA", "")
            os.environ["GITHUB_SHA"] = sha
            try:
                os.chdir(repo)
                return agg.main(argv)
            finally:
                os.chdir(old_cwd)
                if old_sha == "":
                    os.environ.pop("GITHUB_SHA", None)
                else:
                    os.environ["GITHUB_SHA"] = old_sha

        agg_ok = root / "agg-ok"
        agg_ok.mkdir()
        code = run_agg_case(agg_ok)
        check("aggregate_receipt_green", code == 0)
        agg_aba = root / "agg-aba"
        agg_aba.mkdir()
        try:
            run_agg_case(agg_aba, publish_prefix="b" * 16)
            check("aggregate_token_aba_red", False, "(no failure raised)")
        except SystemExit as e:
            check("aggregate_token_aba_red", e.code == 1)
        agg_cov = root / "agg-cov"
        agg_cov.mkdir()
        try:
            run_agg_case(agg_cov, drop_last_file=True)
            check("aggregate_coverage_red", False, "(no failure raised)")
        except SystemExit as e:
            check("aggregate_coverage_red", e.code == 1)

        # 16h. frozen-bytes causal catcher: a FakeClient that rewrites the
        # bundle file after the first PUT. Under the shipped code the remote
        # still receives the frozen in-memory bytes; a mutation that re-reads
        # from the bundle at PUT time turns this red.
        class BundleTamperClient(FakeClient):
            def __init__(self, bundle_path: Path) -> None:
                super().__init__()
                self.bundle_path = bundle_path
                self.armed = True

            def request(self, relative, method, body=None):
                if method == "PUT" and self.armed:
                    self.armed = False
                    import tarfile as _tf3, io as _io
                    with _tf3.open(self.bundle_path) as t0:
                        members = {m.name: t0.extractfile(m).read() for m in t0.getmembers() if m.isfile()}
                    victim = sorted(members)[1]
                    members[victim] = b"x" * len(members[victim])
                    with _tf3.open(self.bundle_path, "w") as t1:
                        for name, data in members.items():
                            info = _tf3.TarInfo(name)
                            info.size = len(data)
                            t1.addfile(info, _io.BytesIO(data))
                return super().request(relative, method, body)

        tamper_client = FakeClient()
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], tamper_client, env)
        tamper_bundle = freeze_into(staging, plan_path, "tamper-target.tar")
        hooked = BundleTamperClient(tamper_bundle)
        run_cmd(["publish", "--manifest", str(manifest_path),
                 "--plan", str(plan_path), "--bundle", str(tamper_bundle),
                 "--token-receipt-out", str(root / "tr8.json")], hooked, env)
        check(
            "remote_receives_frozen_bytes_even_if_bundle_tampered_mid_publish",
            all(hooked.store[rel] == content_for(rel) for rel in paths),
        )

        # 17. B4 workflow contract teeth (text-level, causal): the production
        # workflow must never regain a GitHub writer, the aggregate wiring must
        # keep its shape, and the stage jobs must never see the token.
        wf = (HERE.parent.parent / ".github" / "workflows" / "publish-datetime-raft.yml").read_text()
        check("workflow_no_packages_write", "packages: write" not in wf)
        check("workflow_no_github_writer_credential", "GITHUB_PACKAGES" not in wf)
        check("workflow_no_github_packages_host", "maven.pkg.github.com" not in wf)
        # Single-writer structure: exactly one publish job, pinned to the
        # environment, gated on the aggregate plan.
        publish_jobs = re.findall(r"\n  (publish-[a-z]+):", wf)
        check("workflow_single_writer", publish_jobs == [] and "\n  publish:" in wf)
        publish_block = wf[wf.find("\n  publish:"):wf.find("\n  receipt:")]
        check("workflow_publish_environment_pinned", "environment: raft-artifacts-production" in publish_block)
        check("workflow_publish_needs_plan", re.search(r"needs:\s*\[[^\]]*\bplan\b[^\]]*\]", publish_block) is not None)
        check("workflow_plan_before_publish", wf.find("\n  plan:") < wf.find("\n  publish:"))
        check("workflow_needs_tooth_is_causal", re.search(r"needs:\s*\[[^\]]*\bplan\b[^\]]*\]", "needs: [stage-ohos]") is None)
        stage_section = wf[wf.find("\n  stage-ohos:"):wf.find("\n  plan:")]
        check("workflow_stage_jobs_have_no_environment", "environment:" not in stage_section)
        check("workflow_admission_requires_source_exact", "REQUESTED_SOURCE_SHA" in wf and "origin/master" in wf)
        receipt_block = wf[wf.find("\n  receipt:"):]
        check("workflow_receipt_environment_pinned", "environment: raft-artifacts-production" in receipt_block)
        check("workflow_has_aggregate_receipt", "token-receipt" in wf)
        check("workflow_no_stale_guard_red_on_noop", "stale-rerun" not in wf)
        check(
            "workflow_receipt_needs_plan_and_publish",
            re.search(r"receipt:[\s\S]*?needs:\s*\[[^\]]*\bplan\b[^\]]*\bpublish\b[^\]]*\]", wf) is not None,
        )
        check(
            "workflow_fresh_master_barrier_before_publish",
            'git fetch --quiet origin master' in publish_block
            and publish_block.find("git fetch --quiet origin master") < publish_block.find("raft-publish.py publish"),
        )

        # 18. token self-receipt teeth (fake introspection opener).
        class TokenOpener:
            def __init__(self, payload: dict, status: int = 200) -> None:
                self.payload = payload
                self.status = status
                self.seen_headers: list[dict[str, str]] = []

            def open(self, request, timeout=0):
                self.seen_headers.append({k.lower(): v for k, v in request.header_items()})
                return CapResponse(self.status, json.dumps(self.payload).encode())

        raw_token = "task-token-probe"
        token_hash = hashlib.sha256(raw_token.encode()).hexdigest()
        good_record = {
            "hash": token_hash,
            "principal": "agents/cc-wow2",
            "grants": [{"scope": "build.raft.kuiklybase", "principal": "agents/cc-wow2", "permissions": ["read", "publish"]}],
            "expiresAt": 9999999999999,
        }
        os.environ["RAFT_ARTIFACTS_PUBLISH_TOKEN"] = raw_token
        os.environ["RAFT_ARTIFACTS_USERNAME"] = "raft-ci"
        try:
            def with_opener(opener):
                return pub.fetch_token_self_receipt(opener)
            r = with_opener(TokenOpener({"tokens": [good_record]}))
            check("token_receipt_green", r["hashPrefix"] == token_hash[:16] and r["fullHashMatchedLocally"] is True)
            opener_probe = TokenOpener({"tokens": [good_record]})
            with_opener(opener_probe)
            check(
                "token_introspection_sends_ua_and_basic_auth_to_api_origin_only",
                opener_probe.seen_headers[0].get("user-agent") == pub.PUBLISH_USER_AGENT
                and opener_probe.seen_headers[0].get("authorization", "").startswith("Basic "),
            )
            expect_raises(
                "token_receipt_rejects_no_match",
                lambda: with_opener(TokenOpener({"tokens": [{"hash": "0" * 64, "grants": [], "expiresAt": 1}]})),
                "exactly 1",
            )
            revoked = dict(good_record, revokedAt=123)
            expect_raises(
                "token_receipt_rejects_revoked",
                lambda: with_opener(TokenOpener({"tokens": [revoked]})),
                "already revoked",
            )
            expired = dict(good_record, expiresAt=1)
            expect_raises(
                "token_receipt_rejects_expired",
                lambda: with_opener(TokenOpener({"tokens": [expired]})),
                "already expired",
            )
            no_expiry = dict(good_record, expiresAt=0)
            expect_raises(
                "token_receipt_rejects_zero_expiry",
                lambda: with_opener(TokenOpener({"tokens": [no_expiry]})),
                "no expiry",
            )
            wrong_grants = dict(good_record, grants=[{"scope": "org.other", "principal": "agents/cc-wow2", "permissions": ["read", "publish"]}])
            expect_raises(
                "token_receipt_rejects_wrong_grants",
                lambda: with_opener(TokenOpener({"tokens": [wrong_grants]})),
                "exactly scope=build.raft.kuiklybase",
            )
            # the old substring attack: evil scope + publish elsewhere must fail
            evil_grants = dict(
                good_record,
                principal="agents/cc-wow2",
                grants=[
                    {"scope": "build.raft.kuiklybase.evil", "principal": "agents/cc-wow2", "permissions": ["delete"]},
                ],
                label="publish",
            )
            expect_raises(
                "token_receipt_rejects_substring_grant_attack",
                lambda: with_opener(TokenOpener({"tokens": [evil_grants]})),
                "exactly scope=build.raft.kuiklybase",
            )
            grantless_principal = dict(
                good_record,
                grants=[{"scope": "build.raft.kuiklybase", "permissions": ["read", "publish"]}],
            )
            expect_raises(
                "token_receipt_rejects_grant_without_principal",
                lambda: with_opener(TokenOpener({"tokens": [grantless_principal]})),
                "carries no principal",
            )
            # live-schema shape: no top-level principal key at all still passes
            live_shape = {k: v for k, v in good_record.items() if k != "principal"}
            r = with_opener(TokenOpener({"tokens": [live_shape]}))
            check("token_receipt_live_schema_shape", r["fullHashMatchedLocally"] is True)
        finally:
            os.environ.pop("RAFT_ARTIFACTS_PUBLISH_TOKEN", None)
            os.environ.pop("RAFT_ARTIFACTS_USERNAME", None)

        # 16g. token multi-match red.
        os.environ["RAFT_ARTIFACTS_PUBLISH_TOKEN"] = "task-token-probe"
        os.environ["RAFT_ARTIFACTS_USERNAME"] = "raft-ci"
        try:
            def with_opener(opener):
                return pub.fetch_token_self_receipt(opener)
            multi = TokenOpener({"tokens": [good_record, dict(good_record)]})
            expect_raises(
                "token_receipt_rejects_multi_match",
                lambda: with_opener(multi),
                "exactly 1",
            )
        finally:
            os.environ.pop("RAFT_ARTIFACTS_PUBLISH_TOKEN", None)
            os.environ.pop("RAFT_ARTIFACTS_USERNAME", None)



    print(f"\n{PASS} teeth green, {FAIL} red")
    if FAILURES:
        print("red teeth:", ", ".join(FAILURES))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
