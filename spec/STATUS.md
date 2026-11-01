# Status

## Rebrand: Boundless Paint (2026-10-31, 0.4.5)
- The app is now **Boundless Paint** (label, settings/about, in-app update copy, README, Gradle root project and
  GitHub repository). The Android package stays `app.painter` so existing projects and signing keys continue to
  work.
- New original launcher icon: `docs/icon.png` (master) plus legacy and adaptive mipmaps in `app/src/main/res`;
  the icon is a gradient lemniscate brush stroke with paint flecks on a dark blue rounded square.
- Release assets are named `boundless-paint-<version>.apk`; the updater reads the renamed
  `IWatman/Boundless-Paint` repository.

## M5: in progress (2026-11-01)
> QA closeout: [QA-M5.md](QA-M5.md) — feature evidence, the XP-Pen device matrix and the M5 exit criteria
> (2026-11-02). Hardware rows, 4K, audio drift and throughput are still pending.

### Localization cleanup (2026-11-01)
- The static UI strings in `app/src/main/kotlin` now come from resources: export toasts, HUD labels, default
  project and brush names, brush-import counts, the stroke out-of-memory error and the CMYK TIFF label. The
  Russian wording was fixed for Lighten, Luminosity and Screentone, and brush-import counts use a proper
  plural.
- `tools/check_localization.py` compares `values/strings.xml` with `values-ru/strings.xml`: missing and extra
  names, empty translations, format placeholders and Russian plural categories. It exits non-zero on a
  mismatch. At the 2026-11-01 cleanup: **797 EN resources, 796 RU**; the only difference was the non-translatable
  `app_name`. Recheck on 2026-11-02: **891 EN (1 non-translatable), 890 RU**, 0 missing and 0 extra; see
  [QA-M5.md](QA-M5.md).

### Pattern fill (2026-11-01)
- Core: `core/fill/PatternFill.kt` generates twelve seamless procedural patterns (checks, dots, diagonal
  stripes, brick, herringbone, canvas grain, zigzag, waves, triangles, stars, hexagons, wood grain) and fills a
  layer through the selection with alpha lock, reusing `maskedPatch`/`TilePatch`. The pattern cell rotates with
  its repeat lattice, so rotated fills stay seamless. Coverage blends a background and an ink colour, with a
  phase offset in document pixels, a contrast curve around 0.5 and an invert switch. Offsets are clamped to
  ±256 px; every parameter keeps the periodic lattice.
- UI: Editing Tools → Pattern fill… opens a compact dialog with twelve live previews, two colour swatches backed
  by HSB sliders, tile size, rotation, offset X/Y, contrast, invert and opacity; the fill runs on a worker and
  lands as one undo step.
- Tests: `PatternFillTest` (22) — wrap at tile edges and along the rotated lattice for every pattern, determinism
  with every control, scale and rotation, phase shift, clamped range, contrast curve and rendered direction,
  two-colour blend and invert on every pattern, opacity, selection clipping, alpha lock, locked layers, undo/redo
  and visible-shape checks for each new pattern (fold, antiphase waves, triangle orientation, five star tips,
  hexagon lattice gaps and wood grain bands).

### Materials library (2026-11-02)
- Core: `core/material/MaterialStamp.kt` generates eight procedural stamps — balloon (soft satin sphere), fabric
  (plain weave), wood (grain), metal (brushed plate), stone (speckled slab), foliage (leaf cluster), sparkle
  (four-point stars with a faint glow) and paper (fibrous deckled sheet). Analytic shapes and deterministic value
  noise produce a coverage and tone pair; the stamp is tinted with the current colour and composited over the
  active layer through the selection with `maskedPatch`, so alpha lock and locked layers behave like the fill
  tools. Size is clamped to 32..2048 px and the stamp is placed at the selection centre, or the canvas centre
  when there is no selection.
- UI: Editing Tools → Materials… shows eight live previews in the current colour and opacity, with size, rotation,
  opacity and spacing sliders; **Stamp** places one stamp at the selection/canvas centre, **Paint** switches to a
  material tool that repeats stamps along a drag at stable spacing and commits the whole gesture as one undo step.
  No drag placement of a single preview, import or library persistence yet.
- Tests: `MaterialStampTest` (14) and `MaterialStrokeTest` (11) — determinism for every material, clamped controls,
  visible footprints and distinct textures, size/rotation/opacity/spacing, selection clipping and centring,
  alpha lock, locked layers, tap vs drag, one undo/redo per stroke and no stamps outside the canvas.
