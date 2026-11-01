# Checks on the real tablet

Everything here needs real hardware: the emulator has no stylus (pressure, tilt, buttons, eraser end), no palm
and a much faster CPU path for some kernels. Reference device: XP-Pen Magic Drawing Pad (MDP1221), Android 15,
2160×1440, EMR stylus (see spec/STATUS.md "On-device check").

**How to run.** Install the latest release through the in-app update (or `adb install`). Keep `adb logcat -s
PainterInput:* Painter:* AndroidRuntime:E` running and save it to `.tools/logs/tablet-<date>.log`. Mark each row
✅ / ❌ / ⚠️ and write what you saw in the Notes column. A ❌ becomes a STATUS debt.

Version tested: ______  Date: ______

**Already checked on the emulator with the virtual pen** (`tools/pen.py`, a uinput pen like the XP-Pen: pressure
0..16383 peaking at 0.78, tilt, two buttons, eraser end; 2026-10-30, v0.1.9+): 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 2.4,
2.5, 3.1 (shading pencil, tilt calligraphy), 4.4, 4.5 (during hover; the 300 ms tail is covered by JVM tests). Found and fixed on the way: a pen button
click in hover never reached the app (Android 14 drops hover ACTION_BUTTON_PRESS and Compose filters hover
events of an embedded view). On the tablet these rows still need a real-pen pass, but they are expected to
pass; the rest of the list is untested.

## 1. Pressure and calibration (debt 11)
| # | Check | Steps | Expected | Result | Notes |
|---|---|---|---|---|---|
| 1.1 | Full range after warm-up | Fresh install. Pencil HB. Draw 5 strong strokes, then one at full pressure | The 6th stroke reaches the full size/opacity of the brush (spec: p_cal ≥ 0.98) | | |
| 1.2 | Light pressure | Draw with the lightest touch that still registers | A thin, faint line; no gaps, no dots at the start | | |
| 1.3 | Calibration survives restart | Force-stop the app, reopen, draw at full pressure at once | Full range without a new warm-up | | |
| 1.5 | Calibration wizard | Options → Stylus → Calibrate: 3 light, then 3 hard presses on the pad | State becomes "Fixed: min … max …"; a light stroke is visible, a hard one reaches full size | | |
| 1.6 | Reset to auto | Options → Stylus → Reset to auto | State "Auto range"; the warm-up of 1.1 applies again | | |
| 1.7 | Curve presets | Soft, then Firm; draw the same light stroke | Soft: thicker/darker at light pressure; Firm: thinner | | |
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
| 3.1 | Tilt brushes | Shading pencil and "Calligraphy, tilt nib"; draw upright, tilted, tilted the other way | Pencil: wider and lighter when tilted. Nib: turns with the tilt direction | | |
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
| 6.7 | 4K canvas at the supported layer limit (≤6 layers on a 512 MB heap; fewer on smaller heaps — see [PERFORMANCE-M5.md §4.8](../PERFORMANCE-M5.md)) | Drawing, panning and one filter stay usable; record the actual layer count and heap | | |

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

## 9. M5: video, audio, 4K, materials, patterns, localization

Run these rows on **0.5.10 or later**. The JVM/emulator evidence and the exit criteria are in
[../QA-M5.md](../QA-M5.md); this section is the real-hardware part. Keep the exported MP4/M4A files and record
wall-clock times with a stopwatch. Inspect files with `MediaExtractor` or `ffprobe` (tracks, duration, frame
count). A row with no 4K support is **skip with the exact reason**, not fail.

| # | Check | Steps | Expected | Result | Notes |
|---|---|---|---|---|---|
| 9.1 | Video conversion and timeline | Open an animation → Options → Video mode…; compare duration and clips, then scrub | Duration is `round(steps × 60 / fps)` ticks; one clip per playback step; scrubbing changes only the playhead/frame, not pixels or history | | |
| 9.2 | Preview and transport | Play, pause, stop, scrub; paint during playback; undo; close the panel | Canvas follows the playhead at 60 fps; painting/undo stop playback and edit the visible frame; closing restores the editing frame; no timelapse frame is added | | |
| 9.3 | Clip editing | Add after selection, blank, delete, trim the right edge to zero, move, toggle snap | Track stays sorted, butt-jointed and non-overlapping; delete-to-zero removes the clip; a deleted frame leaves a blank clip (not a crash) | | |
| 9.4 | 1080p60 export | 5 s timeline, 1080p, audio off; export and time it | Record wall-clock time; one `video/avc` track, 300 frames, duration 5 s ±1 frame; temp file deleted; plays in Android and a desktop player | | |
| 9.5 | 1440p / 4K chips | Inspect the resolution chips and their disabled reasons; force a low-heap refusal if possible | Chips match the encoder; 4K30 is explicit; memory guard names the estimate and heap; no silent fps or size change | | |
| 9.6 | 4K export | If offered, export 5 s at 4K30 or 4K60; otherwise record the disabled reason | Record wall-clock time; completes without OOM; layer count stays within the derived limit (≤6 at 4K on a 512 MB heap); skip with reason if unsupported | | |
| 9.7 | Audio import | Import MP3 and M4A; try WAV if the device decoder supports it; place at the playhead; try an unsupported codec | A supported clip appears, plays and is trimmed/moved; an unsupported codec is rejected with its name; audio file is copied into the project | | |
| 9.8 | Audio record | Allow `RECORD_AUDIO`, record 10 s of voice; repeat with permission denied | Allowed: clip inserted at the playhead and audible. Denied: clear message, no crash, project unchanged | | |
| 9.9 | Audio mux and mute | Export with audio, then with mute; inspect tracks and durations | Two tracks (`video/avc`, `audio/mp4a-latm`) with audio; one track when muted; audio duration ≤ video; trailing audio truncated | | |
| 9.10 | A/V sync | Record/import a clap or voice, export 60 s, watch and listen on the tablet and a desktop player | First clap aligned within one frame; no audible drift over the clip | | |
| 9.11 | Playback compatibility | Play 720p, 1080p, 1440p and 4K files in the Android player and a desktop player | Correct duration, orientation and audio; no decode errors or black frames | | |
| 9.12 | Big-project stability | 1080p, 10 layers if the heap allows, 60 s timeline with audio; then a 4K canvas at its layer limit; scrub, play, filter, export repeatedly | No ANR, OOM or force-stop; Dalvik heap stable; temp files cleaned; record the actual layer count and heap | | |
| 9.13 | Pattern fill visual | Fill with each of the twelve patterns; rotate, offset and change colours on a large canvas | No seams at tile edges or after rotation; controls visibly change the result; selection/alpha lock respected | | |
| 9.14 | Materials visual | Stamp and paint each of the eight materials; change size, rotation, opacity and spacing; restart | All eight are distinct and visible; spacing is stable along a drag; favourites, recents and settings survive restart | | |
| 9.15 | Localization spot check | Switch the device to Russian; open Filter, Pattern fill, Materials, Video, Audio and Export dialogs; trigger brush-import counts | No English leftovers, clipped labels or broken plurals; numbers and units read correctly | | |
| 9.16 | Stylus/palm regression | On the M5 build repeat 1.1 (full pressure), 2.1 (primary button), 2.4 (eraser end) and 4.1 (palm rest) | Pressure, buttons, eraser and palm rejection behave as in the M0–M1 baseline | | |
