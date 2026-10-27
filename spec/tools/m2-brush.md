# M2: brushes (engine, catalog, UI)

Basis: spec/brush-engine.md (parameters), spec/GAPS.md §B "Brushes" (formulas), spec/brush-catalog.md (catalog). Here: how to implement this in M2. Everything marked "decision" is chosen here and mandatory for implementation.

## 0. M2 architecture
- **Dabs on the CPU in `:core`**, the GPU only composites (stroke buffer as a preview texture). GPU dabs will come later and only if the benchmark (§5) fails.
- **Storage.** Layer tiles are stored in half (8 B/px). A stroke works with float copies:
  - stroke buffer: float32 RGBA premult (as now), tiles from the `TilePool` (1 tile = 1 MB, pool limit 96 tiles, "decision");
  - `LayerWorkingSet`: on the first touch the layer tile is decoded into a float tile (half → float via a 65536 LUT). `before` = a copy of the half tile (512 KB). On `end()` the float is encoded back.
- **Threading.** A stroke is computed on a separate stroke thread (not UI, not GL). `takeDirty()` is synchronized. Worker threads: `N = clamp(cores − 2, 1, 4)`.
- **Tile parallelism.** Input frame → `DabGenerator` sequentially builds a `DabBatch` (positions, sizes, colors, all random values) → binning of dabs by tile → each tile on its own thread, dabs inside a tile strictly in index order.
  - Kernels that read neighboring pixels or the result of the previous dab (smudge, filter, watercolor deposit with mix_in) run with a barrier per dab: read phase (gather into scratch) → write phase. Tiles of a single dab run in parallel.
- **Determinism.** Stateless RNG: `rnd(ch, i) = mix64(seed ⊕ i·0x9E3779B97F4A7C15 ⊕ ch·0xC2B2AE3D27D4EB4F) → [0,1)`. Here `i` = dab (or point) index, `ch` = channel number (size, angle, scatter…), `seed` = hash (strokeIndex, brushId). The result is bit-for-bit independent of the thread count and of how the input is split into batches.
- **Re-render on pen-up.** The raw stroke points are kept. If after_smoothing is enabled or the profile is longer than the stroke (§1.6), on `end()` the buffer is cleared and the stroke is drawn again along the final trajectory. One mechanism for both cases.
- **Composite paths.**
  - Buffered (default): dabs → stroke buffer → on `end()` into the layer with cap = opacity and the brush blend mode.
  - Direct (buildup, smudge, mix_in/dilution > 0, filter, clone): dabs go straight into `LayerWorkingSet`. Opacity is multiplied by flow, there is no cap ("decision").

## 1. Engine: feature groups
Notation: `m` = dab mask coverage in the pixel 0..1, `d` = current diameter, `θ` = path direction, `s` = arc length from the start of the stroke.

### 1.1 Head: bitmap and procedural
- **Algorithm.**
  - A head = `HeadImage`: 8-bit gray (mask) or RGBA8 (colored) + a mip pyramid down to 1×1.
  - A canvas pixel is mapped into head UV by the inverse transform: translate → rotate by −angle → scale `1/(d·sx)`, `1/(d·sy)`.
  - Mip level `L = clamp(floor(log2(srcSize / d)), 0, max)`, bilinear sampling. The procedural round head stays analytic (the current `DabRenderer`).
  - head_curve (256 LUT) and invert are applied to the mask once at load. Hardness for a bitmap = softness: the mask is blurred with a Gaussian σ = (1 − hardness)·0.15·srcSize (cached at 8 levels).
- **Aspect** a ∈ [−1,1]: at a > 0 `sy = 1 − 0.95a`, at a < 0 `sx = 1 − 0.95|a|`.
- **AA and dots.** antialias = false: `m = m ≥ 0.5 ? 1 : 0`, the dab center snaps to the pixel center (pixel pen). dots = true: spacing is at least 100%.
- **Depth** (bristle): `m' = sat((h − k)/(1 − k))`, where `k = depth·(1 − p)`, h = the mask as a height map.
- **Structure** (impasto): the stroke buffer has an H height plane, `H = max(H, m·structure)`. At composite time the normal comes from `∇H` (Sobel), Lambert with light (−0.5, −0.6, 0.62), brightness ×(1 ± 0.25).
- **Parameters:** head_source, head_color, head_invert, head_curve, hardness, aspect, angle, antialias, dots, depth, structure.
- **Data:** head PNG, mip pyramid (≈ 1.33× the size).
- **CPU:** d² pixels per dab. A bitmap is ≈ 2× more expensive than an analytic circle. Mips bound the texture reads.
- **Acceptance:** a 256² head at d = 16 and d = 512 with no aliasing and no moiré. A 90° rotation produces the same dab rotated (error ≤ 1/255 after rounding). A 1 px pixel pen produces exactly 1 px with no halftones.

### 1.2 Rotation
`angle = base + rotationTerm + jitter`. rotationTerm depends on rotation_mode:

| mode | rotationTerm |
|---|---|
| fixed | 0 |
| tangent | `f·θ`, f = rotation_follow/100 ∈ (0,1] |
| reverse | `−f·θ` (rotation_follow < 0) |
| stylus-azimuth | azimuth. With a finger = the direction of the first 8 px of the stroke |
| stylus-tilt | `azimuth · tilt/(π/2)` (stronger tilt → the head follows the azimuth more) |
| twist | twist (barrel). If the device does not provide it, 0 |
| random | `360°·rnd(ANGLE, i)` |

- `θ` is smoothed with an EMA (α = 0.35) over the path steps so that it does not jitter at kinks.
- orient_screen: the view rotation is added to the angle (`StrokeContext.viewRotation`).
- **Acceptance:** in tangent mode at f = 1 an elongated head always points along the circle (error ≤ 3°).

### 1.3 Jitter, scatter, particles-scatter
- **Jitter** (continuous, per dab; r = rnd(channel, i)):
  - size ×(1 − j·r);
  - flow ×(1 − j·r);
  - dab opacity ×(1 − j·r);
  - angle += j·180°·(2r − 1);
  - step ×(1 + 0.5·j·(2r − 1));
  - scatter: a shift ⊥ θ by `j·d·(2r − 1)` and along θ by `0.25·j·d·(2r′ − 1)`.
