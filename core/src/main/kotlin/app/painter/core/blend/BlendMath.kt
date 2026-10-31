package app.painter.core.blend

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * CPU reference implementation of every [BlendMode] (spec/blend-and-color.md).
 * All pixels are premultiplied RGBA floats, 4 consecutive values per pixel.
 */
object BlendMath {
    private const val EPS = 1e-6f

    private class Scratch {
        val cb = FloatArray(3)
        val cs = FloatArray(3)
        val out = FloatArray(3)
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    /**
     * Composites the premultiplied pixel `src[sOff..sOff+3]` onto `dst[dOff..dOff+3]` in place.
     * [opacity] scales the source alpha (and premultiplied color) before blending.
     * [BlendMode.PASSTHROUGH] behaves as [BlendMode.NORMAL] at pixel level.
     *
     * With [gamma] the blend happens in linear light (spec/blend-and-color.md, document toggle
     * "gamma-correct"): both pixels are decoded from sRGB, blended as usual and encoded back. Off by default.
     */
    fun blendPixel(
        mode: BlendMode,
        dst: FloatArray,
        dOff: Int,
        src: FloatArray,
        sOff: Int,
        opacity: Float,
        gamma: Boolean = false,
    ) {
        if (!gamma) {
            blendPixelSrgb(mode, dst, dOff, src, sOff, opacity)
            return
        }
        if (src[sOff + 3] * opacity <= 0f) return
        val g = gammaPixel.get()
        System.arraycopy(src, sOff, g.src, 0, 4)
        System.arraycopy(dst, dOff, g.dst, 0, 4)
        premulToLinear(g.src, 0)
        premulToLinear(g.dst, 0)
        blendPixelSrgb(mode, g.dst, 0, g.src, 0, opacity)
        premulToSrgb(g.dst, 0)
        dst[dOff] = g.dst[0]
        dst[dOff + 1] = g.dst[1]
        dst[dOff + 2] = g.dst[2]
        dst[dOff + 3] = g.dst[3]
    }

    /** sRGB reference blend of [blendPixel] with `gamma = false`; all values are premultiplied sRGB. */
    private fun blendPixelSrgb(
        mode: BlendMode,
        dst: FloatArray,
        dOff: Int,
        src: FloatArray,
        sOff: Int,
        opacity: Float,
    ) {
        val srcA = src[sOff + 3]
        val aS = (srcA * opacity).coerceIn(0f, 1f)
        if (aS <= 0f) return
        val aB = dst[dOff + 3].coerceIn(0f, 1f)

        val invS = if (srcA > EPS) 1f / srcA else 0f
        val sr = (src[sOff] * invS).coerceIn(0f, 1f)
        val sg = (src[sOff + 1] * invS).coerceIn(0f, 1f)
        val sb = (src[sOff + 2] * invS).coerceIn(0f, 1f)
        val invB = if (aB > EPS) 1f / aB else 0f
        val br = (dst[dOff] * invB).coerceIn(0f, 1f)
        val bg = (dst[dOff + 1] * invB).coerceIn(0f, 1f)
        val bb = (dst[dOff + 2] * invB).coerceIn(0f, 1f)

        when (mode) {
            BlendMode.NORMAL, BlendMode.PASSTHROUGH -> {
                val k = 1f - aS
                dst[dOff] = sr * aS + dst[dOff] * k
                dst[dOff + 1] = sg * aS + dst[dOff + 1] * k
                dst[dOff + 2] = sb * aS + dst[dOff + 2] * k
                dst[dOff + 3] = aS + aB * k
            }

            BlendMode.ADD -> {
                val k = aS * (1f - aB)
                dst[dOff] = k * sr + aB * min(1f, br + sr * aS)
                dst[dOff + 1] = k * sg + aB * min(1f, bg + sg * aS)
                dst[dOff + 2] = k * sb + aB * min(1f, bb + sb * aS)
                dst[dOff + 3] = aS + aB * (1f - aS)
            }

            BlendMode.INVERT -> {
                dst[dOff] = aB * (br + (1f - 2f * br) * aS)
                dst[dOff + 1] = aB * (bg + (1f - 2f * bg) * aS)
                dst[dOff + 2] = aB * (bb + (1f - 2f * bb) * aS)
            }

            BlendMode.ERASE -> {
                val k = 1f - aS
                dst[dOff] *= k; dst[dOff + 1] *= k; dst[dOff + 2] *= k; dst[dOff + 3] *= k
            }

            BlendMode.MASK -> {
                val m = lum(sr, sg, sb) * aS + (1f - aS)
                dst[dOff] *= m; dst[dOff + 1] *= m; dst[dOff + 2] *= m; dst[dOff + 3] *= m
            }

            BlendMode.LINE_ART -> {
                val l = lum(sr, sg, sb)
                val m = l * aS + (1f - aS)
                val ink = (1f - l) * aS
                val aO = max(aB * m, ink)
                val c = (1f - ink) * aO
                dst[dOff] = br * c
                dst[dOff + 1] = bg * c
                dst[dOff + 2] = bb * c
                dst[dOff + 3] = aO
            }

            BlendMode.HUE, BlendMode.SATURATION, BlendMode.COLOR, BlendMode.LUMINOSITY,
            BlendMode.DARKER_COLOR, BlendMode.LIGHTER_COLOR -> {
                val s = scratch.get()
                s.cb[0] = br; s.cb[1] = bg; s.cb[2] = bb
                s.cs[0] = sr; s.cs[1] = sg; s.cs[2] = sb
                nonSeparable(mode, s.cb, s.cs, s.out)
                composeW3c(dst, dOff, aB, aS, br, bg, bb, sr, sg, sb, s.out[0], s.out[1], s.out[2])
            }

            else -> composeW3c(
                dst, dOff, aB, aS, br, bg, bb, sr, sg, sb,
                separable(mode, br, sr), separable(mode, bg, sg), separable(mode, bb, sb),
            )
        }
    }

