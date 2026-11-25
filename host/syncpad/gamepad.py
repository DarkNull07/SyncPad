"""Wi-Fi/USB gamepad.

If the ViGEmBus driver and the `vgamepad` package are installed, the laptop sees
a real Xbox 360 controller. Otherwise the pad presses keys (WASD and friends),
which needs no driver. Over Bluetooth the phone is a real HID gamepad instead.
"""
# bit -> key for the keyboard fallback
KEYMAP = {0: "space", 1: "ctrl", 2: "r", 3: "e", 4: "q", 5: "f", 6: "tab", 7: "esc",
          8: "shift", 9: "c", 10: "up", 11: "down", 12: "left", 13: "right"}
DEAD = 0.35


class KeyPad:
    kind = "keys"

    def __init__(self, press, release, move):
        self.press, self.release, self.move = press, release, move
        self.held = set()

    def update(self, b, lx, ly, rx, ry, lt, rt):
        want = {k for bit, k in KEYMAP.items() if b >> bit & 1}
        if lx < -DEAD: want.add("a")
        if lx > DEAD: want.add("d")
        if ly < -DEAD: want.add("w")
        if ly > DEAD: want.add("s")
        if lt > 0.5: want.add("z")
        if rt > 0.5: want.add("x")
        for k in sorted(want - self.held):
            self.press(k)
        for k in sorted(self.held - want):
            self.release(k)
        self.held = want
        if abs(rx) > 0.12 or abs(ry) > 0.12:
            self.move(int(rx * abs(rx) * 22 * 64), int(ry * abs(ry) * 22 * 64))

    def close(self):
        self.update(0, 0, 0, 0, 0, 0, 0)


class XPad:
    kind = "xbox"

    def __init__(self):
        import vgamepad as vg
        self.pad = vg.VX360Gamepad()
        n = vg.XUSB_BUTTON
        self.bits = [n.XUSB_GAMEPAD_A, n.XUSB_GAMEPAD_B, n.XUSB_GAMEPAD_X, n.XUSB_GAMEPAD_Y,
                     n.XUSB_GAMEPAD_LEFT_SHOULDER, n.XUSB_GAMEPAD_RIGHT_SHOULDER,
                     n.XUSB_GAMEPAD_BACK, n.XUSB_GAMEPAD_START,
                     n.XUSB_GAMEPAD_LEFT_THUMB, n.XUSB_GAMEPAD_RIGHT_THUMB,
                     n.XUSB_GAMEPAD_DPAD_UP, n.XUSB_GAMEPAD_DPAD_DOWN,
                     n.XUSB_GAMEPAD_DPAD_LEFT, n.XUSB_GAMEPAD_DPAD_RIGHT]

    def update(self, b, lx, ly, rx, ry, lt, rt):
        for i, code in enumerate(self.bits):
            (self.pad.press_button if b >> i & 1 else self.pad.release_button)(button=code)
        self.pad.left_joystick_float(lx, -ly)
        self.pad.right_joystick_float(rx, -ry)
        self.pad.left_trigger_float(lt)
        self.pad.right_trigger_float(rt)
        self.pad.update()

    def close(self):
        self.pad.reset()
        self.pad.update()


def make(press, release, move, allow_driver=True):
    if allow_driver:
        try:
            return XPad()
        except Exception:     # package or driver missing
            pass
    return KeyPad(press, release, move)
