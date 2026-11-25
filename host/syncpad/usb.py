"""USB cable link: keeps `adb reverse` set up so the phone reaches us on 127.0.0.1."""
import os
import subprocess
import threading
import time
from pathlib import Path

from . import wire

NO_WINDOW = 0x08000000
ACTIVITY = "dev.syncpad.app/.MainActivity"


def find_adb():
    # Never from the current folder: Windows would otherwise look there first.
    path = os.pathsep.join(d for d in os.environ.get("PATH", "").split(os.pathsep) if os.path.isabs(d))
    on_path = next((f for d in path.split(os.pathsep) if d and os.path.isfile(f := os.path.join(d, "adb.exe"))), None)
    for c in (on_path, os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")):
        if c and Path(c).is_file():
            return c
    return None


class Usb(threading.Thread):
    def __init__(self, port=wire.TCP_PORT, on_change=lambda: None):
        super().__init__(daemon=True)
        self.adb, self.port, self.on_change = find_adb(), port, on_change
        self.enabled = True
        self.ready, self.waiting = [], []   # serials linked / needing approval on the phone

    def _run(self, *args, timeout=8):
        return subprocess.run([self.adb, *args], capture_output=True, text=True, timeout=timeout,
                              creationflags=NO_WINDOW, stdin=subprocess.DEVNULL)

    def poll(self):
        ready, waiting = [], []
        for line in self._run("devices").stdout.splitlines()[1:]:
            serial, _, state = line.partition("\t")
            if state == "device":
                r = self._run("-s", serial, "reverse", f"tcp:{self.port}", f"tcp:{self.port}")
                if r.returncode == 0:
                    ready.append(serial)
            elif state == "unauthorized":
                waiting.append(serial)
        if (ready, waiting) != (self.ready, self.waiting):
            self.ready, self.waiting = ready, waiting
            self.on_change()

    def run(self):
        while self.adb:
            if self.enabled:
                try:
                    self.poll()
                except (OSError, subprocess.SubprocessError):
                    pass
            time.sleep(4)

    def pair(self, link: str) -> bool:
        """Open the pairing link on a cabled phone. The link is single use."""
        ok = False
        for serial in self.ready:
            r = self._run("-s", serial, "shell",
                          f"am start -n {ACTIVITY} -a android.intent.action.VIEW -d '{link}'")
            ok |= r.returncode == 0 and "Error" not in r.stdout + r.stderr
        return ok
