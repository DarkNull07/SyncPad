"""Reaching this laptop from another network.

A paired phone that is not on the same network dials this laptop by its iroh
endpoint id. iroh finds the laptop, punches a direct UDP path through both
routers when it can and falls back to a relay when it cannot, and keeps trying
to go direct. Each connection is checked against the phones paired here and is
then bridged into the ordinary server on loopback: its streams become TCP
connections and its datagrams become UDP packets. So everything the phone sends
is still SyncPad's own encrypted protocol, end to end, inside iroh's.

The bridge connects from 127.0.0.2, which is how the server tells these phones apart.
"""
import asyncio
import socket
import threading
import time

ALPN = b"syncpad/1"
FROM = "127.0.0.2"          # source address of bridged connections, so the server can say "Internet"
PRIORITY = {1: 10, 2: 0, 3: -10}      # control, screen, file: higher is sent first


def available() -> bool:
    try:
        import iroh  # noqa: F401
        return True
    except Exception:       # missing, or its library failed to load
        return False


class Remote:
    def __init__(self, config, port, on_change=lambda: None):
        self.config, self.port, self.on_change = config, port, on_change
        self.id = ""                 # this laptop's endpoint id, once bound
        self.online = False
        self.error = ""
        self.links = {}              # stable id -> Link, for the window
        self.on_ready = lambda id_: None
        self._loop = None
        self._ep = None
        self._thread = None

    # ---- lifecycle, from any thread
    def start(self):
        if self._thread or not available():
            return self
        self._thread = threading.Thread(target=self._run, daemon=True, name="syncpad-remote")
        self._thread.start()
        return self

    def stop(self):
        loop, ep, t = self._loop, self._ep, self._thread
        self._thread = None
        if not loop or loop.is_closed():
            return
        for link in list(self.links.values()):
            link.close()
        try:
            if ep:
                asyncio.run_coroutine_threadsafe(ep.close(), loop).result(3)   # ends the accept loop, and so the thread
            else:
                loop.call_soon_threadsafe(loop.stop)
        except Exception:
            pass
        if t:
            t.join(3)

    def drop(self, remote_id: str):
        """Close a phone's connection, for example after it was forgotten."""
        for link in list(self.links.values()):
            if link.peer == remote_id:
                link.close()

    def paths(self):
        """(phone id, 'direct' or 'relay', rtt ms) for each live connection."""
        out = []
        for link in list(self.links.values()):
            out.append((link.peer, *link.route()))
        return out

    # ---- the iroh side
    def _run(self):
        loop = asyncio.new_event_loop()
        self._loop = loop
        asyncio.set_event_loop(loop)
        try:
            loop.run_until_complete(self._serve())
        except Exception as e:      # the network side must never take the app down
            self.error = str(e)[:200]
            self.online = False
            self.on_change()
        finally:
            # Let iroh finish its callbacks before the loop goes, or they land on a closed loop.
            try:
                tasks = [t for t in asyncio.all_tasks(loop) if not t.done()]
                for t in tasks:
                    t.cancel()
                loop.run_until_complete(asyncio.gather(*tasks, asyncio.sleep(0.3), return_exceptions=True))
                loop.close()
            except Exception:
                pass

    async def _serve(self):
        from iroh import iroh_ffi as f
        f.uniffi_set_event_loop(asyncio.get_running_loop())
        ep = await f.Endpoint.bind(f.EndpointOptions(preset=f.preset_n0(), alpns=[ALPN], secret_key=self.config.remote_secret()))
        self._ep = ep
        self.id = str(ep.id())
        self.config.remote_id = self.id
        self.on_ready(self.id)
        self.on_change()
        asyncio.get_running_loop().create_task(self._come_online(ep))
        while True:
            inc = await ep.accept_next()
            if inc is None:
                return
            asyncio.get_running_loop().create_task(self._take(inc))

    async def _come_online(self, ep):
        try:
            await asyncio.wait_for(ep.online(), 30)
            self.online = True
        except Exception:
            self.online = False
        self.on_change()

    async def _take(self, inc):
        try:
            conn = await (await inc.accept()).connect()
        except Exception:
            return
        peer = str(conn.remote_id())
        if not self.config.remote_allowed(peer):
            # A phone that scanned the code elsewhere has not told us its id yet. It may come in,
            # but only to pair with the live code, and only a couple at a time.
            if not self.config.settings.get("remote", True) or sum(not x.trusted() for x in self.links.values()) >= 2:
                conn.close(1, b"not paired")
                return
        link = Link(self, conn, peer)
        self.links[conn.stable_id()] = link
        self.on_change()
        try:
            await link.run()
        finally:
            self.links.pop(conn.stable_id(), None)
            self.on_change()


