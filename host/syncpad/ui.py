"""The SyncPad window and tray icon on the laptop."""
import ctypes
import io
import os
import queue
import sys
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

BG, S1, S2, S3 = "#0D0E10", "#191B1E", "#22252A", "#30353B"
INK, DIM, ACCENT, ON_ACCENT, WARN = "#F1F3F2", "#8D949A", "#C8F250", "#12150A", "#FFB454"
FONT, SEMI = "Segoe UI", "Segoe UI Semibold"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
W, H, PAD = 400, 600, 20
TABS = ("Share", "Phone", "Pair", "Settings")


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
    """Accepts files dragged from Explorer onto a Tk window."""

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
                    sh.DragFinish(wp)
                    on_files(names)
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
    inner = W - 2 * PAD
    size_text = staticmethod(size_text)

    def __init__(self, config, server, usb, hidden=False):
        self.config, self.server, self.usb = config, server, usb
        self.q = queue.Queue()
        server.on_change = lambda: self.q.put("refresh")
        server.on_event = lambda s: self.q.put(("event", s))
        usb.on_change = lambda: self.q.put("refresh")
        server.on_record = lambda on: self.q.put(("record", on))
        server.on_alert = lambda s: self.q.put(("alert", s))
        self.root = r = tk.Tk()
        r.withdraw()
        self.k = r.winfo_fpixels("1i") / 96
        self._images = {}
        r.title("SyncPad")
        r.configure(bg=BG)
        for opt in ("padX", "padY", "highlightThickness", "borderWidth"):
            r.option_add(f"*Label.{opt}", 0)      # padding would spill over rounded corners
        r.resizable(False, False)
        r.geometry(f"{self.s(W)}x{self.s(H)}")
        self._ico = [ImageTk.PhotoImage(icon_image(n)) for n in (64, 32, 16)]
        r.iconphoto(True, *self._ico)
        r.protocol("WM_DELETE_WINDOW", self.hide)

        self._header()
        self.tab = None
        self.body = tk.Frame(r, bg=BG)
        self.body.place(x=self.s(PAD), y=self.s(164), width=self.s(W - 2 * PAD), height=self.s(H - 164 - 46))
        self.event = tk.Label(r, bg=BG, fg=DIM, font=(FONT, 9), anchor="w")
        self.event.place(x=self.s(PAD + 4), y=self.s(H - 36), width=self.s(W - 2 * PAD - 8))
        self._event_job = None
        self.phone = PhoneTab(self, self.body, dict(BG=BG, S2=S2, S3=S3, INK=INK, DIM=DIM, ACCENT=ACCENT))
        self.pages = {"Share": self._share_page(), "Phone": self.phone.frame, "Pair": self._pair_page(), "Settings": self._settings_page()}
        self.notice = None
        self._link = None
        self._seen = None
        self.pick("Share" if config.devices else "Pair")
        self.refresh()
        self._tray()
        r.update_idletasks()
        hwnd = int(r.frame(), 16)
        on = ctypes.c_int(1)
        ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(on), 4)      # dark title bar
        bg = ctypes.c_int(0x00100E0D)
        ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 35, ctypes.byref(bg), 4)      # in our colour (Windows 11)
        self.drop = FileDrop(hwnd, lambda names: self.q.put(("files", names)))
        if not hidden:
            r.deiconify()
        r.after(120, self._pump)

    # ---- drawing
    def s(self, n):
        return round(n * self.k)

    def shape(self, w, h, r, fill, under=BG, dash=None):
        """A rounded rectangle, drawn large and shrunk so its edge is smooth."""
        key = (w, h, r, fill, under, dash)
        if key not in self._images:
            m = 4
            pw, ph = self.s(w), self.s(h)
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

    # ---- header
    def _header(self):
        r = self.root
        self.status = self.text(r, size=18, semi=True)
        self.status.place(x=self.s(PAD + 2), y=self.s(16), width=self.s(W - 2 * PAD))
        self.dot = tk.Label(r, bg=BG, bd=0)
        self.dot.place(x=self.s(PAD + 4), y=self.s(58))
        self.detail = self.text(r, color=DIM)
        self.detail.place(x=self.s(PAD + 22), y=self.s(52), width=self.s(W - 2 * PAD - 22))
        bar = tk.Label(r, image=self.shape(W - 2 * PAD, 46, 14, S1), bg=BG, bd=0)
        bar.place(x=self.s(PAD), y=self.s(96))
        self.tabs = {}
        w = (W - 2 * PAD - 8) / len(TABS)
        for i, name in enumerate(TABS):
            b = tk.Label(r, text=name, compound="center", bg=S1, bd=0, font=(SEMI, 10), cursor="hand2")
            b.place(x=self.s(PAD + 4 + i * w), y=self.s(100))
            b.bind("<Button-1>", lambda e, n=name: self.pick(n))
            self.tabs[name] = b

    def pick(self, name):
        self.tab = name
        w = int((W - 2 * PAD - 8) / len(TABS))
        for n, b in self.tabs.items():
            b.config(image=self.shape(w, 38, 11, S3 if n == name else S1, S1), fg=INK if n == name else DIM)
        for n, p in self.pages.items():
            p.place_forget()
        self.pages[name].place(x=0, y=0, relwidth=1, relheight=1)
        if name == "Phone":
            self.phone.shown()

    # ---- share
    def _share_page(self):
        p = tk.Frame(self.body, bg=BG)
        w = W - 2 * PAD
        z = self.zone = tk.Label(p, image=self.shape(w, 128, 20, S1, dash=S3), compound="center", bg=BG, bd=0, fg=INK,
                                 font=(SEMI, 12), text="Drop files here\nor click to choose", cursor="hand2", justify="center")
        z.place(x=0, y=0)
        z.bind("<Enter>", lambda e: z.config(image=self.shape(w, 128, 20, S2, dash=ACCENT)))
        z.bind("<Leave>", lambda e: z.config(image=self.shape(w, 128, 20, S1, dash=S3)))
        z.bind("<ButtonRelease-1>", lambda e: self.send(filedialog.askopenfilenames(title="Send to phone")))
        self.list = tk.Frame(p, bg=BG)
        self.list.place(x=0, y=self.s(140), width=self.s(w), height=self.s(196))
        self.rows = []
        self.button(p, "Open received files", self.open_folder, w).place(x=0, y=self.s(342))
        return p

    def _transfers(self):
        """Redraws the list only when something in it changed."""
        items = list(self.server.transfers)[:4]
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
            row.place(x=0, y=self.s(i * 49), width=self.s(W - 2 * PAD), height=self.s(48))
            self.rows.append(row)

    def _row(self, t):
        w = W - 2 * PAD
        f = tk.Frame(self.list, bg=BG)
        name = t.name if len(t.name) <= 36 else t.name[:20] + "…" + t.name[-14:]
        self.text(f, name, semi=True).place(x=self.s(4), y=self.s(2), width=self.s(w - 70))
        note = self.text(f, size=9, color=WARN if t.state == "failed" else DIM)
        note.place(x=self.s(4), y=self.s(23), width=self.s(w - 70))
        bar = tk.Frame(f, bg=S3)
        fill = tk.Frame(bar, bg=ACCENT)
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
                fill.place(x=0, y=0, relwidth=min(t.done / t.size, 1), relheight=1)
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
        if files:
            self.pick("Share")

    # ---- pair
    def _pair_page(self):
        p = tk.Frame(self.body, bg=BG)
        w = W - 2 * PAD
        card = tk.Label(p, image=self.shape(w, 264, 20, "#FFFFFF"), bg=BG, bd=0)
        card.place(x=0, y=0)
        self.qr = tk.Label(p, bg="#FFFFFF", bd=0)
        self.qr.place(x=self.s(w / 2), y=self.s(132), anchor="center")
        self.text(p, "Scan this in SyncPad on your phone. The code pairs one phone and\nchanges afterwards.", size=9, color=DIM).place(x=self.s(4), y=self.s(274))
        half = (w - 10) // 2
        self.usb_btn = self.button(p, "Pair over USB", self.pair_usb, half)
        self.usb_btn.place(x=0, y=self.s(342))
        self.button(p, "Copy link", self.copy_link, half).place(x=self.s(half + 10), y=self.s(342))
        return p

    # ---- settings
    def _settings_page(self):
        p = tk.Frame(self.body, bg=BG)
        self._switch(p, 0, "Keep the USB link ready", lambda: self.config.settings["usb"], self._set_usb)
        self._switch(p, 48, "Send copied text to the phone", lambda: self.config.settings["clipsync"], self._set_clip)
        self._switch(p, 96, "Start with Windows", autostart_enabled, set_autostart)
        self.text(p, "Paired phones", size=9, color=DIM).place(x=self.s(4), y=self.s(158))
        self.devices = tk.Frame(p, bg=BG)
        self.devices.place(x=0, y=self.s(182), width=self.s(W - 2 * PAD), height=self.s(160))
        self.addr = self.text(p, size=9, color=DIM)
        self.addr.place(x=self.s(4), y=self.s(362))
        return p

    def _switch(self, p, y, label, get, put):
        w = W - 2 * PAD
        self.text(p, label, size=11).place(x=self.s(4), y=self.s(y + 8))
        sw = tk.Label(p, bg=BG, bd=0, cursor="hand2")
        sw.place(x=self.s(w - 46), y=self.s(y + 7))

        def paint():
            on = bool(get())
            key = ("switch", on)
            if key not in self._images:
                m, pw, ph = 4, self.s(46), self.s(26)
                im = Image.new("RGB", (pw * m, ph * m), BG)
                d = ImageDraw.Draw(im)
                d.rounded_rectangle((0, 0, pw * m - 1, ph * m - 1), ph * m // 2, fill=ACCENT if on else S3)
                r, c = ph * m * .36, (pw * m - ph * m / 2) if on else ph * m / 2
                d.ellipse((c - r, ph * m / 2 - r, c + r, ph * m / 2 + r), fill=ON_ACCENT if on else DIM)
                self._images[key] = ImageTk.PhotoImage(im.resize((pw, ph), Image.Resampling.LANCZOS))
            sw.config(image=self._images[key])

        sw.bind("<Button-1>", lambda e: (put(not get()), paint()))
        paint()

    def _set_usb(self, on):
        self.config.settings["usb"] = self.usb.enabled = on
        self.config.save()

    def _set_clip(self, on):
        self.config.settings["clipsync"] = on
        self.config.save()

    # ---- state
    def _pump(self):
        try:
            while True:
                m = self.q.get_nowait()
                if m == "refresh":
                    self.refresh()
                elif m == "show":
                    self.show()
                elif m == "quit":
                    self.quit()
                    return
                elif m[0] == "alert":
                    # Seen even when the window is closed: pairing hands over the laptop.
                    if self.tray:
                        try:
                            self.tray.notify(m[1], "SyncPad")
                        except Exception:
                            pass
                elif m[0] == "record":
                    self.recording(m[1])
                elif m[0] == "files":
                    (self.phone.send if self.tab == "Phone" else self.send)(m[1])
                else:
                    self.say(m[1])
        except queue.Empty:
            pass
        if self.config.pair_link(self.server.port) != self._link:
            self.refresh()
        self._transfers()
        self.phone.pump()
        self.root.after(120, self._pump)

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
        self.event.config(text=text if len(text) < 60 else text[:58] + "…")
        if self._event_job:
            self.root.after_cancel(self._event_job)
        self._event_job = self.root.after(6000, lambda: self.event.config(text=""))

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
        elif self.tab == "Phone":
            self.phone.shown()
        if live:
            self.status.config(text=live[0].model)
            seeing = ", viewing your screen" if self.server.viewers else ""
            self.detail.config(text=f"Connected over {live[0].via}{seeing}", fg=WARN if seeing else DIM)
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
        self.addr.config(text=(", ".join(ips[:2]) if ips else "No network") + f"   port {self.server.port}   SyncPad {__version__}")
        for c in self.devices.winfo_children():
            c.destroy()
        w = W - 2 * PAD
        for i, (did, d) in enumerate(list(self.config.devices.items())[:3]):
            f = tk.Label(self.devices, image=self.shape(w, 46, 14, S1), bg=BG, bd=0)
            f.place(x=0, y=self.s(i * 52))
            self.text(f, d["name"][:34], semi=True, bg=S1).place(x=self.s(16), y=self.s(12))
            b = self.text(f, "Forget", size=9, color=DIM, bg=S1, cursor="hand2")
            b.place(x=self.s(w - 60), y=self.s(14))
            b.bind("<Button-1>", lambda e, x=did: self.forget(x))
        if not self.config.devices:
            self.text(self.devices, "None yet").place(x=self.s(4), y=self.s(4))

    # ---- actions
    def forget(self, did):
        self.config.forget(did)
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

    def hide(self):
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
        self.server.stop()
        self.root.destroy()

    def run(self):
        self.root.mainloop()
