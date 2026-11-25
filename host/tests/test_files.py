import ctypes
import io
import json
import os
import threading
import time
import zipfile

import pytest
from client import Phone
from PIL import Image

from syncpad import fsapi, recorder, wire
from syncpad.server import PhoneError
from syncpad.winput import VK, _key, key_name
from test_host import phone, srv  # noqa: F401


class Fs:
    def __init__(self, srv, link):
        self.p = Phone(link, srv.port, kind=wire.KIND_FILE)

    def raw(self, op, **a):
        self.p.send(t="fs", op=op, **a)
        return self.p.ch.recv()

    def __call__(self, op, **a):
        return json.loads(self.raw(op, **a))


@pytest.fixture
def fs(srv):  # noqa: F811
    link = srv.config.pair_link()
    return Fs(srv, link)


@pytest.fixture
def box(tmp_path):
    d = tmp_path / "box"
    (d / "sub" / "deep").mkdir(parents=True)
    (d / "empty").mkdir()
    (d / "a.txt").write_text("héllo", "utf-8")
    (d / "sub" / "deep" / "report-final.pdf").write_bytes(b"%PDF" * 1000)
    Image.new("RGB", (3000, 2000), "red").save(d / "photo.jpg")
    Image.new("RGBA", (300, 300), (0, 255, 0, 90)).save(d / "clear.png")
    (d / "fake.jpg").write_bytes(b"not an image")
    (d / "bin.dat").write_bytes(b"\0\1\2" * 999)
    (d / "tool.exe").write_bytes(b"MZ")
    return d


def test_roots_and_list(fs, box):
    r = fs("roots")
    assert {"Desktop", "Downloads", "SyncPad"} <= {f["n"] for f in r["folders"]}
    assert any(d["p"] == "C:\\" and d["total"] > d["free"] > 0 for d in r["drives"])
    r = fs("list", path=str(box))
    by = {i["n"]: i for i in r["items"]}
    assert by["sub"]["d"] and not by["a.txt"]["d"] and by["a.txt"]["s"] == 6
    assert r["parent"] == str(box.parent) and fs("list", path="C:\\")["parent"] == ""
    ctypes.windll.kernel32.SetFileAttributesW(str(box / "a.txt"), 2)
    assert "a.txt" not in {i["n"] for i in fs("list", path=str(box))["items"]}
    assert "a.txt" in {i["n"] for i in fs("list", path=str(box), hidden=True)["items"]}


@pytest.mark.parametrize("bad", ["", "relative", "\\\\server\\share\\x", "\\\\?\\C:\\", "\\\\.\\PhysicalDrive0", "C:", "C:x", "//server/x", "C:\\a\0b", 5, None])
def test_bad_paths_refused_everywhere(fs, bad):
    for op in ("list", "get", "open", "reveal", "trash", "thumb", "text", "mkdir", "rename", "put"):
        assert fs(op, path=bad, name="x", size=1)["t"] == "err", op
    assert fs("roots")["t"] == "ok"          # and the channel survives all of it


def test_preview(fs, box):
    d = fs.raw("thumb", path=str(box / "photo.jpg"), w=160)
    assert d[0] == 0x21 and Image.open(io.BytesIO(d[1:])).size == (160, 107)
    assert fs.raw("thumb", path=str(box / "clear.png"), w=160)[0] == 0x21
    assert fs("thumb", path=str(box / "fake.jpg"))["t"] == "err"
    assert fs("thumb", path=str(box / "bin.dat"))["t"] == "err"
    assert fs("text", path=str(box / "a.txt"))["s"] == "héllo"
    assert fs("text", path=str(box / "bin.dat"))["t"] == "err"
    (box / "long.txt").write_text("é" * 60000, "utf-8")
    r = fs("text", path=str(box / "long.txt"))
    assert r["more"] and set(r["s"]) == {"é"}


def test_search(fs, box):
    r = fs("search", path=str(box), q="FINAL")
    assert [i["n"] for i in r["items"]] == ["report-final.pdf"] and r["items"][0]["in"].endswith("deep")


