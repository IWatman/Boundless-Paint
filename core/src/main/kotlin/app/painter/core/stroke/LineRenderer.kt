package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AA line of the harmony engine (spec/tools/m2-brush.md §1.11): a capsule of thickness [thickness] from `p0` to
 * `p1`, its coverage `1 − d/(r + 0.5)` by the distance `d` of the pixel centre to the segment (`r` = half the
 * thickness), so a segment is a 1 px AA ramp of its outline.
 *
 * The pixels are composited source-over into the premultiplied stroke buffer exactly like the dabs of
 * [MaskKernel] do, and the selection mask clips their alpha ([MaskGrid]).
 */
internal class LineRenderer(
    private val width: Int,
    private val height: Int,
    private val color: Rgba,
    private val clip: MaskGrid? = null,
) {
    /** Draws the segment `p0 → p1` of [thickness] with [alpha] (the stroke colour alpha is applied as well). */
    fun stroke(target: TileAccess, x0: Float, y0: Float, x1: Float, y1: Float, thickness: Float, alpha: Float) {
        val sa = alpha.coerceIn(0f, 1f) * color.a
        if (sa <= 0f || thickness <= 0f) return
        val rOut = thickness * 0.5f + 0.5f
        val bx0 = max(0, floor(min(x0, x1) - rOut).toInt())
        val bx1 = min(width - 1, floor(max(x0, x1) + rOut).toInt())
        val by0 = max(0, floor(min(y0, y1) - rOut).toInt())
        val by1 = min(height - 1, floor(max(y0, y1) + rOut).toInt())
        if (bx0 > bx1 || by0 > by1) return
        val cr = color.r
        val cg = color.g
        val cb = color.b
        var tileKey = Long.MIN_VALUE
        var tile: Tile? = null
        for (y in by0..by1) {
            val py = y + 0.5f
            for (x in bx0..bx1) {
                val d = distance(x + 0.5f, py, x0, y0, x1, y1)
                var a = (rOut - d).coerceIn(0f, 1f) * sa
                if (a <= 0f) continue
                val m = clip?.value(x, y) ?: 1f
                if (m < 1f) a *= m
                if (a <= 0f) continue
                val k = TileKey(x shr Tile.SHIFT, y shr Tile.SHIFT)
                if (k.packed != tileKey) {
                    tileKey = k.packed
                    tile = target.prepare(k, create = true)
                }
                val t = tile ?: continue
                val p = t.index(x and Tile.MASK, y and Tile.MASK)
                val inv = 1f - a
                t[p] = cr * a + t[p] * inv
                t[p + 1] = cg * a + t[p + 1] * inv
                t[p + 2] = cb * a + t[p + 2] * inv
                t[p + 3] = a + t[p + 3] * inv
                t.markDirty()
            }
        }
    }

    /** Distance of `(px, py)` to the segment `p0 → p1`. */
    private fun distance(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0f) 0f else (((px - x0) * dx + (py - y0) * dy) / len2).coerceIn(0f, 1f)
        val ex = px - (x0 + dx * t)
        val ey = py - (y0 + dy * t)
        return sqrt(ex * ex + ey * ey)
    }
}
