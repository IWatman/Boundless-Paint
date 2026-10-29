package app.painter.core.stroke

import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Largest side of the n×n sample/pickup grid of one dab (spec/tools/m2-brush.md §1.8). */
private const val MAX_GRID = 64

/** Share of the pickup buffer a dab keeps at pull = 1: `P = lerp(P, C', 1 − pull·0.98)`. */
private const val PULL_KEEP = 0.98f

/**
 * Smudge kernel ([StrokeMode.BLEND], spec/tools/m2-brush.md §1.8): no colour is deposited, the dab drags the
 * colours under it along the stroke. Writes into the layer, so the engine uses it on the direct path only.
 *
 * Per dab: the colour `C` under the dab is sampled from the active layer [source] on an n×n grid in the dab frame
 * (n = min(64, ceil d), bilinear, premultiplied); then `C' = lerp(C, mean(C), blur)` with the mean weighted by the
 * dab mask. The pickup buffer `P` (n×n premultiplied RGBA, kept for the whole stroke and resampled when the dab
 * size changes) is stamped along the dab: `dst = lerp(dst, P, m·flow·blendFlow)` per pixel, premultiplied
 * including the alpha, `P` read at the pixel's own dab-local position. Afterwards `P = lerp(P, C', 1 − pull·0.98)`:
 * a high [pull] keeps the picked-up colour longer, so the smear reaches further. The first dab initialises `P = C'`.
 *
 * The brush colour is never deposited, and only the active layer is sampled: `mix_in`/`dilution` and `sample_lower`
 * follow in S4b, and a head image samples as the analytic round mask for now. With [alphaLock] the layer alpha is
 * left untouched (the premultiplied colour is clamped to it) and no tiles are created. [orderDependent]: a dab
 * reads pixels written by the previous one, so the scheduler renders it in a pass of its own and brackets it with
 * [beginDab]/[endDab] on the calling thread.
 */
