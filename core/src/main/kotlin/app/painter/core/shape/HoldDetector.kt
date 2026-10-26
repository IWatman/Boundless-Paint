package app.painter.core.shape

/**
 * Detects a press-and-hold during a stroke (spec G3.4). Moving more than [toleranceScreenPx] from the
 * anchor moves the anchor and restarts the timer. Fires once per stroke, [holdMs] after the last
 * anchor move. Feed every input point to [onPoint] and call [poll] on frame ticks.
 */
class HoldDetector(
    private val holdMs: Long = DEFAULT_HOLD_MS,
    private val screenToDoc: Float = 1f,
    private val toleranceScreenPx: Float = DEFAULT_TOLERANCE_SCREEN_PX,
) {
    private var ax = 0f
    private var ay = 0f
    private var anchorTime = 0L
    private var active = false

    /** True once the hold has fired for this stroke. */
    var fired: Boolean = false
        private set

    fun begin(x: Float, y: Float, timeMs: Long) {
        ax = x; ay = y; anchorTime = timeMs; active = true; fired = false
    }

    /** Feeds an input point; returns true when the hold fires at this point. */
    fun onPoint(x: Float, y: Float, timeMs: Long): Boolean {
        if (!active) { begin(x, y, timeMs); return false }
        val dx = x - ax; val dy = y - ay
        val tol = toleranceScreenPx * screenToDoc
        if (dx * dx + dy * dy > tol * tol) {
            ax = x; ay = y; anchorTime = timeMs
            return false
        }
        return poll(timeMs)
    }

    /** Checks the timer at [nowMs]; returns true when the hold fires now. */
    fun poll(nowMs: Long): Boolean {
        if (!active || fired) return false
        if (nowMs - anchorTime >= holdMs) {
            fired = true; return true
        }
        return false
    }

    fun end() {
        active = false
    }

    companion object {
        const val DEFAULT_HOLD_MS = 600L
        const val DEFAULT_TOLERANCE_SCREEN_PX = 6f

        /** Allowed hold time range, ms (Settings → Shapes). */
        val HOLD_RANGE_MS = 100L..5000L
    }
}
