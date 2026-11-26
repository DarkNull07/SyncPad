# SyncPad

Your Android phone as a trackpad, keyboard, air mouse, presenter, gamepad, shortcut deck,
file/clipboard link and screen viewer for a Windows laptop. Over Wi-Fi, USB or Bluetooth.

**Download: [syncpad-dark.vercel.app](https://syncpad-dark.vercel.app)**, or from [Releases](https://github.com/DarkNull07/SyncPad/releases).

- Laptop: `SyncPad.exe`, Windows 10 or 11, 64-bit
- Phone: `SyncPad.apk`, Android 9 or newer

## Set up

1. **Laptop:** run `SyncPad.exe`. Windows SmartScreen may warn because it is unsigned. Windows
   Firewall will ask about network access, which only Wi-Fi needs. Allow the kind your Wi-Fi is
   set to (Public or Private); at home, setting it to Private first is safer.
2. **Phone:** open `SyncPad.apk` and allow the install.
3. **Pair once:** scan the code in the laptop window, or press *Pair over USB*, or copy the link.

## Files and shortcuts

- **Laptop files** (phone: More): browse, search, preview, rename, delete to the Recycle Bin, save to the phone (folders as
  zip), upload into a folder, open on the laptop. Programs are shown in their folder, never started from the list.
- **Phone files** (laptop: Phone tab): off until you turn on *Phone files* in More > Share and allow file access in Android.
  Browse, open, save, and drop files into a folder. The laptop cannot delete anything on the phone. *Open in Explorer* needs the
  phone's USB mode set to File transfer.
- **Deck shortcuts**: tap *Press on laptop* and press the keys on the laptop's keyboard, or *Pick keys*. Add several to run them in
  order. While recording, the laptop shows a notice and the keys do nothing else. Win+L and Ctrl+Alt+Del belong to Windows and cannot be sent.

## Choosing a connection

| | Needs | Good for | Does not do |
|---|---|---|---|
| USB | USB debugging on the phone | lowest latency, any network | - |
| Wi-Fi | same network | everything, no cable | networks that isolate devices |
| Bluetooth | Windows pairing, no laptop software | other PCs, lock screen | files, clipboard, screen, deck "Open" |
| Anywhere | pairing at home once, both online | another Wi-Fi or mobile data | lock screen |

Tap the connection label at the top right to choose Automatic, USB, Wi-Fi or Bluetooth at any time. In Bluetooth mode, files and
the screen still use USB or Wi-Fi when there is one. In Automatic, USB and Wi-Fi switch by themselves. On campus or hotel Wi-Fi that blocks devices from seeing
each other, connect the laptop to the phone's hotspot.

**Anywhere:** in Automatic, when nothing nearby answers, the phone reaches the laptop over the internet with
[iroh](https://www.iroh.computer): both sides dial each other by key, iroh punches a direct UDP path through
the routers when it can and otherwise relays, and keeps trying to go direct. SyncPad's own encrypted
protocol runs inside, unchanged. Only phones paired with the laptop are let in. Turn it off with
*Connect from anywhere* on the phone or *Reachable from other networks* on the laptop.

**Bluetooth, first time:** More > Connection > Turn on Bluetooth mouse > Make phone visible, then on
Windows: Bluetooth > Add device. If the phone was already paired with this laptop, remove it
there first, or Windows will not notice that it is now a mouse.

## Limits worth knowing

- Windows blocks injected input to programs running as administrator unless SyncPad also runs as
  administrator, and never allows it on the UAC prompt or lock screen. Bluetooth mode has neither limit.
- The Wi-Fi/USB gamepad presses keys (WASD etc.). Games that need a real controller: use Bluetooth mode, or
  install the ViGEmBus driver and `vgamepad` and run the host from source.
- Android only lets the open app read the clipboard, so phone-to-laptop sync happens when SyncPad is in front.
- Bluetooth typing uses a US keyboard layout; accents and emoji need Wi-Fi or USB.

## Security

Every phone has its own 256-bit key, exchanged through the pairing code. All traffic, including
keystrokes, is AES-256-GCM encrypted and authenticated with fresh keys per connection. A pairing code
works for one phone and expires after 10 minutes. Laptop-side keys are protected with Windows DPAPI.
There is no forward secrecy: someone who records traffic and later steals the key could read it.
A paired phone has full control of the laptop, so press *Forget* if you lose it.

Away from home, iroh's free address and relay servers help the two find each other. A relay sees only
encrypted traffic, plus addresses and amounts. Both apps ask GitHub for the newest version number now
and then; turn that off in Settings. Nothing else leaves your devices.

## Updates

The Windows app downloads a new version in the background, checks it against the release's
`SHA256SUMS.txt`, and installs it when you click the line at the bottom of its window. The Android
app says when one is out and opens the website, since Android only installs what you choose.

## Development

    host/      Windows app (Python 3.14)
    android/   phone app (Kotlin)
    docs/PROTOCOL.md   the wire protocol

Laptop app, from `host/`:

    python -m venv .venv
    .venv\Scripts\pip install -r requirements.txt pytest pyinstaller
    .venv\Scripts\python -m pytest
    .venv\Scripts\python syncpad_host.py      (run from source)
    .venv\Scripts\python build_release.py     (build the download)

Phone app, from `android/`, with JDK 17 and the Android SDK (put its path in `local.properties` as
`sdk.dir=...`):

    gradlew :app:testDebugUnitTest
    gradlew :app:assembleRelease

The Android tests run the phone's networking code against the real laptop app, so set up `host/.venv`
first; without it those tests are skipped. The APK is signed with the building machine's debug key: keep
`%USERPROFILE%\.android\debug.keystore` to be able to update an installed app in place.

Website: `web/`, static files with no build step.

### Releases

`tools/release.py` keeps one version number in both apps, [CHANGELOG.md](CHANGELOG.md) and the website. It runs every test, builds and signs both downloads, checks them for private text and commits. Publishing is a separate step.

```
host\.venv\Scripts\python tools\release.py 0.2.1 "What changed"
host\.venv\Scripts\python tools\release.py 0.2.1 --publish
```

## Licence

MIT, see `LICENSE`. Third-party software is listed in `THIRD_PARTY.md`. The website's font is Hanken Grotesk (SIL Open Font License 1.1).
