#!/usr/bin/env python3
"""Minimal test-only HTTP CONNECT proxy with a marker-file assertion."""

import argparse
import select
import socket
import socketserver
from pathlib import Path


class ConnectHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(10)
        request = b""
        while b"\r\n\r\n" not in request and len(request) < 65536:
            chunk = self.request.recv(4096)
            if not chunk:
                return
            request += chunk
        first_line = request.split(b"\r\n", 1)[0].decode("ascii", "replace")
        method, authority, _ = first_line.split(" ", 2)
        if method.upper() != "CONNECT":
            self.request.sendall(b"HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n")
            return
        host, port_text = authority.rsplit(":", 1)
        with socket.create_connection((host, int(port_text)), timeout=10) as upstream:
            Path(self.server.marker).parent.mkdir(parents=True, exist_ok=True)
            with open(self.server.marker, "a", encoding="utf-8") as marker:
                marker.write(authority + "\n")
            self.request.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            sockets = [self.request, upstream]
            while True:
                readable, _, _ = select.select(sockets, [], [], 10)
                if not readable:
                    return
                for source in readable:
                    data = source.recv(65536)
                    if not data:
                        return
                    target = upstream if source is self.request else self.request
                    target.sendall(data)


class ThreadingProxy(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--marker", required=True)
    args = parser.parse_args()
    with ThreadingProxy(("127.0.0.1", args.port), ConnectHandler) as server:
        server.marker = args.marker
        server.serve_forever()


if __name__ == "__main__":
    main()
