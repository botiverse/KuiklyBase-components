#!/usr/bin/env python3
"""Stage-to-Raft publisher for DatetimeKMM releases (Raft task #106).

The Gradle build has no remote repository. It stages each publication into a
task-scoped file Maven repository that the workflow creates empty; this script
generates the authority manifest from that staging directory (and only from
it), classifies the Raft side conflict-first, and the single writer publishes
the plan-frozen bundle through the server-side atomic release ledger, then
reads every file back byte-compared.

State machine (frozen by review):
  stage first -> exhaustive classify ->
    ALL_ABSENT            -> atomic claim/stage/commit of the frozen bundle,
                             then N/N GET+SHA readback
    ALL_PRESENT_IDENTICAL -> 0 staged objects, but still GET+SHA against the
                             staged bytes: a verified no-op, not a blind skip
    anything else (partial set, extra owned key, different remote bytes)
                          -> 0 staged objects, fail closed, human decision

Contract:
  - the manifest is generated only from the freshly staged directory: every
    expected file must be present and every staged file must be expected --
    old versions, maven-metadata.xml and sidecars turn the run red here;
  - POM cross-binding: every staged POM must carry dev.raft.sourceSha and the
    scm tag equal to DATETIME_SOURCE_SHA, which must equal the dispatch SHA;
  - never overwrite: the release claim binds the exact object set and its
    manifest digest; the ledger refuses any pre-existing canonical object
    below the owned prefixes, overlapping active claims and ordinary
    writers, staging is create-only and invisible, commit is the only
    visibility linearization point, and any post-claim failure aborts
    terminally with zero public mutation;
  - transport discipline as reviewed in task #104: HTTPS only, exact Raft
    origin pin, redirects rejected before another request, HEAD/GET/PUT only
    on the Maven lane (the ledger client speaks claim/stage/commit with
    Bearer auth), a stable non-credential User-Agent on every request (the
    edge bans library defaults with 403/1010 before the request reaches the
    Worker), credentials from the environment and never logged;
  - the control-plane listing (public, unauthenticated) enumerates primary
    artifacts for the aggregate conflict check; it folds checksum/signature
    companions and skips malformed raw keys, so this lane proves exact-set
    equality over primaries and does not claim a raw-prefix inventory.

Env: RAFT_ARTIFACTS_USERNAME / RAFT_ARTIFACTS_PUBLISH_TOKEN (required),
     RAFT_RELEASE_TASK_ID (required for release), DATETIME_SOURCE_SHA
     (required for manifest), GITHUB_RUN_ID / GITHUB_RUN_ATTEMPT /
     GITHUB_SHA (optional, receipt only).

Subcommands:
  manifest --staging DIR --expect FILE --version V --output m.json
  classify --manifest m.json --output plan.json
  release  --manifest m.json --plan plan.json --bundle B --token-receipt-out T --output R
  verify   --manifest m.json [--output receipt.json]
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
import re
import sys
import tarfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Optional

RAFT_ARTIFACTS_BASE_URL = "https://maven.artifacts.botiverse.dev"
CONTROL_PLANE_BASE_URL = "https://artifacts.botiverse.dev"
CONTROL_PLANE_LISTING_PATH = "/api/scopes/build.raft.kuiklybase/artifacts"
# The artifact endpoints sit behind an edge that bans library-default user
# agents (403/1010 before the request reaches the Worker). Every request this
# lane makes -- Maven wire and control plane alike -- carries a stable,
# non-credential UA.
PUBLISH_USER_AGENT = "raft-datetime-publish/1.0"

PATH_PREFIX = "build/raft/kuiklybase/datetime"
PATH_LANE_RE = re.compile(r"build/raft/kuiklybase/datetime(?:[-/].*)?")
PATH_RE = re.compile(r"[A-Za-z0-9._/-]+")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
SHA40_RE = re.compile(r"[0-9a-f]{40}")


class PublishError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PublishError(message)


# --- transport (pattern reviewed in task #104) -------------------------------


def canonical_base_url(value: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise PublishError(f"base URL is invalid: {error}") from error
    require(parsed.scheme == "https", "base URL must use HTTPS")
    require(parsed.hostname is not None, "base URL must have a host")
    require(
        parsed.username is None and parsed.password is None,
        "base URL must not contain credentials",
    )
    require(not parsed.query and not parsed.fragment, "base URL must not contain a query or fragment")
    require(parsed.path in {"", "/"}, "base URL must be an origin without a path")
    hostname = parsed.hostname.lower()
    netloc = hostname if port in {None, 443} else f"{hostname}:{port}"
    return urllib.parse.urlunsplit(("https", netloc, "", "", ""))


class RejectRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):  # type: ignore[override]
        del file_pointer, message, headers
        source = urllib.parse.urlsplit(request.full_url)
        target = urllib.parse.urlsplit(new_url)
        raise PublishError(
            "repository redirect rejected before another request: "
            f"HTTP {code}, {source.scheme}://{source.netloc} -> {target.scheme}://{target.netloc}"
        )


class RepositoryClient:
    """Minimal HEAD/GET/PUT client pinned to the reviewed Raft origin."""

    def __init__(
        self,
        base_url: str,
        username: str,
        password: str,
        *,
        expected_base_url: str = RAFT_ARTIFACTS_BASE_URL,
        opener: Optional[urllib.request.OpenerDirector] = None,
    ) -> None:
        require(
            canonical_base_url(base_url) == canonical_base_url(expected_base_url),
            f"base URL must exactly match the reviewed endpoint {canonical_base_url(expected_base_url)}",
        )
        require(username != "" and password != "", "repository credentials are missing")
        require(
            "\r" not in username + password and "\n" not in username + password,
            "repository credentials are malformed",
        )
        self.base_url = canonical_base_url(base_url)
        encoded = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        self.authorization = f"Basic {encoded}"
        self.opener = opener or urllib.request.build_opener(RejectRedirectHandler())

    def _url(self, relative: str) -> str:
        require(
            relative != "" and not relative.startswith("/") and ".." not in relative.split("/"),
            f"unsafe repository path: {relative!r}",
        )
        return f"{self.base_url}/{urllib.parse.quote(relative, safe='/._-')}"

    def request(self, relative: str, method: str, body: bytes | None = None) -> tuple[int, bytes]:
        require(method in {"HEAD", "GET", "PUT"}, f"unsupported repository method: {method}")
        request = urllib.request.Request(
            self._url(relative), method=method, data=body,
            headers={
                "Authorization": self.authorization,
                "User-Agent": PUBLISH_USER_AGENT,
            },
        )
        try:
            with self.opener.open(request, timeout=60) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            # An HTTP status is an answer, not a transport failure; callers
            # decide which statuses are acceptable. Never log headers here.
            return error.code, error.read() or b""
        except urllib.error.URLError as error:
            raise PublishError(f"transport error for {relative}: {error.reason}") from error


def list_scope_primaries(opener: Optional[urllib.request.OpenerDirector] = None) -> list[dict]:
    """Public control-plane listing of primary artifacts under the scope.
    Unauthenticated by design: the publish token is never sent to this host."""
    base = canonical_base_url(CONTROL_PLANE_BASE_URL)
    url = f"{base}{CONTROL_PLANE_LISTING_PATH}"
    request = urllib.request.Request(
        url,
        method="GET",
        headers={"User-Agent": PUBLISH_USER_AGENT, "Accept": "application/json"},
    )
    opener = opener or urllib.request.build_opener(RejectRedirectHandler())
    try:
        with opener.open(request, timeout=60) as response:
            require(response.status == 200, f"control-plane listing HTTP {response.status}")
            body = response.read()
    except urllib.error.HTTPError as error:
        raise PublishError(f"control-plane listing HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise PublishError(f"control-plane listing transport error: {error.reason}") from error
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PublishError(f"control-plane listing is not JSON: {error}") from error
    require(isinstance(payload, dict), "control-plane listing root must be an object")
    artifacts = payload.get("artifacts")
    require(isinstance(artifacts, list), "control-plane listing has no artifacts array")
    for item in artifacts:
        require(isinstance(item, dict) and isinstance(item.get("key"), str), "invalid listing entry")
    return artifacts


# --- manifest (staging is the only authority) --------------------------------


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def load_expected(path: Path) -> list[str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise PublishError(f"cannot read expected list {path}: {error}") from error
    expected = [line.strip() for line in lines if line.strip()]
    require(expected, "expected list is empty")
    seen: set[str] = set()
    for relative in expected:
        require(
            PATH_RE.fullmatch(relative) is not None
            and not relative.startswith("/")
            and ".." not in relative.split("/")
            and "//" not in relative,
            f"unsafe expected path: {relative!r}",
        )
        require(
            PATH_LANE_RE.fullmatch(relative) is not None,
            f"expected path outside the datetime lane: {relative!r}",
        )
        require(relative not in seen, f"duplicate expected path: {relative}")
        seen.add(relative)
    return sorted(seen)


SIDECAR_SUFFIXES = (".sha1", ".md5", ".sha256", ".sha512")


def allowed_aux(staged_set: set[str], expected_set: set[str]) -> set[str]:
    """Local-only aux files Gradle legitimately generates next to a staged
    publication: checksum companions OF the expected primaries, and the
    artifact-level maven-metadata.xml (+ its companions). These carry zero
    authority, are never an uploader input, and are never uploaded -- but they
    must be exactly these, anything else is contamination."""
    aux: set[str] = set()
    artifact_dirs = {path.rsplit("/", 2)[0] for path in expected_set}
    for rel in staged_set - expected_set:
        for suffix in SIDECAR_SUFFIXES:
            if rel.endswith(suffix) and rel[: -len(suffix)] in expected_set:
                aux.add(rel)
                break
        else:
            parent, _, name = rel.rpartition("/")
            base = name
            for suffix in SIDECAR_SUFFIXES:
                if base.endswith(suffix):
                    base = base[: -len(suffix)]
            if base == "maven-metadata.xml" and parent in artifact_dirs:
                aux.add(rel)
    return aux


def parse_pom_provenance(pom_path: Path, label: str) -> dict[str, str]:
    """Parse a staged POM's direct GAV plus the provenance nodes
    (properties/dev.raft.sourceSha and scm/tag), tolerating the Maven default
    namespace. Values hidden in XML comments never parse as nodes, so they
    cannot spoof the binding. Parent-POM coordinates are not accepted."""
    import xml.etree.ElementTree as ET

    try:
        tree = ET.parse(pom_path)
    except ET.ParseError as error:
        raise PublishError(f"staged POM is not valid XML: {label}") from error
    root = tree.getroot()
    tag = root.tag
    require(tag.endswith("project"), f"staged POM root is not <project>: {label}")
    ns = tag[: -len("project")] if tag != "project" else ""

    def child(name: str, under: Optional[str] = None) -> Optional[str]:
        parent = root if under is None else root.find(f"{ns}{under}")
        if parent is None:
            return None
        element = parent.find(f"{ns}{name}")
        if element is None or element.text is None:
            return None
        return element.text.strip()

    out = {
        "groupId": child("groupId"),
        "artifactId": child("artifactId"),
        "version": child("version"),
        "sourceSha": child("dev.raft.sourceSha", under="properties"),
        "scmTag": child("tag", under="scm"),
    }
    for key, value in out.items():
        require(value, f"staged POM lacks its own {key} node: {label}")
    return out  # type: ignore[return-value]


def build_manifest(staging: Path, expected: list[str], version: str) -> dict:
    require(staging.is_dir(), f"staging directory missing: {staging}")
    source_sha = os.environ.get("DATETIME_SOURCE_SHA", "")
    require(SHA40_RE.fullmatch(source_sha) is not None, "DATETIME_SOURCE_SHA is not the exact 40-hex dispatch SHA")

    expected_set = set(expected)
    staged_files = sorted(
        str(p.relative_to(staging)) for p in staging.rglob("*") if p.is_file()
    )
    staged_set = set(staged_files)
    missing = sorted(expected_set - staged_set)
    require(not missing, f"expected files were not staged: {', '.join(missing[:5])}")
    surprise = sorted(staged_set - expected_set - allowed_aux(staged_set, expected_set))
    require(
        not surprise,
        "staging contains files outside the expected publication set "
        f"(old version, metadata or sidecar contamination): {', '.join(surprise[:5])}",
    )
    aux = sorted(staged_set - expected_set)
    if aux:
        print(f"  staging aux (local-only, never uploaded): {len(aux)} file(s)")

    files = []
    for relative in expected:
        body = (staging / relative).read_bytes()
        require(len(body) > 0, f"staged byte empty: {relative}")
        files.append({"path": relative, "sha256": sha256_bytes(body), "size": len(body)})

    # Cross-binding: every staged POM must point back at the dispatch SHA via
    # REAL XML nodes (properties/dev.raft.sourceSha and scm/tag) -- strings
    # hidden in XML comments never parse as nodes and cannot spoof this.
    pom_files = [f for f in files if f["path"].endswith(".pom")]
    require(pom_files, "no POM staged -- publication shape is wrong")
    for entry in pom_files:
        pom = parse_pom_provenance(staging / entry["path"], entry["path"])
        require(
            pom["sourceSha"] == source_sha,
            f"staged POM's sourceSha node is not the dispatch SHA: {entry['path']}",
        )
        require(
            pom["scmTag"] == source_sha,
            f"staged POM's scm tag node is not the dispatch SHA: {entry['path']}",
        )

    # GAV/version binding: every file's version directory must be this exact
    # release version (or its -ohos variant), and every staged POM's parsed
    # groupId/artifactId/version must equal its path coordinates -- a manifest
    # can never claim one version while carrying another's bytes, and a POM
    # cannot smuggle the right strings past in a comment or parent block.
    allowed_versions = {version, version + "-ohos"}
    for entry in files:
        dir_version = entry["path"].rsplit("/", 2)[-2]
        require(
            dir_version in allowed_versions,
            f"staged path is not the release version {version}: {entry['path']}",
        )
    for entry in pom_files:
        pom = parse_pom_provenance(staging / entry["path"], entry["path"])
        parts = entry["path"].split("/")
        require(
            pom["groupId"] == "build.raft.kuiklybase",
            f"staged POM groupId is not the lane group: {entry['path']}",
        )
        require(
            pom["artifactId"] == parts[-3],
            f"staged POM artifactId does not match its path: {entry['path']}",
        )
        require(
            pom["version"] == parts[-2],
            f"staged POM version does not match its directory: {entry['path']}",
        )

    return {
        "schema": 1,
        "version": version,
        "sourceSha": source_sha,
        "destination": RAFT_ARTIFACTS_BASE_URL,
        "fileCount": len(files),
        "files": files,
    }


def load_manifest(path: Path) -> dict:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PublishError(f"cannot read manifest {path}: {error}") from error
    require(isinstance(manifest, dict), f"manifest root must be an object: {path}")
    require(manifest.get("schema") == 1, "unsupported manifest schema")
    require(SHA40_RE.fullmatch(str(manifest.get("sourceSha", ""))) is not None, "manifest sourceSha invalid")
    require(manifest.get("destination") == RAFT_ARTIFACTS_BASE_URL, "manifest destination is not the Raft origin")
    files = manifest.get("files")
    require(isinstance(files, list) and files, "manifest files must be a non-empty list")
    paths: set[str] = set()
    for entry in files:
        require(isinstance(entry, dict) and set(entry) == {"path", "sha256", "size"}, "invalid manifest file entry")
        relative = entry["path"]
        require(
            isinstance(relative, str)
            and PATH_RE.fullmatch(relative) is not None
            and not relative.startswith("/")
            and ".." not in relative.split("/")
            and "//" not in relative
            and PATH_LANE_RE.fullmatch(relative) is not None,
            f"unsafe manifest path: {relative!r}",
        )
        require(relative not in paths, f"duplicate manifest path: {relative}")
        paths.add(relative)
        require(SHA256_RE.fullmatch(str(entry.get("sha256", ""))) is not None, f"invalid manifest sha256: {relative}")
        require(isinstance(entry.get("size"), int) and entry["size"] > 0, f"invalid manifest size: {relative}")
    require(manifest.get("fileCount") == len(files), "manifest fileCount mismatch")
    return manifest


def owned_prefixes(manifest: dict) -> list[str]:
    """Canonical version-directory prefixes WITH trailing slash — same form as
    atomic_release.owned_prefixes_from_paths and the server ledger."""
    return sorted({entry["path"].rsplit("/", 1)[0] + "/" for entry in manifest["files"]})


# --- classify / publish / verify ----------------------------------------------


def make_plan(client: RepositoryClient, manifest: dict, list_primaries) -> dict:
    """Conflict-first aggregate classification over the manifest's owned
    exact-version prefixes, then per-path probing of the expected set."""
    receipt_paths = {entry["path"] for entry in manifest["files"]}
    prefixes = owned_prefixes(manifest)
    primaries = list_primaries()
    unexpected = sorted(
        item["key"]
        for item in primaries
        if any(item["key"].startswith(prefix) for prefix in prefixes)
        and item["key"] not in receipt_paths
    )
    require(
        not unexpected,
        "unexpected carriers under owned exact-version prefixes — stop, human decision: "
        + ", ".join(unexpected[:5]),
    )

    missing: list[str] = []
    existing: list[str] = []
    for entry in manifest["files"]:
        relative = entry["path"]
        status, _ = client.request(relative, "HEAD")
        require(status in {200, 404}, f"unexpected HEAD status {status} for {relative}")
        if status == 404:
            missing.append(relative)
            continue
        get_status, body = client.request(relative, "GET")
        require(get_status == 200, f"unexpected GET status {get_status} for existing {relative}")
        require(
            sha256_bytes(body) == entry["sha256"],
            f"Raft already carries DIFFERENT bytes at {relative} — stop, human decision",
        )
        existing.append(relative)
    total = len(manifest["files"])
    require(
        len(missing) == total or len(existing) == total,
        "partial publication state on Raft "
        f"({len(existing)} present / {len(missing)} missing) — stop, human decision",
    )
    decision = "publish" if len(missing) == total else "noop-verified"
    return {
        "decision": decision,
        "fileCount": total,
        "ownedPrefixes": prefixes,
        "missing": sorted(missing),
        "existingIdentical": sorted(existing),
    }


def load_plan(path: Path) -> dict:
    try:
        plan = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PublishError(f"cannot read plan {path}: {error}") from error
    require(isinstance(plan, dict), f"plan root must be an object: {path}")
    require(plan.get("decision") in {"publish", "noop-verified"}, "unknown plan decision")
    return plan


def command_manifest(args: argparse.Namespace) -> int:
    expected = load_expected(Path(args.expect))
    manifest = build_manifest(Path(args.staging), expected, args.version)
    Path(args.output).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"publish manifest: {manifest['fileCount']} files, version={manifest['version']}, sourceSha={manifest['sourceSha'][:12]}")
    return 0


def command_audit_staging(args: argparse.Namespace) -> int:
    """Pattern-based staging audit for the preflight (legal-gate) jobs, where
    no expected list exists yet: every staged file must be a plausible primary
    or an allowed local aux (checksum companion / artifact-level metadata).
    Anything else is a third kind of file and turns the run red."""
    staging = Path(args.staging)
    require(staging.is_dir(), f"staging directory missing: {staging}")
    staged = sorted(str(p.relative_to(staging)) for p in staging.rglob("*") if p.is_file())
    require(staged, f"staging directory is empty: {staging}")
    bad = []
    for rel in staged:
        if PATH_RE.fullmatch(rel) is None or rel.startswith("/") or ".." in rel.split("/") or "//" in rel:
            bad.append(rel)
            continue
        name = rel.rsplit("/", 1)[-1]
        base = name
        for suffix in SIDECAR_SUFFIXES:
            if base.endswith(suffix):
                base = base[: -len(suffix)]
                break
        if base == "maven-metadata.xml":
            continue  # artifact-level metadata (+ companions): local-only aux
        if name != base:
            continue  # checksum companion of a staged file: local-only aux
        if PATH_LANE_RE.fullmatch(rel) is None:
            bad.append(rel)
    require(
        not bad,
        f"staging audit found files that are neither lane primaries nor local aux: {', '.join(bad[:5])}",
    )
    print(f"staging audit ok: {len(staged)} file(s), primaries+local-aux only")
    return 0


def command_revalidate(args: argparse.Namespace) -> int:
    """Release-wide pre-PUT barrier, run once in the plan job over ALL shard
    stagings before the global classification: every manifest byte is re-hashed
    against its staged file and every staging root is re-enumerated for added
    or unknown primaries. A failure here means zero PUTs anywhere."""
    manifest = load_manifest(Path(args.manifest))
    roots = [Path(p) for p in args.staging_roots]
    require(roots, "revalidate needs at least one staging root")
    manifest_paths = {entry["path"] for entry in manifest["files"]}
    for root in roots:
        require(root.is_dir(), f"shard staging missing: {root}")
        staged = {str(p.relative_to(root)) for p in root.rglob("*") if p.is_file()}
        aux = allowed_aux(staged, manifest_paths)
        unexpected = sorted(p for p in (staged - aux) if p not in manifest_paths)
        require(
            not unexpected,
            f"shard staging {root} gained or carries unknown files: {', '.join(unexpected[:5])}",
        )
    bodies = 0
    for entry in manifest["files"]:
        relative = entry["path"]
        candidates = [root / relative for root in roots]
        present = [c for c in candidates if c.is_file()]
        require(len(present) == 1, f"manifest path staged {len(present)} times (expected exactly 1): {relative}")
        body = present[0].read_bytes()
        require(
            sha256_bytes(body) == entry["sha256"],
            f"staged bytes changed after manifest: {relative}",
        )
        bodies += 1
    require(bodies == manifest["fileCount"], "revalidate count mismatch")
    print(f"revalidate: {bodies}/{manifest['fileCount']} staged bytes re-hashed across {len(roots)} shard roots, zero drift")
    return 0


def command_freeze(args: argparse.Namespace) -> int:
    """Package the revalidated staged bytes into THE frozen bundle: one
    deterministic tar holding exactly the manifest primaries (never aux), so
    every later consumer uses byte-identical input. The bundle digest is the
    integrity anchor writers verify against the global plan."""
    import tarfile

    manifest = load_manifest(Path(args.manifest))
    roots = [Path(p) for p in args.staging_roots]
    require(roots, "freeze needs at least one staging root")
    out = Path(args.output)
    digest_lines = []
    with tarfile.open(out, "w") as tar:
        for entry in sorted(manifest["files"], key=lambda f: f["path"]):
            relative = entry["path"]
            candidates = [root / relative for root in roots]
            present = [c for c in candidates if c.is_file()]
            require(len(present) == 1, f"manifest path staged {len(present)} times for freeze: {relative}")
            body = present[0].read_bytes()
            require(
                sha256_bytes(body) == entry["sha256"],
                f"staged bytes changed after manifest: {relative}",
            )
            info = tarfile.TarInfo(relative)
            info.size = len(body)
            info.mtime = 0
            info.mode = 0o444
            tar.addfile(info, io.BytesIO(body))
            digest_lines.append(relative)
    require(len(digest_lines) == manifest["fileCount"], "freeze count mismatch")
    bundle_sha = sha256_bytes(out.read_bytes())
    print(f"freeze: {len(digest_lines)} primaries -> {out} sha256={bundle_sha}")
    if args.digest_out:
        Path(args.digest_out).write_text(bundle_sha + "\n", encoding="utf-8")
    return 0


def command_merge(args: argparse.Namespace) -> int:
    """Merge per-platform shard manifests into the single aggregate release
    manifest. Every shard must agree on version and dispatch source SHA; file
    sets must be disjoint (one owner per path)."""
    manifests = [load_manifest(Path(p)) for p in args.manifests]
    require(len(manifests) >= 2, "merge needs at least two shard manifests")
    versions = {m["version"] for m in manifests}
    shas = {m["sourceSha"] for m in manifests}
    require(len(versions) == 1, f"shard manifests disagree on version: {sorted(versions)}")
    require(len(shas) == 1, "shard manifests disagree on dispatch sourceSha")
    seen: set[str] = set()
    files: list[dict] = []
    for manifest in manifests:
        for entry in manifest["files"]:
            require(entry["path"] not in seen, f"path owned by two shard manifests: {entry['path']}")
            seen.add(entry["path"])
            files.append(entry)
    merged = {
        "schema": 1,
        "version": manifests[0]["version"],
        "sourceSha": manifests[0]["sourceSha"],
        "destination": RAFT_ARTIFACTS_BASE_URL,
        "fileCount": len(files),
        "files": sorted(files, key=lambda f: f["path"]),
    }
    Path(args.output).write_text(json.dumps(merged, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"publish merge: {len(files)} files across {len(manifests)} shards, version={merged['version']}")
    return 0


def command_classify(args: argparse.Namespace) -> int:
    manifest = load_manifest(Path(args.manifest))
    client = client_from_env()
    plan = make_plan(client, manifest, list_primaries_from_env)
    Path(args.output).write_text(json.dumps(plan, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"publish classify: decision={plan['decision']} files={plan['fileCount']}")
    return 0



def _well_formed_claim_state(receipt) -> str | None:
    """Return claim state only when the inspect body is a well-formed dict
    with a non-empty string state. Empty/malformed bodies ({}) from
    AtomicReleaseClient._json map to None (unknown) — never "not committed".
    """
    if not isinstance(receipt, dict):
        return None
    state = receipt.get("state")
    if isinstance(state, str) and state:
        return state
    return None


def _reconcile_claim_after_commit_ambiguity(
    client,
    claim_id: str,
    reason: str,
    *,
    staged: int,
    original_error: Exception,
    attempts: int = 5,
):
    """Bounded exact-claim reconcile after an ambiguous commit response.

    - committed (well-formed) → durable recovered receipt, no abort
    - non-committed (well-formed) → abort allowed, re-raise original
    - unknown/malformed/raised inspect → retry; never abort on unknown
    - exhausted unknown → fail without abort (do not misclassify terminal state)
    """
    last_inspect = None
    last_inspect_error = None
    for attempt in range(1, attempts + 1):
        try:
            last_inspect = client.inspect(claim_id)
            last_inspect_error = None
        except Exception as inspect_error:  # noqa: BLE001
            last_inspect = None
            last_inspect_error = inspect_error
            print(
                f"release reconcile inspect attempt {attempt}/{attempts} raised: {inspect_error}",
                file=sys.stderr,
            )
            continue
        state = _well_formed_claim_state(last_inspect)
        if state == "committed":
            print(
                f"release committed under {claim_id} despite ambiguous commit response; "
                f"recovered via inspect attempt {attempt}/{attempts} (no abort)",
                file=sys.stderr,
            )
            return {
                "state": "committed",
                "idempotent": True,
                "recoveredFrom": "commit-response-ambiguity",
                "originalError": reason,
                "inspect": last_inspect,
                "reconcileAttempts": attempt,
            }
        if state is not None:
            print(
                f"release failed after claim (staged={staged}, state={state}); "
                "aborting with immutable reason",
                file=sys.stderr,
            )
            try:
                client.abort(claim_id, reason)
                print("release aborted: terminal, zero public mutation", file=sys.stderr)
            except Exception as abort_error:  # noqa: BLE001
                print(f"ABORT FAILED (original error stands): {abort_error}", file=sys.stderr)
            raise original_error
        print(
            f"release reconcile inspect attempt {attempt}/{attempts}: "
            f"unknown/malformed body {last_inspect!r} (not treated as staged)",
            file=sys.stderr,
        )
    detail = (
        f"inspect error: {last_inspect_error}"
        if last_inspect_error is not None
        else f"last inspect body: {last_inspect!r}"
    )
    print(
        f"release failed after claim (staged={staged}); claim state unresolved "
        f"after {attempts} inspect attempts; no abort attempted ({detail})",
        file=sys.stderr,
    )
    raise PublishError(
        f"claim state unresolved after ambiguous commit under {claim_id}; "
        f"no abort attempted ({detail})"
    ) from original_error



def _claim_intent_path(output: str | Path) -> Path:
    """Sidecar next to the terminal publish receipt; survives exhausted-unknown
    so a later run can recover the exact claim without guessing."""
    out = Path(output)
    return out.with_name(out.stem + ".claim-intent.json")


def _write_claim_intent(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _load_claim_intent(path: Path) -> dict | None:
    if not path.is_file():
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def _build_release_identity(manifest: dict, task_id: str) -> tuple[list[dict], list[str], str, dict]:
    import atomic_release as ar

    objects = [
        {"path": entry["path"], "sha256": entry["sha256"], "size": entry["size"]}
        for entry in sorted(manifest["files"], key=lambda f: f["path"].encode("utf-8"))
    ]
    prefixes = ar.owned_prefixes_from_paths([entry["path"] for entry in objects])
    digest = ar.manifest_digest(objects)
    claim_payload = {
        "repository": "maven",
        "taskId": task_id,
        "version": manifest["version"],
        "sourceSha": manifest["sourceSha"],
        "ownedPrefixes": prefixes,
        "manifestDigest": digest,
        "objects": objects,
        "leaseSeconds": 600,
    }
    return objects, prefixes, digest, claim_payload


def _write_atomic_publish_receipt(
    *,
    output: Path,
    claim_id: str,
    claim: dict,
    task_id: str,
    manifest: dict,
    prefixes: list[str],
    digest: str,
    objects: list[dict],
    commit_receipt: dict,
) -> dict:
    receipt = {
        "status": "committed",
        "claimId": claim_id,
        "generation": claim.get("generation"),
        "idempotent": commit_receipt.get("idempotent", claim.get("idempotent")),
        "taskId": task_id,
        "version": manifest["version"],
        "sourceSha": manifest["sourceSha"],
        "ownedPrefixes": prefixes,
        "manifestDigest": digest,
        "fileCount": len(objects),
        "files": objects,
        "commitReceipt": commit_receipt,
        "provenance": {
            "publishSourceExact": os.environ.get("GITHUB_SHA", ""),
            "runId": os.environ.get("GITHUB_RUN_ID", ""),
            "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
        },
    }
    output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return receipt


def command_release(args: argparse.Namespace) -> int:
    """Single-writer ATOMIC release via the deployed v1 ledger (task #106).

    Shape: the plan-frozen bundle is the only byte source. The claim binds the
    exact release identity (task/version/sourceSha/prefixes/manifest digest)
    against the actual stored constrained token server-side; every object is
    staged invisibly; ONE CAS commit is the only visibility linearization
    point. Any failure after a successful claim aborts with an immutable
    reason, so a failed release leaves zero public mutation by construction --
    not by client checks.

    Client preflight (early-fail only, never the closure): bundle digest ==
    global plan digest; every member to memory, exact path set + size + SHA;
    token introspection receipt; landed master re-fetch."""
    import atomic_release as ar

    manifest = load_manifest(Path(args.manifest))
    plan = load_plan(Path(args.plan))
    require(plan["decision"] == "publish", "plan decision is not publish — nothing to release")

    bundle = Path(args.bundle)
    require(bundle.is_file(), f"frozen bundle missing: {bundle}")
    recorded = plan.get("bundleSha256")
    require(isinstance(recorded, str) and SHA256_RE.fullmatch(recorded) is not None,
            "global plan carries no bundle digest")
    require(
        sha256_bytes(bundle.read_bytes()) == recorded,
        "frozen bundle digest does not match the global plan — not the same bundle",
    )

    manifest_paths = {entry["path"] for entry in manifest["files"]}
    bodies: dict[str, bytes] = {}
    with tarfile.open(bundle, "r") as tar:
        members = [m for m in tar.getmembers() if m.isfile()]
        require(
            sorted(m.name for m in members) == sorted(manifest_paths),
            "bundle member set does not equal the manifest primary set",
        )
        for member in members:
            require(
                PATH_RE.fullmatch(member.name) is not None
                and not member.name.startswith("/")
                and ".." not in member.name.split("/"),
                f"unsafe bundle member: {member.name!r}",
            )
            extracted = tar.extractfile(member)
            require(extracted is not None, f"cannot read bundle member {member.name}")
            bodies[member.name] = extracted.read()
    require(len(bodies) == manifest["fileCount"], "bundle member count mismatch")
    for entry in manifest["files"]:
        body = bodies[entry["path"]]
        require(len(body) == entry["size"], f"bundle size mismatch: {entry['path']}")
        require(
            sha256_bytes(body) == entry["sha256"],
            f"bundle bytes do not match the manifest: {entry['path']}",
        )

    # Token self-receipt (identity continuity into the terminal aggregate).
    token_receipt = fetch_token_self_receipt()
    Path(args.token_receipt_out).write_text(
        json.dumps(token_receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )

    # Landed-master re-fetch inside the command itself.
    dispatch_sha = os.environ.get("GITHUB_SHA", "")
    if dispatch_sha:
        import subprocess

        fetch = subprocess.run(["git", "fetch", "--quiet", "origin", "master"], check=False)
        require(fetch.returncode == 0, "cannot fetch origin master at the barrier")
        current = subprocess.run(
            ["git", "rev-parse", "origin/master"], check=True, capture_output=True, text=True
        ).stdout.strip()
        require(
            current == dispatch_sha == manifest["sourceSha"],
            "landed master drifted between admission and the first stage — stop",
        )

    # Build the exact release identity the ledger will verify server-side.
    task_id = os.environ.get("RAFT_RELEASE_TASK_ID", "")
    require(task_id != "", "RAFT_RELEASE_TASK_ID is required for the release claim")
    objects, prefixes, digest, claim_payload = _build_release_identity(manifest, task_id)
    client = release_client_from_env()
    intent_path = _claim_intent_path(args.output)

    claim = client.claim(claim_payload)
    claim_id = claim.get("claimId")
    require(isinstance(claim_id, str) and claim_id != "", f"claim response carries no claimId: {sorted(claim)}")
    # Persist recoverable identity BEFORE any stage/commit so exhausted-unknown
    # and a later all-present run can rebind the original claim.
    _write_claim_intent(
        intent_path,
        {
            "claimId": claim_id,
            "generation": claim.get("generation"),
            "taskId": task_id,
            "version": manifest["version"],
            "sourceSha": manifest["sourceSha"],
            "ownedPrefixes": prefixes,
            "manifestDigest": digest,
            "fileCount": len(objects),
            "objects": objects,
            "claimPayload": claim_payload,
        },
    )
    print(f"release claim: id={claim_id} generation={claim.get('generation')} prefixes={len(prefixes)} objects={len(objects)}")
    print(f"release claim intent written: {intent_path}")

    def _committed_receipt(source: dict | None = None) -> dict:
        payload = dict(source or {})
        payload["state"] = "committed"
        payload.setdefault("idempotent", True)
        return payload

    # Exact retry of a release whose original claim already committed: the
    # ledger returns the ORIGINAL claim id, and staging is closed on a
    # terminal claim — inspect first and short-circuit so the retry mutates
    # nothing. A retry of a still-staged claim falls through and resumes:
    # per-object staging is byte-identical-idempotent, commit is idempotent.
    if client.inspect(claim_id).get("state") == "committed":
        commit_receipt = _committed_receipt({"idempotent": True})
        print(f"release already committed under {claim_id}: exact retry, zero mutations")
    else:
        staged = 0
        try:
            for entry in objects:
                client.stage_object(claim_id, entry["path"], bodies[entry["path"]])
                staged += 1
                print(f"  staged {entry['path']}")
            inspection = client.inspect(claim_id)
            staged_count = inspection.get("stagedCount", inspection.get("staged"))
            require(
                staged_count == len(objects) or staged_count is None,
                f"server inspect reports staged={staged_count}, expected {len(objects)}",
            )
            commit_receipt = client.commit(claim_id)
            # HTTP success with empty/malformed JSON becomes {} from the client.
            # Treat any non-committed success body as the same ambiguity class
            # as a raised transport error: re-inspect before aborting.
            if not (isinstance(commit_receipt, dict) and commit_receipt.get("state") == "committed"):
                raise ar.AtomicReleaseError(
                    "commit-response-malformed",
                    f"commit returned non-committed body: {commit_receipt!r}",
                )
        except (PublishError, ar.AtomicReleaseError) as error:
            # Commit-response ambiguity: the server may have already committed
            # when the response is lost or unparseable. Reconcile via bounded
            # exact-claim inspect BEFORE any abort. Malformed/empty inspect
            # bodies ({} from AtomicReleaseClient._json) and raised inspect
            # errors are UNKNOWN — never treat them as staged, never abort.
            # Abort is allowed only when a well-formed inspect proves a
            # non-committed state.
            reason = f"{type(error).__name__}: {error}"
            commit_receipt = _reconcile_claim_after_commit_ambiguity(
                client, claim_id, reason, staged=staged, original_error=error
            )

    require(
        isinstance(commit_receipt, dict) and commit_receipt.get("state") == "committed",
        f"commit receipt is not committed: {commit_receipt!r}",
    )

    _write_atomic_publish_receipt(
        output=Path(args.output),
        claim_id=claim_id,
        claim=claim,
        task_id=task_id,
        manifest=manifest,
        prefixes=prefixes,
        digest=digest,
        objects=objects,
        commit_receipt=commit_receipt,
    )
    print(f"release committed: {len(objects)}/{len(objects)} objects visible atomically, receipt {args.output}")
    return 0

def command_recover_claim(args: argparse.Namespace) -> int:
    """All-present / cross-run recovery of a committed atomic claim.

    When a prior release committed publicly but left no publish-receipt (e.g.
    exhausted unknown inspect after ambiguous commit), the next workflow classifies
    all-present as noop-verified. Before accepting a generic complete receipt,
    re-bind the exact release identity via idempotent claim + inspect:

    - committed → write durable atomic publish-receipt (original claimId)
    - not committed / unknown → exit 2 so the caller may fall through only when
      policy allows a non-atomic complete path (workflow currently fails closed
      unless recovery succeeds, or uses verify only when recover exits 3)

    Exit codes:
      0 = recovered committed receipt written
      2 = no recoverable committed claim for this identity
      1 = hard error
    """
    import atomic_release as ar

    manifest = load_manifest(Path(args.manifest))
    task_id = os.environ.get("RAFT_RELEASE_TASK_ID", "")
    require(task_id != "", "RAFT_RELEASE_TASK_ID is required for claim recovery")
    objects, prefixes, digest, claim_payload = _build_release_identity(manifest, task_id)
    client = release_client_from_env()
    intent_path = _claim_intent_path(args.output)
    intent = _load_claim_intent(intent_path)

    claim_id = None
    claim: dict = {}
    # Prefer durable intent from a prior ambiguous run, then idempotent claim.
    if intent and isinstance(intent.get("claimId"), str) and intent.get("claimId"):
        if intent.get("manifestDigest") == digest and intent.get("taskId") == task_id:
            claim_id = intent["claimId"]
            claim = {"claimId": claim_id, "generation": intent.get("generation"), "idempotent": True}
            print(f"recover: using claim intent {claim_id} from {intent_path}")
    if claim_id is None:
        try:
            claim = client.claim(claim_payload)
        except ar.AtomicReleaseError as error:
            print(f"recover: claim failed ({error}); no recoverable identity", file=sys.stderr)
            return 2
        claim_id = claim.get("claimId")
        if not isinstance(claim_id, str) or not claim_id:
            print(f"recover: claim response carries no claimId: {sorted(claim)}", file=sys.stderr)
            return 2
        _write_claim_intent(
            intent_path,
            {
                "claimId": claim_id,
                "generation": claim.get("generation"),
                "taskId": task_id,
                "version": manifest["version"],
                "sourceSha": manifest["sourceSha"],
                "ownedPrefixes": prefixes,
                "manifestDigest": digest,
                "fileCount": len(objects),
                "objects": objects,
                "claimPayload": claim_payload,
            },
        )

    # Bounded inspect for committed (same unknown-safe rules as post-commit reconcile).
    last = None
    for attempt in range(1, 6):
        try:
            last = client.inspect(claim_id)
        except Exception as inspect_error:  # noqa: BLE001
            print(f"recover inspect attempt {attempt}/5 raised: {inspect_error}", file=sys.stderr)
            continue
        state = _well_formed_claim_state(last)
        if state == "committed":
            commit_receipt = {
                "state": "committed",
                "idempotent": True,
                "recoveredFrom": "all-present-claim-reconcile",
                "inspect": last,
                "reconcileAttempts": attempt,
            }
            _write_atomic_publish_receipt(
                output=Path(args.output),
                claim_id=claim_id,
                claim=claim,
                task_id=task_id,
                manifest=manifest,
                prefixes=prefixes,
                digest=digest,
                objects=objects,
                commit_receipt=commit_receipt,
            )
            print(
                f"recover: committed receipt for claim {claim_id} written to {args.output}",
                file=sys.stderr,
            )
            return 0
        if state is not None:
            print(f"recover: claim {claim_id} state={state} (not committed)", file=sys.stderr)
            return 2
        print(f"recover inspect attempt {attempt}/5: unknown body {last!r}", file=sys.stderr)
    print(f"recover: claim {claim_id} state unresolved after 5 inspects", file=sys.stderr)
    return 2


def command_verify(args: argparse.Namespace) -> int:
    manifest = load_manifest(Path(args.manifest))
    only = set(load_expected(Path(args.only))) if getattr(args, "only", None) else None
    selected = [entry for entry in manifest["files"] if only is None or entry["path"] in only]
    require(selected, "verify selection is empty")
    client = client_from_env()
    verified = 0
    for entry in selected:
        relative = entry["path"]
        status, body = client.request(relative, "GET")
        require(status == 200, f"verify GET HTTP {status} for {relative}")
        require(
            sha256_bytes(body) == entry["sha256"],
            f"Raft readback digest mismatch for {relative}",
        )
        verified += 1
    require(verified == len(selected), "verify count mismatch")
    # Terminal aggregate proof: re-enumerate the selection's owned prefixes
    # and require exact-set equality -- nothing missing, nothing extra.
    receipt_paths = {entry["path"] for entry in selected}
    prefixes = sorted({entry["path"].rsplit("/", 1)[0] + "/" for entry in selected})
    primaries = list_primaries_from_env()
    remote_set = {
        item["key"]
        for item in primaries
        if any(item["key"].startswith(prefix) for prefix in prefixes)
    }
    extra = sorted(remote_set - receipt_paths)
    missing_remote = sorted(receipt_paths - remote_set)
    require(not extra, f"unexpected carriers present after publish: {', '.join(extra[:5])}")
    require(not missing_remote, f"primaries missing from listing after publish: {', '.join(missing_remote[:5])}")
    receipt_out = getattr(args, "output", None)
    if receipt_out:
        receipt = {
            "status": "complete",
            "destination": RAFT_ARTIFACTS_BASE_URL,
            "version": manifest["version"],
            "sourceSha": manifest["sourceSha"],
            "fileCount": verified,
            "files": sorted(selected, key=lambda f: f["path"]),
            "provenance": {
                "publishSourceExact": os.environ.get("GITHUB_SHA", ""),
                "runId": os.environ.get("GITHUB_RUN_ID", ""),
                "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
            },
        }
        Path(receipt_out).write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"verify: {verified}/{len(selected)} byte-identical on Raft")
    return 0


def release_client_from_env():
    import atomic_release as ar

    return ar.AtomicReleaseClient()


def client_from_env() -> RepositoryClient:
    username = os.environ.get("RAFT_ARTIFACTS_USERNAME", "")
    token = os.environ.get("RAFT_ARTIFACTS_PUBLISH_TOKEN", "")
    base_url = os.environ.get("RAFT_ARTIFACTS_URL", RAFT_ARTIFACTS_BASE_URL)
    return RepositoryClient(base_url, username, token)


def list_primaries_from_env() -> list[dict]:
    return list_scope_primaries()


def fetch_token_self_receipt(opener: Optional[urllib.request.OpenerDirector] = None) -> dict:
    """Server-side self-receipt for the injected task token (review B3).

    Calls GET /api/tokens on the reviewed API origin WITH the credential --
    this introspection is the one sanctioned credential use off the Maven
    wire host, and it is the same reviewed botiverse service. The raw token's
    SHA-256 must match exactly one server record's full hash; the record must
    be unrevoked, unexpired, and carry the expected scope/publish cap. The
    returned receipt carries only the 16-hex hash prefix; the full hash and
    the token itself never leave the runner's memory."""
    token = os.environ.get("RAFT_ARTIFACTS_PUBLISH_TOKEN", "")
    username = os.environ.get("RAFT_ARTIFACTS_USERNAME", "raft-ci") or "raft-ci"
    require(token != "", "repository credentials are missing")
    require("\r" not in token and "\n" not in token, "repository credentials are malformed")
    base = canonical_base_url(CONTROL_PLANE_BASE_URL)
    url = f"{base}/api/tokens"
    encoded = base64.b64encode(f"{username}:{token}".encode("utf-8")).decode("ascii")
    request = urllib.request.Request(
        url,
        method="GET",
        headers={
            "Authorization": f"Basic {encoded}",
            "User-Agent": PUBLISH_USER_AGENT,
            "Accept": "application/json",
        },
    )
    opener = opener or urllib.request.build_opener(RejectRedirectHandler())
    try:
        with opener.open(request, timeout=60) as response:
            require(response.status == 200, f"token introspection HTTP {response.status}")
            body = response.read()
    except urllib.error.HTTPError as error:
        raise PublishError(f"token introspection HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise PublishError(f"token introspection transport error: {error.reason}") from error
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PublishError(f"token introspection is not JSON: {error}") from error
    records = payload.get("tokens") if isinstance(payload, dict) else None
    if records is None and isinstance(payload, list):
        records = payload
    require(isinstance(records, list), "token introspection has no token list")

    local_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
    matches = [r for r in records if isinstance(r, dict) and r.get("hash") == local_hash]
    require(len(matches) == 1, f"token self-identification matched {len(matches)} server records, expected exactly 1")
    record = matches[0]
    require(record.get("revokedAt") in {None, 0}, "task token is already revoked")
    expires_at = record.get("expiresAt")
    require(isinstance(expires_at, int) and expires_at != 0, "task token has no expiry (expected a short-lived cap)")
    now_ms = int(time.time() * 1000)
    require(expires_at > now_ms, "task token is already expired")
    # Structural grant check against the live TokenSummary schema (allowlist:
    # hash/label/createdAt/expiresAt/revokedAt/grants -- there is NO top-level
    # principal; the principal lives inside each Grant). The cap must be
    # EXACTLY the minimal lane grant: a single grant, exact lane scope,
    # permissions drawn only from {read, publish} and including publish, and
    # an agent-shaped principal. Extra grants, admin anywhere, or a
    # human-shaped principal are all red.
    grants = record.get("grants")
    require(isinstance(grants, list) and grants, "task token carries no grants")
    require(len(grants) == 1, f"task token must carry exactly one minimal grant, got {len(grants)}")
    grant = grants[0]
    require(isinstance(grant, dict), "grant is not an object")
    require(grant.get("scope") == "build.raft.kuiklybase", "grant scope is not exactly build.raft.kuiklybase")
    permissions = grant.get("permissions")
    require(isinstance(permissions, list), "grant permissions missing")
    require("publish" in permissions, "grant lacks publish permission")
    require(
        set(permissions) <= {"read", "publish"},
        f"grant carries beyond-minimal permissions: {sorted(set(permissions) - {'read', 'publish'})}",
    )
    principal = grant.get("principal")
    require(
        isinstance(principal, dict) and principal.get("kind") == "agent" and bool(principal.get("id")),
        "lane grant principal is not a non-empty agent principal",
    )
    # Exact principal identity: the release declares which agent principal the
    # task token must belong to, and the record must match it exactly -- a
    # well-shaped record for the WRONG agent is a red, not a pass.
    expected_principal = os.environ.get("RAFT_ARTIFACTS_EXPECT_PRINCIPAL", "")
    require(expected_principal != "", "RAFT_ARTIFACTS_EXPECT_PRINCIPAL is not set (required)")
    require(
        principal.get("id") == expected_principal,
        f"lane grant principal is not the expected release principal",
    )
    return {
        "hashPrefix": local_hash[:16],
        "fullHashMatchedLocally": True,
        "principal": grant.get("principal"),
        "grants": grants,
        "expiresAt": expires_at,
        "revokedAtAbsent": True,
    }


def command_token_receipt(args: argparse.Namespace) -> int:
    receipt = fetch_token_self_receipt()
    Path(args.output).write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"token self-receipt: hashPrefix={receipt['hashPrefix']} expiry bound, grants cover the lane")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p_manifest = sub.add_parser("manifest")
    p_manifest.add_argument("--staging", required=True)
    p_manifest.add_argument("--expect", required=True)
    p_manifest.add_argument("--version", required=True)
    p_manifest.add_argument("--output", required=True)
    p_manifest.set_defaults(func=command_manifest)
    p_audit = sub.add_parser("audit-staging")
    p_audit.add_argument("--staging", required=True)
    p_audit.set_defaults(func=command_audit_staging)
    p_merge = sub.add_parser("merge")
    p_merge.add_argument("--manifests", required=True, nargs="+")
    p_merge.add_argument("--output", required=True)
    p_merge.set_defaults(func=command_merge)
    p_reval = sub.add_parser("revalidate")
    p_reval.add_argument("--manifest", required=True)
    p_reval.add_argument("--staging-roots", required=True, nargs="+")
    p_reval.set_defaults(func=command_revalidate)
    p_freeze = sub.add_parser("freeze")
    p_freeze.add_argument("--manifest", required=True)
    p_freeze.add_argument("--staging-roots", required=True, nargs="+")
    p_freeze.add_argument("--output", required=True)
    p_freeze.add_argument("--digest-out", default="")
    p_freeze.set_defaults(func=command_freeze)
    p_classify = sub.add_parser("classify")
    p_classify.add_argument("--manifest", required=True)
    p_classify.add_argument("--output", required=True)
    p_classify.set_defaults(func=command_classify)
    p_release = sub.add_parser("release")
    p_release.add_argument("--manifest", required=True)
    p_release.add_argument("--plan", required=True)
    p_release.add_argument("--bundle", required=True)
    p_release.add_argument("--token-receipt-out", required=True)
    p_release.add_argument("--output", required=True)
    p_release.set_defaults(func=command_release)
    p_recover = sub.add_parser(
        "recover-claim",
        help="idempotent claim+inspect to recover a committed atomic receipt on all-present path",
    )
    p_recover.add_argument("--manifest", required=True)
    p_recover.add_argument("--output", required=True)
    p_recover.set_defaults(func=command_recover_claim)
    p_verify = sub.add_parser("verify")
    p_verify.add_argument("--manifest", required=True)
    p_verify.add_argument("--output", default="")
    p_verify.add_argument("--only", default="")
    p_verify.set_defaults(func=command_verify)
    p_token = sub.add_parser("token-receipt")
    p_token.add_argument("--output", required=True)
    p_token.set_defaults(func=command_token_receipt)
    return parser


def main(argv: list[str]) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except PublishError as error:
        print(f"PUBLISH FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
