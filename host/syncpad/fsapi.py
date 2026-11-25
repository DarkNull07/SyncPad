"""Browsing this laptop's files from a paired phone. See docs/PROTOCOL.md."""
import ctypes
import io
import json
import os
import shutil
import stat
import string
import subprocess
import time
import zipfile
from ctypes import wintypes
from pathlib import Path

from .system import tool
from .wire import M_DATA, WireError

CHUNK = 256 * 1024
MAX_ITEMS = 4000
M_BLOB = 0x21
IMAGES = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".ico", ".tif", ".tiff"}
# Opened by showing their folder instead: these run code.
RUNNABLE = {".exe", ".bat", ".cmd", ".com", ".msi", ".ps1", ".vbs", ".vbe", ".js", ".jse", ".scr", ".lnk", ".reg",
            ".hta", ".jar", ".pif", ".wsf", ".msc", ".cpl", ".url", ".appref-ms"}
BAD_NAME = set('<>:"/\\|?*') | {chr(i) for i in range(32)}


class FsError(Exception):
    """Shown to the person as is."""


class _GUID(ctypes.Structure):
    _fields_ = [("b", ctypes.c_byte * 16)]


def _known(guid):
    g = _GUID()
    if ctypes.windll.ole32.CLSIDFromString(guid, ctypes.byref(g)):
        return None
    p = ctypes.c_wchar_p()
    if ctypes.windll.shell32.SHGetKnownFolderPath(ctypes.byref(g), 0, None, ctypes.byref(p)):
        return None
    value = p.value
    ctypes.windll.ole32.CoTaskMemFree(p)
    return value


def known_folders():
    out = []
    for label, kind, guid in (
            ("Desktop", "desktop", "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}"),
            ("Downloads", "download", "{374DE290-123F-4565-9164-39C4925E467B}"),
            ("Documents", "document", "{FDD39AD0-238F-46AF-ADB4-6C85480369C7}"),
            ("Pictures", "picture", "{33E28130-4E1E-4676-835A-98395C3BC3BB}"),
            ("Videos", "video", "{18989B1D-99B5-455B-841C-AB7C74E4DDFC}"),
            ("Music", "music", "{4BD8D571-6D19-48D3-BE97-422220080E43}")):
        path = _known(guid) or str(Path.home() / label)      # follows folders moved to another drive
        if os.path.isdir(path):
            out.append({"n": label, "p": path, "k": kind})
    return out


def drives():
    mask = ctypes.windll.kernel32.GetLogicalDrives()
    out = []
    for i, c in enumerate(string.ascii_uppercase):
        if not mask >> i & 1:
            continue
        root = f"{c}:\\"
        try:
            u = shutil.disk_usage(root)
        except OSError:
            continue           # an empty card reader
        buf = ctypes.create_unicode_buffer(64)
        ctypes.windll.kernel32.GetVolumeInformationW(root, buf, 64, None, None, None, None, 0)
        out.append({"n": f"{buf.value or 'Drive'} ({c}:)", "p": root, "k": "drive", "total": u.total, "free": u.free})
    return out


def check(path) -> str:
    p = str(path)
    # Plain drive paths only. \\server paths would make Windows send credentials
    # to another machine, and \\?\ or \\.\ paths reach devices.
    if len(p) < 3 or p[1:3] != ":\\" or not p[0].isalpha() or "\0" in p:
        raise FsError("That is not a folder on this laptop")
    p = os.path.abspath(p)
    # Windows treats names like CON and NUL as devices in any folder, and a second
    # colon as a hidden stream inside a file.
    if p[1:3] != ":\\" or ":" in p[2:] or any(_is_device(part) for part in p[3:].split("\\")):
        raise FsError("That is not a folder on this laptop")
    return p


_DEVICES = {"con", "prn", "aux", "nul", "conin$", "conout$", *(f"{a}{i}" for a in ("com", "lpt") for i in range(10))}


def _is_device(name):
    return name.split(".")[0].rstrip(" ").lower() in _DEVICES


def check_name(name) -> str:
    n = str(name).strip()
    if not n or n in (".", "..") or len(n) > 200 or set(n) & BAD_NAME or n.endswith(".") or _is_device(n):
        raise FsError("That name cannot be used")
    return n


def _item(e):
    try:
        st = e.stat(follow_symlinks=False)
    except OSError:
        return None
    attrs = st.st_file_attributes
    d = bool(attrs & stat.FILE_ATTRIBUTE_DIRECTORY)
    return {"n": e.name, "d": d, "s": 0 if d else st.st_size, "m": int(st.st_mtime),
            "h": bool(attrs & (stat.FILE_ATTRIBUTE_HIDDEN | stat.FILE_ATTRIBUTE_SYSTEM))}


def listing(path, hidden=False):
    p = check(path)
    out, more = [], False
    with os.scandir(p) as it:
        for e in it:
            x = _item(e)
            if x is None or (x["h"] and not hidden):
                continue
            if len(out) >= MAX_ITEMS:
                more = True
                break
            out.append(x)
    parent = os.path.dirname(p)
    return {"t": "ok", "path": p, "parent": "" if parent == p else parent, "items": out, "more": more}