class Link:
    """One phone's connection: datagrams to one UDP socket, each stream to its own TCP connection."""

    def __init__(self, remote, conn, peer):
        self.remote, self.conn, self.peer = remote, conn, peer
        self.closed = False
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.bind((FROM, 0))
        self.udp.connect(("127.0.0.1", remote.port))
        self.udp.settimeout(1)
        self.socks = set()
        self.born = time.time()

    def trusted(self):
        return self.remote.config.remote_allowed(self.peer)

    def may_open(self, head: bytes) -> bool:
        """Which SyncPad connection a stream starts. A stranger may only pair, and has a minute to."""
        if len(head) < 13 or head[:4] != b"SPD1":
            return False
        if self.trusted():
            return True
        c = self.remote.config
        return time.time() - self.born < 60 and head[5:13] == c.pair_id and time.time() < c.pair_until

    def route(self):
        try:
            for p in self.conn.paths():
                if p.is_selected:
                    return ("relay" if p.is_relay else "direct", int(p.rtt_ms))
        except Exception:
            pass
        return ("relay", -1)

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            self.conn.close(0, b"")
        except Exception:
            pass
        for s in list(self.socks) + [self.udp]:
            try:
                s.close()
            except OSError:
                pass

    async def run(self):
        loop = asyncio.get_running_loop()
        threading.Thread(target=self._udp_back, daemon=True, name="syncpad-remote-udp").start()
        dg = loop.create_task(self._datagrams())
        watch = loop.create_task(self._watch())
        try:
            while not self.closed:
                bi = await self.conn.accept_bi()
                loop.create_task(self._stream(bi))
        except Exception:
            pass
        finally:
            dg.cancel()
            watch.cancel()
            self.close()

    async def _watch(self):
        """A stranger that has not paired within a minute is sent away."""
        while not self.closed:
            await asyncio.sleep(5)
            if not self.trusted() and time.time() - self.born > 60:
                self.close()
                return

    async def _datagrams(self):
        while not self.closed:
            try:
                d = await self.conn.read_datagram()
            except Exception:
                return
            if not self.trusted():
                continue
            try:
                self.udp.send(d)
            except OSError:
                pass

    def _udp_back(self):
        """The server's UDP answers go back as datagrams. A thread, since they are rare and tiny."""
        while not self.closed:
            try:
                d = self.udp.recv(2048)
            except socket.timeout:
                continue
            except OSError:
                return
            try:
                self.conn.send_datagram(d)
            except Exception:
                pass

    async def _stream(self, bi):
        loop = asyncio.get_running_loop()
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socks.add(s)
        send, recv = bi.send(), bi.recv()
        try:
            head = b""
            while len(head) < 13:
                d = await recv.read(65536)
                if not d:
                    return
                head += d
            if not self.may_open(head):
                return
            # The pointer and keys go first; the screen and files share what is left.
            await send.set_priority(PRIORITY.get(head[4], 0))
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            s.bind((FROM, 0))
            s.setblocking(False)
            await loop.sock_connect(s, ("127.0.0.1", self.remote.port))
            await loop.sock_sendall(s, head)
            back = loop.create_task(self._to_phone(s, send))
            try:
                while True:
                    d = await recv.read(65536)
                    if not d:
                        break
                    await loop.sock_sendall(s, d)
            except Exception:
                pass
            try:
                s.shutdown(socket.SHUT_WR)
            except OSError:
                pass
            await back
        except Exception:
            pass
        finally:
            self.socks.discard(s)
            s.close()
            try:
                await send.finish()
            except Exception:
                pass

    async def _to_phone(self, s, send):
        loop = asyncio.get_running_loop()
        try:
            while True:
                d = await loop.sock_recv(s, 65536)
                if not d:
                    break
                await send.write_all(d)
        except Exception:
            pass
        try:
            await send.finish()
        except Exception:
            pass


def wait_until(pred, timeout):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        time.sleep(0.05)
    return pred()
