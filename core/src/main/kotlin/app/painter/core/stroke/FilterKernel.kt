package app.painter.core.stroke

import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Blur radius per px of dab diameter and unit strength (spec/tools/m2-brush.md §1.16): `r = 1 + 0.06·d·|s|`, rounded. */
private const val RADIUS_PER_PX = 0.06f

/** Box passes of the filter blur (§1.16). */
private const val BOX_PASSES = 3

/** Unsharp-mask amount per unit of strength (§1.16): `amount = |strength|·1.5`. */
private const val SHARPEN_GAIN = 1.5f

/** Glow halo mask `sat((lum − 0.6)/0.4)` (§1.16). */
private const val GLOW_LOW = 0.6f
private const val GLOW_SPAN = 0.4f

/** Luminance weights of the glow mask. */
private const val LUM_R = 0.299f
private const val LUM_G = 0.587f
private const val LUM_B = 0.114f

/**
 * Filter kernel ([FilterBrushParams] with `onStroke = false`, spec/tools/m2-brush.md §1.16): the dab filters the
 * layer under itself instead of depositing colour, `out = lerp(src, F(src), m·flow·|strength|)`. It writes into the
 * layer, so the engine uses it on the direct path only.
 *
 * F is evaluated once per dab in [beginDab] over the dab bounding box plus a halo of `r` px read from the current
 * layer, so the parallel tile pass never reads a pixel another tile of the same dab writes. BLUR runs [BOX_PASSES]
 * box passes of radius `r = 1 + 0.06·d·|strength|`; BLUR with a negative strength and SHARPEN are the unsharp mask
 * `src + (src − blur)·|strength|·1.5`; GLOW adds `blur(src·sat((lum − 0.6)/0.4), r)·strength` to the source, clamped
 * to the premultiplied alpha. Strength 0 leaves the layer bit-identical. With [alphaLock] the layer alpha is kept
 * (the premultiplied colour is clamped to it); filtering a transparent pixel keeps it transparent, so the dab never
 * creates tiles. [orderDependent]: a dab reads pixels written by the previous one, so the scheduler renders it in a
 * pass of its own and brackets it with [beginDab]/[endDab] on the calling thread.
 */
