"""Virtual stylus for the emulator: a uinput pen with pressure, tilt, two buttons and an eraser end.

The device is described like the reference XP-Pen (pressure 0..16383, tilt ±127, BTN_STYLUS / BTN_STYLUS2,
BTN_TOOL_RUBBER) and is driven through Android's own `uinput` shell command (frameworks/base/cmds/uinput): a JSON
script registers the device, injects evdev events and keeps it alive until the script ends. The pen is a direct
input device whose axes match the screen, so pen coordinates are screen pixels.

Usage (repo root):
    .venv\\Scripts\\python tools\\pen.py <scenario> [--serial emulator-5556] [--peak 0.78] [--dry-run]

Scenarios:
    stroke    one horizontal stroke, pressure 0 → peak → 0
    warmup    five strong strokes then one at full pen pressure (auto calibration, checklist 1.1)
    flat      one stroke at a constant pressure (--peak)
    light     one stroke at the lightest pressure that registers (checklist 1.2)
    eraser    an eraser-end stroke across the canvas, then a tip stroke (checklist 2.4–2.5)
    pick      primary button held while touching and dragging (eyedropper, checklist 2.1)
    undo      a stroke, then a secondary-button click in hover (undo, checklist 2.2)
    undotouch secondary button pressed with the tip down (checklist 2.3)
    tilt      two strokes with the pen tilted left, then right (checklist 3.1)
    hover     the pen hovers for 3 s, then nothing (for finger/palm checks)
"""
from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
import tempfile
from pathlib import Path

ADB = Path(r"C:\Users\dushc\AppData\Local\Android\Sdk\platform-tools\adb.exe")

# evdev codes (linux/input-event-codes.h).
EV_SYN, EV_KEY, EV_ABS = 0, 1, 3
SYN_REPORT = 0
ABS_X, ABS_Y, ABS_PRESSURE, ABS_DISTANCE, ABS_TILT_X, ABS_TILT_Y = 0, 1, 24, 25, 26, 27
BTN_TOOL_PEN, BTN_TOOL_RUBBER, BTN_TOUCH, BTN_STYLUS, BTN_STYLUS2 = 320, 321, 330, 331, 332

INPUT_PROP_DIRECT = 1
# uinput ioctl names as the `uinput` command numbers them (UI_SET_EVBIT = 100 …); Android 14 accepts numbers only.
UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_ABSBIT, UI_SET_PROPBIT = 100, 101, 103, 110

PRESSURE_MAX = 16383
TILT_MAX = 127
DEVICE_ID = 1


