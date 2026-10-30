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

    /** "Reset to auto": the full range, [auto] on and the peak samples of the old range dropped. */
    fun resetToAuto() {
        auto = true
        minPressure = 0f
        maxPressure = 1f
        peaks.clear()
        observed = 0
    }

    /** Manual calibration from the wizard: fixes the range and turns auto off. */
    fun setManual(min: Float, max: Float) {
        minPressure = min.coerceIn(0f, 1f)
        maxPressure = max.coerceIn(minPressure + 1e-3f, 1f)
        auto = false
    }

    /**
     * Persistable state (spec/tools/m2-brush.md §4.4): the range, the mode and the peak samples the
     * automatic range is computed from. The app keeps one per input device in `files/settings/input.json`.
     */
    data class Snapshot(
        val maxPressure: Float,
        val minPressure: Float,
        val auto: Boolean,
        val peaks: List<Float>,
        val weakThreshold: Float,
        val pressureCurve: Curve,
    )

    /** Copy of the current state, see [restore]. */
    fun snapshot(): Snapshot =
        Snapshot(maxPressure, minPressure, auto, peaks.toList(), weakThreshold, pressureCurve)

    /** Applies [Snapshot]; the downward EMA restarts as if the restored peaks were just observed. */
    fun restore(s: Snapshot) {
        pressureCurve = s.pressureCurve
        minPressure = s.minPressure.coerceIn(0f, MAX_MIN)
        maxPressure = s.maxPressure.coerceIn(minPressure + 1e-3f, 1f)
        auto = s.auto
        weakThreshold = s.weakThreshold.coerceIn(0f, 1f)
        peaks.clear()
        for (p in s.peaks.takeLast(HISTORY)) if (p > 0f) peaks.addLast(p.coerceIn(0f, 1f))
        observed = peaks.size
    }

    private companion object {
        const val HISTORY = 20
        const val MIN_MAX = 0.4f
        const val MAX_MIN = 0.999f
    }
}