class FilterKernel(
    private val source: TileGrid,
    private val params: FilterBrushParams,
    private val alphaLock: Boolean = false,
) : DabKernel {
    override val orderDependent: Boolean get() = true

    override val createsTiles: Boolean get() = false

    /** Filtered source F of the current dab, `rw`×`rh` premultiplied RGBA, and the source `plane` with its `pass`. */
    private var filtered = FloatArray(0)
    private var plane = FloatArray(0)
    private var pass = FloatArray(0)

    /** Haloed dab region the buffers describe; [empty] between dabs and when nothing filters. */
    private var x0 = 0
    private var y0 = 0
    private var rw = 0
    private var rh = 0
    private var empty = true

    override fun beginDab(dab: Dab) {
        empty = true
        val s = abs(params.strength)
        if (s <= 0f || dab.d <= 0f) return
        val radius = max(1, (1f + RADIUS_PER_PX * max(dab.d, 1f) * s).roundToInt())
        val ext = extent(dab)
        val rx0 = max(0, floor(dab.x - ext).toInt() - radius)
        val ry0 = max(0, floor(dab.y - ext).toInt() - radius)
        val rx1 = min(source.width - 1, ceil(dab.x + ext).toInt() + radius)
        val ry1 = min(source.height - 1, ceil(dab.y + ext).toInt() + radius)
        if (rx0 > rx1 || ry0 > ry1) return
        x0 = rx0
        y0 = ry0
        rw = rx1 - rx0 + 1
        rh = ry1 - ry0 + 1
        ensure()
        readSource()
        when {
            params.filter == FilterKind.GLOW -> glow(radius)
            params.filter == FilterKind.SHARPEN || params.strength < 0f -> unsharp(radius)
            else -> {
                plane.copyInto(filtered, 0, 0, rw * rh * 4)
                boxBlur(filtered, pass, radius)
            }
        }
        empty = false
    }

    override fun renderTile(tile: Tile, tx: Int, ty: Int, canvasW: Int, canvasH: Int, dab: Dab, clip: MaskTile?, clipValue: Float) {
        if (empty) return
        val flow = dab.flow * abs(params.strength)
        if (flow <= 0f) return
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
        for (py in py0..py1) {
            val ay = baseY + py
            val dy = ay + 0.5f - dab.y
            val row = (ay - y0) * rw - x0
            var i = (py * Tile.SIZE + px0) * 4
            for (px in px0..px1) {
                val ax = baseX + px
                val cov = mask.cov(ax + 0.5f - dab.x, dy)
                if (cov > 0f) {
                    val m = if (clip != null) clip.data[(py shl Tile.SHIFT) + px].toInt().and(0xFF) / 255f else clipValue
                    val k = cov * flow * m
                    if (k > 0f) {
                        // The layer pixel is the `src` of the lerp: it holds exactly what [beginDab] read.
                        val o = (row + ax) * 4
                        val kk = min(k, 1f)
                        if (alphaLock) {
                            val da = tile[i + 3]
                            tile[i] = (tile[i] + (filtered[o] - tile[i]) * kk).coerceIn(0f, da)
                            tile[i + 1] = (tile[i + 1] + (filtered[o + 1] - tile[i + 1]) * kk).coerceIn(0f, da)
                            tile[i + 2] = (tile[i + 2] + (filtered[o + 2] - tile[i + 2]) * kk).coerceIn(0f, da)
                        } else {
                            tile[i] += (filtered[o] - tile[i]) * kk
                            tile[i + 1] += (filtered[o + 1] - tile[i + 1]) * kk
                            tile[i + 2] += (filtered[o + 2] - tile[i + 2]) * kk
                            tile[i + 3] += (filtered[o + 3] - tile[i + 3]) * kk
                        }
                    }
                }
                i += 4
            }
        }
        tile.markDirty()
    }

    /** Grows the per-dab buffers to the haloed region. */
    private fun ensure() {
        val n = rw * rh * 4
        if (filtered.size >= n) return
        filtered = FloatArray(n)
        plane = FloatArray(n)
        pass = FloatArray(n)
    }

    /** Fills [plane] with the premultiplied layer of the haloed region; a missing tile is transparent. */
    private fun readSource() {
        var i = 0
        for (y in 0 until rh) {
            val py = y0 + y
            val ty = py shr Tile.SHIFT
            val row = py and Tile.MASK
            var cachedTx = Int.MIN_VALUE
            var tile: Tile? = null
            for (x in 0 until rw) {
                val px = x0 + x
                val tx = px shr Tile.SHIFT
                if (tx != cachedTx) {
                    cachedTx = tx
                    tile = source.get(tx, ty)
                }
                val t = tile
                if (t == null) {
                    plane[i] = 0f
                    plane[i + 1] = 0f
                    plane[i + 2] = 0f
                    plane[i + 3] = 0f
                } else {
                    t.read(t.index(px and Tile.MASK, row), plane, i)
                }
                i += 4
            }
        }
    }

    /** GLOW (§1.16): `F = src + blur(src·sat((lum − 0.6)/0.4), r)·strength`, clamped to the alpha. */
    private fun glow(r: Int) {
        val n = rw * rh * 4
        for (i in 0 until n step 4) {
            val lum = LUM_R * plane[i] + LUM_G * plane[i + 1] + LUM_B * plane[i + 2]
            val f = ((lum - GLOW_LOW) / GLOW_SPAN).coerceIn(0f, 1f)
            filtered[i] = plane[i] * f
            filtered[i + 1] = plane[i + 1] * f
            filtered[i + 2] = plane[i + 2] * f
            filtered[i + 3] = plane[i + 3] * f
        }
        boxBlur(filtered, pass, r)
        val k = params.strength
        for (i in 0 until n step 4) {
            val a = max(plane[i + 3], 0f)
            filtered[i] = (plane[i] + filtered[i] * k).coerceIn(0f, a)
            filtered[i + 1] = (plane[i + 1] + filtered[i + 1] * k).coerceIn(0f, a)
            filtered[i + 2] = (plane[i + 2] + filtered[i + 2] * k).coerceIn(0f, a)
            filtered[i + 3] = plane[i + 3]
        }
    }

    /** Unsharp mask (§1.16): `F = src + (src − blur(src, r))·|strength|·1.5`, clamped to the alpha. */
    private fun unsharp(r: Int) {
        val n = rw * rh * 4
        plane.copyInto(filtered, 0, 0, n)
        boxBlur(filtered, pass, r)
        val amount = abs(params.strength) * SHARPEN_GAIN
        for (i in 0 until n step 4) {
            val a = max(plane[i + 3], 0f)
            filtered[i] = (plane[i] + (plane[i] - filtered[i]) * amount).coerceIn(0f, a)
            filtered[i + 1] = (plane[i + 1] + (plane[i + 1] - filtered[i + 1]) * amount).coerceIn(0f, a)
            filtered[i + 2] = (plane[i + 2] + (plane[i + 2] - filtered[i + 2]) * amount).coerceIn(0f, a)
            filtered[i + 3] = plane[i + 3]
        }
    }

    /** [BOX_PASSES] separable box passes of radius [r] over [data] in place, [tmp] as the intermediate. */
    private fun boxBlur(data: FloatArray, tmp: FloatArray, r: Int) {
        repeat(BOX_PASSES) {
            horizontal(data, tmp, r)
            vertical(tmp, data, r)
        }
    }

    /** One horizontal box pass with clamped edges. */
    private fun horizontal(src: FloatArray, dst: FloatArray, r: Int) {
        val inv = 1f / (2 * r + 1)
        val last = rw - 1
        for (y in 0 until rh) {
            val base = y * rw * 4
            var s0 = 0f
            var s1 = 0f
            var s2 = 0f
            var s3 = 0f
            for (k in -r..r) {
                val i = base + k.coerceIn(0, last) * 4
                s0 += src[i]; s1 += src[i + 1]; s2 += src[i + 2]; s3 += src[i + 3]
            }
            for (x in 0 until rw) {
                val o = base + x * 4
                dst[o] = s0 * inv; dst[o + 1] = s1 * inv; dst[o + 2] = s2 * inv; dst[o + 3] = s3 * inv
                val a = base + (x + r + 1).coerceIn(0, last) * 4
                val b = base + (x - r).coerceIn(0, last) * 4
                s0 += src[a] - src[b]; s1 += src[a + 1] - src[b + 1]
                s2 += src[a + 2] - src[b + 2]; s3 += src[a + 3] - src[b + 3]
            }
        }
    }

    /** One vertical box pass with clamped edges. */
    private fun vertical(src: FloatArray, dst: FloatArray, r: Int) {
        val inv = 1f / (2 * r + 1)
        val last = rh - 1
        val stride = rw * 4
        for (x in 0 until rw) {
            val base = x * 4
            var s0 = 0f
            var s1 = 0f
            var s2 = 0f
            var s3 = 0f
            for (k in -r..r) {
                val i = base + k.coerceIn(0, last) * stride
                s0 += src[i]; s1 += src[i + 1]; s2 += src[i + 2]; s3 += src[i + 3]
            }
            for (y in 0 until rh) {
                val o = base + y * stride
                dst[o] = s0 * inv; dst[o + 1] = s1 * inv; dst[o + 2] = s2 * inv; dst[o + 3] = s3 * inv
                val a = base + (y + r + 1).coerceIn(0, last) * stride
                val b = base + (y - r).coerceIn(0, last) * stride
                s0 += src[a] - src[b]; s1 += src[a + 1] - src[b + 1]
                s2 += src[a + 2] - src[b + 2]; s3 += src[a + 3] - src[b + 3]
            }
        }
    }
}
