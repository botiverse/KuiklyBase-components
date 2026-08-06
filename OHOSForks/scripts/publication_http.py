#!/usr/bin/env python3
from __future__ import annotations

import base64
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Optional


RAFT_ARTIFACTS_BASE_URL = "https://maven.artifacts.botiverse.dev"


class PublicationError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PublicationError(message)


def canonical_base_url(value: str, context: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise PublicationError(f"{context} base URL is invalid: {error}") from error
    require(parsed.scheme == "https", f"{context} base URL must use HTTPS")
    require(parsed.hostname is not None, f"{context} base URL must have a host")
    require(parsed.username is None and parsed.password is None, f"{context} base URL must not contain credentials")
    require(not parsed.query and not parsed.fragment, f"{context} base URL must not contain a query or fragment")
    require(parsed.path in {"", "/"}, f"{context} base URL must be an origin without a path")
    hostname = parsed.hostname.lower()
    netloc = hostname if port in {None, 443} else f"{hostname}:{port}"
    return urllib.parse.urlunsplit(("https", netloc, "", "", ""))


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
        source_origin = f"{source.scheme}://{source.netloc}"
        target_origin = f"{target.scheme}://{target.netloc}"
        raise PublicationError(
            f"repository redirect rejected before another request: HTTP {code}, "
            f"{source_origin} -> {target_origin}"
        )


def basic_authorization(username: str, password: str) -> str:
    require(username != "" and password != "", "repository credentials are missing")
    require("\r" not in username + password and "\n" not in username + password, "repository credentials are malformed")
    encoded = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    return f"Basic {encoded}"


class RepositoryClient:
    def __init__(
        self,
        name: str,
        base_url: str,
        username: str,
        password: str,
        *,
        expected_base_url: str = RAFT_ARTIFACTS_BASE_URL,
        opener: Optional[urllib.request.OpenerDirector] = None,
    ) -> None:
        reviewed_base = canonical_base_url(expected_base_url, name)
        configured_base = canonical_base_url(base_url, name)
        require(
            configured_base == reviewed_base,
            f"{name} base URL must exactly match the reviewed endpoint {reviewed_base}",
        )
        self.name = name
        self.base_url = configured_base
        self.authorization = basic_authorization(username, password)
        self.opener = opener or urllib.request.build_opener(RejectRedirectHandler())

    def _url(self, relative: str) -> str:
        require(
            relative != ""
            and not relative.startswith("/")
            and ".." not in relative.split("/"),
            f"unsafe repository path: {relative!r}",
        )
        return f"{self.base_url}/{urllib.parse.quote(relative, safe='/._-')}"

    def request(self, relative: str, method: str, body: bytes | None = None) -> tuple[int, bytes]:
        require(method in {"HEAD", "GET", "PUT"}, f"unsupported repository method: {method}")
        require((method == "PUT") == (body is not None), f"repository body mismatch for {method}")
        request = urllib.request.Request(
            self._url(relative),
            data=body,
            method=method,
            headers={
                "Authorization": self.authorization,
                "User-Agent": "kuiklybase-ohos-forks-gate/1",
            },
        )
        last_error = ""
        for attempt in range(3):
            try:
                with self.opener.open(request, timeout=30) as response:
                    return response.getcode(), response.read() if method != "HEAD" else b""
            except PublicationError:
                raise
            except urllib.error.HTTPError as error:
                if 300 <= error.code <= 399:
                    raise PublicationError(
                        f"{self.name} redirect rejected before another request for {relative}: HTTP {error.code}"
                    ) from error
                if error.code == 404 and method != "PUT":
                    return 404, b""
                if 500 <= error.code <= 599 and attempt < 2:
                    last_error = f"HTTP {error.code}"
                    time.sleep(attempt + 1)
                    continue
                raise PublicationError(f"{self.name} request failed for {relative}: HTTP {error.code}") from error
            except urllib.error.URLError as error:
                last_error = str(error.reason)
                if attempt < 2:
                    time.sleep(attempt + 1)
                    continue
                raise PublicationError(f"{self.name} request failed for {relative}: {last_error}") from error
        raise PublicationError(f"{self.name} request failed for {relative}: {last_error}")

    def positive_control(self, relative: str) -> None:
        status, _ = self.request(relative, "HEAD")
        require(status == 200, f"{self.name} positive control failed with HTTP {status}; absence evidence is void")

    def fetch(self, relative: str) -> Optional[bytes]:
        status, body = self.request(relative, "GET")
        if status == 404:
            return None
        require(status == 200, f"{self.name} readback failed for {relative}: HTTP {status}")
        return body

    def upload(self, relative: str, body: bytes) -> None:
        status, _ = self.request(relative, "PUT", body)
        require(status in {200, 201, 204}, f"{self.name} upload failed for {relative}: HTTP {status}")
