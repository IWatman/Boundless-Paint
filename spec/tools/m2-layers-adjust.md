# M2 specs: layers, masks, blend modes, adjustments

Sources: features/06-layers, 07-blend-modes, 17-adjustments; spec/document-model.md, blend-and-color.md, filters.md, file-formats.md, UI-SHELL.md.
What already exists (M1): `RasterLayer`, `GroupNode` (passthrough by default), clip, alpha lock, 33 modes in `BlendMode`/`BlendMath`/the shader (12 in the UI), `Compositor.flatten`, commands in `LayerCommands.kt`, manifest schema 1.
The **decision** marker = chosen by us, the question is closed.

## Work order (stages)
| Stage | Content | Depends on |
|---|---|---|
| S1 core | `MaskGrid`, `LayerMask`, `AdjustmentLayer`, new `Node` fields, commands, Compositor (masks, adjustments, ADD fix), manifest schema 2, test vectors | the move of CPU tiles to half (can be parallel: the new code works through a scratch FloatArray) |
| S2 render | R8 mask textures, group masks, fix for the clip passthrough base, adjustment shader + LUT, parity test on the emulator | S1 |
| S3 UI panels | groups (drag in/out, collapse), multi-select, mask thumbnails, active target, mode list with previews, properties, tags, lock, reference | S1 |
| S4 mask painting | brush/eraser/fill into a mask, mask from selection, layer → selection | S1, S2, the Selection spec (M2) |
| S5 adjustments | Adjustments workspace (destructive), Curves and Levels editors, adjustment layers in the UI | S1, S2; gradient map uses the gradient editor from the Fill spec |

---

## L1. Groups
- Creation:
  - the "Group" button in the header of the expanded panel: a new group above the active layer, the active layer goes inside;
  - a pinch (bringing two fingers together) over ≥ 2 consecutive rows = group them (like in IP);
  - multi-select → "Group".
- Default of a new group: blend **passthrough**, opacity 100%, name "Group N", `expanded = true`.
- Nesting without a limit. Indentation in the panel is 12 dp per level, visually at most 5 levels (deeper the indent does not grow) — **decision**.
- Collapse/expand: a tap on the chevron to the left of the preview. A collapsed group with an active node inside: an accent dot on the folder icon.
- Group mode and opacity:
  - passthrough: the children are rendered inline into the parent's accumulator. An adjustment inside acts on everything below the group. Opacity < 1 = lerp(before, after) (already implemented);
  - any other mode: offscreen rendering, then blend as a single layer. An adjustment inside acts only on the group content.
- Clipping in groups:
  - a group can be a clip base. The base alpha = the alpha of the isolated render of the group, including for passthrough (the CPU does exactly that). **The GPU currently does not clip to a passthrough base (`baseIsolated=false`), fix it** — **decision**;
  - a group can be clipped (group content × base alpha);
  - a clipped node first in a group (no base) is not drawn, a warning icon is shown in the row.
- Moving into and out of a group (drag in the panel, long-press + drag already exists):
  - the row zone of a regular layer: the upper half = above, the lower half = below;
  - the row of a group: the top 25% = above the group, the middle 50% = **inside** (to the top of the child list, a collapsed group is highlighted and expands after 600 ms), the bottom 25% = below the group;
  - a horizontal finger shift left > 48 dp during a drag = move up one level (leave the group), right > 48 dp under a group = enter it as the last child on top — **decision** (an analogue of the ibis swipe);
  - forbidden: a group cannot be placed into itself or into a descendant.
- Group actions (options): Rename, Blend, Opacity, Hide, Duplicate (deep copy), **Copy merged** (a new raster layer above the group = the isolated composite, the group stays intact), **Flatten** (group → a single raster layer), Merge down, Ungroup, Clip, Select (selection by the total alpha), Mask ▸, Delete, Properties….
- Flatten:
  - the result = the isolated render of the children; the group's blend/opacity/clip/mask are transferred to the new layer; the name of the group;
  - for a passthrough group the mode of the result is normal. If there were adjustment/masking modes inside, the appearance may change: the toast "The appearance may have changed" — **decision**.
