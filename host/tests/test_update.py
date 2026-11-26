from syncpad.update import VERSIONED, newer, parse


def test_versions():
    assert newer("v0.3.1", "0.3.0") and newer("0.10.0", "0.9.9")
    assert not newer("0.3.0", "0.3.0") and not newer("0.2.9", "0.3.0") and not newer("nightly", "0.3.0")
    assert parse("v1.2.3") == (1, 2, 3)


def test_versioned_name():
    assert VERSIONED.fullmatch("SyncPad v0.3.0.exe")
    assert not VERSIONED.fullmatch("SyncPad.exe")
