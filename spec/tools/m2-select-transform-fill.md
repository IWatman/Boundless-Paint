# M2 tool specs: eyedropper, selection, transform, fills

Sources: features/15-selection, 16-transform, 09-fill, 05-color, 02-workspace-ui-gestures; spec/GAPS.md (metrics), spec/UI-SHELL.md (shell).
M2 rule: raster operations are computed on the CPU in `:core` (tile-parallel, `Dispatchers.Default`), the GPU only previews and composites. Everything that is written into the document is computed by the CPU. The GPU preview must match the CPU result to within ≤ 2/255.
No monetization: all modes, interpolations and gradient types are available immediately.

## 0. Common core changes (done by the coordinator, before the other packages)

### 0.1 New common types in `core/raster`
| Type | Essence |
|---|---|
| `MaskTile` + `isEmpty()`, `isFull()`, `fill(v: Byte)` | add the methods, the data format does not change |
| `class MaskGrid(width, height)` | a sparse grid of `MaskTile`, a missing tile = 0. The API mirrors `TileGrid`: `get/getOrCreate/put/keys/entries/copy/compact/clear/keysInRect`, `value(x, y): Float` (0 outside the canvas), `bounds(): IntRect?` |
| `data class IntRect(x0, y0, x1, y1)` | a half-open rectangle in document px. `intersect`, `union`, `isEmpty`, `tileKeys(gridW, gridH)` |
| `class MaskPatch(before: Map<TileKey, MaskTile?>, after: …)` | the analogue of `TilePatch` for the selection mask. `byteSize = n · 65536` |
| `class BitPlane(width, height)` | 1 bit per pixel (`LongArray`), `get/set/clear/or/and/andNot/bounds()`. For fill regions and the wand (4096² = 2 MB) |

### 0.2 Document
- `Document.selection: Selection` (not null, an empty mask = no selection).
- `class Selection(width, height) { val mask: MaskGrid; var active: Boolean = true; val isEmpty: Boolean; val version: Long }`.
- "Active" = `active && !isEmpty`. Only then are the tools masked.
- `Selection.coverageOrNull(): MaskGrid?` returns the mask if the selection is active, otherwise null. All pixel operations use it.

### 0.3 Other common APIs
| Where | What | Why |
|---|---|---|
| `core/blend/Compositor` | `fun flattenTile(doc, key, includePaper: Boolean): Tile?` | composite of a single tile. The eyedropper and canvas fills do not keep the whole canvas in memory (4096² float = 256 MB) |
| `core/history` | `class MaskPatchCommand(patch, name)`, `class SetSelectionActive(on)` | selection undo |
| `core/raster/MaskedEdit.kt` | `fun maskedCommit(layer, result: Map<TileKey, Tile?>, mask: MaskGrid?, name): TilePatch` | the common path: `out = before·(1−m) + after·m` (premult). Used by fill, gradient, Delete and filters (M3) |
| `core/stroke/StrokeEngine` | ctor parameter `clip: MaskGrid? = null` | a stroke under a selection: in `compositeBuffer` `sa *= m`, in direct mode `DabRenderer.render(…, clip)` multiplies the dab coverage by `m` |
| `app/render/CanvasView` | `setSelection(mask?, showOverlay)`, `setFloating(FloatingPreview?)`, `setGradientPreview(GradientSpec?)`, `setRegionPreview(MaskGrid?, color)` | previews (sections 2.7, 3.6, 4.5) |
| `app/render` | `interface ToolInput { onDown/onMove/onUp/onCancel(docX, docY, p) ; onTap }` + routing to the active workspace | workspaces have their own gestures, no stroke begins |

### 0.4 Project format (`core/io`)
- `manifest.json`: `"selection": { "active": true }`. The field is absent = no selection. The schema stays 1 (the new field is optional, old readers ignore it). **Decision.**
- `selection/tiles.idx` + `selection/tiles.bin`: the same format as `masks/<nodeId>/`, R8, LZ4 per tile.
- The selection is always saved (in ibis it is an option, for us the default is without a setting). **Decision.**
- The clipboard, the tool settings and the last gradient are stored in the app settings, not in the project.

## 1. Eyedropper (`core/pick`)

| Parameter | Range | Default |
|---|---|---|
| Source | Canvas (all visible layers + Paper, hidden Paper = white) / Current layer | Canvas |
| Sample size | 1–100 px (circle diameter) | 1 |
| Long-press delay | 200–1500 ms | 500 ms (currently a 600 ms constant in `GestureRouter`, move it to the settings) |