- **Initial** (once per stroke, i = 0): angle, HSB.
- **Count** (1–16): each path step emits count dabs, each with its own index `i·16 + k`.
- **Color jitter.** hue += Jh·180°·(2r − 1), sat and bri += J·(2r − 1), clamp. Initial + continuous add up. The color is recomputed per dab, not per pixel.
- **Scatter particles** (ibis):
  - per path step particle_count (1–64) mini-dabs inside a circle of radius d/2;
  - dot radius `ρ = (d/2)·u^q`, where `q = 0.5·2^(deviation/50)`: 0 = uniform over the area, + toward the center, − toward the edge;
  - size = particle_size·d (or absolute, px);
  - angle_from_center: angle = atan2 of the offset.
- **CPU:** count × particle_count dabs. Limit ("decision"): ≤ 4096 dabs per frame. The excess is carried over to the next frame, the order is preserved.
- **Acceptance:** jitter = 0 yields a bit-for-bit M1 stroke. The same seed yields the same stroke. The mean scatter offset ≈ 0 (|μ| < 0.05·d over 10⁴ dabs).

### 1.4 Texture
- **Texture value.** `t ∈ [0,1]`: a 512² gray texture, tiled. Processing: invert, tex_curve (LUT), softness (Gaussian σ = softness·0.08·512, cached), scale, stretch (×stretch along the u axis), tex_angle.
- **Modes** (where the UV comes from):

| tex_mode | UV |
|---|---|
| canvas-fixed | `(x, y)/(512·scale)` in document coordinates |
| rotate-with-stroke | document coordinates rotated by the dab angle around its center |
| warp-along-stroke | `u = s`, `v` = signed distance to the path (from the local dab frame) |
| moving | UV relative to the dab center (the texture travels with the dab and smears) |

- **Application:** `m' = m·(1 − depth·(1 − t))`, depth [dyn].
- **Gradation:** depth ×(1 − g·τ), τ = 0 at the tip, 1 at the tail along the tilt axis.
- **Lower limit L.** Ceiling of the layer alpha in a pixel: `cap = L + (1 − L)·(1 − depth·(1 − t))`. A repeated stroke does not raise the alpha above cap. "Decision": the default is **100** (no ceiling), not 0 from brush-engine.md. Pencil presets set 30–60. The table in brush-engine.md must be fixed.
- **Texture jitter** (pos / scale / angle): initial = a UV offset per stroke, continuous = per dab.
- tex_scale_with_size: scale ×(d / size).
- **CPU:** +1 bilinear sample per dab pixel (≈ +30%).
- **Acceptance:** canvas-fixed gives the same pattern on two intersecting strokes. There is no seam at the 512 tile edge (the difference of neighboring pixels ≤ 2/255). warp at stretch = 10 produces fibers along the curve.

### 1.5 Dynamics
- **Sources** (all 0..1):

| source | how it is computed |
|---|---|
| pressure | after global calibration (§4.3). A finger = 1 |
| velocity | `sat(v/5)`, v = speed in screen px/ms, EMA α = 0.3 ("decision") |
| tilt | tilt / (π/2) |
| azimuth | azimuth / 2π |
| twist | twist / 2π |
| random | rnd(DYN, i) |
| progress | `sat(s / 300 px)` ("decision") |

- **Targets:** size, flow, opacity, scatter, angle, hardness, tex_depth, mix_in, dilution, color_jitter, blur.
- **A link** = `DynamicsLink(source, target, curve: Curve)`. Curve already exists (monotonic cubic, min).
- **How links to the same target combine:**
  - a product for size, flow, opacity, hardness, tex_depth, mix_in, dilution, blur;
  - an amplitude multiplier for scatter and color_jitter;
  - for angle: `+= 360°·(curve(x) − curve(0))`.
- **M1 compatibility:** pressureToSize and pressureToFlow stay as fields and become links internally.
- **Tilt offset:** the dab center shifts along the azimuth by `tilt·offset·d/2`.
- The global Settings toggles "pressure / velocity / tilt off" substitute the source with 1 (pressure) or 0 (the rest).
- **Acceptance:** a link with the curve y = 1 changes nothing. velocity→size reduces the thickness on a fast segment of a synthetic stroke.

### 1.6 Taper and profiles
- **Taper:** already exists.
  - taper_shape: linear `f = t`, smooth `1 − (1 − t)²`;
  - size ×lerp(1, f, taper_size), alpha ×lerp(1, f, taper_opacity).
- **Size / flow profile** = a Curve over x ∈ [0,1], 3 sections:
  - x ∈ [0, ⅓] → the first Ls px (default 60);
  - [⅓, ⅔] → the middle, stretches;
  - [⅔, 1] → the last Le px (default 60).
- **Profile tail.** The last Le px are held until pen-up (like taperEnd). If the stroke is shorter than Ls + Le, the sections are divided evenly and the stroke is redrawn (§0).
- **Acceptance:** a "thin-thick-thin" profile on a 1000 px stroke: the middle is constant for 880 px.

### 1.7 Wet edges, glaze, adhesion, blend mode
- **Glaze** g: in the buffer `a = lerp(over(a_buf, a_dab), max(a_buf, a_dab), g)`. RGB by the same weights.
- **Wet edges** w: at composite time (and in the preview for dirty tiles with a 4 px halo):
  - `e = sat((α − box₄(α))·4 + 0.5)`;
  - `α' = α·(1 − w·(1 − e))`;
  - box₄ = a separable box blur of radius 4, 2 passes.
- **Adhesion** a: in the buffer an L load plane, `L = over(L, m·flow)`, visible alpha = `smoothstep(a, 1, L)`.
- **Brush blend mode:** the buffer is composited via `BlendMath.blendPixel(mode)`, `out = lerp(normal, mode, strength)`.
- **separate_strokes = false:** the buffer lives between strokes until the tool, brush or layer changes. cap opacity is shared. Each stroke is still a separate undo step (the patch = the difference from the previous state).
- **Acceptance:**
  - glaze = 1 and opacity 50%: a self-intersection is not darker than a single pass;
  - wet edges = 100: on a flat fill the edge is ≥ 30% denser than the center;
  - a multiply brush over white = the brush color.

