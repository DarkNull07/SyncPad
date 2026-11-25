"""Records one shortcut pressed on the laptop's keyboard, when the phone asks.

Deliberately narrow, because it reads keys: it captures a single combination,
stops by itself, always shows a notice on the laptop, and keeps nothing.
"""
import ctypes
import threading
import time
from ctypes import wintypes

from .winput import VK

user32 = ctypes.WinDLL("user32", use_last_error=True)
TIMEOUT = 12.0
INJECTED = 0x10
MODIFIERS = {"ctrl", "alt", "shift", "win"}

_NAMES = {v: k for k, v in VK.items()}
_NAMES.update({0x10: "shift", 0xA0: "shift", 0xA1: "shift", 0x11: "ctrl", 0xA2: "ctrl", 0xA3: "ctrl",
               0x12: "alt", 0xA4: "alt", 0xA5: "alt", 0x5B: "win", 0x5C: "win", 0x2C: "printscreen"})


class Combo:
    """Collects keys until all are released. Pure logic, so it is easy to test."""

    def __init__(self):
        self.down, self.mods, self.key, self.done = set(), [], None, False

    def feed(self, vk: int, pressed: bool):
        name = _NAMES.get(vk)
        if name is None or self.done:
            return
        if pressed:
            self.down.add(vk)
            if name in MODIFIERS:
                if name not in self.mods and self.key is None:
                    self.mods.append(name)
            else:
                self.key = name
                self.held = list(self.mods)
        else:
            # Print Screen reports only its release on some keyboards.
            if vk == 0x2C and self.key is None:
                self.key, self.held = name, list(self.mods)
            self.down.discard(vk)
            if not self.down and (self.key or self.mods):
                self.done = True

    def result(self):
        if self.key is None:
            return list(self.mods)
        return [m for m in ("ctrl", "alt", "shift", "win") if m in self.held] + [self.key]


class KBD(ctypes.Structure):
    _fields_ = [("vk", wintypes.DWORD), ("scan", wintypes.DWORD), ("flags", wintypes.DWORD), ("time", wintypes.DWORD),
                ("extra", ctypes.c_size_t)]


HOOK = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, ctypes.c_int, wintypes.WPARAM, wintypes.LPARAM)
user32.SetWindowsHookExW.argtypes = (ctypes.c_int, HOOK, wintypes.HINSTANCE, wintypes.DWORD)
user32.SetWindowsHookExW.restype = wintypes.HHOOK
user32.CallNextHookEx.argtypes = (wintypes.HHOOK, ctypes.c_int, wintypes.WPARAM, wintypes.LPARAM)
user32.CallNextHookEx.restype = ctypes.c_ssize_t
user32.UnhookWindowsHookEx.argtypes = (wintypes.HHOOK,)
user32.PeekMessageW.argtypes = (ctypes.POINTER(wintypes.MSG), wintypes.HWND, wintypes.UINT, wintypes.UINT, wintypes.UINT)


class Recorder:
    def __init__(self, accept_injected=False):
        self.accept_injected = accept_injected       # tests only
        self._lock = threading.Lock()
        self._cancel = threading.Event()

    def cancel(self):
        self._cancel.set()

    def record(self, timeout=TIMEOUT):
        """Blocks. Returns the key names, or None if cancelled, timed out or already recording."""
        if not self._lock.acquire(blocking=False):
            return None
        try:
            self._cancel.clear()
            out = []
            t = threading.Thread(target=self._run, args=(timeout, out), daemon=True)
            t.start()
            t.join()
            return out or None
        finally:
            self._lock.release()

    def _run(self, timeout, out):
        combo = Combo()

        def proc(code, wp, lp):
            if code == 0:
                e = ctypes.cast(lp, ctypes.POINTER(KBD)).contents
                if self.accept_injected or not e.flags & INJECTED:
                    combo.feed(e.vk, wp in (0x0100, 0x0104))
                    return 1              # kept from Windows, so recording Win+D does not also show the desktop
            return user32.CallNextHookEx(None, code, wp, lp)

        keep = HOOK(proc)
        hook = user32.SetWindowsHookExW(13, keep, None, 0)
        if not hook:
            return
        try:
            end, msg = time.monotonic() + timeout, wintypes.MSG()
            while not combo.done and not self._cancel.is_set() and time.monotonic() < end:
                while user32.PeekMessageW(ctypes.byref(msg), None, 0, 0, 1):
                    pass
                time.sleep(0.005)
        finally:
            user32.UnhookWindowsHookEx(hook)
        if combo.done and not self._cancel.is_set():
            out.extend(combo.result())
