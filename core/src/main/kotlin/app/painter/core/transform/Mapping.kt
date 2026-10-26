package app.painter.core.transform

import app.painter.core.geom.Affine2
import app.painter.core.raster.IntRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Geometric map from floating (source) pixels to canvas (destination) pixels (spec §3.8). */
sealed interface Mapping {
    /** dst → src into [out] (x, y); false if the point is outside the mapped area. */
    fun inverse(x: Float, y: Float, out: FloatArray): Boolean

    /** src → dst into [out]; false if the point has no finite image. */
    fun forward(x: Float, y: Float, out: FloatArray): Boolean

    /** Bounds of the image of [src] (may extend past the canvas). */
    fun dstBounds(src: IntRect): IntRect
}

private const val HUGE = 1 shl 28

private fun boundsOf(xs: FloatArray, ys: FloatArray, n: Int): IntRect {
    var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
    for (i in 0 until n) {
        x0 = min(x0, xs[i]); x1 = max(x1, xs[i]); y0 = min(y0, ys[i]); y1 = max(y1, ys[i])
    }
    fun cl(v: Float) = v.coerceIn(-HUGE.toFloat(), HUGE.toFloat())
    return IntRect(floor(cl(x0)).toInt() - 1, floor(cl(y0)).toInt() - 1, ceil(cl(x1)).toInt() + 1, ceil(cl(y1)).toInt() + 1)
}

/** Affine map x' = a·x + c·y + tx, y' = b·x + d·y + ty (same convention as [Affine2]). */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) : Mapping {

    private val inv: Affine? by lazy { invertedOrNull() }

    val det: Float get() = a * d - b * c

    /** This map followed by [o]. */
    fun then(o: Affine): Affine = Affine(
        a = o.a * a + o.c * b,
        b = o.b * a + o.d * b,
        c = o.a * c + o.c * d,
        d = o.b * c + o.d * d,
        tx = o.a * tx + o.c * ty + o.tx,
        ty = o.b * tx + o.d * ty + o.ty,
    )

    fun inverted(): Affine? = inv

    private fun invertedOrNull(): Affine? {
        val k = a.toDouble() * d - b.toDouble() * c
        if (abs(k) < 1e-12) return null
        val ia = d / k; val ib = -b / k; val ic = -c / k; val id = a / k
        return Affine(
            ia.toFloat(), ib.toFloat(), ic.toFloat(), id.toFloat(),
            (-(ia * tx + ic * ty)).toFloat(), (-(ib * tx + id * ty)).toFloat(),
        )
    }

    override fun inverse(x: Float, y: Float, out: FloatArray): Boolean {
        val i = inv ?: return false
        out[0] = i.a * x + i.c * y + i.tx
        out[1] = i.b * x + i.d * y + i.ty
        return true
    }

    override fun forward(x: Float, y: Float, out: FloatArray): Boolean {
        out[0] = a * x + c * y + tx
        out[1] = b * x + d * y + ty
        return true
    }

    override fun dstBounds(src: IntRect): IntRect {
        val xs = FloatArray(4); val ys = FloatArray(4); val p = FloatArray(2)
        val cx = floatArrayOf(src.x0.toFloat(), src.x1.toFloat(), src.x1.toFloat(), src.x0.toFloat())
        val cy = floatArrayOf(src.y0.toFloat(), src.y0.toFloat(), src.y1.toFloat(), src.y1.toFloat())
        for (i in 0 until 4) { forward(cx[i], cy[i], p); xs[i] = p[0]; ys[i] = p[1] }
        return boundsOf(xs, ys, 4)
    }

    fun toProjective(): Projective = Projective(floatArrayOf(a, c, tx, b, d, ty, 0f, 0f, 1f))

    fun toAffine2(): Affine2 = Affine2(a, b, c, d, tx, ty)

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)

        fun of(m: Affine2) = Affine(m.a, m.b, m.c, m.d, m.tx, m.ty)

        fun translation(dx: Float, dy: Float) = Affine(1f, 0f, 0f, 1f, dx, dy)

        /**
         * Scale ([sx], [sy]) then rotate by [rotDeg] (clockwise on screen, y down) around the pivot
         * ([px], [py]), then translate by ([tx], [ty]). Multiples of 90° use exact sine and cosine.
         */
        fun trs(tx: Float, ty: Float, sx: Float, sy: Float, rotDeg: Float, px: Float, py: Float): Affine {
            val (cs, sn) = exactCosSin(rotDeg)
            val a = cs * sx; val b = sn * sx; val c = -sn * sy; val d = cs * sy
            return Affine(a, b, c, d, px - a * px - c * py + tx, py - b * px - d * py + ty)
        }

        /** Rotation by [deg] around ([px], [py]). */
        fun rotation(deg: Float, px: Float, py: Float) = trs(0f, 0f, 1f, 1f, deg, px, py)

        /** Mirror across the vertical ([horizontal]) or horizontal line through the pivot. */
        fun flip(horizontal: Boolean, px: Float, py: Float) =
            if (horizontal) trs(0f, 0f, -1f, 1f, 0f, px, py) else trs(0f, 0f, 1f, -1f, 0f, px, py)

        internal fun exactCosSin(deg: Float): Pair<Float, Float> {
            val q = deg / 90f
            val qi = q.roundToInt()
            if (abs(q - qi) < 1e-6f) {
                return when (((qi % 4) + 4) % 4) {
                    0 -> 1f to 0f
                    1 -> 0f to 1f
                    2 -> -1f to 0f
                    else -> 0f to -1f
                }
            }
            val r = deg * PI / 180.0
            return cos(r).toFloat() to sin(r).toFloat()
        }
    }
}