### 1.8 Smudge and Blend mode (mix_in, dilution, pull, wet, blur)
- **Pickup buffer P** (RGBA, head sampling: `n = min(64, ceil(d))`², in the local dab frame).
- **Per dab:**
  1. C = the color under the dab on an n² grid (bilinear from the source).
  2. Source = the active layer or, with sample_lower, the "lower composite" + the active layer. The lower composite is computed lazily by tiles and cached per stroke.
  3. `C' = lerp(C, mean(C), blur)`, mean is weighted by the mask.
- **Blend mode** (no color is deposited):
  - `dst = lerp(dst, P, m·flow·blend_flow)` (premultiplied, alpha is always carried over);
  - then `P = lerp(P, C', 1 − pull/100·0.98)`;
  - the first dab of a stroke initializes `P = C'`.
- **Paint with mix_in / dilution** (reservoir R = one color, GAPS formulas):
  - `S = mean(C')`;
  - `R = lerp(R, S, 1 − pull)`;
  - `paint = lerp(paint, S, mix_in·m̄)`;
  - deposited color = `lerp(paint, R, dilution)`;
  - wet = false: the layer alpha under the dab is not reduced (`a_out = max(a_dst, over)`);
  - wet = true: the alpha is blended with S as well.
- **ibis Water:** color_mix → mix_in, wetness → dilution + wet, dragging → pull.
- **Path:** direct, a barrier per dab.
- **CPU:** n² samples + d² writes per dab. Spacing is at least 8% ("decision": smudge presets use 8–15%).
- **Acceptance:**
  - a finger along a red|blue boundary gives a smooth gradient, the smear length grows with pull;
  - dilution = 100 without mix_in adds no brush color (ΔE < 1 from the original colors);
  - blur = 0 transfers detail (a checkerboard stays distinguishable for the first 3 dabs).

### 1.9 Watercolor
- **Model** (CPU, a "decision" instead of the GPU version from GAPS).
  - A wet grid at 1/2 resolution: cell 2×2 px, tile 128²;
  - water W, pigment P (RGBA premult);
  - one wet grid per raster layer.
- **Deposit:** `W += m·flow`, `P += color·m·flow·0.5`. With mix_in, `mix_in·m·layerColor` is added (pickup of the lower layer).
- **Tick at 30 Hz** (on worker threads, over active tiles with a 1 cell halo):
  - diffusion: `P += 0.2·bleed·Σ₄ min(W, Wₙ)·(Pₙ − P)/4` (explicit scheme, stable at coefficient ≤ 0.25);
  - edge: pigment transport along `−∇W` with strength glaze·0.1, which darkens the rim;
  - evaporation: `W −= (0.15 + 1.35·dryout/100)·dt` per second;
  - paper grain: on settling P is multiplied by `lerp(1, t_paper, 0.4)`.
- **Drying and committing.**
  - A cell with W < 0.01 is dry. A tile where all cells are dry is upscaled bilinearly and composited into the layer.
  - A stroke is committed to undo when everything has dried or 4 s have passed after pen-up.
  - Any other command (undo, layer change, save, filter) first force-dries instantly.
  - A new stroke over wet paint = wet-into-wet (the same grid).
- **Preview:** P → stroke buffer tiles.
- **Limit:** 64 active tiles, on overflow the oldest is force-dried.
- **Acceptance:**
  - a drop on a dry area spreads, the radius grows with bleed and stops no later than 4 s;
  - the edge is darker than the center at glaze > 50;
  - two wet strokes blend, a wet one over a dry one does not;
  - a tick ≤ 8 ms on 16 active tiles (reference device).

### 1.10 Particles
- **System** (the step = path step, not time, so it is deterministic):
  - n_a attractors (1–64) on a spring toward the path point + an offset `radius·d/2·dirₖ`, dirₖ from rnd;
  - n_p particles (1–400), particle j is bound to attractor j mod n_a;
  - `v = v·damp + 0.3·(target − x)`, `damp = 0.5 + 0.24·overshoot`;
  - the random impulse = the continuous scatter of the head.
- **Drawing:** each particle stamps with the particle head (d·p_size) along the segment from the previous position, spacing 50%.
- **CPU:** n_p·length/step dabs. At n_p > 100 the path step is ≥ d/4.
- **Acceptance:** on a sharp turn at overshoot = 2 the splashes fly outside the contour farther than at 0.

### 1.11 Harmony
- **General.**
  - Our own implementation: stroke points (after the stabilizer, step ≥ 2 px) are kept in a spatial hash with cell R.
  - A new point is connected to previous ones by the rules of the variant.
  - The lines are drawn by `LineRenderer`: a capsule of thickness size, AA by the distance to the segment.
  - Line alpha ∝ (1 − dist/R). The head is not used.
  - Parameters: size (thickness), spread (×R, default 1), density (×p, default 1). History up to 4000 points.
- **Variants:**

| variant | R, px | rule |
|---|---|---|
| sketchy | 80 | with previous ones, p = 0.3, the line is shortened by 20% on both ends, α·0.3 |
| shaded | 100 | with all within R, α·0.08 |
| web | 60 | with all within R, p = 0.5, α·0.2 |
| chrome | 70 | like sketchy, line color = color ± 30% brightness by rnd |
| short fur / long fur | 40 / 120 | from the point to `p + (pⱼ − p)·(−0.5..1.5)·rnd`, p = 0.6, α·0.25 |
| ribbon | — | 32 "springs" with stiffness 0.05–0.4, lines between consecutive positions |
| curvy | 90 | quadratic Bézier to pⱼ through a displaced midpoint |
| grid | 60 | axes only: horizontal and vertical segments to the projections of pⱼ |
| circles / squares | — | at each point a figure with radius ∝ speed (2–60 px), the square is rotated by θ |

- **CPU:** O(k) neighbors per point, k ≤ 64 ("decision": the nearest ones).
- **Acceptance:** density grows where the stroke returns onto itself. The result is deterministic.

