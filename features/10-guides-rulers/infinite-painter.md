# Infinite Painter: Guides (rulers and stabilizers)

Source: User Guide /painter/guides/*.

## General
- A guide = a geometric constraint for many free strokes (like a ruler).
- A shape = a single editable stroke (see 11-shapes).
- 5 guides in Creative Tools: Lines, Ellipse, Hatching, Lazy, Pen (the 5th is visible after scrolling the row).
- Closing: tap the floating icon at the top right (mini-toolbar).
- They are compatible with Symmetry and with each other.

## Lines guide
- An axis with two handles (drag = angle and position). Rotation snapping to 0°, 45°, 90°.
- Strokes anywhere on the canvas run **parallel or perpendicular** to the axis. The direction is determined by the start of the stroke.
- **Center tick**: the middle mark. The handles are at the ends of the segment → this is how we find its middle.

## Ellipse guide
- Handles:
  - Flatness (the minor axis, all the way = a circle);
  - Center (position);
  - Angle (rotation);
  - Scale (the size of the guide, it does not affect drawing);
  - the minor-axis indicator;
  - the outline.
- What can be drawn: concentric ellipses anywhere, arcs, radii. The stroke type is determined by the direction at the start.
- Moving the guide snaps to the line of the minor axis, which is handy for cylinders in perspective.

## Hatching guide
- Hatching in one continuous motion: only segments of one direction are drawn, the return pass does not draw.
- It combines with Lines (parallel straight lines), Ellipse (concentric and radial) and Perspective.

## Lazy guide (a stabilizer "on a leash")
- The brush tip is dragged behind the stylus at the radius distance. A circle and a direction line are shown on the canvas.
- Tool settings: **Guide radius** (0 = no effect), **Elastic** (the radius grows with speed).
- Separate from Smoothness in the Brush Editor.

## Pen guide (a Bézier guide)
- Building the path:
  - a tap = a cusp node;
  - a short drag = a smooth node;
  - a tap on the line = insert a node;
  - a tap on a node = smooth↔cusp;
  - dragging a node onto a neighbor = delete it;
  - Close/Open.
- **Lock** (irreversible) → you can then draw as many strokes along the path as you like, with any brush and in any mode. The brush can be changed.
- ✓ = delete the guide and start a new one.
- Lock and Close "stick" to the first node.
