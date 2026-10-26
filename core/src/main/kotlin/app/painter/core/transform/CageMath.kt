package app.painter.core.transform

import app.painter.core.geom.Vec2
import app.painter.core.raster.IntRect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Float rectangle in document pixels. */
data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    companion object {
        fun of(r: IntRect) = FloatRect(r.x0.toFloat(), r.y0.toFloat(), r.x1.toFloat(), r.y1.toFloat())
    }
}

/** Snapping helpers (spec §3.5). Thresholds are in document pixels (the caller converts from dp). */
object Snap {
    /**
     * Correction (dx, dy) that makes the nearest cage edge or center line meet a canvas edge or center within
     * [thresholdPx]; zero on an axis with no candidate.
     */
    fun move(cage: FloatRect, canvasW: Int, canvasH: Int, thresholdPx: Float): Vec2 =
        Vec2(
            axis(floatArrayOf(cage.left, cage.centerX, cage.right), floatArrayOf(0f, canvasW / 2f, canvasW.toFloat()), thresholdPx),
            axis(floatArrayOf(cage.top, cage.centerY, cage.bottom), floatArrayOf(0f, canvasH / 2f, canvasH.toFloat()), thresholdPx),
        )

    private fun axis(own: FloatArray, targets: FloatArray, th: Float): Float {
        var best = 0f
        var bestAbs = Float.MAX_VALUE
        for (o in own) for (t in targets) {
            val d = t - o
            if (abs(d) <= th && abs(d) < bestAbs) { best = d; bestAbs = abs(d) }
        }
        return best
    }

    /** [deg] snapped to the nearest multiple of [step] when within [tol] degrees. */
    fun angle(deg: Float, step: Float = 90f, tol: Float = 3f): Float {
        val k = (deg / step).roundToInt() * step
        return if (abs(deg - k) <= tol) k else deg
    }

    /** Scale factor snapped to 1 within [tol] (2 % by default). */
    fun scale(s: Float, tol: Float = 0.02f): Float = if (abs(s - 1f) <= tol) 1f else s

    /** Pivot snapped to the 9 cage points or the canvas center within [thresholdPx]. */
    fun pivot(p: Vec2, cage: FloatRect, canvasW: Int, canvasH: Int, thresholdPx: Float): Vec2 {
        val cands = ArrayList<Vec2>()
        for (y in floatArrayOf(cage.top, cage.centerY, cage.bottom)) for (x in floatArrayOf(cage.left, cage.centerX, cage.right)) cands.add(Vec2(x, y))
        cands.add(Vec2(canvasW / 2f, canvasH / 2f))
        return cands.filter { hypot(it.x - p.x, it.y - p.y) <= thresholdPx }
            .minByOrNull { hypot(it.x - p.x, it.y - p.y) } ?: p
    }
}

/** Cage handles: corners clockwise from top-left, then side midpoints. */
enum class Handle(val ux: Int, val uy: Int) {
    TOP_LEFT(-1, -1), TOP_RIGHT(1, -1), BOTTOM_RIGHT(1, 1), BOTTOM_LEFT(-1, 1),
    TOP(0, -1), RIGHT(1, 0), BOTTOM(0, 1), LEFT(-1, 0);

    val isCorner: Boolean get() = ux != 0 && uy != 0
}

/**
 * Gesture math for the Basic, Anchor and Distort modes (spec §3.2). Affine edits are expressed on top of the
 * current map [base] so a session stays one mapping from the original pixels.
 */
object CageMath {
    fun move(base: Affine, dx: Float, dy: Float): Affine = base.then(Affine.translation(dx, dy))

    /** Rotation by [deg] around the destination point [pivot]. */
    fun rotate(base: Affine, deg: Float, pivot: Vec2): Affine = base.then(Affine.rotation(deg, pivot.x, pivot.y))

    /** Angle in degrees of the drag from [from] to [to] around [pivot]. */
    fun dragAngle(pivot: Vec2, from: Vec2, to: Vec2): Float {
        val a0 = atan2(from.y - pivot.y, from.x - pivot.x)
        val a1 = atan2(to.y - pivot.y, to.x - pivot.x)
        return Math.toDegrees((a1 - a0).toDouble()).toFloat()
    }

    fun flip(base: Affine, horizontal: Boolean, pivot: Vec2): Affine = base.then(Affine.flip(horizontal, pivot.x, pivot.y))

