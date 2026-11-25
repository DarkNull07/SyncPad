"""Text clipboard access and change watching."""
import ctypes
import threading
import time
from ctypes import wintypes

u32 = ctypes.WinDLL("user32", use_last_error=True)
k32 = ctypes.WinDLL("kernel32", use_last_error=True)
u32.GetClipboardData.restype = wintypes.HANDLE
u32.GetClipboardData.argtypes = (wintypes.UINT,)
u32.SetClipboardData.restype = wintypes.HANDLE
u32.SetClipboardData.argtypes = (wintypes.UINT, wintypes.HANDLE)
u32.OpenClipboard.argtypes = (wintypes.HWND,)
k32.GlobalAlloc.restype = wintypes.HGLOBAL
k32.GlobalAlloc.argtypes = (wintypes.UINT, ctypes.c_size_t)
k32.GlobalFree.argtypes = (wintypes.HGLOBAL,)
k32.GlobalLock.restype = ctypes.c_void_p
k32.GlobalLock.argtypes = (wintypes.HGLOBAL,)
k32.GlobalUnlock.argtypes = (wintypes.HGLOBAL,)

CF_UNICODETEXT = 13
MAX_CHARS = 200_000
# Password managers mark secrets with these so that tools like this one skip them.
_EXCLUDE = [u32.RegisterClipboardFormatW(n) for n in (
    "ExcludeClipboardContentFromMonitorProcessing", "Clipboard Viewer Ignore")]
_HISTORY = u32.RegisterClipboardFormatW("CanIncludeInClipboardHistory")


def _open():
    for _ in range(20):
        if u32.OpenClipboard(None):
            return True
        time.sleep(0.01)
    return False


def sequence() -> int:
    return u32.GetClipboardSequenceNumber()


def get_text(skip_secrets=False):
    if not _open():
        return None
    try:
        if skip_secrets:
            if any(u32.IsClipboardFormatAvailable(f) for f in _EXCLUDE):
                return None
            if u32.IsClipboardFormatAvailable(_HISTORY):
                h = u32.GetClipboardData(_HISTORY)
                p = k32.GlobalLock(h) if h else None
                if p:
                    allowed = ctypes.cast(p, ctypes.POINTER(wintypes.DWORD))[0]
                    k32.GlobalUnlock(h)
                    if not allowed:
                        return None
        h = u32.GetClipboardData(CF_UNICODETEXT)
        if not h:
            return None
        p = k32.GlobalLock(h)
        if not p:
            return None
        try:
            return ctypes.wstring_at(p)[:MAX_CHARS]
        finally:
            k32.GlobalUnlock(h)
    finally:
        u32.CloseClipboard()


def set_text(s: str) -> bool:
    data = (s[:MAX_CHARS] + "\0").encode("utf-16-le", "ignore")
    h = k32.GlobalAlloc(0x0002, len(data))
    if not h:
        return False
    p = k32.GlobalLock(h)
    ctypes.memmove(p, data, len(data))
    k32.GlobalUnlock(h)
    if not _open():
        k32.GlobalFree(h)
        return False
    try:
        u32.EmptyClipboard()
        if u32.SetClipboardData(CF_UNICODETEXT, h):
            return True          # Windows owns the memory now
        k32.GlobalFree(h)
        return False
    finally:
        u32.CloseClipboard()


class Watcher(threading.Thread):
    """Calls on_text(s) when some other program changes the clipboard text."""

    def __init__(self, on_text):
        super().__init__(daemon=True)
        self.on_text = on_text
        self.seq = sequence()

    def put(self, s: str) -> None:
        if set_text(s):
            self.seq = sequence()    # our own write must not echo back

    def run(self):
        while True:
            time.sleep(0.3)
            n = sequence()
            if n == self.seq:
                continue
            self.seq = n
            s = get_text(skip_secrets=True)
            if s:
                self.on_text(s)
