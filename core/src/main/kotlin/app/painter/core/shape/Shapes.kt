package app.painter.core.shape

import app.painter.core.geom.Vec2
import app.painter.core.stroke.FillSpec
import app.painter.core.stroke.InputPoint
import app.painter.core.stroke.Interpolation
import app.painter.core.stroke.StrokeOptions
import app.painter.core.stroke.SymmetryCopies
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Editable geometric shape (spec G3), document px, rotations in radians. Rendered as a polyline
 * through the current brush: see [ShapeStroke].
 */
sealed interface Shape {
    val closed: Boolean

    /** Polyline within [tolerance] px of the exact geometry; closed shapes don't repeat the first point. */
    fun outline(tolerance: Float = BezierPath.DEFAULT_TOLERANCE): List<Vec2>

    data class Line(val a: Vec2, val b: Vec2) : Shape {
        override val closed get() = false
        override fun outline(tolerance: Float) = listOf(a, b)
    }

    /** Rectangle [w]×[h] centred at [center], rotated by [rot]. */
    data class Rect(val center: Vec2, val w: Float, val h: Float, val rot: Float = 0f) : Shape {
        override val closed get() = true
        override fun outline(tolerance: Float) = corners()

        /** Corners in order top-left, top-right, bottom-right, bottom-left of the local frame. */
        fun corners(): List<Vec2> = LOCAL_CORNERS.map { (sx, sy) -> toWorld(center, rot, Vec2(sx * w / 2, sy * h / 2)) }
    }

    /** Rectangle with rounded corners; [radius] is clamped to half of the shorter side. */
    data class RoundedRect(
        val center: Vec2,
        val w: Float,
        val h: Float,
        val radius: Float,
        val rot: Float = 0f,
    ) : Shape {
        override val closed get() = true

        val r: Float get() = radius.coerceIn(0f, minOf(abs(w), abs(h)) / 2f)

        override fun outline(tolerance: Float): List<Vec2> {
            val hw = w / 2f
            val hh = h / 2f
            val rr = r
            val out = ArrayList<Vec2>(4 * 9)
            // Clockwise: left-top, top-right, right-bottom, bottom-left quarter arcs.
            val corners = listOf(
                Vec2(-hw + rr, -hh + rr) to 180f,
                Vec2(hw - rr, -hh + rr) to 270f,
                Vec2(hw - rr, hh - rr) to 0f,
                Vec2(-hw + rr, hh - rr) to 90f,
            )
            val steps = 6
            for ((c, start) in corners) {
                for (k in 0..steps) {
                    val a = Math.toRadians((start + 90f * k / steps).toDouble())
                    out.add(toWorld(center, rot, Vec2((c.x + rr * cos(a)).toFloat(), (c.y + rr * sin(a)).toFloat())))
                }
            }
            return out
        }
    }

    /** Filled arrow from [start] to [end]; [thickness] is the shaft width. */
    data class Arrow(
        val start: Vec2,
        val end: Vec2,
        val thickness: Float = 12f,
    ) : Shape {
        override val closed get() = true

        override fun outline(tolerance: Float): List<Vec2> {
            val d = end - start
            val len = d.len
            if (len < 1e-3f) return listOf(start, end)
            val u = d * (1f / len)
            val n = Vec2(-u.y, u.x)
            val shaft = (thickness.coerceAtLeast(1f) / 2f).coerceAtMost(len * 0.4f)
            val headHalf = (shaft * 2.4f).coerceAtMost(len * 0.55f)
            val headLen = (shaft * 3.2f).coerceIn(2f, len * 0.65f)
            val base = end - u * headLen
            return listOf(
                start + n * shaft,
                base + n * shaft,
                base + n * headHalf,
                end,
                base - n * headHalf,
                base - n * shaft,
                start - n * shaft,
            )
        }
    }

    /** Speech balloon: an ellipse with a pointed tail at [tail] (a local offset from the centre). */
    data class Balloon(
        val center: Vec2,
        val rx: Float,
        val ry: Float,
        val tail: Vec2 = Vec2(0f, 0f),
        val rot: Float = 0f,
    ) : Shape {
        override val closed get() = true

        override fun outline(tolerance: Float): List<Vec2> {
            val out = ArrayList<Vec2>()
            val gap = 0.35
            val tailAngle = Math.PI * 0.75
            val start = tailAngle + gap
            val end = tailAngle + Math.PI * 2 - gap
            val n = segmentsFor(max(abs(rx), abs(ry)), end - start, tolerance).coerceAtLeast(12)
            for (k in 0..n) {
                val a = start + (end - start) * k / n
                out.add(toWorld(center, rot, Vec2((rx * cos(a)).toFloat(), (ry * sin(a)).toFloat())))
            }
            out.add(toWorld(center, rot, tail))
            return out
        }
    }

    /** Ellipse with semi-axes [rx] (along [rot]) and [ry]. */
    data class Ellipse(val center: Vec2, val rx: Float, val ry: Float, val rot: Float = 0f) : Shape {
        override val closed get() = true
        override fun outline(tolerance: Float): List<Vec2> {
            val n = segmentsFor(max(abs(rx), abs(ry)), 2 * PI, tolerance)
            return (0 until n).map { i ->
                val t = 2 * PI * i / n
                toWorld(center, rot, Vec2((rx * cos(t)).toFloat(), (ry * sin(t)).toFloat()))
            }
        }
    }

