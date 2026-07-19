#!/usr/bin/env python3
"""Local behavior-contract server for the pbcurlwrapper tests.

Endpoints mirror the failure classes the wrapper must surface faithfully:
status passthrough (the raft.3 bug), error bodies, timeouts, redirects, and
content-encoding decode.
"""
import gzip
import shutil
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

AUTH_BODY = b'{"error":"Invalid or expired token","code":"auth_required"}'
ENCODED_BODY = b'{"encoded":true,"padding":"' + b"x" * 256 + b'"}'


def compress_with(command, data):
    if shutil.which(command) is None:
        raise RuntimeError(f"{command} is not installed")
    return subprocess.check_output([command, "-c"], input=data)


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
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

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
        elif self.path == "/redirect-mixed-case":
            self.send_response(302)
            self.send_header("LoCaTiOn", "/ok")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif self.path == "/redirect-delayed-headers":
            self.send_response(302)
            self.send_header("Location", "/delayed-headers")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif self.path == "/slow-redirect-cross-origin":
            time.sleep(0.75)
            self.send_response(302)
            self.send_header(
                "Location",
                f"http://127.0.0.1:{self.server.cross_origin_port}/delayed-short-headers",
            )
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif self.path == "/redirect-loop":
            self.send_response(302)
            self.send_header("Location", "/redirect-loop")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif self.path == "/redirect-disallowed":
            self.send_response(302)
            self.send_header("Location", "file:///tmp/networkkmm-disallowed")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif self.path == "/no-content":
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif self.path == "/delayed-headers":
            # libcurl's progress callback may be throttled to roughly 1Hz when
            # no socket activity occurs; keep the stall beyond that cadence.
            time.sleep(1.5)
            self._send(200, b"late")
        elif self.path == "/delayed-short-headers":
            time.sleep(0.75)
            self._send(200, b"cross-origin-ok")
        elif self.path == "/stream":
            chunks = [b"alpha", b"beta", b"gamma"]
            self.send_response(200)
            self.send_header("Content-Length", str(sum(map(len, chunks))))
            self.end_headers()
            for chunk in chunks:
                try:
                    self.wfile.write(chunk)
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    break
                time.sleep(0.03)
        elif self.path == "/chunked-stream":
            self.send_response(200)
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            for chunk in [b"one", b"two", b"three"]:
                try:
                    self.wfile.write(b"%X\r\n" % len(chunk) + chunk + b"\r\n")
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    return
                time.sleep(0.03)
            try:
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
        elif self.path == "/informational":
            self.wfile.write(
                b"HTTP/1.1 103 Early Hints\r\n"
                b"Link: </style.css>; rel=preload\r\n\r\n"
            )
            self.wfile.flush()
            self._send(200, b"final")
        elif self.path == "/idle-stream":
            self.send_response(200)
            self.send_header("Content-Length", "6")
            self.end_headers()
            try:
                self.wfile.write(b"abc")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
            time.sleep(1.5)
            try:
                self.wfile.write(b"def")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
        elif self.path == "/headers-only-stall":
            self.send_response(200)
            self.send_header("Content-Length", "3")
            self.end_headers()
            self.wfile.flush()
            time.sleep(1.5)
        elif self.path == "/echo-headers":
            # Echo every received header name:value pair, one per line, so the
            # test can assert each request header arrives exactly once
            # (upstream issue #28 reported duplicated headers on the wire).
            lines = "\n".join(f"{k}: {v}" for k, v in self.headers.items())
            self._send(200, lines.encode())
        elif self.path == "/gzip":
            body = gzip.compress(ENCODED_BODY)
            self._send(200, body, {"Content-Encoding": "gzip"})
        elif self.path == "/gzip-large":
            body = gzip.compress(b"x" * 4096)
            self._send(200, body, {"Content-Encoding": "gzip"})
        elif self.path == "/br":
            body = compress_with("brotli", ENCODED_BODY)
            self._send(200, body, {"Content-Encoding": "br"})
        elif self.path == "/zstd":
            body = compress_with("zstd", ENCODED_BODY)
            self._send(200, body, {"Content-Encoding": "zstd"})
        else:
            self._send(404, b'{"error":"not_found"}')

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        if self.path == "/post-idle-response":
            self.send_response(200)
            self.send_header("Content-Length", "6")
            self.end_headers()
            try:
                self.wfile.write(b"abc")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
            time.sleep(1.5)
            try:
                self.wfile.write(b"def")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        self._send(200, b'{"echoLen":%d}' % len(body))

    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Length", "12")
        self.end_headers()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18923
    cross_origin_port = int(sys.argv[2]) if len(sys.argv) > 2 else port
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.cross_origin_port = cross_origin_port
    print(f"listening on {port}", flush=True)
    server.serve_forever()