def search(path, query, limit=300, seconds=4.0):
    root, q = check(path), str(query).strip().lower()
    out = []
    if not q:
        return {"t": "ok", "path": root, "items": out, "more": False}
    end = time.monotonic() + seconds
    for folder, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in ("$RECYCLE.BIN", "System Volume Information", "node_modules", ".git")]
        names = set(dirs)
        for n in dirs + files:
            if q not in n.lower():
                continue
            full = os.path.join(folder, n)
            try:
                st = os.stat(full)
            except OSError:
                continue
            d = n in names
            out.append({"n": n, "d": d, "s": 0 if d else st.st_size, "m": int(st.st_mtime), "in": folder})
            if len(out) >= limit:
                return {"t": "ok", "path": root, "items": out, "more": True}
        if time.monotonic() > end:
            return {"t": "ok", "path": root, "items": out, "more": True}
    return {"t": "ok", "path": root, "items": out, "more": False}


def thumb(path, width):
    from PIL import Image, ImageOps
    p = check(path)
    if Path(p).suffix.lower() not in IMAGES or os.path.getsize(p) > 80_000_000:
        raise FsError("No preview")
    w = min(max(int(width), 48), 1600)
    try:
        with Image.open(p) as im:
            im.draft("RGB", (w, w))          # lets JPEG decode straight to a small size
            im = ImageOps.exif_transpose(im)
            im.thumbnail((w, w), Image.Resampling.BILINEAR, reducing_gap=2.0)
            if im.mode not in ("RGB", "L"):
                rgba = im.convert("RGBA")
                im = Image.new("RGB", rgba.size, (34, 37, 42))
                im.paste(rgba, mask=rgba.split()[3])
            out = io.BytesIO()
            im.save(out, "JPEG", quality=82 if w > 400 else 72)
            return out.getvalue()
    except (OSError, ValueError, SyntaxError, Image.DecompressionBombError):
        raise FsError("No preview") from None


def text(path, limit=48_000):
    with open(check(path), "rb") as f:
        raw = f.read(limit + 1)
    wide = raw.startswith((b"\xff\xfe", b"\xfe\xff"))
    if b"\0" in raw[:4000] and not wide:
        raise FsError("No preview")
    body, more = raw[:limit], len(raw) > limit
    if wide:
        s = body.decode("utf-16", "ignore")
    else:
        try:
            # A cut can land inside a character; that is not a reason to give up on UTF-8.
            s = body.decode("utf-8-sig", "ignore" if more else "strict")
        except UnicodeError:
            s = body.decode("cp1252", "replace")
    return {"t": "ok", "s": s, "more": more}


class _SHFILEOP(ctypes.Structure):
    _fields_ = [("hwnd", wintypes.HWND), ("wFunc", wintypes.UINT), ("pFrom", ctypes.c_void_p), ("pTo", ctypes.c_void_p),
                ("fFlags", ctypes.c_ushort), ("aborted", wintypes.BOOL), ("maps", ctypes.c_void_p), ("title", ctypes.c_void_p)]


def trash(path):
    """Recycle Bin only. Anything Windows would erase outright is refused."""
    p = check(path)
    same = lambda q: os.path.normcase(os.path.abspath(q)) == os.path.normcase(p)
    if os.path.dirname(p) == p:
        raise FsError("A drive cannot be deleted")
    protected = [k["p"] for k in known_folders()] + [str(Path.home()), os.environ.get("SystemRoot", r"C:\Windows")]
    if any(same(q) for q in protected):
        raise FsError("That folder is part of Windows")
    if not os.path.lexists(p):
        raise FsError("That no longer exists")
    # Drives without a Recycle Bin erase at once, so only fixed disks are allowed.
    if ctypes.windll.kernel32.GetDriveTypeW(p[:3]) != 3:
        raise FsError("This drive has no Recycle Bin")
    buf = ctypes.create_unicode_buffer(p + "\0\0")       # the list ends with two nulls
    op = _SHFILEOP(None, 3, ctypes.cast(buf, ctypes.c_void_p).value, None,
                   0x0040 | 0x0010 | 0x0400 | 0x0004 | 0x4000, False, None, None)
    # ALLOWUNDO, NOCONFIRMATION, NOERRORUI, SILENT, WANTNUKEWARNING
    ctypes.windll.shell32.SHFileOperationW(ctypes.byref(op))
    if os.path.lexists(p):
        raise FsError("Windows would not move that to the Recycle Bin")


class _ZipOut(io.RawIOBase):
    """Turns a zip being written into data frames."""

    def __init__(self, ch):
        self.ch, self.buf, self.n = ch, bytearray(), 0

    def writable(self):
        return True

    def seekable(self):
        return False

    def write(self, b):
        self.buf += b
        self.n += len(b)
        while len(self.buf) >= CHUNK:
            self.ch.send(bytes([M_DATA]) + bytes(self.buf[:CHUNK]))
            del self.buf[:CHUNK]
        return len(b)

    def finish(self):
        if self.buf:
            self.ch.send(bytes([M_DATA]) + bytes(self.buf))
            self.buf.clear()