- Persistence: `MaterialPrefsCodec` + `ToolPrefs` keep the last kind, size, rotation, opacity, spacing and
  Paint/Stamp mode, plus a favourites set and the last four used materials, across app restarts; values are
  clamped on restore. `MaterialPrefsCodecTest` (13) covers parsing, favourites, recents and clamping.
- M5 now: **755 JVM tests** (648 core, 107 app).

### First filter batch (2026-11-01)
- Core: ten more destructive filters — smooth (edge-preserving), lens blur, high-pass, frosted glass/scatter,
  grain (mono/rgb/alpha), local contrast, bokeh, lens flare, god rays and cross filter. Parameters live in
  `FilterParamsM5.kt` and join the sealed `FilterParams` family, so `FilterCodec` and live filter layers keep
  working unchanged.
- Tiling: aperture gathers use the padded patch, light filters work in canvas coordinates, scatter uses
  canvas-aligned value noise, and the two-pass blur behind smooth/high-pass/local contrast has a reach of `2r`;
  every filter is deterministic and seam-free across 256 px tiles.
- UI: Editing Tools → Filters… has a chip for each new filter with only its controls (radius/distance/size,
  mono/rgb/alpha grain, centre X/Y, length, threshold, rays, blade angle and highlight boost) plus the common
  strength. Every control changes the result.
- Tests: `FiltersM5Test` (8) — determinism and visible change, every control, cross-tile reads, boundary
  continuity probe, selection and alpha lock, grain variants and `FilterCodec` round trips.

### Second filter batch (2026-11-01)
- Core: eleven geometric and stylization filters — sphere lens, wave, ripple, twirl, polar coordinates,
  expansion, kaleidoscope, crystallize, hexagon pixelate, triangle pixelate and pointillize. Global warps read
  one immutable full-content snapshot (`needsFullSource`/`needsStableSource`), and cell filters use canvas-aligned
  cell ids with padded reads, so output does not depend on tile ownership or thread scheduling.
- UI: a chip and relevant controls for each new filter, plus the common strength; `FilterCodec` still serializes
  every parameter class.
- Tests: `FiltersM5GeometryTest` (11) — determinism, visible change, thread independence, selection, alpha lock,
  tile seams, cross-tile shape reads, control effects, pointillize background and codec round trips.

### Third filter batch (2026-11-01)
- Core: eleven stylization and material filters — gloom, sketch edges, trace, bevel inner/outer/both, emboss,
  relief, satin, wet edge, waterdrop, extrude and clouds. Neighbourhood filters take a stable source snapshot so
  live filter layers are thread-independent; clouds is a canvas-coordinate generator and waterdrop reads the full
  content for its content-sized lens.
- UI: a chip and only the relevant controls for each filter, including bevel variant, highlight/shadow colours,
  satin colour, cloud seed and two-colour clouds; the common strength stays.
- Tests: `FiltersM5StyleTest` (15) — determinism, visible change, live/history thread independence, selection,
  alpha lock, premultiplied invariant, tile boundaries, cross-tile reads, 34 control-pair effects and codec
  round trips.

### Fourth filter batch (2026-11-01)
- Core: twelve artistic and generative filters — palette posterization, QR code (dependency-free encoder),
  stained glass, dots, 1-bit stamp, glitch, retro-game palettes with dither, chrome, sheer, impressionist,
  swirl painting and pop art. Palette sampling uses a full-content LUT; the QR generator covers the canvas and
  is centred with a quiet zone.
- UI: a chip and only the relevant controls for each filter, including QR text, palettes, colours, seeds and
  dot scales; the common strength stays.
- Tests: `FiltersM5ArtisticTest` (15) — determinism, visible change, live/history thread independence, selection,
  alpha lock, premultiplied output, tile boundaries, QR module alignment across tiles, palette/dot/retro limits,
  1-bit stamp output, every control and codec round trips.
- M5 so far: **677 JVM tests** (577 core, 100 app).
- Video mode design: [VIDEO-MODE.md](VIDEO-MODE.md) — data model, UI, export/audio pipeline and five-slice plan;
  S1 is model + persistence + read-only timeline.
- Performance baseline: [PERFORMANCE-M5.md](PERFORMANCE-M5.md) — desktop JVM A–E numbers, 4K/filter probes and
  open risks; device measurements are still missing.