    /** Luminance used by the non-separable and masking modes: 0.3R + 0.59G + 0.11B. */
    fun lum(r: Float, g: Float, b: Float): Float = 0.3f * r + 0.59f * g + 0.11f * b

    private class GammaPixel {
        val src = FloatArray(4)
        val dst = FloatArray(4)
    }

    private val gammaPixel = ThreadLocal.withInitial { GammaPixel() }

    /** sRGB → linear light of the premultiplied pixel at [o] of [c], in place; alpha is unchanged. */
    fun premulToLinear(c: FloatArray, o: Int = 0) {
        val a = c[o + 3]
        if (a <= EPS) return
        val inv = 1f / a
        for (k in 0..2) {
            val u = (c[o + k] * inv).coerceIn(0f, 1f)
            c[o + k] = srgbToLinear(u) * a
        }
    }

    /** Inverse of [premulToLinear]: linear light → sRGB of the premultiplied pixel at [o] of [c], in place. */
    fun premulToSrgb(c: FloatArray, o: Int = 0) {
        val a = c[o + 3]
        if (a <= EPS) return
        val inv = 1f / a
        for (k in 0..2) {
            val u = (c[o + k] * inv).coerceIn(0f, 1f)
            c[o + k] = linearToSrgb(u) * a
        }
    }

    /** sRGB transfer function decode of [v] in 0..1 (IEC 61966-2-1). */
    fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    /** sRGB transfer function encode of [v] in 0..1 (IEC 61966-2-1). */
    fun linearToSrgb(v: Float): Float =
        if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f

    /**
     * `dst = lerp(dst, src, t)` of two premultiplied pixels; with [gamma] the interpolation happens in
     * linear light, like [blendPixel] (used by passthrough groups with opacity or a mask).
     */
    fun lerpPixel(dst: FloatArray, dOff: Int, src: FloatArray, sOff: Int, t: Float, gamma: Boolean) {
        if (t <= 0f) return
        if (!gamma) {
            for (k in 0..3) dst[dOff + k] += (src[sOff + k] - dst[dOff + k]) * t
            return
        }
        val g = gammaPixel.get()
        System.arraycopy(src, sOff, g.src, 0, 4)
        System.arraycopy(dst, dOff, g.dst, 0, 4)
        premulToLinear(g.src, 0)
        premulToLinear(g.dst, 0)
        for (k in 0..3) g.dst[k] += (g.src[k] - g.dst[k]) * t
        premulToSrgb(g.dst, 0)
        for (k in 0..3) dst[dOff + k] = g.dst[k]
    }

    /** W3C premultiplied source-over with blend result (xr, xg, xb). */
    private fun composeW3c(
        dst: FloatArray, dOff: Int, aB: Float, aS: Float,
        br: Float, bg: Float, bb: Float, sr: Float, sg: Float, sb: Float,
        xr: Float, xg: Float, xb: Float,
    ) {
        val s1 = aS * (1f - aB)
        val sb2 = aS * aB
        val b1 = (1f - aS) * aB
        dst[dOff] = s1 * sr + sb2 * xr + b1 * br
        dst[dOff + 1] = s1 * sg + sb2 * xg + b1 * bg
        dst[dOff + 2] = s1 * sb + sb2 * xb + b1 * bb
        dst[dOff + 3] = aS + aB * (1f - aS)
    }

