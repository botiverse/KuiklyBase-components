#!/usr/bin/env python3
"""Static and mutation contract for NetworkKMM's two-destination release."""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Mapping


class ContractError(RuntimeError):
    def __init__(self, code: str, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code


def require(condition: bool, code: str, detail: str) -> None:
    if not condition:
        raise ContractError(code, detail)


FILES = {
    "workflow": ".github/workflows/publish-network-github-packages.yml",
    "test_workflow": ".github/workflows/networkkmm-tests.yml",
    "publisher": "NetworkKMM/scripts/publish-github-packages.sh",
    "state": "NetworkKMM/scripts/network-publication-state.sh",
    "curl_compat": "NetworkKMM/scripts/network-curl-compat.sh",
    "curl_compat_test": "NetworkKMM/scripts/test-network-curl-compat.sh",
    "manifest": "NetworkKMM/scripts/network-publication-manifest.sh",
    "mirror": "NetworkKMM/scripts/network-raft-mirror.py",
    "mirror_test": "NetworkKMM/scripts/test-network-raft-mirror.py",
    "convention": "NetworkKMM/gradle/raft-artifacts-publishing.gradle.kts",
    "normal_root": "NetworkKMM/build.gradle.kts",
    "ohos_root": "NetworkKMM/build.ohos.gradle.kts",
    "android_runtime": "NetworkKMM/network-android-curl-runtime/build.gradle.kts",
    "ohos_runtime": "NetworkKMM/network-ohos-runtime/build.gradle.kts",
}


TASK_COUNTS = {
    ":network:publishAndroidPublicationToGithubPackagesRepository": 1,
    ":network-android-curl-runtime:publishAndroidCurlRuntimePublicationToGithubPackagesRepository": 1,
    ":network:publishIosX64PublicationToGithubPackagesRepository": 1,
    ":network:publishIosArm64PublicationToGithubPackagesRepository": 1,
    ":network:publishIosSimulatorArm64PublicationToGithubPackagesRepository": 1,
    ":network:publishOhosArm64PublicationToGithubPackagesRepository": 1,
    ":network-ohos-runtime:publishAllPublicationsToGithubPackagesRepository": 1,
    ":network-ohos-runtime-gradle-plugin:publishAllPublicationsToGithubPackagesRepository": 1,
    # Once in the normal metadata lane and once in the independent OHOS tree.
    ":network:publishKotlinMultiplatformPublicationToGithubPackagesRepository": 2,
}


def load_files(root: Path) -> dict[str, str]:
    return {name: (root / relative).read_text() for name, relative in FILES.items()}


def validate(files: Mapping[str, str]) -> None:
    workflow = files["workflow"]
    require(
        workflow.startswith("name: Publish NetworkKMM to GitHub Packages and Raft Artifacts\n"),
        "workflow-name",
        "release workflow must name both destinations",
    )
    require(
        workflow.count("environment: raft-artifacts-production") == 4,
        "environment-count",
        "all four publication jobs must use the protected environment",
    )
    require(
        workflow.count("secrets.RAFT_ARTIFACTS_PUBLISH_TOKEN") == 4,
        "secret-count",
        "the Raft token must be injected only into each of the four publish steps",
    )
    require(
        workflow.count("network-publication-state.sh plan") == 4,
        "planner-count",
        "every publication lane must plan immutable state",
    )
    require(
        workflow.count("network-raft-mirror.py verify") == 4,
        "verifier-count",
        "every publication lane must anonymously verify Raft convergence",
    )
    require(
        workflow.count("NetworkKMM/scripts/publish-github-packages.sh") == 4,
        "publisher-count",
        "every publication lane must use the fail-closed dual publisher",
    )
    require(
        "needs.publish-ohos-tree.result" in workflow
        and "needs.publish-ios.result" in workflow
        and "needs.publish-android.result" in workflow
        and "needs.publish-kmp-metadata.result" in workflow,
        "terminal-results",
        "terminal gate must bind all four job results",
    )
    for task, expected_count in TASK_COUNTS.items():
        require(
            workflow.count(task) == expected_count,
            f"task-count:{task}",
            f"expected {expected_count} required-lane occurrence(s)",
        )

    publisher = files["publisher"]
    require(
        "publication_git status --porcelain=v1 --untracked-files=all" in publisher,
        "clean-checkout",
        "publisher must reject dirty tracked and untracked state",
    )
    require(
        'resolved_source_sha="$(publication_git -C "$PROJECT_DIR" rev-parse HEAD)"' in publisher,
        "source-sha-resolution",
        "source SHA must come from the publication checkout",
    )
    require(
        'HOME="$git_config_home" git config --global --add safe.directory "$REPOSITORY_DIR"' in publisher
        and 'HOME="$git_config_home" git "$@"' in publisher,
        "isolated-safe-directory",
        "container-safe Git provenance must not mutate the real global config",
    )
    state = files["state"]
    empty_array_expansions = {
        "publisher": (
            "validated_required_tasks",
            "github_publish_tasks",
            "selected_publish_tasks",
        ),
        "state": (
            "validated_required_tasks",
            "auth_args",
            "github_missing",
            "requested_lane_tasks",
        ),
    }
    guarded_empty_arrays = True
    for file_name, array_names in empty_array_expansions.items():
        script = files[file_name]
        for array_name in array_names:
            direct = f'"${{{array_name}[@]}}"'
            guarded = f'${{{array_name}[@]+"${{{array_name}[@]}}"}}'
            guarded_empty_arrays = (
                guarded_empty_arrays
                and script.count(guarded) > 0
                and script.count(direct) == script.count(guarded)
            )
    guarded_empty_arrays = (
        guarded_empty_arrays
        and 'github_missing_text=""' in state
        and state.count('github_missing_text="${github_missing[*]}"') == 1
    )
    require(
        "declare -A" not in publisher
        and "[[ -v" not in publisher
        and "declare -A" not in state
        and "[[ -v" not in state
        and guarded_empty_arrays,
        "bash32-portability",
        "macOS scripts must avoid Bash 4 features and nounset-unsafe empty-array expansion",
    )
    require(
        "GITHUB_SHA" not in publisher and "github.sha" not in publisher,
        "source-sha-event-forbidden",
        "event metadata must not provide publication provenance",
    )
    require(
        "RAFT_ARTIFACTS_PUBLISH_TOKEN" not in publisher,
        "gradle-no-raft-token",
        "Gradle publisher must never receive the Raft credential",
    )
    require(
        "RaftArtifactsRepository" not in publisher,
        "gradle-no-raft-task",
        "Gradle publisher must never select Raft repository tasks",
    )
    require(
        '"-PnetworkSourceSha=$NETWORK_SOURCE_SHA"' in publisher,
        "source-sha-gradle-binding",
        "exact checkout SHA must bind POM generation",
    )
    require(
        "-PgithubPackagesToken" not in publisher and "-PraftArtifactsToken" not in publisher,
        "secret-process-args",
        "repository tokens must remain environment credentials, not process arguments",
    )

    require(
        "GitHub Packages positive control failed" in state,
        "state-positive-controls",
        "GitHub authority absence probes require a positive control",
    )
    require(
        "partial immutable publication" in state and "refusing an unsafe overwrite retry" in state,
        "partial-file-fence",
        "mixed file state within one publication must fail closed",
    )
    curl_compat = files["curl_compat"]
    require(
        "if command curl --retry-all-errors --version >/dev/null 2>&1; then" in curl_compat,
        "curl-feature-probe",
        "retry-all-errors must be enabled by an executable curl feature probe",
    )
    require(
        "NETWORK_CURL_RETRY_ARGS=(--retry 2)" in curl_compat
        and "NETWORK_CURL_RETRY_ARGS+=(--retry-all-errors)" in curl_compat,
        "curl-retry-policy",
        "every curl needs two baseline retries and supported curls need retry-all-errors",
    )
    curl_wrapper_block = (
        "command curl \\\n"
        "    --silent --show-error \\\n"
        "    --connect-timeout 15 --max-time 60 \\\n"
        '    "${NETWORK_CURL_RETRY_ARGS[@]}"'
    )
    require(
        curl_wrapper_block in curl_compat,
        "curl-wrapper-policy",
        "the shared wrapper must retain diagnostics, timeouts, and the resolved retry policy",
    )
    require(
        '    "$@"' in curl_compat,
        "curl-argument-forwarding",
        "the shared wrapper must forward every caller-provided argument unchanged",
    )
    require(
        'source "$SCRIPT_DIR/network-curl-compat.sh"' in state
        and state.count("network_resolve_curl_retry_args") == 1
        and state.count("network_curl --") == 1
        and "--retry-all-errors" not in state
        and "--retry 2" not in state,
        "state-curl-wrapper",
        "the GitHub authority probe must use the resolved compatibility wrapper",
    )

    curl_compat_test = files["curl_compat_test"]
    require(
        "NETWORK_FAKE_CURL_MODE=old" in curl_compat_test
        and "command curl --retry-all-errors --version" in curl_compat_test
        and "run_case old false" in curl_compat_test
        and "run_case modern true" in curl_compat_test
        and 'cmp -s "$expected_log" "$fake_log"' in curl_compat_test,
        "curl-executable-contract",
        "the executable contract must reproduce old-curl rejection and compare exact old/modern arguments",
    )

    manifest = files["manifest"]
    require(
        manifest.count("-sources.jar") >= 8,
        "manifest-sources",
        "every artifact-bearing publication must verify a sources artifact",
    )

    convention = files["convention"]
    require(
        'name = "raftArtifacts"' not in convention,
        "no-raft-repository",
        "Gradle must not expose a Raft repository",
    )
    require(
        "RAFT_ARTIFACTS_PUBLISH_TOKEN" not in convention,
        "no-raft-gradle-token",
        "Gradle must not consume the Raft writer credential",
    )
    mirror = files["mirror"]
    require(
        '"If-None-Match": "*"' in mirror
        and 'status == 409' in mirror
        and 'decision = "resume-partial-exact"' in mirror
        and "network-publication-manifest.sh" in mirror,
        "resumable-primary-mirror",
        "Raft mirror must be create-only, partial-resumable and manifest-bound",
    )
    require(
        "RAFT_ARTIFACTS_PUBLISH_TOKEN" in mirror
        and workflow.count("NETWORK_REQUIRED_PATHS_FILE: /tmp/network-required.json") == 12,
        "mirror-credential-boundary",
        "the mirror must own writer credentials and exact path snapshots",
    )
    require(
        'properties.put("dev.raft.sourceSha", networkSourceSha)' in convention
        and "tag.set(networkSourceSha)" in convention,
        "pom-provenance",
        "every Maven POM must carry the exact source SHA as property and SCM tag",
    )

    apply_line = 'apply(from = rootProject.file("gradle/raft-artifacts-publishing.gradle.kts"))'
    require(files["normal_root"].count(apply_line) == 1, "normal-convention", "normal tree must apply convention")
    require(files["ohos_root"].count(apply_line) == 1, "ohos-convention", "OHOS tree must apply convention")
    require(
        "withSourcesJar()" in files["android_runtime"]
        and 'name == "sourceReleaseJar"' in files["android_runtime"]
        and "network/src/androidMain/cpp" in files["android_runtime"]
        and "ohosApp/pbcurlwrapper/src/main/cpp" in files["android_runtime"],
        "android-sources",
        "Android runtime needs its wrapper and JNI sources",
    )
    require(
        "ohosRuntimeSourcesJar" in files["ohos_runtime"]
        and "artifact(ohosRuntimeSourcesJar)" in files["ohos_runtime"],
        "ohos-sources",
        "OHOS native runtime needs an attached sources artifact",
    )

    test_workflow = files["test_workflow"]
    publication_contract_job = test_workflow.split("  publication-contract:\n", 1)[1].split(
        "\n  publication-contract-ios:\n", 1
    )[0]
    require(
        '".github/workflows/publish-network-github-packages.yml"' in test_workflow,
        "test-path-trigger",
        "editing the release workflow must trigger its contract",
    )
    require(
        "verify-network-dual-publication-contract.py" in test_workflow,
        "hosted-contract",
        "Hosted CI must execute the static and mutation contract",
    )
    require(
        "image: ghcr.io/bytemain/harmony-next-pipeline-docker/harmonyos-ci-image:v6.1.1.280"
        in publication_contract_job
        and "run: NetworkKMM/scripts/test-network-curl-compat.sh" in publication_contract_job,
        "hosted-curl-compat",
        "Hosted must execute the curl compatibility test inside the pinned old Harmony image",
    )
    require(
        "publishIosX64PublicationToMavenLocal" in test_workflow
        and "network_required_paths_for" in test_workflow
        and "dev.raft.sourceSha" in test_workflow,
        "hosted-ios-artifacts",
        "macOS Hosted must publish and inspect the real isolated iOS Maven files and provenance",
    )


def mutate_once(files: Mapping[str, str], key: str, old: str, new: str = "") -> dict[str, str]:
    require(old in files[key], "mutation-setup", f"mutation target absent in {key}: {old}")
    mutated = dict(files)
    mutated[key] = files[key].replace(old, new, 1)
    return mutated


def run_mutations(files: Mapping[str, str]) -> None:
    first_task = next(iter(TASK_COUNTS))
    mutations = [
        ("drop-environment", mutate_once(files, "workflow", "    environment: raft-artifacts-production\n"), "environment-count"),
        ("drop-raft-secret", mutate_once(files, "workflow", "          RAFT_ARTIFACTS_PUBLISH_TOKEN: ${{ secrets.RAFT_ARTIFACTS_PUBLISH_TOKEN }}\n"), "secret-count"),
        ("drop-required-task", mutate_once(files, "workflow", f"        {first_task}\n"), f"task-count:{first_task}"),
        ("event-sha", mutate_once(files, "publisher", 'resolved_source_sha="$(publication_git -C "$PROJECT_DIR" rev-parse HEAD)"', 'resolved_source_sha="${GITHUB_SHA}"'), "source-sha-resolution"),
        ("bash4-only", mutate_once(files, "publisher", "#!/usr/bin/env bash\n", "#!/usr/bin/env bash\ndeclare -A bash4_only=()\n"), "bash32-portability"),
        (
            "bash32-empty-array",
            mutate_once(
                files,
                "publisher",
                '${validated_required_tasks[@]+"${validated_required_tasks[@]}"}',
                '"${validated_required_tasks[@]}"',
            ),
            "bash32-portability",
        ),
        ("drop-normal-convention", mutate_once(files, "normal_root", '        apply(from = rootProject.file("gradle/raft-artifacts-publishing.gradle.kts"))\n'), "normal-convention"),
        ("drop-android-sources", mutate_once(files, "android_runtime", "            withSourcesJar()\n"), "android-sources"),
        ("drop-post-verify", mutate_once(files, "workflow", "        run: python3 NetworkKMM/scripts/network-raft-mirror.py verify --receipt /tmp/network-authority.json --output /tmp/network-terminal.json\n"), "verifier-count"),
        (
            "drop-curl-feature-probe",
            mutate_once(
                files,
                "curl_compat",
                "if command curl --retry-all-errors --version >/dev/null 2>&1; then",
                "if true; then",
            ),
            "curl-feature-probe",
        ),
        (
            "restore-unconditional-retry-all-errors",
            mutate_once(
                files,
                "curl_compat",
                '    "${NETWORK_CURL_RETRY_ARGS[@]}" \\\n',
                "    --retry 2 --retry-all-errors \\\n",
            ),
            "curl-wrapper-policy",
        ),
        (
            "drop-baseline-retry",
            mutate_once(files, "curl_compat", "NETWORK_CURL_RETRY_ARGS=(--retry 2)", "NETWORK_CURL_RETRY_ARGS=()"),
            "curl-retry-policy",
        ),
        (
            "drop-curl-caller-arguments",
            mutate_once(files, "curl_compat", '    "$@"\n', ""),
            "curl-argument-forwarding",
        ),
        (
            "drop-hosted-curl-contract",
            mutate_once(files, "test_workflow", "        run: NetworkKMM/scripts/test-network-curl-compat.sh\n"),
            "hosted-curl-compat",
        ),
    ]
    for name, mutated, expected_code in mutations:
        try:
            validate(mutated)
        except ContractError as error:
            require(
                error.code == expected_code,
                "mutation-wrong-tooth",
                f"{name} hit {error.code}, expected {expected_code}",
            )
        else:
            raise ContractError("mutation-survived", name)
    print(f"mutation teeth: {len(mutations)}/{len(mutations)} rejected")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--skip-mutations", action="store_true")
    args = parser.parse_args()
    files = load_files(args.root)
    validate(files)
    print("dual-publication contract: PASS")
    if not args.skip_mutations:
        run_mutations(files)


if __name__ == "__main__":
    main()
