#!/usr/bin/env python3
"""Fail closed if sample-only NetworkDemo leaks into the production SDK."""

from __future__ import annotations

import argparse
import copy
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path


DEMO_PACKAGE_PATH = "com/tencent/kmm/network/demo"
DEMO_PACKAGE_DECLARATION = "package com.tencent.kmm.network.demo"


@dataclass
class Snapshot:
    production_sources: dict[str, str]
    demo_source: str
    demo_test: str
    demo_build: str
    normal_settings: str
    ohos_settings: str
    android_build: str
    swift_source: str
    xcode_project: str
    ios_workflow: str
    network_workflow: str
    publication_files: dict[str, str]
    network_metadata_names: tuple[str, ...] | None
    network_metadata_bytes: bytes | None
    demo_jar_names: tuple[str, ...] | None
    demo_jar_bytes: bytes | None
    staged_publication_names: tuple[str, ...] | None
    staged_publication_bytes: bytes | None


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def one_file(pattern: str, root: Path) -> Path:
    matches = sorted(root.glob(pattern))
    if len(matches) != 1:
        raise ValueError(f"expected one {pattern!r} under {root}, found {len(matches)}")
    return matches[0]


def zip_snapshot(path: Path) -> tuple[tuple[str, ...], bytes]:
    with zipfile.ZipFile(path) as archive:
        names = tuple(sorted(archive.namelist()))
        payload = b"\n".join(archive.read(name) for name in names if not name.endswith("/"))
    return names, payload


def load_staged_publication(repository: Path) -> tuple[tuple[str, ...], bytes]:
    group_root = repository / "com" / "tencent" / "kuiklybase"
    files = sorted(path for path in group_root.rglob("*") if path.is_file())
    if not files:
        raise ValueError(f"no staged com.tencent.kuiklybase publication files under {repository}")
    names: list[str] = []
    payloads: list[bytes] = []
    for path in files:
        relative = str(path.relative_to(repository))
        names.append(relative)
        if path.suffix in {".aar", ".jar", ".klib"}:
            archive_names, archive_bytes = zip_snapshot(path)
            names.extend(f"{relative}!/{name}" for name in archive_names)
            payloads.append(archive_bytes)
        elif path.suffix not in {".md5", ".sha1", ".sha256", ".sha512"}:
            payloads.append(path.read_bytes())
    return tuple(names), b"\n".join(payloads)


def load_snapshot(
    network_root: Path,
    require_built: bool,
    staged_repository: Path | None,
) -> Snapshot:
    production_sources = {
        str(path.relative_to(network_root)): read(path)
        for path in sorted((network_root / "network" / "src").glob("*Main/**/*.kt"))
    }
    publication_paths = (
        network_root / "scripts" / "network-publication-manifest.sh",
        network_root / "scripts" / "publish-github-packages.sh",
        network_root.parent / ".github" / "workflows" / "publish-network-github-packages.yml",
    )

    metadata_names: tuple[str, ...] | None = None
    metadata_bytes: bytes | None = None
    demo_names: tuple[str, ...] | None = None
    demo_bytes: bytes | None = None
    staged_names: tuple[str, ...] | None = None
    staged_bytes: bytes | None = None
    if require_built:
        metadata_jar = one_file("network/build/libs/network-metadata-*.jar", network_root)
        demo_jar = one_file(
            "network-demo/build/intermediates/compile_library_classes_jar/debug/"
            "bundleLibCompileToJarDebug/classes.jar",
            network_root,
        )
        metadata_names, metadata_bytes = zip_snapshot(metadata_jar)
        demo_names, demo_bytes = zip_snapshot(demo_jar)
    if staged_repository is not None:
        staged_names, staged_bytes = load_staged_publication(staged_repository)

    return Snapshot(
        production_sources=production_sources,
        demo_source=read(
            network_root
            / "network-demo/src/commonMain/kotlin/com/tencent/kmm/network/demo/NetworkDemo.kt"
        ),
        demo_test=read(
            network_root
            / "network-demo/src/commonTest/kotlin/com/tencent/kmm/network/demo/NetworkDemoTest.kt"
        ),
        demo_build=read(network_root / "network-demo/build.gradle.kts"),
        normal_settings=read(network_root / "settings.gradle.kts"),
        ohos_settings=read(network_root / "settings.ohos.gradle.kts"),
        android_build=read(network_root / "androidApp/build.gradle.kts"),
        swift_source=read(network_root / "iosApp/iosApp/ContentView.swift"),
        xcode_project=read(network_root / "iosApp/iosApp.xcodeproj/project.pbxproj"),
        ios_workflow=read(network_root.parent / ".github/workflows/networkkmm-ios-demo.yml"),
        network_workflow=read(network_root.parent / ".github/workflows/networkkmm-tests.yml"),
        publication_files={str(path.relative_to(network_root.parent)): read(path) for path in publication_paths},
        network_metadata_names=metadata_names,
        network_metadata_bytes=metadata_bytes,
        demo_jar_names=demo_names,
        demo_jar_bytes=demo_bytes,
        staged_publication_names=staged_names,
        staged_publication_bytes=staged_bytes,
    )