- Ungroup: the children take the place of the group in the same order. The group opacity/mode are lost. Group mask: if present, the user is offered "Apply to each" (the mask is copied to children without a mask, for children with a mask — intersection) or cancel — **decision**.

## L2. Multi-select in the panel
- Entry: the "Select" button (checklist icon) in the header of the expanded panel. Checkboxes appear on the rows, a tap on a row = toggle. Exit: the same button or Back — **decision** (IP has no multi-select, ibis has it only for vectors).
- The active layer (the brush target) is single. The first checked one becomes active.
- The bottom selection action panel: Group · Merge · Duplicate · Delete · Hide/Show · Clip on/off · Transform (opens Transform with these layers, the Transform spec).
- A drag of any checked row = move all checked ones, their relative order is preserved; insertion as a single block at the drop point.
- Merge selected:
  - render the selected nodes in stack order (each with its own mode, opacity, mask, clip to the base if the base is also selected) into a new raster layer over a transparent background;
  - place and name = the topmost selected one, mode normal, opacity 100% — **decision**;
  - an adjustment in the selection acts only on the selected nodes below it.
- Selecting a node inside a group and the group itself at the same time: the group absorbs the children (the children are removed from the selection).

## L3. Layer masks
### Model
- One raster mask per node: raster, group, adjustment. Value 0..1 (R8): 1 = visible, 0 = hidden.
- `MaskGrid`: 256² R8 tiles + `defaultValue` (0 or 255) for missing tiles. A "show all" mask stores no tiles at all — **decision**.
- Properties: `enabled` (default true), `linked` (default true: Transform moves the mask together with the layer), `density` 0–100% (default 100; the effective value `1 − density·(1 − m)`).

### Creation (Layer options → Mask ▸)
| Item | Result |
|---|---|
| Show all (default on tapping "Mask" if there is no selection) | default = 255, no tiles |
| Hide all | default = 0 |
| From selection (default if the selection is active) | a copy of the selection mask, default = 0 |
| From layer alpha | m = the pixel α (for a group — the α of the isolated render), default = 0 |
- After creation the mask becomes the active target.

### Painting in a mask
- Active target = mask: all raster tools write into the mask. The brush color → brightness `Lum(color)` (0.3/0.59/0.11).
- Paint: `m' = m·(1−a) + Lum·a`, where a = stroke coverage (stroke buffer α × opacity).
- Erase: writes 0 (hides) — **decision**: visually like an eraser, but non-destructive.
- Fill, gradient, filters and adjustments (destructive) are also applied to the mask as to a grayscale image.
- The color swatch in the main toolbar is shown in gray when a mask is active. The eyedropper takes the gray value.
- Alpha lock does not apply to a mask.

### Mask options (tap on the mask thumbnail of the active mask)
- Density (slider), Invert, Fill black, Clear (white), On/Off (a disabled one: the thumbnail is crossed out with a red cross), **Show mask** (the canvas shows the mask in gray instead of the composite, until the next tap), Apply, Delete, Linked (chain icon between the thumbnails), Select (mask → selection).
- Apply:
  - raster: `α' = α·m_eff` (premult: all channels × m_eff), the mask is deleted;
  - group: the mask is applied to each child (like the Ungroup logic), if the group contains adjustment/non-raster nodes the item is unavailable — **decision**;
  - adjustment: unavailable (the mask is precisely the way to localize).
- Delete without applying. Both commands are undoable.

