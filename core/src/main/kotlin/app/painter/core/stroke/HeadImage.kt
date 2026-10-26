package app.painter.core.stroke

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Decoded image (the app decodes PNG): 8-bit [channels] = 1 (gray) or 4 (straight RGBA), row-major. */
class ImageData(val width: Int, val height: Int, val channels: Int, val pixels: ByteArray) {
    init {
        require(channels == 1 || channels == 4) { "channels must be 1 or 4" }
        require(width > 0 && height > 0 && pixels.size == width * height * channels) { "bad image size" }
    }
}

/** Longest side a decoded head or texture may keep; larger images are box-downscaled on load. */
private const val MAX_SOURCE_SIDE = 1024

/** Box-downscales [src] by halving until its longer side fits [MAX_SOURCE_SIDE]; smaller images pass through. */
private fun limitSource(src: ImageData): ImageData {
    if (max(src.width, src.height) <= MAX_SOURCE_SIDE) return src
    var cur = src
    while (max(cur.width, cur.height) > MAX_SOURCE_SIDE) cur = halve(cur)
    return cur
}

/** One 2×2 box-downscale step (edges clamp), preserving the channel layout. */
private fun halve(src: ImageData): ImageData {
    val w = max(1, src.width / 2)
    val h = max(1, src.height / 2)
    val ch = src.channels
    val sw = src.width
    val sh = src.height
    val px = ByteArray(w * h * ch)
    for (y in 0 until h) for (x in 0 until w) for (c in 0 until ch) {
        var sum = 0
        for (dy in 0..1) for (dx in 0..1) {
            val sx = min(sw - 1, x * 2 + dx)
            val sy = min(sh - 1, y * 2 + dy)
            sum += src.pixels[(sy * sw + sx) * ch + c].toInt() and 0xFF
        }
        px[(y * w + x) * ch + c] = ((sum + 2) / 4).toByte()
    }
    return ImageData(w, h, ch, px)
}

/** One mip level: [w]×[h] floats, [rgba] premultiplied colour when the head is coloured. */
internal class MipLevel(val w: Int, val h: Int, val mask: FloatArray, val rgba: FloatArray?)

/**
 * Pyramid of one (possibly softened) head mask. Sampling is bilinear between texel centres with a
 * transparent border, UV (0,0)–(1,1) covering the whole image.
 */
class HeadPyramid internal constructor(private val levels: Array<MipLevel>, private val srcSize: Int) {
    val levelCount: Int get() = levels.size

    /** Mip level for dab diameter [d]: `clamp(floor(log2(srcSize / d)), 0, max)`. */
    fun level(d: Float): Int {
        if (d <= 0f) return levels.size - 1
        val r = srcSize / d
        if (r < 2f) return 0
        val l = floor(log2(r)).toInt()
        return l.coerceIn(0, levels.size - 1)
    }

    /** Mask coverage 0..1 at ([u], [v]). */
    fun sample(level: Int, u: Float, v: Float): Float {
        val m = levels[level]
        val fx = u * m.w - 0.5f
        val fy = v * m.h - 0.5f
        if (fx <= -1f || fy <= -1f || fx >= m.w || fy >= m.h) return 0f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val ax = fx - x0
        val ay = fy - y0
        val a = m.mask
        val w = m.w
        val inX0 = x0 >= 0
        val inX1 = x0 + 1 < w
        var top = 0f
        var bot = 0f
        if (y0 >= 0) {
            val row = y0 * w
            val p0 = if (inX0) a[row + x0] else 0f
            val p1 = if (inX1) a[row + x0 + 1] else 0f
            top = p0 + (p1 - p0) * ax
        }
        if (y0 + 1 < m.h) {
            val row = (y0 + 1) * w
            val p0 = if (inX0) a[row + x0] else 0f
            val p1 = if (inX1) a[row + x0 + 1] else 0f
            bot = p0 + (p1 - p0) * ax
        }
        return top + (bot - top) * ay
    }

