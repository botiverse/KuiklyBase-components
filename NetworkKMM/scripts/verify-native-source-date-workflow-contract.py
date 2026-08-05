#!/usr/bin/env python3
"""Fail closed when a native producer stops depending on the fixed epoch."""

from __future__ import annotations

from pathlib import Path
from typing import Dict


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
EPOCH_PATH = "NetworkKMM/scripts/native-source-date-epoch.txt"
ANDROID_WORKFLOW_PATH = ".github/workflows/networkkmm-android-native.yml"
IOS_WORKFLOW_PATH = ".github/workflows/networkkmm-ios-native.yml"
OHOS_WORKFLOW_PATH = ".github/workflows/networkkmm-ohos-native.yml"
TESTS_WORKFLOW_PATH = ".github/workflows/networkkmm-tests.yml"


class ContractViolation(RuntimeError):
    pass


def read_workflows() -> Dict[str, str]:
    return {
        "android": (REPOSITORY_ROOT / ANDROID_WORKFLOW_PATH).read_text(encoding="utf-8"),
        "ios": (REPOSITORY_ROOT / IOS_WORKFLOW_PATH).read_text(encoding="utf-8"),
        "ohos": (REPOSITORY_ROOT / OHOS_WORKFLOW_PATH).read_text(encoding="utf-8"),
        "tests": (REPOSITORY_ROOT / TESTS_WORKFLOW_PATH).read_text(encoding="utf-8"),
    }


def indented_block(document: str, header: str) -> str:
    lines = document.splitlines()
    try:
        start = lines.index(header)
    except ValueError as error:
        raise ContractViolation(f"missing YAML block: {header.strip()}") from error
    indentation = len(header) - len(header.lstrip())
    block = [header]
    for line in lines[start + 1 :]:
        if line.strip() and len(line) - len(line.lstrip()) <= indentation:
            break
        block.append(line)
    return "\n".join(block)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractViolation(message)


def require_epoch_trigger(document: str, event: str, platform: str) -> None:
    trigger = indented_block(document, f"  {event}:")
    require(EPOCH_PATH in trigger, f"{platform} {event} trigger omits {EPOCH_PATH}")


def require_epoch_cache(document: str, prefix: str, platform: str) -> None:
    cache_keys = [line.strip() for line in document.splitlines() if line.strip().startswith(f"key: {prefix}")]
    require(len(cache_keys) == 1, f"{platform} must have exactly one {prefix} cache key")
    require("hashFiles(" in cache_keys[0], f"{platform} cache key is not content-addressed")
    require(EPOCH_PATH in cache_keys[0], f"{platform} cache key omits {EPOCH_PATH}")


def validate(documents: Dict[str, str]) -> None:
    require_epoch_trigger(documents["android"], "pull_request", "Android")
    require_epoch_cache(documents["android"], "android-curl-", "Android")

    require_epoch_trigger(documents["ios"], "pull_request", "iOS")
    require_epoch_cache(documents["ios"], "ios-curl-", "iOS")

    require_epoch_trigger(documents["ohos"], "push", "OHOS")
    require_epoch_trigger(documents["ohos"], "pull_request", "OHOS")

    tests_trigger = indented_block(documents["tests"], "  pull_request:")
    for workflow_path in (ANDROID_WORKFLOW_PATH, IOS_WORKFLOW_PATH, OHOS_WORKFLOW_PATH):
        require(workflow_path in tests_trigger, f"contract tests do not trigger for {workflow_path}")

    ios_runtime_job = indented_block(documents["tests"], "  ios-curl-runtime-tests:")
    require(
        "NETWORKKMM_IOS_CURL_OPTIONAL_API_EXPECTATION: available" in ios_runtime_job,
        "committed iOS artifact lane must require the additive optional API surface",
    )


def remove_nth(document: str, needle: str, occurrence: int) -> str:
    start = 0
    for _ in range(occurrence):
        index = document.find(needle, start)
        if index < 0:
            raise AssertionError(f"mutation setup could not find occurrence {occurrence}: {needle}")
        start = index + len(needle)
    return document[:index] + document[index + len(needle) :]


def require_mutation_red(
    original: Dict[str, str],
    label: str,
    document_name: str,
    needle: str,
    occurrence: int = 1,
) -> None:
    mutated = dict(original)
    mutated[document_name] = remove_nth(mutated[document_name], needle, occurrence)
    try:
        validate(mutated)
    except ContractViolation:
        print(f"mutation rejected: {label}")
        return
    raise AssertionError(f"mutation unexpectedly passed: {label}")


def main() -> None:
    documents = read_workflows()
    validate(documents)

    # Each edge is independently executable: deleting a trigger, cache input,
    # contract-test trigger, or the committed-artifact expectation must go RED.
    mutations = (
        ("android-epoch-trigger", "android", EPOCH_PATH, 1),
        ("android-epoch-cache", "android", EPOCH_PATH, 2),
        ("ios-epoch-trigger", "ios", EPOCH_PATH, 1),
        ("ios-epoch-cache", "ios", EPOCH_PATH, 2),
        ("ohos-epoch-push-trigger", "ohos", EPOCH_PATH, 1),
        ("ohos-epoch-pr-trigger", "ohos", EPOCH_PATH, 2),
        ("android-contract-trigger", "tests", ANDROID_WORKFLOW_PATH, 1),
        ("ios-contract-trigger", "tests", IOS_WORKFLOW_PATH, 1),
        ("ohos-contract-trigger", "tests", OHOS_WORKFLOW_PATH, 1),
        (
            "ios-committed-optional-api-expectation",
            "tests",
            "NETWORKKMM_IOS_CURL_OPTIONAL_API_EXPECTATION: available",
            1,
        ),
    )
    for label, document_name, needle, occurrence in mutations:
        require_mutation_red(documents, label, document_name, needle, occurrence)

    print("Native source-date workflow contract PASS")


if __name__ == "__main__":
    main()
