"""Makes a release. One version number, changed everywhere at once.

    host/.venv/Scripts/python tools/release.py 0.3.0 "What changed" --ci
    host/.venv/Scripts/python tools/release.py 0.3.0 "What changed" "And this"
    host/.venv/Scripts/python tools/release.py 0.3.0 --publish

With --ci (the usual way): bumps the version in both apps and the changelog,
runs the laptop tests, commits, tags v0.3.0 and pushes. GitHub Actions then
builds, signs, checks and publishes both downloads and updates the website, so
nothing is built on this computer. See .github/workflows/release.yml.

Without it, everything happens here instead: the same bump, then every test,
both downloads built, signed and checked, the website updated, and a commit.
That publishes nothing; --publish then pushes, releases and checks the live site.

--build is what the workflow runs: it builds and checks the version already in
the source, without committing. --site then writes those downloads' sizes and
checksums, and the changelog, into the website.
"""
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOST, ANDROID, WEB, OUT = ROOT / "host", ROOT / "android", ROOT / "web", ROOT / "dist" / "release"
LOCAL = ROOT / "Syncpad executables"
REPO, SITE = "DarkNull07/SyncPad", "https://syncpad-dark.vercel.app"
ASSETS = ["SyncPad.exe", "SyncPad.apk", "SyncPad-windows.zip", "SHA256SUMS.txt"]
SMARTSCREEN = ("The Windows app is not signed with a paid certificate, so SmartScreen may warn. Check the file against SHA256SUMS.txt, "
               "then choose More info, Run anyway.\n")
# Anything here must never be published. Checked in the source and inside the downloads.
PRIVATE = re.compile(rb"(?i)@gmail\.|@outlook\.|[A-Z]:\\Users\\(?!Public)[A-Za-z]|gh[pousr]_[A-Za-z0-9]{20}|BEGIN [A-Z ]*PRIVATE KEY")
NEVER = re.compile(r"(^|/)(Ideas\.txt|\.env.*|.*\.(jks|keystore)|signing\.properties)$|^(\.claude|\.vercel|dist|Syncpad executables)/")


def die(msg):
    sys.exit(f"STOPPED: {msg}")


def run(*cmd, cwd=ROOT, quiet=True, **kw):
    r = subprocess.run([str(c) for c in cmd], cwd=cwd, capture_output=quiet, text=True, encoding="utf-8", errors="replace", **kw)
    if r.returncode:
        die(f"{' '.join(map(str, cmd))[:90]}\n{(r.stdout or '')[-1800:]}{(r.stderr or '')[-800:]}")
    return r.stdout or ""


def sub(path: Path, pattern, new, count=1):
    s = path.read_text("utf-8")
    t, n = re.subn(pattern, lambda m: new, s)
    if n != count:
        die(f"{path.name}: expected {count} match of {pattern}, found {n}")
    path.write_text(t, "utf-8", newline="\n")


def current():
    return re.search(r'"([\d.]+)"', (HOST / "syncpad/__init__.py").read_text("utf-8")).group(1)


def extra_private():
    """Words only this computer knows, such as the account name. Kept out of the repository."""
    f = Path.home() / ".syncpad" / "private-words.txt"
    words = [w.strip() for w in f.read_text("utf-8").splitlines() if w.strip()] if f.exists() else []
    return re.compile("|".join(map(re.escape, words)).encode(), re.I) if words else None


def check_tree():
    files = run("git", "ls-files").splitlines()
    for f in files:
        if NEVER.search(f):
            die(f"{f} must not be in the repository")
    more = extra_private()
    for f in files:
        p = ROOT / f
        if p.suffix in (".webp", ".png", ".woff2", ".ico", ".jar") or not p.is_file() or f == "tools/release.py":
            continue
        b = p.read_bytes()
        for pat in (PRIVATE, more):
            m = pat and pat.search(b)
            if m:
                die(f"private text in {f}: {m.group()[:40]!r}")
    for mail in set(run("git", "log", "--format=%ae", "HEAD").split()):
        if not mail.endswith("@users.noreply.github.com"):
            die(f"a commit on main uses a real address: {mail[:2]}***")