def test_make_rename_open(fs, box, srv):  # noqa: F811
    assert fs("mkdir", path=str(box), name="new")["t"] == "ok" and (box / "new").is_dir()
    assert fs("mkdir", path=str(box), name="new")["t"] == "err"
    for n in ("..", "a/b", "a\\b", "x:y", "", "dot."):
        assert fs("mkdir", path=str(box), name=n)["t"] == "err"
        assert fs("rename", path=str(box / "a.txt"), name=n)["t"] == "err"
    assert fs("rename", path=str(box / "a.txt"), name="bin.dat")["t"] == "err"
    assert fs("rename", path=str(box / "a.txt"), name="B.txt")["t"] == "ok" and (box / "B.txt").exists()
    assert fs("open", path=str(box / "B.txt"))["t"] == "ok"
    assert "note" in fs("open", path=str(box / "tool.exe"))
    assert [e[0] for e in srv.inp.log] == ["open", "reveal"]


def test_trash_is_recoverable_and_guarded(fs, box):
    for bad in ("C:\\", os.path.expanduser("~"), fsapi.known_folders()[0]["p"], os.environ["SystemRoot"]):
        assert fs("trash", path=bad)["t"] == "err"
        assert os.path.exists(bad)
    r = fs("trash", path=str(box / "bin.dat"))
    assert r["t"] == "ok" and not (box / "bin.dat").exists()
    assert fs("trash", path=str(box / "bin.dat"))["t"] == "err"


def collect(fs, path):
    fs.p.send(t="fs", op="get", path=path)
    go, body = fs.p.recv(), b""
    while (d := fs.p.ch.recv())[0] == 0x20:
        body += d[1:]
    return go, body, json.loads(d)


def test_download_file_and_folder(fs, box):
    go, body, done = collect(fs, str(box / "photo.jpg"))
    assert body == (box / "photo.jpg").read_bytes() and go["size"] == len(body) == done["size"]
    (box / "zero").write_bytes(b"")
    assert collect(fs, str(box / "zero"))[1] == b""
    held = os.open(str(box / "sub" / "x.txt"), os.O_CREAT | os.O_RDWR)
    os.close(held)
    go, body, done = collect(fs, str(box))
    assert go == {"t": "go", "name": "box.zip", "size": -1} and done["size"] == len(body)
    z = zipfile.ZipFile(io.BytesIO(body))
    assert z.testzip() is None
    assert {"box/a.txt", "box/sub/deep/report-final.pdf", "box/empty/"} <= set(z.namelist())
    assert fs("get", path="C:\\")["t"] == "err"
    assert fs("get", path=str(box / "gone"))["t"] == "err"


def test_folder_download_does_not_follow_junctions(fs, box, tmp_path):
    import _winapi
    secret = tmp_path / "secret"
    secret.mkdir()
    (secret / "s.txt").write_text("no")
    _winapi.CreateJunction(str(secret), str(box / "link"))
    names = zipfile.ZipFile(io.BytesIO(collect(fs, str(box))[1])).namelist()
    assert not any("s.txt" in n for n in names)


def test_upload_into_folder(fs, box):
    blob = os.urandom(300_000)
    assert fs("put", path=str(box / "sub"), name="..\\..\\up.bin", size=len(blob))["t"] == "go"
    for i in range(0, len(blob), 100_000):
        fs.p.ch.send(b"\x20" + blob[i:i + 100_000])
    assert fs.p.recv() == {"t": "done", "name": "up.bin"} and (box / "sub" / "up.bin").read_bytes() == blob
    assert fs("list", path=str(box))["t"] == "ok"        # same channel, still usable


# ---- shortcuts

def test_names_and_unknown_keys(srv):  # noqa: F811
    assert key_name("PrtScn") == key_name("print screen") == "printscreen" and key_name("Windows") == "win"
    p = phone(srv)
    p.send(t="combo", keys=["Win", "PrtScn"])
    p.sync()
    assert [e[1] for e in srv.inp.log if e[2]] == ["win", "printscreen"]
    srv.inp.log.clear()
    p.send(t="combo", keys=["win", "prtscrnn"])
    assert p.recv()["s"] == "There is no key called prtscrnn"
    assert not srv.inp.log                       # not a bare Win press


def test_print_screen_scan_code():
    k = _key(VK["printscreen"], True).ki
    assert (k.wVk, k.wScan, k.dwFlags & 0x8, k.dwFlags & 0x1) == (0x2C, 0x37, 0, 1)


def feed(*events):
    c = recorder.Combo()
    for vk, down in events:
        c.feed(vk, down)
    return c