class Script:
    """A uinput JSON script: register, then events and delays, in order."""

    def __init__(self, width: int, height: int) -> None:
        self.width = width
        self.height = height
        self.commands: list[dict] = [self._register()]
        self.events: list[int] = []

    def _register(self) -> dict:
        def axis(code: int, lo: int, hi: int) -> dict:
            return {"code": code, "info": {"value": 0, "minimum": lo, "maximum": hi, "fuzz": 0, "flat": 0,
                                           "resolution": 0}}

        return {
            "id": DEVICE_ID,
            "command": "register",
            "name": "Painter virtual pen",
            "vid": 0x28BD,
            "pid": 0x0905,
            "bus": "usb",
            "configuration": [
                {"type": UI_SET_EVBIT, "data": [EV_KEY, EV_ABS, EV_SYN]},
                {"type": UI_SET_KEYBIT, "data": [BTN_TOOL_PEN, BTN_TOOL_RUBBER, BTN_TOUCH, BTN_STYLUS, BTN_STYLUS2]},
                {"type": UI_SET_ABSBIT,
                 "data": [ABS_X, ABS_Y, ABS_PRESSURE, ABS_DISTANCE, ABS_TILT_X, ABS_TILT_Y]},
                {"type": UI_SET_PROPBIT, "data": [INPUT_PROP_DIRECT]},
            ],
            "abs_info": [
                axis(ABS_X, 0, self.width - 1),
                axis(ABS_Y, 0, self.height - 1),
                axis(ABS_PRESSURE, 0, PRESSURE_MAX),
                axis(ABS_DISTANCE, 0, 63),
                axis(ABS_TILT_X, -TILT_MAX, TILT_MAX),
                axis(ABS_TILT_Y, -TILT_MAX, TILT_MAX),
            ],
        }

    # --- low level -------------------------------------------------------------------------------------------
    def ev(self, type_: int, code: int, value: int) -> "Script":
        self.events += [type_, code, int(value)]
        return self

    def sync(self) -> "Script":
        self.ev(EV_SYN, SYN_REPORT, 0)
        self.commands.append({"id": DEVICE_ID, "command": "inject", "events": self.events})
        self.events = []
        return self

    def delay(self, ms: int) -> "Script":
        if self.events:
            self.sync()
        self.commands.append({"id": DEVICE_ID, "command": "delay", "duration": ms})
        return self

    # --- pen gestures ----------------------------------------------------------------------------------------
    def move(self, x: float, y: float, pressure: float = 0.0, tilt: tuple[int, int] = (0, 0)) -> "Script":
        self.ev(EV_ABS, ABS_X, round(x)).ev(EV_ABS, ABS_Y, round(y))
        self.ev(EV_ABS, ABS_PRESSURE, round(max(0.0, min(1.0, pressure)) * PRESSURE_MAX))
        self.ev(EV_ABS, ABS_TILT_X, tilt[0]).ev(EV_ABS, ABS_TILT_Y, tilt[1])
        return self.sync()

    def enter(self, x: float, y: float, rubber: bool = False) -> "Script":
        """The tool comes into hover range at (x, y)."""
        self.ev(EV_KEY, BTN_TOOL_RUBBER if rubber else BTN_TOOL_PEN, 1).ev(EV_ABS, ABS_DISTANCE, 10)
        return self.move(x, y)

    def leave(self, rubber: bool = False) -> "Script":
        self.ev(EV_KEY, BTN_TOOL_RUBBER if rubber else BTN_TOOL_PEN, 0)
        return self.sync()

    def button(self, code: int, down: bool) -> "Script":
        return self.ev(EV_KEY, code, 1 if down else 0).sync()

    def stroke(self, x0: float, y0: float, x1: float, y1: float, peak: float, *, steps: int = 60,
               rubber: bool = False, tilt: tuple[int, int] = (0, 0), profile: str = "bell",
               step_ms: int = 8) -> "Script":
        """A pen stroke from (x0, y0) to (x1, y1); pressure follows [profile] up to [peak] (0..1)."""
        self.enter(x0, y0, rubber).delay(30)
        self.ev(EV_ABS, ABS_DISTANCE, 0).ev(EV_KEY, BTN_TOUCH, 1)
        for i in range(steps + 1):
            t = i / steps
            p = peak * (math.sin(math.pi * t) if profile == "bell" else 1.0)
            p = max(p, 0.02)  # a touching pen always reports some pressure
            self.move(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, p, tilt)
            self.delay(step_ms)
        self.ev(EV_KEY, BTN_TOUCH, 0).ev(EV_ABS, ABS_PRESSURE, 0).ev(EV_ABS, ABS_DISTANCE, 10).sync()
        self.delay(30)
        return self.leave(rubber).delay(150)

    def text(self) -> str:
        if self.events:
            self.sync()
        return "\n".join(json.dumps(c) for c in self.commands) + "\n"


