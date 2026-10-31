package app.painter.core.select

import app.painter.core.blend.Compositor
import app.painter.core.color.Rgba
import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.RasterLayer
import app.painter.core.doc.newNodeId
import app.painter.core.fill.BucketFill
import app.painter.core.fill.MaskMath
import app.painter.core.fill.RefSource
import app.painter.core.fill.Region
import app.painter.core.fill.RegionParams
import app.painter.core.history.AddLayer
import app.painter.core.history.Command
import app.painter.core.history.CompoundCommand
import app.painter.core.history.SelectionPatchCommand
import app.painter.core.history.SetSelectionActive
import app.painter.core.history.TilePatchCommand
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch
import app.painter.core.raster.alphaBounds
import app.painter.core.raster.maskedPatch
import app.painter.core.raster.maskedTile
import app.painter.core.raster.parallelTiles

/** Result of [SelectionEdits.cut]: the lifted pixels and the (not yet applied) command that removes them. */
class Cut(val floating: Floating, val command: Command)

/**
 * Selection tool operations (spec §2.2–2.4). Every function only reads the document and returns a command
 * that is **not yet applied**: run it with `History.execute`. Null means the operation would change nothing.
 * Content operations use the selection while it is in effect, otherwise the whole layer (or canvas).
 */
object SelectionEdits {

    /** NEW when the mask is empty and [mode] is ADD (the default for an existing mask). */
    fun effectiveMode(doc: Document, mode: SelectMode): SelectMode =
        if (mode == SelectMode.ADD && doc.selection.isEmpty) SelectMode.NEW else mode

    /** Rasterizes [s] and combines it with the selection. */
    fun shape(doc: Document, s: SelShape, mode: SelectMode, aa: Boolean): Command? =
        combineWith(doc, PolygonRaster.rasterize(s.toPolygon(), doc.width, doc.height, aa), mode, "Selection")

    /** Magic wand at ([x], [y]) with the shared region pipeline. */
    fun wand(doc: Document, x: Int, y: Int, p: RegionParams, ref: RefSource, mode: SelectMode): Command? {
        val r = Region.compute(doc, ref, x, y, p) ?: MaskGrid(doc.width, doc.height)
        return combineWith(doc, r, mode, "Magic wand")
    }

    /** Combines an arbitrary coverage [src] with the selection. */
    fun combineWith(doc: Document, src: MaskGrid, mode: SelectMode, name: String): Command? {
        val m = doc.selection.mask.copy()
        MaskOps.combine(m, src, effectiveMode(doc, mode))
        return withActivation(doc, SelectionPatchCommand.replacing(doc, m, name), name)
    }

    /** "Select opaque" of a layer or group (composite without paper). */
    fun fromLayerAlpha(doc: Document, nodeId: String, mode: SelectMode): Command? {
        val node = doc.findNode(nodeId) ?: return null
        val grid = when (node) {
            is AdjustmentLayer -> return null
            is RasterLayer -> node.pixels
            is GroupNode -> {
                val src = Region.source(doc, RefSource.Layer(nodeId))
                TileGrid(doc.width, doc.height).also { g ->
                    for (k in canvasKeys(doc)) src.tile(k)?.let { g.put(k, it) }
                }
            }
        }
        return combineWith(doc, MaskOps.fromAlpha(grid), mode, "Select opaque")
    }

    fun selectAll(doc: Document): Command? =
        withActivation(doc, SelectionPatchCommand.replacing(doc, MaskGrid(doc.width, doc.height).also(MaskOps::selectAll), "Select all"), "Select all")

    fun invert(doc: Document): Command? =
        withActivation(doc, SelectionPatchCommand.replacing(doc, doc.selection.mask.copy().also(MaskOps::invert), "Invert selection"), "Invert selection")

    /** Empties the mask ("Deselect"). */
    fun deselect(doc: Document): Command? =
        SelectionPatchCommand.replacing(doc, MaskGrid(doc.width, doc.height), "Deselect")

