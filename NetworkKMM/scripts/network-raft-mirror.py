#!/usr/bin/env python3
"""Mirror exact NetworkKMM GitHub Packages primaries into Raft Artifacts.

GitHub Packages is the authenticated byte authority produced by Gradle. Raft
receives only the primary paths declared by network-publication-manifest.sh.
Existing identical partial state is resumable; divergent or unexpected primary
state fails closed. Checksum sidecars are server-owned and are never uploaded.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Optional


RAFT_BASE_URL = "https://maven.artifacts.botiverse.dev"
CONTROL_BASE_URL = "https://artifacts.botiverse.dev"
GITHUB_BASE_PREFIX = "https://maven.pkg.github.com/"
EXPECTED_GITHUB_REPOSITORY = "botiverse/KuiklyBase-components"
POSITIVE_CONTROL_PATH = (
    "build/raft/kuiklybase/datetime/0.1.0-raft.0/"
    "datetime-0.1.0-raft.0.pom"
)
SCOPE = "com.tencent.kuiklybase"
USER_AGENT = "networkkmm-raft-primary-mirror/1.0"
PATH_RE = re.compile(r"[A-Za-z0-9._/-]+")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
HEX40_RE = re.compile(r"[0-9a-f]{40}")


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


def safe_relative(relative: str) -> None:
    require(
        bool(relative)
        and not relative.startswith("/")
        and PATH_RE.fullmatch(relative) is not None
        and ".." not in relative.split("/")
        and "//" not in relative,
        f"unsafe repository path: {relative!r}",
    )


def canonical_origin(value: str, name: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise MirrorError(f"{name} URL is invalid: {error}") from error
    require(parsed.scheme == "https" and parsed.hostname is not None, f"{name} must use HTTPS")
    require(parsed.username is None and parsed.password is None, f"{name} must not carry credentials")
    require(not parsed.query and not parsed.fragment, f"{name} must not carry query or fragment")
    hostname = parsed.hostname.lower()
    netloc = hostname if port in {None, 443} else f"{hostname}:{port}"
    return urllib.parse.urlunsplit(("https", netloc, parsed.path.rstrip("/"), "", ""))


class RejectRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):  # type: ignore[override]
        del fp, msg, headers
        source = urllib.parse.urlsplit(request.full_url)
        target = urllib.parse.urlsplit(newurl)
        raise MirrorError(
            f"redirect rejected before another request: HTTP {code}, "
            f"{source.scheme}://{source.netloc} -> {target.scheme}://{target.netloc}"
        )


class GithubPackagesRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Allow GitHub's signed download host without forwarding credentials."""

    def redirect_request(self, request, fp, code, msg, headers, newurl):  # type: ignore[override]
        del fp, msg, headers
        source = urllib.parse.urlsplit(request.full_url)
        target = urllib.parse.urlsplit(newurl)
        require(
            code in {301, 302, 303, 307, 308}
            and source.scheme == "https"
            and source.hostname == "maven.pkg.github.com"
            and target.scheme == "https"
            and target.hostname == "github-registry-files.githubusercontent.com",
            f"GitHub Packages redirect target changed: HTTP {code}, {target.scheme}://{target.netloc}",
        )
        return urllib.request.Request(
            newurl,
            method="GET",
            headers={"User-Agent": USER_AGENT, "Accept": "application/octet-stream"},
        )


