#!/usr/bin/env python3
"""Mirror the verified DatetimeKMM authority bytes into Raft Artifacts.

Raft task #99: the 0.1.0-raft.0 / -ohos publication matrix (34 files) exists
only on GitHub Packages. authority-readback.sh fetches those bytes read-only
and freezes a digest receipt; this script is the ONLY write half: it plans
against the Raft side (fail-closed unless the coordinate set is fully absent
or already fully present with identical bytes), uploads the staged bytes, and
then reads every file back from Raft, byte-compared against the receipt.

Contract:
  - the receipt is the single source of what may be written: path set, sizes
    and sha256 are re-validated here, bound to the frozen publication exact
    and the frozen authority version — a receipt for anything else is refused
    before any network traffic;
  - never overwrite: a path that already exists on Raft must carry the exact
    receipt bytes, otherwise the run stops and reports instead of repairing;
  - fail closed everywhere: transport error, unexpected status, redirect,
    corrupt local staging byte, or a count other than 34 all stop the run;
  - transport discipline mirrors the reviewed OHOSForks publication_http.py:
    HTTPS only, the base URL must be exactly the reviewed Raft origin,
    redirects are rejected before another request is issued, credentials come
    from the environment and are never logged, and only HEAD/GET/PUT exist;
  - the receipt written at the end records paths and sha256 only: no headers,
    no credentials, no URLs beyond the reviewed origin.

Env: RAFT_ARTIFACTS_USERNAME / RAFT_ARTIFACTS_PUBLISH_TOKEN (required),
     GITHUB_RUN_ID / GITHUB_RUN_ATTEMPT / GITHUB_SHA (optional, receipt only).

Subcommands:
  plan    --receipt R --output plan.json
  publish --receipt R --bytes-dir B --plan plan.json
  verify  --receipt R
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Optional

RAFT_ARTIFACTS_BASE_URL = "https://maven.artifacts.botiverse.dev"
# Public read-only control plane (no credentials): primary-artifact listing
# used by the conflict-first aggregate gate. Deliberately a different origin
# than the Maven wire host; the publish token is never sent here.
CONTROL_PLANE_BASE_URL = "https://artifacts.botiverse.dev"
CONTROL_PLANE_LISTING_PATH = "/api/scopes/build.raft.kuiklybase/artifacts"
# The control plane sits behind Cloudflare, which bans library-default user
# agents (Python-urllib/* answers 403/1010). A stable, non-credential UA is
# required for the exact client to be dispatchable at all (review finding).
CONTROL_PLANE_USER_AGENT = "raft-datetime-mirror/1.0"

# Server primitives this design relies on (confirmed by the service owner
# 2026-08-08, task #104 thread): a non-SNAPSHOT version-directory PUT is a
# server-side create-if-absent — an occupied path answers 409 and keeps its
# original bytes. That is per-object atomic, NOT a 34-file transaction: a
# half-written set is visible to readers, so consumers must wait for the
# terminal verify + announcement. The listing API folds checksum/signature
# companions and ignores malformed out-of-band keys, so this mirror proves
# exact-set equality over PRIMARY artifacts and does not claim a raw-prefix
# inventory; closing that residual is a server-side follow-up.

# The one authority set this mirror may carry, frozen like the readback's.
AUTHORITY_VERSION = "0.1.0-raft.0"
EXPECTED_SOURCE_EXACT = "8ffc865419ef2e210e2d78f18aedcae00ea9b9cc"
EXPECTED_TOTAL = 34
PATH_PREFIX = "build/raft/kuiklybase/datetime"
# The datetime lane is the root artifact plus every `datetime-*` target
# artifact — nothing else under the group may be carried by this mirror.
PATH_LANE_RE = re.compile(r"build/raft/kuiklybase/datetime(?:[-/].*)?")
PATH_RE = re.compile(r"[A-Za-z0-9._/-]+")
SHA256_RE = re.compile(r"[0-9a-f]{64}")


class MirrorError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise MirrorError(message)


# --- transport (pattern reviewed in OHOSForks/scripts/publication_http.py) --


def canonical_base_url(value: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise MirrorError(f"base URL is invalid: {error}") from error
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
        raise MirrorError(
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
            headers={"Authorization": self.authorization},
        )
        try:
            with self.opener.open(request, timeout=60) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            # An HTTP status is an answer, not a transport failure; callers
            # decide which statuses are acceptable. Never log headers here:
            # error responses can echo request metadata.
            return error.code, error.read() or b""
        except urllib.error.URLError as error:
            raise MirrorError(f"transport error for {relative}: {error.reason}") from error


# --- receipt / plan models ---------------------------------------------------


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def load_receipt(path: Path) -> list[dict[str, str]]:
    try:
        receipt = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise MirrorError(f"cannot read receipt {path}: {error}") from error
    require(isinstance(receipt, dict), f"receipt root must be an object: {path}")
    require(receipt.get("status") == "complete", "receipt status is not complete")
    require(receipt.get("fileCount") == EXPECTED_TOTAL, f"receipt fileCount is not {EXPECTED_TOTAL}")
    provenance = receipt.get("provenance")
    require(isinstance(provenance, dict), "receipt has no provenance")
    require(
        provenance.get("manifestSourceExact") == EXPECTED_SOURCE_EXACT,
        "receipt manifest source exact is not the frozen publication exact",
    )
    files = receipt.get("files")
    require(isinstance(files, list), "receipt files must be a list")
    require(len(files) == EXPECTED_TOTAL, f"receipt must contain exactly {EXPECTED_TOTAL} files")
    paths: set[str] = set()
    for entry in files:
        require(isinstance(entry, dict), "invalid receipt file entry")
        require(set(entry) == {"path", "sha256"}, "invalid receipt file entry keys")
        relative = entry["path"]
        digest = entry["sha256"]
        require(
            isinstance(relative, str)
            and PATH_RE.fullmatch(relative) is not None
            and not relative.startswith("/")
            and ".." not in relative.split("/")
            and "//" not in relative,
            f"unsafe receipt path: {relative!r}",
        )
        require(
            PATH_LANE_RE.fullmatch(relative) is not None,
            f"receipt path outside the datetime lane: {relative!r}",
        )
        require(
            f"/{AUTHORITY_VERSION}/" in relative or f"/{AUTHORITY_VERSION}-ohos/" in relative,
            f"receipt path is not the frozen authority version: {relative!r}",
        )
        require(relative not in paths, f"duplicate receipt path: {relative}")
        paths.add(relative)
        require(
            isinstance(digest, str) and SHA256_RE.fullmatch(digest) is not None,
            f"invalid receipt sha256: {relative}",
        )
    return files


def owned_prefixes(files: list[dict[str, str]]) -> list[str]:
    """The owned exact-version prefixes, derived from the receipt itself: the
    distinct parent directories of the 34 files (seven for this lane)."""
    prefixes = sorted({entry["path"].rsplit("/", 1)[0] for entry in files})
    require(prefixes, "receipt produced no owned prefixes")
    return prefixes


def list_scope_primaries(opener: Optional[urllib.request.OpenerDirector] = None) -> list[dict]:
    """Public control-plane listing of primary artifacts under the scope.

    Unauthenticated by design: the publish token is never sent to this host.
    Returns the raw `artifacts` array. See the header note for the folding
    boundary this surface carries."""
    base = canonical_base_url(CONTROL_PLANE_BASE_URL)
    url = f"{base}{CONTROL_PLANE_LISTING_PATH}"
    request = urllib.request.Request(
        url,
        method="GET",
        headers={
            "User-Agent": CONTROL_PLANE_USER_AGENT,
            "Accept": "application/json",
        },
    )
    opener = opener or urllib.request.build_opener(RejectRedirectHandler())
    try:
        with opener.open(request, timeout=60) as response:
            require(response.status == 200, f"control-plane listing HTTP {response.status}")
            body = response.read()
    except urllib.error.HTTPError as error:
        raise MirrorError(f"control-plane listing HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise MirrorError(f"control-plane listing transport error: {error.reason}") from error
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise MirrorError(f"control-plane listing is not JSON: {error}") from error
    require(isinstance(payload, dict), "control-plane listing root must be an object")
    artifacts = payload.get("artifacts")
    require(isinstance(artifacts, list), "control-plane listing has no artifacts array")
    for item in artifacts:
        require(isinstance(item, dict) and isinstance(item.get("key"), str), "invalid listing entry")
    return artifacts


def make_plan(
    client: RepositoryClient,
    files: list[dict[str, str]],
    list_primaries,
) -> dict:
    """Classify the Raft side, conflict-first: enumerate every primary under
    the owned exact-version prefixes before probing expected paths. Publish
    only from aggregate all-absent, noop on aggregate complete-identical, fail
    closed on anything else."""
    receipt_paths = {entry["path"] for entry in files}
    prefixes = owned_prefixes(files)

    # B3: terminal enumeration of the owned prefixes. Any primary the receipt
    # does not name -- unexpected classifier, foreign artifact, leftover from
    # another lane -- is a CONFLICT, never silently carried alongside.
    primaries = list_primaries()
    unexpected = sorted(
        item["key"]
        for item in primaries
        if any(item["key"].startswith(prefix + "/") for prefix in prefixes)
        and item["key"] not in receipt_paths
    )
    require(
        not unexpected,
        "unexpected carriers under owned exact-version prefixes — stop, human decision: "
        + ", ".join(unexpected[:5]),
    )

    missing: list[str] = []
    existing: list[str] = []
    for entry in files:
        relative = entry["path"]
        status, _ = client.request(relative, "HEAD")
        require(status in {200, 404}, f"unexpected HEAD status {status} for {relative}")
        if status == 404:
            missing.append(relative)
            continue
        # A path that already exists must carry the exact receipt bytes;
        # anything else is a foreign write we never repair automatically.
        get_status, body = client.request(relative, "GET")
        require(get_status == 200, f"unexpected GET status {get_status} for existing {relative}")
        require(
            sha256_bytes(body) == entry["sha256"],
            f"Raft already carries DIFFERENT bytes at {relative} — stop, human decision",
        )
        existing.append(relative)
    require(
        len(missing) == EXPECTED_TOTAL or len(existing) == EXPECTED_TOTAL,
        "partial publication state on Raft "
        f"({len(existing)} present / {len(missing)} missing) — stop, human decision",
    )
    decision = "publish" if len(missing) == EXPECTED_TOTAL else "noop-complete"
    return {
        "decision": decision,
        "fileCount": EXPECTED_TOTAL,
        "ownedPrefixes": prefixes,
        "missing": sorted(missing),
        "existingIdentical": sorted(existing),
    }


def load_plan(path: Path) -> dict:
    try:
        plan = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise MirrorError(f"cannot read plan {path}: {error}") from error
    require(isinstance(plan, dict), f"plan root must be an object: {path}")
    require(plan.get("fileCount") == EXPECTED_TOTAL, "plan fileCount mismatch")
    require(plan.get("decision") in {"publish", "noop-complete"}, "unknown plan decision")
    return plan


def command_plan(args: argparse.Namespace) -> int:
    files = load_receipt(Path(args.receipt))
    client = client_from_env()
    plan = make_plan(client, files, list_primaries_from_env)
    out = Path(args.output)
    out.write_text(json.dumps(plan, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"mirror plan: decision={plan['decision']} files={plan['fileCount']}")
    return 0


def command_publish(args: argparse.Namespace) -> int:
    files = load_receipt(Path(args.receipt))
    plan = load_plan(Path(args.plan))
    require(plan["decision"] == "publish", "plan decision is not publish — nothing to upload")
    require(
        sorted(plan["missing"]) == sorted(entry["path"] for entry in files),
        "plan missing set does not equal the receipt file set",
    )
    bytes_root = Path(args.bytes_dir)
    client = client_from_env()
    uploaded = 0
    for entry in files:
        relative = entry["path"]
        local = bytes_root / relative
        require(local.is_file(), f"staged byte missing: {relative}")
        body = local.read_bytes()
        require(len(body) > 0, f"staged byte empty: {relative}")
        require(
            sha256_bytes(body) == entry["sha256"],
            f"staged bytes do not match the verified receipt: {relative}",
        )
        status, _ = client.request(relative, "PUT", body)
        # The server's create-if-absent makes an occupied path a 409 with the
        # original bytes preserved. That is the never-overwrite backstop: a
        # foreign write that landed after the plan stops the run here.
        require(
            status != 409,
            f"conflict: {relative} was occupied after the plan — foreign write, stopping",
        )
        require(status in {200, 201, 204}, f"PUT failed with HTTP {status} for {relative}")
        uploaded += 1
        print(f"  put {relative}")
    require(uploaded == EXPECTED_TOTAL, f"uploaded {uploaded}, expected {EXPECTED_TOTAL}")
    print(f"mirror publish: {uploaded}/{EXPECTED_TOTAL} uploaded")
    return 0


def command_verify(args: argparse.Namespace) -> int:
    files = load_receipt(Path(args.receipt))
    client = client_from_env()
    verified = 0
    for entry in files:
        relative = entry["path"]
        status, body = client.request(relative, "GET")
        require(status == 200, f"verify GET HTTP {status} for {relative}")
        require(
            sha256_bytes(body) == entry["sha256"],
            f"Raft readback digest mismatch for {relative}",
        )
        verified += 1
    require(verified == EXPECTED_TOTAL, f"verified {verified}, expected {EXPECTED_TOTAL}")
    # Terminal aggregate proof: after the byte readback, re-enumerate the owned
    # prefixes and require exact-set equality -- nothing missing, nothing extra.
    receipt_paths = {entry["path"] for entry in files}
    prefixes = owned_prefixes(files)
    primaries = list_primaries_from_env()
    remote_set = {
        item["key"]
        for item in primaries
        if any(item["key"].startswith(prefix + "/") for prefix in prefixes)
    }
    extra = sorted(remote_set - receipt_paths)
    missing_remote = sorted(receipt_paths - remote_set)
    require(not extra, f"unexpected carriers present after publish: {', '.join(extra[:5])}")
    require(not missing_remote, f"primaries missing from listing after publish: {', '.join(missing_remote[:5])}")
    receipt_out = getattr(args, "output", None)
    if receipt_out:
        run_id = os.environ.get("GITHUB_RUN_ID", "")
        run_attempt = os.environ.get("GITHUB_RUN_ATTEMPT", "")
        run_sha = os.environ.get("GITHUB_SHA", "")
        mirror_receipt = {
            "status": "complete",
            "destination": RAFT_ARTIFACTS_BASE_URL,
            "fileCount": verified,
            "files": sorted(files, key=lambda f: f["path"]),
            "provenance": {
                "mirrorSourceExact": run_sha,
                "runId": run_id,
                "runAttempt": run_attempt,
            },
        }
        Path(receipt_out).write_text(
            json.dumps(mirror_receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    print(f"mirror verify: {verified}/{EXPECTED_TOTAL} byte-identical on Raft")
    return 0


def client_from_env() -> RepositoryClient:
    username = os.environ.get("RAFT_ARTIFACTS_USERNAME", "")
    token = os.environ.get("RAFT_ARTIFACTS_PUBLISH_TOKEN", "")
    base_url = os.environ.get("RAFT_ARTIFACTS_URL", RAFT_ARTIFACTS_BASE_URL)
    return RepositoryClient(base_url, username, token)


def list_primaries_from_env() -> list[dict]:
    return list_scope_primaries()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p_plan = sub.add_parser("plan")
    p_plan.add_argument("--receipt", required=True)
    p_plan.add_argument("--output", required=True)
    p_plan.set_defaults(func=command_plan)
    p_publish = sub.add_parser("publish")
    p_publish.add_argument("--receipt", required=True)
    p_publish.add_argument("--bytes-dir", required=True)
    p_publish.add_argument("--plan", required=True)
    p_publish.set_defaults(func=command_publish)
    p_verify = sub.add_parser("verify")
    p_verify.add_argument("--receipt", required=True)
    p_verify.add_argument("--output", default="")
    p_verify.set_defaults(func=command_verify)
    return parser


def main(argv: list[str]) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except MirrorError as error:
        print(f"MIRROR FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
