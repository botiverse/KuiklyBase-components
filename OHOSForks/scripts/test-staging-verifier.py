#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Callable
from xml.etree import ElementTree


Mutation = Callable[[Path, Path], None]
MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def copy_fork_contract(source: Path, destination: Path) -> None:
    (destination / "scripts").mkdir(parents=True)
    (destination / "patches").mkdir()
    for relative in (
        "gradle.properties",
        "release-spec.json",
        "source-lock.json",
        "scripts/verify-staging.py",
        "patches/kotlinx.atomicfu.patch",
        "patches/kotlinx-coroutines.patch",
    ):
        source_path = source / relative
        destination_path = destination / relative
        shutil.copy2(source_path, destination_path)


def pom_path(repository: Path, artifact: str, version: str) -> Path:
    return repository / "org" / "jetbrains" / "kotlinx" / artifact / version / f"{artifact}-{version}.pom"


def module_path(repository: Path, artifact: str, version: str) -> Path:
    return repository / "org" / "jetbrains" / "kotlinx" / artifact / version / f"{artifact}-{version}.module"


def update_pom(path: Path, update: Callable[[ElementTree.Element], None]) -> None:
    tree = ElementTree.parse(path)
    update(tree.getroot())
    tree.write(path, encoding="utf-8", xml_declaration=True)


def changed_pom_carrier_sha(repository: Path, fork: Path) -> None:
    del fork

    def update(root: ElementTree.Element) -> None:
        value = root.find("m:properties/m:dev.raft.carrierSha", MAVEN_NS)
        require(value is not None, "carrier-SHA mutation target is missing")
        value.text = "0" * 40

    update_pom(pom_path(repository, "atomicfu", "0.23.2-raft.1"), update)


def changed_pom_scm_repository(repository: Path, fork: Path) -> None:
    del fork

    def update(root: ElementTree.Element) -> None:
        value = root.find("m:scm/m:url", MAVEN_NS)
        require(value is not None, "SCM repository mutation target is missing")
        value.text = "https://example.invalid/not-the-carrier.git"

    update_pom(pom_path(repository, "atomicfu", "0.23.2-raft.1"), update)


def changed_pom_upstream_repository(repository: Path, fork: Path) -> None:
    del fork

    def update(root: ElementTree.Element) -> None:
        value = root.find("m:properties/m:dev.raft.upstreamRepository", MAVEN_NS)
        require(value is not None, "upstream repository mutation target is missing")
        value.text = "https://example.invalid/not-the-upstream.git"

    update_pom(pom_path(repository, "atomicfu", "0.23.2-raft.1"), update)


def changed_dependency_to_kba(repository: Path, fork: Path) -> None:
    del fork

    def update(root: ElementTree.Element) -> None:
        value = root.find("m:dependencies/m:dependency/m:version", MAVEN_NS)
        require(value is not None, "dependency mutation target is missing")
        value.text = "0.23.2-KBA-001"

    update_pom(pom_path(repository, "kotlinx-coroutines-core", "1.8.0-raft.1"), update)


def deleted_referenced_target(repository: Path, fork: Path) -> None:
    del fork
    path = module_path(repository, "atomicfu-ohosarm64", "0.23.2-raft.1")
    require(path.is_file(), "target-module mutation target is missing")
    path.unlink()


def injected_non_ohos_variant(repository: Path, fork: Path) -> None:
    del fork
    path = module_path(repository, "atomicfu", "0.23.2-raft.1")
    value = json.loads(path.read_text(encoding="utf-8"))
    value["variants"].append(
        {
            "name": "iosArm64ApiElements-published",
            "attributes": {
                "org.jetbrains.kotlin.native.target": "ios_arm64",
                "org.jetbrains.kotlin.platform.type": "native",
            },
        }
    )
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def modified_artifact_bytes(repository: Path, fork: Path) -> None:
    del fork
    path = (
        repository
        / "org"
        / "jetbrains"
        / "kotlinx"
        / "atomicfu"
        / "0.23.2-raft.1"
        / "atomicfu-0.23.2-raft.1.jar"
    )
    require(path.is_file(), "artifact-byte mutation target is missing")
    with path.open("ab") as stream:
        stream.write(b"mutation")


def unexpected_release_file(repository: Path, fork: Path) -> None:
    del fork
    path = repository / "org" / "jetbrains" / "kotlinx" / "atomicfu" / "0.23.2-raft.1" / "unexpected.bin"
    path.write_bytes(b"unexpected")


def unexpected_coordinate(repository: Path, fork: Path) -> None:
    del fork
    path = repository / "org" / "example" / "unexpected" / "1.0" / "unexpected-1.0.pom"
    path.parent.mkdir(parents=True)
    path.write_text("<project/>\n", encoding="utf-8")


def deleted_provenance_field(repository: Path, fork: Path) -> None:
    del fork

    def update(root: ElementTree.Element) -> None:
        properties = root.find("m:properties", MAVEN_NS)
        value = root.find("m:properties/m:dev.raft.patchSha256", MAVEN_NS)
        require(properties is not None and value is not None, "provenance mutation target is missing")
        properties.remove(value)

    update_pom(pom_path(repository, "atomicfu", "0.23.2-raft.1"), update)


def changed_patch_bytes(repository: Path, fork: Path) -> None:
    del repository
    path = fork / "patches" / "kotlinx.atomicfu.patch"
    require(path.is_file(), "patch-byte mutation target is missing")
    with path.open("ab") as stream:
        stream.write(b"\n# mutation\n")


