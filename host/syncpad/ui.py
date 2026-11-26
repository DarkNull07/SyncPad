"""The SyncPad window and tray icon on the laptop.

Small, it shows one page at a time under tabs. Wide or maximised, everything
is on one screen: sharing, the phone's files, and pairing and settings.
"""
import ctypes
import io
import os
import queue
import sys
import time
import tkinter as tk
import winreg
from ctypes import wintypes
from pathlib import Path
from tkinter import filedialog

import segno
from PIL import Image, ImageDraw, ImageTk

from . import __version__
from .config import local_ips
from .fsapi import RUNNABLE
from .phonetab import PhoneTab
from .update import SITE, Updater, tidy

BG, S1, S2, S3 = "#0D0E10", "#191B1E", "#22252A", "#30353B"
INK, DIM, ACCENT, ON_ACCENT, WARN = "#F1F3F2", "#8D949A", "#C8F250", "#12150A", "#FFB454"
FONT, SEMI = "Segoe UI", "Segoe UI Semibold"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
W, H, PAD = 400, 660, 20          # the small window, in 96-dpi pixels
WIDE = 880                         # from this width, everything on one screen
TABS = ("Share", "Phone", "Pair", "Settings")
SIDE = ("Pair", "Settings")


def ease(t):
    """Fast start, gentle settle: feels immediate, never sluggish."""
    return 1 - (1 - t) ** 4


def icon_image(size=64):
    big = size * 4
    k = big / 64
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    s = lambda *v: [x * k for x in v]
    d.rounded_rectangle(s(4, 4, 60, 60), 14 * k, fill=S1)
    d.rounded_rectangle(s(17, 11, 47, 53), 8 * k, outline=ACCENT, width=int(4 * k))
    d.ellipse(s(27, 22, 37, 32), fill=ACCENT)
    return im.resize((size, size), Image.Resampling.LANCZOS)


def autostart_command():
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}" --hidden'
    pyw = Path(sys.executable).with_name("pythonw.exe")
    launcher = Path(__file__).resolve().parent.parent / "syncpad_host.py"
    return f'"{pyw}" "{launcher}" --hidden'


def autostart_enabled():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, "SyncPad")
            return True
    except OSError:
        return False


def set_autostart(on: bool):
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            winreg.SetValueEx(k, "SyncPad", 0, winreg.REG_SZ, autostart_command())
        else:
            try:
                winreg.DeleteValue(k, "SyncPad")
            except OSError:
                pass


def size_text(n):
    for unit, f in (("GB", 1e9), ("MB", 1e6), ("kB", 1e3)):
        if n >= f:
            return f"{n / f:.1f} {unit}" if unit != "kB" else f"{n / f:.0f} kB"
    return f"{max(n, 0)} B"