**Algorithm.** Take the pixels in a circle of diameter `d` centered at `(floor x, floor y)`. Tiles come from `flattenTile` or from the layer, they are cached for the eyedropper session (key `doc.modified`). The premult values are averaged, then the color is un-premultiplied. If the mean `α < 1/255`, there is no result. "Current layer" mode: adjustment layers above are ignored. The resulting alpha is always 1, the color is put into the current color and into the recent colors.

**Invocation:**
- long-press on the canvas (default, assignable);
- a drag from the Color button in the main toolbar outward by more than 24 dp (T6);
- an icon in the Color panel;
- a stylus button (assignable, debt 12);
- the `I` key.

The color is updated on move and fixed on up. A tap with a second finger or a drag back onto the button cancels.

**Loupe (UI):**
- A ring of 112 dp diameter, 14 dp thick, its center offset 96 dp up from the point (near the screen edge it moves down or sideways).
- The upper half of the ring = the new color, the lower half = the current one.
- Inside the ring, the pixels under the point at ×8 magnification (nearest) and a 1 px crosshair. The sample circle is outlined if `d > 1`.
- Below the ring the hex of the new color.
- Settings (the eyedropper ⚙, also Settings → Input): source, size, delay, "Loupe on/off" (on).

**API:**
```kotlin
enum class PickSource { CANVAS, CURRENT_LAYER }
class Eyedropper(doc: Document, source: PickSource, layerId: String?, diameter: Int = 1) {
    fun sample(x: Float, y: Float): Rgba?            // straight, a = 1
    fun loupe(x: Int, y: Int, radius: Int, out: IntArray) // ARGB8888 (2r+1)², for the UI
}
```
**Acceptance:**
- a sample of a solid area = the color ± 1/255;
- `d = 5` on a black/white checkerboard gives a gray of ≈ 0.5;
- 60 fps of the loupe on 4096² × 10 layers (tile cache).

**Tests:**
- `pick_singlePixel_currentLayer`;
- `pick_canvas_includesPaper`, `pick_canvas_hiddenPaperIsWhite`;
- `pick_average_premultipliedNoDarkFringe` (semi-transparent red over transparent = pure red);
- `pick_transparent_returnsNull`;
- `pick_outOfBounds_null`;
- `pick_cacheInvalidatedOnModified`.

## 2. Selection (`core/select`)

### 2.1 Model
- An 8-bit mask at document level (`Document.selection.mask`), values 0..1, soft edges are allowed.
- The `active` flag is separate from the mask: turning it off in the top bar does not erase the mask.
- Combining a new shape `S` with the mask `M`, fuzzy operations (**decision**: max/min, without accumulating edges):

| Mode | Formula | Invocation |
|---|---|---|
| New | `S` | default if the mask is empty |
| Add | `max(M, S)` | default if the mask exists (like Normal in IP) |
| Subtract | `min(M, 1 − S)` | |
| Intersect | `min(M, S)` | |

- Touch-Alt (holding a finger while using the stylus) switches Add ↔ Subtract for the duration of the gesture.

### 2.2 Tools
| Tool | Input | Algorithm |
|---|---|---|
| Lasso | a free drag, the contour closes on up | a polyline decimated to a step ≥ 1 document px → `PolygonRaster` |
| Polygon | tap = a node; drag = a free segment; hold for 1 s on a node = drag it; tapping the first node or ✓ = close | the same rasterizer |
| Rectangle | drag from corner to corner. After up the shape stays editable: 8 resize handles, a rotate handle, drag inside = move. The next tap outside the shape = commit and a new shape | shape → 4 vertices |
| Ellipse | drag from the center (IP). Handles as on the rectangle | 256 segments → a polygon |
| Wand | tap = the tolerance from the settings; drag from the point = live tolerance | the `Region` from §4.1 (code shared with the fill) → a mask |
| Selection brush / eraser | Paint and Erase in the main toolbar paint on the mask with any brush, the color is ignored | `MaskStrokeEngine`: a regular stroke into an RGBA buffer, on `end()` the buffer alpha `a` goes into the mask: add `m + a(1−m)`, erase `m(1−a)` |
| Select opaque | Layer options of a layer/group | `m = α` of the layer (for a group: the composite without Paper) with the mode selected in §2.1 (IP boolean operations) |

**PolygonRaster.**
- Scanlines, non-zero rule (a self-intersecting lasso fills the loops). **Decision.**
- AA on: 4×4 coverage supersampling per pixel (16 levels). AA off: the pixel center.
- The work is bounded by the bbox, tiles run in parallel.

**Wand parameters** (⚙, shared with the fill: tolerance, boundary, reference):

