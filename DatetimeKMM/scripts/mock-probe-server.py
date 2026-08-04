#!/usr/bin/env python3
"""Mock HTTP probe server for publish-lib.sh self-tests.

Returns a configurable status code per path. Special paths:
  /429once        429 on the first request, 200 thereafter (bounded retry).
  /redirect200    302 with a same-host Location to /200 (valid redirect).
  /redirect-cross 302 with a cross-host Location (MOCK_REDIRECT_CROSS_TARGET),
                  to exercise the same-host redirect policy.
Unmatched paths return DEFAULT_CODE (env MOCK_DEFAULT_CODE, default 404).
Usage: mock-probe-server.py <port>
"""
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

STATE = {"429once_hits": 0}
LOCK = threading.Lock()
DEFAULT_CODE = int(os.environ.get("MOCK_DEFAULT_CODE", "404"))
REDIRECT_CROSS_TARGET = os.environ.get("MOCK_REDIRECT_CROSS_TARGET", "")

STATIC_CODES = {
    "/200": 200, "/200b": 200, "/200c": 200,
    "/404": 404, "/404b": 404, "/404c": 404,
    "/401": 401, "/403": 403, "/500": 500,
}


class Handler(BaseHTTPRequestHandler):
    def _respond(self, code, location=None):
        self.send_response(code)
        if location is not None:
            self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_HEAD(self):
        path = self.path.split("?", 1)[0]
        if path == "/429once":
            with LOCK:
                STATE["429once_hits"] += 1
                hits = STATE["429once_hits"]
            self._respond(429 if hits == 1 else 200)
            return
        if path == "/redirect200":
            self._respond(302, location="/200")
            return
        if path == "/redirect-cross":
            self._respond(302, location=REDIRECT_CROSS_TARGET or "/200")
            return
        self._respond(STATIC_CODES.get(path, DEFAULT_CODE))

    def do_GET(self):
        self.do_HEAD()

    def log_message(self, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18931
    server = HTTPServer(("127.0.0.1", port), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
