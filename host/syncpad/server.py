"""Connections, sessions and everything a paired phone may ask for."""
import ctypes
import ipaddress
import json
import os
import queue
import secrets
import socket
import struct
import subprocess
import threading
import time
from collections import deque
from pathlib import Path

from . import gamepad, winclip, wire
from .config import local_ips
from .fsapi import FsSession
from .recorder import Recorder
from .system import tool
from .winput import VK, DryInput, Pointer, WinInput, key_name
from .wire import (KIND_CONTROL, KIND_FILE, KIND_SCREEN, M_BUTTON, M_DATA, M_FRAME, M_STATE,
                   M_UPING, MAGIC, STATE, Channel, Keys, Totals, UdpCrypto, WireError)

IDLE_LIMIT = 4.0
MAX_PEERS, MAX_PER_PEER = 64, 16       # connections at once, in all and from one address
CHUNK = 256 * 1024
RESERVED = {"con", "prn", "aux", "nul", *(f"{p}{i}" for p in ("com", "lpt") for i in range(1, 10))}


class Transfer:
    """One file moving either way, for the window to show."""

    def __init__(self, name, size, to_phone, state):
        self.name, self.size, self.to_phone, self.state = name, size, to_phone, state
        self.done, self.path = 0, None      # state: waiting, active, done, failed


def safe_name(name: str) -> str:
    name = str(name).replace("\\", "/").split("/")[-1]
    name = "".join(c for c in name if c >= " " and c not in '<>:"|?*').strip(" .")[:150]
    if not name or name.split(".")[0].lower() in RESERVED:
        name = "file_" + name
    return name


_SHARED = ipaddress.ip_network("100.64.0.0/10")      # carrier NAT and private overlay networks