    /** Premultiplied RGBA at ([u], [v]) into [out]; a gray head yields white × mask. */
    fun sampleRgba(level: Int, u: Float, v: Float, out: FloatArray) {
        val m = levels[level]
        val rgba = m.rgba
        if (rgba == null) {
            val a = sample(level, u, v)
            out[0] = a; out[1] = a; out[2] = a; out[3] = a
            return
        }
        out.fill(0f, 0, 4)
        val fx = u * m.w - 0.5f
        val fy = v * m.h - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val ax = fx - x0
        val ay = fy - y0
        for (dy in 0..1) for (dx in 0..1) {
            val x = x0 + dx
            val y = y0 + dy
            if (x < 0 || y < 0 || x >= m.w || y >= m.h) continue
            val wgt = (if (dx == 0) 1f - ax else ax) * (if (dy == 0) 1f - ay else ay)
            val i = (y * m.w + x) * 4
            out[0] += rgba[i] * wgt; out[1] += rgba[i + 1] * wgt
            out[2] += rgba[i + 2] * wgt; out[3] += rgba[i + 3] * wgt
        }
    }

    private fun log2(x: Float): Float = (kotlin.math.ln(x.toDouble()) / LN2).toFloat()

    private companion object {
        val LN2 = kotlin.math.ln(2.0)
    }
}

/**
 * Bitmap brush head (spec/tools/m2-brush.md §1.1). The mask is the gray value (white = coverage) or the
 * alpha of an RGBA image; [curve] (256-entry LUT) and [invert] are applied once at load. Softness is a
 * Gaussian blur with σ = (1 − hardness)·0.15·srcSize, quantised to [SOFT_LEVELS] cached pyramids.
 *
 * Sources longer than [MAX_SOURCE_SIDE] are box-downscaled first, and a very wide kernel is applied to a
 * halved copy before being stretched back, so construction cost stays bounded for huge head images.
 */
class HeadImage(src: ImageData, curve: Curve? = null, invert: Boolean = false) {
    private val image = limitSource(src)

    /** Head width in px after the size limit. */
    val width = image.width

    /** Head height in px after the size limit. */
    val height = image.height

    val colored = image.channels == 4
    private val srcSize = max(width, height)
    private val base: FloatArray
    private val baseRgba: FloatArray?
    private val pyramids = arrayOfNulls<HeadPyramid>(SOFT_LEVELS)

    init {
        val lut = FloatArray(256) { i ->
            var v = i / 255f
            if (invert) v = 1f - v
            curve?.eval(v) ?: v
        }
        val n = width * height
        base = FloatArray(n)
        baseRgba = if (colored) FloatArray(n * 4) else null
        val px = image.pixels
        for (i in 0 until n) {
            if (colored) {
                val a = lut[px[i * 4 + 3].toInt() and 0xFF]
                base[i] = a
                for (c in 0..2) baseRgba!![i * 4 + c] = (px[i * 4 + c].toInt() and 0xFF) / 255f * a
                baseRgba!![i * 4 + 3] = a
            } else {
                base[i] = lut[px[i].toInt() and 0xFF]
            }
        }
    }

    /** Unsoftened pyramid. */
    val sharp: HeadPyramid get() = pyramid(1f)

    /** Pyramid for [hardness] (0 = softest). */
    fun pyramid(hardness: Float): HeadPyramid {
        val q = ((1f - hardness.coerceIn(0f, 1f)) * (SOFT_LEVELS - 1) + 0.5f).toInt()
        synchronized(pyramids) {
            pyramids[q]?.let { return it }
            val p = build(q)
            pyramids[q] = p
            return p
        }
    }

    /** Mip level of the unsoftened pyramid for diameter [d]. */
    fun level(d: Float): Int = sharp.level(d)

    /** Unsoftened mask sample. */
    fun sample(level: Int, u: Float, v: Float): Float = sharp.sample(level, u, v)

