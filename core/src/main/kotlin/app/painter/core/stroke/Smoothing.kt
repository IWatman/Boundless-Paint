package app.painter.core.stroke

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Brush-level lazy rope ([BrushParams.lazyRadius], spec/brush-engine.md §4): the tip `q` follows the pen `p`
 * only when `|p − q| > R`, staying exactly R behind; it does not catch up at pen-up.
 * `R = radius/zoom · (1 + elastic·min(v, 4)/4)`, v = pen speed in screen px/ms.
 * (The guide-tool rope of spec/tools/m2-guides-shapes.md is a separate stage owned by `core/guide`.)
 */
class BrushLazyRope(private val radiusScreen: Float, private val elastic: Float, private val zoom: Float) : PointStage {
    private var qx = 0f
    private var qy = 0f
    private var last: InputPoint? = null

    override fun begin(p: InputPoint, out: PointSink) {
        qx = p.x; qy = p.y
        last = p
        out.point(p)
    }

    override fun add(p: InputPoint, out: PointSink) {
        val prev = last ?: return begin(p, out)
        val dt = (p.timeMs - prev.timeMs).toFloat()
        val v = if (dt > 0f) sqrt((p.x - prev.x).let { it * it } + (p.y - prev.y).let { it * it }) * zoom / dt else 0f
        last = p
        val r = radiusScreen / zoom * (1f + elastic.coerceIn(0f, 1f) * min(v, 4f) / 4f)
        val dx = p.x - qx
        val dy = p.y - qy
        val d = sqrt(dx * dx + dy * dy)
        if (d <= r) return
        val k = (d - r) / d
        qx += dx * k
        qy += dy * k
        out.point(p.copy(x = qx, y = qy))
    }

    override fun end(out: PointSink) {
        last = null
    }
}

/**
 * Pen-up re-fit (spec/GAPS.md «Стабилизатор» → after): Gaussian smoothing of positions over arc length with
 * σ = 0.5·[amount] px; the first and last points stay fixed.
 */
object AfterSmoothing {
    fun smooth(points: List<InputPoint>, amount: Float): List<InputPoint> {
        if (amount <= 0f || points.size < 3) return points
        val sigma = 0.5f * amount
        val n = points.size
        val s = FloatArray(n)
        for (i in 1 until n) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            s[i] = s[i - 1] + sqrt(dx * dx + dy * dy)
        }
        val reach = 3f * sigma
        val inv = 1f / (2f * sigma * sigma)
        val out = ArrayList<InputPoint>(n)
        out.add(points[0])
        var lo = 0
        for (i in 1 until n - 1) {
            while (s[i] - s[lo] > reach) lo++
            var sw = 0.0
            var sx = 0.0
            var sy = 0.0
            var j = lo
            while (j < n && s[j] - s[i] <= reach) {
                val d = s[j] - s[i]
                val wt = exp((-d * d * inv).toDouble())
                sw += wt; sx += wt * points[j].x; sy += wt * points[j].y
                j++
            }
            out.add(points[i].copy(x = (sx / sw).toFloat(), y = (sy / sw).toFloat()))
        }
        out.add(points[n - 1])
        return out
    }
}