### 1.12 Double (outline and shadow)
- **Algorithm:**
  - body = the stroke buffer (alpha A, color body_color or the current one);
  - shadow = `S = sat(shadow_size + 0.5 − EDT(A ≥ 0.5))`, shifted by (distance, angle);
  - EDT by Felzenszwalb, over the bbox of the dirty tiles + a halo of `shadow_size + distance` (≤ 64 px);
  - composite: shadow_color·S under the body;
  - texture_on_shadow: S × t.
- **Acceptance:** an outline of uniform thickness ±0.5 px around a loop. distance = 0 gives a clean outline.

### 1.13 Color stamp (colored dab)
- **Algorithm:** an RGBA head. The color shift is computed once per stroke (and again per dab if continuous color jitter is enabled; cached by hue with a 1° step):
  - `h′ = h + (h_cur − h_base)`;
  - `S′ = min(S, S·S_cur / max(S_base, 0.01))`, V′ likewise;
  - colorize: a blend of the original and the shifted color.
- **Acceptance:** when the current color = base_color the dab matches the bitmap (ΔE < 1). With a blue current color a red dab becomes blue and does not become brighter than the original.

### 1.14 Screentone snap
- **Algorithm:**
  - a grid with step g (px, or `g·d/size` with scale_with_size), rotated by angle;
  - for nodes inside the dab area `c = max(c, m·flow)` accumulates in the stroke node map;
  - at a node a dot is drawn with diameter `g·sqrt(c)·1.13` (area ∝ coverage). The line and cross modes draw a dash of length g and thickness `g·c`;
  - a node is redrawn only when c grows (via a max op).
- **Acceptance:** a soft brush gives a gradient of growing dots, all centers exactly on the grid nodes.

### 1.15 Fill brush
- **Algorithm:**
  - trajectory = a polygon (closed by the last point);
  - each frame is rasterized even-odd: scanlines, 4 subsamples per pixel for AA;
  - content: solid, pattern (canvas-fixed texture) or procedural (noise);
  - draw preview off: fill only on pen-up.
  - On fill brushes harmony, particles and screentone do not work.
- **CPU:** O(bbox area). At bbox > 4 Mpx the preview runs at 10 Hz.
- **Acceptance:** a figure-eight is filled even-odd (the intersection is empty). The edge is AA.

### 1.16 Filter brush (blur, glow, sharpen)
- **target = layer** (direct path):
  - `out = lerp(src, F(src), m·flow·|strength|)`;
  - blur = 3 box passes, radius `r = 1 + 0.06·d·|strength|`, over the dab region + a halo r from the current layer;
  - strength < 0 on blur = sharpen (unsharp mask, amount |strength|·1.5);
  - glow: `src + blur(src·sat((lum − 0.6)/0.4), r)·strength` (Add). strength < 0 = dark halo (Multiply).
- **target = stroke:** F is applied to the buffer before the composite (glow brushes: core + blurred halo, blend Add/Screen).
- **CPU:** (d + 2r)²·6 per dab. Spacing of the presets is ≥ 20%.
- **Acceptance:** a blur brush on a checkerboard smooths only under the stroke. strength 0 = a bit-for-bit no-op.

### 1.17 3D stamp (tube, ribbon, twisty)
- **Tube:**
  - normal `n = (x, y, sqrt(1 − r²))` in the local disk frame;
  - color `c·(0.35 + 0.65·max(0, n·L)) + 0.4·(n·H)^24`, L as in structure;
  - in the buffer a z plane, a pixel is written if `z_new ≥ z_buf` → a smooth tube at spacing 5%.
- **Ribbon:** a flat band ⊥ θ, the normal rotates by `s·twist_rate` (default 1°/px).
- **Twisty:** tube × bands by `φ = atan2(nₓ, n_z) + s·twist_rate`, 4 bands.
- **Acceptance:** on a straight tube the highlight is continuous, the dark side is bottom-right.

### 1.18 Clone mode (`StrokeMode.CLONE`)
- **Source:**
  - a layer (default: the active one) or "all visible";
  - the source snapshot is taken at begin, so cloning into itself is safe;
  - the offset is set by long-press in Clone mode (the source point), a crosshair cursor is shown on the source.
- **Aligned** (default): the offset is constant for all strokes. **Non-aligned:** each stroke starts from the source point.
- **Pixel:** `src(p − offset)` (bilinear) × m·flow.
- **Artistic:** the dab color = mean(src under the dab), then as Paint.
- **Acceptance:** a clone without an offset on a clean layer reproduces the source (ΔE < 1).

### 1.19 Tool modes
- Paint, Blend (§1.8), Erase (destination-out with a full mask: head, texture, jitter; color stamp/double/fill/harmony in Erase give only a mask), Clone.
- In Blend and Erase the special effects (watercolor, particles, harmony, double, 3D) are disabled.

## 2. Implementation stages
| # | Stage | Content | Depends on |
|---|---|---|---|
| S0 | Skeleton | half tiles + `LayerWorkingSet`, `TilePool`, `TileScheduler`, hash-RNG, `PathWalker` (from StrokeResampler), `DabGenerator`, `StrokeContext`, `Brush` + `BrushCodec`, re-render on pen-up, lazy rope, after_smoothing, benchmark | M1 |
| S1 | Dab | bitmap heads and mips, aspect, rotation modes, AA and dots, jitter, count, color jitter, dynamics (all sources and targets), taper shape, profiles, tilt offset | S0 |
| S2 | Texture | 4 modes, depth, lower limit, gradation, jitter | S1 |
| S3 | Stroke effects | glaze, wet edges, adhesion, structure, depth, brush blend mode, separate_strokes | S1 |
| S4 | Blending | Blend mode, mix_in, dilution, pull, wet, blur, sample lower, ibis Water | S1 |
| S5 | Modes and filters | Erase with a full mask, filter brush (blur, sharpen, glow), Clone | S4 |
| S6 | Special-1 | color stamp, scatter particles, double, screentone snap, fill brush | S2, S3 |
| S7 | Special-2 | particles, harmony (`LineRenderer`) | S1 |
| S8 | Watercolor | `WetSimulation` | S2, S4 |
| S9 | 3D | tube, ribbon, twisty | S3 |
| S10 | Catalog | `gen_assets.py`, ≈236 presets, previews | S1–S9 as they become ready (categories are connected when their features are ready) |
| U1 | Brush browser | §4.1 | S0 (codec) |
| U2 | Editor | §4.2 | S1, U1 |
| U3 | Stylus | §4.3 | S0 |
| U4 | State persistence | §4.4 | U1 |

