"""Mouse and keyboard injection through Win32 SendInput."""
import ctypes
import math
import threading
import time
from ctypes import wintypes

user32 = ctypes.WinDLL("user32", use_last_error=True)

ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class _U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wintypes.DWORD), ("u", _U)]


user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
user32.SendInput.restype = wintypes.UINT

MOVE, ABSOLUTE, VIRTUALDESK = 0x0001, 0x8000, 0x4000
WHEEL, HWHEEL = 0x0800, 0x1000
BUTTONS = {0: (0x0002, 0x0004, 0), 1: (0x0008, 0x0010, 0), 2: (0x0020, 0x0040, 0),
           3: (0x0080, 0x0100, 1), 4: (0x0080, 0x0100, 2)}   # left right middle back forward
KEYUP, EXTENDED, UNICODE, SCANCODE = 0x0002, 0x0001, 0x0004, 0x0008

VK = {
    "enter": 0x0D, "backspace": 0x08, "tab": 0x09, "esc": 0x1B, "space": 0x20, "delete": 0x2E,
    "insert": 0x2D, "home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22,
    "up": 0x26, "down": 0x28, "left": 0x25, "right": 0x27,
    "ctrl": 0xA2, "alt": 0xA4, "shift": 0xA0, "win": 0x5B, "rctrl": 0xA3, "ralt": 0xA5,
    "capslock": 0x14, "printscreen": 0x2C, "menu": 0x5D, "pause": 0x13,
    "play_pause": 0xB3, "next": 0xB0, "prev": 0xB1, "stop": 0xB2,
    "vol_up": 0xAF, "vol_down": 0xAE, "mute": 0xAD,
    "browser_back": 0xA6, "browser_forward": 0xA7,
    ";": 0xBA, "=": 0xBB, ",": 0xBC, "-": 0xBD, ".": 0xBE, "/": 0xBF, "`": 0xC0,
    "[": 0xDB, "\\": 0xDC, "]": 0xDD, "'": 0xDE,
    "numlock": 0x90, "scrolllock": 0x91, "nummul": 0x6A, "numadd": 0x6B, "numsub": 0x6D, "numdot": 0x6E, "numdiv": 0x6F,
}
VK.update({f"num{i}": 0x60 + i for i in range(10)})
VK.update({f"f{i}": 0x6F + i for i in range(1, 25)})
VK.update({c: ord(c.upper()) for c in "abcdefghijklmnopqrstuvwxyz0123456789"})
EXTENDED_VK = {0x2E, 0x2D, 0x24, 0x23, 0x21, 0x22, 0x26, 0x28, 0x25, 0x27, 0x5B, 0xA3, 0xA5,
               0x2C, 0x5D, 0x6F, 0x90, 0xB3, 0xB0, 0xB1, 0xB2, 0xAF, 0xAE, 0xAD, 0xA6, 0xA7}


# Names people type or other keyboards print.
ALIAS = {"prtsc": "printscreen", "prtscn": "printscreen", "prtscr": "printscreen", "prntscrn": "printscreen", "printscrn": "printscreen",
         "print": "printscreen", "snapshot": "printscreen", "sysrq": "printscreen", "windows": "win", "cmd": "win", "super": "win", "meta": "win",
         "control": "ctrl", "ctl": "ctrl", "lctrl": "ctrl", "option": "alt", "lalt": "alt", "altgr": "ralt", "lshift": "shift", "rshift": "shift",
         "return": "enter", "escape": "esc", "del": "delete", "ins": "insert", "pgup": "pageup", "pgdn": "pagedown", "pgdown": "pagedown",
         "spacebar": "space", "bksp": "backspace", "caps": "capslock", "apps": "menu", "plus": "=", "minus": "-", "comma": ",", "period": ".", "dot": ".",
         "slash": "/", "backslash": "\\", "semicolon": ";", "quote": "'", "equals": "=", "volup": "vol_up", "voldown": "vol_down"}


def key_name(name):
    """The canonical key name, or None if there is no such key."""
    n = str(name).strip().lower().replace(" ", "")
    n = ALIAS.get(n, n)
    return n if n in VK else None


def set_dpi_aware() -> None:
    try:
        user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
    except (AttributeError, OSError):
        user32.SetProcessDPIAware()


def cursor_pos():
    p = wintypes.POINT()
    user32.GetCursorPos(ctypes.byref(p))
    return p.x, p.y


def virtual_screen():
    g = user32.GetSystemMetrics
    return g(76), g(77), g(78), g(79)


def _send(*inputs) -> None:
    arr = (INPUT * len(inputs))(*inputs)
    user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT))


def _mouse(dx=0, dy=0, data=0, flags=0):
    i = INPUT(type=0)
    i.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
    return i


def _key(vk: int, down: bool):
    # Windows maps Print Screen to the scan code of Alt+SysRq, which is another key.
    scan = 0x37 if vk == 0x2C else user32.MapVirtualKeyW(vk, 0)
    flags = (0 if down else KEYUP) | (EXTENDED if vk in EXTENDED_VK else 0)
    i = INPUT(type=1)
    # Scan codes reach games that ignore virtual keys; media keys have none worth using.
    if scan and not 0xA6 <= vk <= 0xB7 and vk not in (0x13, 0x2C, 0x90, 0x91):
        i.ki = KEYBDINPUT(vk, scan, flags | SCANCODE, 0, 0)
    else:
        i.ki = KEYBDINPUT(vk, scan, flags, 0, 0)
    return i


