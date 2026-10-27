# ibisPaint: fills

Source: ibispaint.com/lecture №12, 13, 82, 139, 196, 72–74 (gradation filters).

## Bucket tool (the window behind the "%" button)
- **Strength**: the tolerance of similarity to the starting point (0% = exact match, 100% = the whole layer).
- **Unpainted Recognition**: neat filling inside lines of uneven thickness.
- **Expansion** (default **1.5 px**): expansion/contraction of the area. A negative value for balloons. It closes the white gaps at antialiased edges.
- **Gap Recognition**: closes small breaks in the lines.
- **Continuous Filling**: a swipe across the canvas fills every area it crosses.
- **Under Line**: filling under the line (the color goes under the line art).
- **Boundary**: Color (RGBA) / Opacity.
- **Reference Layer**: Canvas (default) / Current Layer / Specific Layer.
- **Easy Settings** (a simplified mode).
- Reset. The "Save Bucket Settings" option in Settings.

## Bucket sub-modes
- **Bucket** (fill).
- **Eraser Bucket**: a tap = erase the area (make the background transparent).
- **Surrounding Fill**: trace an area with a finger → fill all the enclosed areas inside.
- **Surrounding Eraser**: the same, erasing.
- Their options:
  - **Scrape** (contraction against overflow on soft lines, default 1.0);
  - Boundary;
  - Strength;
  - Gap Recognition;
  - Expansion;
  - **Fill from Underside**;
  - Reference Layer.

## Special Pen
- **Lasso Fill**: traced → filled. Opacity.
- **Lasso Eraser**: traced → erased. The **Erase All Layers** option (all visible layers).

## Gradients
- There is no separate gradient tool. Through the Draw filters: Parallel / Concentric / Radial Line Gradation (see 18). Plus an airbrush + clipping.

## What to take into the IP model
- Expansion (±px).
- Gap Recognition.
- Unpainted Recognition.
- Continuous Filling.
- Under Line / Fill from Underside.
- Boundary: color or opacity.
- Eraser Bucket.
- Surrounding Fill/Eraser.
- Lasso Fill/Eraser (+ all layers).
- IP already has: Source layer = Reference Layer, Live Tolerance, all the gradients, Pattern fill.