### Fifth filter batch (2026-11-01)
- Core: the last eight filters — manga tone from the image, manga background, anime background, pencil sketch,
  oil paint (Kuwahara), blur frame, table frame and rain. The manga tones average a canvas-aligned cell and draw a
  dot screen with Sobel ink; pencil sketch draws two canvas-anchored hatch families plus contours; the frames use
  the content bounds; rain is a canvas-coordinate generator that covers the canvas or the selection.
- Tiling: cell, neighbourhood and blur filters declare a stable pre-filter source, canvas-anchored phases and a
  reach that covers every read, so history and live filter layers are seam-free and frame-rate independent.
- UI: a chip and only the relevant controls for each filter (brightness/edge/tone size, contrast/edge darkness,
  anime colour controls and posterize, line length, oil radius/levels, frame width/blur/corner radius/colour,
  rain density/angle/length/opacity/seed) plus the common strength.
- Tests: `FiltersM5FinalTest` (16) — determinism, visible change, history and live thread independence, selection,
  alpha lock, premultiplied output, seams on both tile axes, greyscale ink/paper, posterize and oil level limits,
  frame behaviour, sparse rain, every control and codec round trips.

### Video mode S1 (2026-11-01)
- Core: `VideoMeta`/`VideoTrack`/`VideoClip`/`VideoAudio`/`AudioClip` with a 60 Hz integer tick model, conversion
  from `AnimationMeta` (loop/once/ping-pong expansion and fps rounding) and manifest DTOs at schema 2. Existing
  projects without `video` load unchanged.
- Persistence: `ProjectIO` reads/writes the video block and normalizes it against the document frames; `Snapshot`
  copies `video`, so incremental autosave and undo snapshots keep it.
- UI: Options → Video mode… converts an animation (or explains why it cannot) and opens a read-only timeline with
  duration, ruler, tracks/clips, playhead, seeking and clip highlight; the Animation panel has a Video action.
  Playback, clip editing, export and audio are later slices and stay absent rather than faked.
- Tests: `VideoTest` (13), `VideoProjectIOTest` (7), `SnapshotTest` (4, two new for video and autosave delta).

### Video mode S2 (2026-11-01)
- Core: `VideoEdit` copy-on-write operations (add after selection or at playhead, blank, delete, right-edge trim
  with zero deleting, ripple move, track visibility, snap edges) keep the single track sorted, butt-jointed and
  non-overlapping. `VideoPlaybackCursor` owns the 60 Hz tick math, looping, end clamping and scrub clamping.
- Preview: a 60 Hz coroutine clock drives play/pause/stop/scrub and shows the clip frame through
  `Video.visibleFrames`; painting, undo/redo, filters, saving, panel close, animation switch, studio exit and
  document switch stop preview and restore the editing frame as specified. Playback adds no history entry, no
  timelapse frame and no dirty flag.
- UI: wired transport, add/blank/delete, snap toggle, ruler scrubbing, clip selection, long-press move and
  right-edge trim; no fake inspector fields.
- Tests: `VideoEditTest` (10), `VideoPlaybackCursorTest` (6), `VideoTimelineTest` (5).
- Pattern fill now ships twelve seamless patterns (the six M5 additions are zigzag, waves, triangles, stars,
  hexagons and wood grain); `PatternFillTest` (22) covers their lattice wrap, rotations, offsets and shapes.
- M5 now: **741 JVM tests** (634 core, 107 app). All filters in [filters.md](filters.md) are implemented.

### Video mode S3 (2026-11-02)
- Export plan: `core/video/VideoExportPlan` maps the canvas aspect to 720p/1080p/1440p long sides, caps the area at
  8,294,400 px, rounds to the encoder alignment, applies the 8/16/24 Mbps table and decides each preset against
  a `VideoEncoderCaps` probe before the file picker opens.
- Color: `core/video/Yuv` converts straight RGB to BT.709 limited-range I420 and `I420Frame` writes luma/chroma
  planes with configurable row strides. The existing BT.601 timelapse encoder is unchanged.
- Rendering/export: `Compositor.flatten` takes an optional visibility override, so the export renders each unique
  tick without touching node flags. `VideoExporter` encodes H.264 at 60 fps with `MediaCodec`/`MediaMuxer` to a
  cache temp file; the ViewModel streams that file to the SAF URI in 1 MiB chunks, reports frame progress, cancels
  at frame boundaries and deletes the temp file on every path. No audio track, no 4K, no document/history change.