/**
 * Homography: x' = (m0·x + m1·y + m2) / w, y' = (m3·x + m4·y + m5) / w, w = m6·x + m7·y + m8 (row-major 3×3).
 * Points with w ≤ 0 lie behind the horizon and are not mapped.
 */
class Projective(val m: FloatArray) : Mapping {
    init { require(m.size == 9) }

    private val md = DoubleArray(9) { m[it].toDouble() }
    private val inv: DoubleArray? = invert3(md)

    override fun forward(x: Float, y: Float, out: FloatArray): Boolean {
        val w = md[6] * x + md[7] * y + md[8]
        if (w <= 1e-12) return false
        out[0] = ((md[0] * x + md[1] * y + md[2]) / w).toFloat()
        out[1] = ((md[3] * x + md[4] * y + md[5]) / w).toFloat()
        return true
    }

    override fun inverse(x: Float, y: Float, out: FloatArray): Boolean {
        val i = inv ?: return false
        val w = i[6] * x + i[7] * y + i[8]
        if (abs(w) < 1e-12) return false
        val sx = (i[0] * x + i[1] * y + i[2]) / w
        val sy = (i[3] * x + i[4] * y + i[5]) / w
        if (md[6] * sx + md[7] * sy + md[8] <= 1e-12) return false
        out[0] = sx.toFloat(); out[1] = sy.toFloat()
        return true
    }

    override fun dstBounds(src: IntRect): IntRect {
        val xs = FloatArray(4); val ys = FloatArray(4); val p = FloatArray(2)
        val cx = floatArrayOf(src.x0.toFloat(), src.x1.toFloat(), src.x1.toFloat(), src.x0.toFloat())
        val cy = floatArrayOf(src.y0.toFloat(), src.y0.toFloat(), src.y1.toFloat(), src.y1.toFloat())
        for (i in 0 until 4) {
            if (!forward(cx[i], cy[i], p)) return IntRect(-HUGE, -HUGE, HUGE, HUGE)
            xs[i] = p[0]; ys[i] = p[1]
        }
        return boundsOf(xs, ys, 4)
    }

    /** This map followed by [o]. */
    fun then(o: Projective): Projective = Projective(FloatArray(9) { k ->
        val r = k / 3; val c = k % 3
        (0 until 3).sumOf { o.md[r * 3 + it] * md[it * 3 + c] }.toFloat()
    })

    companion object {
        val IDENTITY get() = Projective(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

        /**
         * Homography taking the 4 corners [src] to [dst] (xy pairs, same order), by DLT with Gaussian
         * elimination (partial pivoting) on the 8×8 system. Null for degenerate quads.
         */
        fun fromQuads(src: FloatArray, dst: FloatArray): Projective? {
            require(src.size == 8 && dst.size == 8)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = src[i * 2].toDouble(); val y = src[i * 2 + 1].toDouble()
                val u = dst[i * 2].toDouble(); val v = dst[i * 2 + 1].toDouble()
                a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val scale = (src + dst).maxOf { abs(it) }.toDouble().coerceAtLeast(1.0)
            for (col in 0 until 8) {
                var piv = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 1e-9 * scale) return null
                val t = a[piv]; a[piv] = a[col]; a[col] = t
                for (r in 0 until 8) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    if (f == 0.0) continue
                    for (k in col until 9) a[r][k] -= f * a[col][k]
                }
            }
            val h = DoubleArray(9) { if (it == 8) 1.0 else a[it][8] / a[it][it] }
            val det = h[0] * (h[4] * h[8] - h[5] * h[7]) - h[1] * (h[3] * h[8] - h[5] * h[6]) + h[2] * (h[3] * h[7] - h[4] * h[6])
            if (abs(det) < 1e-12 || !det.isFinite()) return null
            val cx = (src[0] + src[2] + src[4] + src[6]) / 4.0
            val cy = (src[1] + src[3] + src[5] + src[7]) / 4.0
            val sign = if (h[6] * cx + h[7] * cy + h[8] < 0) -1.0 else 1.0
            return Projective(FloatArray(9) { (h[it] * sign).toFloat() })
        }

