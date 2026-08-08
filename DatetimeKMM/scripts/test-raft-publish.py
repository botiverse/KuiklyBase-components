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


def pom_text(sha: str) -> str:
    return (
        "<project><properties>"
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


def run_cmd(argv: list[str], client: FakeClient, env: dict[str, str] | None = None) -> int:
    original_client = pub.client_from_env
    original_listing = pub.list_primaries_from_env
    saved_env: dict[str, str] = {}
    env = env or {}
    pub.client_from_env = lambda: client
    pub.list_primaries_from_env = client.listing
    for key, value in env.items():
        saved_env[key] = os.environ.get(key, "")
        os.environ[key] = value
    try:
        return pub.main(argv)
    finally:
        pub.client_from_env = original_client
        pub.list_primaries_from_env = original_listing
        for key, value in saved_env.items():
            if value == "":
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def run_direct(argv: list[str], client: FakeClient, env: dict[str, str] | None = None) -> int:
    original_client = pub.client_from_env
    original_listing = pub.list_primaries_from_env
    saved_env: dict[str, str] = {}
    env = env or {}
    pub.client_from_env = lambda: client
    pub.list_primaries_from_env = client.listing
    for key, value in env.items():
        saved_env[key] = os.environ.get(key, "")
        os.environ[key] = value
    try:
        args = pub.build_parser().parse_args(argv)
        return args.func(args)
    finally:
        pub.client_from_env = original_client
        pub.list_primaries_from_env = original_listing
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

        # 8. publish refuses a noop plan
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], full, env)
        expect_raises(
            "publish_refuses_noop_plan",
            lambda: run_direct(["publish", "--manifest", str(manifest_path), "--staging", str(staging),
                                "--plan", str(plan_path)], full, env),
            "not publish",
        )

        # 9. publish re-hashes staged bytes (tamper after manifest -> red)
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], client, env)
        tampered = root / "tampered"
        tampered_staging = make_staging(tampered, paths)
        victim = tampered_staging / paths[2]
        victim.write_bytes(b"tampered")
        expect_raises(
            "publish_refuses_tampered_staging",
            lambda: run_direct(["publish", "--manifest", str(manifest_path), "--staging", str(tampered_staging),
                                "--plan", str(plan_path)], client, env),
            "changed after manifest",
        )

        # 10. causal race tooth: foreign bytes after classify -> 409 -> fail, preserved
        race = FakeClient()
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], race, env)
        race.store[paths[0]] = b"foreign-post-plan-bytes"
        expect_raises(
            "publish_never_overwrites_post_plan_foreign_bytes",
            lambda: run_direct(["publish", "--manifest", str(manifest_path), "--staging", str(staging),
                                "--plan", str(plan_path)], race, env),
            "foreign write, stopping",
        )
        check("race_foreign_bytes_preserved", race.store[paths[0]] == b"foreign-post-plan-bytes")

        # 11. publish aborts on non-409 PUT failure
        failing = FakeClient()
        failing.put_fail_status[paths[1]] = 500
        expect_raises(
            "publish_aborts_on_put_failure",
            lambda: run_direct(["publish", "--manifest", str(manifest_path), "--staging", str(staging),
                                "--plan", str(plan_path)], failing, env),
            "PUT failed with HTTP 500",
        )

        # 12. e2e: empty -> classify -> publish -> verify + receipt
        e2e = FakeClient()
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], e2e, env)
        code = run_cmd(["publish", "--manifest", str(manifest_path), "--staging", str(staging),
                        "--plan", str(plan_path)], e2e, env)
        check("e2e_publish_all", code == 0 and len(e2e.store) == len(paths))
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

        # 12b. publish uploads ONLY primaries: local aux in staging never
        # becomes a remote write.
        auxe2e = FakeClient()
        run_cmd(["classify", "--manifest", str(manifest_path), "--output", str(plan_path)], auxe2e, env)
        code = run_cmd(["publish", "--manifest", str(manifest_path), "--staging", str(auxok),
                        "--plan", str(plan_path)], auxe2e, env)
        puts = sorted(p for m, p in auxe2e.calls if m == "PUT")
        check("publish_puts_only_primaries", code == 0 and puts == sorted(paths))

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

    print(f"\n{PASS} teeth green, {FAIL} red")
    if FAILURES:
        print("red teeth:", ", ".join(FAILURES))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
