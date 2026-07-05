#!/usr/bin/env python3
"""Local behavior-contract server for the pbcurlwrapper tests.

Endpoints mirror the failure classes the wrapper must surface faithfully:
status passthrough (the raft.3 bug), error bodies, timeouts, redirects, gzip.
"""
import gzip
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

AUTH_BODY = b'{"error":"Invalid or expired token","code":"auth_required"}'


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, status, body, headers=None):
        self.send_response(status)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/ok":
            self._send(200, b'{"ok":true}')
        elif self.path == "/auth401":
            # The exact 59-byte body from the production incident.
            self._send(401, AUTH_BODY)
        elif self.path == "/boom500":
            self._send(500, b'{"error":"internal"}')
        elif self.path == "/slow":
            time.sleep(10)
            self._send(200, b'{"slow":true}')
        elif self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "/ok")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif self.path == "/echo-headers":
            # Echo every received header name:value pair, one per line, so the
            # test can assert each request header arrives exactly once
            # (upstream issue #28 reported duplicated headers on the wire).
            lines = "\n".join(f"{k}: {v}" for k, v in self.headers.items())
            self._send(200, lines.encode())
        elif self.path == "/gzip":
            raw = b'{"gzipped":true,"padding":"' + b"x" * 256 + b'"}'
            body = gzip.compress(raw)
            self._send(200, body, {"Content-Encoding": "gzip"})
        else:
            self._send(404, b'{"error":"not_found"}')

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        self._send(200, b'{"echoLen":%d}' % len(body))


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18923
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"listening on {port}", flush=True)
    server.serve_forever()
