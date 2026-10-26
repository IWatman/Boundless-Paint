package app.painter.core.adjust

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The 1D lookup table shared by the CPU ([AdjustMath]) and the GPU (a 1024×1 RGBA16F texture): the single source
 * of parity for LUT-based corrections.
 *
 * Layout: [SIZE] entries × 4 interleaved channels. Entry `i` maps input `i / (SIZE - 1)`.
 * - per-channel kinds (brightness_contrast, exposure, levels, curves, invert rgb, posterize): channels 0..2 are
 *   the full R, G, B mappings (per-channel then master), channel 3 the alpha mapping (curves `a`, else identity);
 * - gradient_map: the straight RGBA of the gradient at `t = Lum`, reverse already applied.
 *
 * Sampling is linear between entries ([sample]); on the GPU use `GL_LINEAR` with `u = (x·(SIZE−1) + 0.5) / SIZE`.
 */
object AdjustLut {
    const val SIZE = 1024

    /** The LUT of [p], or null for kinds computed per pixel from uniforms. */
    fun build(p: AdjustParams): FloatArray? = when (p) {
        is BrightnessContrastParams -> perChannel { brightnessContrast(p, it) }
        is ExposureParams -> perChannel { exposure(p, it) }
        is LevelsParams -> {
            val c = arrayOf(p.r, p.g, p.b)
            table { ch, x -> if (ch == 3) x else levels(p.rgb, levels(c[ch], x)) }
        }
        is CurvesParams -> {
            val master = MonotoneCurve(p.rgb)
            val c = arrayOf(MonotoneCurve(p.r), MonotoneCurve(p.g), MonotoneCurve(p.b))
            val alpha = MonotoneCurve(p.a)
            table { ch, x -> if (ch == 3) alpha.eval(x) else master.eval(c[ch].eval(x)) }
        }
        is GradientMapParams -> gradient(p)
        is InvertParams -> if (p.variant == InvertParams.LUMINANCE) null else perChannel { 1.0 - it }
        is PosterizeParams -> {
            val n = p.levels.coerceIn(2, 64) - 1
            perChannel { (it * n).roundToInt().toDouble() / n }
        }
        else -> null
    }

    /** Linearly interpolated value of channel [ch] at input [x] (clamped to 0..1). */
    fun sample(lut: FloatArray, ch: Int, x: Float): Float {
        val pos = x.coerceIn(0f, 1f) * (SIZE - 1)
        val i = min(pos.toInt(), SIZE - 2)
        val f = pos - i
        val a = lut[i * 4 + ch]
        return a + (lut[(i + 1) * 4 + ch] - a) * f
    }

