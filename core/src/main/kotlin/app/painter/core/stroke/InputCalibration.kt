package app.painter.core.stroke

/**
 * Global stylus pressure calibration (spec/tools/m2-brush.md §4.3), stored per input device by the app.
 *
 * `p_cal = curve(sat((raw − minPressure) / (maxPressure − minPressure)))`. With [auto] on, [maxPressure]
 * follows the 95th percentile of the last [HISTORY] stroke peaks, clamped to [0.4, 1]: it rises at once and
 * falls with an EMA whose weight is `max(0.1, 1/n)` for the n-th observed stroke. The warm-up weight is a
 * decision of this implementation: the spec's plain 0.1 cannot reach full range in 3–5 strokes from the
 * 1.0 start.
 *
 * Pressures below [weakThreshold] (0 = off, 0.02 for "weak sensitivity") draw no dab.
 */
class InputCalibration(
    var pressureCurve: Curve = Curve.LINEAR,
    var maxPressure: Float = 1f,
    var minPressure: Float = 0f,
    var auto: Boolean = true,
    var weakThreshold: Float = 0f,
) {
    private val peaks = ArrayDeque<Float>()
    private var observed = 0

    /** Calibrated pressure 0..1 for the raw device pressure [raw]. */
    fun pressure(raw: Float): Float {
        val span = (maxPressure - minPressure).coerceAtLeast(1e-3f)
        return pressureCurve.eval(((raw - minPressure) / span).coerceIn(0f, 1f))
    }

    /** Feeds the raw peak pressure of a finished stylus stroke. */
    fun observeStrokePeak(p: Float) {
        if (!auto || p <= 0f) return
        peaks.addLast(p)
        while (peaks.size > HISTORY) peaks.removeFirst()
        observed++
        val sorted = peaks.sorted()
        val idx = ((sorted.size - 1) * 0.95f + 0.5f).toInt().coerceIn(0, sorted.size - 1)
        val target = sorted[idx].coerceIn(MIN_MAX, 1f)
        maxPressure = if (target >= maxPressure) {
            target
        } else {
            val w = maxOf(0.1f, 1f / observed)
            maxPressure + (target - maxPressure) * w
        }
    }

    /** Manual calibration from the wizard: fixes the range and turns auto off. */
    fun setManual(min: Float, max: Float) {
        minPressure = min.coerceIn(0f, 1f)
        maxPressure = max.coerceIn(minPressure + 1e-3f, 1f)
        auto = false
    }

    private companion object {
        const val HISTORY = 20
        const val MIN_MAX = 0.4f
    }
}
