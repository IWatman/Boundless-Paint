# Infinite Painter: transforms

Source: User Guide /painter/transform/*.

## Objects of the transform
- The selected fragment (Selection toolbar → Transform). It can span several layers at once.
- The whole current layer (the default if there is no selection).
- **A set of layers**: in Transform mode a tap on a layer preview adds or removes it (a red frame). Groups too. With a selection, the fragment on each layer is transformed.
- The selection mask (Transform mask), a layer mask.
- Not possible: editable Text and Panels (7.2), rasterize first.
- The workspace opens automatically after Import image, Duplicate, Isolate, Copy Merged.

## Workspace / toolbar
- Cancel.
- **Unlock bounding box**: transform only the cage, without the object. Not for Warp. Tip: a square cage for controlling perspective.
- Mode selector.
- **Flip** (horizontal only. Through the center or the anchor. Vertical = flip + 180°).
- **Rotate 45°** (clockwise only).
- **Stamp**.
- Confirm.
- The modes can be alternated within one session.
- The cage is aligned to the canvas edges (v7).

## Modes
- **Basic** (the default):
  - a drag anywhere = move;
  - a corner handle = scale (proportionally from the center) or rotate, depending on the drag direction;
  - two fingers inside the cage: move, pinch-scale, rotate. Outside the cage the gesture = navigation;
  - Touch-Alt: move in 45° steps, resize in 100% steps.
- **Anchor**: like Basic, but the pivot = a movable point (it can be outside the canvas). It is also the flip axis and the center of Rotate 45°.
- **Distort**:
  - a 4×4 grid;
  - side handles: perpendicular = stretch, along the edge = skew;
  - corner handles = free distort / perspective;
  - the object and its stamps move in a virtual perspective plane as long as you don't touch the handles.
- **Warp**: a spline grid, 3×3 handles, the cage sides = Bézier (the tangent handles are hidden). You can pull any point inside. The analogue of Photoshop Warp.

## Stamp
- A copy at any moment, in any mode, including in quick gestures.
- The original receives the final transform. The copies land on a new layer with the same name. Cancel keeps the layer of copies.

## Virtual transformations (an important feature)
- The layer stores the original until the first edit (a brush stroke, for example). A chain of transforms does not lose quality, parts hidden behind the canvas edge are restored.
- The analogue of a PS Smart Object, but always on.
- Implementation: store a source bitmap + an accumulated matrix or grid. Commit on a destructive edit.

## Liquify
- A separate deformation tool (see 19).
