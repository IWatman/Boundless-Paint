package app.painter.core.guide

import app.painter.core.geom.Vec2
import app.painter.core.shape.ArcPolyline
import app.painter.core.shape.dot
import app.painter.core.shape.minus
import app.painter.core.shape.normalized
import app.painter.core.shape.plus
import app.painter.core.shape.times
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** Mutable projection result, reused to avoid allocation per point. */
class Projection {
    var x = 0f
    var y = 0f

    /** Arc-length parameter on the track (continuous along a stroke when hints are used). */
    var s = 0f
}

/** Tie-break order when two tracks match the start direction equally (spec G1.3): lower wins. */
enum class SnapPriority { PEN, ELLIPSE, LINES, PERSPECTIVE }

/** A curve a stroke sticks to. `s` is an arc-length parameter (used by Pen and Hatching). */
interface SnapTrack {
    val priority: SnapPriority

    /** Unit tangent of the track near [p0]. */
    fun tangentAt(p0: Vec2): Vec2

    /** Projects ([x], [y]) on the track; [hintS] = previous `s`, or NaN for a global search. */
    fun project(x: Float, y: Float, hintS: Float, out: Projection)
}

/** Source of track families (a guide or a perspective grid). */
fun interface SnapGuide {
    /** Adds candidates for a stroke started at [p0]. Adding none = the guide sits this stroke out. */
    fun tracks(p0: Vec2, ctx: SnapContext, out: MutableList<SnapTrack>)
}

/** Per-stroke view facts: [screenToDoc] = 1/zoom, [brushSize] in document px. */
data class SnapContext(val screenToDoc: Float = 1f, val brushSize: Float = 0f)

/** Infinite straight track through [origin] along unit [dir]; `s` = signed distance along [dir]. */
class LineTrack(val origin: Vec2, dir: Vec2, override val priority: SnapPriority) : SnapTrack {
    val dir: Vec2 = dir.normalized()

    override fun tangentAt(p0: Vec2) = dir

    override fun project(x: Float, y: Float, hintS: Float, out: Projection) {
        val t = (x - origin.x) * dir.x + (y - origin.y) * dir.y
        out.x = origin.x + dir.x * t
        out.y = origin.y + dir.y * t
        out.s = t
    }
}

/**
 * Ellipse with centre [c], semi-axis [r] along [angleRad] and `r·ratio` across it. `s = r·θ` where θ is
 * the parametric angle, unwrapped next to the hint so it never jumps by a full turn.
 */
class EllipseTrack(
    val c: Vec2,
    val r: Float,
    val ratio: Float,
    val angleRad: Float,
    override val priority: SnapPriority = SnapPriority.ELLIPSE,
) : SnapTrack {
    private val cs = cos(angleRad)
    private val sn = sin(angleRad)

    /** Parametric angle of a point in the ellipse's unit frame. */
    fun thetaOf(x: Float, y: Float): Float {
        val dx = x - c.x; val dy = y - c.y
        val u = dx * cs + dy * sn
        val v = (-dx * sn + dy * cs) / ratio
        return atan2(v, u)
    }

    /** Point at parametric angle [theta]. */
    fun pointAt(theta: Float): Vec2 {
        val u = r * cos(theta); val v = r * ratio * sin(theta)
        return Vec2(c.x + u * cs - v * sn, c.y + u * sn + v * cs)
    }

    override fun tangentAt(p0: Vec2): Vec2 {
        val th = thetaOf(p0.x, p0.y)
        val du = -r * sin(th); val dv = r * ratio * cos(th)
        return Vec2(du * cs - dv * sn, du * sn + dv * cs).normalized()
    }

    override fun project(x: Float, y: Float, hintS: Float, out: Projection) {
        val dx = x - c.x; val dy = y - c.y
        var th = if (dx * dx + dy * dy < 1e-8f && !hintS.isNaN()) hintS / r else thetaOf(x, y)
        if (!hintS.isNaN() && r > 0f) {
            val h = hintS / r
            th += (2 * PI * round((h - th) / (2 * PI))).toFloat()
        }
        val p = pointAt(th)
        out.x = p.x; out.y = p.y; out.s = th * r
    }
}

