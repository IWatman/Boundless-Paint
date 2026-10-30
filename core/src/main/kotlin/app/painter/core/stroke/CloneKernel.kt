package app.painter.core.stroke

import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Clone kernel ([StrokeMode.CLONE], spec/tools/m2-brush.md §1.18), direct path: per pixel the dab deposits
 * `src(p − offset)` (bilinear) × m·flow, premultiplied over the layer. [source] is the snapshot the engine takes
 * before the first dab, so cloning into the source layer itself never feeds back. Only the plain (non-artistic)
 * mode is implemented; artistic clones and the source crosshair follow later.
 *
 * Writes into the layer, so the engine uses it on the direct path only. [alphaLock] keeps the layer alpha (the
 * premultiplied colour is clamped to it) and never creates tiles.
 */
class CloneKernel(
    private val source: TileGrid,
    private val offsetX: Float = 0f,
    private val offsetY: Float = 0f,
    private val alphaLock: Boolean = false,
) : DabKernel {
    override val createsTiles: Boolean get() = !alphaLock

    /** Bilinear premultiplied sampling of the snapshot, shared with [SmudgeKernel]. */
    private val sampler = DabSampler(source)

    override fun renderTile(tile: Tile, tx: Int, ty: Int, canvasW: Int, canvasH: Int, dab: Dab, clip: MaskTile?, clipValue: Float) {
        val flow = dab.flow
        if (flow <= 0f || dab.d <= 0f) return
        val ext = extent(dab)
        val bx0 = max(0, floor(dab.x - ext).toInt())
        val by0 = max(0, floor(dab.y - ext).toInt())
        val bx1 = min(canvasW - 1, ceil(dab.x + ext).toInt())
        val by1 = min(canvasH - 1, ceil(dab.y + ext).toInt())
        val baseX = tx shl Tile.SHIFT
        val baseY = ty shl Tile.SHIFT
        val px0 = max(bx0, baseX) - baseX
        val px1 = min(bx1, baseX + Tile.MASK) - baseX
        val py0 = max(by0, baseY) - baseY
        val py1 = min(by1, baseY + Tile.MASK) - baseY
        if (px0 > px1 || py0 > py1) return

        val mask = DabMask(dab)
        val s = FloatArray(4)
        for (py in py0..py1) {
            val ay = baseY + py
            val dy = ay + 0.5f - dab.y
            var i = (py * Tile.SIZE + px0) * 4
            for (px in px0..px1) {
                val ax = baseX + px
                val cov = mask.cov(ax + 0.5f - dab.x, dy)
                if (cov > 0f) {
                    val m = if (clip != null) clip.data[(py shl Tile.SHIFT) + px].toInt().and(0xFF) / 255f else clipValue
                    val k = cov * flow * m
                    if (k > 0f) {
                        // The sampler takes pixel centres at whole coordinates, so a pixel centre maps to (x, y).
                        sampler.sample(ax - offsetX, ay - offsetY, s)
                        val sa = s[3] * k
                        val inv = 1f - sa
                        if (alphaLock) {
                            val da = tile[i + 3]
                            tile[i] = (s[0] * k + tile[i] * inv).coerceIn(0f, da)
                            tile[i + 1] = (s[1] * k + tile[i + 1] * inv).coerceIn(0f, da)
                            tile[i + 2] = (s[2] * k + tile[i + 2] * inv).coerceIn(0f, da)
                        } else {
                            tile[i] = s[0] * k + tile[i] * inv
                            tile[i + 1] = s[1] * k + tile[i + 1] * inv
                            tile[i + 2] = s[2] * k + tile[i + 2] * inv
                            tile[i + 3] = sa + tile[i + 3] * inv
                        }
                    }
                }
                i += 4
            }
        }
        tile.markDirty()
    }
}
