# Tool specs M0–M1

## T1. Canvas navigation
- 2 fingers: pan + pinch zoom (0.05×–64×, at the gesture center) + rotate (free, snap to 0/90/180/270 ±3°).
- Fast pinch and release (scale < 0.8 in < 250 ms) = fit. Double tap with 1 finger = fit (configurable).
- The canvas is rendered with mipmap at zoom < 1, nearest at zoom ≥ 4 (pixel view option, otherwise linear).
- Background around the canvas: #2B2B2B (dark) / #D0D0D0 (light).
- Acceptance: 60 fps while panning on a 4096² canvas with 10 layers.

## T2. Brush (Paint)
- M1 parameters: size, opacity, flow, spacing, hardness, pressure → size (linear curve, min 20%), pressure → flow (off), smoothing (EMA), taper start/end, color.
- Heads: procedural round (hardness → profile `smoothstep(1, h, r)`).
- Stroke buffer + cap opacity.
- With a finger pressure = 1. With a stylus, `getPressure()` and the history (`getHistoricalX`) are used for every point.
- Acceptance:
  - a straight line with no gaps at spacing ≤ 25%;
  - round ends;
  - opacity 50% does not accumulate inside a stroke;
  - undo removes the whole stroke.

## T3. Eraser (Erase)
- The same brush, composited destination-out. Its own selected brush for the mode.

## T4. Blend (smudge, M2) — in M1 the button is visible but inactive.

## T5. Undo / Redo
- Buttons at the bottom left. 2-tap (undo) and 3-tap (redo) gestures: touches within 250 ms, no movement > 20 px.
- Long-press undo = clear the layer (with a toast confirmation and the ability to undo).
- Limit of 100 steps.

## T6. Main toolbar
- Paint · Blend · Erase · Size (virtual slider + popup: size, hardness, angle) · Color (tap = Color panel, drag up/down = brightness, drag outward = eyedropper in M2) · Opacity (virtual slider + popup: opacity, flow, hardness. The selected item controls the slider).
- 3 fingers vertically = size, horizontally = opacity.
- HUD with a size ring.

## T7. Color panel
- Tabs:
  - HSB wheel (default style: ring + S-B square);
  - Sliders (HSB / RGB + hex);
  - Palette (one standard palette + 10 recent).
- Previous/current swatch. Adding a color to the palette.

## T8. Layer panel
- On the right, toggled in the top bar. Collapsed (preview) / expanded.
- Add, delete, duplicate, clear, merge down, reorder (long-press drag), visibility, opacity 0–100, blend (12: normal, multiply, screen, overlay, darken, lighten, color dodge, color burn, soft light, hard light, add, difference), alpha lock, clip, rename.
- Paper: color, on/off.
- Layer limit by the GAPS formula.

## T9. Projects and gallery
- Home: "New canvas" (size: screen / presets / custom, px), Recent.
- Projects: preview grid, open, rename, duplicate, delete (with confirmation). No folders in M1.
- Autosave on leaving Studio and on `onStop`. Manual Save.

## T10. Export
- Options → Export: PNG (transparent background yes/no), JPG (quality). Via `ACTION_CREATE_DOCUMENT` (SAF) or Share.
