# Document model

## Project
```
Project {
  id: UUID, name, created, modified, type: regular | pattern | challenge | animation | manuscript
  width, height (px), dpi, colorMode: rgb | gray | bw
  paper: { color, textureId?, texDepth, texOpacity, texScale, visible }
  root: GroupNode (layer stack; for animation: frames[] each with its own root + shared bgLayer?)
  manuscript?: { trim, bleed, safe, offset, unit }
  pattern?: { tileW, tileH }  // canvas = 2× tile
  guides: { symmetry?, perspective?, grid?, ruler? } // persisted
  references: [ { id, imageRef, transform, visible } ]
  palette collection, recent brushes, recent colors
  timelapse: { enabled, quality, followNav, chunks[] }
  stats: { strokes, timeMs, distancePx, undos }
}
```

## Layer nodes
```
Node { id, name, visible, opacity, blendMode, clipped(to below), hideInRecording, locked, mask?: Mask }
RasterLayer : Node { tiles: TileGrid(RGBA16F, 256²), alphaLock, virtualTransform?: {source: TileGrid, xform: Affine|Mesh} }
VectorLayer : Node { shapes: [VectorShape] }          // P2
TextLayer   : Node { text, font, size, color, outline{mode,width,color}, align, case, spacing{letter,word,line}, justify, vertical, background{color,opacity,marginX,marginY}, frame{x,y,w,rot} }
PanelsLayer : Node { frame{w,h,margin}, gutter{w, h}, color, dividers:[Segment], fills:{panelId: color} }
AdjustmentLayer : Node { kind, params }               // any adjustment or filter
FilterLayer = AdjustmentLayer (kind = filter.*)
ScreentoneProp (on RasterLayer): { pattern: dot|line|..., lpi, angle, physical: bool }  // P2
GroupNode : Node { children: [Node], passthrough: bool (blendMode=passthrough), expanded }
Mask { tiles: TileGrid(R8), enabled, linked, strength }
```
- Paper is not a node but a property of the project (it cannot be drawn on or moved).
- Clipping: a node with `clipped=true` is clipped by the alpha of the nearest lower non-clipped neighbor (the base). An adjustment base is not allowed.
- Compositing order: bottom to top. A group with a normal mode is rendered offscreen, passthrough is rendered inline.

## Tiles
- 256×256, RGBA16F premultiplied. An empty tile = null (not stored).
- The CPU copy is kept only for saving and undo, in compressed form (LZ4 over half-float).
- GPU: a texture pool, LRU eviction when memory runs short.
- Memory limit: show "max layers" = budget / (w·h·8 bytes · fill factor 0.5).

## Selection
- A separate R8 TileGrid mask at the document level + an active flag. Modes set/add/subtract/intersect.

## History (undo)
- Command pattern: `apply()` / `revert()`.
- Pixel commands store before-snapshots of the affected tiles (compressed).
- Structural commands (add/move/delete layer, props) store deltas.
- Session grouping: one stroke = one command. A liquify session = one command.
- Limit: 100 steps or 512 MB, whichever comes first.
- Project versions (save points): full snapshots in the archive's `history/`, limit 0–10 / ∞ (default 6).

## Timelapse
- Recording = a stream of "command applied" events + periodic low-resolution composite frames (default 1080p on the long side), 1 frame per command, no more than 30/s.
- Export: re-encoding into MP4 (MediaCodec H.264) with the required duration.

## Animation (P2)
- frames: [Frame{ id, durationFrames, root }], fps, playMode loop|pingpong|once, onion{before, after, firstOpacity, lastOpacity, colors}.
- Video mode (P3): tracks drawing/audio.
