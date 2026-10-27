# Brush engine (unified model)

Merges the parameters of IP (features/04/infinite-painter.md) and ibis (features/04/ibispaint.md). Duplicates are merged. Ranges and defaults are ours.

## 1. Stroke pipeline
```
input events (x, y, pressure, tiltX, tiltY, azimuth, twist, time)
 → coalesced/predicted touches (Android MotionEvent history + predicted)
 → stabilizer (real-time: constant + speed-based; lazy-rope; after-stroke)
 → constraint (lazy / guide / ruler / perspective snap / hatching / shape)
 → resampler (discretization by spacing along the Catmull-Rom curve)
 → dab generator (per dab: position, size, angle, flow, opacity, color, scatter, texture)
 → symmetry (dab copies via affine transforms)
 → dab renderer (GPU: head mask × texture × color → stroke buffer)
 → stroke compositor (stroke buffer → layer: blend mode, opacity cap, alpha lock, selection mask, clip)
```
- A stroke is drawn into a separate **stroke buffer** (RGBA16F, the size of the bbox). Stroke opacity = cap on the buffer (IP Opacity / ibis Opacity Saturation). Compositing into the layer happens on every frame for the preview, and finally on pen-up.
- "Build-up" mode (ibis Opacity Saturation = off): dabs go straight into the layer without a cap.

## 2. Parameters
Notation:
- **[dyn]**: supports dynamics (pressure / velocity / tilt / random / stroke-progress curves);
- `%` = 0..1 internally.

### 2.1 Stroke
| id | Parameter | Range | Def. | Source |
|---|---|---|---|---|
| size | Size, px diameter | 0.5–size_max | 20 | both |
| size_min, size_max | Slider bounds | 0.5–5000 | 1 / 500 | ibis |
| size_relative | Size relative to the canvas | bool | false | ibis |
| opacity | Stroke opacity cap [dyn] | 0–100% | 100 | both |
| buildup | Accumulation without a cap | bool | false | ibis |
| smoothing | Stabilizer real-time constant | 0–100 | 10 | both |
| smoothing_speed | Stabilizer growing with speed | 0–100 | 0 | ibis |
| after_smoothing | Smoothing after pen-up | 0–100 | 0 | ibis |
| lazy_radius | "Rope", screen px | 0–200 | 0 | IP |
| lazy_elastic | Radius grows with speed | bool | false | IP |
| taper_start, taper_end | Taper length, px | 0–500 | 0 | both |
| taper_shape | smooth / linear | enum | smooth | ibis |
| taper_size, taper_opacity | Effect of the taper on size and opacity | 0–100% | 100 / 0 | ibis |
| size_profile, flow_profile | Curve along the stroke length (3 sections) | curve | off | IP |
| adhesion | "Material" threshold | 0–100 | 0 | IP |
| wet_edges | Edges denser than the middle | 0–100 | 0 | IP |
| glaze | No accumulation within the stroke | 0–100 | 0 | IP |
| blend_mode | Stroke blend mode | 33 | normal | both |
| blend_strength | Mode strength | 0–100% | 100 | IP |
| separate_strokes | Each stroke separate (otherwise they merge in one session buffer layer) | bool | true | ibis |

### 2.2 Head (dab)
| id | Parameter | Range | Def. |
|---|---|---|---|
| head_source | round / bitmap / procedural | enum | round |
| head_color | Use the RGBA of the bitmap | bool | false |
| head_invert | Invert | bool | false |
| head_curve | Tone curve of the mask | curve | linear |
| colorize | How much color comes from the bitmap (with head_color) | 0–100 | 0 |
| base_color | Base for the hue shift of the color stamp (ibis Color type) | color | — |
| hardness | 1 − softness. Dab edge | 0–100 | 80 |
| aspect | Flattening −100..100 (− taller, + wider) | −100–100 | 0 |
| spacing | Step, % of size | 1–1000 | 10 |
| flow | Dab opacity [dyn] | 0–100 | 100 |
| angle | Base angle | 0–360 | 0 |
| rotation_mode | fixed / tangent(direction) / reverse / stylus-azimuth / stylus-twist (barrel) / random | enum | fixed |
| rotation_follow | Share of direction following (IP −100..100) | −100–100 | 0 |
| orient_screen | Angle relative to the screen | bool | false |
| antialias | Dab AA | bool | true |
| dots | Discrete dots | bool | false |
| structure | Impasto relief (normals from the mask, lighting) | 0–100 | 0 |
| depth | Head alpha as a height map (bristle) | 0–100 | 0 |

### 2.3 Jitter (random)
- Size, Angle, Scatter (offset ⊥ to the direction, % of size), Flow, Opacity, Spacing: 0–100, default 0.
- Count: dabs per step (1–16).
- Initial (once per stroke) and continuous (per dab): angle and HSB.
- **Color jitter HSB**: hue ±180°, sat, bri. Initial and continuous.
- **Scatter particles** (ibis):
  - particle_count 1–64;
  - particle_size;
  - deviation −100..100 (outward/inward);
  - angle_from_center.