def test_combo_logic():
    c = feed((0x5B, True), (0x2C, True), (0x2C, False), (0x5B, False))
    assert c.done and c.result() == ["win", "printscreen"]
    c = feed((0x5B, True), (0x2C, False), (0x5B, False))             # PrtScn often has no press event
    assert c.done and c.result() == ["win", "printscreen"]
    c = feed((0xA0, True), (0xA2, True), (0x1B, True), (0xA2, False), (0x1B, False), (0xA0, False))
    assert c.done and c.result() == ["ctrl", "shift", "esc"]
    assert feed((0xA2, True), (0x43, True)).done is False
    assert feed((0x5B, True), (0x5B, False)).result() == ["win"]
    assert feed((0x43, True), (0x43, True), (0x43, False)).result() == ["c"]


def test_recording_needs_a_visible_notice(srv):  # noqa: F811
    p = phone(srv)
    p.send(t="record")
    assert "err" in p.recv()
    shown = []
    srv.on_record = shown.append
    srv.recorder.record = lambda: ["win", "printscreen"]
    p.send(t="record")
    assert p.recv() == {"t": "recorded", "keys": ["win", "printscreen"]} and shown == [True, False]


def test_recorder_times_out_and_cancels():
    r = recorder.Recorder()
    t = time.monotonic()
    assert r.record(timeout=0.3) is None and time.monotonic() - t < 1.5
    threading.Timer(0.2, r.cancel).start()
    t = time.monotonic()
    assert r.record(timeout=5) is None and time.monotonic() - t < 2


# ---- the phone's files

class Answer(threading.Thread):
    """A phone that answers one ask."""

    def __init__(self, ctl, link, port, fn):
        super().__init__(daemon=True)
        self.ctl, self.link, self.port, self.fn = ctl, link, port, fn
        self.start()

    def run(self):
        while (m := self.ctl.recv())["t"] != "ask":
            pass
        ch = Phone(self.link, self.port, kind=wire.KIND_FILE)
        self.fn(ch, m)


def test_phone_files(srv, tmp_path):  # noqa: F811
    link = srv.config.pair_link()
    with pytest.raises(PhoneError, match="not connected"):
        srv.phone_list()
    ctl = Phone(link, srv.port)
    Answer(ctl, link, srv.port, lambda c, m: c.send(t="reply", rid=m["rid"], items=[{"n": "DCIM", "d": True}]))
    assert srv.phone_list("")["items"][0]["n"] == "DCIM"
    Answer(ctl, link, srv.port, lambda c, m: c.send(t="reply", rid=m["rid"], err="Turn it on first"))
    with pytest.raises(PhoneError, match="Turn it on"):
        srv.phone_list("")
    blob = os.urandom(500_000)

    def give(c, m, cut=False):
        c.send(t="reply", rid=m["rid"], size=len(blob))
        for i in range(0, len(blob) // (2 if cut else 1), 100_000):
            c.ch.send(b"\x20" + blob[i:i + 100_000])
        c.send(t="done")
        time.sleep(0.3)

    Answer(ctl, link, srv.port, give)
    f = srv.phone_get("/x/a.jpg", "../a.jpg", len(blob), tmp_path)
    assert f == tmp_path / "a.jpg" and f.read_bytes() == blob
    Answer(ctl, link, srv.port, lambda c, m: give(c, m, True))
    with pytest.raises(PhoneError):
        srv.phone_get("/x/a.jpg", "cut.jpg", len(blob), tmp_path)
    assert not list(tmp_path.glob(".*.part")) and not (tmp_path / "cut.jpg").exists()
    got = []

    def take(c, m):
        c.send(t="reply", rid=m["rid"])
        while (d := c.ch.recv())[0] == 0x20:
            got.append(d[1:])
        c.send(t="saved", name=m["name"])

    Answer(ctl, link, srv.port, take)
    srv.phone_put(f, "/x")
    assert b"".join(got) == blob


def test_another_phone_cannot_answer(srv):  # noqa: F811
    link = srv.config.pair_link()
    ctl = Phone(link, srv.port)
    other = srv.config.pair_link()
    Phone(other, srv.port, kind=wire.KIND_FILE).close()
    Answer(ctl, other, srv.port, lambda c, m: c.send(t="reply", rid=m["rid"], items=[]))
    with pytest.raises(PhoneError, match="did not answer"):
        srv.ask("list", timeout=1.0, path="")


def test_silent_phone(srv):  # noqa: F811
    phone_ = phone(srv)
    with pytest.raises(PhoneError, match="did not answer"):
        srv.ask("list", timeout=0.4, path="")
    phone_.sync()