    /** Grows or shrinks the selection by [px] (−100..100). */
    fun expand(doc: Document, px: Float, aa: Boolean = true): Command? =
        SelectionPatchCommand.replacing(doc, MaskOps.expand(doc.selection.mask, px, aa), if (px >= 0) "Expand selection" else "Contract selection")

    /** Feathers the selection with [radius] 0..250 px. */
    fun feather(doc: Document, radius: Float): Command? =
        SelectionPatchCommand.replacing(doc, MaskOps.feather(doc.selection.mask, radius), "Feather selection")

    /** One selection-brush stroke: the alpha of [buffer] added to (or erased from) the mask. */
    fun maskStroke(doc: Document, buffer: TileGrid, erase: Boolean, opacity: Float = 1f): Command? {
        val m = doc.selection.mask.copy()
        MaskOps.applyStroke(m, buffer, erase, opacity)
        return withActivation(doc, SelectionPatchCommand.replacing(doc, m, "Selection brush"), "Selection brush")
    }

    /** Toggles [Selection.active]; null when it already has that value. */
    fun setActive(doc: Document, on: Boolean): Command? =
        if (doc.selection.active == on) null else SetSelectionActive(on)

    /**
     * Active layer × m (or the visible composite without paper with [merged]) as a [Floating];
     * null when nothing would be copied.
     */
    fun copy(doc: Document, merged: Boolean): Floating? {
        val mask = doc.selection.coverageOrNull()
        val src: (TileKey) -> Tile?
        val keys: List<TileKey>
        if (merged) {
            src = { Compositor.flattenTile(doc, it, includePaper = false) }
            keys = mask?.keys()?.filter { mask.isInBounds(it.tx, it.ty) }?.sortedBy { it.packed } ?: canvasKeys(doc)
        } else {
            val layer = doc.activeRaster ?: return null
            src = { layer.pixels.get(it) }
            keys = layer.pixels.keys().filter { layer.pixels.isInBounds(it.tx, it.ty) && (mask == null || mask.get(it) != null) }
                .sortedBy { it.packed }
        }
        val tiles = parallelTiles(keys) { k ->
            val t = src(k) ?: return@parallelTiles null
            if (mask != null) maskedTile(t, mask, k) else t.copy(false).takeUnless { it.isEmpty() }
        }
        val g = TileGrid(doc.width, doc.height)
        for ((i, k) in keys.withIndex()) tiles[i]?.let { g.put(k, it) }
        val b = g.alphaBounds() ?: return null
        return Floating(g, b)
    }

    /** Copy + delete as one command. */
    fun cut(doc: Document): Cut? {
        val f = copy(doc, merged = false) ?: return null
        val del = deletePatch(doc) ?: return null
        return Cut(f, TilePatchCommand(del, "Cut"))
    }

    /** Clears the selected pixels of the active layer (`maskedCommit` with transparent). */
    fun delete(doc: Document): Command? = deletePatch(doc)?.let { TilePatchCommand(it, "Delete") }

    /** Fills the selected area of the active layer with [color], normal OVER, alpha lock respected. */
    fun fill(doc: Document, color: Rgba): Command? {
        val layer = doc.activeRaster ?: return null
        val p = BucketFill.patch(doc, layer.id, MaskMath.full(doc.width, doc.height), color, 1f, erase = false)
        return p?.let { TilePatchCommand(it, "Fill") }
    }

    /** Fills everything outside the current selection ("surrounding fill"); null without an active selection. */
    fun fillOutside(doc: Document, color: Rgba): Command? {
        val layer = doc.activeRaster ?: return null
        val inside = doc.selection.coverageOrNull() ?: return null
        val outside = inside.copy().also(MaskOps::invert)
        val p = BucketFill.patch(
            doc, layer.id, outside, color, 1f, erase = false, clipSelection = false,
        ) ?: return null
        return TilePatchCommand(p, "Surrounding fill")
    }