| Parameter | Range | Default |
|---|---|---|
| Tolerance | 0–100 % | 15 % |
| Contiguous | on/off | on |
| Source | Canvas / Current layer / Source layer | Canvas |
| Boundary | Color / Opacity | Color |
| Expansion | −20…+20 px | 0 |
| Antialiasing | on/off | on |

Live tolerance: drag distance in dp / 300 dp → 0–100 %, recomputed at most at 15 Hz, the last request cancels the previous one.

### 2.3 Mask operations
| Operation | Parameters | Algorithm |
|---|---|---|
| Select all | | all tiles within the canvas = 255 |
| Invert | | `1 − m` over the whole canvas, missing tiles → full |
| Deselect | | the mask is empty |
| Expand / contract | −100…+100 px, default 0, a slider with a live preview | binarization `m ≥ 0.5`, signed EDT (§4.2), coverage `clamp(e + 0.5 − s, 0, 1)`. The softness of the mask is lost (**decision**), Feather can be applied afterwards |
| Feather | radius 0–250 px, default 8 | Gaussian σ = r/2, three box passes, separable, tiles with a 3σ margin |
| Transform mask | | §3 with the "mask" object |

### 2.4 Content operations
An empty selection means the whole layer or canvas.

| Operation | What it does |
|---|---|
| Copy | the active layer × m → the internal buffer + the system clipboard (PNG via FileProvider) |
| Copy merged | the visible composite without Paper × m → the buffer |
| Cut | Copy + Delete, one command |
| Paste | from the buffer (the fresher of the internal buffer and the system clipboard) → a new layer above the active one, position = the original one (or the center of the visible area if the source is external) → Transform opens immediately |
| Duplicate to layer | the active layer × m → a new layer "<name> copy", above the active one → Transform |
| Isolate | Cut onto a new layer → Transform |
| Fill | the current color, normal OVER, coverage m, respects alpha lock |
| Delete | `maskedCommit` with transparent |
| Transform | §3 with the selected fragment |

- After Paste, Duplicate and Isolate the mask is preserved and transformed together with the fragment (**decision**, a difference from IP: the selection is not dropped).
- `Clear layer` (long-press undo) with an active selection clears only the selected area.

### 2.5 Masking other tools
- Brush, eraser, blend (M2), fill, gradient, Delete, filters (M3): the result goes through `m`. For a stroke via `StrokeEngine(clip)`, for everything else via `maskedCommit`.
- With the selection workspace open, Paint/Erase paint on the mask, not on the layer.
- The selection is off (`active = false`) or empty: no masking.

### 2.6 UI (IP shell)
**Editing Tools → Selection** opens the Selection workspace. The toolbar is at the bottom, scrollable:
- ✗ (deselect and exit);
- tool selector (Lasso, Polygon, Rectangle, Ellipse, Wand, Brush);
- mode segment (New / Add / Subtract / Intersect);
- mask operations (All, Invert, Expand/contract, Feather, Transform mask);
- content operations (Copy, Copy merged, Cut, Paste, Duplicate, Isolate, Fill, Delete, Transform);
- ✓ (exit, the selection stays active).

