package app.painter.core.guide

import app.painter.core.stroke.InputPoint
import app.painter.core.stroke.PointSink
import app.painter.core.stroke.PointStage
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lazy rope (spec G2.4): the brush tip `q` trails the pen `p` and moves only when `|p − q| > R`,
 * staying exactly R behind. `R = radiusDoc·(1 + elastic·min(v, 4)/4)`, v = pen speed in screen px/ms.
 * The tip does not catch up on pen-up. [radiusDoc] ≤ 0 passes points through.
 *
 * @param elastic 0..1.
 * @param screenToDoc converts document speed to screen speed for the elastic term.
 */
class LazyRopeStage(
    private val radiusDoc: Float,
    private val elastic: Float = 0f,
    private val screenToDoc: Float = 1f,
) : PointStage {
    private var qx = 0f
    private var qy = 0f
    private var last: InputPoint? = null

    /** Current tip, for the overlay (null outside a stroke). */
    val tip: InputPoint? get() = last?.let { it.copy(x = qx, y = qy) }

    /** Rope radius used for the latest point, document px. */
    var currentRadius: Float = radiusDoc
        private set

    override fun begin(p: InputPoint, out: PointSink) {
        qx = p.x; qy = p.y; last = p
        currentRadius = radiusDoc
        out.point(p)
    }

    override fun add(p: InputPoint, out: PointSink) {
        val prev = last ?: run { begin(p, out); return }
        if (radiusDoc <= 0f) {
            last = p; qx = p.x; qy = p.y; out.point(p); return
        }
        val dt = if (p.timeMs > prev.timeMs) (p.timeMs - prev.timeMs).toFloat() else DEFAULT_DT_MS
        val mx = p.x - prev.x; val my = p.y - prev.y
        val vScreen = sqrt(mx * mx + my * my) / screenToDoc / dt
        val r = radiusDoc * (1f + elastic.coerceIn(0f, 1f) * min(vScreen, 4f) / 4f)
        currentRadius = r
        last = p
        val dx = p.x - qx; val dy = p.y - qy
        val d = sqrt(dx * dx + dy * dy)
        if (d <= r) return
        val k = r / d
        qx = p.x - dx * k; qy = p.y - dy * k
        out.point(p.copy(x = qx, y = qy))
    }

    override fun end(out: PointSink) {
        last = null
    }

    override fun cancel() {
        last = null
    }

    private companion object {
        const val DEFAULT_DT_MS = 8f
    }
}
