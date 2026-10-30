# M2 implementation plan

Goal of M2: parity with the IP foundation and the full stock brush set. Tool specs:
- [tools/m2-brush.md](tools/m2-brush.md): brush engine, catalog covering every engine feature (≈90; the full ≈236 moves to M3+), browser, editor, stylus, tool state;
- [tools/m2-select-transform-fill.md](tools/m2-select-transform-fill.md): eyedropper, selection, transform, bucket, gradient;
- [tools/m2-layers-adjust.md](tools/m2-layers-adjust.md): groups, multi-select, masks, 33 modes, adjustment layers, adjustments;
- [tools/m2-guides-shapes.md](tools/m2-guides-shapes.md): guides, shapes and detection, symmetry, perspective.
- [tools/brush-import.md](tools/brush-import.md): brush import/export, planned for M3 (kept here for reference).

If the specs diverge, this file wins.

## 1. M2 architecture decisions
| # | Decision | Why |
|---|---|---|
| A1 | Dabs and raster operations are computed on the CPU in `:core`, tile-parallel. The GPU only composites and previews. GPU dabs are done only if the benchmark fails (m2-brush §6) | determinism, JVM tests, a single implementation. Deviation from ROADMAP "GPU dabs" |
| A2 | Layer tiles are stored in half (8 B/px), stroke buffers in float32 (`Tile(precise = true)`). Tiles are uploaded to the GPU as `GL_HALF_FLOAT` | ✅ done |
| A3 | `MaskGrid(width, height, defaultValue)` — a single type for the selection (default 0) and layer masks (0 or 255) | ✅ done |
| A4 | Symmetry replicates **dabs**, not points | identical spacing and random values for the copies, one patch |
| A5 | Edits of guides, symmetry and grids do not go into History | this is view state |
| A6 | Layer multi-select is UI state. Commands accept lists of ids | |
| A7 | Non-destructive adjustment layers; strength is set by the layer opacity | like ibis |
| A8 | Watercolor — CPU simulation at half resolution (m2-brush §1.9) | instead of the GPU simulation from GAPS |

## 2. Unified stroke contract (reconciles the four specs)
```kotlin
// core/stroke/Pipeline.kt (✅ exists): PointSink, PointStage, Interpolation, SymmetryCopies, FillSpec,
// StrokeOptions(stabilize, stages, interpolation, closed, copies, fill, clip), StrokeTarget(Layer | Mask | Selection)
class StrokeEngine(
    doc: Document,
    target: StrokeTarget,
    brush: Brush,
    res: BrushResources,
    color: Rgba,
    mode: StrokeMode,              // PAINT, BLEND, ERASE, CLONE
    ctx: StrokeContext,            // zoom, viewRotation, seed, lowerComposite, paperTexture, clone, threads
    options: StrokeOptions = StrokeOptions(),
) {
    fun begin(p: InputPoint); fun add(points: List<InputPoint>); fun cancel()
    fun end(): StrokeCommit?       // Pixels(TilePatch) | Mask(MaskPatch); applied to the document
    val buffer: TileGrid; fun takeDirty(): Set<TileKey>; val previewOpacity: Float
}
// M1 compatibility: StrokeEngine(doc, layerId, params, color, mode) stays as a wrapper, end() gives TilePatch?.
```
- `InputPoint` got `twist` (✅). `Dab` carries `angle` and `flipX` for mirrored copies.
- Stage order: stabilizer → `options.stages` (lazy, snap, hatching) → resampler (`interpolation`, `closed`) → dab generator → symmetry copies → kernels → buffer → composite (cap, brush blend mode, alpha lock, `clip`).
- Mask/Selection target: the mask value = stroke coverage × color brightness; the eraser decreases it.
- `core/geom/Affine2`, `Vec2` (✅ exist).

## 3. Shared types ready before the phases start (✅)
- `core/raster`: `Tile` (half/precise, `t[i]`, `read/write`, `scale`, `toFloats`, `copyHalvesTo`, `ofHalves`, `contentEquals`), `TileGrid(precise)`, `MaskTile` (`isUniform/isEmpty/isFull/fill`), `MaskGrid`, `IntRect`, `MaskPatch(targetId)`, `BitPlane`.
- `core/doc`: `Selection` (`Document.selection`), `LayerMask` (`Node.mask`), `ColorTag` (`Node.colorTag`), `Node.reference`, `EditTarget` (`Document.activeTarget`), `Document.referenceNode`.
- `core/blend/Compositor.flattenTile(doc, key, includePaper)`.

