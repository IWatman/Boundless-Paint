# Infinite Painter: Shapes

Source: User Guide /painter/shapes/*.

## General
- A shape = one geometric stroke with the current brush. Any brush, any mode (Paint, Blend, Erase, Clone).
- Editable until you tap elsewhere, start a new shape, or close the tool.
- 5 tools: Line, Rectangle, Circle, Path, Arc.
- Mini-toolbar:
  - the tool icon (close);
  - **Stamp**: keep a copy and continue editing. Each copy can have its own size, opacity, color and rotation.
- Filled shapes = a shape + a Fill brush.
- Shapes are raster: after committing they are ordinary pixels.

## Line
- 2 endpoints, editable after drawing.
- Touch-Alt: 15° steps.
- It does not snap to perspective (deliberately): that is how temporary vanishing points are constructed.

## Rectangle
- Handles:
  - start / end corner;
  - move;
  - corners (resize);
  - vertical / horizontal stretch;
  - rotate.
- Touch-Alt: a square while drawing, 15° rotation steps.

## Circle
- A circle, which turns into an ellipse when edited. Touch-Alt: an axis in 15° steps.

## Path
- A Bézier/polyline as in the Pen guide: smooth = G1, cusp = G0. Close, Confirm (✓ = commit), Stamp.

## Arc
- An arc of a circle: start, middle (curvature or radius), end.

## Shape detection
- Hold the stylus ~1 s at the end of a stroke → the stroke turns into a clean shape.
- 8 shapes are recognized:
  - Line;
  - Arc;
  - Path smooth;
  - Path polyline;
  - Triangle;
  - Rectangle;
  - Quadrangle;
  - Ellipse/circle.
- More than 4 corners → an ellipse.
- Right after recognition:
  - the last node is edited;
  - for an ellipse and a rectangle the size and angle are edited;
  - after that, a tap on the line = ordinary shape editing.
- Can be disabled in Settings.

## Shapes in perspective
- Rectangle and Circle in the active perspective grid (except flat 2D) are drawn on one of the 3 planes.
- The plane is chosen by the initial direction of the stroke (toward the vanishing point or along the grid lines). After drawing, the plane does not change.
- Editing (move, resize, rotate) is projected onto the plane.
- Curvilinear: foreshortening only, no curvature.
- Stamp works.
