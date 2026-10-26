package app.painter.core.guide

import app.painter.core.geom.Vec2
import app.painter.core.stroke.InputPoint
import app.painter.core.stroke.PointSink
import app.painter.core.stroke.PointStage
import kotlin.math.sqrt

/**
 * Snaps a stroke to one track chosen from all [guides] by its start direction (spec G1.3).
 *
 * Points are held back until the pen moves [LOCK_SCREEN_PX] screen px from the start; then the track
 * whose tangent at p0 is closest in angle to the movement is locked (ties within 1° go by
 * [SnapPriority]), the held points are projected and flushed, and later points are projected with the
 * previous `s` as hint. A stroke that ends before locking is passed through unchanged.
 * Create one instance per stroke.
 */
class SnapStage(private val guides: List<SnapGuide>, private val ctx: SnapContext) : PointStage {
    private val pending = ArrayList<InputPoint>()
    private var p0: InputPoint? = null
    private val proj = Projection()

    /** Candidate tracks of the current stroke (for the overlay before lock). */
    val candidates: List<SnapTrack> get() = _candidates
    private val _candidates = ArrayList<SnapTrack>()

    /** Track chosen for this stroke, null before lock or without candidates. */
    var lockedTrack: SnapTrack? = null
        private set

    /** `s` of the most recently emitted point on [lockedTrack] (NaN before lock). */
    var lastS: Float = Float.NaN
        private set

    override fun begin(p: InputPoint, out: PointSink) {
        reset()
        p0 = p
        val start = Vec2(p.x, p.y)
        for (g in guides) g.tracks(start, ctx, _candidates)
        if (_candidates.isEmpty()) out.point(p) else pending.add(p)
    }

    override fun add(p: InputPoint, out: PointSink) {
        val start = p0 ?: run { begin(p, out); return }
        val track = lockedTrack
        if (track != null) {
            emit(track, p, out); return
        }
        if (_candidates.isEmpty()) {
            out.point(p); return
        }
        pending.add(p)
        val dx = p.x - start.x; val dy = p.y - start.y
        val d = sqrt(dx * dx + dy * dy)
        if (d >= LOCK_SCREEN_PX * ctx.screenToDoc && d > 0f) {
            val t = choose(Vec2(dx / d, dy / d), Vec2(start.x, start.y))
            lockedTrack = t
            for (q in pending) emit(t, q, out)
            pending.clear()
        }
    }

    override fun end(out: PointSink) {
        if (lockedTrack == null) for (q in pending) out.point(q)
        pending.clear()
    }

    override fun cancel() = reset()

    private fun reset() {
        pending.clear(); _candidates.clear()
        lockedTrack = null; lastS = Float.NaN; p0 = null
    }

    private fun emit(t: SnapTrack, p: InputPoint, out: PointSink) {
        t.project(p.x, p.y, lastS, proj)
        lastS = proj.s
        out.point(p.copy(x = proj.x, y = proj.y))
    }

    private fun choose(u: Vec2, start: Vec2): SnapTrack {
        val angles = FloatArray(_candidates.size) { absAngleBetween(u, _candidates[it].tangentAt(start)) }
        val min = angles.min()
        var best = -1
        for (i in _candidates.indices) {
            if (angles[i] > min + TIE_RAD) continue
            if (best < 0 || _candidates[i].priority < _candidates[best].priority ||
                (_candidates[i].priority == _candidates[best].priority && angles[i] < angles[best])
            ) best = i
        }
        return _candidates[best]
    }

    companion object {
        /** Movement that locks the track, screen px. */
        const val LOCK_SCREEN_PX = 12f
        private const val TIE_RAD = (Math.PI / 180).toFloat()
    }
}
