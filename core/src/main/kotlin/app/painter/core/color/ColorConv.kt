package app.painter.core.color

import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Hue in degrees 0..360, saturation and brightness 0..1. */
data class Hsb(val h: Float, val s: Float, val v: Float)

/** CIE L*a*b* (D65 white point); L is 0..100. */
data class Lab(val l: Float, val a: Float, val b: Float)

/** Conversions between [Rgba] and the color models used by the pickers. */
object ColorConv {
    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883
    private const val LAB_E = 216.0 / 24389.0
    private const val LAB_K = 24389.0 / 27.0

    /** Converts straight sRGB to HSB; hue is 0 for grays. */
    fun toHsb(c: Rgba): Hsb {
        val mx = max(c.r, max(c.g, c.b))
        val mn = min(c.r, min(c.g, c.b))
        val d = mx - mn
        val s = if (mx > 0f) d / mx else 0f
        var h = when {
            d <= 0f -> 0f
            mx == c.r -> 60f * (((c.g - c.b) / d) % 6f)
            mx == c.g -> 60f * ((c.b - c.r) / d + 2f)
            else -> 60f * ((c.r - c.g) / d + 4f)
        }
        if (h < 0f) h += 360f
        if (h >= 360f) h -= 360f
        return Hsb(h, s, mx)
    }

    /** Converts HSB to straight sRGB with the given [alpha]. */
    fun fromHsb(hsb: Hsb, alpha: Float = 1f): Rgba {
        val v = hsb.v.coerceIn(0f, 1f)
        val s = hsb.s.coerceIn(0f, 1f)
        var h = hsb.h % 360f
        if (h < 0f) h += 360f
        val c = v * s
        val hp = h / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val m = v - c
        val (r, g, b) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Rgba(r + m, g + m, b + m, alpha)
    }

    /** Formats as `#RRGGBB`, or `#RRGGBBAA` when [withAlpha]. */
    fun toHex(c: Rgba, withAlpha: Boolean): String {
        val sb = StringBuilder(9).append('#')
        appendByte(sb, c.r); appendByte(sb, c.g); appendByte(sb, c.b)
        if (withAlpha) appendByte(sb, c.a)
        return sb.toString()
    }

    private fun appendByte(sb: StringBuilder, v: Float) {
        val n = (v.coerceIn(0f, 1f) * 255f).roundToInt()
        val digits = "0123456789ABCDEF"
        sb.append(digits[n shr 4]).append(digits[n and 15])
    }

    /** Parses `RGB`, `RRGGBB` or `RRGGBBAA`, with or without a leading `#`; returns null if malformed. */
    fun parseHex(text: String): Rgba? {
        val s = text.trim().removePrefix("#")
        if (s.any { Character.digit(it, 16) < 0 }) return null
        return when (s.length) {
            3 -> {
                val d = IntArray(3) { Character.digit(s[it], 16) * 17 }
                Rgba(d[0] / 255f, d[1] / 255f, d[2] / 255f)
            }
            6, 8 -> {
                val d = IntArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }
                Rgba(d[0] / 255f, d[1] / 255f, d[2] / 255f, if (d.size == 4) d[3] / 255f else 1f)
            }
            else -> null
        }
    }

    /** Converts sRGB to CIE Lab (D65) via linear RGB and XYZ. */
    fun toLab(c: Rgba): Lab {
        val r = toLinear(c.r.toDouble())
        val g = toLinear(c.g.toDouble())
        val b = toLinear(c.b.toDouble())
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / YN
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN
        val fx = labF(x)
        val fy = labF(y)
        val fz = labF(z)
        return Lab((116.0 * fy - 16.0).toFloat(), (500.0 * (fx - fy)).toFloat(), (200.0 * (fy - fz)).toFloat())
    }

    /** Converts CIE Lab (D65) to sRGB, clamping out-of-gamut channels to 0..1. */
    fun fromLab(lab: Lab, alpha: Float = 1f): Rgba {
        val fy = (lab.l + 16.0) / 116.0
        val fx = fy + lab.a / 500.0
        val fz = fy - lab.b / 200.0
        val x = labFInv(fx) * XN
        val y = labFInv(fy) * YN
        val z = labFInv(fz) * ZN
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val b = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        return Rgba(fromLinear(r), fromLinear(g), fromLinear(b), alpha)
    }

    /** Perceptual luminance 0.3R + 0.59G + 0.11B of the straight color. */
    fun luminance(c: Rgba): Float = 0.3f * c.r + 0.59f * c.g + 0.11f * c.b

    private fun toLinear(v: Double): Double =
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun fromLinear(v: Double): Float {
        val c = v.coerceIn(0.0, 1.0)
        val e = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return e.coerceIn(0.0, 1.0).toFloat()
    }

    private fun labF(t: Double): Double = if (t > LAB_E) cbrt(t) else (LAB_K * t + 16.0) / 116.0

    private fun labFInv(f: Double): Double {
        val f3 = f * f * f
        return if (f3 > LAB_E) f3 else (116.0 * f - 16.0) / LAB_K
    }
}
