#!/usr/bin/env python3
"""Exact-byte Tencent Compose authority reader and resumable Raft mirror.

Raft task #120 owns exactly eight 1.7.3-kuikly2 GAVs (32 primary files).
The immutable manifest is the sole admission authority.  This program never
rebuilds or rewrites a Maven byte.  Public authority fetch, Raft planning and
terminal verification are anonymous; the repository-scoped publish credential
exists only in the PUT-only writer process.  Identical partial state is a
normal resumable Maven publication state; divergent bytes always stop.

This task publishes only the 32 Maven primaries.  The authority manifest stays
an audit/admission input and is never PUT to Maven.  The sole public Kuikly
release marker is owned by task #93 after all predecessor receipts are closed.

Commands:
  fetch   --manifest M --bytes-dir B --receipt R
  plan    --manifest M --output P
  publish --manifest M --bytes-dir B --plan P --output W
  verify  --manifest M --output R
  consumer-status --manifest M --terminal-receipt-sha256 H
                  --resolution-outcome O --output R
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import Any, Callable, Optional


AUTHORITY_BASE_URL = "https://mirrors.tencent.com/repository/maven-tencent"
RAFT_BASE_URL = "https://maven.artifacts.botiverse.dev"
CONTROL_PLANE_BASE_URL = "https://artifacts.botiverse.dev"
CONTROL_PLANE_PATH = "/api/scopes/{scope}/artifacts"
USER_AGENT = "raft-compose-exact-mirror/1.0"
VERSION = "1.7.3-kuikly2"
EXPECTED_TASK = 120
EXPECTED_GAV_COUNT = 8
EXPECTED_FILE_COUNT = 32
TARGET_SCOPES = (
    "com.tencent.kuikly-open.compose.runtime",
    "com.tencent.kuikly-open.compose.annotation-internal",
    "com.tencent.kuikly-open.compose.collection-internal",
)
POSITIVE_CONTROL_SCOPE = "build.raft.kuiklybase"
POSITIVE_CONTROL_PATH = (
    "build/raft/kuiklybase/datetime/0.1.0-raft.0/"
    "datetime-0.1.0-raft.0.pom"
)
POSITIVE_CONTROL_SHA256 = "e06e5dc280a556fe7523b631d644ab8fe388e794a254be6a1f4c5f577eef606f"
PATH_RE = re.compile(r"[A-Za-z0-9._/-]+")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
HEX40_RE = re.compile(r"[0-9a-f]{40}")
APACHE_NAME = "The Apache Software License, Version 2.0"
APACHE_URL = "http://www.apache.org/licenses/LICENSE-2.0.txt"

EXPECTED_GAVS = (
    ("com.tencent.kuikly-open.compose.runtime", "runtime", "jar", "root", "runtime-ohosarm64"),
    ("com.tencent.kuikly-open.compose.runtime", "runtime-ohosarm64", "klib", "ohosArm64Physical", None),
    ("com.tencent.kuikly-open.compose.runtime", "runtime-saveable", "jar", "root", "runtime-saveable-ohosarm64"),
    ("com.tencent.kuikly-open.compose.runtime", "runtime-saveable-ohosarm64", "klib", "ohosArm64Physical", None),
    ("com.tencent.kuikly-open.compose.annotation-internal", "annotation", "jar", "root", "annotation-ohosarm64"),
    ("com.tencent.kuikly-open.compose.annotation-internal", "annotation-ohosarm64", "klib", "ohosArm64Physical", None),
    ("com.tencent.kuikly-open.compose.collection-internal", "collection", "jar", "root", "collection-ohosarm64"),
    ("com.tencent.kuikly-open.compose.collection-internal", "collection-ohosarm64", "klib", "ohosArm64Physical", None),
)

EXPECTED_KBA_COORDINATES = {
    "org.jetbrains.kotlin:kotlin-stdlib:2.0.21-KBA-003",
    "org.jetbrains.kotlin:kotlin-stdlib-common:2.0.21-KBA-003",
    "org.jetbrains.kotlinx:atomicfu:0.23.2-KBA-001",
    "org.jetbrains.kotlinx:atomicfu-ohosarm64:0.23.2-KBA-001",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-KBA-001",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-ohosarm64:1.8.0-KBA-001",
}

EXPECTED_CURRENT_PREDECESSOR_COORDINATES = (
    "org.jetbrains.kotlin:kotlin-stdlib:2.0.21-KBA-003",
    "org.jetbrains.kotlinx:atomicfu:0.23.2-KBA-001",
    "org.jetbrains.kotlinx:atomicfu-ohosarm64:0.23.2-KBA-001",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-KBA-002",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-ohosarm64:1.8.0-KBA-002",
)


class MirrorError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise MirrorError(message)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_https_base(value: str, context: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise MirrorError(f"{context} base URL is invalid: {error}") from error
    require(parsed.scheme == "https", f"{context} base URL must use HTTPS")
    require(parsed.hostname is not None, f"{context} base URL must have a host")
    require(parsed.username is None and parsed.password is None, f"{context} base URL must not carry credentials")
    require(not parsed.query and not parsed.fragment, f"{context} base URL must not carry query or fragment")
    hostname = parsed.hostname.lower()
    netloc = hostname if port in {None, 443} else f"{hostname}:{port}"
    path = parsed.path.rstrip("/")
    return urllib.parse.urlunsplit(("https", netloc, path, "", ""))


def safe_relative(relative: str) -> None:
    require(
        relative != ""
        and not relative.startswith("/")
        and PATH_RE.fullmatch(relative) is not None
        and ".." not in relative.split("/")
        and "//" not in relative,
        f"unsafe repository path: {relative!r}",
    )


class RejectRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(  # type: ignore[override]
        self,
        request: urllib.request.Request,
        file_pointer: object,
        code: int,
        message: str,
        headers: object,
        new_url: str,
    ) -> None:
        del file_pointer, message, headers
        source = urllib.parse.urlsplit(request.full_url)
        target = urllib.parse.urlsplit(new_url)
        raise MirrorError(
            "redirect rejected before another request: "
            f"HTTP {code}, {source.scheme}://{source.netloc} -> {target.scheme}://{target.netloc}"
        )


class PublicRepositoryClient:
    """Anonymous GET-only transport pinned to one reviewed HTTPS base."""

    def __init__(
        self,
        name: str,
        base_url: str,
        expected_base_url: str,
        opener: Optional[urllib.request.OpenerDirector] = None,
    ) -> None:
        self.name = name
        self.base_url = canonical_https_base(base_url, name)
        require(
            self.base_url == canonical_https_base(expected_base_url, name),
            f"{name} base URL must exactly match {canonical_https_base(expected_base_url, name)}",
        )
        self.opener = opener or urllib.request.build_opener(RejectRedirectHandler())

    def url(self, relative: str) -> str:
        safe_relative(relative)
        return f"{self.base_url}/{urllib.parse.quote(relative, safe='/._-')}"

    def get(self, relative: str, *, attempts: int = 3) -> tuple[int, bytes]:
        request = urllib.request.Request(
            self.url(relative),
            method="GET",
            headers={"User-Agent": USER_AGENT, "Accept": "application/octet-stream"},
        )
        last_error = ""
        for attempt in range(attempts):
            try:
                with self.opener.open(request, timeout=60) as response:
                    return response.status, response.read()
            except MirrorError:
                raise
            except urllib.error.HTTPError as error:
                if error.code in {404}:
                    return error.code, error.read() or b""
                if 500 <= error.code <= 599 and attempt + 1 < attempts:
                    last_error = f"HTTP {error.code}"
                    time.sleep(attempt + 1)
                    continue
                raise MirrorError(f"{self.name} GET failed for {relative}: HTTP {error.code}") from error
            except urllib.error.URLError as error:
                last_error = str(error.reason)
                if attempt + 1 < attempts:
                    time.sleep(attempt + 1)
                    continue
                raise MirrorError(f"{self.name} GET transport failure for {relative}: {last_error}") from error
        raise MirrorError(f"{self.name} GET transport failure for {relative}: {last_error}")


class CreateOnlyWriter:
    """Immutable Maven PUT-only transport.  It deliberately has no read method."""

    def __init__(
        self,
        base_url: str,
        username: str,
        token: str,
        opener: Optional[urllib.request.OpenerDirector] = None,
    ) -> None:
        require(username != "" and token != "", "Raft writer credential is missing")
        require("\r" not in username + token and "\n" not in username + token, "Raft writer credential is malformed")
        configured = canonical_https_base(base_url, "Raft writer")
        require(configured == canonical_https_base(RAFT_BASE_URL, "Raft writer"), "Raft writer origin changed")
        self.base_url = configured
        encoded = base64.b64encode(f"{username}:{token}".encode()).decode()
        self.authorization = f"Basic {encoded}"
        self.opener = opener or urllib.request.build_opener(RejectRedirectHandler())

    def put(self, relative: str, body: bytes) -> int:
        safe_relative(relative)
        request = urllib.request.Request(
            f"{self.base_url}/{urllib.parse.quote(relative, safe='/._-')}",
            method="PUT",
            data=body,
            headers={
                "Authorization": self.authorization,
                "If-None-Match": "*",
                "User-Agent": USER_AGENT,
                "Content-Type": "application/octet-stream",
            },
        )
        # Never retry an ambiguous write.  A lost response may follow a
        # committed create; the next action must be anonymous inspection.
        try:
            with self.opener.open(request, timeout=60) as response:
                return response.status
        except MirrorError:
            raise
        except urllib.error.HTTPError as error:
            return error.code
        except urllib.error.URLError as error:
            raise MirrorError(
                f"Raft PUT transport outcome is ambiguous for {relative}; stop and inspect anonymously: {error.reason}"
            ) from error


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise MirrorError(f"cannot read JSON {path}: {error}") from error
    require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def expected_gav_objects() -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for group, artifact, binary, role, physical in EXPECTED_GAVS:
        item: dict[str, Any] = {
            "groupId": group,
            "artifactId": artifact,
            "version": VERSION,
            "role": role,
            "binaryExtension": binary,
        }
        if physical is not None:
            item["ohosArm64PhysicalArtifactId"] = physical
        result.append(item)
    return result


def expected_file_paths() -> dict[str, tuple[str, str, str]]:
    result: dict[str, tuple[str, str, str]] = {}
    for group, artifact, binary, _role, _physical in EXPECTED_GAVS:
        prefix = f"{group.replace('.', '/')}/{artifact}/{VERSION}"
        for suffix in (".pom", ".module", f".{binary}", "-sources.jar"):
            path = f"{prefix}/{artifact}-{VERSION}{suffix}"
            result[path] = (group, artifact, binary)
    return result


def load_manifest(path: Path) -> dict[str, Any]:
    manifest = load_json(path)
    require(
        set(manifest) == {"schema", "task", "authority", "inventory", "license", "gavs", "closure", "files"},
        "authority manifest top-level fields changed",
    )
    require(manifest["schema"] == 1 and manifest["task"] == EXPECTED_TASK, "authority manifest identity changed")
    authority = manifest["authority"]
    require(isinstance(authority, dict), "authority manifest has no authority object")
    require(authority.get("kind") == "public-maven-exact-byte", "authority kind changed")
    require(authority.get("baseUrl") == AUTHORITY_BASE_URL, "authority base URL changed")
    require(authority.get("repository") == "maven-tencent", "authority repository changed")
    provenance = authority.get("artifactPublicationProvenance")
    require(isinstance(provenance, dict), "artifact publication provenance is missing")
    require(provenance.get("status") == "not-publicly-auditable", "unproven source lineage was promoted to audited")
    require(isinstance(provenance.get("caveat"), str) and provenance["caveat"], "source-lineage caveat is empty")
    selection = authority.get("publicConsumerSelection")
    require(isinstance(selection, dict), "public consumer-selection evidence is missing")
    require(selection.get("pullRequest") == 1525, "consumer-selection PR changed")
    require(selection.get("pullRequestHead") == "93d1ae7351294baa21abb040c619b22cb17fd99c", "consumer-selection head changed")
    require(selection.get("mergeCommit") == "388f639bcf79a3e675ed33eb005b3a594dedf243", "consumer-selection merge changed")

    inventory = manifest["inventory"]
    require(
        isinstance(inventory, dict)
        and inventory.get("version") == VERSION
        and inventory.get("gavCount") == EXPECTED_GAV_COUNT
        and inventory.get("fileCount") == EXPECTED_FILE_COUNT,
        "authority inventory identity changed",
    )
    require("Primary immutable Maven files only" in inventory.get("boundary", ""), "primary-only boundary is missing")
    license_value = manifest["license"]
    require(isinstance(license_value, dict) and license_value.get("spdx") == "Apache-2.0", "license identity changed")
    require(manifest["gavs"] == expected_gav_objects(), "authority GAV set/order changed")

    closure = manifest["closure"]
    require(isinstance(closure, dict), "authority closure is missing")
    require(closure.get("kotlinCompilerVersion") == "2.0.21-KBA-003", "Kotlin authority version changed")
    require(closure.get("kotlin210Kba010InGraph") is False, "KBA-010 graph verdict changed")
    require(set(closure.get("ohosArm64ExternalCoordinates", [])) == EXPECTED_KBA_COORDINATES, "KBA dependency closure changed")
    mappings = closure.get("rootToOhosArm64")
    require(isinstance(mappings, list) and len(mappings) == 4, "root-to-OHOS mapping count changed")
    collection_mapping = next(
        (item for item in mappings if isinstance(item, dict) and ":collection:" in item.get("root", "")),
        None,
    )
    require(isinstance(collection_mapping, dict), "collection root-to-OHOS mapping is missing")
    require(
        collection_mapping.get("availableAtUrl")
        == "../../collection-ohosarm64/1.7.4-ohos/collection-ohosarm64-1.7.4-ohos.module",
        "canonical collection available-at caveat changed",
    )
    require(isinstance(collection_mapping.get("caveat"), str) and collection_mapping["caveat"], "collection stale-URL caveat is missing")

    expected = expected_file_paths()
    files = manifest["files"]
    require(isinstance(files, list) and len(files) == EXPECTED_FILE_COUNT, "authority manifest must contain 32 files")
    paths: set[str] = set()
    for entry in files:
        require(isinstance(entry, dict) and set(entry) == {"path", "size", "sha256"}, "invalid authority file entry")
        relative = entry.get("path")
        require(isinstance(relative, str), "authority file path is not a string")
        safe_relative(relative)
        require(relative in expected, f"authority file is outside the exact GAV set: {relative}")
        require(relative not in paths, f"duplicate authority file path: {relative}")
        paths.add(relative)
        require(isinstance(entry.get("size"), int) and entry["size"] > 0, f"invalid authority size: {relative}")
        require(isinstance(entry.get("sha256"), str) and SHA256_RE.fullmatch(entry["sha256"]), f"invalid authority SHA-256: {relative}")
    require(paths == set(expected), "authority manifest does not cover the exact 32-file set")
    return manifest


def manifest_entries(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {entry["path"]: entry for entry in manifest["files"]}


def gav_for_path(relative: str) -> tuple[str, str, str]:
    for path, value in expected_file_paths().items():
        if path == relative:
            return value
    raise MirrorError(f"path is outside the exact GAV set: {relative}")


def validate_staged_bytes(manifest: dict[str, Any], bytes_dir: Path) -> None:
    require(bytes_dir.is_dir() and not bytes_dir.is_symlink(), f"bytes directory is missing or unsafe: {bytes_dir}")
    entries = manifest_entries(manifest)
    observed = {
        path.relative_to(bytes_dir).as_posix()
        for path in bytes_dir.rglob("*")
        if path.is_file() or path.is_symlink()
    }
    require(observed == set(entries), "staging inventory differs from the immutable 32-file manifest")
    for relative, entry in entries.items():
        path = bytes_dir / relative
        require(path.is_file() and not path.is_symlink(), f"staged byte is missing or unsafe: {relative}")
        require(path.stat().st_size == entry["size"], f"staged size differs from authority: {relative}")
        require(sha256_file(path) == entry["sha256"], f"staged SHA-256 differs from authority: {relative}")
        if relative.endswith((".jar", ".klib")):
            try:
                with zipfile.ZipFile(path) as archive:
                    require(archive.testzip() is None, f"corrupt archive member in {relative}")
            except zipfile.BadZipFile as error:
                raise MirrorError(f"invalid ZIP/KLIB authority byte: {relative}") from error

    validate_poms(manifest, bytes_dir)
    validate_modules(manifest, bytes_dir)
    validate_klibs(manifest, bytes_dir)


def xml_text(parent: ET.Element, name: str) -> str:
    child = parent.find(f"{{*}}{name}")
    return child.text or "" if child is not None else ""


def validate_poms(manifest: dict[str, Any], bytes_dir: Path) -> None:
    observed_kba: set[str] = set()
    for relative in sorted(path for path in manifest_entries(manifest) if path.endswith(".pom")):
        group, artifact, _binary = gav_for_path(relative)
        try:
            root = ET.parse(bytes_dir / relative).getroot()
        except ET.ParseError as error:
            raise MirrorError(f"invalid POM XML: {relative}") from error
        require(xml_text(root, "groupId") == group, f"POM groupId mismatch: {relative}")
        require(xml_text(root, "artifactId") == artifact, f"POM artifactId mismatch: {relative}")
        require(xml_text(root, "version") == VERSION, f"POM version mismatch: {relative}")
        licenses = root.findall("{*}licenses/{*}license")
        require(len(licenses) == 1, f"POM license count changed: {relative}")
        require(xml_text(licenses[0], "name") == APACHE_NAME, f"POM license name changed: {relative}")
        require(xml_text(licenses[0], "url") == APACHE_URL, f"POM license URL changed: {relative}")
        for dependency in root.findall("{*}dependencies/{*}dependency"):
            dep_version = xml_text(dependency, "version").strip("[]")
            if "KBA-" in dep_version:
                observed_kba.add(
                    f"{xml_text(dependency, 'groupId')}:{xml_text(dependency, 'artifactId')}:{dep_version}"
                )
    require(observed_kba == EXPECTED_KBA_COORDINATES, "POM KBA dependency closure differs from the manifest")


def root_mapping_by_gav(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {item["root"]: item for item in manifest["closure"]["rootToOhosArm64"]}


def validate_modules(manifest: dict[str, Any], bytes_dir: Path) -> None:
    entries = manifest_entries(manifest)
    mappings = root_mapping_by_gav(manifest)
    for relative in sorted(path for path in entries if path.endswith(".module")):
        group, artifact, _binary = gav_for_path(relative)
        try:
            module = json.loads((bytes_dir / relative).read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise MirrorError(f"invalid Gradle module metadata: {relative}") from error
        role = next(item[3] for item in EXPECTED_GAVS if item[0] == group and item[1] == artifact)
        component = module.get("component")
        require(isinstance(component, dict), f"module component missing: {relative}")
        root_artifact = artifact.removesuffix("-ohosarm64") if role == "ohosArm64Physical" else artifact
        require(component.get("group") == group, f"module component group mismatch: {relative}")
        require(component.get("module") == root_artifact, f"module component identity mismatch: {relative}")
        require(component.get("version") == VERSION, f"module component version mismatch: {relative}")
        variants = module.get("variants")
        require(isinstance(variants, list) and variants, f"module variants missing: {relative}")

        # Every local file declaration is bound back to one of the four
        # immutable primaries; metadata cannot smuggle an unmanifested byte.
        prefix = relative.rsplit("/", 1)[0] + "/"
        local_paths = {path for path in entries if path.startswith(prefix)}
        for variant in variants:
            require(isinstance(variant, dict), f"invalid module variant: {relative}")
            files = variant.get("files") or []
            require(isinstance(files, list), f"invalid module files array: {relative}")
            for file_value in files:
                require(isinstance(file_value, dict), f"invalid module file entry: {relative}")
                target = prefix + file_value.get("url", "")
                require(target in local_paths, f"module references an unmanifested local byte: {target}")
                require(file_value.get("size") == entries[target]["size"], f"module size differs from authority: {target}")
                require(file_value.get("sha256") == entries[target]["sha256"], f"module SHA-256 differs from authority: {target}")

        if role == "root":
            gav = f"{group}:{artifact}:{VERSION}"
            mapping = mappings[gav]
            ohos_variants = [v for v in variants if v.get("name") in {
                "ohosArm64ApiElements-published", "ohosArm64SourcesElements-published"
            }]
            require(len(ohos_variants) == 2, f"root OHOS variant mapping count changed: {relative}")
            physical_parts = mapping["physical"].split(":")
            for variant in ohos_variants:
                available = variant.get("available-at")
                require(isinstance(available, dict), f"root OHOS available-at missing: {relative}")
                require(available.get("group") == physical_parts[0], f"root OHOS group mapping changed: {relative}")
                require(available.get("module") == physical_parts[1], f"root OHOS module mapping changed: {relative}")
                require(available.get("version") == physical_parts[2], f"root OHOS version mapping changed: {relative}")
                require(available.get("url") == mapping["availableAtUrl"], f"root OHOS URL byte fact changed: {relative}")
        else:
            api = next((v for v in variants if v.get("name") == "ohosArm64ApiElements-published"), None)
            require(isinstance(api, dict), f"physical OHOS API variant missing: {relative}")
            attributes = api.get("attributes")
            require(isinstance(attributes, dict), f"physical OHOS attributes missing: {relative}")
            require(attributes.get("org.jetbrains.kotlin.native.target") == "ohos_arm64", f"physical target changed: {relative}")


def parse_klib_manifest(value: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in value.splitlines():
        if not line:
            continue
        require("=" in line, "invalid KLIB manifest line")
        key, item = line.split("=", 1)
        require(key not in result, f"duplicate KLIB manifest key: {key}")
        result[key] = item
    return result


def validate_klibs(manifest: dict[str, Any], bytes_dir: Path) -> None:
    klibs = [path for path in manifest_entries(manifest) if path.endswith(".klib")]
    require(len(klibs) == 4, "authority must contain four OHOS KLIBs")
    for relative in sorted(klibs):
        group, artifact, _binary = gav_for_path(relative)
        root_artifact = artifact.removesuffix("-ohosarm64")
        with zipfile.ZipFile(bytes_dir / relative) as archive:
            try:
                value = archive.read("default/manifest").decode("utf-8")
            except (KeyError, UnicodeDecodeError) as error:
                raise MirrorError(f"KLIB manifest missing or invalid: {relative}") from error
        properties = parse_klib_manifest(value)
        require(properties.get("compiler_version") == "2.0.21-KBA-003", f"KLIB compiler version changed: {relative}")
        require(properties.get("native_targets") == "ohos_arm64", f"KLIB native target changed: {relative}")
        require(
            properties.get("unique_name") == f"{group}\\:{root_artifact}",
            f"KLIB unique_name changed: {relative}",
        )
        require("2.0.21-KBA-010" not in value, f"KBA-010 unexpectedly entered the KLIB graph: {relative}")


def write_json_exclusive(path: Path, value: dict[str, Any]) -> None:
    require(not path.exists(), f"refusing to replace output: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def runner_provenance() -> dict[str, str]:
    source = os.environ.get("MIRROR_SOURCE_EXACT", os.environ.get("GITHUB_SHA", "local"))
    require(source == "local" or HEX40_RE.fullmatch(source) is not None, "GITHUB_SHA is not 40-hex")
    return {
        "sourceExact": source,
        "runId": os.environ.get("GITHUB_RUN_ID", "local"),
        "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT", "local"),
    }


def fetch_authority(manifest_path: Path, bytes_dir: Path, receipt_path: Path) -> None:
    manifest = load_manifest(manifest_path)
    require(not bytes_dir.exists(), f"refusing to mix authority bytes into an existing directory: {bytes_dir}")
    bytes_dir.mkdir(parents=True)
    client = PublicRepositoryClient("Tencent authority", AUTHORITY_BASE_URL, AUTHORITY_BASE_URL)
    for relative, entry in sorted(manifest_entries(manifest).items()):
        status, body = client.get(relative)
        require(status == 200, f"Tencent authority GET returned HTTP {status}: {relative}")
        require(len(body) == entry["size"], f"Tencent authority size changed: {relative}")
        require(sha256_bytes(body) == entry["sha256"], f"Tencent authority SHA-256 changed: {relative}")
        destination = bytes_dir / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(body)
    validate_staged_bytes(manifest, bytes_dir)
    receipt = {
        "schema": 1,
        "status": "complete",
        "authentication": "none",
        "authorityBaseUrl": AUTHORITY_BASE_URL,
        "manifestSha256": sha256_file(manifest_path),
        "fileCount": EXPECTED_FILE_COUNT,
        "provenance": runner_provenance(),
        "files": manifest["files"],
    }
    write_json_exclusive(receipt_path, receipt)


def control_plane_get(path: str, opener: Optional[urllib.request.OpenerDirector] = None) -> dict[str, Any]:
    base = canonical_https_base(CONTROL_PLANE_BASE_URL, "Raft control plane")
    require(path.startswith("/") and ".." not in path.split("/"), "unsafe control-plane path")
    request = urllib.request.Request(
        base + path,
        method="GET",
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    active_opener = opener or urllib.request.build_opener(RejectRedirectHandler())
    try:
        with active_opener.open(request, timeout=60) as response:
            require(response.status == 200, f"control-plane GET returned HTTP {response.status}")
            body = response.read()
    except MirrorError:
        raise
    except urllib.error.HTTPError as error:
        raise MirrorError(f"control-plane GET returned HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise MirrorError(f"control-plane GET transport failure: {error.reason}") from error
    try:
        value = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise MirrorError("control-plane response is not JSON") from error
    require(isinstance(value, dict), "control-plane response root is not an object")
    return value


ListScope = Callable[[str], list[str]]
FetchRaft = Callable[[str], tuple[int, bytes]]


def live_list_scope(scope: str) -> list[str]:
    payload = control_plane_get(CONTROL_PLANE_PATH.format(scope=urllib.parse.quote(scope, safe="._-")))
    require(payload.get("scope") == scope, f"control-plane scope echo mismatch for {scope}")
    artifacts = payload.get("artifacts")
    require(isinstance(artifacts, list), f"control-plane artifacts array missing for {scope}")
    keys: list[str] = []
    for item in artifacts:
        require(isinstance(item, dict) and isinstance(item.get("key"), str), f"invalid control-plane entry for {scope}")
        safe_relative(item["key"])
        keys.append(item["key"])
    require(len(keys) == len(set(keys)), f"duplicate control-plane key for {scope}")
    return sorted(keys)


def live_fetch_raft(relative: str) -> tuple[int, bytes]:
    client = PublicRepositoryClient("Raft anonymous read", RAFT_BASE_URL, RAFT_BASE_URL)
    return client.get(relative)


def prove_public_positive_controls(list_scope: ListScope, fetch_raft: FetchRaft) -> None:
    keys = list_scope(POSITIVE_CONTROL_SCOPE)
    require(POSITIVE_CONTROL_PATH in keys, "Raft control-plane positive control is absent; absence evidence is void")
    status, body = fetch_raft(POSITIVE_CONTROL_PATH)
    require(status == 200, f"Raft Maven positive control returned HTTP {status}; absence evidence is void")
    require(sha256_bytes(body) == POSITIVE_CONTROL_SHA256, "Raft Maven positive control bytes changed; absence evidence is void")


def classify_remote(
    manifest: dict[str, Any],
    list_scope: ListScope,
    fetch_raft: FetchRaft,
    *,
    positive_control: bool = True,
) -> dict[str, Any]:
    entries = manifest_entries(manifest)
    expected_paths = set(entries)
    prefixes = {path.rsplit("/", 1)[0] for path in expected_paths}
    if positive_control:
        prove_public_positive_controls(list_scope, fetch_raft)
    listed: set[str] = set()
    scope_counts: dict[str, int] = {}
    for scope in TARGET_SCOPES:
        keys = set(list_scope(scope))
        scope_counts[scope] = len(keys)
        listed.update(key for key in keys if any(key.startswith(prefix + "/") for prefix in prefixes))
    unexpected = sorted(listed - expected_paths)
    existing: list[str] = []
    missing: list[str] = []
    divergent: list[dict[str, Any]] = []
    for relative, entry in sorted(entries.items()):
        status, body = fetch_raft(relative)
        if status == 404:
            missing.append(relative)
            continue
        require(status == 200, f"Raft anonymous GET returned HTTP {status}: {relative}")
        if len(body) != entry["size"] or sha256_bytes(body) != entry["sha256"]:
            divergent.append({
                "path": relative,
                "remoteSize": len(body),
                "remoteSha256": sha256_bytes(body),
            })
        else:
            existing.append(relative)
    listed_expected = listed & expected_paths
    observed_existing = set(existing) | {item["path"] for item in divergent}
    listing_mismatch = sorted(listed_expected ^ observed_existing)
    if unexpected or divergent or listing_mismatch:
        decision = "hold-conflict"
    elif not existing and len(missing) == EXPECTED_FILE_COUNT:
        decision = "publish-all-absent"
    elif len(existing) == EXPECTED_FILE_COUNT and not missing:
        decision = "noop-complete-identical"
    else:
        decision = "resume-partial-exact"
    return {
        "decision": decision,
        "existing": existing,
        "missing": missing,
        "divergent": divergent,
        "unexpected": unexpected,
        "listingMismatch": listing_mismatch,
        "scopePrimaryCounts": scope_counts,
    }


def make_plan(manifest_path: Path, output: Path) -> str:
    manifest = load_manifest(manifest_path)
    remote = classify_remote(manifest, live_list_scope, live_fetch_raft)
    plan = {
        "schema": 1,
        "task": EXPECTED_TASK,
        "manifestSha256": sha256_file(manifest_path),
        "fileCount": EXPECTED_FILE_COUNT,
        "authentication": "none",
        "provenance": runner_provenance(),
        "remote": remote,
    }
    write_json_exclusive(output, plan)
    return remote["decision"]


def load_publish_contract(
    manifest_path: Path,
    bytes_dir: Path,
    plan_path: Path,
) -> tuple[dict[str, Any], dict[str, Any]]:
    manifest = load_manifest(manifest_path)
    validate_staged_bytes(manifest, bytes_dir)
    plan = load_json(plan_path)
    require(plan.get("schema") == 1 and plan.get("task") == EXPECTED_TASK, "publication plan identity changed")
    require(plan.get("manifestSha256") == sha256_file(manifest_path), "publication plan is not bound to the manifest bytes")
    require(plan.get("fileCount") == EXPECTED_FILE_COUNT, "publication plan file count changed")
    require(plan.get("authentication") == "none", "publication plan was not produced anonymously")
    require(plan.get("provenance") == runner_provenance(), "publication plan is not bound to this source/run tuple")
    remote = plan.get("remote")
    require(isinstance(remote, dict), "publication plan has no remote state")
    decision = remote.get("decision")
    require(decision in {"publish-all-absent", "resume-partial-exact"}, "writer plan is not publishable")
    require(remote.get("divergent") == [] and remote.get("unexpected") == [], "writer plan contains conflicts")
    require(remote.get("listingMismatch") == [], "writer plan has listing/GET disagreement")
    existing = remote.get("existing")
    missing = remote.get("missing")
    require(isinstance(existing, list) and isinstance(missing, list), "writer plan path sets are invalid")
    require(len(existing) == len(set(existing)) and len(missing) == len(set(missing)), "writer plan contains duplicate paths")
    require(set(existing).isdisjoint(missing), "writer plan overlaps existing and missing paths")
    require(set(existing) | set(missing) == set(manifest_entries(manifest)), "writer plan does not cover the exact 32 paths")
    expected_decision = "publish-all-absent" if not existing else "resume-partial-exact"
    require(decision == expected_decision and missing, "writer plan decision does not match its exact remote state")
    return manifest, plan


def publication_priority(relative: str) -> tuple[int, int, str]:
    metadata = relative.endswith((".pom", ".module"))
    physical = "-ohosarm64/" in relative
    if not metadata:
        return 0, 0, relative
    # All payloads first, then physical metadata, then root metadata.  Within
    # a tier the .module marker is last.
    tier = 1 if physical else 2
    module_last = 1 if relative.endswith(".module") else 0
    return tier, module_last, relative


def publish(
    manifest_path: Path,
    bytes_dir: Path,
    plan_path: Path,
    output: Path,
    writer_factory: Callable[[str, str, str], CreateOnlyWriter] = CreateOnlyWriter,
) -> None:
    manifest, plan = load_publish_contract(manifest_path, bytes_dir, plan_path)
    username = os.environ.get("RAFT_ARTIFACTS_USERNAME", "raft-ci")
    token = os.environ.get("RAFT_ARTIFACTS_PUBLISH_TOKEN", "")
    writer = writer_factory(os.environ.get("RAFT_ARTIFACTS_URL", RAFT_BASE_URL), username, token)
    existing = plan["remote"]["existing"]
    missing = plan["remote"]["missing"]
    uploaded: list[str] = []
    for relative in sorted(missing, key=publication_priority):
        body = (bytes_dir / relative).read_bytes()
        status = writer.put(relative, body)
        require(
            status in {200, 201, 204},
            f"create-only PUT did not create {relative}: HTTP {status}; stop and inspect anonymously",
        )
        uploaded.append(relative)
    receipt = {
        "schema": 1,
        "status": "put-complete-awaiting-anonymous-verification",
        "publicationBoundary": "32-maven-primaries-only-no-completion-marker",
        "manifestSha256": sha256_file(manifest_path),
        "existingCount": len(existing),
        "uploadedCount": len(uploaded),
        "targetFileCount": EXPECTED_FILE_COUNT,
        "provenance": runner_provenance(),
        "existingPaths": existing,
        "uploadedPaths": uploaded,
    }
    write_json_exclusive(output, receipt)


def verify(manifest_path: Path, output: Path, *, attempts: int = 6) -> None:
    manifest = load_manifest(manifest_path)
    remote: dict[str, Any] | None = None
    for attempt in range(attempts):
        remote = classify_remote(manifest, live_list_scope, live_fetch_raft)
        if remote["decision"] == "noop-complete-identical":
            break
        if attempt + 1 < attempts and remote["decision"] in {"resume-partial-exact", "hold-conflict"}:
            time.sleep(5)
            continue
        break
    require(remote is not None and remote["decision"] == "noop-complete-identical", f"anonymous terminal state is not complete-identical: {remote}")
    receipt = {
        "schema": 1,
        "status": "complete-identical",
        "authentication": "none",
        "manifestSha256": sha256_file(manifest_path),
        "fileCount": EXPECTED_FILE_COUNT,
        "provenance": runner_provenance(),
        "files": manifest["files"],
        "scopePrimaryCounts": remote["scopePrimaryCounts"],
    }
    write_json_exclusive(output, receipt)


def write_consumer_closure_receipt(
    manifest_path: Path,
    terminal_receipt_sha256: str,
    resolution_outcome: str,
    output: Path,
) -> None:
    load_manifest(manifest_path)
    require(
        SHA256_RE.fullmatch(terminal_receipt_sha256) is not None,
        "publication terminal receipt SHA-256 is invalid",
    )
    require(
        resolution_outcome in {"success", "failure"},
        "consumer resolution outcome must be success or failure",
    )
    receipt = {
        "schema": 1,
        "task": EXPECTED_TASK,
        "status": "complete" if resolution_outcome == "success" else "not-closed",
        "resolutionOutcome": resolution_outcome,
        "manifestSha256": sha256_file(manifest_path),
        "publicationTerminalReceiptSha256": terminal_receipt_sha256,
        "publicationBoundary": "32-maven-primaries-verified-before-consumer-closure",
        "provenance": runner_provenance(),
        "externalPredecessor": {
            "task": 121,
            "coordinates": list(EXPECTED_CURRENT_PREDECESSOR_COORDINATES),
        },
    }
    write_json_exclusive(output, receipt)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    fetch_parser = subparsers.add_parser("fetch")
    fetch_parser.add_argument("--manifest", type=Path, required=True)
    fetch_parser.add_argument("--bytes-dir", type=Path, required=True)
    fetch_parser.add_argument("--receipt", type=Path, required=True)
    plan_parser = subparsers.add_parser("plan")
    plan_parser.add_argument("--manifest", type=Path, required=True)
    plan_parser.add_argument("--output", type=Path, required=True)
    publish_parser = subparsers.add_parser("publish")
    publish_parser.add_argument("--manifest", type=Path, required=True)
    publish_parser.add_argument("--bytes-dir", type=Path, required=True)
    publish_parser.add_argument("--plan", type=Path, required=True)
    publish_parser.add_argument("--output", type=Path, required=True)
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--manifest", type=Path, required=True)
    verify_parser.add_argument("--output", type=Path, required=True)
    consumer_parser = subparsers.add_parser("consumer-status")
    consumer_parser.add_argument("--manifest", type=Path, required=True)
    consumer_parser.add_argument("--terminal-receipt-sha256", required=True)
    consumer_parser.add_argument("--resolution-outcome", required=True)
    consumer_parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        if arguments.command == "fetch":
            fetch_authority(arguments.manifest.resolve(), arguments.bytes_dir.resolve(), arguments.receipt.resolve())
            print(f"compose-mirror: authority 32/32 complete: {arguments.receipt}")
            return 0
        if arguments.command == "plan":
            decision = make_plan(arguments.manifest.resolve(), arguments.output.resolve())
            print(f"compose-mirror: anonymous Raft plan decision={decision}")
            return 0 if decision in {"publish-all-absent", "resume-partial-exact", "noop-complete-identical"} else 2
        if arguments.command == "publish":
            publish(
                arguments.manifest.resolve(),
                arguments.bytes_dir.resolve(),
                arguments.plan.resolve(),
                arguments.output.resolve(),
            )
            print(f"compose-mirror: resumable immutable Maven PUT complete: {arguments.output}")
            return 0
        if arguments.command == "verify":
            verify(arguments.manifest.resolve(), arguments.output.resolve())
            print(f"compose-mirror: anonymous terminal readback 32/32 complete: {arguments.output}")
            return 0
        write_consumer_closure_receipt(
            arguments.manifest.resolve(),
            arguments.terminal_receipt_sha256,
            arguments.resolution_outcome,
            arguments.output.resolve(),
        )
        print(
            "compose-mirror: combined consumer closure "
            f"status recorded for outcome={arguments.resolution_outcome}: {arguments.output}"
        )
        return 0
    except MirrorError as error:
        print(f"compose-mirror: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