## 4. Phases and package owners
Agents write only to their own packages and their tests. Only the coordinator changes the shared types from §3.

### Phase 1: core (parallel, 4 streams)
| Stream | Owns | Content |
|---|---|---|
| K (brush) | `core/stroke` (except Pipeline.kt), `core/io/BrushIO.kt` | m2-brush S0 + S1: `LayerWorkingSet`, `TilePool`, `TileScheduler`, hash-RNG, `PathWalker`, `DabGenerator`, `Brush` + codec, re-render on pen-up, lazy rope, after-smoothing, benchmark; bitmap heads, rotation, jitter, dynamics, taper, profiles. Contract §2, including `options` and the Mask/Selection targets |
| L (layers) | `core/doc` (except Masks.kt), `core/adjust`, `core/history` (except SelectionCommands.kt), `core/blend`, `core/io/ProjectIO.kt` | m2-layers S1: `AdjustmentLayer`, `AdjustParams`, `AdjustMath`, `AdjustLut`, new commands, masks and adjustments in Compositor, manifest schema 2 (masks, adjust, colorTag, reference, activeTarget, **selection**, **guides** as an opaque JSON block), blend/adjust test vectors |
| S (selection and fills) | `core/pick`, `core/select`, `core/transform`, `core/fill`, `core/raster/MaskedEdit.kt`, `core/history/SelectionCommands.kt` | m2-select: the whole core of §1–§4 (eyedropper, selection operations, wand, transforms and resampling, bucket, gradients) |
| G (guides) | `core/guide`, `core/symmetry`, `core/shape`, `core/perspective` | m2-guides: `SnapStage`, `LazyRopeStage`, `HatchingStage`, Lines/Ellipse/Pen guides, `SymmetryMath`, perspective grids, shape generation, detection, `GuideState` + serialization (kotlinx) |

### Phase 2: rendering and wiring (after phase 1)
| Stream | Owns | Content |
|---|---|---|
| R (rendering) | `app/render` | masks (R8), adjustment shader, fixes for ADD and clipping to passthrough, selection (marching ants), floating transform preview, gradient and region preview, stroke preview into a mask, parity tests (androidTest) |
| V (wiring) | `app/studio`, `MainActivity` | coordinator: ViewModel for the new APIs, tool input (`ToolInput`), workspaces |

### Phase 3: UI (after phase 2, parallel by screen)
| Stream | Owns | Content |
|---|---|---|
| UB | `app/ui/brush` | browser, editor, stylus settings |
| UL | `app/ui/studio/LayerPanel*`, `app/ui/adjust` | groups, multi-select, masks, modes with previews, adjustment, curves, Levels |
| US | `app/ui/tools` | context toolbars for selection, transform, fill, gradient, magnifier |
| UG | `app/ui/guides` | overlay of guides and handles (Compose), mini-toolbars, symmetry, perspective |

### Phase 4: finish the engine and the catalog
- K: S2–S9 (S2 ∥ S3 ∥ S4 ∥ S7 in parallel, by different agents per kernel file).
- Catalog S10: `tools/gen_assets.py` + `tools/catalog/*.py` → `app/src/main/assets/brushes/`. Categories are connected as their features become ready.

### Phase 5: M2 acceptance
- All JVM tests, brush benchmark, parity tests on the emulator.
- Manual checklist from the "Acceptance" section of each spec on the emulator, stylus — on the XP-Pen when available.
- STATUS.md debts 4, 5, 6, 8, 9, 10, 11, 12, 13 are closed in phases 2–4.

## 5. Scope changes relative to ROADMAP
- Moved into M2: Clone (brush), regular polygon, Hatching, Color Balance, Levels, Gradient map, Posterize, Black & White.
- Moved to M3: curvilinear perspective (5 points and fisheye), Lanczos, keeping the transform source between sessions.
- Remaining in M4: radial and array rulers, rounded rect, arrow, balloon, star, lasso fill across all layers, surrounding fill.
