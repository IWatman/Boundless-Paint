# Status

## M3: in progress
### Filters (2026-10-31)
- Core: `core/filter` — `FilterParams` (sealed, 9 kinds) and `Filters.apply`, which runs tile by tile with a patch
  padded by the filter's reach (no seams), respects the selection and alpha lock, and returns a `TilePatch` so a
  filter is one undo step.
- Implemented: blur, motion blur, pixelate, sharpen (unsharp mask), glow/bloom, noise (mono/colour, deterministic),
  find edges (Sobel), chromatic aberration, fisheye/pinch, dilate, erode, zoom blur, spin blur, radial lines,
  speed lines and halftone.
- UI: Editing Tools → Filters… with a chip per filter and only the sliders it needs; the filter runs on a worker
  thread over a private copy of the layer and is committed in one history step. Strokes are ignored while it runs.
- Tests: `FiltersTest` (21) — tiling and seams, determinism, selection/alpha lock, undo, parameter round trip,
  generators on an empty layer.

### Liquify, text, shortcuts (2026-10-31)
- Liquify: `core/liquify/LiquifySession` pushes pixels along the drag with a smooth falloff and keeps the original
  tiles for one undo step. Editing Tools → Liquify shows a size/strength bar with Cancel (puts the pixels back) and
  Apply (one history step). `LiquifySessionTest` (4).
- Text: Editing Tools → Text opens a multi-line dialog (text, em size); the text is rasterized with the current
  colour and stamped at the centre of the canvas, clipped by the selection, as one undo step; the Transform tool
  moves it afterwards.
- Keyboard shortcuts: Ctrl+Z undo, Ctrl+Shift+Z / Ctrl+Y redo, B paint, E erase, `[`/`]` brush size, F fit.
### Expansion batch (2026-10-31)
- Brush import/export: `.pbrush` / `.pbrushes`, MyPaint `.myb`, GIMP `.gbr`, Krita `.kpp`, Procreate
  `.brushset`/`.brush` and PNG/JPG heads/textures; imported brushes live in their own **Imported** folder with
  their images under `brushes/created/assets/<id>/`. Export current brush or all own brushes through SAF.
  `BrushImportTest` (7) covers the parsers.
- Canvas: crop, canvas size (scale/anchors) and straighten; one `CanvasCommand` swaps every layer, mask and the
  selection, with undo/redo and a GL reset. `CanvasOpsTest` (6).
- Raster layer styles as filters: outline, drop shadow, outer glow; plus a panel-grid Draw generator.
- Curvilinear 5-point perspective grid (fisheye rings + radial spokes).
- PSD import/export of the 8-bit RGB raster subset (layers, names, opacity, blend, clipping, RLE, composite
  fallback) — `PsdIO`, `PsdIOTest`; PSD export from Options, import from Home.
- Timelapse: a JPEG frame per change in a project (settings toggle), H.264 MP4 export via MediaCodec with
  15/30/60 s or full duration and 720p/1080p/4K quality.
- Settings sheet: appearance, colour pipeline, timelapse, stylus and About.
- Live filter layers: a `FilterLayer` caches the filtered composite of every root sibling below it; the compositor
  and the GPU renderer replace that lower content with the cache, and the app recomputes the cache after each
  change. Added from Editing Tools → New filter layer…, persisted in the project manifest (`kind: "filter"`).

