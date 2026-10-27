package app.painter.core.fill

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.history.Command
import app.painter.core.history.touch
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch
import app.painter.core.raster.applyTiles
import app.painter.core.raster.maskedPatch
import app.painter.core.raster.parallelTiles
import app.painter.core.select.PolygonRaster

/** Bucket fill / erase through a coverage mask (spec §4.3). */
object BucketFill {
    /**
     * Fills [cov] × selection on [layerId] with [color] (normal OVER at [opacity]) or, with [erase], scales
     * the pixels by `1 − cov · opacity`. Respects alpha lock; locked layers are left alone.
     * Returns the patch already applied to the layer, or null when nothing changed.
     */
    fun apply(doc: Document, layerId: String, cov: MaskGrid, color: Rgba, opacity: Float, erase: Boolean): TilePatch? {
        val patch = patch(doc, layerId, cov, color, opacity, erase) ?: return null
        applyTiles((doc.findNode(layerId) as RasterLayer).pixels, patch.after)
        doc.touch()
        return patch
    }

    /** Like [apply] but returns an applied [FillCommand] that can later be recolored in place. */
    fun command(
        doc: Document, layerId: String, cov: MaskGrid, color: Rgba, opacity: Float, erase: Boolean,
        name: String = "Fill",
    ): FillCommand? {
        val p = apply(doc, layerId, cov, color, opacity, erase) ?: return null
        return FillCommand(layerId, p, name) { c -> patch(doc, layerId, cov, c, opacity, erase) }
    }

    /** The patch [apply] would produce, without touching the document. */
    fun patch(doc: Document, layerId: String, cov: MaskGrid, color: Rgba, opacity: Float, erase: Boolean): TilePatch? {
        val layer = doc.findNode(layerId) as? RasterLayer ?: return null
        if (layer.locked || opacity <= 0f) return null
        val mask = MaskMath.multiply(cov, doc.selection.coverageOrNull())
        val keys = mask.keys().filter { mask.isInBounds(it.tx, it.ty) && !mask.get(it)!!.isEmpty() }
        val o = opacity.coerceIn(0f, 1f)
        val result = HashMap<TileKey, Tile?>()
        val tiles = parallelTiles(keys) { k -> if (erase) eraseTile(layer.pixels.get(k), o) else overTile(layer.pixels.get(k), color, o) }
        for ((i, k) in keys.withIndex()) result[k] = tiles[i]
        return maskedPatch(layer, result, mask, preserveAlpha = layer.alphaLock)
    }

    private fun eraseTile(before: Tile?, o: Float): Tile? {
        if (before == null) return null
        return before.copy(false).also { it.scale(1f - o) }
    }

    private fun overTile(before: Tile?, c: Rgba, o: Float): Tile {
        val k = (c.a * o).coerceIn(0f, 1f)
        val out = Tile()
        val p = FloatArray(4)
        var i = 0
        while (i < Tile.CHANNELS) {
            if (before != null) before.read(i, p) else p.fill(0f)
            val inv = 1f - k
            out.write(i, c.r * k + p[0] * inv, c.g * k + p[1] * inv, c.b * k + p[2] * inv, k + p[3] * inv)
            i += 4
        }
        return out
    }
}

/** Lasso fill: a closed freehand polygon (non-zero, antialiased) filled or erased like the bucket (spec §4.3). */
object LassoFill {
    /** [poly] holds xy pairs in document pixels. Returns the applied patch, or null when nothing changed. */
    fun apply(doc: Document, layerId: String, poly: FloatArray, color: Rgba, opacity: Float, erase: Boolean): TilePatch? {
        val cov = PolygonRaster.rasterize(poly, doc.width, doc.height, aa = true)
        return BucketFill.apply(doc, layerId, cov, color, opacity, erase)
    }
}

/**
 * Applied fill whose color can be changed afterwards without a new history entry (spec §4.3 «Recolor»):
 * [recolor] reverts the current pixels and refills with the new color.
 */
class FillCommand internal constructor(
    val layerId: String,
    patch: TilePatch,
    override val name: String,
    private val recompute: (Rgba) -> TilePatch?,
) : Command {
    /** The patch currently in effect; null when the last recolor changed nothing. */
    var patch: TilePatch? = patch
        private set

    override fun apply(doc: Document) {
        patch?.let { applyTiles(layer(doc).pixels, it.after) }
        doc.touch()
    }

    override fun revert(doc: Document) {
        patch?.let { applyTiles(layer(doc).pixels, it.before) }
        doc.touch()
    }

    /** Must be called while this command is applied (the latest history entry). */
    fun recolor(doc: Document, color: Rgba) {
        revert(doc)
        patch = recompute(color)
        apply(doc)
    }

    override val byteSize: Long get() = (patch?.byteSize ?: 0L) + 64L

    private fun layer(doc: Document) = doc.findNode(layerId) as? RasterLayer ?: error("raster layer $layerId not found")
}

/** Per-pixel products of masks. */
object MaskMath {
    /** `a × b` (b null = 1), rounded to 8 bits. */
    fun multiply(a: MaskGrid, b: MaskGrid?): MaskGrid {
        if (b == null) return a
        val out = MaskGrid(a.width, a.height)
        val keys = a.keys().filter { a.isInBounds(it.tx, it.ty) }.sortedBy { it.packed }
        val tiles = parallelTiles(keys) { k ->
            val ta = a.get(k)!!
            val tb = b.get(k)
            val bd = b.defaultValue.toInt() and 0xFF
            if (tb == null && bd == 0) return@parallelTiles null
            val t = MaskTile()
            var any = false
            for (i in t.data.indices) {
                val va = ta.data[i].toInt() and 0xFF
                val vb = tb?.let { it.data[i].toInt() and 0xFF } ?: bd
                val v = (va * vb + 127) / 255
                if (v != 0) { t.data[i] = v.toByte(); any = true }
            }
            if (any) t else null
        }
        for ((i, k) in keys.withIndex()) tiles[i]?.let { out.put(k, it) }
        return out
    }

    /** Full-canvas coverage (every in-canvas pixel 255) for "no limit". */
    fun full(width: Int, height: Int): MaskGrid {
        val g = MaskGrid(width, height)
        for (k in IntRect(0, 0, width, height).tileKeys(width, height)) {
            val t = MaskTile()
            val bx = k.tx shl Tile.SHIFT; val by = k.ty shl Tile.SHIFT
            for (y in 0 until minOf(Tile.SIZE, height - by)) {
                val row = y shl Tile.SHIFT
                t.data.fill(-1, row, row + minOf(Tile.SIZE, width - bx))
            }
            g.put(k, t)
        }
        return g
    }
}