class SmudgeKernel(
    private val source: TileGrid,
    private val pull: Float,
    private val blendFlow: Float,
    private val alphaLock: Boolean = false,
) : DabKernel {
    override val orderDependent: Boolean get() = true

    override val createsTiles: Boolean get() = !alphaLock

    /** Pickup buffer `P` (n×n premultiplied RGBA) and its resolution [pickN]; 0 before the first dab. */
    private var pickup = FloatArray(0)
    private var pickN = 0

    /** Sample grid `C` of the current dab and its blurred copy `C'`. */
    private var grid = FloatArray(0)
    private var blurred = FloatArray(0)
    private var gridN = 0

    /** True until the first dab of the stroke has initialised [pickup]. */
    private var cold = true

    /** Scratch of [sampleLayer]: the four bilinear neighbours of one sample. */
    private val quad = FloatArray(16)

    /** Forgets the pickup buffer; the next dab initialises it again (re-render of the stroke). */
    fun reset() {
        cold = true
        pickN = 0
        pickup = FloatArray(0)
    }

    override fun beginDab(dab: Dab) {
        val d = max(dab.d, 1f)
        val n = min(MAX_GRID, max(1, ceil(d).toInt()))
        ensure(n)
        val mask = DabMask(dab)
        val cs = cos(dab.angle)
        val sn = sin(dab.angle)
        val mean = FloatArray(4)
        val s = FloatArray(4)
        var sum = 0f
        for (i in 0 until n) {
            val b = ((i + 0.5f) / n - 0.5f) * d * dab.sy
            for (j in 0 until n) {
                val a = ((j + 0.5f) / n - 0.5f) * d * dab.sx
                // The dab-local offset (lx·sx, ly·sy) rotated into document space.
                val dx = a * cs - b * sn
                val dy = a * sn + b * cs
                sampleLayer(dab.x + dx - 0.5f, dab.y + dy - 0.5f, s)
                val o = (i * n + j) * 4
                grid[o] = s[0]; grid[o + 1] = s[1]; grid[o + 2] = s[2]; grid[o + 3] = s[3]
                val m = mask.cov(dx, dy)
                if (m > 0f) {
                    sum += m
                    mean[0] += s[0] * m; mean[1] += s[1] * m; mean[2] += s[2] * m; mean[3] += s[3] * m
                }
            }
        }
        if (sum > 0f) for (k in 0 until 4) mean[k] /= sum
        // C' = lerp(C, mean(C), blur): the mask-weighted average fades the detail inside the dab.
        val bl = dab.blur.coerceIn(0f, 1f)
        for (k in 0 until n * n * 4) blurred[k] = grid[k] + (mean[k and 3] - grid[k]) * bl
        if (pickN != n) {
            pickup = resample(pickup, pickN, n)
            pickN = n
        }
        if (cold) {
            blurred.copyInto(pickup, 0, 0, n * n * 4)
            cold = false
        }
    }

    override fun endDab(dab: Dab) {
        val k = 1f - pull.coerceIn(0f, 1f) * PULL_KEEP
        if (k <= 0f) return
        val total = pickN * pickN * 4
        for (i in 0 until total) pickup[i] += (blurred[i] - pickup[i]) * k
    }

    override fun renderTile(tile: Tile, tx: Int, ty: Int, canvasW: Int, canvasH: Int, dab: Dab, clip: MaskTile?, clipValue: Float) {
        val n = pickN
        val flow = dab.flow * blendFlow
        if (n <= 0 || dab.d <= 0f || flow <= 0f) return
        val ext = extent(dab)
        val x0 = max(0, floor(dab.x - ext).toInt())
        val y0 = max(0, floor(dab.y - ext).toInt())
        val x1 = min(canvasW - 1, ceil(dab.x + ext).toInt())
        val y1 = min(canvasH - 1, ceil(dab.y + ext).toInt())
        val baseX = tx shl Tile.SHIFT
        val baseY = ty shl Tile.SHIFT
        val px0 = max(x0, baseX) - baseX
        val px1 = min(x1, baseX + Tile.MASK) - baseX
        val py0 = max(y0, baseY) - baseY
        val py1 = min(y1, baseY + Tile.MASK) - baseY
        if (px0 > px1 || py0 > py1) return

        val mask = DabMask(dab)
        val p = FloatArray(4)
        for (py in py0..py1) {
            val dy = baseY + py + 0.5f - dab.y
            var i = (py * Tile.SIZE + px0) * 4
            for (px in px0..px1) {
                val dx = baseX + px + 0.5f - dab.x
                val cov = mask.cov(dx, dy)
                if (cov > 0f) {
                    val m = if (clip != null) clip.data[(py shl Tile.SHIFT) + px].toInt().and(0xFF) / 255f else clipValue
                    val k = cov * flow * m
                    if (k > 0f) {
                        mask.local(dx, dy, p, pickup, n)
                        val kk = min(k, 1f)
                        if (alphaLock) {
                            // Alpha lock (§1.8): the alpha is carried over, so the colour stays premultiplied by it.
                            val da = tile[i + 3]
                            tile[i] = (tile[i] + (p[0] - tile[i]) * kk).coerceIn(0f, da)
                            tile[i + 1] = (tile[i + 1] + (p[1] - tile[i + 1]) * kk).coerceIn(0f, da)
                            tile[i + 2] = (tile[i + 2] + (p[2] - tile[i + 2]) * kk).coerceIn(0f, da)
                        } else {
                            tile[i] += (p[0] - tile[i]) * kk
                            tile[i + 1] += (p[1] - tile[i + 1]) * kk
                            tile[i + 2] += (p[2] - tile[i + 2]) * kk
                            tile[i + 3] += (p[3] - tile[i + 3]) * kk
                        }
                    }
                }
                i += 4
            }
        }
        tile.markDirty()
    }

    /** Grows the per-dab buffers to an n×n grid. */
    private fun ensure(n: Int) {
        if (gridN == n) return
        gridN = n
        grid = FloatArray(n * n * 4)
        blurred = FloatArray(n * n * 4)
    }

    /** Bilinear resample of the pickup buffer from [from]² to [n]²; an empty buffer stays empty. */
    private fun resample(src: FloatArray, from: Int, n: Int): FloatArray {
        val out = FloatArray(n * n * 4)
        if (from <= 0) return out
        val t = FloatArray(4)
        for (i in 0 until n) {
            for (j in 0 until n) {
                bilinearAt(src, from, (j + 0.5f) / n, (i + 0.5f) / n, t)
                t.copyInto(out, (i * n + j) * 4)
            }
        }
        return out
    }

    /** Bilinear premultiplied sample of [source] at pixel coordinates ([px], [py]) (pixel centres at +0.5). */
    private fun sampleLayer(px: Float, py: Float, out: FloatArray) {
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

    /**
     * Analytic round/elliptic dab mask of one dab (as [MaskKernel]): coverage per dab-local offset, and the mapping
     * of a pixel of the dab bounding box to its position in the n×n pickup grid.
     */
    private class DabMask(dab: Dab) {
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

        /** Reads the pickup grid [g] ([n]²) at the dab-local position of the offset ([dx], [dy]). */
        fun local(dx: Float, dy: Float, out: FloatArray, g: FloatArray, n: Int) {
            val lx = (dx * cs + dy * sn) * isx
            val ly = (-dx * sn + dy * cs) * isy
            bilinearAt(g, n, 0.5f + lx / d, 0.5f + ly / d, out)
        }
    }
}

/** Bilinear read of a [`m`]² RGBA grid at ([u], [v]), both in 0..1; edges are clamped. */
private fun bilinearAt(g: FloatArray, m: Int, u: Float, v: Float, out: FloatArray) {
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