    /** Separable blend function B(cb, cs) on unpremultiplied channels. */
    fun separable(mode: BlendMode, cb: Float, cs: Float): Float = when (mode) {
        BlendMode.NORMAL, BlendMode.PASSTHROUGH -> cs
        BlendMode.DARKEN -> min(cb, cs)
        BlendMode.MULTIPLY -> cb * cs
        BlendMode.COLOR_BURN -> colorBurn(cb, cs)
        BlendMode.LINEAR_BURN -> max(0f, cb + cs - 1f)
        BlendMode.LIGHTEN -> max(cb, cs)
        BlendMode.SCREEN -> screen(cb, cs)
        BlendMode.COLOR_DODGE -> colorDodge(cb, cs)
        BlendMode.LINEAR_DODGE, BlendMode.ADD -> min(1f, cb + cs)
        BlendMode.OVERLAY -> hardLight(cs = cb, cb = cs)
        BlendMode.SOFT_LIGHT -> softLight(cb, cs)
        BlendMode.HARD_LIGHT -> hardLight(cb, cs)
        BlendMode.VIVID_LIGHT -> vividLight(cb, cs)
        BlendMode.LINEAR_LIGHT -> (cb + 2f * cs - 1f).coerceIn(0f, 1f)
        BlendMode.PIN_LIGHT -> if (cs <= 0.5f) min(cb, 2f * cs) else max(cb, 2f * cs - 1f)
        BlendMode.HARD_MIX -> if (vividLight(cb, cs) < 0.5f) 0f else 1f
        BlendMode.DIFFERENCE -> abs(cb - cs)
        BlendMode.EXCLUSION -> cb + cs - 2f * cb * cs
        BlendMode.NEGATIVE -> 1f - abs(1f - cb - cs)
        BlendMode.INVERT -> 1f - cb
        BlendMode.SUBTRACT -> max(0f, cb - cs)
        BlendMode.DIVIDE -> if (cs == 0f) 1f else min(1f, cb / cs)
        else -> cs
    }

    private fun screen(cb: Float, cs: Float) = cb + cs - cb * cs

    private fun colorBurn(cb: Float, cs: Float): Float = when {
        cb >= 1f -> 1f
        cs <= 0f -> 0f
        else -> 1f - min(1f, (1f - cb) / cs)
    }

    private fun colorDodge(cb: Float, cs: Float): Float = when {
        cb <= 0f -> 0f
        cs >= 1f -> 1f
        else -> min(1f, cb / (1f - cs))
    }

    private fun hardLight(cb: Float, cs: Float): Float =
        if (cs <= 0.5f) cb * 2f * cs else screen(cb, 2f * cs - 1f)

    private fun vividLight(cb: Float, cs: Float): Float =
        if (cs <= 0.5f) colorBurn(cb, 2f * cs) else colorDodge(cb, 2f * cs - 1f)

    private fun softLight(cb: Float, cs: Float): Float {
        if (cs <= 0.5f) return cb - (1f - 2f * cs) * cb * (1f - cb)
        val d = if (cb <= 0.25f) ((16f * cb - 12f) * cb + 4f) * cb else sqrt(cb)
        return cb + (2f * cs - 1f) * (d - cb)
    }

    /** Non-separable blend (hue, saturation, color, luminosity, darker/lighter color) into [out]. */
    fun nonSeparable(mode: BlendMode, cb: FloatArray, cs: FloatArray, out: FloatArray) {
        when (mode) {
            BlendMode.HUE -> {
                setSat(cs, sat(cb), out)
                setLum(out, lum(cb), out)
            }
            BlendMode.SATURATION -> {
                setSat(cb, sat(cs), out)
                setLum(out, lum(cb), out)
            }
            BlendMode.COLOR -> setLum(cs, lum(cb), out)
            BlendMode.LUMINOSITY -> setLum(cb, lum(cs), out)
            BlendMode.DARKER_COLOR -> (if (lum(cs) < lum(cb)) cs else cb).copyInto(out, 0, 0, 3)
            BlendMode.LIGHTER_COLOR -> (if (lum(cs) > lum(cb)) cs else cb).copyInto(out, 0, 0, 3)
            else -> cs.copyInto(out, 0, 0, 3)
        }
    }

    private fun lum(c: FloatArray) = lum(c[0], c[1], c[2])

    private fun sat(c: FloatArray) = max(c[0], max(c[1], c[2])) - min(c[0], min(c[1], c[2]))

    private fun setLum(c: FloatArray, l: Float, out: FloatArray) {
        val d = l - lum(c)
        out[0] = c[0] + d; out[1] = c[1] + d; out[2] = c[2] + d
        clipColor(out)
    }

    private fun clipColor(c: FloatArray) {
        val l = lum(c)
        val n = min(c[0], min(c[1], c[2]))
        val x = max(c[0], max(c[1], c[2]))
        if (n < 0f) {
            val k = l - n
            for (i in 0..2) c[i] = if (k > EPS) l + (c[i] - l) * l / k else l
        }
        if (x > 1f) {
            val k = x - l
            for (i in 0..2) c[i] = if (k > EPS) l + (c[i] - l) * (1f - l) / k else l
        }
    }

    private fun setSat(c: FloatArray, s: Float, out: FloatArray) {
        val r = c[0]; val g = c[1]; val b = c[2]
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val range = mx - mn
        if (range <= EPS) {
            out[0] = 0f; out[1] = 0f; out[2] = 0f
            return
        }
        val iMax = if (r >= g && r >= b) 0 else if (g >= b) 1 else 2
        val o1 = (iMax + 1) % 3
        val o2 = (iMax + 2) % 3
        val iMin = if (c[o1] <= c[o2]) o1 else o2
        val mid = 3 - iMax - iMin
        val midV = (c[mid] - mn) * s / range
        out[iMax] = s
        out[iMin] = 0f
        out[mid] = midV
    }
}