def violations(snapshot: Snapshot) -> list[str]:
    errors: list[str] = []
    for path, text in snapshot.production_sources.items():
        if "NetworkDemo" in text or DEMO_PACKAGE_DECLARATION in text:
            errors.append(f"production :network source contains demo API: {path}")

    if DEMO_PACKAGE_DECLARATION not in snapshot.demo_source or "class NetworkDemo" not in snapshot.demo_source:
        errors.append(":network-demo commonMain lacks the NetworkDemo positive control")
    if DEMO_PACKAGE_DECLARATION not in snapshot.demo_test or "class NetworkDemoTest" not in snapshot.demo_test:
        errors.append(":network-demo commonTest lacks the NetworkDemoTest positive control")
    if "maven-publish" in snapshot.demo_build or "`maven-publish`" in snapshot.demo_build:
        errors.append(":network-demo must not apply maven-publish")
    if "signing" in snapshot.demo_build:
        errors.append(":network-demo must not apply publication signing")
    if snapshot.normal_settings.count('include(":network-demo")') != 1:
        errors.append("normal settings must include :network-demo exactly once")
    if "network-demo" in snapshot.ohos_settings:
        errors.append("OHOS settings must not include :network-demo")
    if 'implementation(project(":network-demo"))' not in snapshot.android_build:
        errors.append("androidApp must consume the sample-only :network-demo project")
    if "import networkDemo" not in snapshot.swift_source or "import network\n" in snapshot.swift_source:
        errors.append("iosApp must import the demo-only networkDemo framework")
    if ":network-demo:embedAndSignAppleFrameworkForXcode" not in snapshot.xcode_project:
        errors.append("iosApp Xcode build phase must embed :network-demo")
    required_ios_workflow_fragments = (
        '"NetworkKMM/network-demo/**"',
        ":network-demo:linkDebugFrameworkIosSimulatorArm64",
        "networkDemo.framework",
    )
    for fragment in required_ios_workflow_fragments:
        if fragment not in snapshot.ios_workflow:
            errors.append(f"iOS demo workflow missing {fragment}")
    if ":network:syncFramework" in snapshot.ios_workflow:
        errors.append("iOS demo workflow still builds the published :network framework as its facade")
    isolated_git_home = (
        'git_trust_home="$(mktemp -d '
        '"$RUNNER_TEMP/networkkmm-demo-git-trust.XXXXXX")"'
    )
    isolated_git_config = (
        'HOME="$git_trust_home" git config --global --add '
        'safe.directory "$GITHUB_WORKSPACE"'
    )
    isolated_source_read = 'source_sha="$(HOME="$git_trust_home" git rev-parse HEAD)"'
    isolated_git_trust_block = "\n          ".join(
        (isolated_git_home, isolated_git_config, isolated_source_read)
    )
    if snapshot.network_workflow.count(isolated_git_trust_block) != 1:
        errors.append(
            "staged Maven workflow must create one isolated git HOME and use it for workspace trust and HEAD"
        )
    if snapshot.network_workflow.count("git_trust_home") != 3:
        errors.append("staged Maven workflow git trust HOME must have exactly one create/config/read lifecycle")
    if snapshot.network_workflow.count("git config --global") != 1:
        errors.append("staged Maven workflow must have exactly one HOME-scoped global git trust write")
    if snapshot.network_workflow.count("safe.directory") != 1:
        errors.append("staged Maven workflow must trust exactly the checked-out workspace")
    if "git config --system" in snapshot.network_workflow:
        errors.append("staged Maven workflow must not write system git trust")
    if "git -c safe.directory" in snapshot.network_workflow:
        errors.append("staged Maven workflow must not use ineffective command-scoped git trust")
    for path, text in snapshot.publication_files.items():
        if "network-demo" in text:
            errors.append(f"publication planner/discovery references demo-only module: {path}")

    if snapshot.network_metadata_names is not None:
        if any(DEMO_PACKAGE_PATH in name and not name.endswith("/") for name in snapshot.network_metadata_names):
            errors.append(":network metadata archive contains the demo package")
        if snapshot.network_metadata_bytes is not None and b"NetworkDemo" in snapshot.network_metadata_bytes:
            errors.append(":network metadata archive contains NetworkDemo bytes")
    if snapshot.demo_jar_names is not None:
        if not any(name.endswith(f"{DEMO_PACKAGE_PATH}/NetworkDemo.class") for name in snapshot.demo_jar_names):
            errors.append(":network-demo Android classes jar lacks NetworkDemo.class positive control")
        if snapshot.demo_jar_bytes is None or b"NetworkDemo" not in snapshot.demo_jar_bytes:
            errors.append(":network-demo Android classes jar lacks NetworkDemo bytes positive control")
    if snapshot.staged_publication_names is not None:
        if any("network-demo" in name for name in snapshot.staged_publication_names):
            errors.append("staged production Maven repository contains a network-demo coordinate or member")
        if any(
            DEMO_PACKAGE_PATH in name and not name.endswith("/")
            for name in snapshot.staged_publication_names
        ):
            errors.append("staged production Maven repository contains the demo package")
        if snapshot.staged_publication_bytes is not None and b"NetworkDemo" in snapshot.staged_publication_bytes:
            errors.append("staged production Maven repository contains NetworkDemo bytes")

    return errors


