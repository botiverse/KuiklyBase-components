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
    "manifest": "NetworkKMM/scripts/network-publication-manifest.sh",
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
        workflow.count("network-publication-state.sh verify") == 4,
        "verifier-count",
        "every publication lane must verify post-publish convergence",
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
        'resolved_source_sha="$(publication_git rev-parse HEAD)"' in publisher,
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
            "raft_base_tasks",
            "selected_publish_tasks",
        ),
        "state": (
            "validated_required_tasks",
            "auth_args",
            "github_missing",
            "raft_missing",
            "requested_lane_tasks",
            "missing_union",
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
        and 'github_missing_text=""\nraft_missing_text=""' in state
        and state.count('github_missing_text="${github_missing[*]}"') == 1
        and state.count('raft_missing_text="${raft_missing[*]}"') == 1
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
        "RAFT_ARTIFACTS_PUBLISH_TOKEN is required; dual publication fails closed." in publisher,
        "raft-token-required",
        "missing Raft credentials must fail before Gradle",
    )
    require(
        'selected_publish_tasks+=("$(network_raft_task_for "$task")")' in publisher,
        "paired-task-derivation",
        "Raft task graph must derive from each selected GitHub publication",
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
        "GitHub Packages positive control failed" in state
        and "Raft Artifacts scope positive control failed" in state,
        "state-positive-controls",
        "absence probes require destination-specific positive controls",
    )
    require(
        "partial immutable publication" in state and "refusing an unsafe overwrite retry" in state,
        "partial-file-fence",
        "mixed file state within one publication must fail closed",
    )

    manifest = files["manifest"]
    require(
        '${github_task/GithubPackagesRepository/RaftArtifactsRepository}' in manifest,
        "raft-task-mapping",
        "repository task mapping must preserve the exact publication name",
    )
    require(
        manifest.count("-sources.jar") >= 8,
        "manifest-sources",
        "every artifact-bearing publication must verify a sources artifact",
    )

    convention = files["convention"]
    require(
        'name = "raftArtifacts"' in convention,
        "raft-repository",
        "Gradle convention must create RaftArtifactsRepository tasks",
    )
    require(
        'orElse("https://maven.artifacts.botiverse.dev")' in convention,
        "raft-url",
        "Raft Maven URL must be the production registry",
    )
    require(
        'providers.environmentVariable("RAFT_ARTIFACTS_PUBLISH_TOKEN")' in convention,
        "raft-gradle-token",
        "Gradle repository credentials must consume the protected secret",
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
        ("event-sha", mutate_once(files, "publisher", 'resolved_source_sha="$(publication_git rev-parse HEAD)"', 'resolved_source_sha="${GITHUB_SHA}"'), "source-sha-resolution"),
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
        ("drop-task-pair", mutate_once(files, "publisher", 'selected_publish_tasks+=("$(network_raft_task_for "$task")")'), "paired-task-derivation"),
        ("drop-normal-convention", mutate_once(files, "normal_root", '        apply(from = rootProject.file("gradle/raft-artifacts-publishing.gradle.kts"))\n'), "normal-convention"),
        ("drop-android-sources", mutate_once(files, "android_runtime", "            withSourcesJar()\n"), "android-sources"),
        ("drop-post-verify", mutate_once(files, "workflow", "        run: NetworkKMM/scripts/network-publication-state.sh verify\n"), "verifier-count"),
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
