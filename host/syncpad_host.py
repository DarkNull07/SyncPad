"""SyncPad for Windows. Run with no arguments for the window and tray icon."""
import argparse
import ctypes
import multiprocessing
import sys
import time


def main():
    ap = argparse.ArgumentParser(prog="SyncPad")
    ap.add_argument("--hidden", action="store_true", help="start in the tray")
    ap.add_argument("--console", action="store_true", help="no window; print the pairing link")
    ap.add_argument("--dry-run", action="store_true", help="print input instead of performing it")
    ap.add_argument("--port", type=int)
    ap.add_argument("--config", help="settings folder")
    ap.add_argument("--local-only", action="store_true", help="listen on 127.0.0.1 only (USB)")
    ap.add_argument("--no-remote", action="store_true", help="not reachable from other networks")
    ap.add_argument("--remote", action="store_true", help="reachable from other networks even with --local-only")
    a = ap.parse_args()

    from syncpad import wire
    from syncpad.config import Config
    from syncpad.server import Server
    from syncpad.usb import Usb
    from syncpad.winput import DryInput, set_dpi_aware
    set_dpi_aware()

    port = wire.TCP_PORT if a.port is None else a.port
    config = Config(a.config)
    server = Server(config, DryInput(echo=True) if a.dry_run else None, port=port,
                    discovery_port=0 if a.local_only else wire.DISCOVERY_PORT,
                    bind="127.0.0.1" if a.local_only else "0.0.0.0", clipboard=not a.dry_run)
    try:
        server.start()
    except OSError:
        msg = f"SyncPad is already running, or port {port} is in use."
        if a.console:
            print(msg)
        else:
            ctypes.windll.user32.MessageBoxW(None, msg, "SyncPad", 0x40)
        return 1
    if a.remote or not (a.local_only or a.no_remote):
        from syncpad.remote import Remote
        server.remote = Remote(config, server.port, on_change=lambda: server.on_change())
        server.remote.on_ready = server.remote_ready
        server.remote.start()

    if a.console:
        print(config.pair_link(server.port), flush=True)
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            server.stop()
        return 0

    from syncpad.ui import App
    usb = Usb(server.port)
    usb.enabled = config.settings["usb"]
    app = App(config, server, usb, hidden=a.hidden)
    usb.start()
    app.run()
    return 0


if __name__ == "__main__":
    multiprocessing.freeze_support()
    sys.exit(main())