Other elements:
- **Top bar**, while the mask is not empty: the Selection menu (the same operations without the tools + the mask view) and the Active toggle (accent color, key `\`).
- **⚙** wand and shape AA parameters.
- **Layer options**: "Select opaque" with the modes New / Add / Subtract / Intersect.
- **Paste** duplicated in the Options menu when the buffer is not empty.

### 2.7 Display
- **Marching ants.** A contour along the 0.5 iso of the mask, marching squares on the CPU. Cached by `MaskTile.version`. The segments go into a VBO.
- Dash 1 dp, the shader alternates black/white by `fract((gl_FragCoord.x + gl_FragCoord.y) / 16dp + t)`, a shift of half a period per second. The polylines do not need to be stitched. **Decision.**
- **Overlay.** The unselected area is tinted (the R8 mask texture, color #E0405A, 35 %). It is on inside the workspace, outside it only the ants. The view menu: Ants / Overlay / both, color, opacity 0–100 %.
- Inactive selection: the ants are gray, without animation.

### 2.8 Undo
- Every mask operation = a `MaskPatchCommand` (Lasso, Wand, Expand…). A selection brush stroke = one command.
- Content operations = `CompoundCommand(TilePatchCommand…, MaskPatchCommand?, AddLayer?)`.
- The Active toggle = `SetSelectionActive`. ✗ = a "Deselect" command.
- Live previews (live tolerance, the expand slider) do not go into history, only the final value.

### 2.9 API
```kotlin
enum class SelectMode { NEW, ADD, SUBTRACT, INTERSECT }
sealed interface SelShape { fun toPolygon(): FloatArray }  // xy pairs in document px
data class PolyShape(val pts: FloatArray) : SelShape
data class RectShape(val cx: Float, val cy: Float, val w: Float, val h: Float, val angleDeg: Float) : SelShape
data class EllipseShape(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angleDeg: Float) : SelShape
object PolygonRaster { fun rasterize(poly: FloatArray, w: Int, h: Int, aa: Boolean): MaskGrid }
object MaskOps {
    fun combine(dst: MaskGrid, src: MaskGrid, mode: SelectMode): Set<TileKey>
    fun selectAll(m: MaskGrid); fun invert(m: MaskGrid); fun fromAlpha(g: TileGrid): MaskGrid
    fun expand(m: MaskGrid, px: Float, aa: Boolean): MaskGrid
    fun feather(m: MaskGrid, radius: Float): MaskGrid
    fun contour(t: MaskTile, key: TileKey): FloatArray  // segments for the ants
}
class MaskStrokeEngine(doc: Document, params: BrushParams, mode: StrokeMode) {
    fun begin(p: InputPoint); fun add(points: List<InputPoint>); fun end(): MaskPatch?; fun cancel()
}
object SelectionEdits {   // every function returns a ready Command
    fun shape(doc: Document, s: SelShape, mode: SelectMode, aa: Boolean): Command
    fun wand(doc: Document, x: Int, y: Int, p: RegionParams, ref: RefSource, mode: SelectMode): Command
    fun copy(doc: Document, merged: Boolean): Floating  // Floating(pixels: TileGrid, bounds: IntRect)
    fun delete(doc: Document): Command?; fun fill(doc: Document, color: Rgba): Command?
    fun pasteAsLayer(doc: Document, f: Floating, at: IntRect): Command
}
```
**Acceptance:**
- a lasso circle of r = 100 gives an area of πr² ± 1 %;
- the AA edge has intermediate values, without AA only 0 and 255;
- inverting twice = the original mask (bitwise);
- a brush stroke outside the selection does not change the pixels (bitwise);
- the wand on 4096² ≤ 300 ms on mid-range;
- the ants are visible on any background, 60 fps while panning;
- the selection survives save/load and force-stop.

**Tests:**
- `raster_rect_exactArea`, `raster_circle_areaWithin1pct`, `raster_selfIntersecting_nonZero`, `raster_aaOff_binary`;
- `combine_add_max`, `combine_subtract`, `combine_intersect`, `combine_newReplaces`;
- `invert_twice_identity`, `selectAll_coversCanvasOnly`;
- `expand_square_by10`, `contract_toEmpty`, `feather_preservesMassApprox`;
- `fromAlpha_matchesLayerAlpha`;
- `maskStroke_add_then_erase`;
- `strokeClip_outsideUnchanged`, `strokeClip_softEdgeScalesAlpha`;
- `delete_onlySelected`;
- `copyPaste_roundTrip_pixelsEqual`, `copyMerged_excludesPaper`;
- `undo_maskPatch_restoresExact`;
- `io_selection_saveLoad`, `io_noSelection_fieldAbsent`;
- `contour_squareMask_4edges`.

## 3. Transform (`core/transform`)

### 3.1 Objects and session
**Object:**
- The selection is active: the fragment `layer × m` on every layer of the set, the mask is transformed together with it.
- No selection: the whole layer.
- A separate "selection mask" object: Selection → Transform mask.

**Layer set:**
- By default the active layer.
- A tap on a preview in the Layer panel adds or removes a layer (accent frame).
- A group means all its raster descendants.
- Locked layers are skipped (toast).

**Entering a session:**
1. For each layer `floating = pixels × m` (a copy of the tiles) and `rest = pixels × (1 − m)`. Without a selection `rest` is empty.
2. Cage = the common tight alpha bbox of all floating content, aligned to the canvas axes.

**Session:**
- Modes can be alternated. All edits make up a single mapping from the **original** floating content, quality is not lost until ✓ (**decision**: virtual transform inside the session; keeping the source between sessions per GAPS → M3).
- ✓: CPU resampling of `floating` by the inverse mapping → new tiles → normal OVER onto `rest` → `TilePatch` onto the layer (+ `MaskPatch`) → one `CompoundCommand("Transform")`.
- Everything that goes outside the canvas is clipped. **Decision.**
- ✗ restores the layers without writing to history.

### 3.2 Modes
| Mode | Gestures | Model |
|---|---|---|
| Basic (default) | drag inside or outside the cage = move; a corner = scale (proportional by the "Uniform" toggle, default on, from the center); a side handle = stretch along one axis from the opposite side; the rotate handle above the top edge (32 dp); 2 fingers inside the cage = move + pinch + rotate, outside the cage = navigation; Touch-Alt: move in 45° steps, scale in 100 % steps | `Affine(a, b, c, d, tx, ty)` |
| Anchor | like Basic, but the pivot is a draggable point (it may be outside the canvas), it snaps to the 9 cage points and the canvas center within a 24 dp radius. The pivot serves as the center for scale/rotate/flip/45° | Affine around the pivot |
| Distort | the 4 corners move freely; a side handle: perpendicular = stretch, along = skew (moves both corners); drag inside = move the whole quad. A non-convex quad is rejected, the last valid position is kept | 3×3 homography from 4 point pairs (DLT, 8×8 Gaussian with pivoting) |
| Warp | a 4×4 grid of control points (3×3 cells), a drag on any point inside pulls the nearest one in parameters (u, v) with Bernstein weights | a bicubic Bézier patch = a clamped 4×4 B-spline (GAPS). Tessellation 32×32 in the preview, 64×64 on commit |

**Buttons:**
- Flip H and Flip V about the pivot (the cage center if no pivot is set).
- ↻45° clockwise.
- "Stamp": the current result is copied to the layer "<name> stamps" (created once per session), the session continues.
- "Cage only" (unlock cage): the cage moves without the object. Basic and Distort only.

**Composition:** affine × homography collapse into a single homography. Warp is applied after it, to the grid nodes that have gone through the homography. The result is stored as a `Mapping`.

### 3.3 Numeric input
The "123" button opens a dialog:

| Field | Range | Note |
|---|---|---|
| X, Y | cage center, px | |
| W, H | 1…32768 px or 1…10000 % | aspect lock |
| Angle | −360…360°, step 0.1 | |
| Skew X/Y | −89…89° | |

Available in Basic and Anchor. In Distort the dialog shows the coordinates of the 4 corners.

### 3.4 Resampling
| Method | Formula | When |
|---|---|---|
| Nearest | `floor(u), floor(v)` | pixel art |
| Bilinear | 2×2 | fast |
| Bicubic (default) | Catmull-Rom (a = −0.5), 4×4, premult, the result is clamped: `α ∈ [0, 1]`, `rgb ≤ α` | quality |

- Downscaling by more than 2× (local Jacobian: `max singular < 0.5`): first a level of the floating box-mip pyramid (each level = a 2×2 average) with scale ≥ 0.5 is picked, then the method. This yields the "averaging" of ibis without a separate mode. **Decision.**
- Lanczos 2/3 → P2.
- The method is chosen in the session ⚙ and remembered in the settings.
- GPU preview: linear + mipmap, nearest for "Nearest".

### 3.5 Snapping (⚙, on by default)
- Move: the cage edges and center snap to the canvas edges and center, threshold 8 screen dp.
- Rotate: 0/90/180/270 ± 3° (as in T1). With Touch-Alt the step is 15°.
- Scale: to 100 % ± 2 %.
- Pivot: see Anchor.

### 3.6 Preview
- `CanvasView.setFloating(FloatingPreview(layerIds, textures, mapping, tess))`.
- `rest` is loaded as the layer pixels. The floating content is drawn as a triangle mesh with uv in the stack at the layer position (the same blend and opacity). Re-uploading the floating textures is not needed.
- The cage, handles and pivot are drawn by a Compose overlay above GL. The HUD shows W×H, the angle, the %.

### 3.7 UI
**Entry:** Editing Tools → Transform; from Selection; automatically after Paste, Duplicate, Isolate, Import image.

**Toolbar at the bottom:** ✗ · mode (Basic / Anchor / Distort / Warp) · Flip H · Flip V · ↻45° · Stamp · Cage only · 123 · ✓.

**⚙:** interpolation, Uniform, snapping.

### 3.8 API
```kotlin
sealed interface Mapping {
    /** dst → src; false if the point is outside the mapping area. */
    fun inverse(x: Float, y: Float, out: FloatArray): Boolean
    fun dstBounds(src: IntRect): IntRect
}
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) : Mapping {
    fun then(o: Affine): Affine; fun inverted(): Affine?
    companion object { val IDENTITY: Affine; fun trs(tx: Float, ty: Float, sx: Float, sy: Float, rotDeg: Float, px: Float, py: Float): Affine }
}
class Projective(val m: FloatArray /*3×3*/) : Mapping { companion object { fun fromQuads(src: FloatArray, dst: FloatArray): Projective? } }
class BezierWarp(val pre: Projective, val ctrl: FloatArray /*16 dst points*/, val src: IntRect, val tess: Int = 64) : Mapping
enum class Resample { NEAREST, BILINEAR, BICUBIC }
object Resampler { fun sample(src: TileGrid, mips: MipChain?, u: Float, v: Float, mode: Resample, out: FloatArray) }
class TransformSession(doc: Document, layerIds: List<String>, target: Target /*PIXELS, SELECTION_MASK*/) {
    val cage: IntRect; val floating: Map<String, TileGrid>
    fun stamp(mapping: Mapping, mode: Resample): Command
    fun commit(mapping: Mapping, mode: Resample): Command  // CompoundCommand, already applied
    fun cancel()
}
object Snap { fun move(cage: RectF, canvasW: Int, canvasH: Int, thresholdPx: Float): PointF; fun angle(deg: Float, step: Float, tol: Float): Float }
```
- Commit: the tiles of `dstBounds ∩ canvas` are iterated, in parallel, `inverse` → `sample` per pixel.
- Warp: for each tessellation triangle a barycentric walk over the dst pixels → uv. This is the same geometry as on the GPU.

**Acceptance:**
- move by whole px with Nearest and Bicubic is bitwise equal to the shift;
- four 90° rotations = the source bitwise, flipping twice = the source;
- scale 50 % of a 1 px checkerboard = gray 0.5 ± 1/255 (mip);
- Distorting a square into the same corners = identity ± 1/255;
- the preview and the commit differ by ≤ 2/255 and ≤ 0.5 px;
- 3 layers + selection → a single undo record, undo restores the layers and the mask bitwise;
- commit of 4096² bicubic ≤ 1.5 s.

**Tests:**
- `affine_compose_inverse`;
- `trs_aroundPivot`;
- `homography_identityQuad`, `homography_squareToTrapezoid_cornersExact`, `homography_degenerate_null`;
- `warp_identityGrid_isIdentity`, `warp_cornersInterpolate`;
- `resample_nearest_integerShift_exact`, `resample_bicubic_integerShift_exact`, `resample_bicubic_noOvershoot`;
- `downscale_usesMip_checkerboardGray`;
- `rotate90x4_identity`, `flipTwice_identity`;
- `session_withSelection_restUntouched`, `session_multiLayer_singleCommand`, `session_offCanvasClipped`, `session_cancel_noHistory`, `session_maskTransformed`;
- `stamp_createsLayerOnce`;
- `snap_move_edges`, `snap_angle_90`.

## 4. Fills (`core/fill`)

### 4.1 Region (shared by the bucket, the wand and the gradient limit)
**Pixel source `PixelSource.tile(key)`:**
- Canvas: visible layers, without Paper. Empty places stay transparent, so the opacity boundary works. **Decision.**
- Current layer.
- Source layer: assigned in Layer options "Make fill source". While the source ≠ the active layer, an icon is visible in the toolbar.

**Steps:**
1. **Seed:** the color `c0` under the point (un-premult).
2. **Fillable `F`** (BitPlane, tiles in parallel):
   - Color boundary: `max(|ΔR|, |ΔG|, |ΔB|, |ΔA|) ≤ tol` on un-premult (GAPS);
   - Opacity boundary: `|ΔA| ≤ tol`;
   - tol 100 % = the whole canvas.
3. **Gap closing** `g` (0–20 px, default 0 for the wand, 4 for the bucket):
   - `B = ¬F`; `B' = dilate(B, g)` (EDT ≤ g);
   - a flood from the seed in `¬B'` gives `R'`;
   - `R = dilate(R', g + 1) ∩ F`;
   - if the seed falls into `B'`, gap closing is not applied;
   - leakage through a gap is bounded by g px (accepted).
