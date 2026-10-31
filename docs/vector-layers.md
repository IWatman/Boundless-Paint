# Vector layers (M4)

A vector layer is a `VectorLayer` node: a `RasterLayer` whose `pixels` grid is a cache and whose
`strokes` list is the editable source of truth. The cache keeps the existing CPU compositor, masks,
blending, filters and GPU renderer unchanged.

## Model and rendering

- `VectorStroke` stores the recorded points (position, pressure, tilt, azimuth, twist, time), the
  `BrushParams`, colour, paint/erase mode, seed, zoom, view rotation and the symmetry snapshot.
- The ViewModel reads the post-stage points from `StrokeEngine.recordedPoints()` on pen-up. Calibration,
  stabilization, lazy rope and guide snapping are therefore already applied; replay disables them.
- `VectorRenderService` rebuilds the cache with the ordinary `StrokeEngine`: `appendStroke` paints one
  path on top of the cache, `renderAll` clears the cache and replays every path.
- Persistence uses `NodeDto.kind = "vector"` and the `vector` JSON object (`VectorCodec` schema 1).
  `ProjectIO.load` restores the paths and calls `renderAll`, so every layer kind keeps working through
  the normal raster pipeline. `deepCopy` copies the paths.

## Undo

`VectorStrokeCommand` snapshots the path list before/after and stores the pixel patch produced by the
interactive engine. Undo and redo restore both the cache and the paths without re-rendering.
`ClearLayer` clears and restores the paths of a vector layer as well.

## UI

- "Add vector layer" in the layer panel header inserts an empty vector layer above the active node.
- The layer thumbnail shows a small "V" badge.
- Paint and erase brush strokes on the active vector layer are recorded automatically; every other
  tool keeps working on the raster cache.

## Limitations

- Only paint and erase brush strokes are recorded. Shapes, fills, gradients, transform, canvas
  resize/crop, filters and merge-down edit the cache without adding paths; a project reload re-renders
  the cache from the vector paths and therefore drops those non-vector edits.
- A stroke clipped by a selection is recorded without the clipping mask. Textured or bitmap-head
  brushes replay with `BrushResources.EMPTY` in `ProjectIO` (the loader has no brush library), so such
  layers can look different after reopening. Live-session pixels are exact because the interactive
  engine produced them.
- Sub-strokes produced by a lift are flattened into one point list.