    private inline fun table(f: (ch: Int, x: Double) -> Double): FloatArray {
        val out = FloatArray(SIZE * 4)
        for (i in 0 until SIZE) {
            val x = i.toDouble() / (SIZE - 1)
            for (ch in 0..3) out[i * 4 + ch] = f(ch, x).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    private inline fun perChannel(f: (Double) -> Double): FloatArray = table { ch, x -> if (ch == 3) x else f(x) }

    private fun brightnessContrast(p: BrightnessContrastParams, x: Double): Double {
        val b = p.brightness.coerceIn(-100f, 100f) / 200.0
        val y = if (b >= 0) x + (1 - x) * b else x * (1 + b)
        val c = p.contrast.coerceIn(-100f, 100f).toDouble()
        val k = if (c >= 0) 1 + 3 * c / 100 else 1 + c / 100
        return (y - 0.5) * k + 0.5
    }

    private fun exposure(p: ExposureParams, x: Double): Double {
        val lin = srgbToLinear(x) * 2.0.pow(p.exposure.coerceIn(-5f, 5f).toDouble()) + p.offset.coerceIn(-0.5f, 0.5f)
        val s = linearToSrgb(lin.coerceIn(0.0, 1.0))
        return s.pow(1.0 / p.gamma.coerceIn(0.2f, 5f))
    }

    internal fun levels(l: LevelsChannel, x: Double): Double {
        val ib = l.inBlack.coerceIn(0f, 253f) / 255.0
        val iw = max(l.inWhite.coerceIn(2f, 255f).toDouble(), l.inBlack + 2.0).coerceAtMost(255.0) / 255.0
        val v = ((x - ib) / (iw - ib)).coerceIn(0.0, 1.0).pow(1.0 / l.gamma.coerceIn(0.1f, 9.99f))
        val ob = l.outBlack.coerceIn(0f, 255f) / 255.0
        val ow = l.outWhite.coerceIn(0f, 255f) / 255.0
        return ob + (ow - ob) * v
    }

    private fun gradient(p: GradientMapParams): FloatArray {
        val stops = p.stops.take(MAX_GRADIENT_STOPS).sortedBy { it.pos }
        val out = FloatArray(SIZE * 4)
        if (stops.isEmpty()) return out
        for (i in 0 until SIZE) {
            var t = i.toFloat() / (SIZE - 1)
            if (p.reverse) t = 1f - t
            val hi = stops.indexOfFirst { it.pos >= t }
            val c = when {
                hi < 0 -> stops.last().color.let { floatArrayOf(it.r, it.g, it.b, it.a) }
                hi == 0 -> stops.first().color.let { floatArrayOf(it.r, it.g, it.b, it.a) }
                else -> {
                    val a = stops[hi - 1]
                    val b = stops[hi]
                    val span = b.pos - a.pos
                    val f = if (span > 0f) (t - a.pos) / span else 1f
                    floatArrayOf(
                        a.color.r + (b.color.r - a.color.r) * f,
                        a.color.g + (b.color.g - a.color.g) * f,
                        a.color.b + (b.color.b - a.color.b) * f,
                        a.color.a + (b.color.a - a.color.a) * f,
                    )
                }
            }
            for (ch in 0..3) out[i * 4 + ch] = c[ch].coerceIn(0f, 1f)
        }
        return out
    }

    internal fun srgbToLinear(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    internal fun linearToSrgb(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055
}

/**
 * Monotone piecewise-cubic curve through [points] (Fritsch–Carlson): never overshoots between points. Points are
 * sorted by x; duplicates keep the last. Outside the first/last x the curve holds the end values.
 */
class MonotoneCurve(points: List<Pt>) {
    private val xs: DoubleArray
    private val ys: DoubleArray
    private val ms: DoubleArray

    init {
        // Untrusted files may carry huge lists: cap to the editor limit before any O(n) work per sample.
        val sorted = points.take(MAX_POINTS).sortedBy { it.x }
        val px = ArrayList<Double>()
        val py = ArrayList<Double>()
        for (p in sorted) {
            val x = p.x.coerceIn(0f, 1f).toDouble()
            val y = p.y.coerceIn(0f, 1f).toDouble()
            if (px.isNotEmpty() && x - px.last() < 1e-9) { py[py.lastIndex] = y } else { px.add(x); py.add(y) }
        }
        if (px.isEmpty()) { px.add(0.0); py.add(0.0); px.add(1.0); py.add(1.0) }
        xs = px.toDoubleArray()
        ys = py.toDoubleArray()
        val n = xs.size
        ms = DoubleArray(n)
        if (n >= 2) {
            val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
            ms[0] = d[0]
            ms[n - 1] = d[n - 2]
            for (k in 1 until n - 1) ms[k] = if (d[k - 1] * d[k] <= 0) 0.0 else (d[k - 1] + d[k]) / 2
            for (k in 0 until n - 1) {
                if (d[k] == 0.0) { ms[k] = 0.0; ms[k + 1] = 0.0; continue }
                val a = ms[k] / d[k]
                val b = ms[k + 1] / d[k]
                val s = a * a + b * b
                if (s > 9) {
                    val t = 3 / sqrt(s)
                    ms[k] = t * a * d[k]
                    ms[k + 1] = t * b * d[k]
                }
            }
        }
    }

    /** Curve value at [x]. */
    fun eval(x: Double): Double {
        val n = xs.size
        if (n == 1 || x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        var k = 0
        while (k < n - 2 && x > xs[k + 1]) k++
        val h = xs[k + 1] - xs[k]
        val t = (x - xs[k]) / h
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * ms[k] +
            (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * ms[k + 1]
    }
}

/** Editor limits, also enforced on load (spec m2-layers-adjust L5/L6, m2-select-transform-fill §4.4). */
const val MAX_POINTS = 16
const val MAX_GRADIENT_STOPS = 32
