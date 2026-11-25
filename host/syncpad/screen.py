"""Screen capture in its own process, so encoding never delays pointer packets."""
import io
import multiprocessing as mp
import threading


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
                width, quality, index = req
                mons = grab.monitors[1:] or grab.monitors
                m = mons[min(max(index, 0), len(mons) - 1)]
                shot = grab.grab(m)
                img = Image.frombuffer("RGB", shot.size, shot.bgra, "raw", "BGRX", 0, 1)
                if img.width > width:
                    img = img.resize((width, round(img.height * width / img.width)),
                                     Image.Resampling.BILINEAR, reducing_gap=1.5)
                out = io.BytesIO()
                img.save(out, "JPEG", quality=quality, subsampling=2)
                cx, cy = cursor_pos()
                fx = (cx - m["left"]) * 65535 // max(m["width"] - 1, 1)
                fy = (cy - m["top"]) * 65535 // max(m["height"] - 1, 1)
                inside = 0 <= fx <= 65535 and 0 <= fy <= 65535
                conn.send((fx if inside else 65535, fy if inside else 65535,
                           img.width, img.height, len(mons), out.getvalue()))
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

    def frame(self, width: int, quality: int, index: int):
        with self._lock:
            if self._proc is None or not self._proc.is_alive():
                parent, child = mp.Pipe()
                self._proc = mp.get_context("spawn").Process(
                    target=_worker, args=(child,), daemon=True)
                self._proc.start()
                child.close()
                self._conn = parent
            try:
                self._conn.send((width, quality, index))
                if not self._conn.poll(10):
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
