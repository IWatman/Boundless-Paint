package app.painter.core.select

import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.raster.parallelFor
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** How a new shape combines with the existing mask (spec §2.1): max/min, no edge accumulation. */
enum class SelectMode { NEW, ADD, SUBTRACT, INTERSECT }

/** A selection shape that can be turned into a closed polygon. */
sealed interface SelShape {
    /** xy pairs in document pixels, implicitly closed. */
    fun toPolygon(): FloatArray
}

/** Freehand lasso or polygon tool outline. */
class PolyShape(val pts: FloatArray) : SelShape {
    override fun toPolygon(): FloatArray = pts
}

/** Rectangle centered at ([cx], [cy]), rotated by [angleDeg] clockwise in screen space (y down). */
data class RectShape(val cx: Float, val cy: Float, val w: Float, val h: Float, val angleDeg: Float = 0f) : SelShape {
    override fun toPolygon(): FloatArray {
        val hw = w / 2; val hh = h / 2
        val corners = floatArrayOf(-hw, -hh, hw, -hh, hw, hh, -hw, hh)
        return rotateAbout(corners, cx, cy, angleDeg)
    }
}

/** Ellipse centered at ([cx], [cy]) with radii [rx], [ry]; [SEGMENTS]-gon. */
data class EllipseShape(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angleDeg: Float = 0f) : SelShape {
    override fun toPolygon(): FloatArray {
        val out = FloatArray(SEGMENTS * 2)
        for (i in 0 until SEGMENTS) {
            val t = 2 * PI * i / SEGMENTS
            out[i * 2] = (rx * cos(t)).toFloat()
            out[i * 2 + 1] = (ry * sin(t)).toFloat()
        }
        return rotateAbout(out, cx, cy, angleDeg)
    }

    companion object {
        const val SEGMENTS = 256
    }
}

private fun rotateAbout(local: FloatArray, cx: Float, cy: Float, deg: Float): FloatArray {
    val r = deg * PI / 180
    val c = cos(r).toFloat(); val s = sin(r).toFloat()
    val out = FloatArray(local.size)
    for (i in local.indices step 2) {
        val x = local[i]; val y = local[i + 1]
        out[i] = cx + x * c - y * s
        out[i + 1] = cy + x * s + y * c
    }
    return out
}

/** Pixels lifted out of a layer: [pixels] is canvas-sized, content lies within [bounds]. */
class Floating(val pixels: TileGrid, val bounds: IntRect)

/**
 * Scanline polygon rasterizer with the non-zero winding rule (spec §2.2). With antialiasing every pixel
 * gets 4×4 supersampled coverage (16 levels), without it the pixel center decides. Work is limited to the
 * polygon bbox and runs in parallel over tile rows.
 */
object PolygonRaster {
    private const val SUB = 4

    fun rasterize(poly: FloatArray, w: Int, h: Int, aa: Boolean): MaskGrid {
        val out = MaskGrid(w, h)
        val n = poly.size / 2
        if (n < 3) return out
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = poly[i * 2]; val y = poly[i * 2 + 1]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val box = IntRect(floor(minX).toInt(), floor(minY).toInt(), ceil(maxX).toInt() + 1, ceil(maxY).toInt() + 1)
            .intersect(IntRect(0, 0, w, h)) ?: return out
        val ty0 = box.y0 shr Tile.SHIFT
        val ty1 = (box.y1 - 1) shr Tile.SHIFT
        val tx0 = box.x0 shr Tile.SHIFT
        val tx1 = (box.x1 - 1) shr Tile.SHIFT
        val bands = arrayOfNulls<Array<MaskTile?>>(ty1 - ty0 + 1)
        parallelFor(ty1 - ty0 + 1) { b ->
            val ty = ty0 + b
            val y0 = maxOf(box.y0, ty shl Tile.SHIFT)
            val y1 = minOf(box.y1, (ty + 1) shl Tile.SHIFT)
            val edges = bandEdges(poly, n, y0.toFloat(), y1.toFloat())
            val row = arrayOfNulls<MaskTile>(tx1 - tx0 + 1)
            if (edges.isNotEmpty()) rasterBand(edges, box, y0, y1, aa, tx0, row)
            bands[b] = row
        }
        for (b in bands.indices) {
            val row = bands[b] ?: continue
            for (i in row.indices) row[i]?.let { out.put(TileKey(tx0 + i, ty0 + b), it) }
        }
        return out
    }

