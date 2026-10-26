package app.painter.core.shape

import app.painter.core.geom.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sqrt

/** Least-squares fits used by shape recognition (public formulations, see spec G3.4). */
internal object Fitting {

    /** Total-least-squares line: centroid and unit principal direction (PCA). */
    fun pcaLine(pts: List<Vec2>): Pair<Vec2, Vec2> {
        var mx = 0.0; var my = 0.0
        for (p in pts) { mx += p.x; my += p.y }
        mx /= pts.size; my /= pts.size
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (p in pts) {
            val dx = p.x - mx; val dy = p.y - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        val theta = 0.5 * atan2(2 * sxy, sxx - syy)
        return Vec2(mx.toFloat(), my.toFloat()) to Vec2(cos(theta).toFloat(), kotlin.math.sin(theta).toFloat())
    }

    /** Kåsa algebraic circle fit; null when degenerate. */
    fun kasaCircle(pts: List<Vec2>): Pair<Vec2, Float>? {
        if (pts.size < 3) return null
        val (ox, oy, s) = frame(pts)
        val m = Array(3) { DoubleArray(3) }
        val v = DoubleArray(3)
        for (p in pts) {
            val x = (p.x - ox) / s; val y = (p.y - oy) / s
            val z = -(x * x + y * y)
            val row = doubleArrayOf(x, y, 1.0)
            for (i in 0..2) {
                for (j in 0..2) m[i][j] += row[i] * row[j]
                v[i] += row[i] * z
            }
        }
        val sol = solve3(m, v) ?: return null
        val cx = -sol[0] / 2; val cy = -sol[1] / 2
        val r2 = cx * cx + cy * cy - sol[2]
        if (r2 <= 0) return null
        return Vec2((ox + cx * s).toFloat(), (oy + cy * s).toFloat()) to (sqrt(r2) * s).toFloat()
    }

    /** Ellipse as (centre, rx ≥ ry, ry, rotation of rx). */
    data class EllipseFit(val c: Vec2, val rx: Float, val ry: Float, val rot: Float)

    /** Direct least-squares ellipse fit (Fitzgibbon et al. 1999, Halíř–Flusser formulation). */
    fun fitzgibbon(pts: List<Vec2>): EllipseFit? {
        if (pts.size < 6) return null
        val (ox, oy, s) = frame(pts)
        val s1 = Array(3) { DoubleArray(3) }
        val s2 = Array(3) { DoubleArray(3) }
        val s3 = Array(3) { DoubleArray(3) }
        for (p in pts) {
            val x = (p.x - ox) / s; val y = (p.y - oy) / s
            val d1 = doubleArrayOf(x * x, x * y, y * y)
            val d2 = doubleArrayOf(x, y, 1.0)
            for (i in 0..2) for (j in 0..2) {
                s1[i][j] += d1[i] * d1[j]; s2[i][j] += d1[i] * d2[j]; s3[i][j] += d2[i] * d2[j]
            }
        }
        val s3inv = inv3(s3) ?: return null
        // T = −S3⁻¹ S2ᵀ
        val t = Array(3) { i -> DoubleArray(3) { j -> -(0..2).sumOf { k -> s3inv[i][k] * s2[j][k] } } }
        val m = Array(3) { i -> DoubleArray(3) { j -> s1[i][j] + (0..2).sumOf { k -> s2[i][k] * t[k][j] } } }
        val mm = arrayOf(
            DoubleArray(3) { m[2][it] / 2 },
            DoubleArray(3) { -m[1][it] },
            DoubleArray(3) { m[0][it] / 2 },
        )
        var best: DoubleArray? = null
        for (lambda in realEigenvalues(mm)) {
            val vec = nullVector(mm, lambda) ?: continue
            val cond = 4 * vec[0] * vec[2] - vec[1] * vec[1]
            if (cond > 0) { best = vec; break }
        }
        val a1 = best ?: return null
        val a2 = DoubleArray(3) { i -> (0..2).sumOf { k -> t[i][k] * a1[k] } }
        val e = conicToEllipse(a1[0], a1[1], a1[2], a2[0], a2[1], a2[2]) ?: return null
        return EllipseFit(
            Vec2((ox + e[0] * s).toFloat(), (oy + e[1] * s).toFloat()),
            (e[2] * s).toFloat(), (e[3] * s).toFloat(), e[4].toFloat(),
        )
    }

    /** Conic A x² + B xy + C y² + D x + E y + F = 0 → [cx, cy, rx, ry, rot] with rx ≥ ry. */
    fun conicToEllipse(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double): DoubleArray? {
        val det = 4 * a * c - b * b
        if (det <= 0) return null
        val cx = (b * e - 2 * c * d) / det
        val cy = (b * d - 2 * a * e) / det
        val th = 0.5 * atan2(b, a - c)
        val cs = cos(th); val sn = kotlin.math.sin(th)
        val f0 = a * cx * cx + b * cx * cy + c * cy * cy + d * cx + e * cy + f
        val ap = a * cs * cs + b * cs * sn + c * sn * sn
        val cp = a * sn * sn - b * cs * sn + c * cs * cs
        val qa = -f0 / ap; val qc = -f0 / cp
        if (qa <= 0 || qc <= 0) return null
        var rx = sqrt(qa); var ry = sqrt(qc); var rot = th
        if (ry > rx) { val tmp = rx; rx = ry; ry = tmp; rot += PI / 2 }
        return doubleArrayOf(cx, cy, rx, ry, rot)
    }

    /** Centroid and scale used to condition the normal equations. */
    private fun frame(pts: List<Vec2>): Triple<Double, Double, Double> {
        var mx = 0.0; var my = 0.0
        for (p in pts) { mx += p.x; my += p.y }
        mx /= pts.size; my /= pts.size
        var s = 0.0
        for (p in pts) s = maxOf(s, abs(p.x - mx), abs(p.y - my))
        return Triple(mx, my, if (s < 1e-9) 1.0 else s)
    }

    private fun det3(m: Array<DoubleArray>) =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

    fun inv3(m: Array<DoubleArray>): Array<DoubleArray>? {
        val d = det3(m)
        if (abs(d) < 1e-14) return null
        val r = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2) {
            val a = m[(j + 1) % 3][(i + 1) % 3] * m[(j + 2) % 3][(i + 2) % 3]
            val b = m[(j + 1) % 3][(i + 2) % 3] * m[(j + 2) % 3][(i + 1) % 3]
            r[i][j] = (a - b) / d
        }
        return r
    }

