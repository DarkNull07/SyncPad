# Third-party software

SyncPad itself is MIT licensed (see `LICENSE`). It is built with, and its downloads include, the following.

## Windows app

| Package | Licence |
|---|---|
| Python | PSF License |
| cryptography | Apache-2.0 or BSD-3-Clause (includes OpenSSL, Apache-2.0) |
| cffi, pycparser | MIT, BSD-3-Clause |
| Pillow | MIT-CMU |
| mss | MIT |
| segno | BSD-3-Clause |
| pystray | LGPL-3.0 |
| six | MIT |
| iroh (iroh-ffi and its Rust dependencies) | MIT or Apache-2.0 |
| Tcl/Tk | Tcl/Tk License |
| PyInstaller bootloader | GPL-2.0 with an exception that allows any licence for the bundled app |

pystray is LGPL-3.0. Its source is at <https://github.com/moses-palmer/pystray>. SyncPad's full source and build steps are public, so
you can rebuild the app with a modified pystray. The text of the LGPL is at <https://www.gnu.org/licenses/lgpl-3.0.html>.

## Android app

| Package | Licence |
|---|---|
| Kotlin, kotlinx.coroutines | Apache-2.0 |
| AndroidX, Jetpack Compose, Material Icons | Apache-2.0 |
| ZXing, zxing-android-embedded | Apache-2.0 |
| iroh (iroh-ffi, iroh-android and their Rust dependencies) | MIT or Apache-2.0 |
| JNA | Apache-2.0 or LGPL-2.1 (used under Apache-2.0) |
