#!/usr/bin/env python3
"""raft.46: TLS HTTP/2 server for the connection-reuse contract (h2_reuse_test.cpp).

/h2/<key>/<behaviour> (any method). Counts requests per key and connections per key, so the test
can tell a replay from a new request and a reused connection from a new one.

  ok               answer
  goaway-after     answer, then GOAWAY and close: the next request needs a new connection
  refused-once     first hit: RST_STREAM REFUSED_STREAM (the server promises it did not act)
  internal-once    first hit: RST_STREAM INTERNAL_ERROR
  hold-kill-once   first connection: hold every stream until 3 are open, then drop the TCP
                   connection with no response (a connection dying with streams in flight);
                   later connections answer
GET /h2-count/<key> -> "<requests> <connections>"
"""
import argparse
import socket
import ssl
import threading

import h2.config
import h2.connection
import h2.events
import h2.errors

LOCK = threading.Lock()
HITS = {}
CONNECTIONS = {}
HOLD_KILLED = set()


class Conn:
    def __init__(self, sock):
        self.sock = sock
        self.h2 = h2.connection.H2Connection(h2.config.H2Configuration(client_side=False))
        self.streams = {}  # stream id -> {"headers": dict, "body": bytes}
        self.keys_seen = set()
        self.held = []
        with LOCK:
            CONNECTIONS["*"] = CONNECTIONS.get("*", 0) + 1

    def flush(self):
        data = self.h2.data_to_send()
        if data:
            self.sock.sendall(data)

    def respond(self, stream_id, status, body):
        self.h2.send_headers(stream_id, [(":status", str(status)), ("content-length", str(len(body)))])
        self.h2.send_data(stream_id, body, end_stream=True)

    def run(self):
        self.h2.initiate_connection()
        self.flush()
        try:
            while True:
                data = self.sock.recv(65535)
                if not data:
                    return
                for event in self.h2.receive_data(data):
                    if isinstance(event, h2.events.RequestReceived):
                        self.streams[event.stream_id] = {"headers": dict(event.headers), "body": b""}
                        if event.stream_ended:
                            if self.handle(event.stream_id):
                                return
                    elif isinstance(event, h2.events.DataReceived):
                        self.streams[event.stream_id]["body"] += event.data
                        self.h2.acknowledge_received_data(event.flow_controlled_length, event.stream_id)
                        if event.stream_ended and self.handle(event.stream_id):
                            return
                    elif isinstance(event, h2.events.StreamEnded):
                        if event.stream_id in self.streams and self.handle(event.stream_id):
                            return
                self.flush()
        except (ConnectionError, ssl.SSLError, OSError):
            return
        finally:
            try:
                self.sock.close()
            except OSError:
                pass

    def handle(self, stream_id):
        """Returns True when the connection must end now."""
        stream = self.streams.pop(stream_id, None)
        if stream is None:
            return False
        headers = {k.decode() if isinstance(k, bytes) else k: v.decode() if isinstance(v, bytes) else v
                   for k, v in stream["headers"].items()}
        path = headers.get(":path", "/")
        parts = path.split("/")
        if len(parts) == 3 and parts[1] == "h2-count":
            with LOCK:
                body = ("%d %d" % (HITS.get(parts[2], 0), CONNECTIONS.get(parts[2], 0))).encode()
            self.respond(stream_id, 200, body)
            return False
        if len(parts) != 4 or parts[1] != "h2":
            self.respond(stream_id, 404, b"")
            return False
        key, behaviour = parts[2], parts[3]
        with LOCK:
            HITS[key] = HITS.get(key, 0) + 1
            first = HITS[key] == 1
            if key not in self.keys_seen:
                self.keys_seen.add(key)
                CONNECTIONS[key] = CONNECTIONS.get(key, 0) + 1
        payload = ('{"h2":"%s","method":"%s","echoLen":%d}' % (key, headers.get(":method"), len(stream["body"]))).encode()
        if behaviour == "refused-once" and first:
            self.h2.reset_stream(stream_id, h2.errors.ErrorCodes.REFUSED_STREAM)
            return False
        if behaviour == "internal-once" and first:
            self.h2.reset_stream(stream_id, h2.errors.ErrorCodes.INTERNAL_ERROR)
            return False
        if behaviour == "hold-kill-once":
            with LOCK:
                kill_this = key not in HOLD_KILLED
            if kill_this:
                self.held.append(stream_id)
                if len(self.held) >= 3:
                    with LOCK:
                        HOLD_KILLED.add(key)
                    self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, b"\x01\x00\x00\x00\x00\x00\x00\x00")
                    return True
                return False
        self.respond(stream_id, 200, payload)
        if behaviour == "goaway-after":
            self.h2.close_connection(last_stream_id=stream_id)
            self.flush()
            return True
        return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    args = parser.parse_args()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(args.cert, args.key)
    context.set_alpn_protocols(["h2"])
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", args.port))
    listener.listen(64)
    print("h2 listening on %d" % args.port, flush=True)
    while True:
        raw, _ = listener.accept()
        try:
            tls = context.wrap_socket(raw, server_side=True)
        except (ssl.SSLError, OSError):
            raw.close()
            continue
        threading.Thread(target=Conn(tls).run, daemon=True).start()


if __name__ == "__main__":
    main()
