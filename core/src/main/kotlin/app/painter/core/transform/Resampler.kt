package app.painter.core.transform

import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/** Interpolation used when committing a transform (spec §3.4). */
enum class Resample { NEAREST, BILINEAR, BICUBIC }

/**
 * Box-filtered mip pyramid of a floating grid: level 0 is [base], each next level averages 2×2 texels
 * (premultiplied). Levels are built on demand and cached; thread-safe.
 */
class MipChain(val base: TileGrid) {
    private val levels = ArrayList<TileGrid>().apply { add(base) }

    @Synchronized
    fun level(l: Int): TileGrid {
        while (levels.size <= l) levels.add(halve(levels.last()))
        return levels[l]
    }

    private fun halve(g: TileGrid): TileGrid {
        val w = (g.width + 1) / 2
        val h = (g.height + 1) / 2
        val out = TileGrid(w.coerceAtLeast(1), h.coerceAtLeast(1))
        val p = FloatArray(4); val s = FloatArray(4)
        for ((k, t) in g.entries()) {
            val bx = k.tx shl Tile.SHIFT; val by = k.ty shl Tile.SHIFT
            for (y in 0 until Tile.SIZE step 2) for (x in 0 until Tile.SIZE step 2) {
                s.fill(0f)
                for (dy in 0..1) for (dx in 0..1) {
                    t.read(t.index(x + dx, y + dy), p)
                    for (c in 0 until 4) s[c] += p[c]
                }
                if (s[3] <= 0f) continue
                val ox = (bx + x) / 2; val oy = (by + y) / 2
                if (ox >= out.width || oy >= out.height) continue
                val d = out.getOrCreate(ox shr Tile.SHIFT, oy shr Tile.SHIFT)
                d.write(d.index(ox and Tile.MASK, oy and Tile.MASK), s[0] / 4, s[1] / 4, s[2] / 4, s[3] / 4)
            }
        }
        return out
    }
}

/** Reads pixels of one grid with a one-tile cache. Not thread-safe: one per worker. */
internal class GridReader(val grid: TileGrid) {
    private var lastKey = Long.MIN_VALUE
    private var last: Tile? = null

    fun read(x: Int, y: Int, out: FloatArray, off: Int) {
        if (x < 0 || y < 0 || x >= grid.width || y >= grid.height) { out.fill(0f, off, off + 4); return }
        val tx = x shr Tile.SHIFT; val ty = y shr Tile.SHIFT
        val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
        if (key != lastKey) { last = grid.get(tx, ty); lastKey = key }
        val t = last
        if (t == null) out.fill(0f, off, off + 4) else t.read(t.index(x and Tile.MASK, y and Tile.MASK), out, off)
    }
}

/** Point sampling of premultiplied grids (spec §3.4). Coordinates are continuous; pixel i spans [i, i + 1). */
object Resampler {
    private const val SNAP = 1e-3f

    /** Samples level 0 of [src] (or of [mips], when given) at ([u], [v]) into [out]. */
    fun sample(src: TileGrid, mips: MipChain?, u: Float, v: Float, mode: Resample, out: FloatArray) =
        sample(src, mips, u, v, mode, 1f, out)

    /**
     * Samples at ([u], [v]) where the forward map scales by [scale] (largest singular value). Below 0.5 the
     * mip level whose effective scale is ≥ 0.5 is used first.
     */
    fun sample(src: TileGrid, mips: MipChain?, u: Float, v: Float, mode: Resample, scale: Float, out: FloatArray) {
        val l = if (mips != null) levelFor(scale) else 0
        val g = if (l == 0) src else mips!!.level(l)
        sampleAt(GridReader(g), u / (1 shl l), v / (1 shl l), mode, out, FloatArray(64))
    }

    /** Mip level for a forward [scale]: 0 unless the map shrinks more than 2×. */
    fun levelFor(scale: Float): Int {
        if (scale >= 0.5f || scale <= 0f) return 0
        return ceil(ln(0.5 / scale) / ln(2.0) - 1e-6).toInt().coerceIn(0, 24)
    }

