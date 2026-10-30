# Status

## M0–M1: done (2026-10-26)
- Build: `. .\tools\env.ps1; .\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug`.
- Tests: 88 JVM tests, all green:
  - blend 10, compositor 8, color 9;
  - history 8 + 13;
  - io 8 + 5;
  - stroke 18;
  - view transform 9.
- Verified on the emulator (painter_tab, Android 14, GLES 3.1, RGBA16F). Screenshots in `.tools/qa/`:
  - Home → New canvas (presets, layer limit) → Studio;
  - finger stroke, stroke preview;
  - undo;
  - eraser;
  - new layer;
  - color panel (wheel), color pick;
  - drawing on the second layer;
  - autosave when going Home;
  - force-stop → reopen from Recent: layers and pixels intact;
  - PNG export via the system picker: file is correct.

## On-device check (2026-10-26)
XP-Pen Magic Drawing Pad (MDP1221), Android 15, 2160×1440, EMR stylus `tct_stylus`: pressure 0..16383, TILT_X/Y ±127, BTN_STYLUS, BTN_STYLUS2, BTN_TOOL_RUBBER. Input log: tag `PainterInput`, file `.tools/logs/tablet-input.log`.

Works:
- **Finger gestures.** Pan, zoom, rotate with snapping to 0°. A quick pinch fits the canvas to the screen. Two-finger tap = undo, three-finger = redo. A second-finger tap cancels the stroke.
- **Stylus.** Recognized as stylus (tool=2), provides pressure and tilt (AXIS_TILT up to 0.9 rad). The stroke starts immediately on touch.
- **Palm.** The system cancels the touch (ACTION_CANCEL), the stroke is rolled back.

Found:
- Micro-contacts of the stylus on lift-off (2 samples, pressure 0) turned into empty strokes. **Fixed:** a stylus stroke without pressure is discarded.
- Strong pressure gives a maximum of ≈0.78, normal pressure 0.3–0.5. A default pressure curve and calibration are needed (debt 11).
- Stylus buttons and BTN_TOOL_RUBBER (eraser, tool=4) are not handled by the app (debt 12). We did not have time to test the presses.

## Known issues and debt (in M2)
| # | Issue | Plan |
|---|---|---|
| 1 | ✅ CPU layer tiles in half (8 B/px), stroke buffer float32, upload to the GPU as GL_HALF_FLOAT. Remaining: the limit formula accounts for GPU memory (debt 6). Was: the layer limit was conservative (14 for 2048² on the emulator). The formula did not account for tile sparseness, and CPU tiles were float32 = 16 B/px | store CPU tiles in half (ShortArray), limit based on actual memory |
| 4 | ✅ Color, palette and the brush of each mode (preset, size, opacity) are restored from the `tool_prefs` SharedPreferences. Was: Color, brush and palette are not persisted between sessions | save to settings or manifest |
| 5 | ✅ GPU↔CPU parity: androidTest `BlendParityTest` (33 modes, 13 adjustments) green on the emulator; ADD, INVERT, darker/lighter color fixed. Was: the ADD formula — shader and CPU could diverge on a semi-transparent background | a shared GPU↔CPU test vector |
| 6 | GPU memory: a composite texture with mip levels for every document tile | atlas and LRU, lazy mips |
| 7 | ✅ `list()` only reads, recovery happens in `recoverInterrupted()` on start. Was: `ProjectStore.list()` could delete the `.tmp` of a concurrent save | access serialized via a Mutex in the ViewModel. Remove the cleanup from list() |
| 8 | No double-tap fit, no light canvas background, no gamma-correct | M2 |
| 9 | Export is PNG only, without options (JPG and transparent background exist in the API but not in the UI) | export dialog |
| 10 | Saving takes a full copy of the document on the main thread | incremental saving of dirty tiles |
| 11 | ✅ Per-device pressure calibration wired into the app: `InputCalibration` (auto range = the 95th percentile of the last 20 stroke peaks, rises at once and falls with an EMA) is applied before the brush curves in `StrokeEngine`, one instance per `InputDevice.descriptor`, and saved in `files/settings/input.json` by the new `InputSettings` store. The XP-Pen's ≈0.78 peak reaches 1.0 after 3–5 strong strokes. Remaining: the curve UI and the calibration wizard come with the stylus settings screen (debt 12). Was: The XP-Pen stylus does not reach 1.0 even with strong pressure | pressure curve in settings + auto-calibration by the max over the session (as in IP) |
| 12 | ✅ Primary pen button (hold) = eyedropper, secondary (click) = undo, read with `MotionEvent.getButtonState()` (BUTTON_STYLUS_PRIMARY/SECONDARY, plus BUTTON_SECONDARY/TERTIARY for drivers in mouse mode) and from ACTION_BUTTON_PRESS/RELEASE in hover; the decision is the pure `PenButtons` (JVM test `PenButtonsTest`). The back of the pen (TOOL_TYPE_ERASER) draws with the erase brush and the previous mode is restored when the tip returns. Both come from the spec defaults: the assignments are **not configurable** yet, and this still needs a check on a real pen. Was: Stylus buttons (BUTTON_STYLUS_PRIMARY/SECONDARY) and the eraser end (TOOL_TYPE_ERASER) are unused | stylus settings: assign buttons (eyedropper, eraser, undo, custom tool), eraser → Erase mode. Verify on the device |
| 13 | A new canvas opens with the layers panel expanded, and the name "Layer 1" is not localized | minor, M2 |
| 14 | ✅ Autosave 2 s after the last change (not during a stroke), verified with force-stop. Was: autosave only on leaving the studio and in `onStop`: if the process was killed (adb install, crash, system), the last strokes were lost (found on the emulator 2026-10-27) | debounced save ~2 s after each history change |
| 15 | The preview of a wide canvas in Recent shows a checkerboard at the edges instead of the card background | cosmetic |
| 16 | ✅ Two-level menu: Symmetry, Guides, Perspective, Shapes. Was: the Creative tools menu (symmetry, guides, perspective, shapes) no longer fits the screen and has to be scrolled | group it into sections or a side panel like IP |
| 17 | The live preview of a closed shape omits the closing segment and the fill; both appear only on Apply | render the closed path and fill in the preview engine (StrokeEngine closes/fills only in end()) |
| 18 | ✅ Shared ToggleChip (FilterChip with a check). Was: on/off toggles in the tool bars (shape Outline/Fill, guide toggles) look the same as plain action buttons | distinct on/off styling (filled chip or check) |
| 19 | ✅ The left toolbar shows the active tool and returns to the brush on tap. Was: after Apply the Shape tool (and Fill/Gradient) stays active, but the left toolbar still highlights the brush, so the next stroke unexpectedly draws a shape | show the active tool in the toolbar (or return to the brush after Apply, as a setting) |
| 20 | ✅ Opens on the current brush's category with it scrolled into view. Was: the brush browser always opens on the first category instead of the current brush's category; the category list no longer fits and needs scrolling | open on the current brush's category and scroll it into view |
