package app.painter.core.stroke

import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Largest side of the n×n sample/pickup grid of one dab (spec/tools/m2-brush.md §1.8). */
internal const val MAX_GRID = 64

/**
 * Sampling of the layer under a dab (spec/tools/m2-brush.md §1.8), shared by [SmudgeKernel] and [PaintMixKernel]:
 * bilinear premultiplied reads of [source] at pixel coordinates (pixel centres at +0.5). Outside the canvas or over
 * a missing tile the sample is transparent.
 */
internal class DabSampler(private val source: TileGrid) {
    /** The four bilinear neighbours of one sample. */
    private val quad = FloatArray(16)

    /** Bilinear premultiplied sample of [source] at pixel coordinates ([px], [py]) into [out]. */
    fun sample(px: Float, py: Float, out: FloatArray) {
        val x0 = floor(px).toInt()
        val y0 = floor(py).toInt()
        val ax = px - x0
        val ay = py - y0
        val q = quad
        read(x0, y0, q, 0)
        read(x0 + 1, y0, q, 4)
        read(x0, y0 + 1, q, 8)
        read(x0 + 1, y0 + 1, q, 12)
        for (ch in 0 until 4) {
            val a = q[ch] + (q[4 + ch] - q[ch]) * ax
            val b = q[8 + ch] + (q[12 + ch] - q[8 + ch]) * ax
            out[ch] = a + (b - a) * ay
        }
    }

    /** One premultiplied pixel of [source]; outside the canvas or of a missing tile it is transparent. */
    private fun read(x: Int, y: Int, out: FloatArray, off: Int) {
        if (x < 0 || y < 0 || x >= source.width || y >= source.height) {
            out[off] = 0f; out[off + 1] = 0f; out[off + 2] = 0f; out[off + 3] = 0f
            return
        }
        val t = source.get(x shr Tile.SHIFT, y shr Tile.SHIFT)
        if (t == null) {
            out[off] = 0f; out[off + 1] = 0f; out[off + 2] = 0f; out[off + 3] = 0f
            return
        }
        t.read(t.index(x and Tile.MASK, y and Tile.MASK), out, off)
    }
}

/**
 * Analytic round/elliptic dab mask of one dab (as [MaskKernel]): coverage per dab-local offset, and the mapping
 * of a pixel of the dab bounding box to its position in an n×n grid in the dab frame.
 */
internal class DabMask(dab: Dab) {
    private val cs = cos(dab.angle)
    private val sn = sin(dab.angle)
    private val isx = 1f / dab.sx
    private val isy = 1f / dab.sy
    private val d = max(dab.d, 1f)
    private val radius = max(dab.d * 0.5f, 0.5f)
    private val rOut = radius + 0.5f
    private val rIn = max(0f, min(radius * dab.hardness.coerceIn(0f, 1f), radius - 0.5f))
    private val band = rOut - rIn
    private val rOut2 = rOut * rOut
    private val rIn2 = rIn * rIn
    private val circle = dab.sx == 1f && dab.sy == 1f

    /** Coverage at the dab-local offset ([dx], [dy]) from the dab centre. */
    fun cov(dx: Float, dy: Float): Float {
        val d2 = if (circle) {
            dx * dx + dy * dy
        } else {
            val lx = (dx * cs + dy * sn) * isx
            val ly = (-dx * sn + dy * cs) * isy
            lx * lx + ly * ly
        }
        return when {
            d2 >= rOut2 -> 0f
            d2 <= rIn2 -> 1f
            else -> {
                val t = (sqrt(d2) - rIn) / band
                1f - t * t * (3f - 2f * t)
            }
        }
    }

    /** Reads the grid [g] ([n]²) at the dab-local position of the offset ([dx], [dy]). */
    fun local(dx: Float, dy: Float, out: FloatArray, g: FloatArray, n: Int) {
        val lx = (dx * cs + dy * sn) * isx
        val ly = (-dx * sn + dy * cs) * isy
        bilinearAt(g, n, 0.5f + lx / d, 0.5f + ly / d, out)
    }
}

/** Bilinear read of a [`m`]² RGBA grid at ([u], [v]), both in 0..1; edges are clamped. */
internal fun bilinearAt(g: FloatArray, m: Int, u: Float, v: Float, out: FloatArray) {
    val fx = (u * m - 0.5f).coerceIn(0f, (m - 1).toFloat())
    val fy = (v * m - 0.5f).coerceIn(0f, (m - 1).toFloat())
    val x0 = fx.toInt()
    val y0 = fy.toInt()
    val x1 = min(x0 + 1, m - 1)
    val y1 = min(y0 + 1, m - 1)
    val ax = fx - x0
    val ay = fy - y0
    val i00 = (y0 * m + x0) * 4
    val i10 = (y0 * m + x1) * 4
    val i01 = (y1 * m + x0) * 4
    val i11 = (y1 * m + x1) * 4
    for (ch in 0 until 4) {
        val a = g[i00 + ch] + (g[i10 + ch] - g[i00 + ch]) * ax
        val b = g[i01 + ch] + (g[i11 + ch] - g[i01 + ch]) * ax
        out[ch] = a + (b - a) * ay
    }
}
