package app.painter.core.guide

import app.painter.core.stroke.InputPoint
import app.painter.core.stroke.PointSink
import app.painter.core.stroke.PointStage
import kotlin.math.sqrt

/**
 * Hatching (spec G2.5): only forward strokes are drawn. Progress is `s` on the track locked by [snap]
 * (when it runs before this stage and has locked), otherwise the projection on the initial direction
 * `u0`. The forward sign is set by the first movement beyond [hysteresisDoc]. Going back more than
 * [hysteresisDoc] lifts the pen; going forward again by the same amount starts a new sub-stroke at the
 * turning point. Create one instance per stroke.
 */
class HatchingStage(
    private val snap: SnapStage? = null,
    private val hysteresisDoc: Float = DEFAULT_HYSTERESIS,
) : PointStage {
    private val held = ArrayList<InputPoint>()
    private var p0: InputPoint? = null
    private var s0 = 0f
    private var ux = 0f
    private var uy = 0f
    private var sign = 0f
    private var useTrack = false
    private var forward = true
    private var peak = 0f
    private var trough = 0f
    private var troughPoint: InputPoint? = null

    /** Number of forward sub-strokes started so far. */
    var subStrokes: Int = 0
        private set

    override fun begin(p: InputPoint, out: PointSink) {
        held.clear(); p0 = p; sign = 0f; forward = true; subStrokes = 0; troughPoint = null
        useTrack = trackS() != null
        s0 = trackS() ?: 0f
        held.add(p)
    }

    override fun add(p: InputPoint, out: PointSink) {
        val start = p0 ?: run { begin(p, out); return }
        if (sign == 0f) {
            held.add(p)
            if (!useTrack && trackS() != null) {
                useTrack = true; s0 = trackS()!!
            }
            val delta: Float
            if (useTrack) {
                delta = trackS()!! - s0
            } else {
                val dx = p.x - start.x; val dy = p.y - start.y
                val d = sqrt(dx * dx + dy * dy)
                if (d > hysteresisDoc) { ux = dx / d; uy = dy / d }
                delta = d
            }
            if (kotlin.math.abs(delta) > hysteresisDoc) {
                sign = if (delta > 0f) 1f else -1f
                subStrokes = 1
                for (q in held) out.point(q)
                peak = coord(p)
                held.clear()
            }
            return
        }
        val c = coord(p)
        if (forward) {
            if (c < peak - hysteresisDoc) {
                out.lift()
                forward = false; trough = c; troughPoint = p
            } else {
                if (c > peak) peak = c
                out.point(p)
            }
        } else {
            if (c < trough) { trough = c; troughPoint = p }
            if (c > trough + hysteresisDoc) {
                forward = true; peak = c; subStrokes++
                troughPoint?.let { out.point(it) }
                out.point(p)
            }
        }
    }

    override fun end(out: PointSink) {
        if (sign == 0f) for (q in held) out.point(q)
        held.clear(); p0 = null
    }

    override fun cancel() {
        held.clear(); p0 = null
    }

    private fun trackS(): Float? = snap?.takeIf { it.lockedTrack != null }?.lastS?.takeIf { !it.isNaN() }

    private fun coord(p: InputPoint): Float {
        val start = p0!!
        return if (useTrack) sign * ((trackS() ?: s0) - s0) else (p.x - start.x) * ux + (p.y - start.y) * uy
    }

    companion object {
        /** Reversal tolerance, document px. */
        const val DEFAULT_HYSTERESIS = 2f
    }
}
