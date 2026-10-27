# Infinite Painter: canvas tools

Source: User Guide /painter/canvas/*. They act on the whole project, not on a layer (Editing Tools → Canvas).

## Crop (non-destructive)
- It hides areas, it does not delete them. The hidden part is not editable, but it is restored.
- Toolbar:
  - Cancel;
  - W, H;
  - lock proportions;
  - swap;
  - Confirm.
- A 3×3 grid of handles: the corners = proportionally, the sides = when locked.

## Resize
- The **Resize content** toggle:
  - off: cropping or extending the canvas. What is removed is erased, but the virtual transformation keeps it reversible until the pixels are edited;
  - on: resampling of all layers.
- Toolbar:
  - W/H in px;
  - lock ratio;
  - the old and the new size.
- The resampling method and the anchor are not described. The anchor is set by a grid of 9 handles.

## Straighten
- Rotate (drag), Vertical perspective, Horizontal perspective. They can be combined.
- Reset geometry. Undo works.
- The pixel dimensions of the canvas are preserved. What falls outside the edge is lost.
- Tip: in order not to lose anything, do Resize first, then Distort of all layers.

## Panels (comic frames) → the special Panels layer
- The outer frame W/H, **gutter width**, **gutter color**.
- A divider:
  - starts and ends on the frame or on another divider;
  - can be non-perpendicular;
  - a tap = handles;
  - dragging an end off the line = delete.
- At least 1 divider is needed (to get only the frame: create a divider and delete it).
- Editing an existing layer in real time, without Cancel/Confirm.
- **A stroke started inside a panel is confined to that panel.**
- The layer: opacity, blend, mask, select, it can be a clipping base. Several Panels layers.
- Rasterization: merge with an empty layer.
