# Infinite Painter: Brush Editor (brush engine parameters)

Source: User Guide /painter/brushes/settings/*. More than 100 parameters. Where the doc gives no range, it says "—".

## Framework of the editor
- 5 tabs: **Stroke**, **Head**, **Texture**, **Paint**, **Special**.
- Buttons:
  - Cancel;
  - Share (`.prbr`);
  - Save as… (a copy);
  - Confirm.
- Live preview. It does not show color jitter or blending.
- Shortcut: Shift+Ctrl+B.

### Dynamic curve (a shared widget)
- Output (the parameter) ← Input (the source). Tapping the header = on/off.
- The graph: X=input, Y=output. Invert (mirrored along X). Shape presets, the leftmost one = linear (default).
- Nodes: tapping the curve adds one, drag moves it, dragging onto a neighbor deletes it.
- Sources:
  - **Pressure**: without support = 100%, the curve is ignored;
  - **Velocity**: works with a finger too;
  - **Tilt**: 0 = vertical, horizon = 100%.
- Global calibration of Pressure / Velocity / Tilt (Settings) is layered on top of the brush curves.

## Stroke
| Parameter | Meaning | Range |
|---|---|---|
| Size | head diameter, px | the max depends on the canvas × Size range |
| Opacity | the maximum opacity of the stroke | 0–100% |
| Smoothness | smoothing of the trajectory (stabilizer) | — |
| Adhesion | how much "material" accumulates before it is laid down. High = contrasting "clumpy" strokes | — |
| Size range | a multiplier of Size, it sets the maximum for the dynamics | 0.0–4.0 |
| Wet edges | the stroke edges are denser than the middle | — |
| Glaze strokes | >0: overlap within a single stroke does not accumulate (only at opacity<100% and on transparency) | — |
| Blending mode | the blending mode of the brush color, 30 items = the layer modes, each with a **Strength** (how far it differs from Normal) | default Normal |
- Dynamics (size): Pressure, Velocity, Tilt curves + **Tilt Offset** (the direction in which the size grows relative to the tip; + like a pencil, − like an airbrush).
- Jitter: **Size jitter**.
- Profiles along the stroke length: **Size profile**, **Flow profile**.
  - X = the stroke length (not 0–100%), a 3×3 grid: start (fixed length), middle (stretches), end (fixed length);
  - on a short stroke the sections are roughly equal. Invert and presets are available.
- Fill section (fill brushes only): **Draw preview** (global). On = the fill is visible in real time, off = after the stroke is finished.

## Head
- Source:
  - a bitmap (tap = select, + = import);
  - Remove;
  - **Color** (use the RGBA of the bitmap; ignored in Blend, Erase, Clone);
  - **Invert**;
  - **Tonal curve** (a brightness curve of the source);
  - **Colorize** (how much color to take from the bitmap, only with Color).
- Head:
  - **Application style**: how the dabs interact along the stroke (the options are not listed in the doc);
  - **Flow**: the rate at which pigment is delivered, available on the toolbar, has dynamics;
  - **Spacing**: 0–200% of the head width;
  - **Structure**: a simulation of impasto relief;
  - **Softness**: 0 = a hard edge;
  - **Depth**: the opacity of the head as a height map (bristles), at 100% it may lay down no color;
  - **Angle**: 0–360°, default 0;
  - **Rotation**: −100 (Reverse) … 0 (Fixed) … +100 (Tangent, along the direction);
  - **Stylus rotation**: the angle from the tilt or azimuth of the stylus. With a finger the angle = the initial direction of the stroke;
  - **Orient to screen**: the initial angle relative to the screen rather than the canvas.
- Dynamics: **Flow** curves, **Scatter** curves (Pressure, Velocity, Tilt).
- Jitter:
  - Initial: **Angle**;
  - Continuous: **Scatter**, **Angle**, **Flow**.

## Texture
- Source: the same as for Head (bitmap, Remove, Color, Invert, Colorize, Tonal curve).
- **Style**:
  - Fixed: tiles laid edge to edge along the canvas;
  - Rotate: tiles along the stroke direction;
  - Warp: along the stroke with deformation.
- **Depth**: how much the texture limits deposition. 0 = almost invisible, high = only the light areas. Has dynamics.
- **Scale**: 0–2.0, default 1.
- **Stretch**: 1×–50× along the stroke.
- **Structure**: impasto of the texture only.
- **Softness**: Gaussian blur of the texture.
- **Angle**: rotation of the texture relative to the head.
- **Scale with size**: the texture scale follows the dynamic size.
- Dynamics:
  - Depth curves;
  - **Gradation**: the effect falls off from the tip to the tail when tilted. 0 = uniform.
- Jitter:
  - Initial: Position;
  - Continuous: Position, Scale, Angle.

## Paint
- Mix:
  - **Mix-in**: picking up the colors below while replacing the brush color (the brush "loses paint");
  - **Dilution**: picking up without replacement, 100% = a pure blender (watery, waxy);
  - **Wet paint**: with Mix-in/Dilution, transparency is transferred too, otherwise alpha stays in place.
- Blend:
  - **Blur**: averaging of the picked-up color;
  - **Pull**: the length of the smear. 0 = changes quickly, 100 = very slowly, dirties;
  - **Flow**: a multiplier for blending only;
  - **Sample lower layers**.
- Dynamics: 3 **Dilution** curves (pressure/velocity/tilt). They let you switch between "painting" and "blending" by pressure or tilt.
- Color jitter (HSB):
  - Initial: Hue, Saturation, Brightness at the start of the stroke;
  - Continuous: Hue, Saturation, Brightness per dab.

## Special
- **Watercolor** (switch):
  - Bleed out (how much it spreads);
  - Glaze ("the amount of water", not to be confused with Glaze strokes);
  - Mix-in;
  - Dryout (the rate at which the spreading fades).
- **Screentone** (switch):
  - Spacing (the step of the snap grid, px);
  - Angle 0–180°;
  - Scale with size.
- **Particles** (particle brushes only, with no toggle):
  - Attractors 1–64;
  - Particles 1–400;
  - Radius (the offset of the attractors from the path);
  - Particle Size 0.00–0.10 of the brush size;
  - Overshoot 0.00–2.00 (overshoot past the attractor, splashes on turns);
  - Particle head = any bitmap;
  - The brush Size = the diameter of the whole system;
  - the randomness comes from Continuous Scatter on the Head tab.
- **Filters / Color adjustments in the brush**:
  - a selector ("NO EFFECT");
  - target **Layer** (Color + Filters are applied to the active layer under the brush) or **Brush** (Filters are applied to the stroke itself), only one;
  - **Strength** −100…+100, 0 is usually neutral, duplicated by a circular slider on the toolbar instead of the color;
  - Remove.
- Effects are applied after Confirm. Some are incompatible with each other and with Harmony/Fill.
- The library list also shows the [harmony] and 3D effects (Twisty/Tube/Ribbon 3D). Their parameters are not described in the doc; this is a reverse-engineering of the behavior.

## Conclusion for the implementation
The brush model = a stamp-based engine:
- head: bitmap + tonal curve + softness + depth + structure;
- spacing, scatter, rotation;
- texture: 3 mapping styles;
- a smudge model (mix-in, dilution, pull, blur, wet);
- dynamics curves (pressure, velocity, tilt) on size, flow, scatter, depth, dilution;
- profiles along the stroke length;
- HSB jitter;
- the brush blend mode + strength;
- special modes: watercolor simulation, screentone snap, particles, harmony, fill, brush-as-filter.
