"""Internet speed, measured the way speed.cloudflare.com does. The phone app has the same test.

Ping is the middle of fifteen requests over one kept-open connection, minus the time
Cloudflare says it spent. Download and upload each use six connections, ignore the
first 1.5 s while they speed up, and stop when the rate holds steady (at most 12 s).
"""
import http.client
import os
import re
import statistics
import threading
import time

HOST = "speed.cloudflare.com"


class Limited(OSError):
    pass


class SpeedTest:
    def __init__(self, on_change=lambda s: None):
        self.on_change = on_change
        self.state = {"phase": ""}
        self.stop = threading.Event()

    def _set(self, **k):
        self.state = {**self.state, **k}
        self.on_change(self.state)

    def run(self):
        self.stop.clear()
        self.state = {"phase": "ping"}
        self._set()
        try:
            c = http.client.HTTPSConnection(HOST, timeout=10)
            self._ping(c)                                    # sets up the connection and TLS
            pings = [self._ping(c) for _ in range(15) if not self.stop.is_set()]
            c.close()
            jitter = statistics.mean(abs(a - b) for a, b in zip(pings, pings[1:])) if len(pings) > 1 else 0
            self._set(ping=round(statistics.median(pings)), jitter=round(jitter), phase="download")
            self._set(down=self._measure(True), phase="upload", live=0)
            self._set(up=self._measure(False), phase="" if self.stop.is_set() else "done", live=0)
        except Limited:
            self._set(phase="failed", error="Cloudflare is limiting tests from this network. Try again in a few minutes.")
        except (OSError, http.client.HTTPException, ValueError) as e:
            self._set(phase="failed", error=str(e) or "The test did not finish")

    def _ping(self, c):
        t = time.perf_counter()
        c.request("GET", "/__down?bytes=0", headers={"User-Agent": "SyncPad"})
        r = c.getresponse()
        r.read()
        ms = (time.perf_counter() - t) * 1000
        if r.status == 429:
            raise Limited()
        if "server" not in self.state:
            ray = r.getheader("CF-RAY", "")
            self.state["server"] = ray.rsplit("-", 1)[-1] if "-" in ray else ""
        server = sum(float(x) for x in re.findall(r"dur=([\d.]+)", r.getheader("Server-Timing", "")))
        return max(0.1, ms - server)

    def _measure(self, download):
        got = [0]
        lock = threading.Lock()
        done = threading.Event()
        limited = threading.Event()
        block = os.urandom(65536)               # random, so nothing compresses it

        def worker():
            c = http.client.HTTPSConnection(HOST, timeout=10)
            while not done.is_set() and not self.stop.is_set():
                try:
                    if download:
                        c.request("GET", "/__down?bytes=25000000", headers={"User-Agent": "SyncPad"})
                        r = c.getresponse()
                        if r.status == 429:
                            limited.set()
                            done.set()
                            return
                        while not done.is_set() and not self.stop.is_set():
                            d = r.read(65536)
                            if not d:
                                break
                            with lock:
                                got[0] += len(d)
                        if done.is_set():
                            c.close()
                            return
                    else:
                        size = 4_000_000
                        c.putrequest("POST", "/__up")
                        c.putheader("Content-Length", str(size))
                        c.putheader("Content-Type", "application/octet-stream")
                        c.putheader("User-Agent", "SyncPad")
                        c.endheaders()
                        left = size
                        while left and not done.is_set() and not self.stop.is_set():
                            n = min(left, len(block))
                            c.send(block[:n])
                            left -= n
                            with lock:
                                got[0] += n
                        if done.is_set():
                            c.close()
                            return
                        r = c.getresponse()
                        r.read()
                        if r.status == 429:
                            limited.set()
                            done.set()
                            return
                except (OSError, http.client.HTTPException):
                    c.close()
                    c = http.client.HTTPSConnection(HOST, timeout=10)
                    time.sleep(0.2)
            c.close()

        threads = [threading.Thread(target=worker, daemon=True) for _ in range(6)]
        for t in threads:
            t.start()
        start, samples, result = time.perf_counter(), [], None
        while not self.stop.is_set() and not done.is_set():
            time.sleep(0.2)
            t = time.perf_counter() - start
            with lock:
                b = got[0]
            samples.append((t, b))
            recent = [s for s in samples if s[0] >= t - 1]
            live = (recent[-1][1] - recent[0][1]) * 8 / max(1e-3, recent[-1][0] - recent[0][0]) / 1e6 if len(recent) > 1 else 0
            first = next((s for s in samples if s[0] >= 1.5), None)
            if first and t - first[0] > 0.5:
                result = (b - first[1]) * 8 / (t - first[0]) / 1e6
            self._set(live=live)
            a, p = _rate(samples, t - 2, t), _rate(samples, t - 4, t - 2)
            if t >= 12 or b >= (400e6 if download else 150e6) or (t >= 6 and a and p and abs(a - p) <= .06 * max(a, p)):
                break
        done.set()
        for t in threads:
            t.join(1.5)
        if limited.is_set():
            raise Limited()
        if not self.stop.is_set() and not result:
            raise OSError("No data came through. Check the connection and try again.")
        return result


def _rate(s, a, b):
    x = next((v for v in s if v[0] >= a), None)
    y = next((v for v in reversed(s) if v[0] <= b), None)
    if not x or not y or y[0] - x[0] < 1.5:
        return None
    return (y[1] - x[1]) * 8 / (y[0] - x[0]) / 1e6
