# Combined feature map (IP ∪ ibis) → place in the IP UI

Source: `IP` = Infinite Painter only, `ibis` = ibisPaint only, `both` = present in both (we take the better version).
Priority:
- **P0**: MVP core;
- **P1**: parity with IP;
- **P2**: additions from ibis;
- **P3**: services and options.
Details in `features/<NN>/`.

## 01 Projects and gallery
| Feature | Src. | Where in UI | P |
|---|---|---|---|
| Project: canvas + layers + metadata, autosave, crash backup | both | — | P0 |
| Version history (1–10/∞, Restore = new project) | IP | Projects → long-press | P1 |
| Snapshot from timelapse | ibis | Playback → ⋮ | P2 |
| Gallery: folders (nested), drag, rename, duplicate, delete | both | Projects | P0 |
| Multi-select, trash, drawing time | ibis | Projects | P2 |
| Templates | IP | Projects | P2 |
| New Project: presets, units, dpi, orientation, paper, layer limit | both | New Project creator | P0 |
| Types: Regular, Pattern (½ tile), Challenge, **Animation**, **Manuscript** | IP+ibis | New Project creator | P1/P2 |
| From image: gallery, camera, files, clipboard (stock search optional) | both | Home | P1 |

## 02 UI and gestures
Shell: spec/UI-SHELL.md. Additions from ibis:
- cancel-stroke gesture;
- cursor pattern and crosshair;
- undo toast and sound;
- floating layer window.
- **P1/P2**.

## 03–04 Brushes
| Feature | Src. | Where | P |
|---|---|---|---|
| Stamp engine: head bitmap, spacing, angle, rotation (fixed/tangent), softness, flow, opacity | both | Brush Editor | P0 |
| Dynamics: pressure, velocity, tilt (curves) → size, flow, scatter, depth, dilution, opacity, blur | IP curves + ibis blur | Editor → Stroke/Head | P0 |
| Global calibration of pressure/velocity/tilt | both | Settings → Brush | P0 |
| Profiles along the length (size/flow) + force fade (start/end, smooth/linear) | both | Editor → Stroke | P1 |
| Jitter: size, angle, scatter, flow, spacing, opacity, HSB color | both | Editor | P1 |
| Texture: Fixed/Rotate/Warp, depth, scale, stretch, structure, softness, angle, lower limit, moving | both | Editor → Texture | P1 |
| Smudge: mix-in, dilution, pull, blur, wet, sample lower layers | IP (+ibis Water type) | Editor → Paint, Blend mode | P0 |
| Modes: Paint / Blend / Erase / Clone + separate Smudge and Blur brushes | both | Main toolbar | P0 |
| Brush blend mode + strength | both | Editor → Stroke | P1 |
| Wet edges, glaze strokes, adhesion, impasto (structure, depth) | IP | Editor | P1 |
| Watercolor effect (bleed, glaze, mix-in, dryout) | IP | Editor → Special | P2 |
| Screentone snap | IP | Special | P2 |
| Particles (attractors) | IP | Special | P2 |
| Scatter particles (density, deviation, size) | ibis | Editor → Jitter | P2 |
| **Double brush** (outline/shadow) | ibis | Editor → Type | P2 |
| **Color stamp brush** (base color, hue shift) | ibis | Editor → Type | P2 |
| Harmony (procedural lines) | IP | Brush library | P3 |
| Fill brushes | IP | Brush library | P1 |
| Brush-as-filter (filter in the stroke or under the stroke) | IP | Special | P2 |
| Opacity saturation / separate strokes / add opacity / MSAA / dots | ibis | Editor → Shape | P2 |
| Ruler-aware: bind to ellipse center, billboard in array | ibis | Editor → Shape | P3 |
| Barrel roll / stylus rotation, orient to screen | both | Editor → Head | P2 |
| Library: folders, favorites per mode, recent, created, downloads, hide, restore, search | both | Brush selector | P0 |
| Brush Creator (from a bitmap, from a selection), Brush Pattern editor (canvas) | both | Brush selector + | P1 |
| Brush and pack import/export (own format) + QR (opt.) | both | Brush options | P1/P3 |
| Stabilization: smoothness, lazy (radius, elastic), real-time constant + fast, after-stroke | both | Editor/Guides/Stabilizer | P0 |
| Our own library of built-in brushes across 16+ categories | — | — | P1 |