def scenario(name: str, w: int, h: int, peak: float, row: float | None = None) -> Script:
    s = Script(w, h)
    s.delay(800)  # give the input reader time to add the device
    cy = h * (row if row is not None else 0.5)
    x0, x1 = w * 0.25, w * 0.65
    if name == "stroke":
        s.stroke(x0, cy, x1, cy, peak)
    elif name == "warmup":
        for i in range(5):
            s.stroke(x0, h * 0.25 + i * h * 0.07, x1, h * 0.25 + i * h * 0.07, peak, profile="flat")
        s.stroke(x0, h * 0.7, x1, h * 0.7, peak, profile="flat")
    elif name == "flat":
        s.stroke(x0, h * 0.85, x1, h * 0.85, peak, profile="flat")
    elif name == "light":
        s.stroke(x0, cy, x1, cy, 0.04, profile="flat")
    elif name == "eraser":
        s.stroke(w * 0.45, h * 0.2, w * 0.45, h * 0.8, 1.0, rubber=True, profile="flat")
        s.stroke(x0, h * 0.75, x1, h * 0.75, peak)
    elif name == "pick":
        cy = h * (row if row is not None else 0.6)  # default: an empty row of the warm-up page
        s.enter(x0, cy).delay(30).button(BTN_STYLUS, True).delay(30)
        s.ev(EV_ABS, ABS_DISTANCE, 0).ev(EV_KEY, BTN_TOUCH, 1)
        for i in range(31):
            s.move(x0 + (x1 - x0) * i / 30, cy, 0.5).delay(10)
        s.ev(EV_KEY, BTN_TOUCH, 0).ev(EV_ABS, ABS_PRESSURE, 0).sync().delay(30)
        s.button(BTN_STYLUS, False).leave().delay(150)
    elif name == "undo":
        s.stroke(x0, cy, x1, cy, peak)
        # A hand is never perfectly still: the pen drifts a pixel or two while the button is clicked, and only
        # hover moves carry the button state to the app (Android drops hover ACTION_BUTTON_PRESS).
        hx, hy = w * 0.5, cy - h * 0.05
        s.enter(hx, hy).delay(100).button(BTN_STYLUS2, True)
        for i in range(4):
            s.move(hx + i, hy).delay(20)
        s.button(BTN_STYLUS2, False)
        for i in range(4):
            s.move(hx + 4 + i, hy).delay(20)
        s.delay(100).leave().delay(150)
    elif name == "undotouch":
        s.enter(w * 0.5, h * 0.3).delay(30).button(BTN_STYLUS2, True).delay(30)
        s.ev(EV_ABS, ABS_DISTANCE, 0).ev(EV_KEY, BTN_TOUCH, 1).move(w * 0.5, h * 0.3, 0.5).delay(80)
        s.ev(EV_KEY, BTN_TOUCH, 0).ev(EV_ABS, ABS_PRESSURE, 0).sync().delay(30)
        s.button(BTN_STYLUS2, False).leave().delay(150)
    elif name == "tilt":
        s.stroke(x0, h * 0.35, x1, h * 0.35, peak, tilt=(-90, 0), profile="flat")
        s.stroke(x0, h * 0.65, x1, h * 0.65, peak, tilt=(90, 0), profile="flat")
    elif name == "tiltramp":
        # Upright, half and steep tilt to the right, then steep tilt the other way (azimuth turned by 180°).
        for i, t in enumerate((0, 40, 80)):
            s.stroke(x0, h * (0.3 + 0.15 * i), x1, h * (0.3 + 0.15 * i), peak, tilt=(t, 0), profile="flat")
        s.stroke(x0, h * 0.75, x1, h * 0.75, peak, tilt=(0, 80), profile="flat")
    elif name == "hover":
        s.enter(w * 0.5, h * 0.5)
        for i in range(60):
            s.move(w * 0.5 + i * 2, h * 0.5).delay(50)
        s.leave().delay(100)
    else:
        raise SystemExit(f"unknown scenario {name!r}")
    return s.delay(500)


def adb(serial: str, *args: str, stdin: str | None = None) -> str:
    out = subprocess.run([str(ADB), "-s", serial, *args], input=stdin, capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit(out.stderr.strip() or f"adb {' '.join(args)} failed")
    return out.stdout


def screen_size(serial: str) -> tuple[int, int]:
    size = adb(serial, "shell", "wm", "size").strip().splitlines()[-1].split(":")[-1].strip()
    w, h = (int(v) for v in size.split("x"))
    # The tablet runs in landscape; `wm size` reports the natural (portrait or landscape) size.
    return (max(w, h), min(w, h))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scenario")
    ap.add_argument("--serial", default="emulator-5556")
    ap.add_argument("--peak", type=float, default=0.78, help="peak raw pressure 0..1 (XP-Pen ≈ 0.78)")
    ap.add_argument("--y", type=float, default=None, help="row of the stroke as a fraction of the height")
    ap.add_argument("--dry-run", action="store_true", help="print the uinput script instead of running it")
    a = ap.parse_args()
    w, h = (2560, 1600) if a.dry_run else screen_size(a.serial)
    text = scenario(a.scenario, w, h, a.peak, a.y).text()
    if a.dry_run:
        sys.stdout.write(text)
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        local = Path(tmp) / "pen.jsonl"
        local.write_text(text, encoding="utf-8")
        adb(a.serial, "push", str(local), "/data/local/tmp/pen.jsonl")
    adb(a.serial, "shell", "uinput", "/data/local/tmp/pen.jsonl")
    return 0


if __name__ == "__main__":
    sys.exit(main())