4. **Flood:** scanline with an explicit stack of spans, 4-connectivity (does not leak through diagonal lines). Non-contiguous: `R = F`.
5. **Expansion** `e` and AA → coverage via the signed EDT (§4.2): `cov = AA ? clamp(e + 0.5 − s, 0, 1) : (s ≤ e ? 1 : 0)`.
6. **Result:** `cov × selection`.

### 4.2 EDT
- Exact Euclidean, Felzenszwalb–Huttenlocher: 1D parabolas, first the columns, then the rows, in parallel over bands.
- Computed only within `bbox(R) ± (|e| + g + 2)`.
- Signed distance:
  - `s = d_in − 0.5` outside (`d_in` = distance to the nearest pixel of R);
  - `s = −(d_out − 0.5)` inside.

### 4.3 Bucket
| Parameter | Range | Default |
|---|---|---|
| Tolerance (shared with the wand) | 0–100 % | 15 % |
| Boundary | Color / Opacity | Color |
| Source | Canvas / Current layer / Source layer | Canvas |
| Gap closing | 0–20 px | 4 |
| Expansion | −20…+20 px, step 0.5 | +1.5 (like ibis, closes antialiasing slits in line art) |
| Antialiasing | on/off | on |
| Contiguous | on/off | on |
| Mode | Fill / Erase (to transparency) | Fill |
| Opacity | 0–100 % | 100 % |

