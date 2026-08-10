#!/usr/bin/env python3
"""Atomic release client for the Raft Artifacts v1 ledger (task #106).

Implements the deployed contract from raft-artifacts docs/atomic-releases.md:
claim (binds the exact release identity against the actual stored constrained
token), create-only staging below an invisible prefix, single CAS commit as the
ONLY visibility linearization point, abort with an immutable terminal reason.

The client proves its own side honestly: it computes the canonical manifest
digest the same way the server recomputes it (bytewise path sort, compact
{"schema":1,"objects":[...]} JSON), carries a stable non-credential UA (the
edge bans library defaults), and never logs headers or tokens. Bearer auth as
the contract requires -- not the Maven Basic form.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Optional

CONTROL_PLANE_BASE_URL = "https://artifacts.botiverse.dev"
RELEASE_USER_AGENT = "raft-datetime-publish/1.0"


class AtomicReleaseError(RuntimeError):
    def __init__(self, code: str, message: str, http_status: Optional[int] = None) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.http_status = http_status


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def manifest_digest(objects: list[dict]) -> str:
    """Canonical digest: bytewise path sort, each entry exactly
    {path, sha256, size} in that key order, compact JSON as the server
    recomputes it."""
    normalized = []
    for entry in objects:
        normalized.append(
            {
                "path": entry["path"],
                "sha256": entry["sha256"],
                "size": entry["size"],
            }
        )
    normalized.sort(key=lambda e: e["path"].encode("utf-8"))
    body = json.dumps({"schema": 1, "objects": normalized}, separators=(",", ":"), ensure_ascii=False)
    return sha256_bytes(body.encode("utf-8"))


def owned_prefixes_from_paths(paths: list[str]) -> list[str]:
    """Sorted set of version-directory prefixes (trailing slash included),
    derived from the manifest paths -- broader namespaces are refused server
    side, so we derive exactly."""
    return sorted({p.rsplit("/", 1)[0] + "/" for p in paths})


class AtomicReleaseClient:
    def __init__(self, base_url: str = CONTROL_PLANE_BASE_URL, opener: Optional[urllib.request.OpenerDirector] = None) -> None:
        token = os.environ.get("RAFT_ARTIFACTS_PUBLISH_TOKEN", "")
        if not token:
            raise AtomicReleaseError("credentials-missing", "RAFT_ARTIFACTS_PUBLISH_TOKEN is required")
        if "\r" in token or "\n" in token:
            raise AtomicReleaseError("credentials-malformed", "token contains a newline")
        self.base_url = base_url.rstrip("/")
        self.authorization = f"Bearer {token}"
        self.opener = opener or urllib.request.build_opener(NoRedirectHandler())

    def _request(self, method: str, path: str, body: Optional[bytes] = None, headers: Optional[dict] = None) -> tuple[int, bytes]:
        url = f"{self.base_url}{path}"
        request_headers = {"Authorization": self.authorization, "User-Agent": RELEASE_USER_AGENT}
        if headers:
            request_headers.update(headers)
        req = urllib.request.Request(url, method=method, data=body, headers=request_headers)
        try:
            with self.opener.open(req, timeout=60) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read() or b""
        except urllib.error.URLError as error:
            raise AtomicReleaseError("transport", f"{method} {path}: {error.reason}") from error

    @staticmethod
    def _json(payload: bytes) -> dict:
        try:
            value = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return {}
        return value if isinstance(value, dict) else {}

    def _checked(self, method: str, path: str, body: Optional[bytes] = None, headers: Optional[dict] = None, ok: tuple = (200, 201)) -> dict:
        status, payload = self._request(method, path, body, headers)
        parsed = self._json(payload)
        if status not in ok:
            code = parsed.get("code", f"http-{status}")
            message = parsed.get("error", parsed.get("message", payload[:120].decode("utf-8", "replace")))
            raise AtomicReleaseError(str(code), str(message), http_status=status)
        return parsed

    def claim(self, payload: dict) -> dict:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        return self._checked("POST", "/api/releases/claims", body, {"Content-Type": "application/json"}, ok=(200, 201, 409))

    def stage_object(self, claim_id: str, canonical_path: str, body: bytes) -> dict:
        return self._checked(
            "PUT",
            f"/api/releases/{claim_id}/objects",
            body,
            {"X-Raft-Artifact-Path": canonical_path},
            ok=(200, 201),
        )

    def inspect(self, claim_id: str) -> dict:
        return self._checked("GET", f"/api/releases/{claim_id}", ok=(200,))

    def commit(self, claim_id: str) -> dict:
        return self._checked("POST", f"/api/releases/{claim_id}/commit", ok=(200, 201))

    def abort(self, claim_id: str, reason: str) -> dict:
        body = json.dumps({"reason": reason}, separators=(",", ":")).encode("utf-8")
        return self._checked("POST", f"/api/releases/{claim_id}/abort", body, {"Content-Type": "application/json"}, ok=(200, 201, 409))


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):  # type: ignore[override]
        del file_pointer, message, headers
        source = urllib.parse.urlsplit(request.full_url)
        target = urllib.parse.urlsplit(new_url)
        raise AtomicReleaseError(
            "redirect-rejected",
            f"HTTP {code}, {source.scheme}://{source.netloc} -> {target.scheme}://{target.netloc}",
        )