    /** Unsoftened premultiplied colour sample. */
    fun sampleRgba(level: Int, u: Float, v: Float, out: FloatArray) = sharp.sampleRgba(level, u, v, out)

    private fun build(q: Int): HeadPyramid {
        val levels = ArrayList<MipLevel>()
        var cur = if (q == 0) MipLevel(width, height, base, baseRgba) else soft(q)
        levels.add(cur)
        while (cur.w > 1 || cur.h > 1) {
            cur = downsample(cur)
            levels.add(cur)
        }
        return HeadPyramid(levels.toTypedArray(), srcSize)
    }

    /**
     * Base level blurred for softness step [q]. Kernels wider than [MAX_BLUR_SIGMA] run on a halved copy
     * (σ halved with it) and are stretched back, which keeps the cost independent of the source size.
     */
    private fun soft(q: Int): MipLevel {
        var level = MipLevel(width, height, base, baseRgba)
        var sigma = q.toFloat() / (SOFT_LEVELS - 1) * SOFT_SIGMA * srcSize
        while (sigma > MAX_BLUR_SIGMA && (level.w > 1 || level.h > 1)) {
            level = downsample(level)
            sigma *= 0.5f
        }
        val mask = blur(level.mask, level.w, level.h, 1, sigma)
        val rgba = level.rgba?.let { blur(it, level.w, level.h, 4, sigma) }
        if (level.w == width && level.h == height) return MipLevel(width, height, mask, rgba)
        return upscale(mask, rgba, level.w, level.h)
    }

    /** Bilinear stretch of a blurred smaller level back to the head size. */
    private fun upscale(mask: FloatArray, rgba: FloatArray?, w: Int, h: Int): MipLevel {
        val outMask = FloatArray(width * height)
        val outRgba = if (rgba != null) FloatArray(width * height * 4) else null
        val stepX = w.toFloat() / width
        val stepY = h.toFloat() / height
        for (y in 0 until height) {
            val fy = ((y + 0.5f) * stepY - 0.5f).coerceIn(0f, h - 1f)
            val y0 = fy.toInt()
            val y1 = min(h - 1, y0 + 1)
            val ay = fy - y0
            for (x in 0 until width) {
                val fx = ((x + 0.5f) * stepX - 0.5f).coerceIn(0f, w - 1f)
                val x0 = fx.toInt()
                val x1 = min(w - 1, x0 + 1)
                val ax = fx - x0
                val i00 = y0 * w + x0
                val i10 = y0 * w + x1
                val i01 = y1 * w + x0
                val i11 = y1 * w + x1
                val w00 = (1f - ax) * (1f - ay)
                val w10 = ax * (1f - ay)
                val w01 = (1f - ax) * ay
                val w11 = ax * ay
                outMask[y * width + x] = mask[i00] * w00 + mask[i10] * w10 + mask[i01] * w01 + mask[i11] * w11
                if (outRgba != null && rgba != null) {
                    val o = (y * width + x) * 4
                    for (c in 0..3) {
                        outRgba[o + c] = rgba[i00 * 4 + c] * w00 + rgba[i10 * 4 + c] * w10 +
                            rgba[i01 * 4 + c] * w01 + rgba[i11 * 4 + c] * w11
                    }
                }
            }
        }
        return MipLevel(width, height, outMask, outRgba)
    }

