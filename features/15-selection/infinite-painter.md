# Infinite Painter: selections

Source: User Guide /painter/selections/*.

## Model
- A selection = an **8-bit mask** (soft edges are possible). It works as a stencil for brushes, fills, filters. It can itself be transformed and edited.
- Selection workspace: while it is open, **the regular tools edit the mask**, not the layer. Brushes, Pattern tools, Liquify, filters (not all of them are useful for 8-bit), mask transform all work.

## Workspace (shared toolbar)
- The tool list (tap the name of the current one).
- Mode:
  - **Normal (add)**;
  - **Subtract**.
- Cancel (close and clear), Confirm (close, the selection is active).
- Operations on the mask, operations on the content. The toolbar scrolls.
- Top bar:
  - Selection menu (only if there is a selection): the same operations + Mask appearance, without the tools;
  - the Active toggle (red). Turning it off does not erase the mask. The `\` key.

## Tools (8 + layer)
- **Lasso**: a free contour.
- **Rectangle**: rotate, resize, move, stretch handles. Stamp.
- **Circle**: from the center. Stretch → an ellipse. Move, resize, rotate. Stamp.
- **Polyline**:
  - a tap = a node;
  - a drag = a free line;
  - holding ~1 s = precision-dragging a node;
  - ✓.
- **Path**: Bézier (drag = smooth, tap = cusp, tapping a node = toggle, dragging onto a neighbor = delete), close, ✓.
- **Wand**:
  - like bucket, but it creates a selection;
  - a tap = preset tolerance, drag = Live Tolerance;
  - the tolerance is shared with Fill (1–100%);
  - sampling multi/single layer;
  - source layer.
- **Brush**: painting a selection with any brush. Paint adds, Erase removes. The color is ignored.
- **Color range**: tap a color → all similar colors on the current layer, non-contiguous ones too. Its own tolerance, independent.
- **Select Layer** (Layer options): the opaque pixels of a layer, group or mask.

## Compatibility with guides (table from the docs)
- Rectangle, Circle, Polyline — this is Lasso with a different shape type.
- Brush selection is compatible with everything.
- Wand and Color range are not compatible with anything.
- Lasso is compatible only with Symmetry. Rect/Circle add Lines/Ellipse/Pen guides and Grids.

## Boolean (Layer options → Boolean, when a selection already exists)
- A = the selection, B = the layer's area:
  - Select (replace);
  - Add;
  - Subtract A−B;
  - Reverse Subtract B−A;
  - Intersect;
  - Difference (XOR).

## Operations on the mask
- Select all.
- Invert.
- Clear.
- Transform mask (all transform modes).
- **Expand** / **Contract**: a slider 0% → max 6 px, apply repeatedly.
- **Feather** (= a mask blur).

## Operations on the content
- Transform.
- **Duplicate**: into a new layer without a name, opacity, blend and masks. An empty selection = the whole layer.
- **Isolate** (cut → a new layer).
- **Copy Merged**: from all visible layers, it opens immediately in Transform. Empty = the whole canvas.
- **Delete** (the active layer only).
- **Clipboard**: into the internal permanent buffer-library (see 23).
- **Make Brush**: a fragment → Brush Creator as a head.
- After the operation the selection is cleared. To reuse the mask: save it into a layer mask.

## Mask appearance
- **Marching ants** or **Color overlay** (default gray 50%). The color and opacity of the overlay (100% is convenient for manual mask editing).