def local_peer(ip: str) -> bool:
    """Discovery answers only nearby addresses, so it cannot be used to flood someone else."""
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if any(a in ipaddress.ip_network(n) for n in ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24")):
        return False
    return a.is_private or a.is_loopback or a.is_link_local or a in _SHARED


def clamp(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


class PhoneError(Exception):
    """Shown to the person as is."""


class PhoneReply:
    """The phone's answer to ask(): a header, and a channel to stream over."""

    def __init__(self, ch, head):
        self.ch, self.head, self.finished = ch, head, threading.Event()

    def close(self):
        self.finished.set()          # lets the connection's own thread close the socket

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()


class Session:
    """One phone's control connection."""

    def __init__(self, server, ch: Channel, keys: Keys, device_id: bytes, model: str, addr):
        self.server, self.ch, self.device_id, self.model = server, ch, device_id, model
        self.sid = secrets.randbits(32)
        self.udp = UdpCrypto(self.sid, keys.udp_s2c, keys.udp_c2s)
        self.udp_used = False
        self.addr = addr
        self.lock = threading.RLock()
        self.inp = server.inp
        self.pointer = Pointer(self.inp, self.lock)
        self.totals = Totals()
        self.buttons, self.keys = set(), set()
        self.pad = None
        self.clipsync = True
        self.cable = False
        self.closed = False

    @property
    def via(self):
        # Tethering over a cable looks like any network from here; the phone knows better.
        if self.addr[0] == "127.0.0.2":
            return "Internet"
        return "USB" if self.addr[0] == "127.0.0.1" or self.cable else "Wi-Fi"

    def send(self, **m):
        try:
            self.ch.send(json.dumps(m).encode())
        except OSError:
            pass

    # ---- binary
    def binary(self, data: bytes):
        kind = data[0]
        if kind not in (M_STATE, M_BUTTON) or len(data) < STATE.size:
            return
        with self.lock:
            if self.closed:
                return
            d = self.totals.update(*STATE.unpack_from(data)[1:])
            if d:
                dx, dy, sx, sy = d
                if dx or dy:
                    self.pointer.add(dx, dy)
                if sy:
                    self.inp.wheel(sy)
                if sx:
                    self.inp.hwheel(sx)
            if kind == M_BUTTON and len(data) >= STATE.size + 2:
                # Applied even if a later state overtook it: a click must never be lost.
                self.pointer.flush()
                self.button(data[STATE.size], bool(data[STATE.size + 1]))

    def button(self, b: int, down: bool):
        if b > 4 or (b in self.buttons) == down:
            return
        (self.buttons.add if down else self.buttons.discard)(b)
        self.inp.button(b, down)

    def key(self, k: str, down: bool):
        if k not in VK:
            return
        if down:
            self.keys.add(k)
        elif k not in self.keys:
            return
        else:
            self.keys.discard(k)
        self.inp.key(k, down)

    def combo(self, names):
        known = [key_name(k) for k in names][:6]
        if None in known:
            # Pressing the rest would do something else: Win+typo opens Start.
            self.send(t="notice", s=f"There is no key called {str(names[known.index(None)])[:20]}")
            return
        names = list(dict.fromkeys(known))
        fresh = [k for k in names if k not in self.keys]
        for k in fresh:
            self.key(k, True)
        for k in reversed(fresh):
            self.key(k, False)

    # ---- json
    def message(self, m: dict):
        t = m.get("t")
        if t == "ping":
            self.send(t="pong", ts=m.get("ts"))
            return
        with self.lock:
            if self.closed:
                return
            if t == "key":
                k, d = key_name(m.get("k", "")) or "", m.get("d", 2)
                if d == 2 and k:
                    self.combo([k])
                else:
                    self.key(k, bool(d))
            elif t == "combo":
                self.combo([str(k) for k in m.get("keys", [])])
            elif t == "text":
                self.inp.text(str(m.get("s", ""))[:5000])
            elif t == "abs":
                self.absolute(m)
            elif t == "zoom":
                self.key("ctrl", True)
                self.inp.wheel(int(clamp(m.get("d", 0), -1200, 1200)))
                self.key("ctrl", False)
            elif t == "pad":
                if self.pad is None:
                    self.pad = gamepad.make(lambda k: self.key(k, True), lambda k: self.key(k, False),
                                            self.pointer.add, not isinstance(self.inp, DryInput))
                ax = [float(clamp(float(m.get(k, 0)), -1, 1)) for k in ("lx", "ly", "rx", "ry", "lt", "rt")]
                self.pad.update(int(m.get("b", 0)), *ax)
            elif t == "opt":
                if "pointer" in m:
                    self.pointer.raw = m["pointer"] == "raw"
                if "smooth" in m:
                    self.pointer.smooth = float(clamp(float(m["smooth"]), 0, 1))
                if "clipsync" in m:
                    self.clipsync = bool(m["clipsync"])
        if t == "link":
            self.cable = bool(m.get("cable"))
            self.server.on_change()
        elif t == "record":
            threading.Thread(target=self._record, daemon=True).start()
        elif t == "recordstop":
            self.server.recorder.cancel()
        elif t == "clip":
            self.server.put_clip(str(m.get("s", "")))
        elif t == "clipget":
            self.send(t="clip", s=self.server.get_clip() or "", asked=True)
        elif t == "open":
            self.server.open(str(m.get("v", "")), self)
        elif t == "sys":
            self.server.system(str(m.get("a", "")))
        elif t == "remote":
            self.server.config.set_remote(self.device_id, m.get("id", ""))

    def _record(self):
        show = self.server.on_record
        if show is None:              # never read keys without something on screen saying so
            self.send(t="recorded", err="Open the SyncPad window on the laptop first")
            return
        self.recording = True
        show(True)
        try:
            keys = self.server.recorder.record()
        finally:
            show(False)
            self.recording = False
        if keys:
            self.send(t="recorded", keys=keys)
        else:
            self.send(t="recorded", err="Nothing was recorded")

    def absolute(self, m):
        mons = self.server.monitors()
        mon = mons[int(clamp(int(m.get("m", 0)), 0, len(mons) - 1))]
        x, y = clamp(int(m["x"]), 0, 65535), clamp(int(m["y"]), 0, 65535)
        self.inp.move_to(mon["left"] + x * (mon["width"] - 1) // 65535,
                         mon["top"] + y * (mon["height"] - 1) // 65535)

    def close(self):
        with self.lock:
            if self.closed:
                return
            self.closed = True
            if getattr(self, "recording", False):
                self.server.recorder.cancel()
            if self.pad:
                try:
                    self.pad.close()
                except Exception:
                    pass
            for b in list(self.buttons):
                self.button(b, False)
            for k in list(self.keys):
                self.key(k, False)
            self.pointer.close()
        self.ch.close()


class Server:
    def __init__(self, config, inp=None, port=wire.TCP_PORT, discovery_port=wire.DISCOVERY_PORT,
                 bind="0.0.0.0", clipboard=True, on_change=lambda: None, on_event=lambda s: None):
        self.config, self.inp = config, inp or WinInput()
        self.port, self.discovery_port, self.bind = port, discovery_port, bind
        self.on_change, self.on_event = on_change, on_event
        self.sessions = {}
        self.slock = threading.Lock()
        self.offers = {}
        self.asks = {}
        self.recorder = Recorder()
        self.on_alert = lambda s: None
        self.on_record = None                # the window's "press the shortcut now" notice
        self.dry = isinstance(self.inp, DryInput)
        self.safe_name = safe_name
        self.transfers = deque(maxlen=50)     # newest first
        self._mons = (0, None)
        self._capturer = None
        self._peers = {}
        self._plock = threading.Lock()
        self.viewers = 0                     # phones watching the screen right now
        self.clip = winclip.Watcher(self._clip_changed) if clipboard else None
        self._stop = False
        self._socks = []
        self.remote = None                   # set by the app: reaching this laptop from other networks

    def remote_ready(self, endpoint):
        """Phones already connected learn where to find this laptop when away."""
        if self.config.settings.get("remote", True):
            for s in self.all_sessions():
                s.send(t="here", r=endpoint)

    # ---- lifecycle
    def start(self):
        t = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        t.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        t.bind((self.bind, self.port))
        self.port = t.getsockname()[1]
        t.listen(16)
        u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        u.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        u.bind((self.bind, self.port))
        self._tcp, self._udp = t, u
        self._socks = [t, u]
        for fn in (self._accept_loop, self._udp_loop):
            threading.Thread(target=fn, daemon=True, name=fn.__name__).start()
        if self.discovery_port:
            d = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            d.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            d.bind((self.bind, self.discovery_port))
            self._socks.append(d)
            threading.Thread(target=self._discovery_loop, args=(d,), daemon=True).start()
        if self.clip:
            self.clip.start()
        return self

    def stop(self):
        self._stop = True
        for s in self._socks:
            s.close()
        for s in self.all_sessions():
            s.close()
        if self._capturer:
            self._capturer.close()

    def pending(self, ip):
        with self._plock:
            return self._peers.get(ip, 0)

    def all_sessions(self):
        with self.slock:
            return list(self.sessions.values())

    # ---- loops
    def _accept_loop(self):
        while not self._stop:
            try:
                sock, addr = self._tcp.accept()
            except OSError:
                return
            with self._plock:
                n = self._peers.get(addr[0], 0)
                full = n >= MAX_PER_PEER or sum(self._peers.values()) >= MAX_PEERS
                if not full:
                    self._peers[addr[0]] = n + 1
            if full:
                sock.close()
                continue
            threading.Thread(target=self._client, args=(sock, addr), daemon=True).start()

    def _udp_loop(self):
        while not self._stop:
            try:
                data, addr = self._udp.recvfrom(2048)
            except OSError:
                if self._stop:
                    return
                continue
            if len(data) < 12:
                continue
            s = self.sessions.get(int.from_bytes(data[:4], "big"))
            if s is None:
                continue
            msg = s.udp.open(data)
            if not msg:
                continue
            try:
                if msg[0] == M_UPING and len(msg) == 9:
                    s.udp_used = True
                    self._udp.sendto(s.udp.seal(msg), addr)
                elif msg[0] == M_STATE:
                    s.binary(msg)
            except (OSError, struct.error, ValueError):
                continue

    def _discovery_loop(self, sock):
        while not self._stop:
            try:
                data, addr = sock.recvfrom(512)
            except OSError:
                if self._stop:
                    return
                continue
            if data == b"SPD?" and local_peer(addr[0]):
                body = json.dumps({"h": self.config.host_id.hex(), "p": self.port}).encode()
                try:
                    sock.sendto(b"SPD!" + body, addr)
                except OSError:
                    pass

    # ---- one TCP connection
    def _client(self, sock, addr):
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(5)
            head = wire.recv_exact(sock, 29)
            kind, device_id, cn = head[4], head[5:13], head[13:]
            key = self.config.key_for(device_id) if head[:4] == MAGIC else None
            if key is None or kind not in (KIND_CONTROL, KIND_SCREEN, KIND_FILE):
                return
            hn = secrets.token_bytes(16)
            keys = Keys(key, cn, hn)
            ch = Channel(sock, keys.s2c, keys.c2s, 64 * 1024)
            sock.sendall(MAGIC + hn)
            hello = json.loads(ch.recv())       # fails unless the phone has the key
            if hello.get("t") != "hello":
                return
            model = str(hello.get("model", "Phone"))[:60]
            if self.config.confirm(device_id, key, model):
                self.on_event(f"Paired with {model}")
                self.on_alert(f"{model} is now paired and can control this laptop")
            if kind == KIND_CONTROL:
                self._control(ch, keys, device_id, model, addr)
            else:
                if kind == KIND_FILE:
                    ch.max_recv = wire.MAX_FRAME
                ch.send(json.dumps({"t": "welcome"}).encode())
                if kind == KIND_SCREEN:
                    with self._plock:
                        self.viewers += 1
                    self.on_change()
                    try:
                        self._screen(ch)
                    finally:
                        with self._plock:
                            self.viewers -= 1
                        self.on_change()
                else:
                    self._file(ch, device_id)
        except (OSError, WireError, ValueError, AttributeError, KeyError, TypeError, RecursionError):
            pass
        finally:
            sock.close()
            with self._plock:
                n = self._peers.get(addr[0], 1) - 1
                if n:
                    self._peers[addr[0]] = n
                else:
                    self._peers.pop(addr[0], None)

    def _control(self, ch, keys, device_id, model, addr):
        s = Session(self, ch, keys, device_id, model, addr)
        with self.slock:
            old = [x for x in self.sessions.values() if x.device_id == device_id]
            self.sessions[s.sid] = s
        for x in old:          # the phone moved to another transport
            x.close()
        s.send(t="welcome", sid=s.sid, name=socket.gethostname(), udp=self.port,
               h=self.config.host_id.hex(), ips=local_ips(),
               r=self.remote.id if self.remote and self.config.settings.get("remote", True) else "")
        for oid, (path, name, size, _) in list(self.offers.items()):
            s.send(t="offer", id=oid, name=name, size=size)
        self.on_change()
        ch.sock.settimeout(IDLE_LIMIT)
        try:
            while True:
                data = ch.recv()
                if not data:
                    continue
                try:
                    if data[0] == 0x7B:
                        s.message(json.loads(data))
                    else:
                        s.binary(data)
                except (ValueError, TypeError, KeyError, AttributeError, struct.error, RecursionError):
                    continue       # one malformed message must not drop the link
        finally:
            s.close()
            with self.slock:
                self.sessions.pop(s.sid, None)
            self.on_change()

    def _screen(self, ch):
        from .screen import Capturer
        if self._capturer is None:
            self._capturer = Capturer()
        ch.sock.settimeout(30)
        last = None
        while True:
            m = json.loads(ch.recv())
            if m.get("t") != "next":
                continue
            w = int(clamp(int(m.get("w", 1280)), 320, 2560))
            q = int(clamp(int(m.get("q", 60)), 20, 90))
            # Phones that say "wait" accept an unchanged frame: just the cursor, no picture.
            wait = int(clamp(int(m.get("wait", 0)), 0, 1000))
            try:
                cx, cy, fw, fh, mons, jpg, state, waited = self._capturer.frame(w, q, int(m.get("m", 0)), last if wait else None, wait)
                last = state
            except OSError:
                time.sleep(0.5)
                cx, cy, fw, fh, mons, jpg, last, waited = 65535, 65535, 0, 0, 1, b"", None, 0
            head = struct.pack(">BHHHHB", M_FRAME, cx, cy, fw, fh, mons)
            if m.get("v") == 2:
                # How long it waited for a change, so the phone can tell the network's share of the time.
                head += struct.pack(">H", min(waited, 65535))
            ch.send(head + jpg)

    # ---- files
    def save_dir(self) -> Path:
        d = self.config.settings.get("save_dir")
        p = Path(d) if d else Path.home() / "Downloads" / "SyncPad"
        p.mkdir(parents=True, exist_ok=True)
        return p

    def _file(self, ch, device_id):
        ch.sock.settimeout(30)
        m = json.loads(ch.recv())
        if m.get("t") == "put":
            self.receive(ch, m.get("name", "file"), int(m.get("size", -1)), self.save_dir())
        elif m.get("t") == "fs":
            ch.sock.settimeout(120)
            FsSession(ch, self).serve(m)
        elif m.get("t") == "reply":
            waiting = self.asks.pop(str(m.get("rid")), None)
            if waiting and waiting[0] == device_id:
                r = PhoneReply(ch, m)
                waiting[1].put(r)
                r.finished.wait()
        elif m.get("t") == "get":
            self._send_offer(ch, str(m["id"]))

    def _send_offer(self, ch, oid):
        path, name, size, t = self.offers[oid]
        t.state, t.done = "active", 0
        try:
            with open(path, "rb") as f:
                ch.send(json.dumps({"t": "go", "name": name, "size": size}).encode())
                while chunk := f.read(CHUNK):
                    ch.send(bytes([M_DATA]) + chunk)
                    t.done += len(chunk)
            ch.send(b'{"t":"done"}')
            # The phone confirms it saved the file. Only then is the offer used up.
            if json.loads(ch.recv()).get("t") != "saved":
                raise WireError("not saved")
        except BaseException:
            t.state = "waiting" if Path(path).is_file() else "failed"
            raise
        self.offers.pop(oid, None)
        t.state = "done"
        self.on_event(f"Sent {name}")

    def withdraw(self, t: Transfer):
        for oid, o in list(self.offers.items()):
            if o[3] is t and t.state == "waiting":
                self.offers.pop(oid, None)
                self.transfers.remove(t)

    # ---- asking the phone for its files
    def ask(self, op, timeout=12.0, **args):
        live = self.all_sessions()
        if not live:
            raise PhoneError("Your phone is not connected")
        s, rid, q = live[0], secrets.token_hex(8), queue.Queue(1)
        self.asks[rid] = (s.device_id, q)
        s.send(t="ask", rid=rid, op=op, **args)
        try:
            r = q.get(timeout=timeout)
        except queue.Empty:
            raise PhoneError("Your phone did not answer") from None
        finally:
            self.asks.pop(rid, None)
        if r.head.get("err"):
            r.close()
            raise PhoneError(str(r.head["err"])[:200])
        return r

    def phone_list(self, path=""):
        with self.ask("list", path=path) as r:
            return r.head

    def phone_get(self, path, name, size, folder: Path) -> Path:
        t = Transfer(safe_name(name), size, False, "active")
        self.transfers.appendleft(t)
        part = folder / f".{secrets.token_hex(6)}.part"
        try:
            with self.ask("get", path=path) as r:
                t.size = want = int(r.head["size"])
                with open(part, "xb") as f:
                    while (d := r.ch.recv())[:1] == bytes([M_DATA]):
                        f.write(d[1:])
                        t.done += len(d) - 1
                if t.done != want:
                    raise PhoneError("The file did not arrive complete")
            final = self._place(part, folder, t.name)
        except BaseException as e:
            t.state = "failed"
            part.unlink(missing_ok=True)
            if isinstance(e, (OSError, WireError, KeyError, ValueError)):
                raise PhoneError("The connection dropped") from None
            raise
        t.path, t.state = final, "done"
        return final

    def phone_put(self, file: Path, path):
        size = file.stat().st_size
        t = Transfer(file.name, size, True, "active")
        self.transfers.appendleft(t)
        try:
            with open(file, "rb") as f, self.ask("put", path=path, name=file.name, size=size) as r:
                while chunk := f.read(CHUNK):
                    r.ch.send(bytes([M_DATA]) + chunk)
                    t.done += len(chunk)
                r.ch.send(b'{"t":"done"}')
                if json.loads(r.ch.recv()).get("t") != "saved":
                    raise PhoneError("The phone could not save it")
        except BaseException as e:
            t.state = "failed"
            if isinstance(e, (OSError, WireError, ValueError)):
                raise PhoneError("The connection dropped") from None
            raise
        t.state = "done"

    def receive(self, ch, name, size, folder: Path):
        name = safe_name(name)
        part = folder / f".{secrets.token_hex(6)}.part"
        got = 0
        t = Transfer(name, size, False, "active")
        self.transfers.appendleft(t)
        try:
            with open(part, "xb") as f:
                ch.send(b'{"t":"go"}')
                while size < 0 or got < size:
                    data = ch.recv()
                    if data[:1] != bytes([M_DATA]):
                        break
                    f.write(data[1:])
                    got += len(data) - 1
                    t.done = got
            if size >= 0 and got != size:
                raise WireError("short file")
            final = self._place(part, folder, name)
        except BaseException:
            t.state = "failed"
            part.unlink(missing_ok=True)
            raise
        t.name, t.path, t.state = final.name, final, "done"
        ch.send(json.dumps({"t": "done", "name": final.name}).encode())
        self.on_event(f"Received {final.name}")

    @staticmethod
    def _place(part: Path, folder: Path, name: str) -> Path:
        stem, dot, ext = name.rpartition(".")
        if not stem:
            stem, dot, ext = name, "", ""
        for i in range(1000):
            target = folder / (name if i == 0 else f"{stem} ({i}){dot}{ext}")
            try:
                os.rename(part, target)     # on Windows this never overwrites
                return target
            except FileExistsError:
                continue
        raise OSError("too many files with that name")

    def offer(self, path: str) -> bool:
        p = Path(path)
        oid = secrets.token_hex(6)
        t = Transfer(p.name, p.stat().st_size, True, "waiting")
        self.offers[oid] = (p, p.name, p.stat().st_size, t)
        self.transfers.appendleft(t)
        live = self.all_sessions()
        for s in live:
            s.send(t="offer", id=oid, name=p.name, size=p.stat().st_size)
        return bool(live)

    # ---- misc services
    def monitors(self):
        now = time.monotonic()
        if now - self._mons[0] > 5 or self._mons[1] is None:
            if isinstance(self.inp, DryInput):
                self._mons = (now, [{"left": 0, "top": 0, "width": 1920, "height": 1080}])
            else:
                from .screen import monitors
                self._mons = (now, monitors())
        return self._mons[1]

    def _clip_changed(self, s):
        if self.config.settings.get("clipsync", True):
            for x in self.all_sessions():
                if x.clipsync:
                    x.send(t="clip", s=s[:15000])

    def put_clip(self, s):
        if self.clip and s:
            self.clip.put(s)

    def get_clip(self):
        return (winclip.get_text() or "")[:15000] if self.clip else ""

    def open(self, value, session=None):
        value = value.strip()
        if not value:
            return
        self.on_event(f"Opening {value}")
        if isinstance(self.inp, DryInput):
            self.inp._rec("open", value)
            return
        try:
            os.startfile(os.path.expandvars(value))
        except OSError as e:
            if session:
                session.send(t="notice", s=f"Could not open {value}: {e.strerror or e}")

    def system(self, action):
        if isinstance(self.inp, DryInput):
            self.inp._rec("sys", action)
        elif action == "lock":
            ctypes.windll.user32.LockWorkStation()
        elif action == "monitor_off":
            ctypes.windll.user32.PostMessageW(0xFFFF, 0x0112, 0xF170, 2)
        elif action == "sleep":
            subprocess.Popen([tool("rundll32.exe"), "powrprof.dll,SetSuspendState", "0,1,0"],
                             creationflags=0x08000000)