/**
 * Track along a polyline (flattened Pen path). With a hint the search is limited to
 * `hintS ± (2·|Δp| + 8)` so the stroke can't jump to a neighbouring loop; on a closed path the window
 * wraps and `s` keeps growing past the length. Stateful: one instance per stroke.
 */
class PathTrack(val line: ArcPolyline, override val priority: SnapPriority = SnapPriority.PEN) : SnapTrack {
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    override fun tangentAt(p0: Vec2): Vec2 {
        val seg = nearest(p0.x, p0.y, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, tmp)
        if (seg < 0) return Vec2(1f, 0f)
        return (line.segB(seg) - line.segA(seg)).normalized()
    }

    override fun project(x: Float, y: Float, hintS: Float, out: Projection) {
        if (line.segmentCount == 0) {
            val p = line.points.firstOrNull() ?: Vec2(x, y)
            out.x = p.x; out.y = p.y; out.s = 0f; return
        }
        if (hintS.isNaN() || lastX.isNaN()) {
            nearest(x, y, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, out)
        } else {
            val dp = sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY))
            val w = 2 * dp + WINDOW_PAD
            if (line.closed && 2 * w >= line.length) {
                nearest(x, y, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, out)
                val turns = round((hintS - out.s) / line.length)
                out.s += turns * line.length
            } else {
                nearest(x, y, hintS - w, hintS + w, out)
            }
        }
        lastX = x; lastY = y
    }

    private val tmp = Projection()

    /**
     * Nearest point with `s ∈ [lo, hi]` (s unwrapped by whole lengths on a closed line). Returns the
     * segment index, or −1.
     */
    private fun nearest(x: Float, y: Float, lo: Float, hi: Float, out: Projection): Int {
        val len = line.length
        val global = lo == Float.NEGATIVE_INFINITY
        val shifts = if (!line.closed || global || len <= 0f) 0..0 else
            kotlin.math.floor(lo / len).toInt()..kotlin.math.floor(hi / len).toInt()
        var best = Float.MAX_VALUE
        var bestSeg = -1
        for (k in shifts) {
            val off = k * len
            for (i in 0 until line.segmentCount) {
                val s0 = line.cum[i] + off
                val s1 = line.cum[i + 1] + off
                val a = max(s0, lo); val b = min(s1, hi)
                if (a > b) continue
                val pa = line.segA(i); val pb = line.segB(i)
                val segLen = s1 - s0
                var t = if (segLen > 0f) {
                    val ab = pb - pa
                    ((x - pa.x) * ab.x + (y - pa.y) * ab.y) / (segLen * segLen)
                } else 0f
                val tMin = if (segLen > 0f) (a - s0) / segLen else 0f
                val tMax = if (segLen > 0f) (b - s0) / segLen else 0f
                t = t.coerceIn(tMin, tMax)
                val px = pa.x + (pb.x - pa.x) * t; val py = pa.y + (pb.y - pa.y) * t
                val d = (px - x) * (px - x) + (py - y) * (py - y)
                if (d < best) {
                    best = d; bestSeg = i
                    out.x = px; out.y = py; out.s = s0 + t * segLen
                }
            }
        }
        return bestSeg
    }

    private companion object {
        const val WINDOW_PAD = 8f
    }
}

/** Signed distance from [p] to the infinite line through [a] along unit [d] (positive = left normal side). */
internal fun signedDist(p: Vec2, a: Vec2, d: Vec2): Float = (p - a) dot Vec2(-d.y, d.x)

internal fun absAngleBetween(u: Vec2, t: Vec2): Float {
    val c = abs(u dot t).coerceAtMost(1f)
    return kotlin.math.acos(c)
}

internal fun offsetLine(a: Vec2, d: Vec2, off: Float): Vec2 = a + Vec2(-d.y, d.x) * off
