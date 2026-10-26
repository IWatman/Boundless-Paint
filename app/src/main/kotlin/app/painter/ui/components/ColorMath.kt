package app.painter.ui.components

import androidx.compose.ui.graphics.Color
import app.painter.core.color.Rgba
import kotlin.math.abs
import kotlin.math.roundToInt

/** HSV color for UI controls: [h] 0..360, [s] and [v] 0..1. */
data class HsvUi(val h: Float, val s: Float, val v: Float)

fun Rgba.toColor(): Color = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), a.coerceIn(0f, 1f))

fun Rgba.toHsvUi(): HsvUi {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d <= 1e-6f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (max <= 1e-6f) 0f else d / max
    return HsvUi(h, s, max)
}

fun HsvUi.toRgba(alpha: Float = 1f): Rgba {
    val hh = ((h % 360f) + 360f) % 360f
    val c = v * s
    val x = c * (1f - abs((hh / 60f) % 2f - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        hh < 60f -> Triple(c, x, 0f)
        hh < 120f -> Triple(x, c, 0f)
        hh < 180f -> Triple(0f, c, x)
        hh < 240f -> Triple(0f, x, c)
        hh < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Rgba(r1 + m, g1 + m, b1 + m, alpha)
}

fun hueColor(h: Float): Color = HsvUi(h, 1f, 1f).toRgba().toColor()

fun Rgba.toHexString(): String {
    fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt().toString(16).padStart(2, '0')
    return (c(r) + c(g) + c(b)).uppercase()
}

/** Parses "RRGGBB" / "#RRGGBB" / "RGB". Alpha of the result is [alpha]. */
fun parseHexColor(text: String, alpha: Float = 1f): Rgba? {
    var s = text.trim().removePrefix("#")
    if (s.length == 3) s = s.map { "$it$it" }.joinToString("")
    if (s.length != 6) return null
    val v = s.toIntOrNull(16) ?: return null
    return Rgba(
        ((v shr 16) and 0xFF) / 255f,
        ((v shr 8) and 0xFF) / 255f,
        (v and 0xFF) / 255f,
        alpha,
    )
}

/** Approximate equality of colors at 8-bit precision. */
fun Rgba.sameAs(other: Rgba): Boolean =
    abs(r - other.r) < 0.002f && abs(g - other.g) < 0.002f &&
        abs(b - other.b) < 0.002f && abs(a - other.a) < 0.002f

/** Perceived luminance, used to pick contrasting outlines. */
fun Rgba.luma(): Float = 0.299f * r + 0.587f * g + 0.114f * b