### Rendering
- The mask multiplies the node's contribution: `f = opacity · m_eff · clipAlpha`.
- Raster: in blendPass `src *= f`.
- Isolated group: the mask is applied to the offscreen result.
- Passthrough group: `acc = lerp(accBefore, accAfter, opacity·m_eff)` (an extension of the existing lerp path to a per-pixel factor).
- Adjustment: `acc = lerp(acc, adj(acc), f)`.
- Clip: the base alpha for clipped nodes = `α_base · m_eff(base)` — **decision** (as in PS).
- GPU:
  - the mask tile texture `GL_R8`, uploaded like pixels (dirty tiles). A missing tile → the uniform `u_maskDefault`, no texture;
  - if the clip base has a mask and there are clipped nodes above it: the base is rendered into a temporary target (pixels × mask), which is used as `u_clip`.
- CPU: `Compositor.compositeInto(..., mask: MaskGrid?, maskDensity)`. An empty src tile is still skipped.

### The active target in the UI
- A layer row with a mask: `[layer preview][⛓][mask preview]`.
- The active target is outlined with a 2 dp accent frame (the theme's own color, not the IP red). An inactive one — gray 1 dp.
- A tap on the mask preview = the "mask" target, on the layer preview = the "layer" target.
- HUD on switching: "Mask: <layer name>". In the top bar next to the Layers toggle a small "M" badge while the target = mask.

## L4. Blend modes in the UI
- All 33 modes are available for raster, group, adjustment (for an adjustment the mode is applied to `adj(acc)` against `acc`). Passthrough only for groups.
- Selection: the mode chip in Layer options → a bottom sheet with the list:
  - group headers: Normal · Darken · Lighten · Contrast · Comparative · Color (HSL) · Masking;
  - the order inside a group = the `BlendMode` order (shaderIndex);
  - each item has a 40×40 dp preview: a 64×64 px fragment around the center of the layer bbox, blended with the lower composite in that mode. Computed on the CPU from the downscaled (1/4…1/16) layer and background, once when the sheet opens — **decision**;
  - a tap = the mode is applied immediately (live on the canvas), the sheet stays open. Closing = one `SetNodeProps` command (all taps inside the session are one undo step).
- Mode badge on the tile: a short name (≤ 4 characters, our own abbreviations, e.g. "Mul", "Scr", "Ovl") if it is not Normal/Passthrough.
- `BlendMode.M1` is removed, the list is built from `BlendMode.Group`.

### GPU ↔ CPU parity
- **ADD (debt 5)**: the canonical formula = the CPU one (`BlendMath`, matching blend-and-color.md): `C = αs(1−αb)·Cs + αb·min(1, Cb + Cs·αs)`, `αo = αs + αb(1−αs)`. Without the clamp to αo, as now in the shader. At αb = 1 the formulas coincide, at αb < 1 the shader clamps differently. Rewrite the shader — **decision**.
- Common test vectors `core/src/test/resources/vectors/blend.json`:
  - grid: αb, αs ∈ {0, 0.25, 0.5, 1}; Cb, Cs ∈ {0, 0.2, 0.5, 0.8, 1} per channel (different on R/G/B so that the HSL modes are caught); opacity ∈ {1, 0.5};
  - values in premult, ≈ 400 pairs × 33 modes;
  - the file is generated by the `:core:genVectors` task from `BlendMath` and committed. A JVM test compares `BlendMath` with the file (regression).
- androidTest `BlendParityTest` (emulator/device, GLES 3):
  - dst/src are loaded into N×1 RGBA16F textures;
  - the same `BLEND_FS` is run with each `u_mode`;
  - `glReadPixels(GL_RGBA, GL_FLOAT)`, comparison with the expected values;
  - tolerance 2e-3 (half). For color_dodge/color_burn/divide/vivid_light at the points Cs ∈ {0,1} the tolerance is 1e-2.
- The same mechanism for adjustments: `vectors/adjust.json` (6 parameter sets per type), `AdjustParityTest`.
- Edges fixed in the formulas (identically in CPU and GLSL): `cb`, `cs` are clamped to 0..1 after unpremultiply; division by α only when α > 0, otherwise the color is 0.

## L5. Adjustment layers (non-destructive)
- Adding: panel header → "+ spec. layer" → Adjustment ▸ type. The layer is placed above the active one. If the active layer is clipped or the "Clip to layer below" flag is set in the menu, the new layer is immediately clipped — **decision**.
- Strength = the layer opacity (like ibis). There is no separate Strength: the default parameters = no effect — **decision**.
- Default name = the type name, it can be renamed.
- Effect:
  - on everything below within the parent: an isolated group limits it, passthrough passes it through;
  - clipped: `f` is multiplied by the base alpha, i.e. only the base area changes (the PS model). In M2 this is exactly "base layer only" — **decision**;
  - it cannot be a clip base (clipped nodes above it are drawn without a base, a warning icon is shown).
- Adjustments do not create alpha from zero and do not change α. Transparent accumulator tiles are skipped — **decision**.
- Re-editing: a tap on the active adjustment layer → options → "Edit…" opens the Adjustments workspace in layer mode. Live preview through the same node. Confirm = `SetAdjustParams`, Cancel = rollback.

### M2 types (ranges: name range (default))
| kind | Parameters | Formula (brief) | Implementation |
|---|---|---|---|
| `brightness_contrast` | brightness −100–100 (0), contrast −100–100 (0) | b>0: `c+(1−c)·b/200`; b<0: `c·(1+b/200)`; contrast around 0.5: `k = c≥0 ? 1+3c/100 : 1+c/100` | LUT |
| `exposure` | exposure −5–5 EV (0), offset −0.5–0.5 (0), gamma 0.2–5 (1) | sRGB→linear, `·2^ev + offset`, linear→sRGB, `^(1/gamma)` | LUT |
| `hue_saturation` | hue −180–180° (0), saturation −100–100 (0), lightness −100–100 (0), colorize (off), colorHue 0–360 (0), colorSat 0–100 (25) | in HSL; colorize sets H, S, L is preserved | shader |
| `vibrance` | vibrance −100–100 (0), saturation −100–100 (0) | vibrance weight `1 − S_pixel` | shader |
| `temperature_tint` | temperature −100–100 (0), tint −100–100 (0) | Lab: `b* += 0.3·t`, `a* += 0.3·tint`, L is preserved | shader |
| `color_balance` | shadows/midtones/highlights × (cr, mg, yb) −100–100 (0), preserveLum (on) | weights by L: `sh = smoothstep(.5,0,L)`, `mid = 1−|2L−1|`, `hi = smoothstep(.5,1,L)`; channel shift `v/100·0.25·w` | shader |
| `levels` | channel rgb/r/g/b; per channel: inBlack 0–253 (0), inWhite 2–255 (255, ≥ inBlack+2), gamma 0.1–9.99 (1), outBlack 0–255 (0), outWhite 0–255 (255) | first r/g/b, then rgb | LUT |
| `curves` | channels rgb, r, g, b; points (x,y ∈ 0..1), 2–16 per channel | monotonic cubic (Fritsch–Carlson); first r/g/b, then rgb. The A channel only in destructive mode | LUT |
| `gradient_map` | stops 2–16 (pos 0..1, RGBA color) (black→white), reverse (off) | `t = Lum`, linear interpolation in sRGB, α' = α·α_stop | LUT by Lum (1D) |
| `invert` | variant rgb / luminance (rgb) | rgb: `1−c`; luminance: L in HSL → `1−L` | LUT / shader |
| `posterize` | levels 2–64 (8) | `round(c·(n−1))/(n−1)` | LUT |
| `threshold` | level 0–255 (128), smooth 0–100 (0) | by Lum, smooth = the width of the smoothstep in level units | shader |
| `black_white` | wR, wG, wB −200–200% (30, 59, 11), tint (off), tintColor | `g = Σ w·c`, clamping; tint = multiply tinting | shader |
- Grayscale = `black_white` with the defaults. The individual IP items (Brightness, Contrast, Gamma, Saturation, Hue, Temperature, Tint) in the menu are shortcuts to these kinds with a single visible slider.
- Color balance, Levels, Gradient map, Posterize, B&W are moved from M3 (filters.md) to M2 — **decision**.
- Levels Auto: by the histogram of the composite below the layer (destructively — by the target), clipping 0.1% at each edge per channel.
- LUT: `AdjustLut.build(params): FloatArray` in :core, 1024 values × 4 (R, G, B, shared). One function for the CPU and the GPU (the GPU loads it as a 1024×1 RGBA16F texture) — the source of parity — **decision**.
- GPU: the program `ADJUST_FS` (`u_kind`, a uniform array of parameters, `u_lut`, `u_mask`, `u_clip`, `u_opacity`, `u_mode`): `a = adj(unpremult(d))`, `b = blend(u_mode, d, premult(a, d.a)·f)`. The shader formulas are mirrored in `AdjustMath` (CPU).
- CPU: `object AdjustMath { fun applyTile(p: AdjustParams, lut: FloatArray?, px: FloatArray) }` (premult in/out, scratch FloatArray — independent of half storage). In parallel over tiles: `TileJobs.parallel(keys)` on `Dispatchers.Default`.

## L6. Destructive adjustments
- Editing Tools → Adjustments (workspace): the bottom toolbar — tabs Light · Color · Tone, containing the L5 types + the individual shortcuts. Above the toolbar — the slider panel of the active type. Curves and Levels — separate items of Editing Tools (UI-SHELL), they open the same workspace straight on their editor.
- Target: the active layer or the active mask. With an active selection — only inside it (`m_sel` as f). On a group/adjustment the item is unavailable (the toast "Select a raster layer") — **decision**.
- Session: several types can be changed in a row, they accumulate as a chain in the order of application. The last type and its parameters are remembered.
- Live preview: the renderer substitutes the layer texture with `adj(layer)` in the blend pass (`setAdjustPreview(layerId, chain, selection?)`), without the CPU. Throttled to 1 frame.
- Controls: "Hide effect" (holding the eye = before/after), Reset of a parameter (double tap on the slider), Cancel, Confirm. The **"As layer"** button: instead of baking, it creates adjustment layer(s) above the target with the same parameters (clipped if the target is not the only visible one) — **decision**.
- Quick masks (focus, tilt-shift, tonal) — M3 together with the filters. In M2 only the global selection.
- Confirm: CPU application over tiles in parallel → `TilePatch` → `TilePatchCommand`. If > 300 ms — a non-blocking indicator on the canvas, input is blocked. The CPU result must match the preview (test vectors L4).

### Curves editor
- A square plot ≥ 280 dp, a 4×4 grid, a reference diagonal, the histogram of the target under the curve (the selected channel; for RGB — luminance).
- Channels as chips: RGB · R · G · B · A (A only destructively). Each channel is drawn in its own color. Inactive channels are visible as thin lines.
- Nodes:
  - a tap on an empty spot = add a node on the curve at that x;
  - drag = move it (x is limited by its neighbors with a 0.01 gap; the end nodes move along x and y);
  - dragging a node past the edge of the plot or onto a neighbor = delete;
  - all inner nodes deleted = a straight line.
- Selected node: the label "input → output" (0–255), ±1 arrows on tap.
- Presets (dropdown list): Line, Lighter, Darker, Contrast+, Contrast−, Negative, Luminance negative (inversion of the RGB curve only). "Save preset…" — custom presets with a name, stored in the settings (`.pcfg`).
- Reset channel / reset all.

### Levels editor
- A histogram, 3 input handles (black, gamma, white), 2 output handles, channel chips RGB/R/G/B, an Auto button, numeric fields.

## L7. Other layer features
| Feature | Behavior | Where in the UI |
|---|---|---|
| Lock (`locked`) | forbids pixels, mask, Transform, Delete, Merge (including as the lower one). Visibility, opacity, mode, order are allowed. Locking a group acts on all descendants. A tool on a locked layer: the toast "Layer is locked" | toggle in options, lock badge |
| Layer → selection (Select) | raster: by α; group: by the α of the isolated render; mask: by m. Mode = the current Selection mode (replace/add/subtract/intersect) | options "Select" |
| Flatten document | all visible nodes → one raster layer "Layer 1", hidden ones are deleted after confirmation; Paper is not touched | Layer panel ⋮ → "Flatten image" |
| Merge down | see below | options |
| Layer properties | dialog: name, opacity, mode, tag, lock, alpha lock, clip, reference, hide in recording | options → "Properties…" |
| Color tags | none, red, orange, yellow, green, blue, violet, gray. A 4 dp stripe at the left edge of the row, in the collapsed panel — a dot | properties dialog, long-press on the stripe — quick pick |
| Reference | one node per document (raster or group). For Fill and Wand with the "Source: reference" option. Enabling it on another node clears it from the old one (one command) | toggle in options, badge |
| Thumbnails | updated 250 ms after the last command, only for changed nodes (by `Tile.version`). A group is updated if a child changed. 64 px on the long side, background thread. The setting "Thumbnail: whole canvas / content only (bbox)" (default whole canvas) | — |
| Delete | undoable (a difference from IP) — **decision** | options, multi-select |
- Merge down:
  - raster onto raster: as in M1, the masks of both are applied first;
  - onto a group below: the group is flattened first;
  - adjustment onto a raster/group below: the adjustment is baked into the lower one (with the mask, opacity, mode), the adjustment is deleted;
  - lower = adjustment: the item is unavailable — **decision**.
- Layer limit (`StudioViewModel.layerLimitFor`): adjustment layers and groups are not counted; a mask = 1/8 of a layer (1 B/px versus 8). The counter in the panel: `rasters + masks/8` of the limit. Recompute the formula after the move to half (debt 1).

---

## Data model (:core/doc, sketch)
```kotlin
// core/raster — a new common type (Selection needs it too)
class MaskGrid(val width: Int, val height: Int, var defaultValue: Byte /*0 or -1(255)*/) {
    fun get(key: TileKey): MaskTile?; fun getOrCreate(key: TileKey): MaskTile /*filled with defaultValue*/
    fun put(key: TileKey, t: MaskTile?); fun keys(): Set<TileKey>; fun copy(): MaskGrid
    fun valueAt(x: Int, y: Int): Float
}
class MaskPatch(val nodeId: String, val before: Map<TileKey, MaskTile?>, val after: Map<TileKey, MaskTile?>)

class LayerMask(val tiles: MaskGrid) {
    var enabled = true; var linked = true; var density = 1f
    fun deepCopy(): LayerMask
}

enum class ColorTag { NONE, RED, ORANGE, YELLOW, GREEN, BLUE, VIOLET, GRAY }
enum class EditTarget { LAYER, MASK }

sealed class Node(...) {
    // +
    var mask: LayerMask? = null
    var colorTag: ColorTag = ColorTag.NONE
    var reference: Boolean = false
}

class AdjustmentLayer(id: String, name: String, var params: AdjustParams) : Node(id, name)

// core/adjust
@Serializable sealed class AdjustParams { abstract val kind: String }
@Serializable @SerialName("curves") data class CurvesParams(
    val rgb: List<Pt> = LINEAR, val r: List<Pt> = LINEAR, val g: List<Pt> = LINEAR,
    val b: List<Pt> = LINEAR, val a: List<Pt> = LINEAR) : AdjustParams()
// ... one data class per kind from L5
@Serializable data class UnknownAdjust(val raw: JsonObject) : AdjustParams()  // unknown kind: no effect, kept as is

class Document { /* + */ var activeTarget: EditTarget = EditTarget.LAYER
                 val referenceNode: Node? }
```
- Multi-select is ViewModel/UI state (`selectedIds`), not document state. Commands accept lists of ids — **decision**.
- The document selection (`Document.selection: MaskGrid?`) is introduced by the Selection spec, here it is only used.

## History commands (core/history)
| Command | Data | Notes |
|---|---|---|
| `GroupNodes(ids, groupName)` | the id of the new group, the original (parent, index) of each | different parents: the group takes the place of the topmost |
| `Ungroup(groupId, maskPolicy)` | the whole group, the child indices, the modified child masks | |
| `MoveNodes(ids, newParentId, index)` | the original (parent, index) | replaces `MoveNode` (the old one = a special case) |
| `DeleteNodes(ids)` | the whole nodes + positions, the old activeId | |
| `DuplicateNodes(ids)` | | |
| `MergeNodes(ids, blender)` / `MergeDown` (extend) | the deleted nodes, the before tiles of the lower one | `blender` accepts a mask and an adjustment handler, without a direct dependency on `core/blend` |
| `FlattenGroup(id)`, `FlattenDocument` | the original subtree | |
| `CopyMerged(groupId)` | the id of the new layer | |
| `AddMask(nodeId, init)`, `DeleteMask(nodeId)`, `ApplyMask(nodeId)` | the whole mask / the before tiles of the layer | |
| `MaskPatchCommand(patch)` | `MaskPatch` | stroke, fill, invert, destructive adjustment on a mask |
| `SetMaskProps(nodeId, enabled, linked, density)` | before/after | |
| `AddAdjustmentLayer(params, parentId, index, clipped)` | | |
| `SetAdjustParams(id, before, after)` | | one per "Edit…" session |
| `SetNodeProps` (extend) | + colorTag, reference, locked, name, blend, opacity | reference: clears the flag from the old node in the same command |
| `SetActiveTarget` | not a command: does not go into history | |
| destructive adjustment | `TilePatchCommand` | exists |
- `StrokeEngine` gets the target `StrokeTarget.Layer(id) | StrokeTarget.Mask(nodeId)`. For a mask `end()` reduces the buffer into a `MaskGrid` and returns a `MaskPatch`.

## manifest.json, schema 2
```json
{ "schema": 2, "activeId": "…", "activeTarget": "layer",
  "nodes": [
    { "id": "…", "kind": "raster", "colorTag": "none", "reference": false,
      "mask": { "enabled": true, "linked": true, "density": 1.0, "default": 255 } },
    { "id": "…", "kind": "adjustment", "blendMode": "normal", "opacity": 1.0, "clipped": false,
      "adjust": { "kind": "curves", "rgb": [[0,0],[0.5,0.6],[1,1]], "r": [[0,0],[1,1]] } },
    { "id": "…", "kind": "group", "blendMode": "passthrough", "expanded": false, "children": [] } ] }
```
- Mask tiles: `masks/<nodeId>/tiles.idx` + `tiles.bin`, the same index format, R8 65536 B per tile, LZ4 frame. Tiles that are entirely equal to `default` are not written.
- `NodeKind.ADJUSTMENT = "adjustment"`. An unknown `kind` of a node on read → skipped with a log entry. An unknown `adjust.kind` → `UnknownAdjust` (rendered as empty, written back as it was).
- Migration 1 → 2: fields with defaults, no transformations. Writing is always schema 2.
- PSD mapping of adjustment layers — M3, outside this spec.

## Render changes (app/render)
- `SnapNode`: + `kind` (raster/group/adjust), `mask: SnapMask?(enabled, density, default)`, `adjust: SnapAdjust?(kindIndex, uniforms FloatArray, lut FloatArray?)`. The LUT and uniforms are prepared in `DocumentSync` outside the GL thread.
- Mask tiles: a separate `GL_R8` pool. Upload by the dirty keys of `MaskTile.version`, like pixels.
- `BLEND_FS`: + `u_mask`, `u_useMask`, `u_maskDefault`, `u_maskDensity`; `f *= mask_eff`. Preview of a stroke into a mask: `u_preview = 3` (the buffer is blended into the mask value in the shader, not into the color).
- The passthrough lerp path: a per-pixel factor `opacity · mask_eff`.
- A new program `ADJUST_FS` (L5). A destructive preview = an adjust pass over the layer texture into a temporary target before blendPass.
- Fix: clipping to a passthrough group renders the isolated alpha of the base, like the CPU.
- Fix: ADD by the canonical formula.
- "Show mask": the display pass outputs the mask of the active node in gray.
- Budget: an adjustment = 1 full-screen pass per tile. The goal — 60 fps while panning on 4096² with 10 layers + 3 adjustments + 3 masks.

## Acceptance
- Groups:
  - nest 3 levels deep by drag, pull back out. Undo/redo of every step;
  - a passthrough group with a hue adjustment inside changes the layers below the group, a normal group does not;
  - clipping to a passthrough group looks the same on the GPU and in the exported PNG (CPU).
- Multi-select: select 3 non-adjacent layers → Group → Merge; one undo step per action.
- Masks:
  - create with each of the 4 methods. A brush with 50% gray gives 50% translucency ± 1/255;
  - Erase in a mask hides, Paint with white restores. The layer pixels do not change;
  - off/on, Apply, Delete — undoable;
  - the group mask hides the whole group;
  - PNG export = the screen (CPU = GPU within 1/255).
- Modes: all 33 in the list with previews; `BlendParityTest` green on the emulator for all modes.
- Adjustment:
  - the 13 types are created, edited, saved and opened after a restart with the same parameters;
  - opacity 0 = no effect; a clipped one affects only within the base alpha;
  - dragging a slider on 4096² with 10 layers ≥ 30 fps.
- Destructively:
  - the preview = the Confirm result (`AdjustParityTest`);
  - with a selection only the inside changes;
  - a full 4096² layer: Confirm ≤ 1.5 s on an average device;
  - "As layer" gives the same appearance as Confirm.
- Curves: add/move/delete nodes, all presets, a custom preset survives a restart.
- Lock blocks a stroke and Transform. Reference is single in the document. Thumbnails update ≤ 0.5 s after a stroke.

## JVM tests (core/src/test)
| Package | Tests |
|---|---|
| `raster` | MaskGrid: default tiles, copy, valueAt; MaskPatch apply/revert |
| `doc` | deepCopy with mask/tag/reference; AdjustmentLayer deepCopy; reference is single |
| `blend` | `BlendVectorsTest` (all 33 by `vectors/blend.json`); ADD at αb < 1; compositeInto with a mask (density, default 0/255, missing tile) |
| `blend/Compositor` | mask of raster / isolated group / passthrough group; clip to a base with a mask; clip to a passthrough group; adjustment: in an isolated group it does not go outside, in passthrough it does; clipped adjustment within the base alpha; adjustment does not touch transparent; adjustment as base = clipped nodes without a base are not drawn |
| `adjust` | `AdjustVectorsTest`; identity for every kind at the defaults (Δ ≤ 1e-6); curves: monotonicity, 2 points = a straight line, deleting nodes; levels: in/out bounds, gamma; posterize n levels; gradient_map reverse; Auto levels on a synthetic histogram; round-trip serialization of every kind; UnknownAdjust round-trip |
| `history` | GroupNodes/Ungroup (same and different parents), MoveNodes multi preserving order, forbidding nesting a group into a descendant, DeleteNodes multi, MergeNodes, FlattenGroup (passthrough → normal), CopyMerged, Merge down of an adjustment onto a raster (baking), Merge onto an adjustment forbidden, AddMask×4 init, ApplyMask raster/group, MaskPatchCommand, SetMaskProps, SetAdjustParams, SetNodeProps reference clears the old one, lock blocks pixel commands |
| `stroke` | stroke into a mask: Paint with gray, Erase = 0, alpha lock ignored; cancel does not change the mask |
| `io` | schema 1 is read as 2; round-trip: masks (default 0/255, sparse tiles), adjustments of all kinds, colorTag, reference, activeTarget; an unknown node kind is skipped |