    /**
     * Scale from dragging [handle] of [cage] (source space) to the destination point [to]. Corners scale about
     * the cage center ([uniform] keeps the aspect), sides stretch one axis from the opposite side. [pivot] (a
     * source-space point) overrides the fixed point, as in Anchor mode.
     */
    fun scaleHandle(base: Affine, cage: FloatRect, handle: Handle, to: Vec2, uniform: Boolean, pivot: Vec2? = null): Affine {
        val inv = base.inverted() ?: return base
        val p = FloatArray(2)
        inv.forward(to.x, to.y, p)
        val fx = pivot?.x ?: if (handle.isCorner || handle.ux == 0) cage.centerX else if (handle.ux > 0) cage.left else cage.right
        val fy = pivot?.y ?: if (handle.isCorner || handle.uy == 0) cage.centerY else if (handle.uy > 0) cage.top else cage.bottom
        val hx = when (handle.ux) { -1 -> cage.left; 1 -> cage.right; else -> cage.centerX }
        val hy = when (handle.uy) { -1 -> cage.top; 1 -> cage.bottom; else -> cage.centerY }
        var sx = if (handle.ux != 0 && abs(hx - fx) > 1e-6f) (p[0] - fx) / (hx - fx) else 1f
        var sy = if (handle.uy != 0 && abs(hy - fy) > 1e-6f) (p[1] - fy) / (hy - fy) else 1f
        if (uniform && handle.isCorner) {
            val s = if (abs(sx) > abs(sy)) sx else sy
            sx = s; sy = s
        }
        return Affine.trs(0f, 0f, sx, sy, 0f, fx, fy).then(base)
    }

    /**
     * Similarity from a two-finger gesture: the segment ([a0], [b0]) moved to ([a1], [b1]) (translate, scale,
     * rotate), applied after [base].
     */
    fun pinch(base: Affine, a0: Vec2, b0: Vec2, a1: Vec2, b1: Vec2): Affine {
        val d0 = hypot(b0.x - a0.x, b0.y - a0.y)
        val d1 = hypot(b1.x - a1.x, b1.y - a1.y)
        if (d0 < 1e-3f) return move(base, a1.x - a0.x, a1.y - a0.y)
        val s = d1 / d0
        val rot = Math.toDegrees((atan2(b1.y - a1.y, b1.x - a1.x) - atan2(b0.y - a0.y, b0.x - a0.x)).toDouble()).toFloat()
        val m = Affine.trs(a1.x - a0.x, a1.y - a0.y, s, s, rot, a0.x, a0.y)
        return base.then(m)
    }

    /** The 4 destination corners of [cage] under [m] (clockwise from top-left). */
    fun quad(m: Mapping, cage: FloatRect): FloatArray {
        val out = FloatArray(8); val p = FloatArray(2)
        val xs = floatArrayOf(cage.left, cage.right, cage.right, cage.left)
        val ys = floatArrayOf(cage.top, cage.top, cage.bottom, cage.bottom)
        for (i in 0 until 4) { m.forward(xs[i], ys[i], p); out[i * 2] = p[0]; out[i * 2 + 1] = p[1] }
        return out
    }

    /** Distort: moving a side handle moves both of its corners (stretch across, skew along). */
    fun distortSide(quad: FloatArray, side: Handle, dx: Float, dy: Float): FloatArray {
        val out = quad.copyOf()
        val corners = when (side) {
            Handle.TOP -> intArrayOf(0, 1); Handle.RIGHT -> intArrayOf(1, 2)
            Handle.BOTTOM -> intArrayOf(2, 3); Handle.LEFT -> intArrayOf(3, 0)
            else -> intArrayOf(side.ordinal)
        }
        for (c in corners) { out[c * 2] += dx; out[c * 2 + 1] += dy }
        return out
    }

    /** Strictly convex, non-degenerate quad (either orientation). Distort keeps the last valid quad otherwise. */
    fun isConvexQuad(q: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = q[i * 2]; val ay = q[i * 2 + 1]
            val bx = q[(i + 1) % 4 * 2]; val by = q[(i + 1) % 4 * 2 + 1]
            val cx = q[(i + 2) % 4 * 2]; val cy = q[(i + 2) % 4 * 2 + 1]
            val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            if (abs(cross) < 1e-6f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Homography from the cage to a distorted [quad], or null if the quad is invalid. */
    fun distort(cage: FloatRect, quad: FloatArray): Projective? {
        if (!isConvexQuad(quad)) return null
        val src = floatArrayOf(cage.left, cage.top, cage.right, cage.top, cage.right, cage.bottom, cage.left, cage.bottom)
        return Projective.fromQuads(src, quad)
    }
}
