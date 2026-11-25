"""Builds the Windows download: host/.venv/Scripts/python host/build_release.py"""
import hashlib
import subprocess
import sys
import zipfile
from pathlib import Path

HOST = Path(__file__).resolve().parent
ROOT = HOST.parent
sys.path.insert(0, str(HOST))
from syncpad import __version__  # noqa: E402

OUT = ROOT / "dist" / "release"
WORK = HOST / "build"


def version_file() -> Path:
    n = tuple(int(x) for x in __version__.split(".")) + (0,)
    p = WORK / "version.txt"
    p.parent.mkdir(parents=True, exist_ok=True)
    s = {"CompanyName": "SyncPad", "FileDescription": "SyncPad", "FileVersion": __version__, "InternalName": "SyncPad",
         "LegalCopyright": "Copyright (c) 2026 DarkNull07. MIT License.", "OriginalFilename": "SyncPad.exe",
         "ProductName": "SyncPad", "ProductVersion": __version__}
    rows = ", ".join(f"StringStruct({k!r}, {v!r})" for k, v in s.items())
    p.write_text(f"VSVersionInfo(ffi=FixedFileInfo(filevers={n}, prodvers={n}, mask=0x3f, flags=0, OS=0x40004, fileType=1, subtype=0, date=(0, 0)), "
                 f"kids=[StringFileInfo([StringTable('040904B0', [{rows}])]), VarFileInfo([VarStruct('Translation', [1033, 1200])])])", "utf-8")
    return p


def build(onefile: bool):
    """The single file is the simple download. The folder starts faster and is what the zip holds."""
    subprocess.run([sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--windowed", "--noupx", "--onefile" if onefile else "--onedir",
                    "--name", "SyncPad", "--icon", str(HOST / "syncpad.ico"), "--version-file", str(version_file()),
                    "--hidden-import", "pystray._win32", "--hidden-import", "PIL._tkinter_finder", "--exclude-module", "numpy",
                    "--distpath", str(OUT), "--workpath", str(WORK), "--specpath", str(WORK), str(HOST / "syncpad_host.py")], check=True, cwd=HOST)


def main():
    from syncpad.ui import icon_image
    icon_image(256).save(HOST / "syncpad.ico", sizes=[(16, 16), (32, 32), (48, 48), (256, 256)])
    build(onefile=True)
    build(onefile=False)
    app = OUT / "SyncPad"
    for n in ("LICENSE", "THIRD_PARTY.md"):
        (app / n).write_bytes((ROOT / n).read_bytes())
    z = OUT / "SyncPad-windows.zip"
    with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as f:
        for p in sorted(app.rglob("*")):
            if p.is_file():
                f.write(p, p.relative_to(OUT))
    sums = []
    for p in (OUT / "SyncPad.exe", OUT / "SyncPad.apk", z):
        if p.exists():
            sums.append(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}")
    (OUT / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n", "utf-8", newline=chr(10))
    print("\n".join(sums))


if __name__ == "__main__":
    main()