### 2.4 Texture
| id | Parameter | Range | Def. |
|---|---|---|---|
| tex_source | none / bitmap | — | none |
| tex_mode | canvas-fixed / rotate-with-stroke / warp-along-stroke / moving(stamp-relative) | enum | canvas-fixed |
| tex_depth | Strength [dyn] | 0–100 | 50 |
| tex_lower_limit | Paint threshold (ibis), 100 = no threshold | 0–100 | 100 |
| tex_scale | 0.05–4 | — | 1 |
| tex_stretch | 1–50 | — | 1 |
| tex_angle | 0–360 | — | 0 |
| tex_softness | Texture blur | 0–100 | 0 |
| tex_structure | Texture impasto | 0–100 | 0 |
| tex_invert, tex_color, tex_curve | same as for head | — | — |
| tex_scale_with_size | bool | — | false |
| tex_gradation | Falloff under tilt | 0–100 | 0 |
| tex_jitter_pos / scale / angle | initial and continuous | bool | false |
| tex_aa | bool | — | true |

### 2.5 Paint (blending)
| id | Parameter | Range | Def. |
|---|---|---|---|
| mix_in | Pickup with color replacement [dyn] | 0–100 | 0 |
| dilution | Pickup without replacement (100 = blender) [dyn] | 0–100 | 0 |
| wet | Transfer alpha | bool | false |
| pull | Smear length (color memory) | 0–100 | 50 |
| blur | Averaging of the pickup (sample radius) | 0–100 | 0 |
| blend_flow | Flow multiplier in Blend mode | 0–100 | 100 |
| sample_lower | Sample the visible layers below | bool (global) | false |
| water_color_mix / wetness / dragging | ibis Water type, mapped to mix_in/dilution/pull | — | — |

### 2.6 Special
- **Watercolor**:
  - bleed 0–100;
  - glaze 0–100;
  - mix_in 0–100;
  - dryout 0–100.
  - Model: a wet layer (water + pigment), diffusion on the GPU (ping-pong), drying over time.
- **Screentone snap**: grid_spacing px, angle 0–180, scale_with_size.
- **Particles** (attractors 1–64, particles 1–400, radius, p_size 0–0.1, overshoot 0–2).
- **Double** (outline/shadow): body_color/shadow_color, shadow_size (px or %), angle, distance, texture_on_shadow.
- **Harmony** (procedural): sketchy, shaded, chrome, fur, longfur, web, squares, circles, ribbon, grid. Our own implementation based on the description "lines to the nearest previous points".
- **Fill brush**: the stroke = an outline, filled inside (solid / pattern / procedural).
- **Filter brush**: target = layer-under-stroke or stroke. A filter from the list + strength −100..100.
- **3D brushes** (tube, ribbon, twisty): a dab with shading by the normal along the trajectory. P3.

### 2.7 Dynamics (curves)
- Sources:
  - pressure;
  - velocity;
  - tilt (altitude);
  - azimuth;
  - twist/barrel;
  - random;
  - stroke progress;
  - (finger: pressure = 1).
- Targets: size, flow, opacity, scatter, angle, hardness, tex_depth, mix_in, dilution, color jitter, blur (ibis "blurring").
- Each link = a curve (Bézier/monotonic cubic spline, 2–8 nodes) + min.
- Global calibration of pressure / velocity / tilt (Settings) is applied before the brush curves.
- tilt_offset: offset of the size growth relative to the tip.

## 3. Tool modes
- **Paint**.
- **Blend** (the smudge part only, no color is deposited).
- **Erase** (destination-out, flow/opacity work).
- **Clone** (source: layer + offset/aligned, or artistic: color from the source).
- Separate preset tools: Smudge (= Blend), Blur (filter brush gaussian), Liquify (deformation brush).

## 4. Stabilization (order)
1. Predicted + coalesced points.
2. Lazy rope (if radius > 0).
3. Real-time: EMA with α = f(smoothing, speed).
4. Pen-up: after_smoothing. Re-fitting of the whole stroke (spline smoothing) and redraw of the buffer.
5. Straight-line mode: a segment from the first to the last point.

## 5. Performance (targets)
- pen → pixel latency ≤ 1 frame (16 ms) with size ≤ 300 px on an average 2022 tablet.
- A dab is drawn as an instanced quad. Head and texture are atlases. The stroke buffer is drawn only for dirty tiles.

## 6. Brush format (.pbrush)
`brush.json`: {schema, id (UUID), parent_id, name, category, params{...}, dynamics[{source, target, curve:[[x,y]...], min}], resources{head: "head.png", texture: "tex.png", particle: ...}}.
