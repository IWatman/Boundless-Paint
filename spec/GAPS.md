# Gaps → answers

Status: **all items closed** (2026-10-26).
- For section B we do not measure the originals: we make the decision ourselves (see DECISIONS.md "Section B"). Therefore every item has our answer instead of a measurement.
- Where it says "in spec/X", the answer is written out there in detail.

## A. Architectural decisions
| # | Question | Answer |
|---|---|---|
| A1 | Platform and stack | Android only. Kotlin + Jetpack Compose + OpenGL ES 3.2. minSdk 29. Other platforms later |
| A2 | Render pipeline | 256² RGBA16F premultiplied tiles, sRGB encoding, gamma-correct toggle (default off). spec/blend-and-color.md |
| A3 | Document model | Tiles + command-undo with tile snapshots, 100 steps / 512 MB. Virtual transform. spec/document-model.md |
| A4 | Vector layer | A list of path objects (points + pressure + reference to brush parameters), rendered by the stamp engine. In M4 |
| A5 | Animation | A frame = its own layer stack + optional shared background. In M4 |
| A6 | Multi-page | None. One page = project |
| A7 | Own formats | ZIP containers + JSON. spec/file-formats.md |
| A8 | Compatibility | PNG/JPG/WebP (M1), PSD in/out (M3), ORA (M5). No ABR |
| A9 | AI | After v1 |
| A10 | Online | Local + sync with Google Drive (M4). No galleries or community |
| A11 | Languages | RU + EN |
| A12 | Monetization | None. Everything free |

## B. Numbers and behavior (our decisions)
### Brushes
| Item | Answer |
|---|---|
| Parameter ranges and defaults | Tables in spec/brush-engine.md §2 |
| Default dynamics curves | pressure→size: linear, min 20%. pressure→flow: off. velocity, tilt: off. Brush presets override |
| Tilt offset | The dab center shifts along the azimuth by `tilt·offset·size/2` (offset −100..100) |
| Texture gradation | Texture depth is multiplied by a linear falloff along the tilt axis: `1 − g·t`, t ∈ [0,1] from tip to tail |
| Adhesion | A stroke "load" buffer L (0..1) accumulates dab coverage. Visible alpha = `smoothstep(a, 1, L)`, a = adhesion |
| Structure (impasto) | Normal from the gradient of the dab mask (and the accumulated stroke height) × structure. Lambert with light from the upper left, brightness modulation ±25% |
| Depth (bristle) | The mask as a height map: `α = sat((h − d·(1−p)) / (1 − d·(1−p)))`, d = depth, p = pressure |
| Wet edges | `α' = α·(1 − w·(1 − e))`, where `e = sat((α − blur₄(α))·4 + 0.5)`. Edges are denser than the middle |
| Glaze strokes | Within a stroke, dabs are combined by max (without accumulation), strength = glaze |
| Mix-in / Dilution / Pull / Blur | Color reservoir R. Per dab: S = average of the canvas under the dab (sample radius = blur). `R = lerp(R, S, 1 − pull)`. `paint = lerp(paint, S, mix_in)` (constantly). Deposited color = `lerp(paint, R, dilution)`. wet = also transfer the alpha of S |
| Watercolor | GPU simulation on a wet buffer (water W, pigment P), 30 Hz while W > 0. Diffusion of P ∝ W·bleed, evaporation W −= dryout. Edge darkening ∝ glaze. Mixing in of the lower layer ∝ mix_in |
| Harmony | Our own implementation: at each point we connect it with lines to previous points within radius r, with probability p and alpha ∝ (1 − dist/r). Variants are defined by the set (r, p, offset, length). No third-party code |
| Particles | Attractors on a spring to the path point with an offset of radius. Particles on a spring to the attractors with damping, overshoot = inertia coefficient |
| ibis Water / Double / Color | Water → mix_in + dilution + pull. Double → special.double. Color → head_color + base_color hue-shift: `hue += hue(cur) − hue(base)`, S and V ≤ the originals |
| Stabilizer | Real-time: EMA, `α = 1 − 0.95·s/100`. Speed-based: `α /= 1 + k·speed`. After: Gaussian smoothing over the points, σ = after·0.5 px·N, then redraw of the buffer. Lazy: rope radius |
| Fill brush + shapes | The shape outline = the fill polygon, filled even-odd |