## 05 Color
| Feature | Src. | P |
|---|---|---|
| 16 bpc internal pipeline, gamma-correct blending (toggle) | IP | P0 (architecture) |
| HSB wheel (6 styles), sliders HSB/RGB/LAB/CMYK, hex | IP | P0/P1 |
| Opacity in the color | ibis | P2 |
| Tone lock, compare tone, complementary marker | IP | P2 |
| Palettes: collection, library, smart palette from an image/camera | both | P1 |
| Color history (up to 100, merging of similar colors) | both | P1 |
| Eyedropper: radius 1–100, all layers / current, quick (long-press, magnifier), refs, contents-layer jump | both | P0/P2 |
| Lighting orb | IP | P3 |
| Proofing: sRGB, CMYK, grayscale, line art, color-blind | IP | P2 |
| Document mode Color / Gray / BW | ibis | P2 |

## 06 Layers
| Feature | Src. | P |
|---|---|---|
| Raster layers, opacity, blend, hide, rename, duplicate, clear, merge, delete | both | P0 |
| Alpha lock, clipping (multi, groups) | both | P0 |
| Groups (nested, passthrough/normal, flatten, copy merged) | both | P0 |
| Layer masks (8-bit, linked, apply, invert, from selection) | IP | P1 |
| Adjustment layers (all adjustments + filters) | both | P1 |
| Filter layers (live) | IP | P1 |
| Text, Panels layers | IP | P1 |
| **Vector layer** (brush paths, editing of vertices and handles, simplify, vector eraser, convert, loops) | ibis | P2 |
| **Screentone layer property** | ibis | P2 |
| Paper layer (color, texture: depth, opacity, scale) + canvas papers | both | P1 |
| Visibility modes: Normal / Solo / Trace (progressive) | IP | P1 |
| Clear white (gray/color), invert layer, flip layer/canvas H/V, add from canvas/folder, export layer PNG | ibis | P1/P2 |
| Pinch-to-group, hide when recording | IP | P2 |
| Thumbnails: whole / contents | ibis | P3 |

## 07 Blend modes
- 33 modes (list in features/07/ibispaint.md, last section). P0: 12 basic. P1: the rest.

## 08 Text
| Feature | Src. | P |
|---|---|---|
| Text layer, frame with wrapping, snap guides | IP | P1 |
| TTF/OTF fonts, variable axes, missing-font handling | IP | P1 |
| Outline / outline-only, align, case, spacing, justify + scale-justify + balance | IP | P1/P2 |
| **Vertical text** | ibis | P2 |
| **Background plate + margin** | ibis | P2 |
| RTL, complex scripts (via HarfBuzz) | IP | P1 |

## 09 Fills
| Feature | Src. | P |
|---|---|---|
| Bucket: tolerance, live tolerance, multi/single/source layer | IP | P0 |
| Expansion ±px, gap recognition, unpainted recognition, boundary color/opacity, under line | ibis | P1 |
| Continuous filling, eraser bucket, surrounding fill/eraser (scrape) | ibis | P2 |
| Lasso fill / lasso eraser (+all layers) | ibis | P1 |
| Gradients: linear, radial, sweep, stops with opacity, repeat, reflect, reverse | IP | P1 |
| Pattern fill (tile, HSB/contrast/opacity) | IP | P2 |
| Fill brushes | IP | P1 |

## 10 Guides and rulers
| Feature | Src. | P |
|---|---|---|
| Lines (parallel/perpendicular/center), Ellipse (arcs, radii), Pen (Bézier) | IP | P1 |
| Hatching guide | IP | P2 |
| Lazy guide | IP | P1 |
| **Radial guide** (rays, multi-center) | ibis | P2 |
| **Array guide** (live duplication of a cell, perspective) | ibis | P2 |
| **Perspective array** | ibis | P3 |
| Straight-line mode of the stabilizer | ibis | P2 |

## 11 Shapes
| Feature | Src. | P |
|---|---|---|
| Line, Rect, Circle/Ellipse, Path, Arc, stamp | IP | P1 |
| Rounded rect, regular polygon (≤16), arrow, speech balloon | ibis | P2 |
| Fill/outline/brush-line options on a shape | ibis | P2 |
| Shape detection (hold), candidates, priorities, time | both | P1 |
| Shapes in perspective (3 planes) | IP | P2 |

