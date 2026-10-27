package app.painter.core.stroke

import kotlin.math.sqrt

/**
 * Real-time EMA stabilizer (spec/GAPS.md «Stabilizer»).
 *
 * Position alpha is `α = 1 − 0.95·smoothing/100`, further divided by `1 + k·speed` where
 * `k = 0.5·smoothingSpeed/100` and speed is in px/ms. Pressure and tilt use a lighter
 * `0.5 + 0.5·α`. [end] flushes the filter toward the last raw point so the stroke reaches
 * the pen-up position.
 */
class Stabilizer(smoothing: Float, smoothingSpeed: Float) {
    private val baseAlpha = 1f - 0.95f * smoothing.coerceIn(0f, 100f) / 100f
    private val speedK = 0.5f * smoothingSpeed.coerceIn(0f, 100f) / 100f

    private var sx = 0f
    private var sy = 0f
    private var sp = 0f
    private var st = 0f
    private var last: InputPoint? = null
    private var lastAlpha = 1f

    /** Starts a stroke; the first point passes through unchanged. */
    fun begin(p: InputPoint): InputPoint {
        sx = p.x; sy = p.y; sp = p.pressure; st = p.tilt
        last = p
        lastAlpha = baseAlpha
        return p
    }

    /** Filters one raw point and returns the smoothed point. */
    fun add(p: InputPoint): InputPoint {
        val prev = last ?: return begin(p)
        val dx = p.x - prev.x
        val dy = p.y - prev.y
        val dt = if (p.timeMs > prev.timeMs) (p.timeMs - prev.timeMs).toFloat() else DEFAULT_DT_MS
        val speed = sqrt(dx * dx + dy * dy) / dt
        val a = baseAlpha / (1f + speedK * speed)
        val ap = 0.5f + 0.5f * a
        sx += a * (p.x - sx)
        sy += a * (p.y - sy)
        sp += ap * (p.pressure - sp)
        st += ap * (p.tilt - st)
        last = p
        lastAlpha = a
        return p.copy(x = sx, y = sy, pressure = sp, tilt = st)
    }

    /** Returns catch-up points that converge on the last raw point, ending exactly on it. */
    fun end(): List<InputPoint> {
        val raw = last ?: return emptyList()
        val out = ArrayList<InputPoint>()
        val a = lastAlpha.coerceAtLeast(FLUSH_MIN_ALPHA)
        var i = 0
        while (i < FLUSH_MAX_STEPS) {
            val dx = raw.x - sx
            val dy = raw.y - sy
            if (dx * dx + dy * dy < FLUSH_EPS * FLUSH_EPS) break
            sx += a * dx
            sy += a * dy
            sp += a * (raw.pressure - sp)
            st += a * (raw.tilt - st)
            out.add(raw.copy(x = sx, y = sy, pressure = sp, tilt = st))
            i++
        }
        sx = raw.x; sy = raw.y; sp = raw.pressure; st = raw.tilt
        out.add(raw)
        last = null
        return out
    }

    private companion object {
        const val DEFAULT_DT_MS = 8f
        const val FLUSH_MIN_ALPHA = 0.3f
        const val FLUSH_MAX_STEPS = 64
        const val FLUSH_EPS = 0.5f
    }
}
