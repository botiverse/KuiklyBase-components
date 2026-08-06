#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any, Callable


class ContractError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ContractError(f"cannot read JSON {path}: {error}") from error
    require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def require_once(text: str, needle: str, context: str) -> None:
    require(text.count(needle) == 1, f"{context} must contain exactly one {needle!r}")


def verify_contract(ci: str, publication: str, build_driver: str, spec: dict[str, Any]) -> None:
    image = spec.get("harmonyImage")
    require(isinstance(image, str) and "@sha256:" in image, "release spec must pin the Harmony image by digest")
    require(ci.count(image) == 2, "CI workflow must bind both its container and runtime declaration to the pinned image")
    require(
        publication.count(image) == 2,
        "publication workflow must bind both its container and runtime declaration to the pinned image",
    )

    require_once(ci, "  pull_request:\n", "CI workflow")
    require_once(ci, "  push:\n", "CI workflow")
    require("      - master\n" in ci, "CI push gate must target master")
    require("OHOSForks/scripts/build-and-verify.sh" in ci, "CI workflow must execute the closed build driver")
    require("OHOSForks/scripts/test-publication-contract.py" in ci, "CI workflow must exercise publication controls")
    require("OHOSForks/scripts/publish-staging.py" not in ci, "CI workflow must not expose a publication write path")
    exact_ci_carrier = "${{ github.event.pull_request.head.sha || github.sha }}"
    require_once(ci, f"          ref: {exact_ci_carrier}\n", "CI workflow")
    require_once(ci, f"          name: ohos-forks-{exact_ci_carrier}\n", "CI workflow")
    exact_evidence_root = "          path: ${{ runner.temp }}/ohos-forks-output/\n"
    require_once(ci, exact_evidence_root, "CI workflow")

    require_once(publication, "  workflow_dispatch:\n", "publication workflow")
    require("  push:\n" not in publication, "publication workflow must not have a push trigger")
    require("  pull_request:\n" not in publication, "publication workflow must not have a pull-request trigger")
    require("environment: raft-artifacts-production" in publication, "publication workflow must use the protected production environment")
    require_once(publication, "permissions:\n  contents: read\n\n", "publication workflow")
    require("ref: ${{ inputs.source_sha }}" in publication, "publication checkout is not bound to source_sha")
    require_once(publication, exact_evidence_root, "publication workflow")
    require(
        'test "$(HOME="$git_home" git rev-parse origin/master)" = "$REQUESTED_SOURCE_SHA"' in publication,
        "publication workflow must require the requested SHA to be current master",
    )
    require(
        'test "$(sha256sum "$output/manifest/release-manifest.json" | awk \'{print $1}\')" = "$REQUESTED_MANIFEST_SHA256"'
        in publication,
        "publication workflow is not bound to the fresh Hosted manifest digest",
    )

    components = spec.get("components")
    require(isinstance(components, dict), "release spec components are missing")
    atomicfu_version = components.get("atomicfu", {}).get("version")
    coroutines_version = components.get("coroutines", {}).get("version")
    confirmation = f"publish atomicfu-{atomicfu_version} coroutines-{coroutines_version}"
    require(publication.count(confirmation) == 2, "publication confirmation is not exactly bound to both release versions")

    plan_position = publication.find("publication-state.py plan")
    upload_position = publication.find("publish-staging.py")
    verify_position = publication.find("publication-state.py verify")
    require(
        0 <= plan_position < upload_position < verify_position,
        "publication workflow must plan before upload and read back after upload",
    )
    require("OHOSForks/scripts/build-and-verify.sh" in publication, "publication workflow must rebuild the source-owned release")
    require("continue-on-error" not in publication, "publication workflow must not weaken a release gate")
    require("|| true" not in publication, "publication workflow must not suppress a release-gate failure")

    canonicalizer_self_test = '\n"$script_dir/test-klib-canonicalizer.py"\n'
    canonicalizer_call = '"$script_dir/canonicalize-klibs.py" \\\n'
    require_once(build_driver, canonicalizer_self_test, "closed build driver")
    require_once(build_driver, canonicalizer_call, "closed build driver")
    atomicfu_publish = build_driver.find("clean :atomicfu:publishToMavenLocal")
    coroutines_publish = build_driver.find(":kotlinx-coroutines-core:publishToMavenLocal")
    canonicalize_position = build_driver.find(canonicalizer_call)
    staging_verify = build_driver.find('"$script_dir/verify-staging.py"')
    require(
        0 <= atomicfu_publish < coroutines_publish < canonicalize_position < staging_verify,
        "closed build driver must canonicalize KLIBs after both publications and before staging verification",
    )

    for name, workflow in (("CI", ci), ("publication", publication)):
        action_refs = re.findall(r"uses:\s+actions/(?:checkout|upload-artifact)@([^\s#]+)", workflow)
        require(action_refs, f"{name} workflow has no pinned first-party action controls")
        require(
            all(re.fullmatch(r"[0-9a-f]{40}", reference) is not None for reference in action_refs),
            f"{name} workflow contains an unpinned first-party action reference",
        )


