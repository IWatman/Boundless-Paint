# ibisPaint: Canvas tool

Source: ibispaint.com/lecture №39, 40, 41, 77, 178.

- **Change Canvas Size**: W/H px, Keep Aspect Ratio, **Origin** (the anchor).
- **Trim**: the area handles, ✓.
- **Resize**:
  - the px mode (for the screen) / **Print** (mm + dpi);
  - Keep Aspect;
  - Interpolation: Bilinear / Color Average / Nearest Neighbor.
- **Color Mode**: Color / Grayscale / B&W.
- Flip canvas H/V (Layer window).
- dpi is set on creation and in Resize.

## What to add to IP
- An Origin/anchor with an explicit choice of 9 points.
- Resize in physical units + dpi.
- A choice of interpolation.
- A document color mode.
- IP is richer: non-destructive Crop, Straighten, Panels.