S2 ∥ S3 ∥ S4 ∥ S7 and U1 ∥ U3 can run in parallel.

## 3. API, format, storage
### 3.1 Kotlin (`app.painter.core.stroke`, new files)
```kotlin
// Model. The M1 fields of BrushParams stay at the top level (test and JSON compatibility), the new groups are nested.
@Serializable data class BrushParams(/* M1 fields */, val taperShape: TaperShape = SMOOTH,
    val head: HeadParams = HeadParams(), val jitter: JitterParams = JitterParams(),
    val texture: TextureParams? = null, val paint: PaintParams = PaintParams(),
    val effects: EffectParams = EffectParams(), val special: SpecialParams? = null,
    val dynamics: List<DynamicsLink> = emptyList(), val profiles: Profiles? = null)
@Serializable sealed interface SpecialParams  // Watercolor, Particles, Harmony, Double, ColorStamp, Screentone, Fill, Filter, ThreeD
enum class DynSource { PRESSURE, VELOCITY, TILT, AZIMUTH, TWIST, RANDOM, PROGRESS }
enum class DynTarget { SIZE, FLOW, OPACITY, SCATTER, ANGLE, HARDNESS, TEX_DEPTH, MIX_IN, DILUTION, COLOR_JITTER, BLUR }
@Serializable data class DynamicsLink(val source: DynSource, val target: DynTarget, val curve: Curve)
@Serializable data class Brush(val schema: Int = 1, val id: String, val parentId: String?, val name: String,
    val category: String, val tags: List<String> = emptyList(), val params: BrushParams, val resources: ResourceRefs)
@Serializable data class ResourceRefs(val head: String? = null, val texture: String? = null, val particle: String? = null)

class ImageData(val width: Int, val height: Int, val channels: Int /*1|4*/, val pixels: ByteArray) // decoded by app
class HeadImage(src: ImageData, curve: Curve?, invert: Boolean) { fun level(d: Float): Int; fun sample(level: Int, u: Float, v: Float): Float; fun sampleRgba(level: Int, u: Float, v: Float, out: FloatArray) }
class TextureImage(src: ImageData, p: TextureParams) { fun sample(u: Float, v: Float): Float }
class BrushResources(val head: HeadImage?, val texture: TextureImage?, val particle: HeadImage?)
fun interface ResourceResolver { fun image(ref: String): ImageData }   // "asset:heads/x.png" | "brush:head.png"

data class StrokeContext(val zoom: Float, val viewRotation: Float, val seed: Long,
    val lowerComposite: ((TileKey) -> Tile?)? /*for sample_lower*/, val paperTexture: TextureImage?,
    val clone: CloneSource? = null, val threads: Int = 4)
class PathSample { var x = 0f; var y = 0f; var pressure = 1f; var tilt = 0f; var azimuth = 0f; var twist = 0f
    var timeMs = 0L; var distance = 0f; var dirX = 1f; var dirY = 0f; var velocity = 0f; var taper = 1f; var index = 0 }
fun interface PathSink { fun onSample(s: PathSample): Float /* next step, px */ }
class PathWalker(params: BrushParams, sink: PathSink) { fun begin(p: InputPoint); fun add(p: InputPoint); fun end() }
class Dab { var x = 0f; var y = 0f; var d = 0f; var angle = 0f; var sx = 1f; var sy = 1f; var flow = 1f; var hardness = 0.8f
    var r = 0f; var g = 0f; var b = 0f; var a = 1f; var texDepth = 0f; var mixIn = 0f; var dilution = 0f; var blur = 0f; var index = 0 }
class DabBatch(capacity: Int = 4096) { val size: Int; operator fun get(i: Int): Dab; fun clear() }
class DabGenerator(brush: Brush, res: BrushResources, ctx: StrokeContext, color: Rgba) { fun emit(s: PathSample, out: DabBatch): Float /*step*/ }
interface DabKernel { val orderDependent: Boolean; fun renderTile(t: FloatArray, tx: Int, ty: Int, dab: Dab) }
  // MaskKernel(OVER|GLAZE|ERASE|ALPHA_LOCK), SmudgeKernel, FilterKernel, CloneKernel, TubeKernel, ScreentoneKernel
class TileScheduler(threads: Int) : AutoCloseable { fun run(batch: DabBatch, target: TileAccess, kernel: DabKernel) }
interface StrokeEffect { val halo: Int; fun apply(buffer: TileGrid, keys: Set<TileKey>) } // WetEdges, Double, Glow, Structure
interface PrimitiveGenerator { fun onSample(s: PathSample, out: PrimitiveSink) } // Particles, Harmony, Fill
class LineRenderer { fun line(t: TileAccess, x0: Float, y0: Float, x1: Float, y1: Float, width: Float, r: Float, g: Float, b: Float, a: Float) }
class WetSimulation(width: Int, height: Int) { val active: Boolean; fun deposit(dab: Dab, color: Rgba); fun step(dtMs: Int); fun dryAll(); fun takeCommit(): TilePatch? }
enum class StrokeMode { PAINT, BLEND, ERASE, CLONE }
class StrokeEngine(doc: Document, layerId: String, brush: Brush, res: BrushResources, color: Rgba,
    mode: StrokeMode, ctx: StrokeContext)  // begin/add/end/cancel/takeDirty/buffer/previewOpacity as in M1
class InputCalibration(var pressureCurve: Curve, var maxPressure: Float = 1f) { fun pressure(raw: Float): Float; fun observeStrokePeak(p: Float) }
```
- **Contract changes** (to be agreed):
  - the StrokeEngine constructor takes Brush + BrushResources + StrokeContext instead of BrushParams. The old constructor stays as a wrapper;
  - `twist: Float = 0f` is added to InputPoint;
  - StrokeMode is extended to BLEND and CLONE;
  - `Tile` gets half storage (a separate raster task, at the very start of S0).