        private fun invert3(m: DoubleArray): DoubleArray? {
            val c00 = m[4] * m[8] - m[5] * m[7]
            val c01 = m[5] * m[6] - m[3] * m[8]
            val c02 = m[3] * m[7] - m[4] * m[6]
            val det = m[0] * c00 + m[1] * c01 + m[2] * c02
            if (abs(det) < 1e-18 || !det.isFinite()) return null
            val k = 1.0 / det
            return doubleArrayOf(
                c00 * k, (m[2] * m[7] - m[1] * m[8]) * k, (m[1] * m[5] - m[2] * m[4]) * k,
                c01 * k, (m[0] * m[8] - m[2] * m[6]) * k, (m[2] * m[3] - m[0] * m[5]) * k,
                c02 * k, (m[1] * m[6] - m[0] * m[7]) * k, (m[0] * m[4] - m[1] * m[3]) * k,
            )
        }
    }
}

/**
 * Warp mode: a bicubic Bézier patch over the source rect [src] (spec §3.2). The 16 control points [ctrl]
 * (32 floats, row-major by v) are in destination space; the patch is applied on top of [pre]:
 * `dst(u, v) = pre(src(u, v)) + Σ Bᵢ(u)·Bⱼ(v)·(ctrlᵢⱼ − pre(gridᵢⱼ))`, so control points equal to
 * [initialCtrl] reproduce [pre] exactly. Inversion goes through a [tess]×[tess] triangle mesh, the same
 * geometry the GPU preview draws.
 */
class BezierWarp(val pre: Projective, val ctrl: FloatArray, val src: IntRect, val tess: Int = 64) : Mapping {
    init { require(ctrl.size == 32 && !src.isEmpty && tess >= 1) }

    private val offs = FloatArray(32)
    /** Mesh nodes in dst space, (tess + 1)² xy pairs, row-major by v. */
    val nodes: FloatArray
    private val valid: BooleanArray
    private val bounds: IntRect
    private val cell: Float
    private val gx: Int
    private val gy: Int
    private val buckets: Array<IntArray>

    init {
        val base = initialCtrl(pre, src)
        for (i in 0 until 32) offs[i] = ctrl[i] - base[i]
        val n = tess + 1
        nodes = FloatArray(n * n * 2)
        valid = BooleanArray(n * n)
        val p = FloatArray(2)
        for (j in 0 until n) for (i in 0 until n) {
            val k = j * n + i
            valid[k] = evalPatch(i.toFloat() / tess, j.toFloat() / tess, p)
            nodes[k * 2] = p[0]; nodes[k * 2 + 1] = p[1]
        }
        val xs = FloatArray(n * n) { nodes[it * 2] }
        val ys = FloatArray(n * n) { nodes[it * 2 + 1] }
        bounds = boundsOf(xs, ys, n * n)
        cell = max(1f, max(bounds.width, bounds.height).toFloat() / tess)
        gx = ceil(bounds.width / cell).toInt().coerceAtLeast(1)
        gy = ceil(bounds.height / cell).toInt().coerceAtLeast(1)
        val lists = Array(gx * gy) { ArrayList<Int>() }
        for (t in 0 until tess * tess * 2) {
            val tri = triangle(t) ?: continue
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (v in tri) { x0 = min(x0, nodes[v * 2]); x1 = max(x1, nodes[v * 2]); y0 = min(y0, nodes[v * 2 + 1]); y1 = max(y1, nodes[v * 2 + 1]) }
            val cx0 = ((x0 - bounds.x0) / cell).toInt().coerceIn(0, gx - 1)
            val cx1 = ((x1 - bounds.x0) / cell).toInt().coerceIn(0, gx - 1)
            val cy0 = ((y0 - bounds.y0) / cell).toInt().coerceIn(0, gy - 1)
            val cy1 = ((y1 - bounds.y0) / cell).toInt().coerceIn(0, gy - 1)
            for (cy in cy0..cy1) for (cx in cx0..cx1) lists[cy * gx + cx].add(t)
        }
        buckets = Array(lists.size) { lists[it].toIntArray() }
    }

