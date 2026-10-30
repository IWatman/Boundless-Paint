# Checks on the real tablet

Everything here needs real hardware: the emulator has no stylus (pressure, tilt, buttons, eraser end), no palm
and a much faster CPU path for some kernels. Reference device: XP-Pen Magic Drawing Pad (MDP1221), Android 15,
2160×1440, EMR stylus (see spec/STATUS.md "On-device check").

**How to run.** Install the latest release through the in-app update (or `adb install`). Keep `adb logcat -s
PainterInput:* Painter:* AndroidRuntime:E` running and save it to `.tools/logs/tablet-<date>.log`. Mark each row
✅ / ❌ / ⚠️ and write what you saw in the Notes column. A ❌ becomes a STATUS debt.

Version tested: ______  Date: ______

## 1. Pressure and calibration (debt 11)
| # | Check | Steps | Expected | Result | Notes |
|---|---|---|---|---|---|
| 1.1 | Full range after warm-up | Fresh install. Pencil HB. Draw 5 strong strokes, then one at full pressure | The 6th stroke reaches the full size/opacity of the brush (spec: p_cal ≥ 0.98) | | |
| 1.2 | Light pressure | Draw with the lightest touch that still registers | A thin, faint line; no gaps, no dots at the start | | |
| 1.3 | Calibration survives restart | Force-stop the app, reopen, draw at full pressure at once | Full range without a new warm-up | | |
| 1.4 | Finger is not calibrated | Draw with a finger | Constant full pressure, unchanged by the pen calibration | | |

## 2. Pen buttons and the eraser end (debt 12)
| # | Check | Steps | Expected | Result | Notes |
|---|---|---|---|---|---|
| 2.1 | Primary button = eyedropper | Hold the primary (lower) button, touch a coloured area, drag | The colour follows the pen; nothing is drawn | | Which physical button is primary? |
| 2.2 | Secondary button = undo | Draw a stroke, click the secondary button in hover | The stroke is undone once (not twice) | | |
| 2.3 | Secondary while touching | Press the secondary button with the tip on the screen | One undo, no stroke | | |
| 2.4 | Eraser end | Turn the pen over, erase across a stroke | Erases with the eraser brush; the toolbar shows Erase | | |
| 2.5 | Back to the tip | Turn the pen back, draw | The previous mode and brush return | | |

## 3. Tilt and azimuth
| # | Check | Steps | Expected | Result | Notes |
|---|---|---|---|---|---|
| 3.1 | Tilt brushes | A brush with head rotation "stylus-tilt" (e.g. chisel marker, flat oil); draw with the pen tilted left, then right | The head turns with the tilt direction | | |
| 3.2 | Texture gradation | A textured pencil, tilted pen | Grain fades along the tilt axis (only if a preset uses gradation) | | |

## 4. Palm and finger
| # | Check | Steps | Expected | Result | Notes |
|---|---|---|---|---|---|
| 4.1 | Palm rest | Draw for 2 minutes with the palm on the screen | Not a single palm stroke | | |
| 4.2 | Finger gestures with the pen | Pan / zoom / rotate with two fingers between pen strokes | Gestures work; no stray dabs | | |
| 4.3 | Two/three-finger tap | Undo / redo by tap | Works as before | | |
| 4.4 | Finger after the pen (default "stylus only") | Touch the screen with the pen once, then draw with a finger | The finger pans instead of drawing; before any pen touch it draws | | |
| 4.5 | Hover reject | Settings: palm = hover reject. Hover the pen and touch with a finger; again 0.5 s after lifting the pen away | Finger ignored while hovering and for ~300 ms after | | |

## 5. Brushes with a real pen
Draw one stroke with pressure 0 → full → 0 and one quick scribble with each; the look should match the brush sheet.
| # | Category | Expected | Result | Notes |
|---|---|---|---|---|
| 5.1 | Pencils, charcoal, chalk | Size/opacity follow pressure, grain stays on the paper | | |
| 5.2 | Paints (oil, gouache, ink wash) | Colour pickup and wet edges as on the sheet | | |
| 5.3 | Watercolour (wet wash, light, dense) | Crisp edge with a darker rim, layers build up; no lag on pen-up | | |
| 5.4 | Markers incl. alcohol | Alcohol marker darkens where strokes overlap, already while drawing | | |
| 5.5 | Harmony, particles, 3D, stamps | Effects render while drawing; no stutter | | |
| 5.6 | Erasers | Pressure controls the eraser size/strength | | |

## 6. Performance (spec/tools/m2-brush.md §6)
| # | Scenario | Target | Result | Notes |
|---|---|---|---|---|
| 6.1 | A: pencil, normal speed | No visible lag behind the pen | | |
| 6.2 | B: soft round, size 300, fast stroke | ≤ 8 ms CPU per frame, smooth | | |
| 6.3 | C: textured bitmap brush, size 500 | ≤ 8 ms, smooth | | |
| 6.4 | D: smudge (Blend), size 200 | ≤ 12 ms | | |
| 6.5 | E: watercolour wet wash over a large area | Tick ≤ 8 ms; pen-up drying does not freeze the UI for long | | |
| 6.6 | Wet edges preview (ink wash, large size) | No stutter while drawing | | |
| 6.7 | 4K canvas, 10+ layers | Drawing and panning stay smooth | | |

## 7. Persistence, files, updates
| # | Check | Steps | Expected | Result | Notes |
|---|---|---|---|---|---|
| 7.1 | Tool state | Pick a brush, size, colour, add a palette colour; force-stop; reopen | All restored | | |
| 7.2 | Autosave | Draw, force-stop within 5 s, reopen from Recent | The last strokes are there | | |
| 7.3 | In-app update | Install the previous release, update from inside the app | Installs over it; drawings kept | | |
| 7.4 | Update dialog Cancel / Back | Cancel the system install dialog, and close it with Back | Returns to "update available", no hang | | |
| 7.5 | PNG export | Export via the system picker | Correct file | | |

## 8. Cosmetics to look at (debts 13, 15)
| # | Check | Expected | Result | Notes |
|---|---|---|---|---|
| 8.1 | New canvas | Layers panel collapsed, layer name localized | | |
| 8.2 | Recent preview of a wide canvas | No checkerboard at the card edges | | |
