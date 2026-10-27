# ibisPaint: transforms

Source: ibispaint.com/lecture №35, 36, 153, 154, 156.

## Translate Scale
- Toggles: Scaling, Rotation, **Repeat** (showing the repeat).
- Numeric: **Movement X/Y**, **Zoom**, **Angle**.
- **Interpolation** (6 methods):
  - Nearest Neighbor (pixel art);
  - Average Pixel (for downscaling);
  - Bilinear;
  - Bicubic;
  - Lanczos 2;
  - Lanczos 3.
- **Target**: the current layer or the whole canvas.
- Show Frame around Object: a touch outside the frame = navigation.
- Gestures: corner = scale, inside = move, the rotation knob, 2 fingers outside the frame = the canvas.

## Perspective Form
- 4 corners.
- Repeat: Off / Inner / Horizon / Full (mirrored alternation).
- Repeat X/Y, Phase X/Y, Interpolation.

## Mesh Form
- Division X/Y, Smoothness, Draw Order.

## Other
- Flip layer H/V, flip canvas H/V (buttons in the Layer window).
- Move Folder (all the layers of the folder).
- Vector and adjustment layers are rasterized on deformation (with a confirmation).

## What to add to IP
- Numeric input (X, Y, zoom, angle).
- A choice of interpolation (6 methods).
- Target = the whole canvas (IP has multi-select of layers, which is the equivalent).
- Vertical flip (IP has only horizontal).
- Repeat tiling in Perspective.
- Mesh N×M.
