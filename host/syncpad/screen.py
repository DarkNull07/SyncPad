"""Screen capture in its own process, so encoding never delays pointer packets.

A frame is only encoded when the screen changed. Otherwise the worker keeps
looking for up to `wait` milliseconds and then answers with the cursor alone,
which is a dozen bytes instead of a picture: a still screen costs almost nothing,
and a slow link is not filled with copies of the same image.
"""
import io
import multiprocessing as mp
import threading
import time
import zlib


def _worker(conn):
    import mss
    from PIL import Image

    from .winput import cursor_pos, set_dpi_aware
    set_dpi_aware()
    with mss.mss() as grab:
        while True:
            try:
                req = conn.recv()
            except (EOFError, OSError):
                return
            try:
                width, quality, index = req[:3]
                last, wait = (req[3], req[4]) if len(req) > 4 else (None, 0)
                mons = grab.monitors[1:] or grab.monitors
                m = mons[min(max(index, 0), len(mons) - 1)]
                start = time.perf_counter()
                end = start + wait / 1000
                while True:
                    shot = grab.grab(m)
                    cx, cy = cursor_pos()
                    # A sample of every seventh byte catches any visible change, at a fraction of the cost.
                    sig = (zlib.crc32(shot.bgra[::7]), shot.size, width, quality)
                    if last is None or sig != last[0] or (cx, cy) != last[1] or time.perf_counter() >= end:
                        break
                    time.sleep(0.016)
                waited = round((time.perf_counter() - start) * 1000)
                fx = (cx - m["left"]) * 65535 // max(m["width"] - 1, 1)
                fy = (cy - m["top"]) * 65535 // max(m["height"] - 1, 1)
                inside = 0 <= fx <= 65535 and 0 <= fy <= 65535
                fx, fy = (fx, fy) if inside else (65535, 65535)
                if last is not None and sig == last[0]:
                    conn.send((fx, fy, 0, 0, len(mons), b"", (sig, (cx, cy)), waited))
                    continue
                img = Image.frombuffer("RGB", shot.size, shot.bgra, "raw", "BGRX", 0, 1)
                if img.width > width:
                    img = img.resize((width, round(img.height * width / img.width)),
                                     Image.Resampling.BILINEAR, reducing_gap=1.5)
                out = io.BytesIO()
                img.save(out, "JPEG", quality=quality, subsampling=2)
                conn.send((fx, fy, img.width, img.height, len(mons), out.getvalue(), (sig, (cx, cy)), waited))
            except Exception as e:   # a locked desktop or a mode switch; keep serving
                conn.send(str(e))


def monitors():
    import mss
    with mss.mss() as grab:
        return grab.monitors[1:] or grab.monitors


class Capturer:
    def __init__(self):
        self._lock = threading.Lock()
        self._proc = self._conn = None

    def frame(self, width: int, quality: int, index: int, last=None, wait=0):
        """(cursor x, cursor y, width, height, monitors, jpeg, state, waited ms). An empty jpeg means unchanged since `last`."""
        with self._lock:
            if self._proc is None or not self._proc.is_alive():
                parent, child = mp.Pipe()
                self._proc = mp.get_context("spawn").Process(
                    target=_worker, args=(child,), daemon=True)
                self._proc.start()
                child.close()
                self._conn = parent
            try:
                self._conn.send((width, quality, index, last, wait))
                if not self._conn.poll(10 + wait / 1000):
                    raise OSError("capture timed out")
                r = self._conn.recv()
            except (OSError, EOFError):
                self._kill()
                raise
        if isinstance(r, str):
            raise OSError(r)
        return r

    def _kill(self):
        if self._proc:
            self._proc.kill()
        self._proc = self._conn = None

    def close(self):
        with self._lock:
            self._kill()