    /** Erases everything outside the current selection, keeping the selected island. */
    fun eraseOutside(doc: Document): Command? {
        val layer = doc.activeRaster ?: return null
        val inside = doc.selection.coverageOrNull() ?: return null
        val outside = inside.copy().also(MaskOps::invert)
        val p = BucketFill.patch(
            doc, layer.id, outside, Rgba.TRANSPARENT, 1f, erase = true, clipSelection = false,
        ) ?: return null
        return TilePatchCommand(p, "Surrounding erase")
    }

    /**
     * New layer named [name] above the active one holding [f] moved so that its bounds start at [at]'s
     * origin (integer offset, clipped to the canvas).
     */
    fun pasteAsLayer(doc: Document, f: Floating, at: IntRect, name: String = "Pasted"): Command {
        val dx = at.x0 - f.bounds.x0
        val dy = at.y0 - f.bounds.y0
        val layer = RasterLayer(newNodeId(), name, shifted(f.pixels, dx, dy, doc.width, doc.height))
        return addAboveActive(doc, layer, "Paste")
    }

    /** Active layer × m on a new layer "<name> copy" above it. */
    fun duplicateToLayer(doc: Document): Command? {
        val src = doc.activeRaster ?: return null
        val f = copy(doc, merged = false) ?: return null
        val layer = RasterLayer(newNodeId(), src.name + " copy", f.pixels)
        return addAboveActive(doc, layer, "Duplicate selection")
    }

    /** Cut to a new layer above the active one. */
    fun isolate(doc: Document): Command? {
        val src = doc.activeRaster ?: return null
        val c = cut(doc) ?: return null
        val layer = RasterLayer(newNodeId(), src.name, c.floating.pixels)
        return CompoundCommand("Isolate", listOf(c.command, addAboveActive(doc, layer, "Isolate")))
    }

    private fun deletePatch(doc: Document): TilePatch? {
        val layer = doc.activeRaster ?: return null
        if (layer.locked) return null
        val mask = doc.selection.coverageOrNull()
        val result = HashMap<TileKey, Tile?>()
        for (k in layer.pixels.keys()) if (mask == null || mask.get(k) != null) result[k] = null
        return maskedPatch(layer, result, mask)
    }

    private fun addAboveActive(doc: Document, layer: RasterLayer, name: String): Command {
        val active = doc.activeNode
        val parent = active?.parent ?: doc.root
        val index = if (active != null) parent.indexOf(active) + 1 else parent.children.size
        return AddLayer(layer, parent.id, index, name)
    }

    /** Re-activates an inactive selection when a selection tool edits it. */
    private fun withActivation(doc: Document, cmd: Command?, name: String): Command? {
        if (cmd == null || doc.selection.active) return cmd
        return CompoundCommand(name, listOf(cmd, SetSelectionActive(true)))
    }

    private fun canvasKeys(doc: Document) = IntRect(0, 0, doc.width, doc.height).tileKeys(doc.width, doc.height)

    /** [src] translated by whole pixels into a new grid of [w]×[h]; pixels leaving the canvas are dropped. */
    internal fun shifted(src: TileGrid, dx: Int, dy: Int, w: Int, h: Int): TileGrid {
        val out = TileGrid(w, h)
        if (dx and Tile.MASK == 0 && dy and Tile.MASK == 0) {
            val sx = dx shr Tile.SHIFT; val sy = dy shr Tile.SHIFT
            for ((k, t) in src.entries()) {
                val nx = k.tx + sx; val ny = k.ty + sy
                if (out.isInBounds(nx, ny)) out.put(TileKey(nx, ny), t.copy(false))
            }
            return out
        }
        val p = FloatArray(4)
        for ((k, t) in src.entries()) {
            val bx = k.tx shl Tile.SHIFT; val by = k.ty shl Tile.SHIFT
            for (y in 0 until Tile.SIZE) for (x in 0 until Tile.SIZE) {
                val i = t.index(x, y)
                if (t[i + 3] <= 0f) continue
                val nx = bx + x + dx; val ny = by + y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                t.read(i, p)
                val d = out.getOrCreate(nx shr Tile.SHIFT, ny shr Tile.SHIFT)
                d.write(d.index(nx and Tile.MASK, ny and Tile.MASK), p)
            }
        }
        return out
    }
}
