package app.painter.core.stroke

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Dynamics curve on [0,1] → [0,1] defined by 2..N control points and interpolated with a
 * monotone cubic (Fritsch–Carlson) spline. The spline output is remapped to `min + (1 − min)·y`,
 * so [min] is the floor the target never drops below.
 *
 * Points must have strictly increasing x within [0,1]; inputs outside the covered range are
 * clamped to the end points.
 */
@Serializable
data class Curve(
    val points: List<Pair<Float, Float>>,
    val min: Float = 0f,
) {
    @Transient
    private val xs: FloatArray = FloatArray(points.size) { points[it].first }

    @Transient
    private val ys: FloatArray = FloatArray(points.size) { points[it].second }

    @Transient
    private val tangents: FloatArray = computeTangents(xs, ys)

    init {
        require(points.size >= 2) { "curve needs at least 2 points" }
        for (i in 1 until points.size) require(points[i].first > points[i - 1].first) { "x must increase" }
    }

    /** Evaluates the curve at [x] (clamped to [0,1]); result is in [min, 1]. */
    fun eval(x: Float): Float {
        val n = xs.size
        val y = when {
            x <= xs[0] -> ys[0]
            x >= xs[n - 1] -> ys[n - 1]
            else -> {
                var i = 0
                while (i < n - 2 && x > xs[i + 1]) i++
                val h = xs[i + 1] - xs[i]
                val t = (x - xs[i]) / h
                val t2 = t * t
                val t3 = t2 * t
                (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * tangents[i] +
                    (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * tangents[i + 1]
            }
        }
        val m = min.coerceIn(0f, 1f)
        return (m + (1f - m) * y.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    companion object {
        /** Identity curve y = x without a floor. */
        val LINEAR = Curve(listOf(0f to 0f, 1f to 1f))

        /** Linear curve with the given floor. */
        fun linear(min: Float) = Curve(listOf(0f to 0f, 1f to 1f), min)

        private fun computeTangents(xs: FloatArray, ys: FloatArray): FloatArray {
            val n = xs.size
            val m = FloatArray(n)
            if (n < 2) return m
            val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) * 0.5f
            for (i in 0 until n - 1) {
                if (d[i] == 0f) {
                    m[i] = 0f
                    m[i + 1] = 0f
                    continue
                }
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val tau = 3f / kotlin.math.sqrt(s)
                    m[i] = tau * a * d[i]
                    m[i + 1] = tau * b * d[i]
                }
            }
            return m
        }
    }
}

/**
 * M1 subset of the brush model (spec/brush-engine.md §2.1–2.2). Percent parameters are 0..1
 * except [smoothing] and [smoothingSpeed], which use the UI scale 0..100.
 *
 * @property size dab diameter in document pixels at full pressure.
 * @property sizeMin lower bound of the size slider.
 * @property sizeMax upper bound of the size slider.
 * @property opacity stroke opacity cap 0..1.
 * @property flow per-dab opacity 0..1.
 * @property spacing distance between dabs as a fraction of the current size.
 * @property hardness 0 = fully soft edge, 1 = hard edge.
 * @property pressureToSize pressure → size factor.
 * @property pressureToFlow pressure → flow factor; null disables the link.
 * @property smoothing real-time EMA stabilizer strength 0..100.
 * @property smoothingSpeed extra smoothing that grows with pen speed 0..100.
 * @property taperStart length of the start taper in pixels (0 = off).
 * @property taperEnd length of the end taper in pixels (0 = off).
 * @property buildup dabs accumulate directly in the layer without the opacity cap.
 */
@Serializable
data class BrushParams(
    val size: Float = 20f,
    val sizeMin: Float = 1f,
    val sizeMax: Float = 500f,
    val opacity: Float = 1f,
    val flow: Float = 1f,
    val spacing: Float = 0.10f,
    val hardness: Float = 0.8f,
    val pressureToSize: Curve = Curve.linear(0.2f),
    val pressureToFlow: Curve? = null,
    val smoothing: Float = 10f,
    val smoothingSpeed: Float = 0f,
    val taperStart: Float = 0f,
    val taperEnd: Float = 0f,
    val buildup: Boolean = false,
) {
    /** Dab diameter for [pressure] before tapering. */
    fun sizeAt(pressure: Float): Float = size.coerceAtLeast(0f) * pressureToSize.eval(pressure)

    /** Dab flow for [pressure]. */
    fun flowAt(pressure: Float): Float =
        flow.coerceIn(0f, 1f) * (pressureToFlow?.eval(pressure) ?: 1f)
}