### Layers and color
| Item | Answer |
|---|---|
| Negative, Invert | spec/blend-and-color.md: negative = `1−|1−Cb−Cs|`, invert = `1−Cb` with strength αs |
| Trace mode | Lower layers: opacity multiplier `0.5^k` (k = 1 for the adjacent one), minimum 0.1. Upper layers: 0.25. Paper unchanged |
| Layer limit | `max = floor(budget / (w·h·8·0.5))`, budget = 40% of `ActivityManager.memoryClass`·MB for the GPU+CPU cache. Shown in New Project |
| Adjustment layers | 11 types: Brightness, Contrast, Exposure, Gamma, Saturation, Vibrance, Temperature, Tint, Hue, Curves, **Levels** + all filters marked `live` in spec/filters.md |
| Screentone layer | Our own patterns: dot (circle), line, cross, square, diamond, noise. LPI 20–120 (default 60), angle 0–180 (default 45). The size is in physical units from dpi, so it is recomputed on resize. A set of ≈ 40 presets |

### Fills and selections
| Item | Answer |
|---|---|
| Tolerance metric | `max(|ΔR|,|ΔG|,|ΔB|,|ΔA|)` on unpremultiplied values (like "chebyshev"). Threshold = tolerance%. Mode boundary=opacity: only `|ΔA|` |
| Gap recognition | Closing gaps up to N px (setting 0–20, default 4): morphological closing of the boundary mask before flood fill, then expansion of the result back to the lines |
| Unpainted recognition | Pixels with line α < 0.5 are considered fillable. Expansion under the line by expansion px |
| Color range | range = hard distance threshold, fuzziness = width of the soft falloff after the threshold. Up to 5 points, combined by max |
| Expand / contract | Distance transform, 0–100 px (not 6) |

### Transforms and deformations
| Item | Answer |
|---|---|
| Warp / Mesh | One model: a B-spline grid of N×M control points (Warp = 4×4 points, 3×3 cells; Mesh = up to 16×16). Rendering: tessellation into triangles on the GPU |
| Liquify | Falloff `(1 − r²)²`, strength 0–100 (default 50), pressure multiplies the strength. Displacement field on a 1/4 resolution grid, bilinear |
| Virtual transform | Kept through: affine, mesh, flip, rotate 45, canvas resize/crop (as offset). Committed on any pixel edit of the layer, filter, merge, liquify |

### Filters
- The full list of ~110 with our parameters, ranges, defaults and a `live` marker: **spec/filters.md**.

### Shapes and guides
| Item | Answer |
|---|---|
| Recognition | Hold for 0.6 s (setting 0.1–5). Candidates: line, arc, polyline, smooth curve, triangle, rect, quad, ellipse, regular polygon. The first = the best by approximation error with priority weights. The others are shown as icons |
| Pen / Path | Cubic Bézier. Smooth node = automatic tangents (Catmull-Rom, tension 0.5), cusp = corner. Handles are editable (smooth / aligned / free as in ibis) |

### Input
| Item | Answer |
|---|---|
| Prediction | `androidx.input.motionprediction.MotionEventPredictor` (1 frame), drawn into a temporary buffer and replaced by the real points |
| Palm rejection | None / Stylus draws only (finger = navigation) / Hover (when there are stylus hover events, fingers are ignored entirely) / Strict (stylus only everywhere). Stylus detection: `TOOL_TYPE_STYLUS` |

### Timelapse and animation
| Item | Answer |
|---|---|
| Timelapse | Frame capture per command, ≤ 30/s. Export H.264 MP4 (MediaCodec), 30 fps, 8 Mbit/s @1080p, 20 @4K. Durations: 15 / 30 / 60 s / full. Quality: 720p / 1080p / 4K |
| Animation limits | Frames ≤ 999, fps 1–60 (default 12), onion skin 0–5 in each direction, play loop / pingpong / once |

## C. Assets
| Item | Answer |
|---|---|
| Brushes | Our own presets in 16 categories (spec/assets.md). Heads and textures are generated procedurally by the script `tools/gen_assets.py` (.venv: numpy + pillow) |
| Papers, patterns, tones, palettes | The same generator + a few CC0. We compose the palettes ourselves |
| Fonts | Google Fonts with an OFL/Apache license, license files next to them. The list is in spec/assets.md |
| Materials | v1: our own minimal set (balloons, fabrics) as vectors and procedurally |
| Icons | Material Symbols (Apache 2.0) + our own |

## D. Documents
| # | Document | Status |
|---|---|---|
| 1 | spec/brush-engine.md | ✅ |
| 2 | spec/document-model.md | ✅ |
| 3 | spec/blend-and-color.md | ✅ |
| 4 | spec/tools/*.md | ✅ M0–M1 tools. Specs for the following milestones are written **before** the corresponding milestone starts; this is part of its plan |
| 5 | spec/file-formats.md | ✅ |
| 6 | spec/ui-components.md | ✅ |
| 7 | spec/test-corpus.md | ✅ |
| 8 | spec/ROADMAP.md + spec/PLAN-M0-M1.md | ✅ |
| 9 | spec/filters.md | ✅ |
| 10 | spec/assets.md | ✅ |
