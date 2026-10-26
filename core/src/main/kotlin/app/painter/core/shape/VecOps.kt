package app.painter.core.shape

import app.painter.core.geom.Vec2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal operator fun Vec2.plus(o: Vec2) = Vec2(x + o.x, y + o.y)
internal operator fun Vec2.minus(o: Vec2) = Vec2(x - o.x, y - o.y)
internal operator fun Vec2.times(k: Float) = Vec2(x * k, y * k)
internal operator fun Vec2.unaryMinus() = Vec2(-x, -y)
internal infix fun Vec2.dot(o: Vec2) = x * o.x + y * o.y
internal infix fun Vec2.cross(o: Vec2) = x * o.y - y * o.x
internal val Vec2.len: Float get() = sqrt(x * x + y * y)
internal val Vec2.angle: Float get() = atan2(y, x)
internal fun Vec2.dist(o: Vec2): Float = (this - o).len
internal fun Vec2.normalized(): Vec2 {
    val l = len
    return if (l < 1e-12f) Vec2(1f, 0f) else Vec2(x / l, y / l)
}

/** Left normal (rotate +90° in a y-down frame looks clockwise on screen; only orthogonality matters). */
internal fun Vec2.perp() = Vec2(-y, x)
internal fun lerp(a: Vec2, b: Vec2, t: Float) = Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
internal fun polar(c: Vec2, r: Float, rad: Float) = Vec2(c.x + r * cos(rad), c.y + r * sin(rad))
internal fun dir(rad: Float) = Vec2(cos(rad), sin(rad))
internal fun Float.toRad() = (this * PI / 180.0).toFloat()
internal fun Float.toDeg() = (this * 180.0 / PI).toFloat()

/** Distance from [p] to segment [a]–[b]. */
internal fun distToSegment(p: Vec2, a: Vec2, b: Vec2): Float {
    val ab = b - a
    val l2 = ab dot ab
    if (l2 < 1e-12f) return p.dist(a)
    val t = (((p - a) dot ab) / l2).coerceIn(0f, 1f)
    return p.dist(a + ab * t)
}

/** Distance from [p] to a polyline ([closed] adds the last→first edge). */
internal fun distToPolyline(p: Vec2, pts: List<Vec2>, closed: Boolean): Float {
    if (pts.size == 1) return p.dist(pts[0])
    var best = Float.MAX_VALUE
    val n = if (closed) pts.size else pts.size - 1
    for (i in 0 until n) {
        val d = distToSegment(p, pts[i], pts[(i + 1) % pts.size])
        if (d < best) best = d
    }
    return best
}

/** Wraps an angle to (−π, π]. */
internal fun wrapPi(a: Double): Double {
    var r = a % (2 * PI)
    if (r <= -PI) r += 2 * PI
    if (r > PI) r -= 2 * PI
    return r
}