def check_downloads():
    import marshal
    import zipfile
    from PyInstaller.archive.readers import CArchiveReader
    more = extra_private()

    def look(name, b, ours=False):
        # Libraries name their own authors and carry words like "PRIVATE KEY", so the general patterns are for our code only.
        for pat in ((PRIVATE, more) if ours else (more,)):
            m = pat and pat.search(b)
            if m:
                die(f"private text inside {name}: {m.group()[:40]!r}")

    with zipfile.ZipFile(OUT / "SyncPad.apk") as z:
        for n in z.namelist():
            look(f"SyncPad.apk/{n}", z.read(n), n.endswith(".dex") or n.startswith("res/"))
    a = CArchiveReader(str(OUT / "SyncPad.exe"))
    for n in a.toc:
        d = a.extract(n)
        d = d[1] if isinstance(d, tuple) else d
        if isinstance(d, (bytes, bytearray)):
            look(f"SyncPad.exe/{n}", d)
    pyz = a.open_embedded_archive("PYZ.pyz")
    for n in pyz.toc:
        c = pyz.extract(n)
        if c is not None and n.split(".")[0] in ("syncpad", "syncpad_host"):
            look(f"SyncPad.exe/{n}", marshal.dumps(c), True)


def sdk_tool(name):
    sdk = re.search(r"sdk\.dir=(.*)", (ANDROID / "local.properties").read_text("utf-8")).group(1).replace("\\:", ":")
    return sorted((Path(sdk) / "build-tools").iterdir())[-1] / name


def size(n):
    return f"{(OUT / n).stat().st_size / 1e6:.1f} MB"


def sha(n):
    return hashlib.sha256((OUT / n).read_bytes()).hexdigest()


def update_site(version, date):
    p = WEB / "index.html"
    s = p.read_text("utf-8")
    values = {"version": version, "short": ".".join(version.split(".")[:2]), "date": date,
              "exe-size": size("SyncPad.exe"), "apk-size": size("SyncPad.apk"),
              "exe-sha": f"{sha('SyncPad.exe')[:12]}…{sha('SyncPad.exe')[-7:]}", "apk-sha": f"{sha('SyncPad.apk')[:12]}…{sha('SyncPad.apk')[-7:]}"}
    for k, v in values.items():
        s, n = re.subn(rf'(data-rel="{k}"[^>]*>)[^<]*', lambda m: m.group(1) + v, s)
        if not n:
            die(f"the site has no place for {k}")
    for k in ("exe", "apk"):
        s, n = re.subn(rf'data-full="{k}" data-sum="[0-9a-f]*"', f'data-full="{k}" data-sum="{sha("SyncPad." + k)}"', s)
        if n != 1:
            die(f"the site has no checksum for {k}")
    # The newest few entries of the changelog, so the site never needs a second list.
    blocks = re.findall(r"^## ([\d.]+), (.*?)\n\n(.*?)(?=^## |\Z)", (ROOT / "CHANGELOG.md").read_text("utf-8"), re.S | re.M)[:3]
    esc = lambda t: t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    html = "".join(f"<li><h3>{v} <span>{esc(d)}</span></h3><ul>" + "".join(f"<li>{esc(x[2:])}</li>" for x in body.strip().splitlines() if x.startswith("- ")) + "</ul></li>"
                   for v, d, body in blocks)
    s, n = re.subn(r"(<!--log-->).*?(<!--/log-->)", lambda m: m.group(1) + html + m.group(2), s, flags=re.S)
    if n != 1:
        die("the site has no place for the changelog")
    p.write_text(s, "utf-8", newline="\n")


def prepare(version, notes):
    # If anything stops it, the files it edits go back to how they were.
    touched = [HOST / "syncpad/__init__.py", ANDROID / "app/build.gradle.kts", ROOT / "CHANGELOG.md", WEB / "index.html"]
    before = {p: p.read_bytes() for p in touched}
    try:
        build(version, notes)
    except BaseException:
        for p, b in before.items():
            p.write_bytes(b)
        raise


def build(version, notes):
    date = bump(version, notes)
    make(version, date)
    shutil.rmtree(HOST / "build", ignore_errors=True)
    run("git", "add", "-A")
    check_tree()
    run("git", "commit", "-m", f"SyncPad {version}", "-m", "\n".join(f"- {n}" for n in notes), "-m", "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>")
    keep_local(version)
    print(f"ready: {version}. Nothing is published. Next: tools/release.py {version} --publish")


