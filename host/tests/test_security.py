"""Things a stranger on the network, or a hostile folder, might try."""
import json
import os
import shutil
import socket
import time

import pytest

from syncpad import fsapi, server as server_mod, usb, wire
from syncpad.config import Config
from syncpad.server import Server
from syncpad.winput import DryInput


@pytest.fixture
def host(tmp_path):
    c = Config(tmp_path / "cfg")
    s = Server(c, DryInput(), port=0, discovery_port=0, bind="127.0.0.1", clipboard=False).start()
    yield s
    s.stop()


@pytest.mark.parametrize("p", [r"C:\con", r"C:\x\NUL", r"C:\aux.txt", r"C:\a\COM1", r"C:\a\lpt1.log", r"C:\a\b:stream", r"C:\a\CONIN$"])
def test_device_and_stream_paths_refused(p):
    with pytest.raises(fsapi.FsError):
        fsapi.check(p)


def test_ordinary_names_still_work():
    for p in (r"C:\Users", r"C:\console\auxiliary.txt", r"C:\a\com10.txt", r"D:\Project SyncPad\né.txt"):
        assert fsapi.check(p) == p


def test_planted_adb_is_not_run(tmp_path, monkeypatch):
    (tmp_path / "adb.exe").write_bytes(b"MZ")
    monkeypatch.chdir(tmp_path)
    found = usb.find_adb()
    assert found is None or os.path.dirname(os.path.abspath(found)) != str(tmp_path)


def test_system_tools_are_full_paths():
    from syncpad import system
    for n in ("explorer.exe", "rundll32.exe", "powershell.exe"):
        p = system.tool(n)
        assert os.path.isabs(p) and os.path.isfile(p), p


def test_discovery_is_small_and_anonymous(tmp_path):
    c = Config(tmp_path / "cfg")
    s = Server(c, DryInput(), port=0, discovery_port=47999, bind="127.0.0.1", clipboard=False).start()
    try:
        u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        u.settimeout(2)
        u.sendto(b"SPD?", ("127.0.0.1", 47999))
        d = u.recvfrom(1000)[0]
        assert socket.gethostname().encode() not in d and len(d) < 70
        assert json.loads(d[4:])["h"] == c.host_id.hex()
    finally:
        s.stop()


@pytest.mark.parametrize("ip,ok", [("192.168.1.5", True), ("10.0.0.9", True), ("172.20.1.1", True), ("127.0.0.1", True), ("169.254.3.3", True),
                                   ("100.80.1.2", True), ("8.8.8.8", False), ("203.0.113.9", False)])
def test_discovery_never_answers_the_internet(ip, ok):
    assert server_mod.local_peer(ip) is ok


def test_one_address_cannot_use_up_every_connection(host):
    held = [socket.create_connection(("127.0.0.1", host.port)) for _ in range(40)]
    time.sleep(0.4)
    assert host.pending("127.0.0.1") <= server_mod.MAX_PER_PEER < server_mod.MAX_PEERS
    for h in held:
        h.close()
    time.sleep(0.4)
    assert host.pending("127.0.0.1") == 0


def test_unknown_device_learns_nothing(host):
    for head in (b"SPD1" + bytes([1]) + b"\0" * 24, b"GET / HTTP/1.1\r\nHost: x\r\n\r\n   ", b"SPD1" + bytes([9]) + host.config.pair_id + b"\0" * 16):
        s = socket.create_connection(("127.0.0.1", host.port), 2)
        s.settimeout(2)
        s.sendall(head[:29].ljust(29, b"\0"))
        try:
            assert s.recv(100) == b""
        except ConnectionError:
            pass
        s.close()


def test_deeply_nested_json_does_not_kill_the_link(host):
    from client import Phone
    p = Phone(host.config.pair_link(), host.port)
    p.ch.send(b'{"t":' + b"[" * 30000 + b"]" * 30000 + b"}")
    p.sync()


def test_new_installs_do_not_share_the_clipboard(tmp_path):
    assert Config(tmp_path / "fresh").settings["clipsync"] is False


def test_existing_choice_is_kept(tmp_path):
    c = Config(tmp_path / "old")
    c.settings["clipsync"] = True
    c.save()
    assert Config(tmp_path / "old").settings["clipsync"] is True
