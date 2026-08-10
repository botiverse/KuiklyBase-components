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
sys.path.insert(0, str(HERE))
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
    "principal": {"kind": "agent", "id": "cc-wow2"},
    "grants": [
        {
            "scope": "build.raft.kuiklybase",
            "principal": {"kind": "agent", "id": "cc-wow2"},
            "permissions": ["read", "publish"],
        }
    ],
    "expiresAt": 9999999999999,
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
    # The Hosted runner always exports GITHUB_SHA; offline teeth must not trip
    # the publish barrier's live master recheck unless a tooth sets it.
    sha_was = os.environ.pop("GITHUB_SHA", None)
    for key, value in env.items():
        saved_env[key] = os.environ.get(key, "")
        os.environ[key] = value
    try:
        return pub.main(argv)
    finally:
        pub.client_from_env = original_client
        pub.list_primaries_from_env = original_listing
        pub.fetch_token_self_receipt = original_token
        if sha_was is not None:
            os.environ["GITHUB_SHA"] = sha_was
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
    sha_was = os.environ.pop("GITHUB_SHA", None)
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
        if sha_was is not None:
            os.environ["GITHUB_SHA"] = sha_was
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
            "sourceSha node is not the dispatch SHA",
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

        # ------------------------------------------------------------------
        # Fake v1 atomic release ledger (mirrors the deployed semantics from
        # raft-artifacts docs/atomic-releases.md):
        # - claim recomputes the manifest digest and refuses pre-existing
        #   canonical objects below the owned prefixes and overlapping active
        #   claims or ordinary writers;
        # - staging is create-only and invisible; commit is the only
        #   visibility linearization point; abort carries an immutable reason.
        class FakeAtomicServer:
            def __init__(self) -> None:
                self.canonical: dict[str, bytes] = {}
                self.attempts: dict[str, dict] = {}
                self.next_id = 0
                self.ordinary_leases: set[str] = set()
                self.calls: list[tuple] = []

            def _conflict(self, code: str, message: str):
                from atomic_release import AtomicReleaseError
                raise AtomicReleaseError(code, message, http_status=409)

            def claim(self, payload: dict) -> dict:
                self.calls.append(("claim", payload))
                prefixes = payload["ownedPrefixes"]
                digest = payload["manifestDigest"]
                objects = payload["objects"]
                # server recomputes
                import importlib
                ar_spec = importlib.util.spec_from_file_location(
                    "ar2", HERE / "atomic_release.py")
                ar2 = importlib.util.module_from_spec(ar_spec)
                ar_spec.loader.exec_module(ar2)
                if ar2.manifest_digest(objects) != digest:
                    self._conflict("manifest-digest-mismatch", "digest mismatch")
                if ar2.owned_prefixes_from_paths([o["path"] for o in objects]) != sorted(prefixes):
                    self._conflict("prefix-mismatch", "owned prefixes do not derive from objects")
                # exact retry / identity conflict handling (docs/atomic-releases.md):
                # an exact retry of the same release identity returns the
                # ORIGINAL claim id — even after that claim committed (its own
                # objects now occupy canonical) — so this must be decided
                # BEFORE the canonical/lease/overlap contention checks below;
                # a different immutable identity for the same
                # (repository, taskId, version) permanently conflicts.
                identity_keys = ("repository", "taskId", "version")
                for existing_id, attempt in self.attempts.items():
                    if all(attempt["payload"].get(k) == payload.get(k) for k in identity_keys):
                        if attempt["payload"] == payload:
                            return {"claimId": existing_id, "generation": attempt["generation"], "idempotent": True}
                        self._conflict("release-identity-conflict", "different identity for same release coordinate")
                for prefix in prefixes:
                    for key in self.canonical:
                        if key.startswith(prefix):
                            self._conflict("canonical-exists", f"canonical object exists below {prefix}")
                    for lease in self.ordinary_leases:
                        if lease.startswith(prefix):
                            self._conflict("ordinary-writer-active", f"ordinary writer holds {prefix}")
                for attempt in self.attempts.values():
                    if attempt["state"] == "staged":
                        for prefix in prefixes:
                            for existing in attempt["payload"]["ownedPrefixes"]:
                                if prefix.startswith(existing) or existing.startswith(prefix):
                                    self._conflict("overlap", "overlapping active claim")
                self.next_id += 1
                claim_id = f"claim-{self.next_id:04d}"
                self.attempts[claim_id] = {
                    "payload": payload, "staged": {}, "state": "staged",
                    "generation": 1, "abortReason": None,
                }
                return {"claimId": claim_id, "generation": 1, "idempotent": False}

            def stage_object(self, claim_id: str, canonical_path: str, body: bytes) -> dict:
                self.calls.append(("stage", claim_id, canonical_path))
                attempt = self.attempts.get(claim_id)
                if attempt is None or attempt["state"] != "staged":
                    self._conflict("no-active-claim", "claim is not staged")
                declared = {o["path"]: o for o in attempt["payload"]["objects"]}
                if canonical_path not in declared:
                    self._conflict("undeclared-path", canonical_path)
                entry = declared[canonical_path]
                existing = attempt["staged"].get(canonical_path)
                if existing is not None:
                    if existing == body:
                        return {"ok": True, "idempotent": True}
                    self._conflict("byte-mismatch", canonical_path)
                if len(body) != entry["size"] or hashlib.sha256(body).hexdigest() != entry["sha256"]:
                    self._conflict("byte-mismatch", canonical_path)
                attempt["staged"][canonical_path] = body
                return {"ok": True}

            def inspect(self, claim_id: str) -> dict:
                attempt = self.attempts.get(claim_id)
                if attempt is None:
                    self._conflict("unknown-claim", claim_id)
                return {"stagedCount": len(attempt["staged"]), "state": attempt["state"]}

            def commit(self, claim_id: str) -> dict:
                self.calls.append(("commit", claim_id))
                attempt = self.attempts.get(claim_id)
                if attempt is None:
                    self._conflict("unknown-claim", claim_id)
                if attempt["state"] == "committed":
                    return {"state": "committed", "idempotent": True}
                if attempt["state"] != "staged":
                    self._conflict("terminal-claim", attempt["state"])
                declared = attempt["payload"]["objects"]
                if len(attempt["staged"]) != len(declared):
                    self._conflict("staging-incomplete", f"{len(attempt['staged'])}/{len(declared)}")
                for entry in declared:
                    body = attempt["staged"].get(entry["path"])
                    if body is None or len(body) != entry["size"] or hashlib.sha256(body).hexdigest() != entry["sha256"]:
                        self._conflict("staging-incomplete", entry["path"])
                for entry in declared:
                    self.canonical[entry["path"]] = attempt["staged"][entry["path"]]
                attempt["state"] = "committed"
                return {"state": "committed", "idempotent": False}

            def abort(self, claim_id: str, reason: str) -> dict:
                self.calls.append(("abort", claim_id, reason))
                attempt = self.attempts.get(claim_id)
                if attempt is None:
                    self._conflict("unknown-claim", claim_id)
                if attempt["state"] == "aborted":
                    if attempt["abortReason"] != reason:
                        self._conflict("abort-reason-conflict", "immutable abort reason")
                    return {"state": "aborted", "idempotent": True}
                if attempt["state"] == "committed":
                    self._conflict("already-committed", claim_id)
                attempt["state"] = "aborted"
                attempt["abortReason"] = reason
                return {"state": "aborted", "idempotent": False}

            def ordinary_put(self, path: str, body: bytes) -> None:
                # an ordinary Maven writer taking the canonical-write lease
                for attempt in self.attempts.values():
                    if attempt["state"] == "staged":
                        for prefix in attempt["payload"]["ownedPrefixes"]:
                            if path.startswith(prefix):
                                self._conflict("claim-active", f"atomic claim holds {prefix}")
                if path in self.canonical:
                    self._conflict("create-only-409", path)
                self.ordinary_leases.add(path)
                self.canonical[path] = body
                self.ordinary_leases.discard(path)

        def release_via(server: FakeAtomicServer, plan_file: Path, bundle: Path, out: Path,
                        task_id: str | None = "106") -> int:
            """Drive command_release with release_client_from_env stubbed to a
            client backed by the fake server."""
            captured = {"client": None}

            class ServerClient:
                def __init__(self) -> None:
                    self.inner = server

                def claim(self, payload):
                    return self.inner.claim(payload)

                def stage_object(self, claim_id, canonical_path, body):
                    return self.inner.stage_object(claim_id, canonical_path, body)

                def inspect(self, claim_id):
                    return self.inner.inspect(claim_id)

                def commit(self, claim_id):
                    return self.inner.commit(claim_id)

                def abort(self, claim_id, reason):
                    return self.inner.abort(claim_id, reason)

            captured["client"] = ServerClient()
            original = pub.release_client_from_env
            original_maven = pub.client_from_env
            original_listing = pub.list_primaries_from_env
            original_token = pub.fetch_token_self_receipt
            pub.release_client_from_env = lambda: captured["client"]
            pub.client_from_env = original_maven
            pub.list_primaries_from_env = original_listing
            pub.fetch_token_self_receipt = lambda *a, **k: dict(FAKE_TOKEN_RECEIPT)
            task_id_was = os.environ.get("RAFT_RELEASE_TASK_ID")
            if task_id is None:
                os.environ.pop("RAFT_RELEASE_TASK_ID", None)
            else:
                os.environ["RAFT_RELEASE_TASK_ID"] = task_id
            # Same Hosted-runner hygiene as run_cmd/run_direct: GITHUB_SHA
            # would arm the live master recheck against the real origin.
            sha_was = os.environ.pop("GITHUB_SHA", None)
            try:
                args = pub.build_parser().parse_args(
                    ["release", "--manifest", str(manifest_path), "--plan", str(plan_file),
                     "--bundle", str(bundle), "--token-receipt-out", str(root / "tok.json"),
                     "--output", str(out)])
                return args.func(args)
            finally:
                pub.release_client_from_env = original
                pub.client_from_env = original_maven
                pub.list_primaries_from_env = original_listing
                pub.fetch_token_self_receipt = original_token
                if task_id_was is None:
                    os.environ.pop("RAFT_RELEASE_TASK_ID", None)
                else:
                    os.environ["RAFT_RELEASE_TASK_ID"] = task_id_was
                if sha_was is not None:
                    os.environ["GITHUB_SHA"] = sha_was

        def release_raises(name: str, server: FakeAtomicServer, plan_file: Path, bundle: Path, needle: str) -> None:
            try:
                release_via(server, plan_file, bundle, root / "rel.json")
            except Exception as error:  # noqa: BLE001
                check(name, needle in str(error), f"(message was: {error})")
                return
            check(name, False, "(no error raised)")

        # 8-12. atomic release teeth (fake ledger). freeze helper unchanged:
        def freeze_into(staging_dir: Path, plan_file: Path, out_name: str) -> Path:
            bundle = root / out_name
            digest_out = root / (out_name + ".sha")
            run_cmd(["freeze", "--manifest", str(manifest_path), "--staging-roots", str(staging_dir),
                     "--output", str(bundle), "--digest-out", str(digest_out)], FakeClient(), env)
            plan = json.loads(plan_file.read_text())
            plan["bundleSha256"] = digest_out.read_text().strip()
            plan_file.write_text(json.dumps(plan), encoding="utf-8")
            return bundle

        # 8. release refuses a noop plan (no claim is ever made)
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], full, env)
        noop_bundle = freeze_into(staging, plan_path, "noop.tar")
        server = FakeAtomicServer()
        release_raises("release_refuses_noop_plan", server, plan_path, noop_bundle, "not publish")
        check("noop_plan_zero_claims", not [c for c in server.calls if c[0] == "claim"])

        # 9. preflight: wrong bundle digest / tampered member / extra member
        # all stop before ANY claim
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], client, env)
        good_bundle = freeze_into(staging, plan_path, "good.tar")
        wrong_plan = root / "wrong-plan.json"
        wrong_plan.write_text(json.dumps({**json.loads(plan_path.read_text()), "bundleSha256": "0" * 64}), encoding="utf-8")
        server = FakeAtomicServer()
        release_raises("release_rejects_wrong_bundle_digest", server, wrong_plan, good_bundle, "not the same bundle")
        check("wrong_digest_zero_claims", not [c for c in server.calls if c[0] == "claim"])

        import tarfile as _tf
        evil_bundle = root / "evil.tar"
        with _tf.open(good_bundle) as src_tar, _tf.open(evil_bundle, "w") as dst_tar:
            for m in src_tar.getmembers():
                body = src_tar.extractfile(m).read()
                if m.name == paths[1]:
                    body = bytes(b ^ 0xFF for b in body)
                info = _tf.TarInfo(m.name)
                info.size = len(body)
                dst_tar.addfile(info, __import__("io").BytesIO(body))
        evil_plan = root / "evil-plan.json"
        evil_plan.write_text(json.dumps({**json.loads(plan_path.read_text()),
                                          "bundleSha256": hashlib.sha256(evil_bundle.read_bytes()).hexdigest()}), encoding="utf-8")
        server = FakeAtomicServer()
        release_raises("release_rejects_tampered_bundle_member", server, evil_plan, evil_bundle, "do not match the manifest")
        check("tampered_bundle_zero_claims", not [c for c in server.calls if c[0] == "claim"])

        extra_bundle = root / "extra-member.tar"
        with _tf.open(good_bundle) as t0, _tf.open(extra_bundle, "w") as t1:
            for m in t0.getmembers():
                data = t0.extractfile(m).read()
                info = _tf.TarInfo(m.name)
                info.size = len(data)
                t1.addfile(info, __import__("io").BytesIO(data))
            smuggled = f"{LANE}/datetime/{VERSION}/datetime-{VERSION}-smuggled.jar"
            payload = b"smuggled"
            info = _tf.TarInfo(smuggled)
            info.size = len(payload)
            t1.addfile(info, __import__("io").BytesIO(payload))
        extra_plan = root / "extra-plan.json"
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(extra_plan)], FakeClient(), env)
        extra_plan.write_text(json.dumps({**json.loads(extra_plan.read_text()),
                                          "bundleSha256": hashlib.sha256(extra_bundle.read_bytes()).hexdigest()}), encoding="utf-8")
        server = FakeAtomicServer()
        release_raises("release_rejects_extra_bundle_member", server, extra_plan, extra_bundle, "member set does not equal")
        check("extra_member_zero_claims", not [c for c in server.calls if c[0] == "claim"])

        # 10. missing RAFT_RELEASE_TASK_ID fails before any claim
        server = FakeAtomicServer()
        try:
            release_via(server, plan_path, good_bundle, root / "rel.json", task_id=None)
            check("release_requires_task_id_env", False, "(no error raised)")
        except Exception as error:  # noqa: BLE001
            check("release_requires_task_id_env", "RAFT_RELEASE_TASK_ID" in str(error))

        # 11. THE money teeth: full green flow, and a mid-stage crash leaves
        # ZERO public mutation (abort, canonical still empty).
        task_env = dict(env, RAFT_RELEASE_TASK_ID="106")
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], client, task_env)
        rel_bundle = freeze_into(staging, plan_path, "rel.tar")
        server = FakeAtomicServer()
        code = release_via(server, plan_path, rel_bundle, root / "release-receipt.json")
        receipt = json.loads((root / "release-receipt.json").read_text())
        check("release_green_committed", code == 0 and receipt["status"] == "committed")
        check("release_green_claim_shape", bool(receipt["claimId"]) and receipt["taskId"] == "106")
        check("release_green_canonical_complete",
              all(server.canonical[rel] == content_for(rel) for rel in paths))

        # crash at stage 3/5: server refuses the 3rd object (byte mismatch),
        # release must abort with an immutable reason and publish NOTHING
        crash_server = FakeAtomicServer()
        poison = f"{LANE}/datetime/{VERSION}/datetime-{VERSION}.module"
        crash_server.attempts["claim-0001"] = None  # placeholder removed below
        del crash_server.attempts["claim-0001"]

        class CrashServer(FakeAtomicServer):
            def stage_object(self, claim_id, canonical_path, body):
                if canonical_path == poison:
                    from atomic_release import AtomicReleaseError
                    raise AtomicReleaseError("byte-mismatch", "injected stage crash", http_status=409)
                return super().stage_object(claim_id, canonical_path, body)

        crashed = CrashServer()
        release_raises("mid_stage_crash_aborts", crashed, plan_path, rel_bundle, "injected stage crash")
        aborts = [c for c in crashed.calls if c[0] == "abort"]
        check("crash_abort_called_once", len(aborts) == 1)
        check("crash_zero_public_mutation", crashed.canonical == {})
        check("crash_reason_recorded", "injected stage crash" in str(aborts[0][2]))

        # abort reason is immutable: second abort with a different reason
        # conflicts (server-side), and the client surfaces it
        original_reason = "original failure"
        same_server = crashed
        try:
            same_server.abort("claim-0001", "different reason")
            check("abort_reason_immutable", False, "(no conflict raised)")
        except Exception as error:  # noqa: BLE001
            check("abort_reason_immutable", "abort-reason-conflict" in str(error))

        # idempotent retry: running the exact same release again returns the
        # original claim and commits idempotently, changing nothing
        retry_server = FakeAtomicServer()
        code = release_via(retry_server, plan_path, rel_bundle, root / "rel2.json")
        code2 = release_via(retry_server, plan_path, rel_bundle, root / "rel3.json")
        r2 = json.loads((root / "rel2.json").read_text())
        r3 = json.loads((root / "rel3.json").read_text())
        check("release_retry_same_claim", code == 0 and code2 == 0 and r2["claimId"] == r3["claimId"])
        check(
            "release_retry_idempotent_commit",
            r3.get("idempotent") is True and r3["commitReceipt"].get("state") == "committed",
        )
        check("release_retry_canonical_stable", all(retry_server.canonical[rel] == content_for(rel) for rel in paths))

        # B1: lost commit response after server-side commit must recover receipt
        # without aborting the already-committed claim.
        class CommitLossServer(FakeAtomicServer):
            def __init__(self) -> None:
                super().__init__()
                self.lose_next_commit = True

            def commit(self, claim_id: str) -> dict:
                receipt = super().commit(claim_id)
                if self.lose_next_commit:
                    self.lose_next_commit = False
                    from atomic_release import AtomicReleaseError
                    raise AtomicReleaseError(
                        "transport",
                        "response lost after server commit",
                        http_status=None,
                    )
                return receipt

        loss_server = CommitLossServer()
        loss_out = root / "rel-commit-loss.json"
        loss_code = release_via(loss_server, plan_path, rel_bundle, loss_out)
        loss_receipt = json.loads(loss_out.read_text()) if loss_out.is_file() else {}
        loss_aborts = [c for c in loss_server.calls if c[0] == "abort"]
        check("commit_response_loss_recovers_zero_exit", loss_code == 0)
        check(
            "commit_response_loss_writes_committed_receipt",
            loss_receipt.get("status") == "committed"
            and loss_receipt.get("commitReceipt", {}).get("state") == "committed"
            and loss_receipt.get("commitReceipt", {}).get("recoveredFrom") == "commit-response-ambiguity",
        )
        check("commit_response_loss_no_abort", loss_aborts == [])
        check(
            "commit_response_loss_public_objects_stable",
            len(loss_server.canonical) == len(paths)
            and all(loss_server.canonical[rel] == content_for(rel) for rel in paths),
        )

        # B1: HTTP-success commit with malformed/empty body ({}) must also
        # recover via inspect — not fail outer require without re-inspect.
        class MalformedCommitServer(FakeAtomicServer):
            def __init__(self) -> None:
                super().__init__()
                self.malform_next = True

            def commit(self, claim_id: str) -> dict:
                super().commit(claim_id)  # server commits
                if self.malform_next:
                    self.malform_next = False
                    return {}  # HTTP success unparseable body
                return {"state": "committed", "idempotent": True}

        mal_server = MalformedCommitServer()
        mal_out = root / "rel-commit-malformed.json"
        mal_code = release_via(mal_server, plan_path, rel_bundle, mal_out)
        mal_receipt = json.loads(mal_out.read_text()) if mal_out.is_file() else {}
        mal_aborts = [c for c in mal_server.calls if c[0] == "abort"]
        check("commit_malformed_success_recovers_zero_exit", mal_code == 0)
        check(
            "commit_malformed_success_writes_committed_receipt",
            mal_receipt.get("status") == "committed"
            and mal_receipt.get("commitReceipt", {}).get("state") == "committed"
            and mal_receipt.get("commitReceipt", {}).get("recoveredFrom") == "commit-response-ambiguity",
        )
        check("commit_malformed_success_no_abort", mal_aborts == [])
        check(
            "commit_malformed_success_public_objects_stable",
            len(mal_server.canonical) == len(paths)
            and all(mal_server.canonical[rel] == content_for(rel) for rel in paths),
        )

        # B2 end-to-end: classifier plan.ownedPrefixes must equal the atomic
        # release receipt.ownedPrefixes (both trailing-slash canonical).
        e2e_plan = root / "e2e-plan.json"
        e2e_receipt = root / "e2e-publish-receipt.json"
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(e2e_plan)], FakeClient(), env)
        plan_obj = json.loads(e2e_plan.read_text())
        e2e_bundle = freeze_into(staging, e2e_plan, "e2e-bundle.tar")
        e2e_server = FakeAtomicServer()
        e2e_code = release_via(e2e_server, e2e_plan, e2e_bundle, e2e_receipt)
        pub_obj = json.loads(e2e_receipt.read_text()) if e2e_receipt.is_file() else {}
        check("e2e_release_green", e2e_code == 0 and pub_obj.get("status") == "committed")
        check(
            "e2e_plan_receipt_owned_prefixes_equal",
            plan_obj.get("ownedPrefixes") == pub_obj.get("ownedPrefixes"),
        )
        check(
            "e2e_owned_prefixes_trailing_slash",
            isinstance(plan_obj.get("ownedPrefixes"), list)
            and all(isinstance(p, str) and p.endswith("/") for p in plan_obj["ownedPrefixes"])
            and all(isinstance(p, str) and p.endswith("/") for p in pub_obj.get("ownedPrefixes", [])),
        )

        # 11b. claim fences: pre-existing canonical and ordinary writers are
        # conflicts; a mid-claim ordinary writer is refused too
        fenced = FakeAtomicServer()
        fenced.canonical[paths[0]] = b"already-here"
        release_raises("claim_rejects_preexisting_canonical", fenced, plan_path, rel_bundle, "canonical-exists")
        fenced2 = FakeAtomicServer()
        fenced2.ordinary_leases.add(paths[0])
        release_raises("claim_rejects_active_ordinary_writer", fenced2, plan_path, rel_bundle, "ordinary writer")
        try:
            fenced3 = FakeAtomicServer()
            fenced3.claim(json.loads(json.dumps({"repository": "maven", "taskId": "106", "version": VERSION,
                                                  "sourceSha": SOURCE_SHA,
                                                  "ownedPrefixes": sorted({p.rsplit('/', 1)[0] + '/' for p in paths}),
                                                  "manifestDigest": __import__("atomic_release").manifest_digest(
                                                      [{"path": pth, "sha256": hashlib.sha256(content_for(pth)).hexdigest(),
                                                        "size": len(content_for(pth))} for pth in paths]),
                                                  "objects": [{"path": pth, "sha256": hashlib.sha256(content_for(pth)).hexdigest(),
                                                               "size": len(content_for(pth))} for pth in paths],
                                                  "leaseSeconds": 600})))
            fenced3.ordinary_put(paths[0], b"sneaky")
            check("ordinary_writer_rejected_during_claim", False, "(write went through)")
        except Exception as error:  # noqa: BLE001
            check("ordinary_writer_rejected_during_claim", "claim-active" in str(error) or "create-only" in str(error))

        # claim rejects a forged manifest digest (server recomputes): patch
        # the CLIENT-side digest to garbage — the fake ledger recomputes from
        # its own freshly-loaded module, so only the client's claim is forged
        forgery = FakeAtomicServer()
        forged_plan = root / "forged-plan.json"
        forged_plan.write_text(json.dumps({**json.loads(plan_path.read_text())}), encoding="utf-8")
        ar_mod = __import__("atomic_release")
        orig_digest = ar_mod.manifest_digest
        ar_mod.manifest_digest = lambda objects: "0" * 64
        try:
            release_raises("claim_rejects_digest_forgery", forgery, forged_plan, rel_bundle,
                           "manifest-digest-mismatch")
        finally:
            ar_mod.manifest_digest = orig_digest

        # 12. verified no-op second run against a committed canonical: classify
        # sees all-present-identical and no claim is even attempted
        second = FakeClient()
        for entry_rel in paths:
            second.store[entry_rel] = content_for(entry_rel)
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], second, env)
        plan = json.loads(plan_path.read_text())
        check("second_run_noop_verified", plan["decision"] == "noop-verified")
        server2 = FakeAtomicServer()
        run_cmd(["verify", "--manifest", str(manifest_path)], second, env)
        check("second_run_verify_green_zero_claims", not [c for c in server2.calls if c[0] == "claim"])

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
        second.store[paths[0]] = b"corrupted"
        expect_raises(
            "verify_fails_on_corrupted_remote",
            lambda: run_direct(["verify", "--manifest", str(manifest_path)], second, env),
            "digest mismatch",
        )
        second.store[paths[0]] = content_for(paths[0])
        del second.store[paths[1]]
        expect_raises(
            "verify_fails_on_missing_remote",
            lambda: run_direct(["verify", "--manifest", str(manifest_path)], second, env),
            "GET HTTP 404",
        )
        second.store[paths[1]] = content_for(paths[1])
        second.listing_extra.append(f"{LANE}/datetime/{VERSION}/datetime-{VERSION}-stowaway.jar")
        expect_raises(
            "verify_fails_on_extra_listing_primary",
            lambda: run_direct(["verify", "--manifest", str(manifest_path)], second, env),
            "unexpected carriers present",
        )
        second.listing_extra.clear()
        second.listing_drop.add(paths[2])
        expect_raises(
            "verify_fails_on_listing_missing_primary",
            lambda: run_direct(["verify", "--manifest", str(manifest_path)], second, env),
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
        # remote zero-mutation cases are now asserted by the bundle barrier
        # teeth above (wrong_digest_zero_claims, tampered_bundle_zero_claims,
        # extra_member_zero_claims + the claim fences and the frozen-bytes
        # precision tooth). The release lane never re-reads mutable paths, so
        # a post-freeze staging mutation cannot influence the staged set by
        # construction.

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

        def run_agg_case(base: Path, plan_prefix="a" * 16, publish_prefix="a" * 16, terminal_prefix="a" * 16, drop_last_file: bool = False, bad_receipt_sha: bool = False, bad_plan_decision: bool = False, missing_plan_prefix: bool = False, expired_terminal: bool = False, committed_no_claimid: bool = False, publish_as_complete: bool = False, committed_missing_taskid: bool = False, committed_missing_commit_receipt: bool = False, noop_decision: bool = False, noop_with_claimid: bool = False, wrong_taskid: bool = False, bad_manifest_digest: bool = False, bad_manifest_digest_nonhex: bool = False, staged_commit_receipt: bool = False, omit_verify_receipt: bool = False, bad_verify_sha: bool = False, release_task_id: str = "106"):
            """One self-contained aggregate-receipt case: temp git repo whose
            HEAD is the release exact (master == dispatch == fixture sha)."""
            repo = base / "repo"
            repo.mkdir(parents=True, exist_ok=True)
            _sp.run(["git", "init", "-q", "-b", "master"], cwd=repo, check=True)
            (repo / "gradle.properties").write_text(f"mavenVersion={VERSION}\n", encoding="utf-8")
            _sp.run(["git", "add", "gradle.properties"], cwd=repo, check=True)
            _sp.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "x"], cwd=repo, check=True)
            sha = _sp.run(["git", "rev-parse", "HEAD"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()
            origin_repo = base / "origin-repo"
            origin_repo.mkdir(exist_ok=True)
            _sp.run(["git", "init", "-q", "-b", "master", "--bare"], cwd=origin_repo, check=True)
            _sp.run(["git", "remote", "add", "origin", str(origin_repo)], cwd=repo, check=True)
            _sp.run(["git", "push", "-q", "origin", "master"], cwd=repo, check=True)
            _sp.run(["git", "fetch", "--quiet", "origin", "master"], cwd=repo, check=True)

            artifacts = base / "artifacts"
            (artifacts / "datetime-raft-global-plan").mkdir(parents=True)
            (artifacts / "datetime-raft-receipt-publish").mkdir(parents=True)
            files = [{"path": pth, "sha256": hashlib.sha256(pth.encode()).hexdigest(), "size": 3} for pth in paths]
            merged = {"schema": 1, "version": VERSION, "sourceSha": sha,
                      "destination": pub.RAFT_ARTIFACTS_BASE_URL, "fileCount": len(paths), "files": files}
            json.dump(merged, open(artifacts / "datetime-raft-global-plan" / "merged-manifest.json", "w"))
            import tarfile as _tf5, io as _io3
            bundle_bytes = base / "bundle.tar"
            with _tf5.open(bundle_bytes, "w") as _tb:
                for pth in sorted(paths):
                    data = content_for(pth)
                    info = _tf5.TarInfo(pth)
                    info.size = len(data)
                    _tb.addfile(info, _io3.BytesIO(data))
            bundle_digest = hashlib.sha256(bundle_bytes.read_bytes()).hexdigest()
            plan_obj = {"decision": "publish", "fileCount": len(paths), "tokenHashPrefix": plan_prefix,
                        "bundleSha256": bundle_digest, "ownedPrefixes": sorted({pth.rsplit("/", 1)[0] + "/" for pth in paths}),
                        "missing": sorted(paths)}
            if noop_decision or noop_with_claimid:
                plan_obj["decision"] = "noop-verified"
                plan_obj["missing"] = []
            if bad_plan_decision:
                plan_obj["decision"] = "teleport"
            if missing_plan_prefix:
                del plan_obj["tokenHashPrefix"]
            json.dump(plan_obj, open(artifacts / "datetime-raft-global-plan" / "global-plan.json", "w"))
            json.dump(dict(FAKE_TOKEN_RECEIPT, hashPrefix=plan_prefix),
                      open(artifacts / "datetime-raft-global-plan" / "token-receipt.json", "w"))
            import shutil as _shutil
            _shutil.copy(bundle_bytes, artifacts / "datetime-raft-global-plan" / "frozen-bundle.tar")
            receipt_files = files[:-1] if drop_last_file else files
            receipt_files = [dict(f) for f in receipt_files]
            if bad_receipt_sha:
                receipt_files[0]["sha256"] = "z" * 64
            import importlib.util as _ilu
            _ar_spec = _ilu.spec_from_file_location("ar_agg", HERE / "atomic_release.py")
            _ar = _ilu.module_from_spec(_ar_spec)
            _ar_spec.loader.exec_module(_ar)
            object_digest = _ar.manifest_digest(
                [{"path": f["path"], "sha256": f["sha256"], "size": f["size"]} for f in files]
            )
            receipt_obj = {
                "status": "committed",
                "claimId": "claim-0001",
                "taskId": "wrong-task" if wrong_taskid else "106",
                "manifestDigest": (("not-a-sha256" if bad_manifest_digest_nonhex else (("0" * 64) if bad_manifest_digest else object_digest))),
                "ownedPrefixes": sorted({pth.rsplit("/", 1)[0] + "/" for pth in paths}),
                "commitReceipt": (
                    {"state": "staged"} if staged_commit_receipt
                    else {"state": "committed", "idempotent": False}
                ),
                "version": VERSION,
                "sourceSha": sha,
                "fileCount": len(receipt_files),
                "files": receipt_files,
            }
            if publish_as_complete or noop_decision or noop_with_claimid:
                # Real verify-shape complete receipt (what overwrote committed
                # before B1 was closed). No atomic identity fields.
                receipt_obj = {
                    "status": "complete",
                    "version": VERSION,
                    "sourceSha": sha,
                    "fileCount": len(receipt_files),
                    "files": receipt_files,
                    "destination": pub.RAFT_ARTIFACTS_BASE_URL,
                }
                if noop_with_claimid:
                    receipt_obj["claimId"] = "claim-should-not-be-here"
            if committed_no_claimid:
                receipt_obj.pop("claimId", None)
            if committed_missing_taskid:
                receipt_obj.pop("taskId", None)
            if committed_missing_commit_receipt:
                receipt_obj.pop("commitReceipt", None)
            json.dump(receipt_obj,
                      open(artifacts / "datetime-raft-receipt-publish" / "publish-receipt.json", "w"))
            json.dump(dict(FAKE_TOKEN_RECEIPT, hashPrefix=publish_prefix),
                      open(artifacts / "datetime-raft-receipt-publish" / "publish-token-receipt.json", "w"))
            # Publish path requires a separate verify-receipt.json (readback proof).
            # Noop path does not emit it (verify wrote publish-receipt as complete).
            if not (publish_as_complete or noop_decision or noop_with_claimid or omit_verify_receipt):
                verify_files = [dict(f) for f in files]
                if bad_verify_sha:
                    verify_files[0] = dict(verify_files[0], sha256="c" * 64)
                verify_obj = {
                    "status": "complete",
                    "destination": pub.RAFT_ARTIFACTS_BASE_URL,
                    "version": VERSION,
                    "sourceSha": sha,
                    "fileCount": len(verify_files),
                    "files": verify_files,
                }
                json.dump(verify_obj,
                          open(artifacts / "datetime-raft-receipt-publish" / "verify-receipt.json", "w"))
            terminal_record = dict(FAKE_TOKEN_RECEIPT, hashPrefix=terminal_prefix)
            if expired_terminal:
                terminal_record["expiresAt"] = 1
            json.dump(terminal_record, open(base / "terminal-token.json", "w"))

            argv = ["--artifacts", str(artifacts), "--terminal-token-receipt", str(base / "terminal-token.json"),
                    "--output", str(base / "agg.json")]
            old_cwd = os.getcwd()
            old_sha = os.environ.get("GITHUB_SHA", "")
            old_ep = os.environ.get("RAFT_ARTIFACTS_EXPECT_PRINCIPAL", "")
            old_task = os.environ.get("RAFT_RELEASE_TASK_ID", "")
            os.environ["GITHUB_SHA"] = sha
            os.environ["RAFT_ARTIFACTS_EXPECT_PRINCIPAL"] = "cc-wow2"
            os.environ["RAFT_RELEASE_TASK_ID"] = release_task_id
            try:
                os.chdir(repo)
                return agg.main(argv)
            finally:
                os.chdir(old_cwd)
                if old_sha == "":
                    os.environ.pop("GITHUB_SHA", None)
                else:
                    os.environ["GITHUB_SHA"] = old_sha
                if old_ep == "":
                    os.environ.pop("RAFT_ARTIFACTS_EXPECT_PRINCIPAL", None)
                else:
                    os.environ["RAFT_ARTIFACTS_EXPECT_PRINCIPAL"] = old_ep
                if old_task == "":
                    os.environ.pop("RAFT_RELEASE_TASK_ID", None)
                else:
                    os.environ["RAFT_RELEASE_TASK_ID"] = old_task

        agg_ok = root / "agg-ok"
        agg_ok.mkdir()
        code = run_agg_case(agg_ok)
        check("aggregate_receipt_green", code == 0)

        # B2 production pipeline: classifier-owned prefixes (with trailing /)
        # must equal command_release receipt prefixes and pass aggregate.
        pipe = root / "pipeline-prefix"
        pipe.mkdir()
        # Build plan via make_plan semantics using pub.owned_prefixes
        plan_prefixes = pub.owned_prefixes({"files": [{"path": p, "sha256": "a"*64, "size": 1} for p in paths]})
        claim_prefixes = __import__("atomic_release").owned_prefixes_from_paths(paths)
        check("owned_prefixes_plan_matches_atomic_client", plan_prefixes == claim_prefixes)
        check(
            "owned_prefixes_trailing_slash_canonical",
            all(p.endswith("/") for p in plan_prefixes) and all(p.endswith("/") for p in claim_prefixes),
        )
        agg_noop = root / "agg-noop"
        agg_noop.mkdir()
        code = run_agg_case(agg_noop, noop_decision=True)
        check("aggregate_noop_complete_green", code == 0)
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
        for name, kwargs in (
            ("aggregate_rejects_bad_receipt_sha", {"bad_receipt_sha": True}),
            ("aggregate_rejects_bad_plan_decision", {"bad_plan_decision": True}),
            ("aggregate_rejects_missing_plan_prefix", {"missing_plan_prefix": True}),
            ("aggregate_rejects_expired_terminal_receipt", {"expired_terminal": True}),
            ("aggregate_rejects_committed_without_claimid", {"committed_no_claimid": True}),
            # B1 real-shape arms: publish + complete (no claimId) must RED;
            # committed missing taskId / commitReceipt must RED; noop+claimId must RED.
            ("aggregate_rejects_publish_with_complete_receipt", {"publish_as_complete": True}),
            ("aggregate_rejects_committed_without_taskid", {"committed_missing_taskid": True}),
            ("aggregate_rejects_committed_without_commit_receipt", {"committed_missing_commit_receipt": True}),
            ("aggregate_rejects_noop_with_claimid", {"noop_with_claimid": True}),
            ("aggregate_rejects_wrong_taskid", {"wrong_taskid": True}),
            ("aggregate_rejects_bad_manifest_digest", {"bad_manifest_digest": True}),
            ("aggregate_rejects_nonhex_manifest_digest", {"bad_manifest_digest_nonhex": True}),
            ("aggregate_rejects_staged_commit_receipt", {"staged_commit_receipt": True}),
            ("aggregate_rejects_missing_verify_receipt", {"omit_verify_receipt": True}),
            ("aggregate_rejects_bad_verify_sha", {"bad_verify_sha": True}),
        ):
            case_dir = root / ("agg-" + name)
            case_dir.mkdir()
            try:
                run_agg_case(case_dir, **kwargs)
                check(name, False, "(no failure raised)")
            except SystemExit as e:
                check(name, e.code == 1)

        # 16h. frozen-bytes causal catcher: tampering the bundle FILE after
        # the first staged object must not change what the ledger receives —
        # the client stages only the frozen in-memory bytes it validated at
        # the barrier. A regression that re-reads the bundle at stage time
        # turns this red (byte-mismatch or wrong canonical bytes).
        class BundleTamperServer(FakeAtomicServer):
            def __init__(self, bundle_path: Path) -> None:
                super().__init__()
                self.bundle_path = bundle_path
                self.armed = True

            def stage_object(self, claim_id, canonical_path, body):
                if self.armed:
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
                return super().stage_object(claim_id, canonical_path, body)

        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], FakeClient(), env)
        tamper_bundle = freeze_into(staging, plan_path, "tamper-target.tar")
        tamper_server = BundleTamperServer(tamper_bundle)
        code = release_via(tamper_server, plan_path, tamper_bundle, root / "rel-tamper.json")
        check(
            "remote_receives_frozen_bytes_even_if_bundle_tampered_mid_publish",
            code == 0 and all(tamper_server.canonical[rel] == content_for(rel) for rel in paths),
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
        admission_block = wf[wf.find("\n  admission:"):wf.find("\n  preflight-android:")]
        check(
            "workflow_admission_requires_source_exact",
            '"$REQUESTED_SOURCE_SHA" = "$GITHUB_SHA"' in admission_block
            and 'git rev-parse origin/master' in admission_block
            and '^[0-9a-f]{40}$' in admission_block
            and 'REQUESTED_SOURCE_SHA: ${{ github.event.inputs.source_sha }}' in admission_block,
        )
        plan_block = wf[wf.find("\n  plan:"):wf.find("\n  publish:")]
        check("workflow_plan_environment_pinned", "environment: raft-artifacts-production" in plan_block)
        receipt_block = wf[wf.find("\n  receipt:"):]
        check("workflow_receipt_environment_pinned", "environment: raft-artifacts-production" in receipt_block)
        check("workflow_has_aggregate_receipt", "token-receipt" in wf)
        # Sidecar model: no dispatch input may carry the principal; the value
        # comes only from the protected environment secret on the three
        # credentialed jobs.
        check("workflow_no_principal_dispatch_input", "expected_principal" not in wf)
        # per-job-block structural assertion (a stray comment or duplicated
        # line elsewhere cannot satisfy this)
        for job_name, next_name in (("plan", "publish"), ("publish", "receipt"), ("receipt", None)):
            start_j = wf.find(f"\n  {job_name}:\n")
            end_j = wf.find(f"\n  {next_name}:\n") if next_name else len(wf)
            block = wf[start_j:end_j]
            check(
                f"workflow_{job_name}_reads_principal_secret",
                "RAFT_ARTIFACTS_EXPECT_PRINCIPAL: ${{ secrets.RAFT_ARTIFACTS_EXPECT_PRINCIPAL }}" in block
                and "environment: raft-artifacts-production" in block,
            )
        check("workflow_no_stale_guard_red_on_noop", "stale-rerun" not in wf)
        check(
            "workflow_receipt_needs_plan_and_publish",
            re.search(r"receipt:[\s\S]*?needs:\s*\[[^\]]*\bplan\b[^\]]*\bpublish\b[^\]]*\]", wf) is not None,
        )
        check(
            "workflow_fresh_master_barrier_before_publish",
            'git fetch --quiet origin master' in publish_block
            and publish_block.find("git fetch --quiet origin master") < publish_block.find("raft-publish.py release"),
        )
        # Atomic claim/stage/commit wiring: the publish job invokes the
        # release subcommand (never the retired plain-PUT publish), and the
        # release task id arrives only via the required dispatch input.
        check("workflow_publish_invokes_release_subcommand", "raft-publish.py release" in publish_block)
        check("workflow_no_plain_put_publish_left", "raft-publish.py publish" not in wf)
        check("workflow_has_release_task_id_input", re.search(r"\n      release_task_id:\n\s+description: [^\n]*\n\s+required: true", wf) is not None)
        check(
            "workflow_publish_binds_release_task_id",
            "RAFT_RELEASE_TASK_ID: ${{ github.event.inputs.release_task_id }}" in publish_block,
        )
        receipt_block = wf[wf.find("\n  receipt:"):]
        check(
            "workflow_receipt_binds_release_task_id",
            "RAFT_RELEASE_TASK_ID: ${{ github.event.inputs.release_task_id }}" in receipt_block,
        )
        # B1: release writes publish-receipt.json; verify must target a SEPARATE
        # file on the publish arm so the atomic committed receipt is preserved.
        # The unconditional `verify --output publish-receipt.json` after the
        # case is the exact fail-open shape.
        check(
            "workflow_publish_verify_does_not_overwrite_committed_receipt",
            "--output verify-receipt.json" in publish_block
            and publish_block.find("raft-publish.py release") < publish_block.find("verify-receipt.json"),
        )
        # After the case/esac there must be no `raft-publish.py verify ... --output publish-receipt.json`.
        after_case = publish_block.split("esac", 1)[-1] if "esac" in publish_block else ""
        check(
            "workflow_no_post_case_verify_overwrite",
            re.search(r"raft-publish\.py\s+verify[\s\S]*?--output\s+publish-receipt\.json", after_case) is None,
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
            "grants": [
                {
                    "scope": "build.raft.kuiklybase",
                    "principal": {"kind": "agent", "id": "cc-wow2"},
                    "permissions": ["read", "publish"],
                }
            ],
            "expiresAt": 9999999999999,
        }
        os.environ["RAFT_ARTIFACTS_PUBLISH_TOKEN"] = raw_token
        os.environ["RAFT_ARTIFACTS_USERNAME"] = "raft-ci"
        os.environ["RAFT_ARTIFACTS_EXPECT_PRINCIPAL"] = "cc-wow2"
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
                "not exactly build.raft.kuiklybase",
            )
            # the old substring attack: evil scope + publish elsewhere must fail
            evil_grants = dict(
                good_record,
                grants=[
                    {"scope": "build.raft.kuiklybase.evil", "principal": {"kind": "agent", "id": "cc-wow2"}, "permissions": ["delete"]},
                ],
                label="publish",
            )
            expect_raises(
                "token_receipt_rejects_substring_grant_attack",
                lambda: with_opener(TokenOpener({"tokens": [evil_grants]})),
                "not exactly build.raft.kuiklybase",
            )
            grantless_principal = dict(
                good_record,
                grants=[{"scope": "build.raft.kuiklybase", "permissions": ["read", "publish"]}],
            )
            # human-shaped principal is equally red
            human_principal = dict(
                good_record,
                grants=[{"scope": "build.raft.kuiklybase", "principal": {"kind": "human", "id": "wrong-human"}, "permissions": ["read", "publish"]}],
            )
            expect_raises(
                "token_receipt_rejects_grant_without_principal",
                lambda: with_opener(TokenOpener({"tokens": [grantless_principal]})),
                "non-empty agent principal",
            )
            expect_raises(
                "token_receipt_rejects_human_principal",
                lambda: with_opener(TokenOpener({"tokens": [human_principal]})),
                "agent principal",
            )
            wrong_agent = dict(
                good_record,
                grants=[{"scope": "build.raft.kuiklybase", "principal": {"kind": "agent", "id": "someone-else"}, "permissions": ["read", "publish"]}],
            )
            expect_raises(
                "token_receipt_rejects_wrong_agent_principal",
                lambda: with_opener(TokenOpener({"tokens": [wrong_agent]})),
                "expected release principal",
            )
            admin_cap = dict(
                good_record,
                grants=[{"scope": "build.raft.kuiklybase", "principal": {"kind": "agent", "id": "cc-wow2"}, "permissions": ["read", "publish", "admin"]}],
            )
            expect_raises(
                "token_receipt_rejects_admin_permission",
                lambda: with_opener(TokenOpener({"tokens": [admin_cap]})),
                "beyond-minimal permissions",
            )
            extra_grant = dict(
                good_record,
                grants=good_record["grants"] + [{"scope": "org.other", "principal": {"kind": "agent", "id": "x"}, "permissions": ["admin"]}],
            )
            expect_raises(
                "token_receipt_rejects_extra_grant",
                lambda: with_opener(TokenOpener({"tokens": [extra_grant]})),
                "exactly one minimal grant",
            )
            # live-schema shape: no top-level principal key at all still passes
            live_shape = {k: v for k, v in good_record.items() if k != "principal"}
            r = with_opener(TokenOpener({"tokens": [live_shape]}))
            check("token_receipt_live_schema_shape", r["fullHashMatchedLocally"] is True)
        finally:
            os.environ.pop("RAFT_ARTIFACTS_PUBLISH_TOKEN", None)
            os.environ.pop("RAFT_ARTIFACTS_USERNAME", None)
            os.environ.pop("RAFT_ARTIFACTS_EXPECT_PRINCIPAL", None)

        # 16g. token multi-match red.
        os.environ["RAFT_ARTIFACTS_PUBLISH_TOKEN"] = "task-token-probe"
        os.environ["RAFT_ARTIFACTS_USERNAME"] = "raft-ci"
        os.environ["RAFT_ARTIFACTS_EXPECT_PRINCIPAL"] = "cc-wow2"
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
            os.environ.pop("RAFT_ARTIFACTS_EXPECT_PRINCIPAL", None)



    print(f"\n{PASS} teeth green, {FAIL} red")
    if FAILURES:
        print("red teeth:", ", ".join(FAILURES))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
