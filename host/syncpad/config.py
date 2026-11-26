"""Paired devices and settings, stored per Windows user with DPAPI-protected keys."""
import ctypes
import json
import os
import secrets
import socket
import threading
import time
from ctypes import wintypes
from pathlib import Path
from urllib.parse import quote

from . import wire

PAIR_TTL = 600


class _Blob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]


def _dpapi(data: bytes, protect: bool) -> bytes:
    crypt = ctypes.windll.crypt32
    buf = ctypes.create_string_buffer(data, len(data))
    src = _Blob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
    out = _Blob()
    fn = crypt.CryptProtectData if protect else crypt.CryptUnprotectData
    if not fn(ctypes.byref(src), None, None, None, None, 1, ctypes.byref(out)):
        raise OSError("DPAPI failed")
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(out.pbData)


def default_dir() -> Path:
    return Path(os.environ.get("APPDATA", Path.home())) / "SyncPad"


class Config:
    def __init__(self, folder: Path | None = None):
        self.folder = Path(folder) if folder else default_dir()
        self.path = self.folder / "config.json"
        self.lock = threading.RLock()
        self.host_id = secrets.token_bytes(8)
        self.devices = {}   # id -> {"key": bytes, "name": str, "seen": float}
        self.settings = {"usb": True, "clipsync": False, "save_dir": "", "remote": True}
        self._remote_key = None
        self._load()
        self.new_pairing()

    def _load(self):
        try:
            raw = json.loads(self.path.read_text("utf-8"))
        except (OSError, ValueError):
            self.save()
            return
        self.host_id = bytes.fromhex(raw["host_id"])
        self.settings.update(raw.get("settings", {}))
        for d, v in raw.get("devices", {}).items():
            try:
                key = _dpapi(bytes.fromhex(v["key"]), False)
            except (OSError, ValueError):
                continue   # config copied from another Windows account
            self.devices[bytes.fromhex(d)] = {"key": key, "name": v["name"], "seen": v["seen"], "remote": v.get("remote", "")}
        try:
            self._remote_key = _dpapi(bytes.fromhex(raw["remote_key"]), False) if raw.get("remote_key") else None
        except (OSError, ValueError):
            self._remote_key = None

    def save(self):
        with self.lock:
            data = {"host_id": self.host_id.hex(), "settings": self.settings, "devices": {
                d.hex(): {"key": _dpapi(v["key"], True).hex(), "name": v["name"], "seen": v["seen"], "remote": v.get("remote", "")}
                for d, v in self.devices.items()}}
            if self._remote_key:
                data["remote_key"] = _dpapi(self._remote_key, True).hex()
            self.folder.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(data, indent=1), "utf-8")
            tmp.replace(self.path)

    # ---- pairing
    def new_pairing(self):
        with self.lock:
            self.pair_id, self.pair_key = secrets.token_bytes(8), secrets.token_bytes(32)
            self.pair_until = time.time() + PAIR_TTL

    def pair_link(self, port=wire.TCP_PORT) -> str:
        with self.lock:
            if time.time() > self.pair_until - 30:
                self.new_pairing()
            return (f"syncpad://pair?v=1&h={self.host_id.hex()}&d={self.pair_id.hex()}"
                    f"&k={self.pair_key.hex()}&n={quote(socket.gethostname())}&p={port}"
                    f"&a={','.join(local_ips())}")

    def key_for(self, device_id: bytes):
        with self.lock:
            if device_id in self.devices:
                return self.devices[device_id]["key"]
            if device_id == self.pair_id and time.time() < self.pair_until:
                return self.pair_key
            return None

    def confirm(self, device_id: bytes, key: bytes, name: str) -> bool:
        """Called once a device proved it has its key. True if newly paired."""
        with self.lock:
            new = device_id not in self.devices
            if new:
                # Only the live pairing offer may create a device: the offer can
                # have been used or replaced while this handshake was running.
                if device_id != self.pair_id:
                    raise wire.WireError("pairing offer no longer valid")
                self.devices[device_id] = {"key": key, "name": name, "seen": time.time()}
                self.new_pairing()
            else:
                self.devices[device_id].update(name=name, seen=time.time())
            self.save()
            return new

    # ---- reaching this laptop from other networks
    def remote_secret(self) -> bytes:
        """This laptop's iroh identity. Kept, so paired phones can always find it."""
        with self.lock:
            if not self._remote_key:
                self._remote_key = secrets.token_bytes(32)
                self.save()
            return self._remote_key

    def set_remote(self, device_id: bytes, endpoint: str):
        """A phone tells us its iroh id over its encrypted, paired connection."""
        endpoint = str(endpoint).strip().lower()
        if len(endpoint) != 64 or any(c not in "0123456789abcdef" for c in endpoint):
            return
        with self.lock:
            d = self.devices.get(device_id)
            if d is not None and d.get("remote") != endpoint:
                d["remote"] = endpoint
                self.save()

    def remote_allowed(self, endpoint: str) -> bool:
        with self.lock:
            return bool(self.settings.get("remote", True)) and any(
                v.get("remote") == endpoint for v in self.devices.values())

    def forget(self, device_id: bytes):
        with self.lock:
            self.devices.pop(device_id, None)
            self.save()


def local_ips():
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))   # picks a route, sends nothing
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.append(info[4][0])
    except OSError:
        pass
    seen = []
    for ip in ips:
        if ip not in seen and not ip.startswith(("127.", "169.254.")):
            seen.append(ip)
    return seen[:6]
