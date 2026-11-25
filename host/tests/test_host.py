import os
import struct
import time

import pytest
from client import Phone

from syncpad import wire
from syncpad.config import Config
from syncpad.server import Server, safe_name
from syncpad.winput import DryInput

REJECTED = (wire.WireError, OSError)


@pytest.fixture
def srv(tmp_path):
    c = Config(tmp_path / "cfg")
    c.settings["save_dir"] = str(tmp_path / "in")
    s = Server(c, DryInput(), port=0, discovery_port=0, bind="127.0.0.1", clipboard=False).start()
    yield s
    s.stop()


def phone(srv, **k):
    return Phone(srv.config.pair_link(), srv.port, **k)


def moved(srv):
    m = [e for e in srv.inp.log if e[0] == "move"]
    return sum(e[1] for e in m), sum(e[2] for e in m)


def test_hkdf_rfc5869():
    okm = wire.hkdf(b"\x0b" * 22, bytes(range(13)), bytes(range(0xF0, 0xFA)), 42)
    assert okm.hex() == ("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                         "34007208d5b887185865")


def test_pair_then_reconnect_after_restart(srv):
    link = srv.config.pair_link()
    p = Phone(link, srv.port)
    p.sync()
    p.close()
    assert Config(srv.config.folder).devices[p.id]["key"] == p.key
    assert p.key.hex() not in srv.config.path.read_text()
    b = Phone(link, srv.port)
    b.sync()


def test_link_pairs_only_one_phone(srv):
    a = srv.config.pair_link()
    Phone(a, srv.port).sync()
    assert srv.config.pair_link() != a and len(srv.config.devices) == 1


def test_wrong_key_and_expired_rejected(srv):
    link = srv.config.pair_link()
    with pytest.raises(REJECTED):
        Phone(link, srv.port, key=b"x" * 32)
    srv.config.pair_until = 0
    with pytest.raises(REJECTED):
        Phone(link, srv.port)
    assert not srv.config.devices and not srv.inp.log


def test_forgotten_device_rejected(srv):
    link = srv.config.pair_link()
    p = Phone(link, srv.port)
    p.sync()
    srv.config.forget(p.id)
    with pytest.raises(REJECTED):
        Phone(link, srv.port)


def test_udp_loss_reorder_replay(srv):
    p = phone(srv)
    assert p.uping()[0] == wire.M_UPING
    pk = [p.udp.seal(p.state(dx=3, dy=-2)) for _ in range(10)]
    for i in (0, 1, 5, 3, 5, 9, 2):          # lost, reordered and replayed
        p.us.sendto(pk[i], p.uaddr)
    p.uping()
    p.sync()
    assert moved(srv) == (30, -20)


def test_fractional_motion_accumulates(srv):
    p = phone(srv)
    for _ in range(64):
        p.ch.send(p.state(dx=0.25))
    p.sync()
    assert moved(srv) == (16, 0)


def test_click_lands_after_lost_moves(srv):
    p = phone(srv)
    p.state(dx=40)                             # never sent
    late = p.udp.seal(p.state(dx=60))
    p.ch.send(p.state(kind=wire.M_BUTTON) + b"\0\1")
    p.ch.send(p.state(kind=wire.M_BUTTON) + b"\0\0")
    p.sync()
    p.us.sendto(late, p.uaddr)
    p.uping()
    p.sync()
    b = [e for e in srv.inp.log if e[0] == "button"]
    assert b == [("button", 0, True, (600, 500)), ("button", 0, False, (600, 500))]
    assert moved(srv) == (100, 0)


def test_smoothing_loses_nothing(srv):
    p = phone(srv)
    p.send(t="opt", smooth=0.8)
    for _ in range(20):
        p.ch.send(p.state(dx=7, dy=1))
    p.sync()
    time.sleep(0.6)
    assert moved(srv) == (140, 20) and len(srv.inp.log) > 1


def test_held_input_released_on_drop(srv):
    p = phone(srv)
    p.ch.send(p.state(kind=wire.M_BUTTON) + b"\0\1")
    p.send(t="key", k="shift", d=1)
    p.sync()
    p.close()
    time.sleep(0.3)
    assert ("key", "shift", False) in srv.inp.log
    assert any(e[:3] == ("button", 0, False) for e in srv.inp.log)
    assert not srv.sessions


def test_silent_phone_times_out(srv):
    p = phone(srv)
    p.send(t="key", k="w", d=1)
    p.sync()
    time.sleep(4.6)
    assert ("key", "w", False) in srv.inp.log


def test_second_connection_replaces_first(srv):
    link = srv.config.pair_link()
    a = Phone(link, srv.port)
    a.sync()
    b = Phone(link, srv.port)
    b.sync()
    time.sleep(0.2)
    assert len(srv.sessions) == 1


def test_keys_text_combo_and_garbage(srv):
    p = phone(srv)
    p.send(t="combo", keys=["ctrl", "c"])
    p.send(t="text", s="hé🙂")
    p.send(t="key", k="nope")
    for bad in (b"{", b"\x01", b"\xff\x00", b'{"t":"pad","b":"x"}', b'{"t":"abs"}', b"[]",
                b'{"t":"combo","keys":5}'):
        p.ch.send(bad)
    p.sync()
    assert srv.inp.log == [("key", "ctrl", True), ("key", "c", True), ("key", "c", False),
                           ("key", "ctrl", False), ("text", "hé🙂")]


def test_gamepad_keys(srv):
    p = phone(srv)
    p.send(t="pad", b=1, lx=1, ly=-1)
    p.send(t="pad", b=0)
    p.sync()
    down = {e[1] for e in srv.inp.log if e[2]}
    up = {e[1] for e in srv.inp.log if not e[2]}
    assert down == up == {"space", "d", "w"}


def test_files_both_ways(srv, tmp_path):
    link = srv.config.pair_link()
    ctl = Phone(link, srv.port)
    blob = os.urandom(900_000)
    for want in ("a.txt", "a (1).txt"):
        f = Phone(link, srv.port, kind=wire.KIND_FILE)
        f.send(t="put", name="..\\..\\a.txt", size=len(blob))
        assert f.recv()["t"] == "go"
        for i in range(0, len(blob), 60000):
            f.ch.send(b"\x20" + blob[i:i + 60000])
        assert f.recv()["name"] == want
        assert (tmp_path / "in" / want).read_bytes() == blob
    f = Phone(link, srv.port, kind=wire.KIND_FILE)      # interrupted upload leaves nothing
    f.send(t="put", name="cut", size=99)
    f.recv()
    f.close()
    time.sleep(0.3)
    assert len(list((tmp_path / "in").iterdir())) == 2
    src = tmp_path / "big.bin"
    src.write_bytes(blob)
    srv.offer(str(src))
    o = ctl.recv()
    assert o["t"] == "offer"
    f = Phone(link, srv.port, kind=wire.KIND_FILE)
    f.send(t="get", id=o["id"])
    f.recv()
    got = b""
    while (d := f.ch.recv())[0] == 0x20:
        got += d[1:]
    assert got == blob
    assert srv.transfers[0].state == "active"       # not done until the phone says so
    f.send(t="saved")
    time.sleep(0.3)
    assert srv.transfers[0].state == "done" and not srv.offers


def test_sent_file_is_not_offered_again(srv, tmp_path):
    link = srv.config.pair_link()
    a = Phone(link, srv.port)
    src = tmp_path / "x.txt"
    src.write_text("hi")
    srv.offer(str(src))
    oid = a.recv()["id"]
    f = Phone(link, srv.port, kind=wire.KIND_FILE)      # dropped half way: offered again
    f.send(t="get", id=oid)
    f.recv()
    f.close()
    time.sleep(0.3)
    a.close()
    a = Phone(link, srv.port)
    assert a.recv()["id"] == oid
    f = Phone(link, srv.port, kind=wire.KIND_FILE)
    f.send(t="get", id=oid)
    while f.ch.recv()[:1] != b"{" or srv.transfers[0].done < 2:
        pass
    f.send(t="saved")
    time.sleep(0.3)
    a.close()
    a = Phone(link, srv.port)
    a.send(t="ping", ts=1)
    assert a.recv()["t"] == "pong"                      # and never again once saved


def test_waiting_file_can_be_withdrawn(srv, tmp_path):
    src = tmp_path / "x.txt"
    src.write_text("hi")
    srv.offer(str(src))
    srv.withdraw(srv.transfers[0])
    assert not srv.offers and not srv.transfers


@pytest.mark.parametrize("n", ["../x", "C:\\y", "con.txt", "a\0b", "", "...", "x:stream"])
def test_safe_name(n):
    s = safe_name(n)
    assert s and not set(s) & set("/\\:\0") and s.split(".")[0].lower() != "con"


def test_screen_frame(srv):
    f = phone(srv, kind=wire.KIND_SCREEN)
    for _ in range(2):
        f.send(t="next", w=800, q=50)
        d = f.ch.recv()
    kind, cx, cy, w, h, n = struct.unpack(">BHHHHB", d[:10])
    assert kind == wire.M_FRAME and w == 800 and n >= 1 and d[10:12] == b"\xff\xd8"


def pointer(times):
    """A Pointer fed by a scripted clock, whose moves are recorded."""
    import threading
    from syncpad.winput import Pointer
    inp = DryInput()
    p = Pointer(inp, threading.RLock())
    it = iter(times)
    p.clock = lambda: next(it)
    return p, inp


def test_steady_link_is_never_delayed():
    for step in (0.0042, 0.0083, 0.016, 0.03):         # 240 Hz touch down to a slow link
        p, inp = pointer([1 + i * step for i in range(60)])
        for i in range(60):
            p.add(5 * 64, 0)
            assert inp.pos[0] == 500 + 5 * (i + 1)     # applied at once, every time
        assert p._thread is None


def test_finger_resting_then_moving_is_not_delayed():
    t, times = 1.0, []
    for _ in range(5):
        t += 0.3                                       # a pause long enough to look like a gap
        for _ in range(10):
            times.append(t)
            t += 0.008
    p, inp = pointer(times)
    for i in range(50):
        p.add(5 * 64, 0)
        assert inp.pos[0] == 500 + 5 * (i + 1)
    assert p._thread is None


def test_burst_after_a_gap_glides_and_loses_nothing():
    times = [1 + i * 0.008 for i in range(10)] + [1.08 + 0.2 + i * 0.0006 for i in range(25)]
    p, inp = pointer(times)
    for _ in range(35):
        p.add(8 * 64, 0)
    time.sleep(0.4)
    moves = [e[1] for e in inp.log]
    assert sum(moves) == 35 * 8
    assert max(moves) < 110                            # not one 200 px jump
    assert len(moves) > 16 and not p._gliding
    p.close()


def test_click_during_a_glide_lands_on_target():
    times = [1 + i * 0.008 for i in range(5)] + [1.04 + 0.2 + i * 0.0005 for i in range(25)]
    p, inp = pointer(times)
    for _ in range(30):
        p.add(8 * 64, 0)
    p.flush()
    assert inp.pos[0] == 500 + 240
    p.close()
