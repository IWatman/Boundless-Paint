# ibisPaint: Shape tool and Drawing tools

Source: ibispaint.com/lecture №51, 82, 93, 105–109, 212, 214, 216, 217.

## Shape Tool (a toolbar button, the icon = the current shape)
- OFF = free drawing (+ Smart Shape).
- Shapes:
  - Line;
  - Rectangle;
  - **Rounded Rectangle** (Radius 1–50% of the short side, a yellow handle, the radius shrinks automatically);
  - Circle;
  - Ellipse;
  - **Regular Polygon** (Corners up to 16);
  - **Thin Arrow**;
  - **Speech Bubble**.
- Common options:
  - **Draw Line by Brush** (the shape, thickness and opacity of the brush) or a plain line;
  - **Fill**;
  - **Fill with Current Color** (or a separate fill color);
  - brush opacity 0 = fill only without an outline;
  - **Anti-aliasing** of the fill.
- Edit shape (the button at the top after drawing): the shape and size handles, a rotate handle. ✓ or switching tools = commit.
- On a vector layer the shape stays editable.

### Thin Arrow
- Drag from the start to the tip.
- **Arrowhead Drawing Order** (for brushes with Fade): One Stroke / From the Tip / From Both Ends.
- Fill (a filled triangle).
- Handles: the tip (length and direction), the ends, the middle (bend = a curved arrow).

### Speech Bubble
- An oval balloon with a tail to the point where you release.
- Tail handles:
  - tip;
  - base (only inside the balloon);
  - base edge (width);
  - middle (bend).

## Smart Shape (recognition)
- Hold at the end of a stroke → a dashed candidate. While still holding, dragging adjusts the angle and size. Releasing = the correction.
- Candidate icons: a tap = switch.
- Recognized: curve, line, rectangle, regular polygon, circle, ellipse, polyline.
- Settings:
  - **Time for Activation** 0.1–5.0 s;
  - Bézier Correction Strength;
  - Polyline Correction Strength;
  - **Shape Priority** for each shape;
  - Reset.

## Drawing tools (in the Stabilizer window)
- Straight Line.
- Rectangle.
- Circle.
- Ellipse.
- Regular Polygon (up to 16 corners).
- **Bézier Curve**: a tap = a pass-through point, drag a point, drag the line = move, 2 fingers = zoom/rotate, ✓.
- **Polyline**: the same.
- On a vector layer they are converted to Polyline/Curve for vertex editing.

## Mapping to IP
- IP: Line, Rectangle, Circle, Path, Arc, Stamp, shape detection (8 shapes), shapes in perspective.
- To add from ibis:
  - Rounded Rectangle;
  - Regular Polygon;
  - Arrow;
  - Speech Bubble;
  - Fill/Outline options right in the shape (in IP only through a fill brush);
  - Shape Priority and the detection activation time;
  - alternative candidates;
  - vector shapes on a vector layer.
