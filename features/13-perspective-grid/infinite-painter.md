# Infinite Painter: perspective grids

Source: User Guide /painter/perspective/*.

## Types (6)
| Grid | Geometry | Handles |
|---|---|---|
| 1pt | 1 VP | move the VP (including off the canvas) |
| 2pt | 2 VPs, verticals stay parallel | 2 white VP handles, the horizon is implicit |
| 3pt | 3 VPs, the verticals converge | VP handles |
| Curvilinear (5pt, fish-eye) | a center + 4 VPs at the sides of a control circle | control handles (scale/VP), move |
| Isometric | 3 directions at 120°, no foreshortening | — |
| 2D grid | orthogonal, a square by default | size, ratio |
- Isometric and 2D grid are hidden in a scrollable row.
- There are no numeric settings: it is not CAD.

## Controls (the same for all)
- Tool settings:
  - Lock grid;
  - **Snap** (default ON);
  - **Visibility** (default ON);
  - Reset.
- Mini-toolbar: Close, Snap, Visibility.
- A hidden grid keeps snapping.

## Snapping
- Free strokes go toward any VP. The direction is chosen by the start of the stroke. Red guides at the cursor in real time.
- Rectangle and Circle settle onto virtual planes (see 11-shapes). Colored guides.
- Line does not snap in 3D. In a 2D grid it snaps to the intersections.
- 2D grid: free strokes are parallel to the grid lines but do not stick to the lines themselves. Shapes snap to the intersections.
- The other tools ignore snapping, though guides may be shown.
- A tip from the doc: a VP far from the canvas = a "normal lens", close = fish-eye.