    internal fun sampleAt(r: GridReader, u: Float, v: Float, mode: Resample, out: FloatArray, tmp: FloatArray) {
        when (mode) {
            Resample.NEAREST -> r.read(floor(u).toInt(), floor(v).toInt(), out, 0)
            Resample.BILINEAR -> {
                val x = u - 0.5f; val y = v - 0.5f
                val ix = floor(x).toInt(); val iy = floor(y).toInt()
                val fx = snap(x - ix); val fy = snap(y - iy)
                r.read(ix, iy, tmp, 0); r.read(ix + 1, iy, tmp, 4)
                r.read(ix, iy + 1, tmp, 8); r.read(ix + 1, iy + 1, tmp, 12)
                for (c in 0 until 4) {
                    val top = tmp[c] * (1 - fx) + tmp[4 + c] * fx
                    val bot = tmp[8 + c] * (1 - fx) + tmp[12 + c] * fx
                    out[c] = top * (1 - fy) + bot * fy
                }
            }
            Resample.BICUBIC -> {
                val x = u - 0.5f; val y = v - 0.5f
                var ix = floor(x).toInt(); var iy = floor(y).toInt()
                var fx = snap(x - ix); var fy = snap(y - iy)
                if (fx >= 1f) { ix++; fx = 0f }
                if (fy >= 1f) { iy++; fy = 0f }
                if (fx == 0f && fy == 0f) { r.read(ix, iy, out, 0); return }
                val wx = cubic(fx); val wy = cubic(fy)
                out.fill(0f, 0, 4)
                for (j in 0 until 4) {
                    if (wy[j] == 0f) continue
                    for (i in 0 until 4) {
                        val w = wx[i] * wy[j]
                        if (w == 0f) continue
                        r.read(ix - 1 + i, iy - 1 + j, tmp, 0)
                        for (c in 0 until 4) out[c] += w * tmp[c]
                    }
                }
                val a = out[3].coerceIn(0f, 1f)
                out[3] = a
                for (c in 0 until 3) out[c] = out[c].coerceIn(0f, a)
            }
        }
    }

    private fun snap(f: Float): Float = when {
        f < SNAP -> 0f
        f > 1f - SNAP -> 1f
        else -> f
    }

    /** Catmull-Rom (a = −0.5) weights for taps −1, 0, 1, 2. */
    private fun cubic(t: Float): FloatArray {
        val t2 = t * t; val t3 = t2 * t
        return floatArrayOf(
            -0.5f * t3 + t2 - 0.5f * t,
            1.5f * t3 - 2.5f * t2 + 1f,
            -1.5f * t3 + 2f * t2 + 0.5f * t,
            0.5f * t3 - 0.5f * t2,
        )
    }

    /** Largest singular value of the forward map near dst ([x], [y]), from the inverse by finite differences. */
    internal fun forwardScale(m: Mapping, x: Float, y: Float): Float {
        val p0 = FloatArray(2); val px = FloatArray(2); val py = FloatArray(2)
        val h = 0.5f
        if (!m.inverse(x, y, p0) || !m.inverse(x + h, y, px) || !m.inverse(x, y + h, py)) return 1f
        val a = (px[0] - p0[0]) / h; val b = (px[1] - p0[1]) / h
        val c = (py[0] - p0[0]) / h; val d = (py[1] - p0[1]) / h
        // Singular values of the inverse Jacobian; the forward max is 1 / inverse min.
        val s1 = a * a + b * b + c * c + d * d
        val det = a * d - b * c
        val disc = sqrt(((s1 * s1) / 4 - det * det).coerceAtLeast(0f))
        val minSq = s1 / 2 - disc
        if (minSq <= 1e-12f) return 1f
        return 1f / sqrt(minSq)
    }
}
