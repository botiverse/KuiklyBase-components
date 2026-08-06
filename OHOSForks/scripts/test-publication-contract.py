#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import io
import importlib.util
import json
import shutil
import sys
import tempfile
import urllib.request
import urllib.response
from email.message import Message
from pathlib import Path
from types import ModuleType
from typing import Callable


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def load_module(path: Path, name: str) -> ModuleType:
    specification = importlib.util.spec_from_file_location(name, path)
    require(specification is not None and specification.loader is not None, f"cannot load contract module: {path}")
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def expect_failure(action: Callable[[], object], expected: str, name: str) -> None:
    try:
        action()
    except Exception as error:  # The loaded scripts own their concrete error types.
        require(expected in str(error), f"{name} failed without its named reason {expected!r}: {error}")
        print(f"test-publication-contract: PASS mutation: {name}")
        return
    raise RuntimeError(f"mutation unexpectedly passed: {name}")


class ScriptedHttpsHandler(urllib.request.BaseHandler):
    handler_order = 100

    def __init__(self, status: int, body: bytes = b"", location: str | None = None) -> None:
        self.status = status
        self.body = body
        self.location = location
        self.requests: list[urllib.request.Request] = []

    def https_open(self, request: urllib.request.Request) -> urllib.response.addinfourl:
        self.requests.append(request)
        headers = Message()
        if self.location is not None:
            headers.add_header("Location", self.location)
        response = urllib.response.addinfourl(io.BytesIO(self.body), headers, request.full_url, self.status)
        response.msg = "scripted response"
        return response


