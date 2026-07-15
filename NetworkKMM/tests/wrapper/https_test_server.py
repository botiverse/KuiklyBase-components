#!/usr/bin/env python3
import argparse
import ssl
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path == "/phase-headers":
            time.sleep(0.25)
            self._write(200, b"phase-ok")
            return
        if self.path == "/slow":
            time.sleep(10)
            self._write(200, b"slow-ok")
            return
        if self.path == "/stream":
            self._write(200, b"stream-chunk-" * 4096)
            return
        self._write(200, b"buffered-ok", {"X-NetworkKMM-Test": "yes"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        self._write(200, body or b"empty-upload")

    def log_message(self, _format, *_args):
        return

    def _write(self, status, body, headers=None):
        self.send_response(status)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()


class QuietThreadingHTTPServer(ThreadingHTTPServer):
    def handle_error(self, _request, _client_address):
        return


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    args = parser.parse_args()

    server = QuietThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(args.cert, args.key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
