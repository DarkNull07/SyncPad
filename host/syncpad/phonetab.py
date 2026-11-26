"""The Phone tab: the phone's files, browsed from the laptop."""
import os
import queue
import shutil
import subprocess
import tempfile
import threading
import tkinter as tk
from datetime import datetime
from pathlib import Path

from .fsapi import RUNNABLE
from .server import PhoneError
from .system import tool

ICONS = {"dir": "", "image": "", "video": "", "audio": "", "doc": "", "zip": "", "app": ""}
KINDS = {**dict.fromkeys(("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp"), "image"),
         **dict.fromkeys(("mp4", "mkv", "mov", "webm", "avi", "3gp"), "video"),
         **dict.fromkeys(("mp3", "wav", "flac", "m4a", "ogg", "opus", "aac"), "audio"),
         **dict.fromkeys(("zip", "rar", "7z", "tar", "gz"), "zip"), "apk": "app"}

# Finds a phone Windows itself can see (USB, "File transfer" mode) and opens it.
EXPLORER = r"""
$d = (New-Object -ComObject Shell.Application).NameSpace(17).Items() | Where-Object { $_.Path -match '\\\\\?\\usb#' } | Select-Object -First 1
if ($d) { $d.InvokeVerb('open'); 'ok' } else { 'none' }
"""