- **Application:** Fill = normal OVER of the color with `cov · opacity`, alpha lock is respected. Erase = `d ·= 1 − cov · opacity`. Via `maskedCommit`, one "Fill" command.
- **Recolor:** after a fill, changing the color in the Color panel recolors the last fill (revert + repeat with the new color, the same undo record) until the next fill or ✓. **Decision.**
- **Live tolerance:** as with the wand. Preview = `setRegionPreview(cov, color)`.
- **Lasso fill** (a subtype in the selector): outline → polygon (§2.2, non-zero, AA on) → fill or erase with opacity, respecting the selection. The "all layers" and Surrounding fill variants → M4.

### 4.4 Gradient
**Types.** `p0` = start, `p1` = end, `d = p1 − p0`.

| Type | `t` |
|---|---|
| Linear | `dot(p − p0, d) / |d|²` |
| Radial | `|p − p0| / |d|` |
| Symmetric | `|dot(p − p0, d)| / |d|²` |
| Angular | `((atan2(p − p0) − atan2(d)) / 2π) mod 1`, then `fract(t · n)`, repetitions n = 1–100 (default 1) |

**Spread:**
- Pad (default) = clamp;
- Repeat = `fract`;
- Reflect = triangle;
- Reverse: `t → 1 − t`.

For Angular, Reflect is on by default, as in IP.