- UI: a real export action in the video panel opens the MP4/size dialog with capability-disabled chips, missing
  frame and size-estimate notes, then the modal progress/result dialog with Cancel/Close only.
- Tests: `VideoExportPlanTest` (13), `YuvTest` (8), `CompositorTest` +1 for the visibility override, and
  instrumented `VideoExporterTest` (3 on the `painter_tab` AVD): 1 s/60 access units with the document flags
  unchanged, 5 s/300 frames with a one-frame duration tolerance, and cancel deleting the temp file.
- Not measured: 1080p60 throughput on the reference tablet. The export renders and encodes on the CPU and may
  take longer than real time; no performance number is claimed.
- M5 now: **777 JVM tests** (670 core, 107 app); instrumented export tests green on the emulator.

### Video mode S4 (2026-11-02)
- Audio model: `AudioTimeline` normalizes clip placement, move/trim/delete, clip volume, track mute/volume and
  master volume; `AudioMix` maps 48 kHz stereo samples, loops long clips, applies clip x track x master gain,
  clamps and hashes the cache key from every audio and duration input.
- Import/record: SAF import copies into the project `audio/` folder, probes the codec and rejects unsupported
  files with the codec name; recording uses `RECORD_AUDIO` (permission/denial handled) and inserts at the playhead.
- Mix/export/preview: `AudioMixer` decodes, resamples, mixes and encodes AAC-LC 48 kHz stereo into an M4A cache;
  `VideoExporter` muxes `audio/mp4a-latm` with the video track, truncates beyond the video end and omits the track
  when muted/absent. `AudioPreviewPlayer` plays the same cached mix from the playhead and follows transport/mute.
- UI: audio lane with import/record/delete, clip/track/master volume, mute and move/trim; the export dialog has an
  Include audio switch with disabled reasons and a size estimate.
- Persistence: audio files are relative project files, survive `saveDelta` and are included in `.pnt` archives.
- Tests: `AudioMixTest`, `AudioTimelineTest`, `VideoProjectIOTest` additions; instrumented `AudioMixerTest`,
  `AudioPreviewPlayerTest`, `ProjectAudioTest` and `VideoExporterAudioTest` (8/8 green on the `painter_tab` AVD).
- Not measured: audible A/V drift and 1080p60 throughput on hardware; recording itself was not instrumented.
- M5 now: **810 JVM tests** (703 core, 107 app).

### Video mode S5 (2026-11-02)
- 4K: `VideoExportPlan` now offers 720p/1080p/1440p plus 4K. The 4K chip is 60 fps when the selected encoder
  advertises that exact size and rate, otherwise the explicit 4K/30 fallback; when neither works the 4K/60 chip
  is disabled with the encoder reason. Bitrate stays 40 Mbps at 4K/60 and 4K/30. The dialog shows the frame
  count at the selected rate, so there is no silent fps change.
- Memory guard: `VideoExportPlan.estimatedPeakBytes` budgets two I420 buffers, a full-canvas RGBA16F grid and
  48 MiB of headroom; 4K is refused when that exceeds half of `ActivityManager.largeMemoryClass`. The 4K
  estimate is 141,570,048 bytes (135 MiB), so a 256 MB large heap is refused and 512 MB is accepted. The dialog
  disables the chip with the estimate and heap size; `StudioViewModel` rechecks before the file picker.
- Frame cache: the exporter keeps exactly one reusable I420 frame, so 4K stays at 11.9 MiB for the rasterizer
  buffer and does not gain a second frame cache.
- Layer limit: the export dialog derives `LayerLimit.maxLayers` from the selected output size and the 40% heap
  budget (6 layers at 4K/512 MB, 24 at 1080p/512 MB) and explains the limit when the timeline references more
  frame layers. The current canvas limit is checked separately and shown as an error when exceeded.
- Second drawing track: **shipped**. The timeline adds a second track with its own frame group, one frame and an
  initial one-second clip, shows both lanes, edits clips on the selected track, toggles visibility per track and
  deletes a track without destroying its frame layers (the group and frames stay in the layer list, hidden). The
  animation panel selects the editing track and restores that track's current frame; painting during video
  preview selects the painted frame's track. Track add/delete is timeline metadata and is not undoable. The UI
  caps creation at two tracks; the model, compositor and exporter remain N-track and composite every visible
  track.
