package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Screentone snap (spec/tools/m2-brush.md §1.14): the dabs of the stroke paint no pixels of their own, they raise
 * the coverage `c` of the grid nodes they cover, `c = max(c, m·flow)` per node, and the stroke buffer receives one
 * dot per node: `diameter = g·sqrt(c)·1.13`, an AA disk centred exactly on the node. The area of the dots is
 * therefore proportional to the coverage, so a soft dab rim leaves small dots and the core full ones.
 *
 * The grid has step `g` = [ScreentoneParams.spacing] px (`g·d/size` with [ScreentoneParams.scaleWithSize]),
 * rotated by [ScreentoneParams.angle] and anchored at the canvas origin. Nodes are keyed by their integer grid
 * index, which is what [Dab] geometry is projected on; a node is redrawn only when its coverage grows.
 *
 * [ScreentoneShape.DOT] is implemented; [ScreentoneShape.LINE] and [ScreentoneShape.CROSS] draw dots as well
 * (TODO(S6b)). `scale_with_size` takes the step of the first dab of the stroke (TODO(S6b): re-grid per dab).
 * Head masks, texture and colour jitter do not take part in the coverage yet (a round analytic head is assumed).
 */
internal class ScreentoneEffect(
    private val params: ScreentoneParams,
    private val brushSize: Float,
    private val width: Int,
    private val height: Int,
    private val color: Rgba,
    private val clip: MaskGrid? = null,
) {
    /** Coverage `c` of every hit node, by packed grid index. */
    private val nodes = HashMap<Long, Float>()

    /** Nodes whose coverage grew in the current batch; each one is redrawn with its new dot. */
    private val grown = LinkedHashSet<Long>()

    /** Grid step of the stroke (locked on the first dab) and its rotation, cos/sin. */
    private var step = 0f
    private var cs = 1f
    private var sn = 0f

    /** Accumulates [batch] into the node map, then draws the dots that grew into [target]. */
    fun add(batch: DabBatch, target: TileAccess) {
        for (i in 0 until batch.size) accumulate(batch[i])
        if (grown.isEmpty()) return
        for (k in grown) draw(k, nodes.getValue(k), target)
        grown.clear()
    }

    /** Drops the node map: a re-rendered stroke rebuilds it from the recorded points, so the dots must too. */
    fun reset() {
        nodes.clear()
        grown.clear()
        step = 0f
    }

    /** Raises the coverage of every grid node inside [dab] to `m·flow` (spec §1.14). */
    private fun accumulate(dab: Dab) {
        if (dab.d <= 0f) return
        val flow = dab.flow * dab.a
        if (flow <= 0f) return
        val g = gridOf(dab)
        val radius = max(dab.d * 0.5f, 0.5f)
        val h = dab.hardness.coerceIn(0f, 1f)
        val rOut = radius + 0.5f
        val rIn = min(radius * h, radius - 0.5f).coerceAtLeast(0f)
        val band = rOut - rIn
        val rOut2 = rOut * rOut
        val rIn2 = rIn * rIn
        // Index box of the dab: its bbox corners taken into the rotated grid frame.
        var i0 = Int.MAX_VALUE
        var j0 = Int.MAX_VALUE
        var i1 = Int.MIN_VALUE
        var j1 = Int.MIN_VALUE
        for (c in 0 until 4) {
            val px = dab.x + if (c and 1 == 0) -rOut else rOut
            val py = dab.y + if (c and 2 == 0) -rOut else rOut
            val u = (px * cs + py * sn) / g
            val v = (-px * sn + py * cs) / g
            i0 = min(i0, floor(u).toInt())
            j0 = min(j0, floor(v).toInt())
            i1 = max(i1, floor(u).toInt() + 1)
            j1 = max(j1, floor(v).toInt() + 1)
        }
        for (j in j0..j1) {
            for (i in i0..i1) {
                val nx = (i * cs - j * sn) * g
                val ny = (i * sn + j * cs) * g
                if (nx < -rOut || ny < -rOut || nx > width + rOut || ny > height + rOut) continue
                val dx = nx - dab.x
                val dy = ny - dab.y
                val d2 = dx * dx + dy * dy
                val cov = when {
                    d2 >= rOut2 -> 0f
                    d2 <= rIn2 -> 1f
                    else -> {
                        val t = (sqrt(d2) - rIn) / band
                        1f - t * t * (3f - 2f * t)
                    }
                }
                if (cov <= 0f) continue
                val c = (cov * flow).coerceAtMost(1f)
                val key = pack(i, j)
                val old = nodes[key]
                if (old != null && old >= c) continue
                nodes[key] = c
                grown.add(key)
            }
        }
    }

    /** Draws the dot of node [key] with coverage [c] into [target] (a premultiplied stroke buffer). */
    private fun draw(key: Long, c: Float, target: TileAccess) {
        val g = step
        if (g <= 0f) return
        val i = (key shr 32).toInt()
        val j = key.toInt()
        val nx = (i * cs - j * sn) * g
        val ny = (i * sn + j * cs) * g
        val radius = g * sqrt(c.coerceIn(0f, 1f)) * 1.13f * 0.5f
        if (radius <= 0f) return
        val rOut = radius + 0.5f
        val r = color.r
        val gg = color.g
        val b = color.b
        var tileKey = Long.MIN_VALUE
        var tile: Tile? = null
        for (y in max(0, floor(ny - rOut).toInt())..min(height - 1, floor(ny + rOut).toInt())) {
            val dy = y + 0.5f - ny
            for (x in max(0, floor(nx - rOut).toInt())..min(width - 1, floor(nx + rOut).toInt())) {
                val dx = x + 0.5f - nx
                // A 1 px AA ramp of the disk edge, centred on the node; the selection clips its alpha.
                var a = (rOut - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
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
                t[p] = r * a + t[p] * inv
                t[p + 1] = gg * a + t[p + 1] * inv
                t[p + 2] = b * a + t[p + 2] * inv
                t[p + 3] = a + t[p + 3] * inv
                t.markDirty()
            }
        }
    }

    /** Grid step of [dab]; the first dab of the stroke locks the grid, later ones reuse it. */
    private fun gridOf(dab: Dab): Float {
        if (step <= 0f) {
            val g = params.spacing.coerceAtLeast(1f)
            val s = if (params.scaleWithSize && brushSize > 0f) g * dab.d / brushSize else g
            step = s.coerceIn(1f, max(width, height).toFloat())
            val rad = params.angle * DEG
            cs = cos(rad)
            sn = sin(rad)
        }
        return step
    }

    private fun pack(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)

    private companion object {
        val DEG = (PI / 180.0).toFloat()
    }
}