def keep_local(version):
    """This laptop runs SyncPad from its own folder, one file per version. Older ones are tidied by the app."""
    LOCAL.mkdir(exist_ok=True)
    shutil.copy2(OUT / "SyncPad.exe", LOCAL / f"SyncPad v{version}.exe")
    print(f"  this laptop: {LOCAL.name}/SyncPad v{version}.exe")


def bump(version, notes):
    """Checks, tests the laptop app, then sets the version everywhere. Returns the date."""
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        die("the version looks like 1.2.3")
    if tuple(map(int, version.split("."))) <= tuple(map(int, current().split("."))):
        die(f"{version} is not newer than {current()}")
    if run("git", "branch", "--show-current").strip() != "main":
        die("releases are made on main")
    if not notes:
        die("say what changed")
    check_tree()
    print("tests")
    py = HOST / ".venv/Scripts/python.exe"
    print("  laptop:", run(py, "-m", "pytest", "-q", cwd=HOST).strip().splitlines()[-1])
    today = datetime.date.today()
    date = f"{today.day} {today:%B %Y}"
    sub(HOST / "syncpad/__init__.py", r'"[\d.]+"', f'"{version}"')
    g = ANDROID / "app/build.gradle.kts"
    code = int(re.search(r"versionCode = (\d+)", g.read_text("utf-8")).group(1)) + 1
    sub(g, r"versionCode = \d+", f"versionCode = {code}")
    sub(g, r'versionName = "[\d.]+"', f'versionName = "{version}"')
    log = ROOT / "CHANGELOG.md"
    head, _, rest = log.read_text("utf-8").partition("\n## ")
    log.write_text(f"{head.rstrip()}\n\n## {version}, {date}\n\n" + "".join(f"- {n}\n" for n in notes) + "\n## " + rest, "utf-8", newline="\n")
    return date


def make(version, date):
    """Tests, builds, signs and checks both downloads, then puts them on the website."""
    py = HOST / ".venv/Scripts/python.exe"
    print("phone app")
    run(ANDROID / "gradlew.bat", "--console=plain", "-Ppublic", ":app:testDebugUnitTest", ":app:assembleRelease", cwd=ANDROID)
    OUT.mkdir(parents=True, exist_ok=True)
    shutil.copy(ANDROID / "app/build/outputs/apk/release/app-release.apk", OUT / "SyncPad.apk")
    if "CN=SyncPad" not in run(sdk_tool("apksigner.bat"), "verify", "--print-certs", OUT / "SyncPad.apk"):
        die("the APK is not signed with the public key")
    badge = run(sdk_tool("aapt2.exe"), "dump", "badging", OUT / "SyncPad.apk")
    if f"versionName='{version}'" not in badge:
        die("the APK has another version")
    print("laptop app")
    run(py, "build_release.py", cwd=HOST)
    (OUT / "SHA256SUMS.txt").write_text("".join(f"{sha(n)}  {n}\n" for n in ASSETS[:3]), "utf-8", newline="\n")
    check_downloads()
    defender = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Windows Defender/MpCmdRun.exe"
    if defender.exists() and not os.environ.get("CI"):          # build machines often run without it
        for n in ASSETS[:2]:
            if "no threats" not in run(defender, "-Scan", "-ScanType", "3", "-File", OUT / n, "-DisableRemediation"):
                die(f"Windows Defender has something to say about {n}")
        print("  Defender: no threats")
    update_site(version, date)


def push(*refs):
    run("git", "-c", "credential.helper=", "-c", f"credential.helper=!'{gh()}' auth git-credential", "push", "origin", *refs)


def ci(version, notes):
    """Bump, commit and tag here; GitHub Actions does the rest."""
    touched = [HOST / "syncpad/__init__.py", ANDROID / "app/build.gradle.kts", ROOT / "CHANGELOG.md"]
    before = {p: p.read_bytes() for p in touched}
    try:
        if run("git", "status", "--porcelain").strip():
            die("commit or stash the changes first, so the release holds only what is committed")
        bump(version, notes)
        run("git", "add", *[str(p.relative_to(ROOT)) for p in touched])
        check_tree()
        run("git", "commit", "-m", f"SyncPad {version}", "-m", "\n".join(f"- {n}" for n in notes), "-m", "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>")
    except BaseException:
        for p, b in before.items():
            p.write_bytes(b)
        raise
    run("git", "tag", "-a", f"v{version}", "-m", f"SyncPad {version}")
    push("main", f"refs/tags/v{version}")
    print(f"pushed v{version}. GitHub Actions builds and publishes it: https://github.com/{REPO}/actions")