- Tests: `VideoExportPlanTest` (19, matrix/4K30/memory/pixel cap), `VideoTest` multi-track visibility,
  `VideoEditTest` +5 (add/delete track, per-track clip edits, two-track cap/normalization, two-track visibility),
  `VideoProjectIOTest` +2 (two-track round-trip/saveDelta, one-track manifest) and `SnapshotTest` +1 (two
  tracks); `LayerLimitTest` +1 derived 4K/1080p limits; instrumented `VideoExporter4KTest` runs only when the
  selected encoder advertises 4K and the heap passes the guard, otherwise it is skipped. The `painter_tab` AVD
  skipped it, so no 4K hardware result is claimed.
- Not measured: 4K/60 and 4K/30 throughput on the reference tablet; the instrumented 4K smoke is a capability
  check, not a benchmark. The 1080p60 timing remains unmeasured.
- M5 now: **839 JVM tests** (730 core, 109 app).

## M3: done (2026-10-31)
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
- Verified on `painter_tab` (2026-10-31): blur filter layer replaces the lower strokes live, crop dialog/apply,
  settings sheet, PSD export and import (the Home picker now accepts any document and the parser validates the
  header), and a timelapse export whose MP4 is H.264 720p (720×404), 30 fps, 29.97 s.
- Tests: **566 JVM tests, all green** (493 core, 73 app).

### Stability and control markers (2026-10-31, 0.3.7)
- Fixed the ANR found on `painter_tab`: the stylus-up stack ended in `OutOfMemoryError` in `TilePool.obtain` from
  `StrokeEngine.compositeBuffer`. The undo history budget was 512 MB on a 450 MB heap, the float-tile pool kept
  96 MB, and a live filter layer recomputed its full-grid cache with before/after snapshots after every change.
  History is now capped at a quarter of the process heap, the pool at 32 tiles, filter layers composite in place
  with `Filters.applyToGrid` (no history copies), and an allocation failure rolls the stroke back with a message
  instead of blocking the main thread. Watercolour repaints its preview at ~30 Hz, catch-up ticks are capped at 3,
  and the pen-up dry loop at 1.2 s.
- Brush editor controls with no engine behind them are disabled and marked with a small construction badge:
  watercolour mix-in, double-brush body colour and texture-on-shadow, unsupported harmony variants (only Sketchy,
  Shaded and Web have their own rules), and fill pattern/procedural (solid only).
- Re-checked after the fix: 29 fast strokes on the emulator, Dalvik heap stabilises around 227 MB of the 450 MB
  cap, no new ANR or `OutOfMemoryError`.

### Brush coverage audit (2026-10-31, 0.3.9)
- `BrushCoverageTest` (androidTest, emulator) renders **every one of the 91 stock presets** through the real
  `StrokeEngine` and `BrushLibrary` resources on a gradient + 8 px checkerboard, counts changed pixels and logs
  `BrushCoverage|category|name|changed=N`. All 91 changed pixels; there are no dead catalog brushes.
- `Blur, soft` looked like a no-op on the first linear-gradient probe (a symmetric blur leaves a linear gradient
  unchanged); the checkerboard pass changes ~17k pixels, so the brush works.
- `Dust` was technically drawing but too faint: opacity 0.6→0.82, flow 0.6→0.7, particle count 200→240 and
  sprite size 0.09→0.14 in `tools/catalog/particles.py`; regenerated presets (`gen_assets.py --check` OK), it now
  covers ~22k pixels in the audit and is visible in the UI.
- Watercolour preview now writes only the pigment bounding rectangle of each tile (plus the bilinear border)
  instead of walking all 256×256 pixels; the previous rectangle is included once so drying pixels are cleared.
  The local cell-to-pixel conversion was also fixed so wet tiles after the first one write their pigment.
  `StrokeBench` E: **3.6 ms mean** (was 10.0 at 0.3.6, 5.5 at 0.3.7), max ≈60 ms (the pen-up dry loop).
  Wet wash checked on `painter_tab`: a 1000 px stroke renders with its dark core and wet halo.

## M4: done (2026-11-01, 0.5.0)
### Shape toolbox (2026-10-31, 0.4.0)
- New Shape tools: **Rounded rectangle**, **Arrow** and **Speech balloon** alongside line/rect/ellipse/polygon/arc.
  Outlines are closed polylines rendered through the current brush; move/rotate/resize handles work for the new
  shapes as well. `ShapeTest` covers creation, closure and the tail geometry.
