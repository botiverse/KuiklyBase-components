#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any
from xml.etree import ElementTree


class VerificationError(RuntimeError):
    pass


CANONICAL_KLIB_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
CANONICAL_KLIB_EXTERNAL_ATTR = 0o600 << 16


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"cannot read JSON {path}: {error}") from error
    require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def digest_file(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_gradle_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        require("=" in line, f"invalid Gradle property at {path}:{line_number}")
        key, value = line.split("=", 1)
        require(key and key not in values, f"duplicate Gradle property {key!r} in {path}")
        values[key] = value
    return values


def component_path(repository: Path, group: str, artifact: str, version: str) -> Path:
    return repository.joinpath(*group.split("."), artifact, version)


def require_hex(value: Any, width: int, context: str) -> str:
    require(
        isinstance(value, str) and re.fullmatch(rf"[0-9a-f]{{{width}}}", value) is not None,
        f"{context} must be exactly {width} lowercase hexadecimal characters",
    )
    return value


def verify_source_lock_entry(
    fork_root: Path,
    name: str,
    component: dict[str, Any],
    source_lock: dict[str, Any],
) -> None:
    require(
        set(source_lock)
        == {
            "repository",
            "commit",
            "patch",
            "patchSha256",
            "preparedTree",
            "preparedTreeManifestSha256",
            "positiveControl",
        },
        f"source-lock field set mismatch for {name}: {sorted(source_lock)}",
    )
    repository = source_lock.get("repository")
    require(
        isinstance(repository, str) and re.fullmatch(r"https://github\.com/[^/]+/[^/]+\.git", repository) is not None,
        f"source repository must be an HTTPS GitHub .git URL for {name}",
    )
    commit = require_hex(source_lock.get("commit"), 40, f"source commit for {name}")
    patch_sha256 = require_hex(source_lock.get("patchSha256"), 64, f"patch SHA-256 for {name}")
    prepared_tree = require_hex(source_lock.get("preparedTree"), 40, f"prepared tree for {name}")
    prepared_manifest = require_hex(
        source_lock.get("preparedTreeManifestSha256"),
        64,
        f"prepared tree-manifest SHA-256 for {name}",
    )
    patch_relative = source_lock.get("patch")
    positive_control = source_lock.get("positiveControl")
    require(
        isinstance(patch_relative, str)
        and patch_relative
        and not Path(patch_relative).is_absolute()
        and ".." not in Path(patch_relative).parts,
        f"source patch path must stay inside OHOSForks for {name}",
    )
    require(
        isinstance(positive_control, str)
        and positive_control
        and not Path(positive_control).is_absolute()
        and ".." not in Path(positive_control).parts,
        f"positive-control path must stay inside the prepared source for {name}",
    )
    patch_path = fork_root / patch_relative
    require(patch_path.is_file() and not patch_path.is_symlink(), f"source patch is missing or unsafe for {name}: {patch_path}")
    require(sha256(patch_path) == patch_sha256, f"source patch checksum mismatch for {name}: {patch_path}")
    require(component.get("upstreamSha") == commit, f"upstream SHA drift for {name}")
    require(component.get("upstreamRepository") == repository, f"upstream repository drift for {name}")
    require(component.get("patchSha256") == patch_sha256, f"patch SHA drift for {name}")
    require(component.get("preparedTree") == prepared_tree, f"prepared-tree drift for {name}")
    require(
        component.get("preparedTreeManifestSha256") == prepared_manifest,
        f"prepared tree-manifest drift for {name}",
    )


def expected_filenames(artifact: str, version: str, target: bool) -> set[str]:
    prefix = f"{artifact}-{version}"
    if target:
        return {
            f"{prefix}.klib",
            f"{prefix}-cinterop-interop.klib",
            f"{prefix}-sources.jar",
            f"{prefix}.module",
            f"{prefix}.pom",
        }
    return {
        f"{prefix}.jar",
        f"{prefix}-sources.jar",
        f"{prefix}-kotlin-tooling-metadata.json",
        f"{prefix}.module",
        f"{prefix}.pom",
    }


def verify_exact_directory(path: Path, expected: set[str]) -> list[Path]:
    require(path.is_dir() and not path.is_symlink(), f"coordinate directory is missing: {path}")
    actual: set[str] = set()
    files: list[Path] = []
    for child in path.iterdir():
        require(child.is_file() and not child.is_symlink(), f"unexpected non-file in release directory: {child}")
        require(child.stat().st_size > 0, f"release file is empty: {child}")
        actual.add(child.name)
        files.append(child)
    require(actual == expected, f"release file set mismatch in {path}: expected {sorted(expected)}, got {sorted(actual)}")
    return files


def normalize_dependencies(value: Any, context: str) -> list[tuple[str, str, str]]:
    if value is None:
        return []
    require(isinstance(value, list), f"dependencies must be a list in {context}")
    normalized: list[tuple[str, str, str]] = []
    for dependency in value:
        require(isinstance(dependency, dict), f"dependency must be an object in {context}")
        version = dependency.get("version")
        require(isinstance(version, dict), f"dependency version must be an object in {context}")
        require(set(version) == {"requires"}, f"dependency version must use only 'requires' in {context}")
        require(
            set(dependency) == {"group", "module", "version"},
            f"unexpected dependency fields in {context}: {sorted(dependency)}",
        )
        normalized.append((dependency.get("group"), dependency.get("module"), version.get("requires")))
    require(all(all(isinstance(part, str) and part for part in item) for item in normalized), f"invalid dependency in {context}")
    return sorted(normalized)


def verify_file_descriptors(module_path: Path, variant: dict[str, Any], expected_urls: set[str]) -> None:
    files = variant.get("files") or []
    require(isinstance(files, list), f"variant files must be a list in {module_path}")
    actual_urls: set[str] = set()
    for descriptor in files:
        require(isinstance(descriptor, dict), f"file descriptor must be an object in {module_path}")
        url = descriptor.get("url")
        require(isinstance(url, str) and Path(url).name == url, f"unsafe file URL in {module_path}: {url!r}")
        file_path = module_path.parent / url
        require(file_path.is_file() and not file_path.is_symlink(), f"module points to missing release file: {file_path}")
        require(descriptor.get("size") == file_path.stat().st_size, f"module size mismatch for {file_path}")
        for field, algorithm in (("sha512", "sha512"), ("sha256", "sha256"), ("sha1", "sha1"), ("md5", "md5")):
            require(descriptor.get(field) == digest_file(file_path, algorithm), f"module {field} mismatch for {file_path}")
        actual_urls.add(url)
    require(actual_urls == expected_urls, f"variant file set mismatch in {module_path}: expected {sorted(expected_urls)}, got {sorted(actual_urls)}")


def verify_module(
    module_path: Path,
    group: str,
    root_artifact: str,
    target_artifact: str,
    version: str,
    expected_root_dependency: tuple[str, str, str] | None,
) -> None:
    module = load_json(module_path)
    component = module.get("component")
    require(isinstance(component, dict), f"module component is missing: {module_path}")
    expected_component = {"group": group, "module": root_artifact, "version": version}
    require(
        all(component.get(key) == value for key, value in expected_component.items()),
        f"module component identity mismatch: {module_path}",
    )
    variants = module.get("variants")
    require(isinstance(variants, list), f"module variants are missing: {module_path}")
    by_name = {variant.get("name"): variant for variant in variants if isinstance(variant, dict)}
    require(len(by_name) == len(variants), f"duplicate or invalid variant in {module_path}")

    is_target = module_path.name.startswith(f"{target_artifact}-")
    if is_target:
        expected_names = {"ohosArm64ApiElements-published", "ohosArm64SourcesElements-published"}
        require(set(by_name) == expected_names, f"target module is not OHOS-only: {module_path}: {sorted(by_name)}")
        expected_root_url = f"../../{root_artifact}/{version}/{root_artifact}-{version}.module"
        require(component.get("url") == expected_root_url, f"target component backlink mismatch: {module_path}")
        api_variant = by_name["ohosArm64ApiElements-published"]
        source_variant = by_name["ohosArm64SourcesElements-published"]
        require(api_variant.get("available-at") is None, f"target API variant must not redirect: {module_path}")
        require(source_variant.get("available-at") is None, f"target sources variant must not redirect: {module_path}")
        expected_deps = [] if expected_root_dependency is None else [expected_root_dependency]
        require(normalize_dependencies(api_variant.get("dependencies"), str(module_path)) == expected_deps, f"target dependency mismatch: {module_path}")
        require(normalize_dependencies(source_variant.get("dependencies"), str(module_path)) == [], f"target sources dependency mismatch: {module_path}")
        verify_file_descriptors(
            module_path,
            api_variant,
            {
                f"{target_artifact}-{version}.klib",
                f"{target_artifact}-{version}-cinterop-interop.klib",
            },
        )
        verify_file_descriptors(module_path, source_variant, {f"{target_artifact}-{version}-sources.jar"})
        for variant in (api_variant, source_variant):
            attributes = variant.get("attributes") or {}
            require(attributes.get("org.jetbrains.kotlin.native.target") == "ohos_arm64", f"target variant is not ohos_arm64: {module_path}")
            require(attributes.get("org.jetbrains.kotlin.platform.type") == "native", f"target variant is not native: {module_path}")
        return

    require(component.get("url") is None, f"root component must not redirect: {module_path}")
    expected_names = {
        "metadataApiElements",
        "metadataSourcesElements",
        "ohosArm64ApiElements-published",
        "ohosArm64SourcesElements-published",
    }
    require(set(by_name) == expected_names, f"root module is not metadata+OHOS-only: {module_path}: {sorted(by_name)}")
    expected_deps = [] if expected_root_dependency is None else [expected_root_dependency]
    require(
        normalize_dependencies(by_name["metadataApiElements"].get("dependencies"), str(module_path)) == expected_deps,
        f"root metadata dependency mismatch: {module_path}",
    )
    require(
        normalize_dependencies(by_name["metadataSourcesElements"].get("dependencies"), str(module_path)) == [],
        f"root metadata sources dependency mismatch: {module_path}",
    )
    verify_file_descriptors(module_path, by_name["metadataApiElements"], {f"{root_artifact}-{version}.jar"})
    verify_file_descriptors(module_path, by_name["metadataSourcesElements"], {f"{root_artifact}-{version}-sources.jar"})
    expected_available_at = {
        "url": f"../../{target_artifact}/{version}/{target_artifact}-{version}.module",
        "group": group,
        "module": target_artifact,
        "version": version,
    }
    for name in ("ohosArm64ApiElements-published", "ohosArm64SourcesElements-published"):
        variant = by_name[name]
        require(variant.get("available-at") == expected_available_at, f"root OHOS redirect mismatch in {module_path}: {name}")
        require(not variant.get("files"), f"redirecting root variant must not carry files: {module_path}: {name}")
        require(not variant.get("dependencies"), f"redirecting root variant must not carry dependencies: {module_path}: {name}")
        attributes = variant.get("attributes") or {}
        require(attributes.get("org.jetbrains.kotlin.native.target") == "ohos_arm64", f"root redirect is not ohos_arm64: {module_path}")
        require(attributes.get("org.jetbrains.kotlin.platform.type") == "native", f"root redirect is not native: {module_path}")
    target_module = (module_path.parent / expected_available_at["url"]).resolve()
    require(target_module.is_file(), f"root module redirect is dangling: {target_module}")


MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def pom_text(root: ElementTree.Element, path: str, context: Path, required: bool = True) -> str | None:
    element = root.find(path, MAVEN_NS)
    value = element.text.strip() if element is not None and element.text else None
    if required:
        require(value is not None, f"missing POM field {path}: {context}")
    return value


def verify_pom(
    pom_path: Path,
    group: str,
    artifact: str,
    version: str,
    carrier_repository: str,
    carrier_sha: str,
    upstream_repository: str,
    upstream_sha: str,
    patch_sha256: str,
    expected_dependency: tuple[str, str, str, str] | None,
    target: bool,
) -> None:
    try:
        root = ElementTree.parse(pom_path).getroot()
    except (OSError, ElementTree.ParseError) as error:
        raise VerificationError(f"invalid POM {pom_path}: {error}") from error
    require(pom_text(root, "m:groupId", pom_path) == group, f"POM group mismatch: {pom_path}")
    require(pom_text(root, "m:artifactId", pom_path) == artifact, f"POM artifact mismatch: {pom_path}")
    require(pom_text(root, "m:version", pom_path) == version, f"POM version mismatch: {pom_path}")
    packaging = pom_text(root, "m:packaging", pom_path, required=False)
    require(packaging == ("klib" if target else None), f"POM packaging mismatch: {pom_path}")
    require(
        pom_text(root, "m:scm/m:url", pom_path) == carrier_repository,
        f"POM SCM repository mismatch: {pom_path}",
    )
    require(pom_text(root, "m:scm/m:tag", pom_path) == carrier_sha, f"POM SCM tag mismatch: {pom_path}")
    properties = root.find("m:properties", MAVEN_NS)
    require(properties is not None, f"POM provenance properties are missing: {pom_path}")
    property_values = {element.tag.rsplit("}", 1)[-1]: (element.text or "").strip() for element in properties}
    require(
        property_values
        == {
            "dev.raft.carrierRepository": carrier_repository,
            "dev.raft.carrierSha": carrier_sha,
            "dev.raft.upstreamRepository": upstream_repository,
            "dev.raft.upstreamSha": upstream_sha,
            "dev.raft.patchSha256": patch_sha256,
        },
        f"POM provenance mismatch: {pom_path}: {property_values}",
    )
    dependencies: list[tuple[str, str, str, str]] = []
    for dependency in root.findall("m:dependencies/m:dependency", MAVEN_NS):
        dependencies.append(
            (
                pom_text(dependency, "m:groupId", pom_path),
                pom_text(dependency, "m:artifactId", pom_path),
                pom_text(dependency, "m:version", pom_path),
                pom_text(dependency, "m:scope", pom_path),
            )
        )
    expected_dependencies = [] if expected_dependency is None else [expected_dependency]
    require(sorted(dependencies) == sorted(expected_dependencies), f"POM dependency mismatch: {pom_path}: {dependencies}")


def verify_tooling_metadata(path: Path, kotlin_version: str) -> None:
    metadata = load_json(path)
    require(metadata.get("buildPluginVersion") == kotlin_version, f"tooling Kotlin version mismatch: {path}")
    targets = metadata.get("projectTargets")
    require(isinstance(targets, list) and len(targets) == 2, f"tooling metadata must expose only common+OHOS: {path}")
    platform_types = sorted(target.get("platformType") for target in targets if isinstance(target, dict))
    require(platform_types == ["common", "native"], f"unexpected tooling targets: {path}: {platform_types}")
    native = next(target for target in targets if target.get("platformType") == "native")
    native_details = ((native.get("extras") or {}).get("native") or {})
    require(native_details.get("konanTarget") == "ohos_arm64", f"tooling native target mismatch: {path}")
    require(native_details.get("konanVersion") == kotlin_version, f"tooling Konan version mismatch: {path}")


def verify_embedded_archive(cinterop_path: Path, member: str, expected_sha256: str) -> None:
    try:
        with zipfile.ZipFile(cinterop_path) as archive:
            names = archive.namelist()
            require(names.count(member) == 1, f"embedded archive positive control missing or duplicated: {cinterop_path}:{member}")
            actual = hashlib.sha256(archive.read(member)).hexdigest()
    except (OSError, zipfile.BadZipFile, KeyError) as error:
        raise VerificationError(f"cannot inspect cinterop KLIB {cinterop_path}: {error}") from error
    require(actual == expected_sha256, f"embedded archive checksum mismatch: {cinterop_path}:{member}")


def verify_canonical_klib(path: Path) -> None:
    try:
        with zipfile.ZipFile(path, "r") as archive:
            require(archive.comment == b"", f"canonical KLIB archive comment drifted: {path}")
            infos = archive.infolist()
            names = [info.filename for info in infos]
            require(names, f"canonical KLIB archive is empty: {path}")
            require(len(names) == len(set(names)), f"duplicate canonical KLIB entry: {path}")
            require(
                names == sorted(names, key=lambda name: name.encode("utf-8")),
                f"canonical KLIB entry order drifted: {path}",
            )
            regular_entries = 0
            for info in infos:
                name = info.filename
                candidate = PurePosixPath(name)
                require(
                    name and "\\" not in name and not candidate.is_absolute()
                    and all(part not in {"", ".", ".."} for part in candidate.parts),
                    f"unsafe canonical KLIB entry name: {path}:{name!r}",
                )
                is_directory = info.is_dir()
                require(
                    is_directory == name.endswith("/"),
                    f"ambiguous canonical KLIB directory entry: {path}:{name}",
                )
                try:
                    name.encode("ascii")
                    expected_flags = 0
                except UnicodeEncodeError:
                    expected_flags = 0x800
                require(info.date_time == CANONICAL_KLIB_TIMESTAMP, f"canonical KLIB timestamp drifted: {path}:{name}")
                require(info.extra == b"", f"canonical KLIB extra metadata drifted: {path}:{name}")
                require(info.comment == b"", f"canonical KLIB entry comment drifted: {path}:{name}")
                require(info.create_system == 0, f"canonical KLIB creator drifted: {path}:{name}")
                expected_version = 10 if is_directory else 20
                require(
                    info.create_version == expected_version and info.extract_version == expected_version,
                    f"canonical KLIB ZIP version drifted: {path}:{name}",
                )
                require(info.flag_bits == expected_flags, f"canonical KLIB flags drifted: {path}:{name}")
                require(info.volume == 0, f"canonical KLIB volume drifted: {path}:{name}")
                require(
                    info.internal_attr == 0 and info.external_attr == CANONICAL_KLIB_EXTERNAL_ATTR,
                    f"canonical KLIB attributes drifted: {path}:{name}",
                )
                expected_compression = zipfile.ZIP_STORED if is_directory else zipfile.ZIP_DEFLATED
                require(
                    info.compress_type == expected_compression,
                    f"canonical KLIB compression drifted: {path}:{name}",
                )
                payload = archive.read(info)
                require(not is_directory or payload == b"", f"canonical KLIB directory has payload: {path}:{name}")
                if not is_directory:
                    regular_entries += 1
            require(regular_entries > 0, f"canonical KLIB lacks its regular-entry positive control: {path}")
    except (OSError, zipfile.BadZipFile, KeyError, RuntimeError) as error:
        if isinstance(error, VerificationError):
            raise
        raise VerificationError(f"cannot inspect canonical KLIB {path}: {error}") from error


def verify_repository(repository: Path, carrier_sha: str, manifest_path: Path | None) -> dict[str, Any]:
    require_hex(carrier_sha, 40, "carrier SHA")
    fork_root = Path(__file__).resolve().parent.parent
    spec_path = fork_root / "release-spec.json"
    lock_path = fork_root / "source-lock.json"
    properties_path = fork_root / "gradle.properties"
    spec = load_json(spec_path)
    lock = load_json(lock_path)
    require(spec.get("schema") == 1, "unsupported release-spec schema")
    require(lock.get("schema") == 1, "unsupported source-lock schema")
    properties = parse_gradle_properties(properties_path)
    carrier_repository = spec.get("carrierRepository")
    require(
        isinstance(carrier_repository, str)
        and re.fullmatch(r"https://github\.com/[^/]+/[^/]+\.git", carrier_repository) is not None,
        "carrier repository must be an HTTPS GitHub .git URL",
    )
    require(properties.get("carrierRepository") == carrier_repository, "carrier repository drift between Gradle and release spec")
    require(properties.get("kotlinVersion") == spec.get("kotlinVersion"), "kotlinVersion drift between Gradle and release spec")
    require(
        properties.get("atomicfuBuildPluginVersion") == spec.get("atomicfuBuildPluginVersion"),
        "atomicfu build-plugin version drift between Gradle and release spec",
    )

    repository = repository.resolve()
    require(repository.is_dir() and not repository.is_symlink(), f"staging repository is missing: {repository}")
    release_files: list[Path] = []
    allowed_local_metadata: set[Path] = set()
    components = spec.get("components")
    require(isinstance(components, dict) and set(components) == {"atomicfu", "coroutines"}, "release spec component set mismatch")

    atomic_spec = components["atomicfu"]
    atomic_dependency_root = (
        atomic_spec["group"],
        atomic_spec["rootArtifact"],
        atomic_spec["version"],
    )

    for name in ("atomicfu", "coroutines"):
        component = components[name]
        source_lock = lock.get("sources", {}).get(name)
        require(isinstance(component, dict) and isinstance(source_lock, dict), f"missing component or source lock: {name}")
        verify_source_lock_entry(fork_root, name, component, source_lock)
        require(properties.get(f"{name}Version") == component.get("version"), f"Gradle version drift for {name}")
        require(
            properties.get(f"{name}UpstreamRepository") == component.get("upstreamRepository"),
            f"Gradle upstream repository drift for {name}",
        )
        require(properties.get(f"{name}UpstreamSha") == component.get("upstreamSha"), f"Gradle upstream SHA drift for {name}")
        require(properties.get(f"{name}PatchSha256") == component.get("patchSha256"), f"Gradle patch SHA drift for {name}")

        group = component["group"]
        root_artifact = component["rootArtifact"]
        target_artifact = component["targetArtifact"]
        version = component["version"]
        root_dir = component_path(repository, group, root_artifact, version)
        target_dir = component_path(repository, group, target_artifact, version)
        allowed_local_metadata.add(root_dir.parent / "maven-metadata-local.xml")
        allowed_local_metadata.add(target_dir.parent / "maven-metadata-local.xml")
        release_files.extend(verify_exact_directory(root_dir, expected_filenames(root_artifact, version, target=False)))
        release_files.extend(verify_exact_directory(target_dir, expected_filenames(target_artifact, version, target=True)))

        target_prefix = f"{target_artifact}-{version}"
        verify_canonical_klib(target_dir / f"{target_prefix}.klib")
        verify_canonical_klib(target_dir / f"{target_prefix}-cinterop-interop.klib")

        module_dependency = None if name == "atomicfu" else atomic_dependency_root
        verify_module(
            root_dir / f"{root_artifact}-{version}.module",
            group,
            root_artifact,
            target_artifact,
            version,
            module_dependency,
        )
        verify_module(
            target_dir / f"{target_artifact}-{version}.module",
            group,
            root_artifact,
            target_artifact,
            version,
            module_dependency,
        )

        root_pom_dependency = None
        target_pom_dependency = None
        if name == "coroutines":
            root_pom_dependency = (*atomic_dependency_root, "runtime")
            target_pom_dependency = (
                atomic_spec["group"],
                atomic_spec["targetArtifact"],
                atomic_spec["version"],
                "compile",
            )
        verify_pom(
            root_dir / f"{root_artifact}-{version}.pom",
            group,
            root_artifact,
            version,
            carrier_repository,
            carrier_sha,
            component["upstreamRepository"],
            component["upstreamSha"],
            component["patchSha256"],
            root_pom_dependency,
            target=False,
        )
        verify_pom(
            target_dir / f"{target_artifact}-{version}.pom",
            group,
            target_artifact,
            version,
            carrier_repository,
            carrier_sha,
            component["upstreamRepository"],
            component["upstreamSha"],
            component["patchSha256"],
            target_pom_dependency,
            target=True,
        )
        verify_tooling_metadata(
            root_dir / f"{root_artifact}-{version}-kotlin-tooling-metadata.json",
            spec["kotlinVersion"],
        )

        embedded = component.get("abiControl", {}).get("embeddedArchive")
        if embedded:
            verify_embedded_archive(
                target_dir / f"{target_artifact}-{version}-cinterop-interop.klib",
                embedded["path"],
                embedded["sha256"],
            )

    relative_files = [path.relative_to(repository).as_posix() for path in release_files]
    require(len(relative_files) == len(set(relative_files)) == 20, "release manifest must contain exactly 20 unique files")
    require(all("maven-metadata" not in path for path in relative_files), "local Maven metadata must not enter the release manifest")
    expected_files = set(release_files)
    expected_directories = {
        parent
        for path in expected_files | allowed_local_metadata
        for parent in path.parents
        if parent != repository and repository in parent.parents
    }
    for path in repository.rglob("*"):
        require(not path.is_symlink(), f"staging repository contains a symlink: {path}")
        if path.is_dir():
            require(path in expected_directories, f"staging repository contains an unexpected directory: {path}")
            continue
        require(path.is_file(), f"staging repository contains an unsupported entry: {path}")
        require(
            path in expected_files or path in allowed_local_metadata,
            f"staging repository contains an unexpected file: {path}",
        )
        require(path.stat().st_size > 0, f"staging repository file is empty: {path}")
    entries = [
        {
            "path": relative,
            "size": (repository / relative).stat().st_size,
            "sha256": sha256(repository / relative),
        }
        for relative in sorted(relative_files)
    ]
    manifest = {
        "schema": 1,
        "carrierSha": carrier_sha,
        "sourceLockSha256": sha256(lock_path),
        "releaseSpecSha256": sha256(spec_path),
        "files": entries,
    }
    if manifest_path is not None:
        require(not manifest_path.exists(), f"manifest output already exists: {manifest_path}")
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify the exact OHOS fork staging repository and emit its immutable manifest")
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--carrier-sha", required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        manifest = verify_repository(arguments.repository, arguments.carrier_sha, arguments.manifest)
    except VerificationError as error:
        print(f"verify-staging: {error}", file=sys.stderr)
        return 1
    print(
        "verify-staging: verified "
        f"{len(manifest['files'])} files, {sum(entry['size'] for entry in manifest['files'])} bytes, "
        f"manifest_sha256={sha256(arguments.manifest)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