def require_mutation_rejected(name: str, baseline: Snapshot, mutate) -> None:
    candidate = copy.deepcopy(baseline)
    mutate(candidate)
    if not violations(candidate):
        raise AssertionError(f"mutation unexpectedly passed: {name}")


def run_mutation_teeth(snapshot: Snapshot) -> None:
    mutations = (
        (
            "production-source-leak",
            lambda item: item.production_sources.__setitem__(
                "network/src/commonMain/kotlin/Leak.kt",
                f"{DEMO_PACKAGE_DECLARATION}\nclass NetworkDemo",
            ),
        ),
        ("missing-demo-source", lambda item: setattr(item, "demo_source", "package sample")),
        ("publish-plugin", lambda item: setattr(item, "demo_build", item.demo_build + "\n`maven-publish`\n")),
        ("normal-settings-edge", lambda item: setattr(item, "normal_settings", item.normal_settings.replace('include(":network-demo")', ""))),
        ("ohos-settings-edge", lambda item: setattr(item, "ohos_settings", item.ohos_settings + '\ninclude(":network-demo")\n')),
        ("android-consumer-edge", lambda item: setattr(item, "android_build", item.android_build.replace('implementation(project(":network-demo"))', ""))),
        ("swift-import-edge", lambda item: setattr(item, "swift_source", item.swift_source.replace("import networkDemo", "import network"))),
        ("xcode-framework-edge", lambda item: setattr(item, "xcode_project", item.xcode_project.replace(":network-demo:embedAndSignAppleFrameworkForXcode", ":network:embedAndSignAppleFrameworkForXcode"))),
        ("hosted-framework-edge", lambda item: setattr(item, "ios_workflow", item.ios_workflow.replace(":network-demo:linkDebugFrameworkIosSimulatorArm64", ":network:syncFramework"))),
        (
            "publication-discovery-leak",
            lambda item: item.publication_files.__setitem__(
                "NetworkKMM/scripts/network-publication-manifest.sh",
                item.publication_files["NetworkKMM/scripts/network-publication-manifest.sh"]
                + "\n:network-demo:publishAllPublicationsToGithubPackagesRepository\n",
            ),
        ),
        (
            "drop-staged-git-trust-home",
            lambda item: setattr(
                item,
                "network_workflow",
                item.network_workflow.replace(
                    'git_trust_home="$(mktemp -d '
                    '"$RUNNER_TEMP/networkkmm-demo-git-trust.XXXXXX")"\n',
                    "",
                ),
            ),
        ),
        (
            "drop-staged-git-trust-config",
            lambda item: setattr(
                item,
                "network_workflow",
                item.network_workflow.replace(
                    'HOME="$git_trust_home" git config --global --add '
                    'safe.directory "$GITHUB_WORKSPACE"\n',
                    "",
                ),
            ),
        ),
        (
            "wrong-staged-config-home",
            lambda item: setattr(
                item,
                "network_workflow",
                item.network_workflow.replace(
                    'HOME="$git_trust_home" git config --global',
                    'HOME="$RUNNER_TEMP" git config --global',
                ),
            ),
        ),
        (
            "wrong-staged-readback-home",
            lambda item: setattr(
                item,
                "network_workflow",
                item.network_workflow.replace(
                    'source_sha="$(HOME="$git_trust_home" git rev-parse HEAD)"',
                    'source_sha="$(HOME="$RUNNER_TEMP" git rev-parse HEAD)"',
                ),
            ),
        ),
        (
            "wrong-staged-safe-directory",
            lambda item: setattr(
                item,
                "network_workflow",
                item.network_workflow.replace(
                    'safe.directory "$GITHUB_WORKSPACE"',
                    'safe.directory "$RUNNER_TEMP"',
                ),
            ),
        ),
        (
            "unscoped-global-staged-trust",
            lambda item: setattr(
                item,
                "network_workflow",
                item.network_workflow.replace(
                    'HOME="$git_trust_home" git config --global',
                    "git config --global",
                ),
            ),
        ),
        (
            "system-staged-trust",
            lambda item: setattr(
                item,
                "network_workflow",
                item.network_workflow.replace(
                    'HOME="$git_trust_home" git config --global',
                    "git config --system",
                ),
            ),
        ),
        (
            "command-scoped-staged-trust",
            lambda item: setattr(
                item,
                "network_workflow",
                item.network_workflow.replace(
                    'source_sha="$(HOME="$git_trust_home" git rev-parse HEAD)"',
                    'source_sha="$(git -c safe.directory="$GITHUB_WORKSPACE" rev-parse HEAD)"',
                ),
            ),
        ),
    )
    for name, mutate in mutations:
        require_mutation_rejected(name, snapshot, mutate)

    if snapshot.network_metadata_names is not None:
        require_mutation_rejected(
            "built-network-metadata-leak",
            snapshot,
            lambda item: setattr(
                item,
                "network_metadata_names",
                item.network_metadata_names + (f"{DEMO_PACKAGE_PATH}/0_demo.knm",),
            ),
        )
        require_mutation_rejected(
            "missing-built-demo-positive",
            snapshot,
            lambda item: (
                setattr(item, "demo_jar_names", tuple()),
                setattr(item, "demo_jar_bytes", b""),
            ),
        )
    if snapshot.staged_publication_names is not None:
        require_mutation_rejected(
            "staged-publication-leak",
            snapshot,
            lambda item: setattr(
                item,
                "staged_publication_names",
                item.staged_publication_names
                + ("com/tencent/kuiklybase/network-demo/0/network-demo-0.jar",),
            ),
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--require-built",
        action="store_true",
        help="also require and inspect the built :network metadata and :network-demo Android jars",
    )
    parser.add_argument(
        "--staged-repository",
        type=Path,
        help="inspect an isolated Maven repository containing staged production :network artifacts",
    )
    args = parser.parse_args()

    network_root = Path(__file__).resolve().parents[1]
    try:
        snapshot = load_snapshot(
            network_root,
            require_built=args.require_built,
            staged_repository=args.staged_repository,
        )
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"network-demo boundary input error: {error}", file=sys.stderr)
        return 1

    errors = violations(snapshot)
    if errors:
        for error in errors:
            print(f"network-demo boundary violation: {error}", file=sys.stderr)
        return 1
    try:
        run_mutation_teeth(snapshot)
    except AssertionError as error:
        print(f"network-demo boundary mutation failure: {error}", file=sys.stderr)
        return 1

    scope = " + built archives" if args.require_built else ""
    mutation_count = 20 if args.require_built else 18
    if args.staged_repository is not None:
        scope += " + staged Maven publication"
        mutation_count += 1
    print(f"network-demo sample-only boundary{scope}: PASS ({mutation_count} mutations rejected)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