def ci_build(version):
    """Run by the workflow: build what the tag holds."""
    if current() != version:
        die(f"the source says {current()}, the tag says {version}")
    m = re.search(rf"^## {re.escape(version)}, (.*)$", (ROOT / "CHANGELOG.md").read_text("utf-8"), re.M)
    if not m:
        die("the changelog has no entry for this version")
    check_tree()
    print("  laptop:", run(HOST / ".venv/Scripts/python.exe", "-m", "pytest", "-q", cwd=HOST).strip().splitlines()[-1])
    make(version, m.group(1).strip())
    notes = re.search(rf"^## {re.escape(version)}, .*?\n\n(.*?)(?=^## |\Z)", (ROOT / "CHANGELOG.md").read_text("utf-8"), re.S | re.M).group(1).strip()
    (OUT / "notes.md").write_text(notes + "\n\n" + SMARTSCREEN, "utf-8", newline="\n")


def gh():
    p = shutil.which("gh") or r"C:\Program Files\GitHub CLI\gh.exe"
    if not Path(p).exists():
        die("GitHub CLI is not installed")
    return p


def publish(version):
    if current() != version or f"SyncPad {version}" not in run("git", "log", "-1", "--format=%s"):
        die(f"prepare {version} first")
    if run("git", "status", "--porcelain").strip():
        die("there are changes that are not committed")
    check_tree()
    sums = dict(reversed(l.split("  ")) for l in (OUT / "SHA256SUMS.txt").read_text("utf-8").splitlines())
    page = (WEB / "index.html").read_text("utf-8")
    for n in ASSETS[:3]:
        if sha(n) != sums.get(n):
            die(f"{n} changed after it was prepared")
    for n in ASSETS[:2]:
        if sums[n] not in page:
            die(f"the site has another checksum for {n}")
    notes = re.search(rf"^## {re.escape(version)}, .*?\n\n(.*?)(?=^## |\Z)", (ROOT / "CHANGELOG.md").read_text("utf-8"), re.S | re.M).group(1).strip()
    body = OUT / "notes.md"
    body.write_text(notes + "\n\n" + SMARTSCREEN, "utf-8", newline="\n")
    print("release")
    # The files go up first as a draft, so the site never points at something missing.
    run(gh(), "release", "create", f"v{version}", "--repo", REPO, "--draft", "--title", f"SyncPad {version}", "--notes-file", body, "--target", "main", *[OUT / n for n in ASSETS])
    push("main")
    run(gh(), "release", "edit", f"v{version}", "--repo", REPO, "--draft=false", "--latest")
    print("checking the live site")
    end = time.time() + 300
    while True:
        try:
            with urllib.request.urlopen(f"{SITE}/?{int(time.time())}", timeout=20) as r:
                if r.read().decode("utf-8") == page:
                    break
        except OSError:
            pass
        if time.time() > end:
            die("released, but the site has not updated after five minutes")
        time.sleep(6)
    for n, o in (("SyncPad.exe", "windows"), ("SyncPad.apk", "android")):
        with urllib.request.urlopen(f"{SITE}/download/{o}", timeout=120) as r:
            if hashlib.sha256(r.read()).hexdigest() != sums[n]:
                die(f"the site serves another {n}")
        print(f"  {n}: live and correct")
    print(f"published {version}")


def main():
    a = sys.argv[1:]
    if not a:
        sys.exit(__doc__)
    sys.path.insert(0, str(HOST))
    notes = [x for x in a[1:] if not x.startswith("--")]
    if "--publish" in a:
        publish(a[0])
    elif "--build" in a:
        ci_build(a[0])
    elif "--site" in a:
        m = re.search(rf"^## {re.escape(a[0])}, (.*)$", (ROOT / "CHANGELOG.md").read_text("utf-8"), re.M)
        update_site(a[0], m.group(1).strip() if m else "")
    elif "--ci" in a:
        ci(a[0], notes)
    else:
        prepare(a[0], notes)


if __name__ == "__main__":
    main()