class PhoneTab:
    ROW = 44

    def __init__(self, app, colors):
        self.app, self.c = app, colors
        self.path, self.parent, self.items, self.picked, self.loaded = "", None, [], set(), False
        self.jobs, self.results = queue.Queue(), queue.Queue()
        self.temp = None
        self.title_text, self.said = "Phone storage", ""
        threading.Thread(target=self._work, daemon=True).start()

    def build(self, parent, w, h):
        """Makes the widgets for this size. What is listed survives a rebuild."""
        app, colors, s = self.app, self.c, self.app.s
        self.w, self.h = w, h
        self.frame = p = tk.Frame(parent, bg=colors["BG"])
        self.back = tk.Label(p, text="", bg=colors["BG"], fg=colors["INK"] if self.parent is not None else colors["S3"],
                             font=("Segoe Fluent Icons", 11), cursor="hand2")
        self.back.place(x=s(2), y=s(4), width=s(28), height=s(24))
        self.back.bind("<Button-1>", lambda e: self.parent is not None and self.go(self.parent))
        self.title = app.text(p, self.title_text, 12, semi=True)
        self.title.place(x=s(36), y=s(4), width=s(w - 76))
        r = tk.Label(p, text="", bg=colors["BG"], fg=colors["DIM"], font=("Segoe Fluent Icons", 11), cursor="hand2")
        r.place(x=s(w - 30), y=s(4), width=s(28), height=s(24))
        r.bind("<Button-1>", lambda e: self.go(self.path))
        self.list_h = h - 36 - 56
        self.cv = tk.Canvas(p, bg=colors["BG"], highlightthickness=0, bd=0, yscrollincrement=s(self.ROW) // 2)
        self.cv.place(x=0, y=s(36), width=s(w), height=s(self.list_h))
        self.cv.bind("<MouseWheel>", lambda e: self.cv.yview_scroll(-int(e.delta / 60), "units"))
        self.cv.bind("<Button-1>", lambda e: self._click(e, bool(e.state & 0x4)))
        self.cv.bind("<Double-Button-1>", self._open_row)
        half = (w - 10) // 2
        self.save = app.button(p, "Save to laptop", self.save_picked, half, primary=True)
        self.save.place(x=0, y=s(h - 46))
        app.button(p, "Open in Explorer", self.explorer, half).place(x=s(half + 10), y=s(h - 46))
        self.message = app.text(p, "", color=colors["DIM"])
        self.save.enable(False)
        self._draw()
        self._say(self.said)
        return p

    # ---- background work: the phone can take seconds, the window must not
    def _work(self):
        while True:
            fn, done = self.jobs.get()
            try:
                self.results.put((done, fn(), None))
            except PhoneError as e:
                self.results.put((done, None, str(e)))
            except OSError as e:
                self.results.put((done, None, e.strerror or "That did not work"))

    def pump(self):
        try:
            while True:
                done, value, err = self.results.get_nowait()
                done(value, err)
        except queue.Empty:
            pass

    def shown(self):
        if not self.loaded:
            self.go("")

    def lost(self):
        """The phone went away: what is listed may be stale."""
        self.loaded = False

    # ---- browsing
    def go(self, path):
        self._say("Loading")
        self.jobs.put((lambda: self.app.server.phone_list(path), lambda v, e: self._listed(path, v, e)))

    def _listed(self, path, v, err):
        if err:
            self.items, self.picked = [], set()
            self._draw()
            self._say(err)
            return
        self.loaded, self.path, self.parent = True, path, v.get("parent")
        self.items = sorted((i for i in v.get("items", []) if isinstance(i, dict) and i.get("n")),
                            key=lambda i: (not i.get("d"), str(i["n"]).lower()))
        self.picked = set()
        name = str(v.get("name") or path.rstrip("/").rsplit("/", 1)[-1]) if path else "Phone storage"
        self.title_text = name[:60]
        self.title.config(text=self.title_text)
        self.back.config(fg=self.c["INK"] if self.parent is not None else self.c["S3"])
        self._draw()
        self._say("" if self.items else "This folder is empty. Drop files here to send them.")

    def _say(self, text):
        self.said = text
        if text:
            self.message.config(text=text, wraplength=self.app.s(self.w - 20))
            self.message.place(x=self.app.s(8), y=self.app.s(60), width=self.app.s(self.w - 16))
            self.message.lift()
        else:
            self.message.place_forget()

    def _draw(self):
        c, s, w = self.cv, self.app.s, self.w
        room = max(30, int((w - 60) / 7.2))
        c.delete("all")
        for n, i in enumerate(self.items):
            y = s(self.ROW) * n
            if n in self.picked:
                c.create_image(0, y + s(1), image=self.app.shape(w, self.ROW - 2, 12, self.c["S2"]), anchor="nw")
            kind = "dir" if i.get("d") else KINDS.get(str(i["n"]).rsplit(".", 1)[-1].lower(), "doc")
            c.create_text(s(22), y + s(22), text=ICONS[kind], font=("Segoe Fluent Icons", 13),
                          fill=self.c["ACCENT"] if kind == "dir" else self.c["INK"])
            name = str(i["n"])
            c.create_text(s(46), y + s(14), text=name if len(name) <= room else name[:room - 14] + "…" + name[-13:], anchor="w",
                          fill=self.c["INK"], font=("Segoe UI Semibold", 10))
            when = datetime.fromtimestamp(i["m"]).strftime("%d %b %Y") if i.get("m") else ""
            note = when if i.get("d") else f"{self.app.size_text(int(i.get('s', 0)))}   {when}"
            c.create_text(s(46), y + s(31), text=note, anchor="w", fill=self.c["DIM"], font=("Segoe UI", 9))
        c.config(scrollregion=(0, 0, s(w), max(s(self.ROW) * len(self.items), s(self.list_h))))
        files = [n for n in self.picked if not self.items[n].get("d")]
        self.save.enable(bool(files), f"Save {len(files)} files" if len(files) > 1 else "Save to laptop")

    def _row(self, e):
        n = int(self.cv.canvasy(e.y)) // self.app.s(self.ROW)
        return n if 0 <= n < len(self.items) else None

    def _click(self, e, add):
        n, top = self._row(e), self.cv.yview()[0]
        if n is None:
            self.picked = set()
        elif add:
            self.picked ^= {n}
        else:
            self.picked = {n}
        self._draw()
        self.cv.yview_moveto(top)

    def _full(self, i):
        return i.get("p") or (self.path.rstrip("/") + "/" + str(i["n"]))

    def _open_row(self, e):
        n = self._row(e)
        if n is None:
            return
        i = self.items[n]
        if i.get("d"):
            self.cv.yview_moveto(0)
            self.go(self._full(i))
            return
        if self.temp is None:
            self.temp = Path(tempfile.mkdtemp(prefix="SyncPad-"))
        self.app.say(f"Opening {i['n']}")
        self.jobs.put((lambda: self.app.server.phone_get(self._full(i), str(i["n"]), int(i.get("s", -1)), self.temp), self._opened))

    def _opened(self, path, err):
        if err:
            self.app.say(err)
        elif path.suffix.lower() in RUNNABLE:
            os.startfile(path.parent)          # programs from the phone are shown, never run
        else:
            try:
                os.startfile(path)
            except OSError:
                os.startfile(path.parent)

    def save_picked(self):
        files = [self.items[n] for n in sorted(self.picked) if not self.items[n].get("d")]
        for i in files:
            self.jobs.put((lambda i=i: self.app.server.phone_get(self._full(i), str(i["n"]), int(i.get("s", -1)), self.app.server.save_dir()),
                           lambda v, e, i=i: self.app.say(e or f"Saved {v.name} to Downloads\\SyncPad")))

    def send(self, paths):
        """Files dropped on this tab go into the folder being shown."""
        if not self.path:
            self.app.say("Open a phone folder first, then drop files into it")
            return
        here = self.path
        for p in paths:
            if os.path.isfile(p):
                self.jobs.put((lambda p=p: self.app.server.phone_put(Path(p), here),
                               lambda v, e, p=p: (self.app.say(e or f"Sent {os.path.basename(p)}"), here == self.path and self.go(here))))
            else:
                self.app.say("Folders cannot be sent yet. Zip them first.")

    def explorer(self):
        self.app.say("Looking for the phone in Windows")

        def find():
            r = subprocess.run([tool("powershell.exe"), "-NoProfile", "-NonInteractive", "-Command", EXPLORER], capture_output=True, text=True,
                               timeout=25, creationflags=0x08000000, stdin=subprocess.DEVNULL)
            return "ok" in r.stdout

        def done(ok, err):
            self.app.say("" if ok else "Plug the phone in and set its USB mode to File transfer")

        def safe():
            try:
                return find()
            except subprocess.SubprocessError:
                return False
        self.jobs.put((safe, done))

    def close(self):
        if self.temp:
            shutil.rmtree(self.temp, ignore_errors=True)