- Selection content actions: **Fill selection**, **Erase selection** and **Fill outside selection** in Editing
  Tools when a selection is active. The surrounding fill renders an inverted selection mask without intersecting
  it with the active selection; `SelectTest` covers fill and erase keeping the selected island.
- **Lasso fill** and **Lasso erase** tools: drag a freehand contour, release, and the enclosed area is filled
  or cleared in one undo step (`LassoFill` + the direct tools).
- **Screentone** filter: dot/line/cross patterns on a configurable cell grid, angle and ink colour; usable on an
  empty layer as a tone layer or over existing content.
- **Manuscript guides**: bleed (3%), trim (5%) and safe-area (10%) overlays, toggled from Options. They follow
  the canvas transform like the other overlays.
- **CMYK export**: 8-bit uncompressed CMYK TIFF, four inks, paper composited; available as the third export
  format. `TiffCmyk` + `TiffCmykTest` cover the container and black/white conversion.
- Radial and array ruler overlays: 24 rays from the canvas centre, and a 3x3 thirds grid with an outer frame;
  both follow the canvas transform and are toggled in Options.
- **Vector layers**: `VectorLayer` keeps editable brush paths beside the raster cache used by the existing
  compositor/renderer. Paint/erase strokes append a path and replay deterministically; `VectorStrokeCommand` restores
  paths and pixels together, the layer panel has "Add vector layer" and a `V` badge. Paths persist in the manifest
  (`kind: "vector"`) and are re-rendered on load. See `docs/vector-layers.md`.
- **Frame animation**: `Document.animation` stores fps, loop/pingpong/once, onion settings and the frame-group id;
  frames are raster layers inside a dedicated group with one visible at a time. Options → Animation opens a
  timeline with thumbnails, add/duplicate/delete, prev/next, play/stop, fps, loop mode and onion before/after.
  Onion skin is a cached Compose bitmap overlay (previous frames red, next blue). Animation metadata persists in
  the manifest; non-animation documents are unchanged.
- **Google Drive backup and drawing export**: in-app Google Play services `AuthorizationClient` for the
  `drive.file` scope (no client id, secret, redirect URI or OAuth token is stored by the app). The Drive sheet is
  reachable from Home and Settings: connect/disconnect, back up the current project or every project, save the
  current drawing as PNG, and restore any backup. The app creates a visible `Boundless Paint` tree with
  `Projects` (one `.pnt` archive per project, project name in Drive app properties) and `Drawings` (timestamped
  PNG files); reconnecting searches for the existing folders by name and parent and only creates the missing
  ones (the oldest match wins, so a duplicate never splits the backups). A restore keeps the project id when it
  is free so later syncs update the same remote file, and otherwise creates a copy.
  `ProjectStore.importArchive` rewrites the manifest id/name; `DriveApiTest` and `DriveRepositoryTest` cover
  folders, reconnect reuse, upload/download, 401 retry, account linking and sign-out. Setup is in
  `docs/google-drive.md`.
- **Auto-sync after saving**: **Sync after saving** in the Drive sheet (on by default once connected, stored in
  `DriveSettings` independently of the account) backs the open project up after an explicit Save or leaving the
  studio. Autosave, undo and project switches do not upload; an upload in flight is not duplicated, and disconnect
  cancels a pending one.
- **Share image**: Options → Share image flattens the current frame onto the paper, writes a PNG to
  `cacheDir/share` and opens the Android share sheet with a read-only `FileProvider` URI. The receiving app needs
  no storage permission; the share name is sanitized, and the cache keeps only the current image.
- Drive was exercised end to end on `painter_tab` with the test Google account: Play services account chooser and
  consent, visible `Boundless Paint/Projects` and `Drawings` folders, back up all/current, PNG save, restore as
  `Untitled (Drive)`, disconnect/reconnect, and the cancelled-sign-in path. A rejected cached token is cleared
  with `GoogleAuthUtil.clearToken` and re-authorized; disconnect clears only the local link. A reconnect found
  the existing folder ids unchanged (`1vp-…`, `1viK…`, `15Hh…`) and created nothing. **Share image** was checked
  on `painter_tab`: the system chooser listed Drive, Print, Messages, Nearby Share and Maps, and the print
  spooler rendered the 2560×1432 opaque PNG from the cache. M4 tests: **611 JVM tests** (511 core, 100 app).

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