- **Codec:** `core/io/BrushIO`:
  - `decode(json): Brush`, `encode(Brush): String`;
  - `readPbrush(zip: InputStream): Pair<Brush, Map<String, ByteArray>>`, `writePbrush(...)`;
  - `readPack(...)` for `.pbrushes`.

### 3.2 brush.json (schema 1)
```json
{ "schema": 1, "id": "pencil.graphite_hb", "parentId": null, "name": {"ru": "Grafit HB", "en": "Graphite HB"},
  "category": "pencils", "tags": ["grafit", "graphite", "sketch"],
  "params": { "size": 6, "sizeMax": 60, "opacity": 0.9, "flow": 0.55, "spacing": 0.08, "hardness": 0.9,
    "head": {"source": "bitmap", "aspect": 0, "rotationMode": "stylus-tilt", "depth": 0.2},
    "jitter": {"size": 0.05, "angle": 1.0},
    "texture": {"mode": "canvas-fixed", "depth": 0.7, "scale": 0.5, "lowerLimit": 0.4},
    "dynamics": [ {"source": "PRESSURE", "target": "FLOW", "curve": {"points": [[0,0],[1,1]], "min": 0.1}},
                  {"source": "TILT", "target": "SIZE", "curve": {"points": [[0,0],[1,1]], "min": 0.5}} ] },
  "resources": { "head": "asset:heads/grain_disk_01.png", "texture": "asset:textures/graphite_grain.png" } }
```
- **name:** a string or `{ru, en}`. User brushes keep a string.
- **Reading:** unknown fields are ignored (`ignoreUnknownKeys`), missing ones = defaults. On `schema > 1` a migration applies.
- **id:** for built-in brushes a stable slug `<category>.<name>`, for user brushes a UUID.
- **Resource references:** `asset:` = from the APK, `brush:` = a file inside `.pbrush`. On export `asset:` resources are copied into the archive and rewritten to `brush:`.

### 3.3 Storage
```
app/src/main/assets/brushes/
  index.json              { schema:1, categories:[{id, name{ru,en}, icon, brushes:[id...]}], favorites:{paint:[], blend:[], erase:[], clone:[]} }
  presets/<category>/<id>.json
  heads/*.png  textures/*.png  particles/*.png
files/brushes/
  library.json            { folders:[{id, name, brushIds}], hidden:[], favorites{mode:[ids]}, order, quick:{id:{size,opacity,flow,hardness}} }
  user/<uuid>/brush.json + *.png        // Created / Imported; editing a built-in = an override with parentId
cache/brush_previews/<id>_<paramsHash>.png
```
- Reset of a built-in brush = delete the override and `quick[id]`. Reset of a user brush = restore the snapshot taken at creation time (`user/<uuid>/original.json`).

## 4. UI (IP structure, our own visuals: ui-components.md)
### 4.1 Brush browser
- **Opening:** a repeated tap on the active mode (Paint, Blend, Erase, Clone). A popup panel of 360×560 dp next to the toolbar, on a phone a bottom sheet at 85% of the height.
- **Header:** a preview of the current brush (a 300×64 dp stroke) and the buttons: Edit, ♡, Options (⋯), Add (+). Below it a search field.
- **Left:** a 96 dp column of folders.
  - Recent: the 20 last brushes of this project (manifest `recentBrushes`);
  - Favorites: their own per mode, prefilled from index.json;
  - Created, Imported;
  - the 20 catalog categories;
  - the user's folders.
- **Right:** the brush list, a 56 dp row: icon, name, a 160×40 dp stroke preview.
  - The preview is rendered in the background (S-curve, pressure 0.2 → 1 → 0.2, the current color, on a dark and a light background), cached in cache/brush_previews;
  - tap = select the brush for the current mode;
  - long-press + drag = move to a folder.
- **Search:** a case-insensitive substring over the ru/en names and tags in all folders. The result is a flat list with the category as a subtitle.
- **Brush Options:** Rename (for a built-in one, Restore name), Duplicate (parentId = the source), Export `.pbrush` (SAF or Share), Reset, Move to folder, Remove from folder, Hide (built-in) or Delete (user, with confirmation).
- **Folders:** create, rename, Export `.pbrushes`, Hide, Delete (the brushes return to their original containers). A built-in folder can be restored: long-press → Restore.
- **Import:** via `ACTION_OPEN_DOCUMENT` (`.pbrush`, `.pbrushes`) → the Imported folder. A pack = a new folder. Head/texture can be imported from a PNG.

### 4.2 Brush editor
- **Opening:** Edit in the browser, a button in the Size popup, Shift+Ctrl+B. Tablet: a 440 dp right panel, the canvas stays visible. Phone: full screen.
- **Header:**
  - name (tap = rename);
  - a **preview pad** of 160 dp: drawn with the pen, a "clear" button, by default a reference S-curve; the preview is without color jitter and smudge (blending is shown against a background of 4 color bands);
  - buttons: Cancel, Save as (a copy in Created), Export, Reset, Confirm.
- **Tabs:**

| Tab | Content |
|---|---|
| Stroke | size, size range (min/max), opacity, smoothing, smoothing_speed, after_smoothing, lazy, taper (start, end, shape, size, opacity), profiles (size, flow), glaze, wet edges, adhesion, blend mode + strength, buildup, separate strokes, dynamics size (pressure, velocity, tilt) + tilt offset, size jitter |
| Head | head selection (a grid of built-ins + Imported + "+"), color, invert, tonal curve, colorize, base color, hardness, aspect, spacing, flow, angle, rotation mode/follow, orient to screen, antialias, dots, depth, structure, dynamics (flow, scatter, angle, hardness), jitter (initial angle; continuous scatter, angle, flow, opacity, spacing; count), scatter particles |
| Texture | selection, mode, depth, lower limit, scale, stretch, angle, softness, structure, invert, curve, scale with size, gradation, jitter, AA, dynamics depth |
| Paint | mix_in, dilution, wet, pull, blur, blend flow, sample lower (global), dynamics (mix_in, dilution, blur), color jitter HSB (initial and continuous) |
| Special | one type from the list (none, watercolor, particles, harmony, double, color stamp, screentone, fill, filter, 3D) and its parameters |

