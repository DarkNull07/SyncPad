"""A phone on another network: reaches the laptop through iroh, then speaks the usual protocol.

The phone side here is a small Python copy of what the Android app does: a
loopback listener whose TCP connections become streams and whose UDP packets
become datagrams. Both ends run on this machine, so it needs no internet.
"""
import asyncio
import socket
import struct
import threading
import time

import pytest
from client import Phone

from syncpad import wire
from syncpad.config import Config
from syncpad.remote import ALPN, Remote, available, wait_until
from syncpad.server import Server
from syncpad.winput import DryInput

pytestmark = pytest.mark.skipif(not available(), reason="iroh is not installed")
HERE = "127.0.0.3"


class PhoneSide:
    """The Android app's bridge, in Python."""

    def __init__(self, laptop_addr, loop):
        # iroh's Python binding has one event loop per process, so the "phone" shares the laptop's.
        self.loop = loop
        self.ready = threading.Event()
        self.id = ""
        self.error = None
        self.tcp = socket.socket()
        self.tcp.bind((HERE, 0))
        self.port = self.tcp.getsockname()[1]
        self.tcp.listen(8)
        self.tcp.setblocking(False)
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.bind((HERE, self.port))
        self.udp.settimeout(0.5)
        self.peer_udp = None
        self.laptop_addr = laptop_addr
        asyncio.run_coroutine_threadsafe(self._safe(), loop)

    async def _safe(self):
        try:
            await self._main()
        except Exception as e:
            self.error = e
            self.ready.set()

    async def _main(self):
        from iroh import iroh_ffi as f
        self.ep = await f.Endpoint.bind(f.EndpointOptions(preset=f.preset_n0(), alpns=[ALPN]))
        self.id = str(self.ep.id())
        self.ready.set()
        self.go = asyncio.Event()
        await self.go.wait()
        id_, relay, addrs = self.laptop_addr()
        self.conn = await self.ep.connect(f.EndpointAddr(f.EndpointId.from_string(id_), relay, addrs), ALPN)
        self.connected.set()
        threading.Thread(target=self._udp_out, daemon=True).start()
        self.loop.create_task(self._udp_in())
        loop = asyncio.get_running_loop()
        while True:
            s, _ = await loop.sock_accept(self.tcp)
            loop.create_task(self._pipe(s))

    def dial(self):
        self.connected = threading.Event()
        self.loop.call_soon_threadsafe(self.go.set)
        return self.connected.wait(20)

    def _udp_out(self):
        while True:
            try:
                d, a = self.udp.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                return
            self.peer_udp = a
            self.conn.send_datagram(d)

    async def _udp_in(self):
        while True:
            d = await self.conn.read_datagram()
            if self.peer_udp:
                self.udp.sendto(d, self.peer_udp)

    async def _pipe(self, s):
        loop = asyncio.get_running_loop()
        bi = await self.conn.open_bi()
        send, recv = bi.send(), bi.recv()

        async def up():
            while d := await loop.sock_recv(s, 65536):
                await send.write_all(d)
            await send.finish()

        t = loop.create_task(up())
        try:
            while d := await recv.read(65536):
                await loop.sock_sendall(s, d)
        except Exception:
            pass
        t.cancel()
        s.close()


@pytest.fixture
def srv(tmp_path):
    c = Config(tmp_path / "cfg")
    s = Server(c, DryInput(), port=0, discovery_port=0, bind="127.0.0.1", clipboard=False).start()
    s.remote = Remote(c, s.port).start()
    assert wait_until(lambda: s.remote.id, 10), s.remote.error
    yield s
    s.remote.stop()
    s.stop()


def laptop(srv):
    a = srv.remote._ep.addr()
    return lambda: (srv.remote.id, None, a.direct_addresses())


def test_paired_phone_reaches_the_laptop_through_iroh(srv):
    link = srv.config.pair_link()
    home = Phone(link, srv.port)                    # paired at home, over Wi-Fi
    assert home.welcome["r"] == srv.remote.id       # the phone learns where to find the laptop
    side = PhoneSide(laptop(srv), srv.remote._loop)
    assert side.ready.wait(10) and not side.error, side.error
    home.send(t="remote", id=side.id)               # and tells the laptop who it is
    home.sync()
    assert srv.config.remote_allowed(side.id)
    home.close()

    assert side.dial()
    away = Phone(link, side.port, host=HERE, key=home.key)
    assert away.welcome["t"] == "welcome"
    away.uaddr = (HERE, side.port)                  # the phone's bridge, as the app does for UDP
    assert wait_until(lambda: [s.via for s in srv.all_sessions()] == ["Internet"], 3)
    # Pointer packets travel as datagrams, both ways.
    reply = away.uping()
    assert reply and reply[0] == wire.M_UPING and struct.unpack(">Q", reply[1:])[0] == 7
    away.us.sendto(away.udp.seal(away.state(dx=10, dy=-4)), away.uaddr)
    away.sync()
    assert wait_until(lambda: any(e[0] == "move" for e in srv.inp.log), 2)
    # A second channel (a file or the screen) is another stream.
    extra = Phone(link, side.port, host=HERE, key=home.key, kind=wire.KIND_FILE)
    assert extra.welcome["t"] == "welcome"
    assert srv.remote.paths() and srv.remote.paths()[0][1] == "direct"


def test_unpaired_endpoint_reaches_nothing_but_pairing(srv):
    side = PhoneSide(laptop(srv), srv.remote._loop)
    assert side.ready.wait(10)
    side.dial()
    # A device id that is not the live pairing code never reaches the server.
    with pytest.raises((OSError, wire.WireError)):
        Phone(srv.config.pair_link(), side.port, host=HERE, key=b"x" * 32, device=b"\x01" * 8)
    assert not srv.all_sessions()


def test_pairs_from_anywhere_with_the_code(srv):
    """The code carries the laptop's id, so a phone can pair without ever being at home."""
    link = srv.config.pair_link()
    assert f"&r={srv.remote.id}" in link
    side = PhoneSide(laptop(srv), srv.remote._loop)
    assert side.ready.wait(10)
    assert side.dial()
    away = Phone(link, side.port, host=HERE)          # pairs through the tunnel
    away.uaddr = (HERE, side.port)
    away.send(t="remote", id=side.id)
    away.sync()
    assert srv.config.remote_allowed(side.id)
    assert away.uping()[0] == wire.M_UPING              # datagrams flow once it has paired
    # A stranger with an old or made-up code gets nowhere.
    other = PhoneSide(laptop(srv), srv.remote._loop)
    other.ready.wait(10)
    other.dial()
    with pytest.raises((OSError, wire.WireError)):
        Phone(link, other.port, host=HERE)


def test_turned_off_means_nobody(srv):
    home = Phone(srv.config.pair_link(), srv.port)
    side = PhoneSide(laptop(srv), srv.remote._loop)
    side.ready.wait(10)
    home.send(t="remote", id=side.id)
    home.sync()
    srv.config.settings["remote"] = False
    assert not srv.config.remote_allowed(side.id)