    fun solve3(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
        val inv = inv3(m) ?: return null
        return DoubleArray(3) { i -> (0..2).sumOf { k -> inv[i][k] * v[k] } }
    }

    /** Real roots of the characteristic polynomial of a 3×3 matrix. */
    private fun realEigenvalues(m: Array<DoubleArray>): List<Double> {
        val tr = m[0][0] + m[1][1] + m[2][2]
        val c1 = m[0][0] * m[1][1] - m[0][1] * m[1][0] + m[0][0] * m[2][2] - m[0][2] * m[2][0] +
            m[1][1] * m[2][2] - m[1][2] * m[2][1]
        val det = det3(m)
        // λ³ − tr·λ² + c1·λ − det = 0; depress with λ = t + tr/3.
        val a = -tr; val b = c1; val c = -det
        val p = b - a * a / 3
        val q = 2 * a * a * a / 27 - a * b / 3 + c
        val shift = -a / 3
        val disc = q * q / 4 + p * p * p / 27
        return if (disc > 0) {
            val sq = sqrt(disc)
            listOf(cbrt(-q / 2 + sq) + cbrt(-q / 2 - sq) + shift)
        } else {
            val r = sqrt(-p / 3)
            val phi = acos((3 * q / (2 * p) * sqrt(-3 / p)).coerceIn(-1.0, 1.0)) / 3
            (0..2).map { k -> 2 * r * cos(phi - 2 * PI * k / 3) + shift }
        }
    }

    /** Null vector of (m − λI) from the best-conditioned cross product of two rows. */
    private fun nullVector(m: Array<DoubleArray>, lambda: Double): DoubleArray? {
        val r = Array(3) { i -> DoubleArray(3) { j -> m[i][j] - if (i == j) lambda else 0.0 } }
        var best: DoubleArray? = null
        var bestN = 0.0
        for ((i, j) in listOf(0 to 1, 0 to 2, 1 to 2)) {
            val a = r[i]; val b = r[j]
            val v = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (n > bestN) { bestN = n; best = v }
        }
        if (best == null || bestN < 1e-300) return null
        return DoubleArray(3) { best[it] / bestN }
    }

    /** Intersection of lines p1 + t·d1 and p2 + u·d2, or null when (nearly) parallel. */
    fun intersect(p1: Vec2, d1: Vec2, p2: Vec2, d2: Vec2): Vec2? {
        val den = d1 cross d2
        if (abs(den) < 1e-6f) return null
        val t = ((p2 - p1) cross d2) / den
        return p1 + d1 * t
    }
}

/**
 * Cubic Bézier curve fitting (Schneider, "An Algorithm for Automatically Fitting Digitized Curves",
 * Graphics Gems 1990): least-squares control lengths, Newton reparameterisation, split at max error.
 */
internal object CurveFit {
    private const val MAX_ITER = 4

    /** Fits [pts] with cubic segments within [tolerance] px. */
    fun fit(pts: List<Vec2>, tolerance: Float): List<Cubic> {
        val clean = ArrayList<Vec2>(pts.size)
        for (p in pts) if (clean.isEmpty() || clean.last().dist(p) > 1e-4f) clean.add(p)
        if (clean.size < 2) return emptyList()
        val out = ArrayList<Cubic>()
        val t1 = (clean[1] - clean[0]).normalized()
        val t2 = (clean[clean.size - 2] - clean.last()).normalized()
        fitCubic(clean, 0, clean.size - 1, t1, t2, tolerance * tolerance, out, 0)
        return out
    }