    /** Edges (x0, y0, x1, y1, dir) crossing the band [y0, y1). */
    private fun bandEdges(poly: FloatArray, n: Int, y0: Float, y1: Float): FloatArray {
        val list = ArrayList<Float>()
        for (i in 0 until n) {
            val ax = poly[i * 2]; val ay = poly[i * 2 + 1]
            val j = (i + 1) % n
            val bx = poly[j * 2]; val by = poly[j * 2 + 1]
            if (ay == by) continue
            if (maxOf(ay, by) < y0 || minOf(ay, by) >= y1) continue
            list.add(ax); list.add(ay); list.add(bx); list.add(by)
        }
        return list.toFloatArray()
    }

    private fun rasterBand(e: FloatArray, box: IntRect, y0: Int, y1: Int, aa: Boolean, tx0: Int, row: Array<MaskTile?>) {
        val bw = box.width
        val acc = IntArray(bw + 2)
        val full = IntArray(bw + 2)
        val xs = FloatArray(e.size / 4)
        val dirs = IntArray(e.size / 4)
        val order = IntArray(e.size / 4)
        val sub = if (aa) SUB else 1
        val maxCount = sub * sub
        for (y in y0 until y1) {
            acc.fill(0); full.fill(0)
            for (sy in 0 until sub) {
                val yy = y + (sy + 0.5f) / sub
                var m = 0
                for (k in 0 until e.size / 4) {
                    val ax = e[k * 4]; val ay = e[k * 4 + 1]; val bx = e[k * 4 + 2]; val by = e[k * 4 + 3]
                    val lo = minOf(ay, by); val hi = maxOf(ay, by)
                    if (yy < lo || yy >= hi) continue
                    xs[m] = ax + (yy - ay) * (bx - ax) / (by - ay)
                    dirs[m] = if (by > ay) 1 else -1
                    m++
                }
                if (m < 2) continue
                for (i in 0 until m) {
                    var j = i
                    while (j > 0 && xs[order[j - 1]] > xs[i]) { order[j] = order[j - 1]; j-- }
                    order[j] = i
                }
                var wind = 0
                for (i in 0 until m - 1) {
                    wind += dirs[order[i]]
                    if (wind == 0) continue
                    span(xs[order[i]], xs[order[i + 1]], box.x0, bw, sub, acc, full)
                }
            }
            var run = 0
            val ly = y and Tile.MASK
            for (i in 0 until bw) {
                run += full[i]
                val c = acc[i] + run
                if (c <= 0) continue
                val x = box.x0 + i
                val t = row[(x shr Tile.SHIFT) - tx0] ?: MaskTile().also { row[(x shr Tile.SHIFT) - tx0] = it }
                t.data[(ly shl Tile.SHIFT) + (x and Tile.MASK)] = ((minOf(c, maxCount) * 255 + maxCount / 2) / maxCount).toByte()
            }
        }
    }

    /** Adds the subsamples of one scanline span [xa, xb) to the per-pixel counters. */
    private fun span(xa: Float, xb: Float, ox: Int, bw: Int, sub: Int, acc: IntArray, full: IntArray) {
        val qa = maxOf(ceil((xa - ox) * sub - 0.5f).toInt(), 0)
        val qb = minOf(ceil((xb - ox) * sub - 0.5f).toInt(), bw * sub)
        if (qb <= qa) return
        val pa = qa / sub
        val pb = (qb - 1) / sub
        if (pa == pb) { acc[pa] += qb - qa; return }
        acc[pa] += sub - (qa - pa * sub)
        acc[pb] += qb - pb * sub
        if (pb > pa + 1) { full[pa + 1] += sub; full[pb] -= sub }
    }
}
