#!/usr/bin/env python3
"""Causal contract tests for compose-mirror.py.

The default suite is offline.  Passing --authority-bytes adds semantic
mutations against a read-only Tencent fetch staged by the caller.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import io
import json
import shutil
import sys
import tempfile
import urllib.error
import urllib.request
import urllib.response
import zipfile
from email.message import Message
from pathlib import Path
from typing import Any, Callable


HERE = Path(__file__).resolve().parent
MANIFEST_PATH = HERE.parent / "compose-mirror" / "authority-manifest.json"
spec = importlib.util.spec_from_file_location("compose_mirror", HERE / "compose-mirror.py")
if spec is None or spec.loader is None:
    raise SystemExit("cannot load compose-mirror.py")
mirror = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mirror)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def expect_failure(action: Callable[[], object], needle: str, name: str) -> None:
    try:
        action()
    except Exception as error:  # The contract module owns its concrete errors.
        require(needle in str(error), f"{name} failed without {needle!r}: {error}")
        print(f"test-compose-mirror: PASS mutation: {name}")
        return
    raise RuntimeError(f"mutation unexpectedly passed: {name}")


class ScriptedHttpsHandler(urllib.request.BaseHandler):
    handler_order = 100

    def __init__(
        self,
        status: int = 200,
        body: bytes = b"ok",
        *,
        location: str | None = None,
        url_error: str | None = None,
    ) -> None:
        self.status = status
        self.body = body
        self.location = location
        self.url_error = url_error
        self.requests: list[urllib.request.Request] = []

    def https_open(self, request: urllib.request.Request) -> urllib.response.addinfourl:
        self.requests.append(request)
        if self.url_error is not None:
            raise urllib.error.URLError(self.url_error)
        headers = Message()
        if self.location is not None:
            headers.add_header("Location", self.location)
        response = urllib.response.addinfourl(io.BytesIO(self.body), headers, request.full_url, self.status)
        response.msg = "scripted response"
        return response


def fake_manifest() -> tuple[dict[str, Any], dict[str, bytes]]:
    payloads: dict[str, bytes] = {}
    files: list[dict[str, Any]] = []
    for index, relative in enumerate(sorted(mirror.expected_file_paths())):
        body = f"fixture-{index}-{relative}".encode()
        payloads[relative] = body
        files.append({"path": relative, "size": len(body), "sha256": hashlib.sha256(body).hexdigest()})
    return {"files": files}, payloads


def scope_lister(keys: list[str]) -> Callable[[str], list[str]]:
    def value(_scope: str) -> list[str]:
        return list(keys)

    return value


def fetcher(store: dict[str, bytes]) -> Callable[[str], tuple[int, bytes]]:
    def value(relative: str) -> tuple[int, bytes]:
        if relative not in store:
            return 404, b""
        return 200, store[relative]

    return value


def test_manifest_admission() -> None:
    manifest = mirror.load_manifest(MANIFEST_PATH)
    require(len(manifest["files"]) == 32 and len(manifest["gavs"]) == 8, "canonical manifest admission failed")
    with tempfile.TemporaryDirectory(prefix="compose-manifest-mutation.") as temporary:
        root = Path(temporary)

        changed = copy.deepcopy(manifest)
        changed["authority"]["artifactPublicationProvenance"]["status"] = "audited"
        path = root / "promoted-source.json"
        path.write_text(json.dumps(changed), encoding="utf-8")
        expect_failure(lambda: mirror.load_manifest(path), "promoted to audited", "unproven source promoted to audited")

        changed = copy.deepcopy(manifest)
        changed["closure"]["kotlin210Kba010InGraph"] = True
        path = root / "invented-kba010.json"
        path.write_text(json.dumps(changed), encoding="utf-8")
        expect_failure(lambda: mirror.load_manifest(path), "KBA-010 graph verdict changed", "invented KBA-010 closure")

        changed = copy.deepcopy(manifest)
        changed["closure"]["rootToOhosArm64"][-1].pop("caveat")
        path = root / "lost-stale-url-caveat.json"
        path.write_text(json.dumps(changed), encoding="utf-8")
        expect_failure(lambda: mirror.load_manifest(path), "stale-URL caveat is missing", "collection stale URL hidden")

        changed = copy.deepcopy(manifest)
        changed["files"].pop()
        path = root / "missing-primary.json"
        path.write_text(json.dumps(changed), encoding="utf-8")
        expect_failure(lambda: mirror.load_manifest(path), "must contain 32 files", "omitted primary")
    print("test-compose-mirror: PASS immutable manifest admission")


def test_transports() -> None:
    origin = "https://fixture.example.test/repository"
    public_handler = ScriptedHttpsHandler(body=b"authority")
    public_opener = urllib.request.build_opener(public_handler, mirror.RejectRedirectHandler())
    public = mirror.PublicRepositoryClient("fixture", origin, origin, public_opener)
    require(public.get("group/artifact/file.pom") == (200, b"authority"), "public GET control failed")
    request = public_handler.requests[0]
    require(request.get_method() == "GET", "public transport is not GET-only")
    require(request.get_header("Authorization") is None, "public GET carried Authorization")
    print("test-compose-mirror: PASS authority/Raft reads are anonymous")

    redirect_handler = ScriptedHttpsHandler(status=302, location="https://evil.example.test/escape")
    redirect_opener = urllib.request.build_opener(redirect_handler, mirror.RejectRedirectHandler())
    redirect_client = mirror.PublicRepositoryClient("fixture", origin, origin, redirect_opener)
    expect_failure(
        lambda: redirect_client.get("group/artifact/file.pom", attempts=1),
        "redirect rejected before another request",
        "public redirect",
    )
    require(len(redirect_handler.requests) == 1, "redirect issued a second request")

    writer_handler = ScriptedHttpsHandler(status=201, body=b"")
    writer_opener = urllib.request.build_opener(writer_handler, mirror.RejectRedirectHandler())
    # The expected origin stays pinned even though transport is scripted.
    writer = mirror.CreateOnlyWriter(mirror.RAFT_BASE_URL, "writer", "secret-value", writer_opener)
    require(writer.put("group/artifact/file.pom", b"bytes") == 201, "writer PUT control failed")
    request = writer_handler.requests[0]
    require(request.get_method() == "PUT" and request.data == b"bytes", "writer did not issue the exact PUT")
    require(request.get_header("Authorization", "").startswith("Basic "), "writer PUT omitted Authorization")
    require(request.get_header("If-none-match") == "*", "writer PUT omitted create-only precondition")
    require(not hasattr(writer, "get"), "writer process unexpectedly exposes a read method")

    conflict_handler = ScriptedHttpsHandler(status=409, body=b"")
    conflict_opener = urllib.request.build_opener(conflict_handler, mirror.RejectRedirectHandler())
    conflict_writer = mirror.CreateOnlyWriter(mirror.RAFT_BASE_URL, "writer", "secret-value", conflict_opener)
    require(conflict_writer.put("group/artifact/file.pom", b"bytes") == 409, "create-only conflict control failed")
    require(len(conflict_handler.requests) == 1, "writer retried a 409")

    ambiguous_handler = ScriptedHttpsHandler(url_error="lost response")
    ambiguous_opener = urllib.request.build_opener(ambiguous_handler, mirror.RejectRedirectHandler())
    ambiguous_writer = mirror.CreateOnlyWriter(mirror.RAFT_BASE_URL, "writer", "secret-value", ambiguous_opener)
    expect_failure(
        lambda: ambiguous_writer.put("group/artifact/file.pom", b"bytes"),
        "outcome is ambiguous",
        "ambiguous PUT is never retried",
    )
    require(len(ambiguous_handler.requests) == 1, "ambiguous PUT was retried")
    print("test-compose-mirror: PASS PUT-only credential isolation and no ambiguous retry")


def test_planner_states() -> None:
    manifest, payloads = fake_manifest()
    empty = mirror.classify_remote(manifest, scope_lister([]), fetcher({}), positive_control=False)
    require(empty["decision"] == "publish-all-absent" and len(empty["missing"]) == 32, "all-absent control failed")

    complete = mirror.classify_remote(
        manifest,
        scope_lister(list(payloads)),
        fetcher(payloads),
        positive_control=False,
    )
    require(complete["decision"] == "noop-complete-identical" and len(complete["existing"]) == 32, "complete control failed")

    subset = dict(list(payloads.items())[:11])
    partial = mirror.classify_remote(
        manifest,
        scope_lister(list(subset)),
        fetcher(subset),
        positive_control=False,
    )
    require(partial["decision"] == "resume-partial-exact", "partial exact state is not resumable")

    divergent_store = dict(payloads)
    first = sorted(payloads)[0]
    divergent_store[first] = b"different"
    divergent = mirror.classify_remote(
        manifest,
        scope_lister(list(payloads)),
        fetcher(divergent_store),
        positive_control=False,
    )
    require(divergent["decision"] == "hold-conflict" and divergent["divergent"][0]["path"] == first, "divergent bytes did not conflict")

    unexpected_path = first.rsplit("/", 1)[0] + "/unexpected.classifier"
    unexpected = mirror.classify_remote(
        manifest,
        scope_lister(list(payloads) + [unexpected_path]),
        fetcher(payloads),
        positive_control=False,
    )
    require(unexpected["decision"] == "hold-conflict" and unexpected_path in unexpected["unexpected"], "unexpected carrier did not conflict")

    listing_mismatch = mirror.classify_remote(
        manifest,
        scope_lister([]),
        fetcher(payloads),
        positive_control=False,
    )
    require(listing_mismatch["decision"] == "hold-conflict" and len(listing_mismatch["listingMismatch"]) == 32, "listing/GET mismatch did not conflict")
    print("test-compose-mirror: PASS all-absent/complete/resumable-partial/divergent/unexpected/listing-mismatch planner teeth")


def test_writer_order() -> None:
    manifest, payloads = fake_manifest()
    ordered = sorted(payloads, key=mirror.publication_priority)
    first_metadata = next(index for index, path in enumerate(ordered) if path.endswith((".pom", ".module")))
    require(all(not path.endswith((".pom", ".module")) for path in ordered[:first_metadata]), "payloads are not first")
    physical_metadata = [path for path in ordered if "-ohosarm64/" in path and path.endswith((".pom", ".module"))]
    root_metadata = [path for path in ordered if "-ohosarm64/" not in path and path.endswith((".pom", ".module"))]
    require(max(ordered.index(path) for path in physical_metadata) < min(ordered.index(path) for path in root_metadata), "root metadata precedes physical metadata")
    require(
        all(ordered.index(path.replace(".pom", ".module")) > ordered.index(path) for path in ordered if path.endswith(".pom")),
        ".module marker is not last within a GAV tier",
    )
    print("test-compose-mirror: PASS payload -> physical metadata -> root metadata ordering")


def update_entry(manifest: dict[str, Any], relative: str, path: Path) -> None:
    entry = next(item for item in manifest["files"] if item["path"] == relative)
    body = path.read_bytes()
    entry["size"] = len(body)
    entry["sha256"] = hashlib.sha256(body).hexdigest()


class RecordingWriter:
    def __init__(self, statuses: list[int] | None = None) -> None:
        self.statuses = list(statuses or [])
        self.calls: list[tuple[str, bytes]] = []

    def put(self, relative: str, body: bytes) -> int:
        self.calls.append((relative, body))
        return self.statuses.pop(0) if self.statuses else 201


def publication_contract_teeth(manifest: dict[str, Any], authority_bytes: Path, root: Path) -> None:
    plan = {
        "schema": 1,
        "task": 120,
        "manifestSha256": mirror.sha256_file(MANIFEST_PATH),
        "fileCount": 32,
        "authentication": "none",
        "provenance": mirror.runner_provenance(),
        "remote": {
            "decision": "publish-all-absent",
            "existing": [],
            "missing": sorted(mirror.manifest_entries(manifest)),
            "divergent": [],
            "unexpected": [],
            "listingMismatch": [],
            "scopePrimaryCounts": {scope: 0 for scope in mirror.TARGET_SCOPES},
        },
    }
    plan_path = root / "all-absent-plan.json"
    plan_path.write_text(json.dumps(plan), encoding="utf-8")
    mirror.load_publish_contract(MANIFEST_PATH, authority_bytes, plan_path)

    writer = RecordingWriter()
    receipt = root / "writer-receipt.json"
    mirror.publish(
        MANIFEST_PATH,
        authority_bytes,
        plan_path,
        receipt,
        writer_factory=lambda _base, _username, _token: writer,
    )
    expected_order = sorted(mirror.manifest_entries(manifest), key=mirror.publication_priority)
    require([path for path, _body in writer.calls] == expected_order, "PUT-only writer order changed")
    require(
        len(writer.calls) == 32 and all("authority-manifest" not in path for path, _body in writer.calls),
        "task #120 wrote a completion marker or non-primary path",
    )
    receipt_value = json.loads(receipt.read_text())
    require(receipt_value["existingCount"] == 0 and receipt_value["uploadedCount"] == 32, "writer receipt count changed")
    require(receipt_value["uploadedPaths"] == expected_order, "writer receipt path order changed")
    require(
        receipt_value["publicationBoundary"] == "32-maven-primaries-only-no-completion-marker",
        "writer receipt lost the task #120 publication boundary",
    )

    existing = expected_order[:9]
    changed = copy.deepcopy(plan)
    changed["remote"]["decision"] = "resume-partial-exact"
    changed["remote"]["existing"] = existing
    changed["remote"]["missing"] = [path for path in expected_order if path not in existing]
    changed_path = root / "partial-plan.json"
    changed_path.write_text(json.dumps(changed), encoding="utf-8")
    resume_writer = RecordingWriter()
    resume_receipt = root / "resume-writer-receipt.json"
    mirror.publish(
        MANIFEST_PATH,
        authority_bytes,
        changed_path,
        resume_receipt,
        writer_factory=lambda _base, _username, _token: resume_writer,
    )
    require(
        [path for path, _body in resume_writer.calls]
        == sorted(changed["remote"]["missing"], key=mirror.publication_priority),
        "resumable writer did not PUT only planned missing paths",
    )
    resume_value = json.loads(resume_receipt.read_text())
    require(resume_value["existingCount"] == 9 and resume_value["uploadedCount"] == 23, "resume receipt count changed")
    require(set(resume_value["existingPaths"]) == set(existing), "resume receipt lost identical existing paths")

    changed = copy.deepcopy(plan)
    first = changed["remote"]["missing"].pop(0)
    changed["remote"]["divergent"] = [{"path": first, "remoteSize": 1, "remoteSha256": "0" * 64}]
    changed_path = root / "divergent-plan.json"
    changed_path.write_text(json.dumps(changed), encoding="utf-8")
    divergent_writer = RecordingWriter()
    expect_failure(
        lambda: mirror.publish(
            MANIFEST_PATH,
            authority_bytes,
            changed_path,
            root / "divergent-receipt.json",
            writer_factory=lambda _base, _username, _token: divergent_writer,
        ),
        "writer plan contains conflicts",
        "divergent remote bytes stop before PUT",
    )
    require(divergent_writer.calls == [], "divergent plan reached the writer")

    changed = copy.deepcopy(plan)
    changed["remote"]["decision"] = "noop-complete-identical"
    changed["remote"]["existing"] = list(changed["remote"]["missing"])
    changed["remote"]["missing"] = []
    changed_path = root / "complete-plan.json"
    changed_path.write_text(json.dumps(changed), encoding="utf-8")
    expect_failure(
        lambda: mirror.load_publish_contract(MANIFEST_PATH, authority_bytes, changed_path),
        "writer plan is not publishable",
        "complete-identical state skips the writer",
    )

    conflict_writer = RecordingWriter([409])
    expect_failure(
        lambda: mirror.publish(
            MANIFEST_PATH,
            authority_bytes,
            plan_path,
            root / "conflict-receipt.json",
            writer_factory=lambda _base, _username, _token: conflict_writer,
        ),
        "HTTP 409",
        "create-only occupied path",
    )
    require(len(conflict_writer.calls) == 1, "create-only conflict retried or continued")

    changed = copy.deepcopy(plan)
    changed["manifestSha256"] = "0" * 64
    changed_path = root / "wrong-manifest-plan.json"
    changed_path.write_text(json.dumps(changed), encoding="utf-8")
    expect_failure(
        lambda: mirror.load_publish_contract(MANIFEST_PATH, authority_bytes, changed_path),
        "not bound to the manifest bytes",
        "plan/manifest digest binding",
    )

    changed = copy.deepcopy(plan)
    changed["provenance"]["runId"] = "another-run"
    changed_path = root / "wrong-run-plan.json"
    changed_path.write_text(json.dumps(changed), encoding="utf-8")
    expect_failure(
        lambda: mirror.load_publish_contract(MANIFEST_PATH, authority_bytes, changed_path),
        "not bound to this source/run tuple",
        "plan/source-run tuple binding",
    )

    changed = copy.deepcopy(plan)
    changed["remote"]["missing"].pop()
    changed_path = root / "incomplete-plan.json"
    changed_path.write_text(json.dumps(changed), encoding="utf-8")
    expect_failure(
        lambda: mirror.load_publish_contract(MANIFEST_PATH, authority_bytes, changed_path),
        "does not cover the exact 32 paths",
        "incomplete writer plan",
    )
    print("test-compose-mirror: PASS manifest/plan/staging binding and resumable immutable Maven writer teeth")


def semantic_mutations(authority_bytes: Path) -> None:
    manifest = mirror.load_manifest(MANIFEST_PATH)
    mirror.validate_staged_bytes(manifest, authority_bytes)
    print("test-compose-mirror: PASS live authority semantic positive control")
    with tempfile.TemporaryDirectory(prefix="compose-authority-semantics.") as temporary:
        root = Path(temporary)
        publication_contract_teeth(manifest, authority_bytes, root)

        changed_bytes = root / "license"
        shutil.copytree(authority_bytes, changed_bytes)
        changed_manifest = copy.deepcopy(manifest)
        pom_rel = next(path for path in mirror.manifest_entries(manifest) if path.endswith("runtime-1.7.3-kuikly2.pom"))
        pom = changed_bytes / pom_rel
        pom.write_text(pom.read_text().replace(mirror.APACHE_NAME, "Changed License"), encoding="utf-8")
        update_entry(changed_manifest, pom_rel, pom)
        expect_failure(
            lambda: mirror.validate_staged_bytes(changed_manifest, changed_bytes),
            "POM license name changed",
            "POM license semantics",
        )

        changed_bytes = root / "mapping"
        shutil.copytree(authority_bytes, changed_bytes)
        changed_manifest = copy.deepcopy(manifest)
        module_rel = next(path for path in mirror.manifest_entries(manifest) if path.endswith("runtime-1.7.3-kuikly2.module"))
        module = changed_bytes / module_rel
        value = json.loads(module.read_text())
        variant = next(item for item in value["variants"] if item["name"] == "ohosArm64ApiElements-published")
        variant["available-at"]["version"] = "changed"
        module.write_text(json.dumps(value), encoding="utf-8")
        update_entry(changed_manifest, module_rel, module)
        expect_failure(
            lambda: mirror.validate_staged_bytes(changed_manifest, changed_bytes),
            "root OHOS version mapping changed",
            "root-to-physical mapping semantics",
        )

        changed_bytes = root / "klib"
        shutil.copytree(authority_bytes, changed_bytes)
        klib_rel = next(path for path in mirror.manifest_entries(manifest) if path.endswith("annotation-ohosarm64-1.7.3-kuikly2.klib"))
        klib = changed_bytes / klib_rel
        replacement = klib.with_suffix(".mutated")
        with zipfile.ZipFile(klib) as source, zipfile.ZipFile(replacement, "w") as target:
            for info in source.infolist():
                body = source.read(info.filename)
                if info.filename == "default/manifest":
                    body = body.decode().replace("2.0.21-KBA-003", "2.0.21-KBA-010").encode()
                target.writestr(info, body)
        replacement.replace(klib)
        expect_failure(
            lambda: mirror.validate_klibs(manifest, changed_bytes),
            "KLIB compiler version changed",
            "KLIB compiler identity",
        )
    print("test-compose-mirror: PASS semantic causal mutations")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--authority-bytes", type=Path)
    arguments = parser.parse_args()
    test_manifest_admission()
    test_transports()
    test_planner_states()
    test_writer_order()
    if arguments.authority_bytes is not None:
        semantic_mutations(arguments.authority_bytes.resolve())
    print("test-compose-mirror: PASS all contract teeth")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"test-compose-mirror: FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
