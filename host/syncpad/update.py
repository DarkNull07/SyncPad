"""Updates: compares this version with the newest GitHub release.

When a newer one exists, the matching download is fetched in the background,
checked against the release's SHA256SUMS.txt, and kept ready. Nothing changes
until the person chooses to restart; then the new copy takes this one's place.
Run from source, it only says that a new version exists.
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

from .system import tool

REPO = "DarkNull07/SyncPad"
API = f"https://api.github.com/repos/{REPO}/releases/latest"
SITE = "https://syncpad-dark.vercel.app"
EVERY = 6 * 3600


def parse(v: str):
    try:
        return tuple(int(x) for x in v.strip().lstrip("v").split(".")[:3])
    except ValueError:
        return ()


def newer(latest: str, current: str) -> bool:
    a, b = parse(latest), parse(current)
    return bool(a) and bool(b) and a > b


def install_kind():
    """'file' for the one-file exe, 'folder' for the zip's folder, '' when run from source."""
    if not getattr(sys, "frozen", False):
        return ""
    here = Path(sys.executable).resolve().parent
    return "folder" if Path(getattr(sys, "_MEIPASS", "")).resolve().parent == here else "file"


def _get(url, timeout=20, agent="SyncPad"):
    req = urllib.request.Request(url, headers={"User-Agent": agent, "Accept": "application/vnd.github+json"})
    return urllib.request.urlopen(req, timeout=timeout)


class Updater:
    def __init__(self, version, on_change=lambda: None, enabled=lambda: True):
        self.version, self.on_change, self.enabled = version, on_change, enabled
        self.latest = ""          # newest version found, if newer than this one
        self.state = ""           # "", "available", "downloading", "ready", "failed"
        self.kind = install_kind()
        self._staged = None
        self._busy = False
        self._last = 0.0

    def start(self):
        threading.Thread(target=self._loop, daemon=True, name="syncpad-update").start()
        return self

    def _loop(self):
        time.sleep(20)                 # not during start-up
        while True:
            if self.enabled() and time.time() - self._last > EVERY:
                self.check()
            time.sleep(300)

    def check(self):
        if self._busy:
            return
        self._busy = True
        self._last = time.time()
        try:
            with _get(API, agent=f"SyncPad/{self.version}") as r:
                rel = json.load(r)
            tag = str(rel.get("tag_name", ""))
            if not newer(tag, self.version) or rel.get("draft") or rel.get("prerelease"):
                return
            self.latest = tag.lstrip("v")
            assets = {a.get("name"): a.get("browser_download_url") for a in rel.get("assets", [])}
            if not self.kind:
                self._set("available")
                return
            self._set("downloading")
            self._staged = self._download(assets)
            self._set("ready" if self._staged else "failed")
        except (OSError, ValueError):
            if self.state == "downloading":
                self._set("failed")
        finally:
            self._busy = False

    def _set(self, s):
        self.state = s
        self.on_change()

    def _download(self, assets):
        name = "SyncPad.exe" if self.kind == "file" else "SyncPad-windows.zip"
        if name not in assets or "SHA256SUMS.txt" not in assets:
            return None
        with _get(assets["SHA256SUMS.txt"]) as r:
            sums = dict(reversed(line.split("  ", 1)) for line in r.read().decode("utf-8").splitlines() if "  " in line)
        want = sums.get(name)
        if not want:
            return None
        folder = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "SyncPad" / "update"
        shutil.rmtree(folder, ignore_errors=True)
        folder.mkdir(parents=True, exist_ok=True)
        dest = folder / name
        h = hashlib.sha256()
        with _get(assets[name], timeout=60) as r, open(dest, "wb") as f:
            while chunk := r.read(1 << 16):
                h.update(chunk)
                f.write(chunk)
        if h.hexdigest() != want:
            dest.unlink(missing_ok=True)
            return None
        if self.kind == "folder":
            with zipfile.ZipFile(dest) as z:
                for n in z.namelist():
                    if n.startswith("/") or ".." in Path(n).parts:
                        return None
                z.extractall(folder / "new")
            dest.unlink()
            app = folder / "new" / "SyncPad"
            return app if (app / "SyncPad.exe").exists() else None
        return dest

    def apply(self) -> bool:
        """Puts the new copy in place and starts it. True means: quit now."""
        if self.state != "ready" or not self._staged:
            return False
        exe = Path(sys.executable).resolve()
        if self.kind == "file":
            old = exe.with_suffix(".old.exe")
            old.unlink(missing_ok=True)
            exe.rename(old)                      # Windows lets a running exe be renamed, not replaced
            shutil.move(str(self._staged), exe)
            subprocess.Popen([str(exe)], close_fds=True)
            return True
        here = exe.parent
        old = here.with_name(here.name + ".old")
        script = Path(self._staged).parent.parent / "swap.cmd"
        script.write_text(
            "@echo off\r\n"
            f":wait\r\ntasklist /FI \"PID eq {os.getpid()}\" | find \"{os.getpid()}\" >nul && (timeout /t 1 /nobreak >nul & goto wait)\r\n"
            f"rmdir /s /q \"{old}\" 2>nul\r\n"
            f"move \"{here}\" \"{old}\" >nul || (start \"\" \"{exe}\" & exit /b)\r\n"
            f"move \"{self._staged}\" \"{here}\" >nul || (move \"{old}\" \"{here}\" >nul & start \"\" \"{exe}\" & exit /b)\r\n"
            f"start \"\" \"{exe}\"\r\nrmdir /s /q \"{old}\"\r\n", "utf-8")
        subprocess.Popen([tool("cmd.exe"), "/c", str(script)], creationflags=0x08000000 | 0x00000008, close_fds=True)
        return True


def tidy():
    """After an update of the one-file exe, the previous copy is left behind once."""
    if install_kind() == "file":
        try:
            Path(sys.executable).resolve().with_suffix(".old.exe").unlink(missing_ok=True)
        except OSError:
            pass               # still closing; next time