- **Controls:** LinearSlider, CurveEditor for each link (a header toggle on/off, preset buttons: linear, ease-in, ease-out, S, invert).
- **Live preview:** a redraw 100 ms after the last change, on the stroke thread, cancellable.
- Changes from the Size and Opacity popup are written to `quick` immediately when the popup closes (like in IP, without undo, with Reset).

### 4.3 Stylus and input (Settings → Stylus, the section appears after the first TOOL_TYPE_STYLUS/ERASER event)
- **Pressure calibration** (`InputCalibration`, stored per device: `InputDevice.descriptor`):
  - **Auto** (on by default): `p_cal = min(1, p_raw / p_max)`;
    - p_max = the 95th percentile of the peak pressures of the last 20 strokes, clamp [0.4, 1], start 1.0;
    - p_max is updated fast upward (immediately) and slowly downward (EMA 0.1 per stroke);
    - example: an XP-Pen with a peak of ≈ 0.78 gets the full range after 3–5 strong strokes.
  - **Calibration wizard:** "press 3 times lightly and 3 times hard" → p_min (default 0) and p_max are fixed, auto is turned off.
  - **Pressure curve** on top of p_cal: a CurveEditor with a live dot for the current pressure. Presets Soft (γ 0.6), Normal (linear, default), Firm (γ 1.6). Velocity and tilt curves work the same way.
  - **Toggles:** pressure, velocity, tilt for all brushes. Weak sensitivity = threshold 0.02: below it no dab is drawn.
- **Pen buttons:**
  - `MotionEvent.getButtonState()` is read: BUTTON_STYLUS_PRIMARY, BUTTON_STYLUS_SECONDARY;
  - for drivers in mouse mode with toolType = STYLUS also BUTTON_SECONDARY and BUTTON_TERTIARY;
  - the ACTION_BUTTON_PRESS/RELEASE events work in hover too.
- **Button actions:**

| Action | Type |
|---|---|
| none | — |
| eyedropper | hold |
| erase | hold: temporary Erase mode |
| blend | hold |
| pan / zoom | hold + drag |
| size | hold + drag horizontally |
| undo, redo | click |
| previous tool | click |
| brush browser | click |
| custom toolbar tool | click |

  - "Decision": default primary = eyedropper (hold), secondary = undo (click).
- **Back of the pen** (TOOL_TYPE_ERASER) → Erase mode with its own brush and size. When the tip returns, the previous mode is restored.
- **Finger with an active stylus:** Draw, Navigate only (default after the first pen touch), Eyedropper, Blend, Erase.
- **Palm rejection:**
  - Off;
  - Stylus only (default, = "the finger does not draw");
  - Hover reject: finger touches are ignored while the pen is in hover and for 300 ms after.
  - Always: ACTION_CANCEL and FLAG_CANCELED roll the stroke back. A touch with `getTouchMajor() > 40 dp` = a palm.
- **Other:** touch offset (dx, dy ±20 px), cursor (none, head outline, crosshair in hover), point prediction (on), discard a stylus stroke without pressure (already exists).

### 4.4 Tool state persistence
- **File** `files/settings/tool_state.json`. Writing with a 500 ms debounce, immediately in `onStop`.
  - Content: `{schema, mode, brushByMode{paint, blend, erase, clone}, color, prevColor, recentColors[10], paletteId, blendPanel{blur, pull, flow, sampleLower}, eyedropper{sampleAll}, clone{sourceLayer, aligned}}`.
  - Size, opacity, flow, hardness are stored in `library.json → quick[brushId]` (each brush has its own).
- **Stylus:** `files/settings/input.json`: per-device calibration, buttons, palm mode, finger action, curves.
- **On Studio start:** restore everything. If the brush was deleted, take the mode's default brush from index.json.
- **Project:** `recentBrushes` in the manifest.

## 5. Catalog and assets: `tools/gen_assets.py`
- **Run:** `.\.venv\Scripts\python tools\gen_assets.py [--only heads|textures|presets] [--check]`. Dependencies: numpy, Pillow (`tools/requirements.txt`).
- **Determinism:** `np.random.default_rng(seed)`, seed = crc32(asset name). `--check` regenerates into a temporary folder and compares the bytes: a CI check that the assets are reproducible.
- **Tiling:** all textures tile. Synthesis is spectral via FFT: white noise × a 1/f^β filter, then the inverse FFT. Periodicity follows by construction. Worley noise with periodic distances.
- **Format:**
  - heads 256² (large bristles 512²), gray 8-bit: white = coverage (we store white-on-black, invert is not needed);
  - colored stamps RGBA 256²;
  - textures 512² gray;
  - budget for all assets ≤ 8 MB in the APK.
- **Shape drawing:** via Pillow ImageDraw with 4× supersampling and LANCZOS downscaling.