    private fun downsample(m: MipLevel): MipLevel {
        val w = max(1, m.w / 2)
        val h = max(1, m.h / 2)
        val mask = FloatArray(w * h)
        val rgba = m.rgba?.let { FloatArray(w * h * 4) }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var cnt = 0
            val acc = FloatArray(4)
            for (dy in 0..1) for (dx in 0..1) {
                val sx = min(m.w - 1, x * 2 + dx)
                val sy = min(m.h - 1, y * 2 + dy)
                val i = sy * m.w + sx
                s += m.mask[i]
                if (m.rgba != null) for (c in 0..3) acc[c] += m.rgba[i * 4 + c]
                cnt++
            }
            mask[y * w + x] = s / cnt
            if (rgba != null) for (c in 0..3) rgba[(y * w + x) * 4 + c] = acc[c] / cnt
        }
        return MipLevel(w, h, mask, rgba)
    }

    private fun blur(src: FloatArray, w: Int, h: Int, ch: Int, sigma: Float): FloatArray {
        val r = (sigma * 3f).toInt().coerceAtLeast(1)
        val k = FloatArray(2 * r + 1) { val x = (it - r).toFloat(); exp(-x * x / (2f * sigma * sigma)) }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until ch) {
            var s = 0f
            for (j in -r..r) {
                val xx = x + j
                if (xx in 0 until w) s += src[(y * w + xx) * ch + c] * k[j + r]
            }
            tmp[(y * w + x) * ch + c] = s
        }
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until ch) {
            var s = 0f
            for (j in -r..r) {
                val yy = y + j
                if (yy in 0 until h) s += tmp[(yy * w + x) * ch + c] * k[j + r]
            }
            out[(y * w + x) * ch + c] = s
        }
        return out
    }

    companion object {
        const val SOFT_LEVELS = 8

        /** σ per unit of (1 − hardness) relative to the head side. */
        private const val SOFT_SIGMA = 0.15f

        /** Blur budget: above this σ the kernel is applied to a halved copy (a 512 px head's worth of taps). */
        private const val MAX_BLUR_SIGMA = SOFT_SIGMA * 512f
    }
}

/**
 * Tiling gray texture sampled bilinearly with wrap-around; `u, v` in texture periods. Sources longer than
 * [MAX_SOURCE_SIDE] are box-downscaled on load. Grain processing (curve, softness, stretch…) arrives with S2.
 */
class TextureImage(src: ImageData, invert: Boolean = false) {
    private val image = limitSource(src)
    val width = image.width
    val height = image.height
    private val v = FloatArray(width * height) { i ->
        val raw = if (image.channels == 1) image.pixels[i] else image.pixels[i * 4]
        val f = (raw.toInt() and 0xFF) / 255f
        if (invert) 1f - f else f
    }

    /** Value 0..1 at ([u], [v]) in periods (1 = one texture width). */
    fun sample(u: Float, vv: Float): Float {
        val fx = u * width - 0.5f
        val fy = vv * height - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val ax = fx - x0
        val ay = fy - y0
        val xa = Math.floorMod(x0, width)
        val xb = Math.floorMod(x0 + 1, width)
        val ya = Math.floorMod(y0, height) * width
        val yb = Math.floorMod(y0 + 1, height) * width
        val top = v[ya + xa] + (v[ya + xb] - v[ya + xa]) * ax
        val bot = v[yb + xa] + (v[yb + xb] - v[yb + xa]) * ax
        return top + (bot - top) * ay
    }
}

/** Loads image references (`asset:heads/x.png`, `brush:head.png`); implemented by the app. */
fun interface ResourceResolver {
    fun image(ref: String): ImageData
}

/** Decoded resources of one brush. */
class BrushResources(val head: HeadImage? = null, val texture: TextureImage? = null, val particle: HeadImage? = null) {
    companion object {
        val EMPTY = BrushResources()

        /** Resolves and decodes every resource [brush] references. */
        fun load(brush: Brush, resolver: ResourceResolver): BrushResources {
            val h = brush.params.head
            val t = brush.params.texture
            return BrushResources(
                head = brush.resources.head?.let { HeadImage(resolver.image(it), h.curve, h.invert) },
                texture = brush.resources.texture?.let { TextureImage(resolver.image(it), t?.invert ?: false) },
                particle = brush.resources.particle?.let { HeadImage(resolver.image(it)) },
            )
        }
    }
}