## 12 Symmetry
- V/H (any angle, crop), Radial 2–16 + fan, Kaleido 2–16 + crop: IP, **P1**.
- Array symmetry: ibis, **P2**.

## 13 Perspective
- 1/2/3pt, curvilinear, isometric, 2D with snap and visibility: IP, **P1**.
- 2-level grid overlay: ibis, **P2**.

## 14 View
- Zoom, rotate, pan, fit, flip (+vertical), lock view, navigator (gray, flip), refs widgets, immersive: **P0/P1**.
- Pixelated zoom: ibis, **P2**.

## 15 Selections
| Feature | Src. | P |
|---|---|---|
| 8-bit mask, workspace, set/add/subtract, boolean with layers (intersect, diff) | both | P0/P1 |
| Lasso, Rect, Ellipse, Polyline, Path, Wand, Brush, Color range (multi-point, fuzziness) | both | P0/P1 |
| Expand/contract, feather, invert, select all, transform mask | both | P0 |
| Duplicate, isolate, copy merged, delete, cut/copy/paste (system clipboard), library clipboard, make brush | both | P0/P1 |
| Marching ants / overlay | IP | P1 |
| Persisting the selection between sessions | ibis | P2 |

## 16 Transforms
| Feature | Src. | P |
|---|---|---|
| Basic, anchor, distort (perspective), warp | IP | P0/P1 |
| Mesh N×M + smoothness | ibis | P2 |
| Multi-layer, fragment, mask | IP | P0 |
| Flip H/V, rotate 45°, stamp, unlock cage | both | P1 |
| Numeric input, 6 interpolations | ibis | P1 |
| Perspective repeat (inner/horizon/full) | ibis | P3 |
| **Virtual transformations** (lossless until an edit) | IP | P1 (architecture) |

## 17 Adjustments
- Brightness, Contrast, Exposure, Gamma, Saturation, Vibrance, Temperature, Tint, Hue: IP, **P1**.
- Curves RGBA: IP, **P1**.
- Levels, Color balance, HSL, Replace color: ibis, **P2**.
- Quick masks (focus, tilt-shift, tonal, saturation): IP, **P2**.

## 18 Filters
- ~110 combined (list in features/18/*).
- **P1**: blur/sharpen/noise/pixelate/edges/halftone/gradient map/motion.
- **P2**: style effects, draw generators, artistic.
- **P3**: smart (AI).

## 19 Liquify
- Push, pinch, bloat, swirl, restore, rewind, strength, smoothen + symmetry: **P1**.

## 20 Clone
- Photo / artistic / smart, offset, align, source layer: IP, **P1/P3**.
- Copy zoom, angle jitter: ibis, **P2**.

## 21 Patterns, materials, tones
- Pattern tools (symmetry/path/quilt/tile): IP, **P2**.
- Material library (local): ibis, **P2**.
- Screentone layer: ibis, **P2**.

## 22 Canvas
- Crop (non-destructive), resize (content, canvas, anchor), straighten: IP, **P1**.
- Resize in mm+dpi with interpolation, color mode: ibis, **P1/P2**.
- Panels + frame divider (margins, gutters, fill of panels): both, **P2**.

## 23 Import/export
- PNG/JPG/WebP: **P0**.
- PSD (layers, masks, adjustment, flattened) in/out: **P1**.
- ZIP of layers: **P2**.
- Own project format: **P0**.
- CMYK PSD + ICC/intent/BPC: **P2**.
- Gray 8 / mono 1-bit: **P2**.
- Assets (brush, pack, palette, settings): **P1**.
- Super-resolution, AI-disturbance: **P3**.

## 24 Timelapse and animation
- Timelapse (regular / follow navigation, hide layer, export durations, statistics): IP, **P1**.
- Frame animation (fps, duration, onion skin, loop/pingpong/once): ibis, **P2**.
- Video mode (tracks, audio, 60 fps, MOV/MP4): ibis, **P3**.

## 25 Input and settings
- Pressure, tilt, velocity, palm rejection 4 modes, touch offset, stylus buttons/squeeze/barrel, low latency/prediction, coalesced touches, shortcuts (+custom), volume keys: **P0–P2**.

## 26 Manga
- Manuscript type (bleed/trim/safe, presets), color mode, vertical text, balloons, speed/radial lines, manga background: ibis, **P2**.

## 27 Online, AI
- Cloud, gallery: **outside the core**, P3, separate decision.
- Challenges: IP, P3.
