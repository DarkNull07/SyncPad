"""A phone, in Python: the reference client used by the tests."""
import json
import os
import socket
import struct
from urllib.parse import parse_qs, urlparse

from syncpad import wire


class Phone:
    def __init__(self, link, port, host="127.0.0.1", kind=wire.KIND_CONTROL, key=None, device=None):
        q = {k: v[0] for k, v in parse_qs(urlparse(link).query).items()}
        self.id, self.key = device or bytes.fromhex(q["d"]), key or bytes.fromhex(q["k"])
        self.sock = socket.create_connection((host, port), 3)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        cn = os.urandom(16)
        self.sock.sendall(wire.MAGIC + bytes([kind]) + self.id + cn)
        r = wire.recv_exact(self.sock, 20)
        self.keys = wire.Keys(self.key, cn, r[4:])
        self.ch = wire.Channel(self.sock, self.keys.c2s, self.keys.s2c)
        self.send(t="hello", model="TestPhone")
        self.welcome = json.loads(self.ch.recv())
        self.q = self.x = self.y = self.sx = self.sy = 0
        if kind == wire.KIND_CONTROL:
            self.udp = wire.UdpCrypto(self.welcome["sid"], self.keys.udp_c2s, self.keys.udp_s2c)
            self.us = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.us.settimeout(2)
            self.uaddr = (host, self.welcome["udp"])

    def send(self, **m):
        self.ch.send(json.dumps(m).encode())

    def recv(self):
        return json.loads(self.ch.recv())

    def sync(self):
        """Round trip: everything sent before this has been handled."""
        self.send(t="ping", ts=1)
        while self.recv()["t"] != "pong":
            pass

    def state(self, dx=0, dy=0, sx=0, sy=0, kind=wire.M_STATE):
        self.q += 1
        self.x += int(dx * 64)
        self.y += int(dy * 64)
        self.sx += sx
        self.sy += sy
        return wire.STATE.pack(kind, self.q, self.x, self.y, self.sx, self.sy)

    def uping(self):
        self.us.sendto(self.udp.seal(struct.pack(">BQ", wire.M_UPING, 7)), self.uaddr)
        return self.udp.open(self.us.recvfrom(2048)[0])

    def close(self):
        self.ch.close()
