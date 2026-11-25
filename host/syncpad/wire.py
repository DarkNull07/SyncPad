"""Framing and encryption. See docs/PROTOCOL.md."""
import hashlib
import hmac
import socket
import struct
import threading

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"SPD1"
TCP_PORT = 47800
UDP_PORT = 47800
DISCOVERY_PORT = 47801
KIND_CONTROL, KIND_SCREEN, KIND_FILE = 1, 2, 3
MAX_FRAME = 4 * 1024 * 1024
TAG = 16

M_STATE, M_BUTTON, M_UPING, M_FRAME, M_DATA = 0x01, 0x02, 0x09, 0x10, 0x20

STATE = struct.Struct(">BIiiii")
UDP_HEAD = struct.Struct(">IQ")


class WireError(Exception):
    pass


def hkdf(key: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    prk = hmac.new(salt, key, hashlib.sha256).digest()
    out, block, i = b"", b"", 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([i]), hashlib.sha256).digest()
        out += block
        i += 1
    return out[:length]


class Keys:
    def __init__(self, psk: bytes, client_nonce: bytes, host_nonce: bytes):
        salt = client_nonce + host_nonce
        self.c2s, self.s2c, self.udp_c2s, self.udp_s2c = (
            hkdf(psk, salt, b"syncpad1 " + label)
            for label in (b"c2s", b"s2c", b"udp c2s", b"udp s2c"))


def nonce(counter: int) -> bytes:
    return b"\0\0\0\0" + counter.to_bytes(8, "big")


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray(n)
    view, got = memoryview(buf), 0
    while got < n:
        r = sock.recv_into(view[got:], n - got)
        if r == 0:
            raise WireError("closed")
        got += r
    return bytes(buf)


class Channel:
    """An encrypted, framed TCP connection. send() is thread safe."""

    def __init__(self, sock: socket.socket, send_key: bytes, recv_key: bytes,
                 max_recv: int = MAX_FRAME):
        self.sock = sock
        self._tx, self._rx = AESGCM(send_key), AESGCM(recv_key)
        self._tx_n = self._rx_n = 0
        self._lock = threading.Lock()
        self.max_recv = max_recv

    def send(self, data: bytes) -> None:
        head = struct.pack(">I", len(data) + TAG)
        with self._lock:
            body = self._tx.encrypt(nonce(self._tx_n), data, head)
            self._tx_n += 1
            self.sock.sendall(head + body)

    def recv(self) -> bytes:
        head = recv_exact(self.sock, 4)
        n = struct.unpack(">I", head)[0]
        if n < TAG or n > self.max_recv + TAG:
            raise WireError("bad frame length")
        body = recv_exact(self.sock, n)
        try:
            data = self._rx.decrypt(nonce(self._rx_n), body, head)
        except InvalidTag:
            raise WireError("authentication failed") from None
        self._rx_n += 1
        return data

    def close(self) -> None:
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


class UdpCrypto:
    def __init__(self, sid: int, send_key: bytes, recv_key: bytes):
        self.sid = sid
        self._tx, self._rx = AESGCM(send_key), AESGCM(recv_key)
        self._seq = 0
        self._seen = 0

    def seal(self, data: bytes) -> bytes:
        self._seq += 1
        head = UDP_HEAD.pack(self.sid, self._seq)
        return head + self._tx.encrypt(nonce(self._seq), data, head)

    def open(self, packet: bytes):
        """Returns the plaintext, or None for anything forged, replayed or stale."""
        if len(packet) < UDP_HEAD.size + TAG:
            return None
        head = packet[:UDP_HEAD.size]
        _, seq = UDP_HEAD.unpack(head)
        if seq <= self._seen:
            return None
        try:
            data = self._rx.decrypt(nonce(seq), packet[UDP_HEAD.size:], head)
        except InvalidTag:
            return None
        self._seen = seq
        return data


def diff32(new: int, old: int) -> int:
    """Signed difference of two wrapping 32-bit counters."""
    return ((new - old + 0x80000000) & 0xFFFFFFFF) - 0x80000000


class Totals:
    """Turns running totals into deltas, ignoring duplicate and stale messages."""

    def __init__(self):
        self.q = None
        self.v = (0, 0, 0, 0)

    def update(self, q: int, x: int, y: int, sx: int, sy: int):
        if self.q is not None and diff32(q, self.q) <= 0:
            return None
        self.q = q
        new = (x, y, sx, sy)
        d = tuple(diff32(a, b) for a, b in zip(new, self.v))
        self.v = new
        return d