class Reader:
    def __init__(
        self,
        name: str,
        base_url: str,
        expected_base_url: str,
        authorization: str = "",
        opener: Optional[urllib.request.OpenerDirector] = None,
        before_redirect=None,
    ) -> None:
        self.name = name
        self.base_url = canonical_origin(base_url, name)
        require(self.base_url == canonical_origin(expected_base_url, name), f"{name} origin changed")
        self.authorization = authorization
        self.opener = opener or urllib.request.build_opener(RejectRedirectHandler())
        self.before_redirect = before_redirect

    def get(self, relative: str, attempts: int = 3) -> tuple[int, bytes]:
        safe_relative(relative)
        headers = {"User-Agent": USER_AGENT, "Accept": "application/octet-stream"}
        if self.authorization:
            headers["Authorization"] = self.authorization
        request = urllib.request.Request(
            f"{self.base_url}/{urllib.parse.quote(relative, safe='/._-')}",
            method="GET",
            headers=headers,
        )
        last_error = ""
        for attempt in range(attempts):
            try:
                with self.opener.open(request, timeout=60) as response:
                    body = response.read()
                    status = response.status
                    final_url = response.geturl()
                if self.before_redirect is not None:
                    self.before_redirect(request.full_url, final_url)
                return status, body
            except MirrorError:
                raise
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    return 404, b""
                if 500 <= error.code <= 599 and attempt + 1 < attempts:
                    last_error = f"HTTP {error.code}"
                    time.sleep(attempt + 1)
                    continue
                raise MirrorError(f"{self.name} GET HTTP {error.code}: {relative}") from error
            except urllib.error.URLError as error:
                last_error = str(error.reason)
                if attempt + 1 < attempts:
                    time.sleep(attempt + 1)
                    continue
                raise MirrorError(f"{self.name} GET transport failure for {relative}: {last_error}") from error
        raise MirrorError(f"{self.name} GET transport failure for {relative}: {last_error}")


