#!/usr/bin/env python3
import base64
import hashlib
import socket
import struct
import sys


def read_http(sock):
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(4096)
        if not chunk:
            raise RuntimeError("client closed during handshake")
        data += chunk
    lines = data.decode("latin1").split("\r\n")
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            key, value = line.split(":", 1)
            headers[key.lower()] = value.strip()
    return lines[0], headers


def send_text(sock, value):
    payload = value.encode("utf-8")
    if len(payload) < 126:
        header = bytes((0x81, len(payload)))
    else:
        header = bytes((0x81, 126)) + struct.pack("!H", len(payload))
    sock.sendall(header + payload)


def recv_text(sock):
    first, second = sock.recv(2)
    opcode = first & 0x0F
    length = second & 0x7F
    if length == 126:
        length = struct.unpack("!H", sock.recv(2))[0]
    elif length == 127:
        length = struct.unpack("!Q", sock.recv(8))[0]
    mask = sock.recv(4) if second & 0x80 else b""
    payload = b""
    while len(payload) < length:
        payload += sock.recv(length - len(payload))
    if mask:
        payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    if opcode == 8:
        return "<close>"
    if opcode != 1:
        raise RuntimeError(f"unexpected opcode {opcode}")
    return payload.decode("utf-8")


def main():
    port = int(sys.argv[1])
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(1)
    print(f"socketio listening on {port}", flush=True)
    client, _ = server.accept()
    try:
        request, headers = read_http(client)
        if not request.startswith("GET /socket.io/?EIO=4&transport=websocket "):
            raise RuntimeError(f"bad request {request}")
        key = headers["sec-websocket-key"]
        accept = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
        ).decode()
        client.sendall(
            ("HTTP/1.1 101 Switching Protocols\r\n"
             "Upgrade: websocket\r\n"
             "Connection: Upgrade\r\n"
             f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode()
        )
        send_text(client, '0{"sid":"s1","pingInterval":25000,"pingTimeout":20000}')
        assert recv_text(client) == '40{"token":"test"}'
        send_text(client, "40")
        send_text(client, "2probe")
        assert recv_text(client) == "3probe"
        assert recv_text(client) == '42["room:join",{"roomId":"r1"}]'
        send_text(client, '42["message:new",{"id":"m1"}]')
        recv_text(client)
    finally:
        client.close()
        server.close()


if __name__ == "__main__":
    main()
