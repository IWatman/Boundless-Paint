# ibisPaint: brush parameters and the stabilizer

Source: ibispaint.com/lecture (№118 "Details of Brush Parameters", №50 "Stabilizer", №200, №201). The doc gives no ranges.

## Brush Window tabs
- Basic.
- Fade.
- Shape.
- Jitter.
- Type (Brush only).
- Dynamic.
- Settings.
- Texture (for Eraser, Smudge, Blur).

## Basic / Fade
- **Thickness**, **Opacity**.
- Thickness of Start / End (a fraction of Thickness). Opacity of Start / End.
- **Force Fade Out**: a boosted falloff at the end of the stroke.
- Blur along the stroke length:
  - **Blurring Shape** (the blur shape, off = disabled);
  - Blurring Degree;
  - Blurring of Start / Middle / End;
  - **Jitter Blurring**.

## Shape
- **Brush Pattern** (the shape of the head).
- **Spacing** (a fraction of Thickness).
- **Pattern Opacity**.
- **Opacity Saturation**: within a stroke, do not exceed Opacity. Off = it darkens with each pass.
- **Decrease Opacity**.
- **Initial Angle**, **Following Rotation** (along the stroke direction).
- **Aspects** (flattening of the head).
- **Antialias**.
- **Separate Each Stroke**: off = overlapping strokes merge.
- Extra options:
  - **Dots** (draw as discrete dots);
  - **Add Opacity** (Add blending of the dabs);
  - **Constant Opacity**;
  - Spacing for <3 px;
  - **MSAA** (a high-quality edge);
  - **Bind Center on Elliptical Ruler** (the head points at the center of the ellipse);
  - **Billboard Display on Array Ruler** → Fixed Thickness, Array in Perspective (chained brushes and lace look correct in perspective);
  - Max Spacing for 30 px+ (removes seams).

## Jitter
- Jitter Position, Thickness, Opacity, Spacing, Angle.
- **Scatter**:
  - Absolute Particle Size (px) / Particle Size;
  - Particle Density;
  - Particle Deviation (<0 outward, >0 toward the center);
  - Angle Based on Center.

## Type (brush type)
- **Mono**: the white level of the pattern = alpha, the color comes from the current one.
- **Water**: mixing with the layer below.
  - Color Mix;
  - Wetness (alpha blending, lighter);
  - Dragging (dragging the color along).
- **Double**: an outline/shadow around the stroke (two colors).
  - Select Shadow Color;
  - Body Color;
  - Absolute Shadow Size / Shadow Size;
  - Shadow Angle;
  - Shadow Distance;
  - Apply Texture Shadow.
- **Color**: a colored pattern stamp. The formula: `Final = Pattern + Selected − Base`, the hue is shifted by the base↔selected difference, S/B is not brighter than the original.

## Texture
- Texture Pattern.
- Opacity.
- **Lower Limit**: 0% = the texture cannot be painted over with a second pass, higher = it can.
- Absolute Texture Size (from the original or from the canvas).
- Scale, Angle.
- **Moving**: the texture is fixed to the canvas or moves with the stroke (smearing).
- Texture Antialiasing (off for pixel art).
- Invert.

## Dynamic
- Speed → Thickness / Opacity / Blurring.
- Pressure → Thickness / Opacity / Blurring.
- Sliders, not curves.
- A global pressure curve in Settings (a graph with nodes), Weak Sensitivity.

## Settings
- **Min / Max Thickness** (the slider bounds).
- The brush **Blending Mode**.
- Rename.
- Reset (basic → factory, custom → the state when it was added).

## Stabilizer
- Set for Current Brush (per-brush or shared).
- **Method**:
  - **Real Time**: Constant (the strength does not depend on speed) + Fast Strokes (it grows with speed), the two can be combined. There is a Use Legacy Stabilization option;
  - **After**: smoothing of the whole line after release, with a strength slider.
- **Fade (force taper)**:
  - Force Fade;
  - Start Length;
  - End Length;
  - Fade Shape: Smooth (a rounded tip) / Linear (a sharp one).
  - Per-brush: Custom Force Fade, Custom Stabilizer Settings.

## Additional brush tools (in Tool Select)
- Eraser (its own brush + a Texture tab).
- **Smudge** (finger).
- **Blur**.
- Special Pen: Liquify Pen, Lasso Fill, Lasso Eraser, Copy Pen (see 19/09/20).

## Your own brush patterns (Brush Pattern editor)
- My Gallery → New → Brush Pattern. A login is required.
- Template brush → type (Mono / Color / Texture Pattern / Blurring Shape) → size → the editor = an ordinary canvas with layers → Test Drawing → automatic upload to the server → registration of the custom brush.
- A **Base Color** is set in a Color pattern.
- Patterns appear in the "Original" category in the Shape, Type and Fade tabs. They work for Eraser, Smudge, Blur.