class Writer:
    def __init__(self, username: str, token: str, opener: Optional[urllib.request.OpenerDirector] = None) -> None:
        require(username and token, "Raft writer credential is missing")
        require("\r" not in username + token and "\n" not in username + token, "Raft credential is malformed")
        self.base_url = canonical_origin(RAFT_BASE_URL, "Raft writer")
        self.authorization = "Basic " + base64.b64encode(f"{username}:{token}".encode()).decode()
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
        try:
            with self.opener.open(request, timeout=60) as response:
                return response.status
        except MirrorError:
            raise
        except urllib.error.HTTPError as error:
            return error.code
        except urllib.error.URLError as error:
            raise MirrorError(
                f"Raft PUT outcome is ambiguous for {relative}; inspect anonymously: {error.reason}"
            ) from error


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise MirrorError(f"cannot read JSON {path}: {error}") from error
    require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def write_json_exclusive(path: Path, value: dict[str, Any]) -> None:
    require(not path.exists(), f"refusing to replace output: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def current_tuple() -> dict[str, str]:
    carrier_source = os.environ.get("GITHUB_SHA", "")
    artifact_source = os.environ.get("NETWORK_ARTIFACT_SOURCE_SHA", carrier_source)
    require(HEX40_RE.fullmatch(carrier_source) is not None, "GITHUB_SHA must be the exact 40-hex carrier checkout")
    require(HEX40_RE.fullmatch(artifact_source) is not None, "NETWORK_ARTIFACT_SOURCE_SHA must be exact 40-hex")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    attempt = os.environ.get("GITHUB_RUN_ATTEMPT", "")
    require(run_id.isdigit() and attempt.isdigit(), "GitHub run tuple is missing")
    return {
        "carrierSourceExact": carrier_source,
        "artifactSourceExact": artifact_source,
        "runId": run_id,
        "runAttempt": attempt,
    }


def required_paths(tasks: list[str], version: str) -> list[str]:
    require(tasks and len(tasks) == len(set(tasks)), "required task set is empty or duplicated")
    require(version and "SNAPSHOT" not in version and not version.endswith("-ohos"), "invalid base version")
    script = Path(__file__).with_name("network-publication-manifest.sh")
    result: list[str] = []
    for task in tasks:
        completed = subprocess.run(
            [
                "bash",
                "-c",
                'source "$1"; network_required_paths_for "$2" "$3"',
                "network-raft-mirror",
                str(script),
                task,
                version,
            ],
            text=True,
            capture_output=True,
            check=False,
        )
        require(completed.returncode == 0, f"publication manifest rejected task {task}: {completed.stderr.strip()}")
        paths = [line for line in completed.stdout.splitlines() if line]
        require(paths, f"publication manifest returned no paths for {task}")
        for relative in paths:
            safe_relative(relative)
            require(relative.startswith("com/tencent/kuiklybase/"), f"path outside owned group: {relative}")
        result.extend(paths)
    require(len(result) == len(set(result)), "required task paths overlap")
    return sorted(result)


def load_required_paths_file(path: str) -> tuple[list[str], str, list[str]]:
    payload = load_json(Path(path))
    require(payload.get("schema") == 1 and payload.get("status") == "complete", "required-paths file is incomplete")
    version = payload.get("version")
    tasks = payload.get("tasks")
    paths = payload.get("paths")
    require(isinstance(version, str), "required-paths version invalid")
    require(isinstance(tasks, list) and all(isinstance(item, str) for item in tasks), "required-paths tasks invalid")
    require(isinstance(paths, list) and all(isinstance(item, str) for item in paths), "required-paths paths invalid")
    expected = required_paths(tasks, version)
    require(paths == expected, "required-paths file differs from the publication manifest")
    return tasks, version, paths


def github_reader() -> Reader:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    username = os.environ.get("GITHUB_PACKAGES_USERNAME", os.environ.get("GITHUB_ACTOR", ""))
    token = os.environ.get("GITHUB_PACKAGES_TOKEN", os.environ.get("GITHUB_TOKEN", ""))
    require(repository == EXPECTED_GITHUB_REPOSITORY, "GitHub Packages authority repository changed")
    require(username and token, "GitHub Packages read credential is missing")
    require("\r" not in username + token and "\n" not in username + token, "GitHub credential is malformed")
    authorization = "Basic " + base64.b64encode(f"{username}:{token}".encode()).decode()
    base = GITHUB_BASE_PREFIX + repository
    def verify_redirect(source_url: str, final_url: str) -> None:
        source = urllib.parse.urlsplit(source_url)
        target = urllib.parse.urlsplit(final_url)
        if target.hostname != source.hostname:
            require(
                source.hostname == "maven.pkg.github.com"
                and target.scheme == "https"
                and target.hostname == "github-registry-files.githubusercontent.com",
                f"GitHub Packages final download host changed: {target.scheme}://{target.netloc}",
            )

    return Reader(
        "GitHub Packages authority",
        base,
        base,
        authorization,
        urllib.request.build_opener(GithubPackagesRedirectHandler()),
        verify_redirect,
    )


def validate_pom_source(relative: str, body: bytes, source_exact: str) -> None:
    if not relative.endswith(".pom"):
        return
    try:
        root = ET.fromstring(body)
    except ET.ParseError as error:
        raise MirrorError(f"invalid authority POM: {relative}") from error
    namespace = {"m": root.tag.split("}")[0].lstrip("{")} if root.tag.startswith("{") else {}
    prefix = "m:" if namespace else ""
    property_value = root.findtext(f"{prefix}properties/{prefix}dev.raft.sourceSha", namespaces=namespace)
    tag = root.findtext(f"{prefix}scm/{prefix}tag", namespaces=namespace)
    require(property_value == source_exact and tag == source_exact, f"POM source provenance mismatch: {relative}")


def authority_fetch(bytes_dir: Path, receipt_path: Path) -> None:
    required_file = os.environ.get("NETWORK_REQUIRED_PATHS_FILE", "")
    if required_file:
        tasks, version, paths = load_required_paths_file(required_file)
    else:
        tasks = os.environ.get("NETWORK_REQUIRED_TASKS", "").split()
        version = os.environ.get("MAVEN_VERSION", "")
        paths = required_paths(tasks, version)
    provenance = current_tuple()
    require(not bytes_dir.exists(), f"refusing to mix authority bytes into existing directory: {bytes_dir}")
    bytes_dir.mkdir(parents=True)
    client = github_reader()
    control_status, _ = client.get(POSITIVE_CONTROL_PATH)
    require(control_status == 200, "GitHub Packages positive control failed; authority read is void")
    entries: list[dict[str, Any]] = []
    for relative in paths:
        status, body = client.get(relative)
        require(status == 200, f"GitHub Packages authority is missing required primary: {relative}")
        validate_pom_source(relative, body, provenance["artifactSourceExact"])
        destination = bytes_dir / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(body)
        entries.append({"path": relative, "size": len(body), "sha256": sha256_bytes(body)})
    receipt = {
        "schema": 1,
        "status": "complete",
        "authority": "github-packages",
        "version": version,
        "tasks": tasks,
        "fileCount": len(entries),
        "provenance": provenance,
        "files": entries,
    }
    write_json_exclusive(receipt_path, receipt)


def snapshot_required(output: Path) -> None:
    tasks = os.environ.get("NETWORK_REQUIRED_TASKS", "").split()
    version = os.environ.get("MAVEN_VERSION", "")
    write_json_exclusive(output, {
        "schema": 1,
        "status": "complete",
        "version": version,
        "tasks": tasks,
        "paths": required_paths(tasks, version),
    })


def load_receipt(path: Path) -> dict[str, Any]:
    receipt = load_json(path)
    require(receipt.get("schema") == 1 and receipt.get("status") == "complete", "receipt is not complete")
    require(receipt.get("authority") == "github-packages", "receipt authority changed")
    require(receipt.get("provenance") == current_tuple(), "receipt run/source tuple changed")
    tasks = receipt.get("tasks")
    version = receipt.get("version")
    require(isinstance(tasks, list) and all(isinstance(item, str) for item in tasks), "receipt tasks invalid")
    require(isinstance(version, str), "receipt version invalid")
    paths = required_paths(tasks, version)
    files = receipt.get("files")
    require(isinstance(files, list) and receipt.get("fileCount") == len(files), "receipt file count invalid")
    require(len(files) == len(paths), "receipt does not cover the exact manifest path set")
    seen: set[str] = set()
    for entry in files:
        require(isinstance(entry, dict) and set(entry) == {"path", "size", "sha256"}, "invalid receipt file")
        relative = entry.get("path")
        require(isinstance(relative, str), "receipt path invalid")
        safe_relative(relative)
        require(relative not in seen, f"duplicate receipt path: {relative}")
        seen.add(relative)
        require(isinstance(entry.get("size"), int) and entry["size"] >= 0, f"invalid receipt size: {relative}")
        require(isinstance(entry.get("sha256"), str) and SHA256_RE.fullmatch(entry["sha256"]), f"invalid SHA: {relative}")
    require(seen == set(paths), "receipt path set differs from publication manifest")
    return receipt


def entries(receipt: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {entry["path"]: entry for entry in receipt["files"]}


def validate_staged(receipt: dict[str, Any], bytes_dir: Path) -> None:
    require(bytes_dir.is_dir() and not bytes_dir.is_symlink(), "authority bytes directory is missing or unsafe")
    for relative, entry in entries(receipt).items():
        path = bytes_dir / relative
        require(path.is_file() and not path.is_symlink(), f"authority byte missing or unsafe: {relative}")
        body = path.read_bytes()
        require(len(body) == entry["size"] and sha256_bytes(body) == entry["sha256"], f"authority byte changed: {relative}")


def list_scope() -> list[str]:
    url = f"{CONTROL_BASE_URL}/api/scopes/{SCOPE}/artifacts"
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    opener = urllib.request.build_opener(RejectRedirectHandler())
    try:
        with opener.open(request, timeout=60) as response:
            body = response.read()
            require(response.status == 200, f"Raft control-plane HTTP {response.status}")
    except urllib.error.HTTPError as error:
        raise MirrorError(f"Raft control-plane HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise MirrorError(f"Raft control-plane transport failure: {error.reason}") from error
    try:
        payload = json.loads(body.decode())
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise MirrorError("Raft control-plane response is not JSON") from error
    require(isinstance(payload, dict) and payload.get("scope") == SCOPE, "Raft scope response changed")
    artifacts = payload.get("artifacts")
    require(isinstance(artifacts, list), "Raft scope artifacts missing")
    keys: list[str] = []
    for item in artifacts:
        require(isinstance(item, dict) and isinstance(item.get("key"), str), "invalid Raft scope entry")
        safe_relative(item["key"])
        keys.append(item["key"])
    require(len(keys) == len(set(keys)), "duplicate Raft scope keys")
    return keys


def classify(receipt: dict[str, Any], raft: Reader, listed_keys: list[str]) -> dict[str, Any]:
    expected = entries(receipt)
    prefixes = {relative.rsplit("/", 1)[0] for relative in expected}
    unexpected = sorted(
        key for key in listed_keys
        if any(key.startswith(prefix + "/") for prefix in prefixes) and key not in expected
    )
    existing: list[str] = []
    missing: list[str] = []
    divergent: list[dict[str, Any]] = []
    for relative, entry in sorted(expected.items()):
        status, body = raft.get(relative)
        if status == 404:
            missing.append(relative)
        else:
            require(status == 200, f"Raft anonymous GET HTTP {status}: {relative}")
            digest = sha256_bytes(body)
            if len(body) == entry["size"] and digest == entry["sha256"]:
                existing.append(relative)
            else:
                divergent.append({"path": relative, "size": len(body), "sha256": digest})
    listed_expected = set(listed_keys) & set(expected)
    observed = set(existing) | {item["path"] for item in divergent}
    listing_mismatch = sorted(listed_expected ^ observed)
    if unexpected or divergent or listing_mismatch:
        decision = "hold-conflict"
    elif not missing:
        decision = "noop-complete-identical"
    elif existing:
        decision = "resume-partial-exact"
    else:
        decision = "publish-all-absent"
    return {
        "decision": decision,
        "existing": existing,
        "missing": missing,
        "divergent": divergent,
        "unexpected": unexpected,
        "listingMismatch": listing_mismatch,
    }


def make_plan(receipt_path: Path, output: Path) -> str:
    receipt = load_receipt(receipt_path)
    raft = Reader("Raft anonymous read", RAFT_BASE_URL, RAFT_BASE_URL)
    control, _ = raft.get(POSITIVE_CONTROL_PATH)
    require(control == 200, "Raft positive control failed; absence evidence is void")
    remote = classify(receipt, raft, list_scope())
    plan = {
        "schema": 1,
        "receiptSha256": sha256_file(receipt_path),
        "fileCount": receipt["fileCount"],
        "provenance": current_tuple(),
        "authentication": "none",
        "remote": remote,
    }
    write_json_exclusive(output, plan)
    return remote["decision"]


def load_plan(receipt_path: Path, plan_path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    receipt = load_receipt(receipt_path)
    plan = load_json(plan_path)
    require(plan.get("schema") == 1 and plan.get("authentication") == "none", "plan identity changed")
    require(plan.get("receiptSha256") == sha256_file(receipt_path), "plan receipt binding changed")
    require(plan.get("fileCount") == receipt["fileCount"], "plan file count changed")
    require(plan.get("provenance") == current_tuple(), "plan run/source tuple changed")
    remote = plan.get("remote")
    require(isinstance(remote, dict), "plan remote state missing")
    require(remote.get("decision") in {"publish-all-absent", "resume-partial-exact"}, "plan is not publishable")
    require(remote.get("divergent") == [] and remote.get("unexpected") == [], "plan contains conflicts")
    require(remote.get("listingMismatch") == [], "plan listing/GET evidence disagrees")
    expected = set(entries(receipt))
    existing = remote.get("existing")
    missing = remote.get("missing")
    require(isinstance(existing, list) and isinstance(missing, list), "plan path sets invalid")
    require(set(existing).isdisjoint(missing) and set(existing) | set(missing) == expected, "plan path coverage changed")
    require(missing, "publishable plan has no missing paths")
    return receipt, plan


def publish(receipt_path: Path, bytes_dir: Path, plan_path: Path, output: Path) -> None:
    receipt, plan = load_plan(receipt_path, plan_path)
    validate_staged(receipt, bytes_dir)
    username = os.environ.get("RAFT_ARTIFACTS_USERNAME", "raft-ci")
    token = os.environ.get("RAFT_ARTIFACTS_PUBLISH_TOKEN", "")
    writer = Writer(username, token)
    reader = Reader("Raft anonymous read", RAFT_BASE_URL, RAFT_BASE_URL)
    uploaded: list[str] = []
    raced_identical: list[str] = []
    for relative in plan["remote"]["missing"]:
        body = (bytes_dir / relative).read_bytes()
        status = writer.put(relative, body)
        if status in {200, 201, 204}:
            uploaded.append(relative)
            continue
        if status == 409:
            read_status, remote = reader.get(relative)
            require(
                read_status == 200 and len(remote) == len(body) and sha256_bytes(remote) == sha256_bytes(body),
                f"Raft PUT race is not byte-identical: {relative}",
            )
            raced_identical.append(relative)
            continue
        raise MirrorError(f"Raft PUT failed with HTTP {status}: {relative}")
    write_json_exclusive(output, {
        "schema": 1,
        "status": "writes-complete",
        "receiptSha256": sha256_file(receipt_path),
        "provenance": current_tuple(),
        "uploaded": uploaded,
        "racedIdentical": raced_identical,
    })


def verify(receipt_path: Path, output: Path, attempts: int = 6) -> None:
    receipt = load_receipt(receipt_path)
    raft = Reader("Raft anonymous read", RAFT_BASE_URL, RAFT_BASE_URL)
    remote: dict[str, Any] | None = None
    for attempt in range(attempts):
        remote = classify(receipt, raft, list_scope())
        if remote["decision"] == "noop-complete-identical":
            break
        if attempt + 1 < attempts and remote["decision"] == "resume-partial-exact":
            time.sleep(attempt + 1)
            continue
        break
    require(remote is not None and remote["decision"] == "noop-complete-identical", f"Raft terminal state is not complete-identical: {remote}")
    write_json_exclusive(output, {
        "schema": 1,
        "status": "complete-identical",
        "authentication": "none",
        "receiptSha256": sha256_file(receipt_path),
        "fileCount": receipt["fileCount"],
        "provenance": current_tuple(),
        "files": receipt["files"],
    })


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    authority = sub.add_parser("authority")
    authority.add_argument("--bytes-dir", type=Path, required=True)
    authority.add_argument("--receipt", type=Path, required=True)
    snapshot = sub.add_parser("snapshot-required")
    snapshot.add_argument("--output", type=Path, required=True)
    plan = sub.add_parser("plan")
    plan.add_argument("--receipt", type=Path, required=True)
    plan.add_argument("--output", type=Path, required=True)
    writer = sub.add_parser("publish")
    writer.add_argument("--receipt", type=Path, required=True)
    writer.add_argument("--bytes-dir", type=Path, required=True)
    writer.add_argument("--plan", type=Path, required=True)
    writer.add_argument("--output", type=Path, required=True)
    terminal = sub.add_parser("verify")
    terminal.add_argument("--receipt", type=Path, required=True)
    terminal.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "snapshot-required":
            snapshot_required(args.output.resolve())
        elif args.command == "authority":
            authority_fetch(args.bytes_dir.resolve(), args.receipt.resolve())
        elif args.command == "plan":
            decision = make_plan(args.receipt.resolve(), args.output.resolve())
            print(f"network-raft-mirror: plan decision={decision}")
            return 0 if decision != "hold-conflict" else 2
        elif args.command == "publish":
            publish(args.receipt.resolve(), args.bytes_dir.resolve(), args.plan.resolve(), args.output.resolve())
        else:
            verify(args.receipt.resolve(), args.output.resolve())
        return 0
    except MirrorError as error:
        print(f"network-raft-mirror: ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