**Stops:**
- 2–32 of them, `pos 0..1`, straight sRGB color with alpha.
- Interpolation between adjacent stops is linear in **premultiplied** space (no dark fringe at transparent stops). **Decision.**
- Outside the outermost stops the color of the outermost stop is used.
- Presets: "Color → transparent" (the default on first run: current color α1 → the same α0), "Color → previous color". After that the last gradient is used.

**Dithering** (on by default):
- deterministic triangular noise `(h1 + h2 − 1) / 255` is added to the premult-RGBA, where `h` = a hash of `(x, y, channel)` in [0, 1);
- clamped to `[0, α]`;
- determinism is needed so that the preview, the commit and the tests match.

**Application:**
- normal OVER with `opacity (0–100 %, default 100) × selection × (region?)`;
- the "Limit to region" toggle (off by default) takes the region of §4.1 from `p0` with the bucket settings;
- alpha lock is respected;
- one "Gradient" command per confirmed gradient.

**Handles (until ✓ or until the next gradient starts):**
- stop circles on the axis: drag = move, tap = color (Color panel + stop opacity), dragging farther than 48 dp from the axis = delete (at least 2 remain);
- a tap on the axis = a new stop (color = the current value at that point);
- dragging the axis = move the whole gradient;
- the ends `p0` and `p1` move.

For Radial a preview circle is drawn, for Angular a 0° ray.

**Preview:**
- a GPU shader with the same formulas (uniforms: type, p0, p1, up to 32 stops, spread, n, dithering) over the layer under the selection mask;
- the CPU commit computes the same function, a GPU↔CPU test vector is mandatory (as with debt 5).

### 4.5 UI
**Entry:** Creative Tools → Tools → Fill opens the Fill workspace.

**Toolbar at the bottom:**
- type: Bucket / Gradient (the subtype in a dropdown menu: Linear, Radial, Symmetric, Angular) / Lasso fill;
- Source (a toggle icon: Canvas / Current / Source layer);
- Fill ↔ Erase;
- type controls;
- ✓.

Type controls:
- Bucket: tolerance (virtual slider);
- Gradient: spread, Reverse, repetitions n (Angular), preset, dithering, opacity.

**⚙:** tolerance, boundary, gap, expansion, AA, contiguous, "Limit to region".

The Bucket has no ✗: ✓ + undo (like IP). The Gradient's ✗ cancels only the current gradient. The main toolbar and the Color panel stay available. Several fills of different types in a row are allowed.