    /** Regular polygon with [sides] (3–16) vertices on a circle of [radius]; [rot] = first vertex angle. */
    data class Polygon(val center: Vec2, val radius: Float, val sides: Int = 5, val rot: Float = -(PI / 2).toFloat()) : Shape {
        override val closed get() = true
        val n: Int get() = sides.coerceIn(MIN_SIDES, MAX_SIDES)
        override fun outline(tolerance: Float) = (0 until n).map { polar(center, radius, rot + (2 * PI * it / n).toFloat()) }

        companion object {
            const val MIN_SIDES = 3
            const val MAX_SIDES = 16
        }
    }

    /** Circular arc from [start] through [mid] to [end]; collinear points give a straight line. */
    data class Arc(val start: Vec2, val end: Vec2, val mid: Vec2) : Shape {
        override val closed get() = false
        override fun outline(tolerance: Float): List<Vec2> {
            val g = geometry() ?: return listOf(start, end)
            // Sample start→mid and mid→end separately so the user's mid handle lies exactly on the outline.
            val m = norm2pi((mid - g.c).angle.toDouble() - g.a0)
            val sm = if (g.sweep > 0) m else m - 2 * PI
            val n1 = segmentsFor(g.r, abs(sm), tolerance)
            val n2 = segmentsFor(g.r, abs(g.sweep - sm), tolerance)
            val out = ArrayList<Vec2>(n1 + n2 + 1)
            out.add(start)
            for (i in 1 until n1) out.add(polar(g.c, g.r, (g.a0 + sm * i / n1).toFloat()))
            out.add(mid)
            for (i in 1 until n2) out.add(polar(g.c, g.r, (g.a0 + sm + (g.sweep - sm) * i / n2).toFloat()))
            out.add(end)
            return out
        }

        /** Circle centre, radius, start angle and signed sweep (radians), or null when collinear. */
        fun geometry(): ArcGeometry? {
            val c = circumcenter(start, mid, end) ?: return null
            val r = c.dist(start)
            val a0 = (start - c).angle.toDouble()
            val s = norm2pi((end - c).angle - a0)
            val m = norm2pi((mid - c).angle - a0)
            val sweep = if (m < s) s else s - 2 * PI
            return ArcGeometry(c, r, a0, sweep)
        }
    }

    /** Bézier path or polyline (all-cusp path). */
    data class Path(val path: BezierPath) : Shape {
        override val closed get() = path.closed
        override fun outline(tolerance: Float) = path.flatten(tolerance)
    }

    companion object {
        private val LOCAL_CORNERS = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)
    }
}

data class ArcGeometry(val c: Vec2, val r: Float, val a0: Double, val sweep: Double)

/** Builds stroke input for a shape (spec G3.1): no stabilizer, LINEAR, pressure 1, `closed` for closed shapes. */
object ShapeStroke {
    /** Outline points as stroke input (pressure 1). */
    fun points(shape: Shape, tolerance: Float = BezierPath.DEFAULT_TOLERANCE): List<InputPoint> =
        shape.outline(tolerance).map { InputPoint(it.x, it.y, pressure = 1f) }

    /** Options for rendering [shape]; [fill] applies only to closed shapes. */
    fun options(shape: Shape, fill: FillSpec? = null, copies: SymmetryCopies = SymmetryCopies.NONE) = StrokeOptions(
        stabilize = false,
        interpolation = Interpolation.LINEAR,
        closed = shape.closed,
        copies = copies,
        fill = if (shape.closed) fill else null,
    )
}

internal fun toWorld(c: Vec2, rot: Float, local: Vec2): Vec2 {
    val cs = cos(rot); val sn = sin(rot)
    return Vec2(c.x + local.x * cs - local.y * sn, c.y + local.x * sn + local.y * cs)
}

internal fun toLocal(c: Vec2, rot: Float, p: Vec2): Vec2 {
    val cs = cos(rot); val sn = sin(rot)
    val dx = p.x - c.x; val dy = p.y - c.y
    return Vec2(dx * cs + dy * sn, -dx * sn + dy * cs)
}

/** Segments for an arc of radius [r] spanning [span] radians with sagitta ≤ [tol]. */
internal fun segmentsFor(r: Float, span: Double, tol: Float): Int {
    if (r <= tol) return max(3, ceil(span / (PI / 2)).toInt())
    val step = 2 * acos((1 - tol / r).toDouble().coerceIn(-1.0, 1.0))
    return ceil(span / step).toInt().coerceIn(3, 8192)
}

internal fun norm2pi(a: Double): Double {
    val r = a % (2 * PI)
    return if (r < 0) r + 2 * PI else r
}

internal fun norm2pi(a: Float): Double = norm2pi(a.toDouble())

/** Centre of the circle through three points, or null when they are (nearly) collinear. */
internal fun circumcenter(a: Vec2, b: Vec2, c: Vec2): Vec2? {
    val ax = a.x.toDouble(); val ay = a.y.toDouble()
    val bx = b.x - ax; val by = b.y - ay; val cx = c.x - ax; val cy = c.y - ay
    val d = 2 * (bx * cy - by * cx)
    val scale = max(bx * bx + by * by, cx * cx + cy * cy)
    if (abs(d) < 1e-6 * scale) return null
    val b2 = bx * bx + by * by; val c2 = cx * cx + cy * cy
    val ux = (cy * b2 - by * c2) / d; val uy = (bx * c2 - cx * b2) / d
    return Vec2((ax + ux).toFloat(), (ay + uy).toFloat())
}