class FsSession:
    def __init__(self, ch, server):
        self.ch, self.server = ch, server

    def reply(self, **m):
        self.ch.send(json.dumps(m).encode())

    def serve(self, m):
        while True:
            try:
                self.handle(m)
            except FsError as e:
                self.reply(t="err", s=str(e))
            except PermissionError:
                self.reply(t="err", s="Windows does not allow access to that")
            except FileNotFoundError:
                self.reply(t="err", s="That no longer exists")
            except (KeyError, ValueError, TypeError):
                self.reply(t="err", s="Bad request")
            m = json.loads(self.ch.recv())

    def handle(self, m):
        op = m.get("op")
        if op == "roots":
            self.reply(t="ok", folders=known_folders() + [{"n": "SyncPad", "p": str(self.server.save_dir()), "k": "syncpad"}],
                       drives=drives())
        elif op == "list":
            self.reply(**listing(m["path"], bool(m.get("hidden"))))
        elif op == "search":
            self.reply(**search(m["path"], m["q"]))
        elif op == "thumb":
            self.ch.send(bytes([M_BLOB]) + thumb(m["path"], m.get("w", 160)))
        elif op == "text":
            self.reply(**text(m["path"]))
        elif op == "open":
            self._open(check(m["path"]))
        elif op == "reveal":
            p = check(m["path"])
            if not os.path.lexists(p):
                raise FsError("That no longer exists")
            self._reveal(p)
            self.reply(t="ok")
        elif op == "mkdir":
            try:
                os.mkdir(os.path.join(check(m["path"]), check_name(m["name"])))
            except FileExistsError:
                raise FsError("Something here already has that name") from None
            self.reply(t="ok")
        elif op == "rename":
            p = check(m["path"])
            if os.path.dirname(p) == p:
                raise FsError("A drive cannot be renamed here")
            target = os.path.join(os.path.dirname(p), check_name(m["name"]))
            if os.path.lexists(target) and os.path.normcase(target) != os.path.normcase(p):
                raise FsError("Something here already has that name")
            os.rename(p, target)
            self.reply(t="ok")
        elif op == "trash":
            trash(m["path"])
            self.reply(t="ok")
        elif op == "get":
            p = check(m["path"])
            (self._get_folder if os.path.isdir(p) else self._get)(p)
        elif op == "put":
            folder = check(m["path"])
            if not os.path.isdir(folder):
                raise FsError("That folder no longer exists")
            self.server.receive(self.ch, m.get("name", "file"), int(m.get("size", -1)), Path(folder))
        else:
            raise FsError("Bad request")

    def _reveal(self, p):
        if self.server.dry:
            self.server.inp._rec("reveal", p)
        else:
            # Passed as one string: Explorer parses its own command line. Windows
            # file names cannot contain a quote, so the path cannot break out.
            subprocess.Popen(f'"{tool("explorer.exe")}" /select,"{p}"')

    def _open(self, p):
        if not os.path.lexists(p):
            raise FsError("That no longer exists")
        if Path(p).suffix.lower() in RUNNABLE and not os.path.isdir(p):
            self._reveal(p)
            self.reply(t="ok", note="Shown in its folder. Programs are not started from here.")
            return
        if self.server.dry:
            self.server.inp._rec("open", p)
        else:
            os.startfile(p)
        self.reply(t="ok")

    def _get(self, p):
        size = os.path.getsize(p)
        with open(p, "rb") as f:
            self.reply(t="go", name=os.path.basename(p), size=size)
            left = size
            while left > 0:
                b = f.read(min(CHUNK, left))
                if not b:
                    raise WireError("file shrank")       # drops the channel; the phone reports it
                self.ch.send(bytes([M_DATA]) + b)
                left -= len(b)
        self.reply(t="done", size=size)

    def _get_folder(self, p):
        if os.path.dirname(p) == p:
            raise FsError("Choose a folder, not a whole drive")
        self.reply(t="go", name=os.path.basename(p) + ".zip", size=-1)
        out = _ZipOut(self.ch)
        base, skipped = os.path.dirname(p), 0
        try:
            # Lightly compressed, not stored: many unzip apps reject a streamed archive with stored entries.
            with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, allowZip64=True, compresslevel=1) as z:
                for folder, dirs, files in os.walk(p):
                    # A junction could lead out of the folder, or round in circles.
                    dirs[:] = [d for d in dirs if not os.path.islink(os.path.join(folder, d))
                               and not os.path.isjunction(os.path.join(folder, d))]
                    if not files and not dirs:
                        z.writestr(os.path.relpath(folder, base).replace("\\", "/") + "/", b"")
                    for n in files:
                        full = os.path.join(folder, n)
                        try:
                            with open(full, "rb"):
                                pass
                        except OSError:
                            skipped += 1          # in use or not ours; the rest is still wanted
                            continue
                        z.write(full, os.path.relpath(full, base))
        except (OSError, ValueError) as e:
            # The archive has started, so an error reply would land inside it.
            raise WireError(str(e)) from None
        out.finish()
        self.reply(t="done", size=out.n, skipped=skipped)