### 4.6 API
```kotlin
enum class Boundary { COLOR, ALPHA }
sealed interface RefSource { object Canvas : RefSource; object Current : RefSource; data class Layer(val id: String) : RefSource }
data class RegionParams(val tolerance: Float = 0.15f, val boundary: Boundary = Boundary.COLOR,
    val contiguous: Boolean = true, val gapPx: Int = 0, val expandPx: Float = 0f, val antialias: Boolean = true)
fun interface PixelSource { fun tile(key: TileKey): Tile? }
object Region {
    fun source(doc: Document, ref: RefSource): PixelSource
    fun fillable(src: PixelSource, w: Int, h: Int, seed: Rgba, p: RegionParams): BitPlane
    fun flood(f: BitPlane, x: Int, y: Int): BitPlane
    fun coverage(r: BitPlane, expandPx: Float, aa: Boolean): MaskGrid
    fun compute(doc: Document, ref: RefSource, x: Int, y: Int, p: RegionParams): MaskGrid?  // the whole pipeline of §4.1
}
object Edt { fun signed(r: BitPlane, area: IntRect): FloatArray }
object BucketFill { fun apply(doc: Document, layerId: String, cov: MaskGrid, color: Rgba, opacity: Float, erase: Boolean): TilePatch? }
enum class GradientType { LINEAR, RADIAL, SYMMETRIC, ANGULAR }
enum class Spread { PAD, REPEAT, REFLECT }
data class GradientStop(val pos: Float, val color: Rgba)
data class GradientSpec(val type: GradientType, val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val stops: List<GradientStop>, val spread: Spread = Spread.PAD, val reverse: Boolean = false,
    val repetitions: Int = 1, val dither: Boolean = true)
object Gradient {
    fun t(g: GradientSpec, x: Float, y: Float): Float
    fun eval(g: GradientSpec, x: Int, y: Int, out: FloatArray)  // premult + dither
    fun apply(doc: Document, layerId: String, g: GradientSpec, opacity: Float, region: MaskGrid?): TilePatch?
}
```
All `apply` functions themselves multiply by `doc.selection.coverageOrNull()` and return the patch already applied (like `StrokeEngine.end()`).

**Acceptance:**
- filling a closed circle of 3 px line art with a 3 px gap at gap 4 does not leak outside;
- at expansion 1.5 there is no white fringe at the AA line;
- tol 0 on a solid layer fills the whole contiguous region, tol 100 the whole layer;
- 4-connectivity: a diagonal line 1 px thick does not let the fill through;
- the bucket on 4096² ≤ 300 ms (gap 0), ≤ 800 ms (gap 4);
- the gradient on 4096² ≤ 500 ms, without banding on an 8-bit export with dithering on;
- the preview and the commit ≤ 2/255.

**Tests:**
- `fillable_chebyshevMetric`, `fillable_alphaBoundary`, `fillable_tol100_all`;
- `flood_4connected_diagonalBlocks`, `flood_nonContiguous_equalsF`, `flood_largeNoStackOverflow` (4096² empty);
- `gap_closesSmallGap`, `gap_seedInGap_fallback`;
- `edt_matchesBruteForce_random64`;
- `expand_positive_negative`, `aa_coverageRamp`;
- `bucket_respectsSelection`, `bucket_alphaLock`, `bucket_erase`, `bucket_canvasRef_ignoresPaper`, `bucket_sourceLayer`;
- `lassoFill_area`;
- `gradient_linear_endpoints`, `gradient_radial`, `gradient_symmetric`, `gradient_angular_reps`;
- `gradient_spread_repeat_reflect`, `gradient_reverse`;
- `gradient_premultInterp_noDarkFringe`;
- `gradient_dither_deterministic`, `gradient_dither_boundedAmplitude`;
- `gradient_stops_unsorted_sorted`;
- `gradient_respectsSelection`;
- `undo_fill_singleCommand`;
- `recolor_lastFill_sameHistoryEntry`.

## 5. Implementation order
1. **§0 (coordinator):** the common types, `Document.selection`, `MaskPatchCommand`, `flattenTile`, `maskedCommit`, `StrokeEngine(clip)`, selection IO. Without this the rest does not compile.
2. **In parallel:** `core/pick` (§1), `core/fill/Region + Edt` (§4.1–4.2, needed by the wand), `core/transform` (§3, it depends on the selection only through `MaskGrid`).
3. **`core/select`** (§2), it uses `Region`.
4. **Bucket and gradient** (§4.3–4.4).
5. **App:**
   - `ToolInput` routing;
   - overlay/ants/floating/gradient shaders;
   - the Selection, Transform and Fill workspaces;
   - the loupe;
   - the settings.