def run_self_test(ci: str, publication: str, build_driver: str, spec: dict[str, Any]) -> None:
    canonicalizer_call = '"$script_dir/canonicalize-klibs.py" \\\n'
    mutations: list[tuple[str, str, str, str, str]] = [
        (
            "removed CI pull-request trigger",
            ci.replace("  pull_request:\n", "  workflow_dispatch:\n", 1),
            publication,
            build_driver,
            "CI workflow must contain exactly one",
        ),
        (
            "changed pinned image",
            ci.replace(spec["harmonyImage"], "ghcr.io/example/image@sha256:" + "0" * 64, 1),
            publication,
            build_driver,
            "CI workflow must bind",
        ),
        (
            "added automatic publication trigger",
            ci,
            publication.replace("  workflow_dispatch:\n", "  push:\n", 1),
            build_driver,
            "publication workflow must contain exactly one",
        ),
        (
            "removed protected environment",
            ci,
            publication.replace("environment: raft-artifacts-production", "environment: unprotected", 1),
            build_driver,
            "protected production environment",
        ),
        (
            "removed manifest binding",
            ci,
            publication.replace(
                '          test "$(sha256sum "$output/manifest/release-manifest.json" | awk \'{print $1}\')" = "$REQUESTED_MANIFEST_SHA256"\n',
                "",
                1,
            ),
            build_driver,
            "fresh Hosted manifest digest",
        ),
        (
            "removed Raft readback",
            ci,
            publication.replace("publication-state.py verify", "publication-state.py omitted", 1),
            build_driver,
            "plan before upload and read back",
        ),
        (
            "unpinned checkout action",
            ci.replace("actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683", "actions/checkout@v4", 1),
            publication,
            build_driver,
            "unpinned first-party action",
        ),
        (
            "removed exact PR-head checkout",
            ci.replace(
                "          ref: ${{ github.event.pull_request.head.sha || github.sha }}\n",
                "          ref: ${{ github.sha }}\n",
                1,
            ),
            publication,
            build_driver,
            "CI workflow must contain exactly one '          ref:",
        ),
        (
            "changed exact-carrier artifact binding",
            ci.replace(
                "          name: ohos-forks-${{ github.event.pull_request.head.sha || github.sha }}\n",
                "          name: ohos-forks-${{ github.sha }}\n",
                1,
            ),
            publication,
            build_driver,
            "CI workflow must contain exactly one '          name: ohos-forks-",
        ),
        (
            "narrowed immutable evidence artifact",
            ci.replace(
                "          path: ${{ runner.temp }}/ohos-forks-output/\n",
                "          path: ${{ runner.temp }}/ohos-forks-output/abi/\n",
                1,
            ),
            publication,
            build_driver,
            "CI workflow must contain exactly one '          path: ${{ runner.temp }}/ohos-forks-output/",
        ),
        (
            "removed KLIB canonicalization",
            ci,
            publication,
            build_driver.replace(canonicalizer_call, '"$script_dir/canonicalize-klibs-omitted.py" \\\n', 1),
            "closed build driver must contain exactly one",
        ),
    ]
    for name, mutated_ci, mutated_publication, mutated_build_driver, expected in mutations:
        try:
            verify_contract(mutated_ci, mutated_publication, mutated_build_driver, spec)
        except ContractError as error:
            require(expected in str(error), f"workflow mutation failed without its named reason: {name}: {error}")
            print(f"verify-workflow-contract: PASS mutation: {name}")
            continue
        raise ContractError(f"workflow mutation unexpectedly passed: {name}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify that OHOS fork publication stays manual, exact-bound and Raft-only")
    parser.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args()
    repository_root = Path(__file__).resolve().parents[2]
    fork_root = repository_root / "OHOSForks"
    ci_path = repository_root / ".github" / "workflows" / "ohos-forks-ci.yml"
    publication_path = repository_root / ".github" / "workflows" / "publish-ohos-forks.yml"
    build_driver_path = fork_root / "scripts" / "build-and-verify.sh"
    try:
        spec = load_json(fork_root / "release-spec.json")
        ci = ci_path.read_text(encoding="utf-8")
        publication = publication_path.read_text(encoding="utf-8")
        build_driver = build_driver_path.read_text(encoding="utf-8")
        verify_contract(ci, publication, build_driver, spec)
        print("verify-workflow-contract: PASS pinned CI and manual exact-bound Raft-only publication")
        if arguments.self_test:
            run_self_test(ci, publication, build_driver, spec)
            print("verify-workflow-contract: PASS 11 fail-closed workflow mutations")
        return 0
    except (OSError, ContractError) as error:
        print(f"verify-workflow-contract: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