    /** Destination of the patch parameter ([u], [v]) ∈ [0, 1]². */
    fun evalPatch(u: Float, v: Float, out: FloatArray): Boolean {
        val sx = src.x0 + u * src.width
        val sy = src.y0 + v * src.height
        if (!pre.forward(sx, sy, out)) return false
        val bu = bernstein(u); val bv = bernstein(v)
        var dx = 0f; var dy = 0f
        for (j in 0 until 4) for (i in 0 until 4) {
            val w = bu[i] * bv[j]
            dx += w * offs[(j * 4 + i) * 2]; dy += w * offs[(j * 4 + i) * 2 + 1]
        }
        out[0] += dx; out[1] += dy
        return true
    }

    override fun forward(x: Float, y: Float, out: FloatArray): Boolean =
        evalPatch((x - src.x0) / src.width, (y - src.y0) / src.height, out)

    override fun inverse(x: Float, y: Float, out: FloatArray): Boolean {
        val cx = floor((x - bounds.x0) / cell).toInt()
        val cy = floor((y - bounds.y0) / cell).toInt()
        if (cx !in 0 until gx || cy !in 0 until gy) return false
        val n = tess + 1
        for (t in buckets[cy * gx + cx]) {
            val tri = triangle(t) ?: continue
            val ax = nodes[tri[0] * 2]; val ay = nodes[tri[0] * 2 + 1]
            val bx = nodes[tri[1] * 2]; val by = nodes[tri[1] * 2 + 1]
            val qx = nodes[tri[2] * 2]; val qy = nodes[tri[2] * 2 + 1]
            val den = (by - qy) * (ax - qx) + (qx - bx) * (ay - qy)
            if (abs(den) < 1e-12f) continue
            val l0 = ((by - qy) * (x - qx) + (qx - bx) * (y - qy)) / den
            val l1 = ((qy - ay) * (x - qx) + (ax - qx) * (y - qy)) / den
            val l2 = 1f - l0 - l1
            val e = -1e-5f
            if (l0 < e || l1 < e || l2 < e) continue
            var u = 0f; var v = 0f
            val ls = floatArrayOf(l0, l1, l2)
            for (k in 0 until 3) {
                u += ls[k] * (tri[k] % n) / tess
                v += ls[k] * (tri[k] / n) / tess
            }
            out[0] = src.x0 + u * src.width
            out[1] = src.y0 + v * src.height
            return true
        }
        return false
    }

    override fun dstBounds(src: IntRect): IntRect = bounds

    /** Node indices of mesh triangle [t], or null if a node has no image. */
    private fun triangle(t: Int): IntArray? {
        val n = tess + 1
        val q = t / 2
        val i = q % tess; val j = q / tess
        val a = j * n + i; val b = a + 1; val c = a + n; val d = c + 1
        val tri = if (t % 2 == 0) intArrayOf(a, b, c) else intArrayOf(b, d, c)
        return if (valid[tri[0]] && valid[tri[1]] && valid[tri[2]]) tri else null
    }

    /** Copy with control points [newCtrl]. */
    fun withCtrl(newCtrl: FloatArray) = BezierWarp(pre, newCtrl, src, tess)

    companion object {
        /** Control points reproducing [pre]: the uniform 4×4 grid over [src] mapped through it. */
        fun initialCtrl(pre: Projective, src: IntRect): FloatArray {
            val out = FloatArray(32)
            val p = FloatArray(2)
            for (j in 0 until 4) for (i in 0 until 4) {
                pre.forward(src.x0 + src.width * i / 3f, src.y0 + src.height * j / 3f, p)
                out[(j * 4 + i) * 2] = p[0]; out[(j * 4 + i) * 2 + 1] = p[1]
            }
            return out
        }

        /** Cubic Bernstein basis at [t]. */
        fun bernstein(t: Float): FloatArray {
            val s = 1f - t
            return floatArrayOf(s * s * s, 3 * t * s * s, 3 * t * t * s, t * t * t)
        }

        /**
         * Control points after dragging the patch point at ([u], [v]) by ([dx], [dy]): the minimum-norm
         * change of the control points weighted by their Bernstein weights, so that point moves exactly.
         */
        fun drag(ctrl: FloatArray, u: Float, v: Float, dx: Float, dy: Float): FloatArray {
            val bu = bernstein(u.coerceIn(0f, 1f)); val bv = bernstein(v.coerceIn(0f, 1f))
            var sum2 = 0f
            for (j in 0 until 4) for (i in 0 until 4) { val w = bu[i] * bv[j]; sum2 += w * w }
            val out = ctrl.copyOf()
            if (sum2 <= 0f) return out
            for (j in 0 until 4) for (i in 0 until 4) {
                val k = (j * 4 + i) * 2
                val w = bu[i] * bv[j] / sum2
                out[k] += w * dx; out[k + 1] += w * dy
            }
            return out
        }
    }
}