| Kind | Count | Generation |
|---|---|---|
| Circles and basics | 6 | soft disk (Gaussian), square, rounded square, ellipse-chisel, ring (bokeh: brighter edge), triangle |
| Grainy disk (pencil, charcoal) | 6 | disk × (fbm β = 0.5 > threshold), anisotropy for charcoal (noise stretched 1:4) |
| Chalk, pastel, crayon | 5 | disk with a torn edge (radius + fbm over the angle) × coarse grain with a threshold |
| Bristle | 8 | N = 12–80 capsule strokes with random width, length and alpha; variants flat, round, fan, rake, dry (gaps) |
| Palette knife, roller | 3 | a trapezoid with ribs (sin profile), a rectangle with stripes |
| Sponges | 3 | Worley F1 with a threshold, 3 scales (coarse, kitchen, natural) |
| Splashes and spray | 6 | disks with a power-law distribution of radii (α = 2.2) + 1–3 runs; spray = 400 Gaussian dots |
| Particles | 5 | soft dot, hard dot, spark (4-point star), droplet, smoke (fbm drop) |
| Design | 8 | dash, dot, arrow, chevron, chain link, lace motif (rings along a circle), stitch (cross), ribbon segment |
| Colored stamps | 14 | star, diamond, heart, flower, leaf, snowflake, note, cloud, drop, bubble, butterfly, grass, rose (layers of polygon petals with a gradient), berry. base_color in the preset = the mean hue |
| Watercolor | 3 | salt blossom (radial rays + noise), circle stain (a ring with a dark edge), floral stencil |
| Paper textures | 6 | smooth, cold press, rough, watercolor (fbm + Worley), kraft (fibers), rice paper (long fibers) |
| Grain textures | 6 | graphite grain (high-frequency β = 0.3 + anisotropy), charcoal, chalk, crayon wax, sand, stucco |
| Fabrics | 5 | canvas weave (sin interweaving + noise), linen, denim (45° twill), corduroy (stripes), cloth fiber |
| Hatching and graphics | 6 | hatching, crosshatch, scratch (random segments), grunge (fbm with thresholds), debris, newspaper (halftone + noise) |
| Patterns | 7 | brick, wood (rings `sin(r·k + fbm)`), pebbles (Worley F2 − F1), isometric grid, square grid, checker, velvet |

- **Presets:** `tools/catalog/*.py`, one file per category. A dict table: a base template ("pencil", "ink", "paint", "airbrush"…) + overrides. The script writes `presets/<cat>/<id>.json` and `index.json`. The JSON is never edited by hand.
  - Every preset must have a ru/en name (our own, not from the originals), tags, and references only to generated assets.
- **Vector pens (cat. 18):** in M2 they are shipped as raster analogues with the tag `vector-later`. The outline eraser (cat. 19) in M2 = a regular hard eraser.
- **Fill-in order:**
  1. after S1–S3: pencils, pens, calligraphy, markers, erasers, chalk, charcoal, design, textures;
  2. after S4–S5: paints, blending;
  3. after S6–S9: the remaining categories.

## 6. Performance and the GPU gate
- **Benchmark** `core/src/test/.../StrokeBench` (tag `bench`, not part of a normal run) + an on-screen counter in the debug build.
- **Scenarios:**
  - A: 1000 px, size 20, spacing 10%;
  - B: size 300, soft, spacing 5%;
  - C: size 500, bitmap + texture, spacing 8%;
  - D: smudge, size 200;
  - E: watercolor, 16 tiles.
- **Targets** on the reference tablet (XP-Pen MDP1221 / emulator ×0.5):
  - CPU per frame ≤ 8 ms for A–C at a pen speed of 2000 px/s;
  - D ≤ 12 ms;
  - E: tick ≤ 8 ms.
- **Gate:** if scenario B or C fails after optimizations (SIMD-friendly loops, LUTs, bbox clamping), a "GPU dabs for MaskKernel" task is created. The other kernels stay on the CPU.

## 7. M2 acceptance (brushes)
- [ ] All ≈236 presets load. Each one draws a non-empty stroke on the reference trajectory without exceptions (an automated JVM test + a run on the device).
- [ ] Each group of §1 passes its own acceptance.
- [ ] Determinism: 1 and 4 threads, input in one batch and point by point yield a bit-for-bit identical buffer.
- [ ] Undo rolls back any stroke, including smudge, filter, clone and watercolor after drying.
- [ ] The §6 benchmark is within the targets or a gate task has been created.
- [ ] Browser: search, favorites per mode, project recent, folders, hide/restore, duplicate, export/import of `.pbrush` and `.pbrushes` (a lossless round trip).
- [ ] Editor: all §4.2 parameters change the preview. Reset restores the factory values, Save as creates a copy with a parentId.
- [ ] XP-Pen: after 5 strong strokes full pressure gives p_cal ≥ 0.98.
- [ ] Pen buttons and the eraser end work according to the setting. Palm rejection produces not a single palm stroke in 2 minutes of drawing.
- [ ] After a force-stop the mode, the brushes per mode, the sizes, the color and the recent colors are restored.
- [ ] `gen_assets.py --check` passes. Assets ≤ 8 MB. There are no third-party assets or names in the repository.

## 8. JVM tests (`core/src/test/kotlin/app/painter/core/stroke/…`, `…/io/`)
| Test | Check |
|---|---|
| HashRngTest | uniformity (χ², 10⁴), channel independence, value stability |
| PathWalkerTest | the M1 resampler tests are green; the step from the sink; direction and velocity on a line and a circle |
| DeterminismTest | 1 vs 4 threads, batch vs points → equal buffers |
| HeadImageTest | mip levels; 90° rotation is exact; aspect; AA off gives 0/1 |
| DynamicsTest | each source × target; the product of links; the curve y = 1 is neutral; calibration 0.78 → 1 |
| JitterScatterTest | jitter 0 = M1; mean offset ≈ 0; deviation shifts the radii toward the center and the edge |
| TextureTest | 4 UV modes; tiling without a seam; lower limit sets the ceiling; gradation |
| TaperProfileTest | linear vs smooth; the profile on 1000 px; re-render of a short stroke |
| EffectsTest | glaze without accumulation; wet edges edge > center; adhesion threshold; multiply blend mode |
| SmudgeTest | a gradient at the boundary; pull ↑ gives a longer smear; dilution 100 without brush color; wet and alpha |
| WatercolorTest | pigment mass is conserved during diffusion (±1%); drying ≤ 4 s; patch commit |
| ParticlesTest | determinism; overshoot increases the spread |
| HarmonyTest | all 11 variants draw; the number of lines ≤ k per point |
| DoubleTest | outline thickness ±0.5 px |
| ColorStampTest | base = cur → no change; S and V do not grow |
| ScreentoneTest | dot centers on the nodes; area ∝ coverage |
| FillBrushTest | even-odd on a figure-eight; AA edge |
| FilterBrushTest | strength 0 = no-op; blur only under the mask; sharpen raises the contrast |
| CloneTest | a clone without an offset reproduces the source; aligned and non-aligned |
| HalfTileTest | float → half → float error ≤ 1e-3 on [0,1]; the working set commits and rolls back |