class WinInput:
    """Not thread safe by itself; Session serialises calls."""

    def move(self, dx: int, dy: int, raw: bool) -> None:
        if raw:
            # Relative motion: what games read, but Windows applies its own acceleration.
            _send(_mouse(dx, dy, 0, MOVE))
            return
        # Absolute motion bypasses "Enhance pointer precision", so the phone's
        # own curve is the only one in play.
        x, y = cursor_pos()
        self.move_to(x + dx, y + dy)

    def move_to(self, x: int, y: int) -> None:
        vx, vy, vw, vh = virtual_screen()
        x = min(max(x, vx), vx + vw - 1)
        y = min(max(y, vy), vy + vh - 1)
        ax = math.ceil((x - vx) * 65536 / vw)
        ay = math.ceil((y - vy) * 65536 / vh)
        _send(_mouse(ax, ay, 0, MOVE | ABSOLUTE | VIRTUALDESK))

    def button(self, b: int, down: bool) -> None:
        d, u, data = BUTTONS[b]
        _send(_mouse(0, 0, data, d if down else u))

    def wheel(self, v: int) -> None:
        _send(_mouse(0, 0, v, WHEEL))

    def hwheel(self, v: int) -> None:
        _send(_mouse(0, 0, v, HWHEEL))

    def key(self, name: str, down: bool) -> None:
        _send(_key(VK[name], down))

    def text(self, s: str) -> None:
        for ch in s.replace("\r\n", "\n"):
            if ch in "\n\r":
                _send(_key(0x0D, True), _key(0x0D, False))
            elif ch == "\t":
                _send(_key(0x09, True), _key(0x09, False))
            else:
                raw = ch.encode("utf-16-le", "ignore")
                seq = []
                for o in range(0, len(raw), 2):
                    unit = raw[o] | raw[o + 1] << 8
                    for up in (0, KEYUP):
                        i = INPUT(type=1)
                        i.ki = KEYBDINPUT(0, unit, UNICODE | up, 0, 0)
                        seq.append(i)
                if seq:
                    _send(*seq)


class DryInput:
    """Records calls instead of performing them. Used by the tests and --dry-run."""

    def __init__(self, echo=False):
        self.log, self.pos, self.echo = [], [500, 500], echo

    def _rec(self, *a):
        self.log.append(a)
        if self.echo:
            print("[dry-run]", *a, flush=True)

    def move(self, dx, dy, raw):
        self.pos[0] += dx
        self.pos[1] += dy
        self._rec("move", dx, dy)

    def move_to(self, x, y):
        self.pos[:] = [x, y]
        self._rec("move_to", x, y)

    def button(self, b, down): self._rec("button", b, bool(down), tuple(self.pos))
    def wheel(self, v): self._rec("wheel", v)
    def hwheel(self, v): self._rec("hwheel", v)
    def key(self, name, down): self._rec("key", name, bool(down))
    def text(self, s): self._rec("text", s)


class Pointer:
    """Fractional accumulation, plus smoothing of bursty Wi-Fi delivery.

    smooth = 0 applies every packet at once. Above 0, each 4 ms tick applies a
    share of what is pending, so a 20 ms burst becomes several small steps.

    Some Wi-Fi links pause for 200 ms at a time and then deliver everything at
    once. Packets arriving faster than a finger can produce them, right after
    such a pause, are glided over instead of jumping. A steady link never
    triggers this, so it costs nothing there.
    """

    GAP, LONGEST = 0.045, 0.8       # a pause worth noticing; longer is a finger at rest
    BURST = 0.003                   # no touchscreen reports this often
    GLIDE = 0.8                     # as a smoothing strength: about 50 ms to arrive

    def __init__(self, inp, lock: threading.RLock):
        self.inp, self.lock = inp, lock
        self.fx = self.fy = 0.0
        self.raw = False
        self.smooth = 0.0
        self._wake = threading.Event()
        self._stop = False
        self._thread = None
        self._last = 0.0
        self._paused_at = -1.0
        self._gliding = False
        self.clock = time.perf_counter       # replaced by the tests

    def add(self, dx64: int, dy64: int) -> None:
        now = self.clock()
        with self.lock:
            since, self._last = now - self._last, now
            if self.GAP < since < self.LONGEST:
                self._paused_at = now
            elif since < self.BURST and now - self._paused_at < 0.06:
                self._gliding = True
            self.fx += dx64 / 64
            self.fy += dy64 / 64
            if self.smooth <= 0 and not self._gliding:
                self._apply(1.0)
                return
            if self._thread is None:
                self._thread = threading.Thread(target=self._run, daemon=True)
                self._thread.start()
        self._wake.set()

    def flush(self) -> None:
        """Finish any pending motion, so a click lands where the finger was."""
        with self.lock:
            self._apply(1.0)
            self._gliding = False

    def _apply(self, share: float) -> None:
        ix = math.trunc(self.fx * share)
        iy = math.trunc(self.fy * share)
        if share < 1 and not (ix or iy):
            ix, iy = math.trunc(self.fx), math.trunc(self.fy)
        if ix or iy:
            self.fx -= ix
            self.fy -= iy
            self.inp.move(ix, iy, self.raw)

    def _run(self) -> None:
        while not self._stop:
            self._wake.wait()
            with self.lock:
                strength = max(self.smooth, self.GLIDE if self._gliding else 0.0)
                self._apply(1.0 - 0.8 * min(max(strength, 0.0), 1.0))
                if abs(self.fx) < 1 and abs(self.fy) < 1:
                    self._gliding = False
                    self._wake.clear()
            time.sleep(0.004)

    def close(self) -> None:
        self._stop = True
        self._wake.set()