def main() -> int:
    script_root = Path(__file__).resolve().parent
    http_module = load_module(script_root / "publication_http.py", "ohos_forks_publication_http_contract")
    state_module = load_module(script_root / "publication-state.py", "ohos_forks_publication_state")
    publish_module = load_module(script_root / "publish-staging.py", "ohos_forks_publish_staging")
    require(
        state_module.RepositoryClient is publish_module.RepositoryClient,
        "planner/readback and upload do not share one repository transport",
    )
    print("test-publication-contract: PASS one shared planner/readback/upload transport")

    payloads = {f"path/file-{index}.bin": f"payload-{index}".encode("utf-8") for index in range(20)}
    entries = [
        {"path": path, "size": len(body), "sha256": hashlib.sha256(body).hexdigest()}
        for path, body in sorted(payloads.items())
    ]

    absent = state_module.classify_files(entries, lambda path: None, "fixture")
    require(absent["state"] == "absent" and len(absent["missing"]) == 20, "absent planner control failed")
    complete = state_module.classify_files(entries, lambda path: payloads[path], "fixture")
    require(complete["state"] == "complete" and len(complete["existing"]) == 20, "complete planner control failed")
    existing_paths = set(sorted(payloads)[:7])
    partial = state_module.classify_files(
        entries,
        lambda path: payloads[path] if path in existing_paths else None,
        "fixture",
    )
    require(
        partial["state"] == "partial-exact"
        and len(partial["existing"]) == 7
        and len(partial["missing"]) == 13,
        "partial-exact planner control failed",
    )
    print("test-publication-contract: PASS absent/complete/partial-exact planner controls")

    fixture_origin = "https://repository.example.test"
    success_handler = ScriptedHttpsHandler(200, b"known bytes")
    success_opener = urllib.request.build_opener(success_handler, http_module.RejectRedirectHandler())
    success_client = http_module.RepositoryClient(
        "fixture",
        fixture_origin,
        "fixture-user",
        "fixture-token",
        expected_base_url=fixture_origin,
        opener=success_opener,
    )
    require(success_client.fetch("known/path.pom") == b"known bytes", "GET transport positive control failed")
    success_client.upload("new/path.bin", b"upload bytes")
    require(
        len(success_handler.requests) == 2
        and success_handler.requests[0].get_method() == "GET"
        and success_handler.requests[1].get_method() == "PUT"
        and success_handler.requests[1].data == b"upload bytes",
        "GET/PUT transport positive control did not exercise both request paths",
    )
    print("test-publication-contract: PASS shared GET/PUT transport positive control")

    for method in ("GET", "PUT"):
        redirect_handler = ScriptedHttpsHandler(302, location="https://redirect.example.test/escaped")
        redirect_opener = urllib.request.build_opener(redirect_handler, http_module.RejectRedirectHandler())
        redirect_client = http_module.RepositoryClient(
            "fixture",
            fixture_origin,
            "fixture-user",
            "fixture-token",
            expected_base_url=fixture_origin,
            opener=redirect_opener,
        )
        if method == "GET":
            action = lambda: redirect_client.fetch("known/path.pom")
        else:
            action = lambda: redirect_client.upload("new/path.bin", b"upload bytes")
        expect_failure(action, "redirect rejected before another request", f"{method} redirect rejection")
        require(
            len(redirect_handler.requests) == 1
            and redirect_handler.requests[0].get_method() == method
            and redirect_handler.requests[0].full_url.startswith(fixture_origin + "/"),
            f"{method} redirect attempted a second or non-reviewed-origin request",
        )
    print("test-publication-contract: PASS no redirect forwards credentials or publication bytes")

    expect_failure(
        lambda: http_module.RepositoryClient(
            "fixture",
            "https://changed.example.test",
            "fixture-user",
            "fixture-token",
            expected_base_url=fixture_origin,
        ),
        "must exactly match the reviewed endpoint",
        "changed initial repository origin",
    )

    expect_failure(
        lambda: state_module.classify_files(
            entries,
            lambda path: b"divergent" if path == entries[0]["path"] else payloads[path],
            "fixture",
        ),
        "existing file checksum diverges",
        "existing byte divergence",
    )

    with tempfile.TemporaryDirectory(prefix="ohos-forks-publication-contract.") as temporary:
        root = Path(temporary)
        repository = root / "repository"
        repository.mkdir()
        for relative, body in payloads.items():
            path = repository / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(body)
        manifest = {
            "schema": 1,
            "carrierSha": "a" * 40,
            "sourceLockSha256": "b" * 64,
            "releaseSpecSha256": "c" * 64,
            "files": entries,
        }
        manifest_path = root / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        manifest_sha = publish_module.sha256(manifest_path)
        plan = {
            "schema": 1,
            "carrierSha": manifest["carrierSha"],
            "manifestSha256": manifest_sha,
            "destinations": {
                "raft": partial,
            },
        }
        plan_path = root / "plan.json"
        plan_path.write_text(json.dumps(plan, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        publish_module.validate_contract(repository, manifest_path, plan_path)
        print("test-publication-contract: PASS exact manifest/plan/staging binding")

        wrong_manifest_plan = json.loads(plan_path.read_text(encoding="utf-8"))
        wrong_manifest_plan["manifestSha256"] = "0" * 64
        wrong_manifest_plan_path = root / "wrong-manifest-plan.json"
        wrong_manifest_plan_path.write_text(json.dumps(wrong_manifest_plan) + "\n", encoding="utf-8")
        expect_failure(
            lambda: publish_module.validate_contract(repository, manifest_path, wrong_manifest_plan_path),
            "not bound to the exact manifest bytes",
            "changed manifest digest",
        )

        incomplete_plan = json.loads(plan_path.read_text(encoding="utf-8"))
        incomplete_plan["destinations"]["raft"]["missing"].pop()
        incomplete_plan_path = root / "incomplete-plan.json"
        incomplete_plan_path.write_text(json.dumps(incomplete_plan) + "\n", encoding="utf-8")
        expect_failure(
            lambda: publish_module.validate_contract(repository, manifest_path, incomplete_plan_path),
            "does not cover the exact manifest",
            "omitted planned path",
        )

        changed_repository = root / "changed-repository"
        shutil.copytree(repository, changed_repository)
        with (changed_repository / entries[0]["path"]).open("ab") as stream:
            stream.write(b"mutation")
        expect_failure(
            lambda: publish_module.validate_contract(changed_repository, manifest_path, plan_path),
            "staged release size drift",
            "changed staged bytes",
        )

    require(
        publish_module.publication_priority("org/example/library/1/library-1.jar")
        < publish_module.publication_priority("org/example/library-ohosarm64/1/library-ohosarm64-1.module")
        < publish_module.publication_priority("org/example/library/1/library-1.module"),
        "publication ordering must upload artifacts, then target metadata, then root metadata",
    )
    print("test-publication-contract: PASS non-dangling publication order")
    print("test-publication-contract: verified positive and fail-closed publication states")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"test-publication-contract: {error}", file=sys.stderr)
        raise SystemExit(1)