class FileDrop:
    """Accepts files dragged from Explorer onto a Tk window, and says where they landed."""

    WM_DROPFILES = 0x0233

    def __init__(self, hwnd, on_files):
        u, sh = ctypes.WinDLL("user32"), ctypes.WinDLL("shell32")
        LRESULT = ctypes.c_ssize_t
        PROC = ctypes.WINFUNCTYPE(LRESULT, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
        u.SetWindowLongPtrW.restype = ctypes.c_void_p
        u.SetWindowLongPtrW.argtypes = (wintypes.HWND, ctypes.c_int, ctypes.c_void_p)
        u.CallWindowProcW.restype = LRESULT
        u.CallWindowProcW.argtypes = (ctypes.c_void_p, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
        sh.DragQueryFileW.argtypes = (ctypes.c_void_p, wintypes.UINT, wintypes.LPWSTR, wintypes.UINT)
        sh.DragQueryPoint.argtypes = (ctypes.c_void_p, ctypes.POINTER(wintypes.POINT))
        sh.DragFinish.argtypes = (ctypes.c_void_p,)
        sh.DragAcceptFiles.argtypes = (wintypes.HWND, wintypes.BOOL)

        def proc(h, msg, wp, lp):
            if msg == self.WM_DROPFILES:
                # Runs inside Tk's message loop, so it only collects names and returns.
                try:
                    names = []
                    for i in range(sh.DragQueryFileW(wp, 0xFFFFFFFF, None, 0)):
                        n = sh.DragQueryFileW(wp, i, None, 0)
                        buf = ctypes.create_unicode_buffer(n + 1)
                        sh.DragQueryFileW(wp, i, buf, n + 1)
                        names.append(buf.value)
                    pt = wintypes.POINT()
                    sh.DragQueryPoint(wp, ctypes.byref(pt))
                    sh.DragFinish(wp)
                    on_files(names, pt.x)
                except Exception:
                    pass
                return 0
            return u.CallWindowProcW(self._old, h, msg, wp, lp)

        self._proc = PROC(proc)          # must outlive the window
        self._hwnd, self._set = hwnd, u.SetWindowLongPtrW
        self._old = u.SetWindowLongPtrW(hwnd, -4, ctypes.cast(self._proc, ctypes.c_void_p).value)
        sh.DragAcceptFiles(hwnd, True)

    def close(self):
        self._set(self._hwnd, -4, self._old)


class App:
    size_text = staticmethod(size_text)

    def __init__(self, config, server, usb, hidden=False):
        self.config, self.server, self.usb = config, server, usb
        self.q = queue.Queue()
        server.on_change = lambda: self.q.put("refresh")
        server.on_event = lambda s: self.q.put(("event", s))
        usb.on_change = lambda: self.q.put("refresh")
        server.on_record = lambda on: self.q.put(("record", on))
        server.on_alert = lambda s: self.q.put(("alert", s))
        tidy()
        if autostart_enabled():
            set_autostart(True)              # follows the app if it moved or was updated
        self.updater = Updater(__version__, lambda: self.q.put("refresh"), lambda: self.config.settings.get("updates", True)).start()
        self.root = r = tk.Tk()
        r.withdraw()
        self.k = r.winfo_fpixels("1i") / 96
        self._images = {}
        r.title("SyncPad")
        r.configure(bg=BG)
        for opt in ("padX", "padY", "highlightThickness", "borderWidth"):
            r.option_add(f"*Label.{opt}", 0)      # padding would spill over rounded corners
        r.minsize(self.s(W), self.s(H))
        size = str(config.settings.get("window", ""))
        w, h = (int(v) for v in size.split("x")) if "x" in size else (W, H)
        self.size = (max(W, w), max(H, h))
        r.geometry(f"{self.s(self.size[0])}x{self.s(self.size[1])}")
        self._ico = [ImageTk.PhotoImage(icon_image(n)) for n in (64, 32, 16)]
        r.iconphoto(True, *self._ico)
        r.protocol("WM_DELETE_WINDOW", self.hide)

        self.tab = "Share" if config.devices else "Pair"
        self.side = "Settings" if config.devices else "Pair"
        self.phone = PhoneTab(self, dict(BG=BG, S2=S2, S3=S3, INK=INK, DIM=DIM, ACCENT=ACCENT))
        self.notice = None
        self.tray = None
        self.frame = None
        self.built = None
        self._resize_job = None
        self.build(*self.size)
        self._tray()
        r.update_idletasks()
        hwnd = int(r.frame(), 16)
        on = ctypes.c_int(1)
        ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(on), 4)      # dark title bar
        bg = ctypes.c_int(0x00100E0D)
        ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 35, ctypes.byref(bg), 4)      # in our colour (Windows 11)
        self.drop = FileDrop(hwnd, lambda names, x: self.q.put(("files", names, x)))
        if config.settings.get("zoomed"):
            r.state("zoomed")
        if not hidden:
            r.deiconify()
        r.bind("<Configure>", self._resized)
        r.after(120, self._pump)

    # ---- drawing
    def s(self, n):
        return round(n * self.k)

    def shape(self, w, h, r, fill, under=BG, dash=None):
        """A rounded rectangle, drawn large and shrunk so its edge is smooth."""
        key = (round(w), round(h), r, fill, under, dash)
        if key not in self._images:
            m = 4
            pw, ph = max(1, self.s(w)), max(1, self.s(h))
            im = Image.new("RGB", (pw * m, ph * m), under)
            d = ImageDraw.Draw(im)
            rad = self.s(r) * m
            if dash:
                d.rounded_rectangle((m, m, pw * m - m, ph * m - m), rad, fill=fill, outline=dash, width=int(1.5 * self.k * m))
            else:
                d.rounded_rectangle((0, 0, pw * m - 1, ph * m - 1), rad, fill=fill)
            self._images[key] = ImageTk.PhotoImage(im.resize((pw, ph), Image.Resampling.LANCZOS))
        return self._images[key]

    def button(self, parent, text, cmd, w, h=44, primary=False, under=BG):
        idle, over = (ACCENT, "#D8FA6E") if primary else (S2, S3)
        b = tk.Label(parent, text=text, compound="center", bd=0, bg=under, cursor="hand2",
                     fg=ON_ACCENT if primary else INK, font=(SEMI, 10))
        b.enabled = True

        def paint(fill):
            b.config(image=self.shape(w, h, 14, fill, under))

        def enable(on, label=None):
            b.enabled = on
            b.config(fg=(ON_ACCENT if primary else INK) if on else DIM, cursor="hand2" if on else "arrow", text=label or text)
            paint(idle if on else S1)

        b.bind("<Enter>", lambda e: b.enabled and paint(over))
        b.bind("<Leave>", lambda e: b.enabled and paint(idle))
        b.bind("<Button-1>", lambda e: b.enabled and paint(S3 if not primary else "#AFD63E"))
        b.bind("<ButtonRelease-1>", lambda e: b.enabled and (paint(over), cmd()))
        b.enable = enable
        paint(idle)
        return b

    def text(self, parent, text="", size=10, color=INK, semi=False, **kw):
        return tk.Label(parent, text=text, bg=kw.pop("bg", BG), fg=color, font=(SEMI if semi else FONT, size),
                        justify="left", anchor="w", bd=0, padx=0, pady=0, **kw)

    def animate(self, step, ms=200):
        """Calls step(0..1) on each frame for ms milliseconds. Only while something moves."""
        start = time.perf_counter()

        def tick():
            t = min(1.0, (time.perf_counter() - start) * 1000 / ms)
            try:
                step(ease(t))
            except tk.TclError:
                return             # the window was rebuilt meanwhile
            if t < 1:
                self.root.after(8, tick)
        tick()

    def tabbar(self, parent, x, y, w, names, current, on_pick):
        """Segmented tabs whose highlight slides to the one picked."""
        c = tk.Canvas(parent, width=self.s(w), height=self.s(46), bg=BG, highlightthickness=0, bd=0, cursor="hand2")
        c.place(x=self.s(x), y=self.s(y))
        c.create_image(0, 0, image=self.shape(w, 46, 14, S1), anchor="nw")
        tw = (w - 8) / len(names)
        i = names.index(current) if current in names else 0
        ind = c.create_image(self.s(4 + i * tw), self.s(4), image=self.shape(int(tw), 38, 11, S3, S1), anchor="nw")
        texts = {n: c.create_text(self.s(4 + j * tw + tw / 2), self.s(23), text=n, font=(SEMI, 10), fill=INK if n == current else DIM)
                 for j, n in enumerate(names)}

        def click(e):
            j = min(len(names) - 1, max(0, int((e.x / self.k - 4) // tw)))
            on_pick(names[j])
        c.bind("<Button-1>", click)

        def show(name):
            j = names.index(name)
            x0, x1 = c.coords(ind)[0], self.s(4 + j * tw)
            for n, t in texts.items():
                c.itemconfig(t, fill=INK if n == name else DIM)
            self.animate(lambda p: c.coords(ind, x0 + (x1 - x0) * p, self.s(4)), 220)
        return show

    # ---- layout
    def _resized(self, e):
        if e.widget is not self.root:
            return
        w, h = round(e.width / self.k), round(e.height / self.k)
        if (w, h) == self.size:
            return
        self.size = (w, h)
        if self._resize_job:
            self.root.after_cancel(self._resize_job)
        self._resize_job = self.root.after(140, lambda: self.build(*self.size))

    def build(self, w, h):
        """Lays the whole window out for this size. Cheap enough to redo on resize."""
        if self.built == (w, h):
            return
        self.built = (w, h)
        old = self.frame
        self.frame = f = tk.Frame(self.root, bg=BG)
        self.wide = w >= WIDE
        self._link = None
        self._seen = None
        self._header(f, w)
        if self.wide:
            self._wide(f, w, h)
        else:
            self._compact(f, w, h)
        self.event = tk.Label(f, bg=BG, fg=DIM, font=(FONT, 9), anchor="w", cursor="arrow")
        self.event.place(x=self.s(PAD + 4), y=self.s(h - 34), width=self.s(w - 2 * PAD - 8))
        self.event.bind("<Button-1>", lambda e: self._event_click())
        self._event_job = None
        self._event_action = None
        f.place(x=0, y=0, relwidth=1, relheight=1)
        if old:
            old.destroy()
        self.refresh()

    def _header(self, f, w):
        self.status = self.text(f, size=18, semi=True)
        self.status.place(x=self.s(PAD + 2), y=self.s(16), width=self.s(w - 2 * PAD))
        self.dot = tk.Label(f, bg=BG, bd=0)
        self.dot.place(x=self.s(PAD + 4), y=self.s(58))
        self.detail = self.text(f, color=DIM)
        self.detail.place(x=self.s(PAD + 22), y=self.s(52), width=self.s(w - 2 * PAD - 22))

    def _compact(self, f, w, h):
        self.pick_tab = self.tabbar(f, PAD, 96, w - 2 * PAD, TABS, self.tab, self.pick)
        pw, ph = w - 2 * PAD, h - 164 - 46
        body = tk.Frame(f, bg=BG)
        body.place(x=self.s(PAD), y=self.s(164), width=self.s(pw), height=self.s(ph))
        self.pages = {"Share": self._share_page(body, pw, ph), "Phone": self.phone.build(body, pw, ph),
                      "Pair": self._pair_page(body, pw, ph), "Settings": self._settings_page(body, pw, ph)}
        self.columns = None
        self._show(self.tab)

    def _wide(self, f, w, h):
        gap, pad = 28, 28
        avail = w - 2 * pad - 2 * gap
        cols = [round(avail * .3), round(avail * .4)]
        cols.append(avail - sum(cols))
        top, ph = 112, h - 112 - 46
        xs = [pad, pad + cols[0] + gap, pad + cols[0] + cols[1] + 2 * gap]
        self.columns = xs
        frames = []
        for i, (x, cw) in enumerate(zip(xs, cols)):
            c = tk.Frame(f, bg=BG)
            c.place(x=self.s(x), y=self.s(top), width=self.s(cw), height=self.s(ph))
            frames.append(c)
        # The status line moves in line with the columns.
        self.status.place(x=self.s(pad + 2))
        self.dot.place(x=self.s(pad + 4))
        self.detail.place(x=self.s(pad + 22))
        self.text(frames[0], "Send to phone", size=9, color=DIM).place(x=self.s(4), y=0)
        share = self._share_page(frames[0], cols[0], ph - 28)
        share.place(x=0, y=self.s(28), relwidth=1, height=self.s(ph - 28))
        phone = self.phone.build(frames[1], cols[1], ph)
        phone.place(x=0, y=0, relwidth=1, relheight=1)
        self.pick_tab = self.tabbar(frames[2], 0, 0, cols[2], SIDE, self.side, self.pick)
        body = tk.Frame(frames[2], bg=BG)
        body.place(x=0, y=self.s(62), width=self.s(cols[2]), height=self.s(ph - 62))
        self.pages = {"Pair": self._pair_page(body, cols[2], ph - 62), "Settings": self._settings_page(body, cols[2], ph - 62)}
        self._show(self.side)
        self.phone.shown()

    def pick(self, name):
        if self.wide:
            self.side = name
        else:
            self.tab = name
        self.pick_tab(name)
        self._show(name)

    def _show(self, name):
        for n, p in self.pages.items():
            if n != name:
                p.place_forget()
        page = self.pages[name]
        page.place(x=0, y=0, relwidth=1, relheight=1)
        if name == "Phone":
            self.phone.shown()

    # ---- share
    def _share_page(self, parent, w, h):
        p = tk.Frame(parent, bg=BG)
        zh = 128 if h < 520 else 180
        z = self.zone = tk.Label(p, image=self.shape(w, zh, 20, S1, dash=S3), compound="center", bg=BG, bd=0, fg=INK,
                                 font=(SEMI, 12), text="Drop files here\nor click to choose", cursor="hand2", justify="center")
        z.place(x=0, y=0)
        z.bind("<Enter>", lambda e: z.config(image=self.shape(w, zh, 20, S2, dash=ACCENT)))
        z.bind("<Leave>", lambda e: z.config(image=self.shape(w, zh, 20, S1, dash=S3)))
        z.bind("<ButtonRelease-1>", lambda e: self.send(filedialog.askopenfilenames(title="Send to phone")))
        self.list = tk.Frame(p, bg=BG)
        lh = h - zh - 12 - 56
        self.list.place(x=0, y=self.s(zh + 12), width=self.s(w), height=self.s(lh))
        self.list_w, self.list_rows = w, max(1, lh // 49)
        self.rows = []
        self.button(p, "Open received files", self.open_folder, w).place(x=0, y=self.s(h - 46))
        return p

    def _transfers(self):
        """Redraws the list only when something in it changed."""
        items = list(self.server.transfers)[:self.list_rows]
        seen = [(id(t), t.state, t.name, t.done) for t in items]
        if seen == self._seen:
            return
        same = self._seen is not None and [x[:3] for x in seen] == [x[:3] for x in self._seen]
        self._seen = seen
        if same:
            for t, row in zip(items, self.rows):
                row.progress(t)
            return
        for c in self.list.winfo_children():
            c.destroy()
        self.rows = []
        if not items:
            self.text(self.list, "Files you send and receive show up here.", color=DIM).place(x=4, y=self.s(8))
        for i, t in enumerate(items):
            row = self._row(t)
            row.place(x=0, y=self.s(i * 49), width=self.s(self.list_w), height=self.s(48))
            self.rows.append(row)

    def _row(self, t):
        w = self.list_w
        f = tk.Frame(self.list, bg=BG)
        room = max(20, int((w - 90) / 7.4))
        name = t.name if len(t.name) <= room else t.name[:room - 14] + "…" + t.name[-13:]
        self.text(f, name, semi=True).place(x=self.s(4), y=self.s(2), width=self.s(w - 70))
        note = self.text(f, size=9, color=WARN if t.state == "failed" else DIM)
        note.place(x=self.s(4), y=self.s(23), width=self.s(w - 70))
        bar = tk.Frame(f, bg=S3)
        fill = tk.Frame(bar, bg=ACCENT)
        fill.shown = 0.0
        if t.state == "active":
            bar.place(x=self.s(4), y=self.s(43), width=self.s(w - 8), height=self.s(3))
        act = None
        if t.state == "waiting":
            act = ("Remove", lambda: self.server.withdraw(t))
        elif t.state == "done" and t.path:
            act = ("Open", lambda: self._open(t.path))
        if act:
            b = self.text(f, act[0], size=9, color=DIM, cursor="hand2")
            b.config(anchor="e")
            b.place(x=self.s(w - 66), y=self.s(10), width=self.s(62))
            b.bind("<Enter>", lambda e: b.config(fg=INK))
            b.bind("<Leave>", lambda e: b.config(fg=DIM))
            b.bind("<Button-1>", lambda e: act[1]())

        def progress(t):
            way = "to phone" if t.to_phone else "from phone"
            if t.state == "active" and t.size > 0:
                # The bar glides to each new value instead of jumping.
                a, b = fill.shown, min(t.done / t.size, 1)
                fill.shown = b
                self.animate(lambda p: fill.place(x=0, y=0, relwidth=a + (b - a) * p, relheight=1), 260)
                note.config(text=f"{size_text(t.done)} of {size_text(t.size)}, {way}")
            else:
                note.config(text={"waiting": "Waiting for the phone", "failed": "Failed", "done": f"{size_text(max(t.size, t.done))}, {way}"}
                            .get(t.state, way))
        f.progress = progress
        progress(t)
        return f

    def _open(self, path):
        # Programs sent from the phone are shown in their folder, never run from here.
        if Path(path).suffix.lower() in RUNNABLE:
            os.startfile(Path(path).parent)
        elif Path(path).exists():
            os.startfile(path)
        else:
            self.say("That file has been moved or deleted")

    def send(self, paths):
        files = [p for p in paths if os.path.isfile(p)]
        for p in files:
            try:
                self.server.offer(p)
            except OSError:
                self.say(f"Cannot read {os.path.basename(p)}")
        if len(files) < len(paths):
            self.say("Folders cannot be sent yet. Zip them first.")
        if files and not self.wide:
            self.pick("Share")

    # ---- pair
    def _pair_page(self, parent, w, h):
        p = tk.Frame(parent, bg=BG)
        cw = min(w, 340)
        card = tk.Label(p, image=self.shape(cw, 264, 20, "#FFFFFF"), bg=BG, bd=0)
        card.place(x=self.s((w - cw) / 2), y=0)
        self.qr = tk.Label(p, bg="#FFFFFF", bd=0)
        self.qr.place(x=self.s(w / 2), y=self.s(132), anchor="center")
        self.text(p, "Scan this in SyncPad on your phone. The code pairs one phone and changes afterwards.", size=9, color=DIM,
                  wraplength=self.s(w - 8)).place(x=self.s(4), y=self.s(274), width=self.s(w - 8))
        half = (w - 10) // 2
        self.usb_btn = self.button(p, "Pair over USB", self.pair_usb, half)
        y = min(342, h - 46)
        self.usb_btn.place(x=0, y=self.s(y))
        self.button(p, "Copy link", self.copy_link, half).place(x=self.s(half + 10), y=self.s(y))
        return p

    # ---- settings
    def _settings_page(self, parent, w, h):
        p = tk.Frame(parent, bg=BG)
        self._switch(p, w, 0, "Keep the USB link ready", lambda: self.config.settings["usb"], self._set_usb)
        self._switch(p, w, 46, "Send copied text to the phone", lambda: self.config.settings["clipsync"], self._set_clip)
        self._switch(p, w, 92, "Reachable from other networks", lambda: self.config.settings.get("remote", True), self._set_remote)
        self._switch(p, w, 138, "Check for updates", lambda: self.config.settings.get("updates", True), self._set_updates)
        self._switch(p, w, 184, "Start with Windows", autostart_enabled, set_autostart)
        # Internet speed: one line, with its result beside it.
        self.text(p, "Internet speed", size=11).place(x=self.s(4), y=self.s(238))
        self.speed_line = self.text(p, self._speed_text(), size=9, color=DIM, cursor="hand2")
        self.speed_line.config(anchor="e")
        self.speed_line.place(x=self.s(120), y=self.s(241), width=self.s(w - 124))
        self.speed_line.bind("<Button-1>", lambda e: self.speed_test())
        self.text(p, "Paired phones", size=9, color=DIM).place(x=self.s(4), y=self.s(280))
        self.devices = tk.Frame(p, bg=BG)
        self.devices.place(x=0, y=self.s(302), width=self.s(w), height=self.s(max(52, h - 302 - 30)))
        self.devices_w, self.devices_rows = w, max(1, (h - 302 - 30) // 52)
        self.addr = self.text(p, size=9, color=DIM)
        self.addr.place(x=self.s(4), y=self.s(h - 20), width=self.s(w - 8))
        return p

    def _switch(self, p, w, y, label, get, put):
        self.text(p, label, size=11).place(x=self.s(4), y=self.s(y + 8))
        sw = tk.Label(p, bg=BG, bd=0, cursor="hand2")
        sw.place(x=self.s(w - 46), y=self.s(y + 7))

        def frame(pos):
            """The switch with its knob a fraction pos of the way to on."""
            key = ("switch", round(pos * 8))
            if key not in self._images:
                m, pw, ph = 4, self.s(46), self.s(26)
                im = Image.new("RGB", (pw * m, ph * m), BG)
                d = ImageDraw.Draw(im)
                q = round(pos * 8) / 8
                mix = lambda a, b: "#%02x%02x%02x" % tuple(round(int(a[i:i + 2], 16) + (int(b[i:i + 2], 16) - int(a[i:i + 2], 16)) * q) for i in (1, 3, 5))
                d.rounded_rectangle((0, 0, pw * m - 1, ph * m - 1), ph * m // 2, fill=mix(S3, ACCENT))
                r = ph * m * .36
                c = ph * m / 2 + (pw * m - ph * m) * q
                d.ellipse((c - r, ph * m / 2 - r, c + r, ph * m / 2 + r), fill=mix(DIM, ON_ACCENT))
                self._images[key] = ImageTk.PhotoImage(im.resize((pw, ph), Image.Resampling.LANCZOS))
            sw.config(image=self._images[key])

        def flip(e):
            put(not get())
            on = bool(get())
            self.animate(lambda t: frame(t if on else 1 - t), 180)
        sw.bind("<Button-1>", flip)
        frame(1 if get() else 0)

    def _speed_text(self):
        st = getattr(self, "speed_state", {})
        ph = st.get("phase", "")
        if ph == "ping":
            return "Measuring ping…"
        if ph in ("download", "upload"):
            return f"{ph.capitalize()} {st.get('live', 0):.0f} Mbit/s…"
        if ph == "failed":
            return st.get("error", "Failed") + "  Retry"
        if ph == "done":
            return f"↓ {st.get('down', 0):.0f}  ↑ {st.get('up', 0):.0f} Mbit/s   {st.get('ping', 0)} ms   Test again"
        return "Test"

    def speed_test(self):
        """Measured against Cloudflare's nearest server, like the phone's test."""
        if getattr(self, "speed_state", {}).get("phase") in ("ping", "download", "upload"):
            return
        import threading
        from .speed import SpeedTest
        self.speed_state = {"phase": "ping"}
        t = SpeedTest(lambda st: (setattr(self, "speed_state", dict(st)), self.q.put("speed")))
        threading.Thread(target=t.run, daemon=True, name="syncpad-speed").start()
        self.speed_line.config(text=self._speed_text())

    def _set_usb(self, on):
        self.config.settings["usb"] = self.usb.enabled = on
        self.config.save()

    def _set_clip(self, on):
        self.config.settings["clipsync"] = on
        self.config.save()

    def _set_remote(self, on):
        self.config.settings["remote"] = on
        self.config.save()
        if not on and self.server.remote:
            for link in list(self.server.remote.links.values()):
                link.close()
        self.refresh()

    def _set_updates(self, on):
        self.config.settings["updates"] = on
        self.config.save()

    # ---- state
    def _pump(self):
        try:
            while True:
                m = self.q.get_nowait()
                if m == "refresh":
                    self.refresh()
                elif m == "speed":
                    try:
                        self.speed_line.config(text=self._speed_text())
                    except tk.TclError:
                        pass
                elif m == "show":
                    self.show()
                elif m == "quit":
                    self.quit()
                    return
                elif m[0] == "alert":
                    # Seen even when the window is closed: pairing hands over the laptop.
                    self.notify(m[1])
                elif m[0] == "record":
                    self.recording(m[1])
                elif m[0] == "files":
                    self._dropped(m[1], m[2])
                else:
                    self.say(m[1])
        except queue.Empty:
            pass
        if self.config.pair_link(self.server.port) != self._link:
            self.refresh()
        self._transfers()
        self.phone.pump()
        self.root.after(120, self._pump)

    def _dropped(self, names, x):
        """Wide, files go where they were dropped: on the phone's folder, or to be sent."""
        if self.wide and self.columns:
            on_phone = self.s(self.columns[1]) <= x < self.s(self.columns[2])
        else:
            on_phone = self.tab == "Phone"
        (self.phone.send if on_phone else self.send)(names)

    def notify(self, text):
        if self.tray:
            try:
                self.tray.notify(text, "SyncPad")
            except Exception:
                pass

    def recording(self, on):
        """Always on screen while keys are being read, above every other window."""
        if self.notice:
            self.notice.destroy()
            self.notice = None
        if not on:
            return
        n = self.notice = tk.Toplevel(self.root)
        n.overrideredirect(True)
        n.attributes("-topmost", True)
        n.configure(bg=S1)
        w, h = self.s(400), self.s(104)
        n.geometry(f"{w}x{h}+{(n.winfo_screenwidth() - w) // 2}+{self.s(60)}")
        tk.Frame(n, bg=ACCENT).place(x=0, y=0, width=self.s(5), relheight=1)
        self.text(n, "Press the shortcut now", 16, semi=True, bg=S1).place(x=self.s(28), y=self.s(20))
        self.text(n, "SyncPad on your phone is recording one key combination.", color=DIM, bg=S1).place(x=self.s(28), y=self.s(60))
        try:
            ctypes.windll.dwmapi.DwmSetWindowAttribute(int(n.frame(), 16), 33, ctypes.byref(ctypes.c_int(2)), 4)     # rounded
        except (OSError, ValueError):
            pass

    def say(self, text):
        self.event.config(text=text if len(text) < 90 else text[:88] + "…", fg=DIM, cursor="arrow")
        self._event_action = None
        if self._event_job:
            self.root.after_cancel(self._event_job)
        self._event_job = self.root.after(6000, self._update_line)

    def _event_click(self):
        if self._event_action:
            self._event_action()

    def _update_line(self):
        """The bottom line offers an update when one is ready."""
        self._event_job = None
        u = self.updater
        if u.state == "ready":
            self.event.config(text=f"SyncPad {u.latest} is ready. Click to restart and update.", fg=ACCENT, cursor="hand2")
            self._event_action = self.update_now
        elif u.state == "available":
            self.event.config(text=f"SyncPad {u.latest} is out. Click to get it.", fg=ACCENT, cursor="hand2")
            self._event_action = lambda: os.startfile(SITE + "/#download")
        else:
            self.event.config(text="", cursor="arrow")
            self._event_action = None

    def update_now(self):
        try:
            if self.updater.apply():
                self.quit()
        except OSError:
            self.say("The update could not be installed. Get it from the website instead.")

    def refresh(self):
        link = self.config.pair_link(self.server.port)
        if link != self._link:
            self._link = link
            qr = segno.make(link, error="l", micro=False)
            b = io.BytesIO()
            modules = qr.symbol_size(scale=1, border=0)[0]
            qr.save(b, kind="png", scale=max(2, self.s(240) // modules), border=0)
            self._qr = tk.PhotoImage(data=b.getvalue())
            self.qr.config(image=self._qr)
        live = self.server.all_sessions()
        if not live:
            self.phone.lost()
        elif self.wide or self.tab == "Phone":
            self.phone.shown()
        if live:
            s = live[0]
            self.status.config(text=s.model)
            seeing = ", viewing your screen" if self.server.viewers else ""
            via = s.via
            if via == "Internet" and self.server.remote:
                route = dict((p, (kind, ms)) for p, kind, ms in self.server.remote.paths()).get(self.config.devices.get(s.device_id, {}).get("remote", ""))
                if route:
                    via += ", direct" if route[0] == "direct" else ", through a relay"
            self.detail.config(text=f"Connected over {via}{seeing}", fg=WARN if seeing else DIM)
        else:
            self.status.config(text="SyncPad")
            self.detail.config(text="Waiting for your phone" if self.config.devices else "No phone paired yet", fg=DIM)
        self.dot.config(image=self.shape(9, 9, 4.5, ACCENT if live else S3))
        if self.usb.adb is None:
            self.usb_btn.enable(False, "USB needs adb")
        elif self.usb.waiting:
            self.usb_btn.enable(False, "Allow on phone")
        else:
            self.usb_btn.enable(bool(self.usb.ready))
        ips = local_ips()
        r = self.server.remote
        away = ("   away: on" if r and r.online and self.config.settings.get("remote", True) else "   away: starting" if r and r.id and self.config.settings.get("remote", True) else "")
        self.addr.config(text=(", ".join(ips[:2]) if ips else "No network") + f"   port {self.server.port}{away}   SyncPad {__version__}")
        for c in self.devices.winfo_children():
            c.destroy()
        w = self.devices_w
        for i, (did, d) in enumerate(list(self.config.devices.items())[:self.devices_rows]):
            f = tk.Label(self.devices, image=self.shape(w, 46, 14, S1), bg=BG, bd=0)
            f.place(x=0, y=self.s(i * 52))
            self.text(f, d["name"][:34], semi=True, bg=S1).place(x=self.s(16), y=self.s(12))
            b = self.text(f, "Forget", size=9, color=DIM, bg=S1, cursor="hand2")
            b.place(x=self.s(w - 60), y=self.s(14))
            b.bind("<Button-1>", lambda e, x=did: self.forget(x))
        if not self.config.devices:
            self.text(self.devices, "None yet").place(x=self.s(4), y=self.s(4))
        if self._event_job is None:
            was = self._event_action
            self._update_line()
            if self.updater.state == "ready" and was is None:
                self.notify(f"SyncPad {self.updater.latest} is ready. Open SyncPad to update.")

    # ---- actions
    def forget(self, did):
        peer = self.config.devices.get(did, {}).get("remote", "")
        self.config.forget(did)
        if peer and self.server.remote:
            self.server.remote.drop(peer)
        for s in self.server.all_sessions():
            if s.device_id == did:
                s.close()
        self.refresh()

    def pair_usb(self):
        ok = self.usb.pair(self.config.pair_link(self.server.port))
        self.say("Tap Pair on the phone" if ok else "Install the SyncPad app on the phone first")

    def copy_link(self):
        # Our own link must not be synced to the phone as copied text.
        self.root.clipboard_clear()
        self.root.clipboard_append(self._link)
        if self.server.clip:
            from . import winclip
            self.root.update()
            self.server.clip.seq = winclip.sequence()
        self.say("Link copied. Treat it like a password.")

    def open_folder(self):
        os.startfile(self.server.save_dir())

    # ---- tray
    def _tray(self):
        self.tray = None
        try:
            import pystray
            self.tray = pystray.Icon("SyncPad", icon_image(), "SyncPad", pystray.Menu(
                pystray.MenuItem("Open SyncPad", lambda: self.q.put("show"), default=True),
                pystray.MenuItem("Quit", lambda: self.q.put("quit"))))
            self.tray.run_detached()
        except Exception:
            self.tray = None

    def _remember(self):
        zoomed = self.root.state() == "zoomed"
        self.config.settings["zoomed"] = zoomed
        if not zoomed and self.root.state() == "normal":
            self.config.settings["window"] = f"{self.size[0]}x{self.size[1]}"
        self.config.save()

    def hide(self):
        self._remember()
        if self.tray is None:
            self.quit()
        else:
            self.root.withdraw()

    def show(self):
        self.root.deiconify()
        self.root.lift()

    def quit(self):
        if self.tray:
            self.tray.stop()
        self.drop.close()
        self.phone.close()
        if self.server.remote:
            self.server.remote.stop()
        self.server.stop()
        self.root.destroy()

    def run(self):
        self.root.mainloop()