    private fun fitCubic(p: List<Vec2>, first: Int, last: Int, tHat1: Vec2, tHat2: Vec2, err2: Float, out: MutableList<Cubic>, depth: Int) {
        val n = last - first + 1
        if (n == 2 || depth > 24) {
            val d = p[last].dist(p[first]) / 3f
            out.add(Cubic(p[first], p[first] + tHat1 * d, p[last] + tHat2 * d, p[last])); return
        }
        var u = chordParams(p, first, last)
        var bez = generate(p, first, last, u, tHat1, tHat2)
        var (maxErr, split) = maxError(p, first, last, bez, u)
        if (maxErr < err2) { out.add(bez); return }
        if (maxErr < err2 * 4) {
            repeat(MAX_ITER) {
                u = reparameterize(p, first, last, u, bez)
                bez = generate(p, first, last, u, tHat1, tHat2)
                val r = maxError(p, first, last, bez, u)
                maxErr = r.first; split = r.second
                if (maxErr < err2) { out.add(bez); return }
            }
        }
        split = split.coerceIn(first + 1, last - 1)
        val center = (p[split - 1] - p[split + 1]).normalized()
        fitCubic(p, first, split, tHat1, center, err2, out, depth + 1)
        fitCubic(p, split, last, -center, tHat2, err2, out, depth + 1)
    }

    private fun chordParams(p: List<Vec2>, first: Int, last: Int): FloatArray {
        val u = FloatArray(last - first + 1)
        for (i in first + 1..last) u[i - first] = u[i - first - 1] + p[i].dist(p[i - 1])
        val total = u.last()
        if (total > 0f) for (i in u.indices) u[i] /= total
        return u
    }

    private fun generate(p: List<Vec2>, first: Int, last: Int, u: FloatArray, t1: Vec2, t2: Vec2): Cubic {
        var c00 = 0.0; var c01 = 0.0; var c11 = 0.0; var x0 = 0.0; var x1 = 0.0
        val p0 = p[first]; val p3 = p[last]
        for (i in u.indices) {
            val t = u[i]; val s = 1 - t
            val b0 = s * s * s; val b1 = 3 * t * s * s; val b2 = 3 * t * t * s; val b3 = t * t * t
            val a1 = t1 * b1; val a2 = t2 * b2
            c00 += (a1 dot a1); c01 += (a1 dot a2); c11 += (a2 dot a2)
            val tmp = p[first + i] - (p0 * (b0 + b1) + p3 * (b2 + b3))
            x0 += (a1 dot tmp); x1 += (a2 dot tmp)
        }
        val det = c00 * c11 - c01 * c01
        var alpha1 = if (abs(det) > 1e-12) (x0 * c11 - c01 * x1) / det else 0.0
        var alpha2 = if (abs(det) > 1e-12) (c00 * x1 - c01 * x0) / det else 0.0
        val segLen = p3.dist(p0)
        val eps = 1e-6 * segLen
        if (alpha1 < eps || alpha2 < eps) { alpha1 = segLen / 3.0; alpha2 = segLen / 3.0 }
        return Cubic(p0, p0 + t1 * alpha1.toFloat(), p3 + t2 * alpha2.toFloat(), p3)
    }

    private fun maxError(p: List<Vec2>, first: Int, last: Int, bez: Cubic, u: FloatArray): Pair<Float, Int> {
        var maxD = 0f
        var split = (last - first + 1) / 2 + first
        for (i in first + 1 until last) {
            val q = bez.at(u[i - first])
            val d = (q - p[i]) dot (q - p[i])
            if (d >= maxD) { maxD = d; split = i }
        }
        return maxD to split
    }

    private fun reparameterize(p: List<Vec2>, first: Int, last: Int, u: FloatArray, bez: Cubic): FloatArray =
        FloatArray(u.size) { i -> newton(bez, p[first + i], u[i]) }

    private fun newton(b: Cubic, p: Vec2, t: Float): Float {
        val q = b.at(t)
        val d1 = derivative(b, t)
        val d2 = second(b, t)
        val num = (q - p) dot d1
        val den = (d1 dot d1) + ((q - p) dot d2)
        if (abs(den) < 1e-12f) return t
        return (t - num / den).coerceIn(0f, 1f)
    }

    private fun derivative(b: Cubic, t: Float): Vec2 {
        val s = 1 - t
        return (b.c1 - b.p0) * (3 * s * s) + (b.c2 - b.c1) * (6 * s * t) + (b.p3 - b.c2) * (3 * t * t)
    }

    private fun second(b: Cubic, t: Float): Vec2 {
        val s = 1 - t
        return (b.c2 - b.c1 * 2f + b.p0) * (6 * s) + (b.p3 - b.c2 * 2f + b.c1) * (6 * t)
    }
}