## M2: done (2026-10-31)
- Build: `. .\tools\env.ps1; .\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug`.
- Tests: **524 JVM tests, all green** (451 in `:core`, 73 in `:app`); GPU↔CPU parity `BlendParityTest` (33 modes, 13 adjustments, in sRGB and in linear light) green on `painter_tab` (Android 14, GLES 3.1, RGBA16F).
- Brush benchmark (`StrokeBench`, 16-core desktop JVM): A 0.23–0.27 ms/frame, B 0.87–1.69, C 1.08–2.23, D (smudge) 0.97–1.10, E (watercolor) ~8.2 mean / ~68 max. The M2 target (A–C ≤ 8 ms/frame at 2000 px/s) is met.
- Catalog: **91 presets in 17 categories**, `gen_assets.py --check` OK. Coverage of every engine feature: all ten specials (filter, harmony, fill, screentone, double, particles, color stamp, 3D, watercolor), all four texture modes (canvas-fixed, warp-along-stroke, rotate-with-stroke, moving), taper shapes, dynamics (pressure/velocity/tilt, size/flow/scatter/tex-depth/opacity/angle/color-jitter/progress), mixing (mix-in, dilution, pull, blur), stroke effects (glaze, wet edges, adhesion, brush blend mode), buildup, double, color stamps, screentone, harmony variants, particles, 3D.
- Emulator checks: every M2 UI flow was exercised on `painter_tab` (brush browser/editor, stylus panel and calibration wizard, layers/masks/adjustments, selection/transform/fill/gradient, guides/symmetry/perspective/shapes, export, updates, incremental autosave, zoomed-in and zoomed-out rendering).
- Open: the stylus rows of [qa/TABLET-CHECKLIST.md](qa/TABLET-CHECKLIST.md) still need the real XP-Pen; they are marked in the file.

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
| 1 | ✅ CPU layer tiles in half (8 B/px), stroke buffer float32, upload to the GPU as GL_HALF_FLOAT. The layer limit formula now also accounts for GPU memory (`LayerLimit`, debt 6). Was: the layer limit was conservative (14 for 2048² on the emulator). The formula did not account for tile sparseness, and CPU tiles were float32 = 16 B/px | store CPU tiles in half (ShortArray), limit based on actual memory |
| 4 | ✅ Color, palette and the brush of each mode (preset, size, opacity) are restored from the `tool_prefs` SharedPreferences. Was: Color, brush and palette are not persisted between sessions | save to settings or manifest |
| 5 | ✅ GPU↔CPU parity: androidTest `BlendParityTest` (33 modes, 13 adjustments) green on the emulator; ADD, INVERT, darker/lighter color fixed. Was: the ADD formula — shader and CPU could diverge on a semi-transparent background | a shared GPU↔CPU test vector |
| 6 | ✅ Composite memory is bounded (debt 6): visible tiles live in an LRU cache (32 MB budget, at least the visible set), composited on demand; zoomed out the canvas is drawn from a single mipmapped overview (up to 2560 px on the long side) instead of one texture per tile, and the overview's mip chain is allocated and generated only when the canvas is small on screen. Tiles have no mips and offscreen tiles are no longer pre-composited. The layer limit also accounts for the GPU layer tiles (192 MB allowance, `LayerLimit`). Was: GPU memory: a composite texture with mip levels for every document tile | atlas and LRU, lazy mips |
| 7 | ✅ `list()` only reads, recovery happens in `recoverInterrupted()` on start. Was: `ProjectStore.list()` could delete the `.tmp` of a concurrent save | access serialized via a Mutex in the ViewModel. Remove the cleanup from list() |
| 8 | ✅ Double tap with one finger fits the canvas: the dot of a still finger tap is deferred by 280 ms, so the second tap fits and draws nothing (turn it off in Options; a stylus is unaffected). The backdrop around the canvas is dark #2B2B2B or light #D0D0D0 (Options, kept in `tool_prefs`). Gamma-correct blending is a document toggle (`Document.gammaCorrect`, saved in the manifest): `BlendMath` decodes sRGB → linear, blends and encodes back, the GPU blend and adjustment shaders mirror it, `GammaTest` (JVM) and the new linear-light pass of `BlendParityTest` (emulator) cover it. Was: No double-tap fit, no light canvas background, no gamma-correct | M2 |
| 9 | ✅ Export dialog: PNG or JPG, JPG quality 1–100 and, for PNG, a transparent background instead of the paper; the activity then opens the system picker with the matching MIME type and file extension. Checked on the emulator: a 2560×1432 JPG (FF D8 FF E0) and a PNG with alpha 0 outside the artwork. Was: Export is PNG only, without options (JPG and transparent background exist in the API but not in the UI) | export dialog |
| 10 | ✅ Saving is incremental: `SaveTracker` remembers the version of every tile after a successful write, and `save()` copies only the tiles that changed since — on the main thread, without deep-copying the document. `ProjectIO.saveDelta` builds the `.tmp` directory from the previous one: files of untouched layers and masks are copied, unchanged tiles keep their compressed bytes, only changed tiles are LZ4-encoded again; the manifest is always rebuilt and the thumbnail is refreshed on leaving the studio (autosave keeps the old `thumb.png`, `updateThumbnail` refreshes it on Home). `IncrementalSaveTest` covers reuse, removals, new layers, masks, selection, metadata and the failure path. Was: Saving takes a full copy of the document on the main thread | incremental saving of dirty tiles |
| 11 | ✅ Per-device pressure calibration wired into the app: `InputCalibration` (auto range = the 95th percentile of the last 20 stroke peaks, rises at once and falls with an EMA) is applied before the brush curves in `StrokeEngine`, one instance per `InputDevice.descriptor`, and saved in `files/settings/input.json` by the new `InputSettings` store. The XP-Pen's ≈0.78 peak reaches 1.0 after 3–5 strong strokes. Remaining: the curve UI and the calibration wizard come with the stylus settings screen (debt 12). Was: The XP-Pen stylus does not reach 1.0 even with strong pressure | pressure curve in settings + auto-calibration by the max over the session (as in IP) |
| 12 | ✅ Primary pen button (hold) = eyedropper, secondary (click) = undo, read with `MotionEvent.getButtonState()` (BUTTON_STYLUS_PRIMARY/SECONDARY, plus BUTTON_SECONDARY/TERTIARY for drivers in mouse mode) and from ACTION_BUTTON_PRESS/RELEASE in hover; the decision is the pure `PenButtons` (JVM test `PenButtonsTest`). The back of the pen (TOOL_TYPE_ERASER) draws with the erase brush and the previous mode is restored when the tip returns. Both come from the spec defaults: the assignments are **not configurable** yet, and this still needs a check on a real pen. Was: Stylus buttons (BUTTON_STYLUS_PRIMARY/SECONDARY) and the eraser end (TOOL_TYPE_ERASER) are unused | stylus settings: assign buttons (eyedropper, eraser, undo, custom tool), eraser → Erase mode. Verify on the device |
| 13 | ✅ A new canvas opens with the layers panel collapsed, and both the first and added layers take a localized default name from `R.string.layer_default_name` ("Layer N" / "Слой N"), handed to core as the `layerName` parameter of `Document.create`. Was: A new canvas opens with the layers panel expanded, and the name "Layer 1" is not localized | minor, M2 |
| 14 | ✅ Autosave 2 s after the last change (not during a stroke), verified with force-stop. Was: autosave only on leaving the studio and in `onStop`: if the process was killed (adb install, crash, system), the last strokes were lost (found on the emulator 2026-10-27) | debounced save ~2 s after each history change |
| 15 | ✅ The Recent and Gallery thumbnail draws the checkerboard only under the document area (the thumbnail is letterboxed by its own aspect ratio), and the card background fills the rest. Was: The preview of a wide canvas in Recent shows a checkerboard at the edges instead of the card background | cosmetic |
| 16 | ✅ Two-level menu: Symmetry, Guides, Perspective, Shapes. Was: the Creative tools menu (symmetry, guides, perspective, shapes) no longer fits the screen and has to be scrolled | group it into sections or a side panel like IP |
| 17 | ✅ The draft preview engine closes and fills the path itself: `StrokeEngine.previewFinish()` draws the closing segment and the closed-shape fill into the preview buffer (or the layer working set of a build-up brush) without committing, `end()` afterwards repeats neither, so the live preview matches Apply. JVM test `ShapePreviewTest`. Was: The live preview of a closed shape omits the closing segment and the fill; both appear only on Apply | render the closed path and fill in the preview engine (StrokeEngine closes/fills only in end()) |
| 18 | ✅ Shared ToggleChip (FilterChip with a check). Was: on/off toggles in the tool bars (shape Outline/Fill, guide toggles) look the same as plain action buttons | distinct on/off styling (filled chip or check) |
| 19 | ✅ The left toolbar shows the active tool and returns to the brush on tap. Was: after Apply the Shape tool (and Fill/Gradient) stays active, but the left toolbar still highlights the brush, so the next stroke unexpectedly draws a shape | show the active tool in the toolbar (or return to the brush after Apply, as a setting) |
| 20 | ✅ Opens on the current brush's category with it scrolled into view. Was: the brush browser always opens on the first category instead of the current brush's category; the category list no longer fits and needs scrolling | open on the current brush's category and scroll it into view |
| 21 | ✅ Not a bug: the colour had been picked by the eyedropper test from the paper and then from the anti-aliased end of a thin stroke (grey 119), so the oil strokes were near-white or grey. With the default colour oil round draws fully on the device, and a JVM probe with the app's context (point by point, 4 threads, calibration 0.78, pressure from 0.02) matches the sheet. Was: oil round seemed to fade a few dabs after the start of a stroke | — |
| 22 | ✅ Two tilt brushes: pencils.shading (tilt widens and lightens the stroke) and pens.calligraphy_tilt (the nib turns with the pen azimuth); checked with the virtual pen (`pen.py tiltramp`). Was: no catalog brush used tilt | — |