def missing_patch_checksum(repository: Path, fork: Path) -> None:
    del repository
    path = fork / "source-lock.json"
    value = json.loads(path.read_text(encoding="utf-8"))
    del value["sources"]["atomicfu"]["patchSha256"]
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def changed_prepared_tree_lock(repository: Path, fork: Path) -> None:
    del repository
    path = fork / "source-lock.json"
    value = json.loads(path.read_text(encoding="utf-8"))
    value["sources"]["atomicfu"]["preparedTree"] = "0" * 40
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def reversed_klib_entry_order(repository: Path, fork: Path) -> None:
    del fork
    path = (
        repository
        / "org"
        / "jetbrains"
        / "kotlinx"
        / "atomicfu-ohosarm64"
        / "0.23.2-raft.1"
        / "atomicfu-ohosarm64-0.23.2-raft.1.klib"
    )
    require(path.is_file(), "KLIB-order mutation target is missing")
    with zipfile.ZipFile(path, "r") as source:
        entries = [(info, source.read(info)) for info in source.infolist()]
    require(len(entries) > 1, "KLIB-order mutation lacks its multi-entry positive control")
    temporary = path.with_name(path.name + ".reversed")
    with zipfile.ZipFile(temporary, "w", allowZip64=True) as output:
        for info, payload in reversed(entries):
            output.writestr(info, payload, compress_type=info.compress_type, compresslevel=9)
    temporary.replace(path)


MUTATIONS: list[tuple[str, Mutation, str]] = [
    ("changed POM carrier SHA", changed_pom_carrier_sha, "POM provenance mismatch"),
    ("changed POM SCM repository", changed_pom_scm_repository, "POM SCM repository mismatch"),
    ("changed POM upstream repository", changed_pom_upstream_repository, "POM provenance mismatch"),
    ("changed dependency back to KBA", changed_dependency_to_kba, "POM dependency mismatch"),
    ("deleted target referenced by available-at", deleted_referenced_target, "release file set mismatch"),
    ("injected non-OHOS variant", injected_non_ohos_variant, "root module is not metadata+OHOS-only"),
    ("modified artifact bytes", modified_artifact_bytes, "module size mismatch"),
    ("unexpected release file", unexpected_release_file, "release file set mismatch"),
    ("unexpected coordinate", unexpected_coordinate, "unexpected directory"),
    ("deleted provenance field", deleted_provenance_field, "POM provenance mismatch"),
    ("changed source patch bytes", changed_patch_bytes, "source patch checksum mismatch"),
    ("missing source patch checksum", missing_patch_checksum, "source-lock field set mismatch"),
    ("changed prepared-tree lock", changed_prepared_tree_lock, "prepared-tree drift"),
    ("reversed canonical KLIB entry order", reversed_klib_entry_order, "canonical KLIB entry order drifted"),
]


def run_verifier(fork: Path, repository: Path, carrier_sha: str, manifest: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(fork / "scripts" / "verify-staging.py"),
            "--repository",
            str(repository),
            "--carrier-sha",
            carrier_sha,
            "--manifest",
            str(manifest),
        ],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Exercise every fail-closed staging-verifier boundary")
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--carrier-sha", required=True)
    arguments = parser.parse_args()

    source_fork = Path(__file__).resolve().parent.parent
    source_repository = arguments.repository.resolve()
    require(source_repository.is_dir(), f"staging repository is missing: {source_repository}")

    with tempfile.TemporaryDirectory(prefix="ohos-forks-verifier-mutations.") as temporary:
        temporary_root = Path(temporary)

        baseline_root = temporary_root / "baseline"
        baseline_fork = baseline_root / "fork"
        baseline_repository = baseline_root / "repository"
        copy_fork_contract(source_fork, baseline_fork)
        shutil.copytree(source_repository, baseline_repository, symlinks=True)
        baseline = run_verifier(
            baseline_fork,
            baseline_repository,
            arguments.carrier_sha,
            baseline_root / "manifest.json",
        )
        require(
            baseline.returncode == 0 and "verified 20 files" in baseline.stdout,
            f"baseline did not positively exercise the verifier:\n{baseline.stdout}{baseline.stderr}",
        )
        print("test-staging-verifier: PASS baseline")

        for index, (name, mutate, expected_reason) in enumerate(MUTATIONS, 1):
            case_root = temporary_root / f"mutation-{index:02d}"
            fork = case_root / "fork"
            repository = case_root / "repository"
            copy_fork_contract(source_fork, fork)
            shutil.copytree(source_repository, repository, symlinks=True)
            mutate(repository, fork)
            result = run_verifier(fork, repository, arguments.carrier_sha, case_root / "manifest.json")
            output = result.stdout + result.stderr
            require(result.returncode != 0, f"mutation unexpectedly passed: {name}")
            require(
                expected_reason in output,
                f"mutation failed without its named reason: {name}; expected {expected_reason!r}; got:\n{output}",
            )
            print(f"test-staging-verifier: PASS mutation {index}/{len(MUTATIONS)}: {name}")

    print(f"test-staging-verifier: verified baseline plus {len(MUTATIONS)} fail-closed mutations")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"test-staging-verifier: {error}", file=sys.stderr)
        raise SystemExit(1)
