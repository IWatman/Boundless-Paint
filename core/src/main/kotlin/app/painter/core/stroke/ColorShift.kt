package app.painter.core.stroke

import app.painter.core.color.ColorConv
import app.painter.core.color.Rgba
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Colour stamp shift of one stroke (spec/tools/m2-brush.md §1.13): maps a colour of the RGBA head into the
 * colour space of the stroke.
 *
 * The shift is resolved once per stroke from [base] (head `base_color`) and [cur] (the brush colour):
 * `h' = h + (h_cur − h_base)`, `S' = min(S, S·S_cur / max(S_base, 0.01))` and `V'` likewise [MIN_COMPONENT].
 * [colorize] blends the shifted colour with the original pixel, so 0 gives the head colours back untouched.
 *
 * The wrapped hue of every 1° bucket is cached ([hueCache]); a head pixel then costs one cache lookup plus its
 * fractional degree, which keeps the per-pixel cost of the kernel flat.
 *
 * @property identity true when the shift leaves every head colour unchanged (equal base and current colours);
 *   the kernel then uses the head colour directly, without the HSB round trip.
 */
class ColorShift(base: Rgba?, cur: Rgba, colorize: Float = 1f) {
    private val baseHsb = ColorConv.toHsb(base ?: cur)
    private val curHsb = ColorConv.toHsb(cur)

    /** Hue offset `h_cur − h_base`, degrees (not wrapped: [shiftedHue] wraps per bucket). */
    private val hueShift = curHsb.h - baseHsb.h

    /** `S_cur / max(S_base, 0.01)`; saturation is only ever lowered by the shift. */
    private val satGain = curHsb.s / max(baseHsb.s, MIN_COMPONENT)

    /** `V_cur / max(V_base, 0.01)`; brightness is only ever lowered by the shift. */
    private val briGain = curHsb.v / max(baseHsb.v, MIN_COMPONENT)

    /** Share of the shifted colour in the result (spec §1.13 `colorize`). */
    private val mix = colorize.coerceIn(0f, 1f)

    /** Shifted hue of every 1° bucket; `NaN` until the bucket is first used. */
    private val hueCache = FloatArray(360) { Float.NaN }

    private val tmp = FloatArray(3)

    /** True when the shift is a no-op, so the head colour can be used as it is. */
    val identity: Boolean = hueShift == 0f && satGain >= 1f && briGain >= 1f

    /**
     * Writes the shifted straight colour of head pixel ([r], [g], [b]) into [out] (three floats, 0..1).
     * With [identity] the pixel is copied through [colorize] unchanged.
     */
    fun shift(r: Float, g: Float, b: Float, out: FloatArray) {
        if (identity) {
            blend(r, g, b, r, g, b, out)
            return
        }
        val v = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = v - mn
        val s = if (v > 0f) d / v else 0f
        hsbToRgb(shiftedHue(hueOf(r, g, b, v, d)), min(s, s * satGain), min(v, v * briGain), tmp)
        blend(r, g, b, tmp[0], tmp[1], tmp[2], out)
    }

    /** `lerp(original, shifted, colorize)` of one channel triple. */
    private fun blend(r: Float, g: Float, b: Float, sr: Float, sg: Float, sb: Float, out: FloatArray) {
        val m = mix
        out[0] = (r + (sr - r) * m).coerceIn(0f, 1f)
        out[1] = (g + (sg - g) * m).coerceIn(0f, 1f)
        out[2] = (b + (sb - b) * m).coerceIn(0f, 1f)
    }

    /** `h + (h_cur − h_base)` of hue [h], degrees, wrapped into `[0, 360)`; cached per 1° bucket. */
    private fun shiftedHue(h: Float): Float {
        val bucket = h.toInt().coerceIn(0, 359)
        var c = hueCache[bucket]
        if (c.isNaN()) {
            c = wrap(bucket + hueShift)
            hueCache[bucket] = c
        }
        // The fractional degree of the pixel rides on the cached bucket value, so no precision is lost.
        return wrap(c + (h - bucket))
    }

    private fun wrap(h: Float): Float {
        var v = h % 360f
        if (v < 0f) v += 360f
        if (v >= 360f) v -= 360f
        return v
    }

    /** Hue of a straight colour 0..360 (0 for grays), from the precomputed max [v] and delta [d]. */
    private fun hueOf(r: Float, g: Float, b: Float, v: Float, d: Float): Float {
        if (d <= 0f) return 0f
        val h = when {
            v == r -> 60f * (((g - b) / d) % 6f)
            v == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return wrap(h)
    }

    /** HSB → straight sRGB (`ColorConv.fromHsb` without the allocation). */
    private fun hsbToRgb(h: Float, s: Float, v: Float, out: FloatArray) {
        val c = v * s
        val hp = h / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val m = v - c
        when (hp.toInt()) {
            0 -> { out[0] = c; out[1] = x; out[2] = 0f }
            1 -> { out[0] = x; out[1] = c; out[2] = 0f }
            2 -> { out[0] = 0f; out[1] = c; out[2] = x }
            3 -> { out[0] = 0f; out[1] = x; out[2] = c }
            4 -> { out[0] = x; out[1] = 0f; out[2] = c }
            else -> { out[0] = c; out[1] = 0f; out[2] = x }
        }
        out[0] += m
        out[1] += m
        out[2] += m
    }

    private companion object {
        /** Floor of the base saturation and brightness in `S_cur / max(S_base, 0.01)` (spec §1.13). */
        const val MIN_COMPONENT = 0.01f
    }
}
