package app.painter.core.geom

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class Vec2(val x: Float, val y: Float)

/** 2D affine map: x' = a·x + c·y + tx; y' = b·x + d·y + ty. A negative [det] means a reflection. */
data class Affine2(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) {

    val det: Float get() = a * d - b * c

    /** Rotation angle of the linear part (of its x axis image), radians. */
    val rotationRad: Float get() = atan2(b, a)

    fun map(x: Float, y: Float, out: FloatArray) {
        out[0] = a * x + c * y + tx
        out[1] = b * x + d * y + ty
    }

    fun map(p: Vec2): Vec2 = Vec2(a * p.x + c * p.y + tx, b * p.x + d * p.y + ty)

    /** This map followed by [o]: (o ∘ this). */
    fun then(o: Affine2): Affine2 = Affine2(
        a = o.a * a + o.c * b,
        b = o.b * a + o.d * b,
        c = o.a * c + o.c * d,
        d = o.b * c + o.d * d,
        tx = o.a * tx + o.c * ty + o.tx,
        ty = o.b * tx + o.d * ty + o.ty,
    )

    fun inverse(): Affine2 {
        val k = det
        require(k != 0f) { "singular transform" }
        val ia = d / k; val ib = -b / k; val ic = -c / k; val id = a / k
        return Affine2(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty))
    }

    companion object {
        val ID = Affine2(1f, 0f, 0f, 1f, 0f, 0f)

        fun translation(dx: Float, dy: Float) = Affine2(1f, 0f, 0f, 1f, dx, dy)

        /** Rotation by [rad] around [c]. */
        fun rotation(c: Vec2, rad: Float): Affine2 {
            val cs = cos(rad); val sn = sin(rad)
            return Affine2(cs, sn, -sn, cs, c.x - cs * c.x + sn * c.y, c.y - sn * c.x - cs * c.y)
        }

        /** Reflection across the line through [c] at angle [axisRad]. */
        fun reflection(c: Vec2, axisRad: Float): Affine2 {
            val cs = cos(2 * axisRad); val sn = sin(2 * axisRad)
            return Affine2(cs, sn, sn, -cs, c.x - cs * c.x - sn * c.y, c.y - sn * c.x + cs * c.y)
        }

        fun scale(sx: Float, sy: Float, c: Vec2 = Vec2(0f, 0f)) =
            Affine2(sx, 0f, 0f, sy, c.x - sx * c.x, c.y - sy * c.y)
    }
}
