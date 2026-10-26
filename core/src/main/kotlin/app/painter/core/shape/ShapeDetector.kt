package app.painter.core.shape

import app.painter.core.geom.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Recognised figure kinds (spec G3.4); each has its own priority in Settings → Shapes. */
enum class ShapeKind { LINE, ARC, CIRCLE, ELLIPSE, TRIANGLE, RECT, QUAD, POLYGON, POLYLINE, CURVE }

/**
 * @param screenToDoc 1/zoom, converts the 24 screen px minimum length.
 * @param priorities 0.5–2 per kind (default 1); higher makes a kind win more often.
 */
data class DetectOptions(
    val screenToDoc: Float = 1f,
    val priorities: Map<ShapeKind, Float> = emptyMap(),
) {
    fun priority(k: ShapeKind) = (priorities[k] ?: 1f).coerceIn(0.5f, 2f)
}

/** One recognition result; [error] = RMS distance / bbox diagonal, lower [score] is better. */
data class Candidate(val kind: ShapeKind, val shape: Shape, val error: Float, val score: Float)

/**
 * Freehand stroke → shape candidates (spec G3.4): arc-length resampling, closure test, ShortStraw
 * corners (Wolin et al. 2008) with a sharp-turn significance filter (smooth bends are not corners), then per-kind fits (PCA line,
 * Kåsa arc, Fitzgibbon ellipse, polygons from side lines) ranked by `e / maxE / priority`. A Schneider
 * Bézier curve is always appended last as the fallback.
 */
object ShapeDetector {
    const val MIN_LENGTH_SCREEN_PX = 24f
    const val MAX_RESULTS = 5
    private const val MIN_SAMPLES = 32
    private const val MAX_SAMPLES = 256
    private const val STRAW_W = 3
    private const val CORNER_MIN_TURN_DEG = 30f
    private const val CORNER_SHARPNESS = 0.7f

    private val MAX_E = mapOf(
        ShapeKind.LINE to 0.025f, ShapeKind.ARC to 0.03f, ShapeKind.CIRCLE to 0.035f, ShapeKind.ELLIPSE to 0.035f,
        ShapeKind.TRIANGLE to 0.03f, ShapeKind.RECT to 0.03f, ShapeKind.QUAD to 0.03f, ShapeKind.POLYGON to 0.03f,
        ShapeKind.POLYLINE to 0.025f,
    )

    /** Candidates best first (at most [MAX_RESULTS], Curve last); empty for strokes under 24 screen px. */
    fun detect(points: List<Vec2>, opts: DetectOptions = DetectOptions()): List<Candidate> {
        if (points.size < 2) return emptyList()
        val length = pathLength(points, false)
        if (length < MIN_LENGTH_SCREEN_PX * opts.screenToDoc) return emptyList()
        val diag = bboxDiag(points)
        if (diag <= 0f) return emptyList()
        val closed = points.last().dist(points.first()) < 0.2f * diag
        val n = (length / (diag / 40f)).toInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
        val s = resample(points, n, closed)
        val corners = corners(s, closed)

        val found = ArrayList<Pair<ShapeKind, Shape>>()
        if (!closed) {
            fitLine(s, points, length)?.let { found += ShapeKind.LINE to it }
            if (corners.isEmpty()) fitArc(s, diag)?.let { found += ShapeKind.ARC to it }
            if (corners.isNotEmpty()) {
                found += ShapeKind.POLYLINE to Shape.Path(BezierPath.polyline(listOf(s.first()) + corners.map { s[it] } + s.last()))
            }
        } else {
            fitEllipse(s)?.let { found += it }
            if (corners.size in 3..8) found += fitPolygons(s, corners)
        }
        val accepted = found.mapNotNull { (kind, shape) ->
            val e = rmsError(s, shape) / diag
            val maxE = MAX_E.getValue(kind)
            if (e <= maxE) Candidate(kind, shape, e, e / maxE / opts.priority(kind)) else null
        }.sortedBy { it.score }.take(MAX_RESULTS - 1)
        val curve = fitCurve(s, closed, diag)
        val curveCand = Candidate(ShapeKind.CURVE, curve, rmsError(s, curve) / diag, Float.MAX_VALUE)
        return accepted + curveCand
    }

    private fun fitLine(s: List<Vec2>, raw: List<Vec2>, length: Float): Shape? {
        val chord = raw.last().dist(raw.first())
        if (chord / length < 0.9f) return null
        val (c, d) = Fitting.pcaLine(s)
        fun proj(p: Vec2) = c + d * ((p - c) dot d)
        return Shape.Line(proj(raw.first()), proj(raw.last()))
    }

    private fun fitArc(s: List<Vec2>, diag: Float): Shape? {
        val (c, r) = Fitting.kasaCircle(s) ?: return null
        if (r >= 4 * diag) return null
        var sweep = 0.0
        var prev = atan2((s[0].y - c.y).toDouble(), (s[0].x - c.x).toDouble())
        for (i in 1 until s.size) {
            val a = atan2((s[i].y - c.y).toDouble(), (s[i].x - c.x).toDouble())
            sweep += wrapPi(a - prev); prev = a
        }
        val deg = abs(sweep) * 180 / PI
        if (deg < 20 || deg > 330) return null
        val a0 = atan2((s[0].y - c.y).toDouble(), (s[0].x - c.x).toDouble())
        fun at(a: Double) = Vec2((c.x + r * cos(a)).toFloat(), (c.y + r * sin(a)).toFloat())
        return Shape.Arc(at(a0), at(a0 + sweep), at(a0 + sweep / 2))
    }

    private fun fitEllipse(s: List<Vec2>): Pair<ShapeKind, Shape>? {
        val e = Fitting.fitzgibbon(s)
        if (e == null || e.ry <= 0f) {
            val (c, r) = Fitting.kasaCircle(s) ?: return null
            return ShapeKind.CIRCLE to Shape.Ellipse(c, r, r)
        }
        if (e.ry / e.rx >= 0.88f) {
            val r = (e.rx + e.ry) / 2
            return ShapeKind.CIRCLE to Shape.Ellipse(e.c, r, r)
        }
        return ShapeKind.ELLIPSE to Shape.Ellipse(e.c, e.rx, e.ry, e.rot)
    }

    private fun fitPolygons(s: List<Vec2>, corners: List<Int>): List<Pair<ShapeKind, Shape>> {
        val out = ArrayList<Pair<ShapeKind, Shape>>()
        val v = sideLineVertices(s, corners)
        val k = v.size
        val angles = (0 until k).map { interiorAngleDeg(v[(it + k - 1) % k], v[it], v[(it + 1) % k]) }
        when (k) {
            3 -> out += ShapeKind.TRIANGLE to Shape.Path(BezierPath.polyline(v, closed = true))
            4 -> {
                out += ShapeKind.QUAD to Shape.Path(BezierPath.polyline(v, closed = true))
                if (angles.all { abs(it - 90f) <= 15f }) out += ShapeKind.RECT to rectFrom(v)
            }
            else -> {
                val sides = (0 until k).map { v[it].dist(v[(it + 1) % k]) }
                val mean = sides.average().toFloat()
                val cv = sqrt(sides.map { (it - mean) * (it - mean) }.average()).toFloat() / mean
                val ideal = (k - 2) * 180f / k
                if (cv < 0.2f && angles.all { abs(it - ideal) <= 15f }) {
                    val c = Vec2(v.map { it.x }.average().toFloat(), v.map { it.y }.average().toFloat())
                    val r = v.map { it.dist(c) }.average().toFloat()
                    out += ShapeKind.POLYGON to Shape.Polygon(c, r, k, (v[0] - c).angle)
                }
            }
        }
        return out
    }

    private fun rectFrom(v: List<Vec2>): Shape.Rect {
        // Mean side direction mod 90° via the 4θ circular mean.
        var sx = 0.0; var sy = 0.0
        for (i in v.indices) {
            val d = v[(i + 1) % v.size] - v[i]
            val a = 4.0 * atan2(d.y.toDouble(), d.x.toDouble())
            sx += cos(a); sy += sin(a)
        }
        var rot = (atan2(sy, sx) / 4).toFloat()
        if (abs(rot) < 4f.toRad()) rot = 0f
        val c = Vec2(v.map { it.x }.average().toFloat(), v.map { it.y }.average().toFloat())
        val local = v.map { toLocal(c, rot, it) }
        val w = 2 * local.map { abs(it.x) }.average().toFloat()
        val h = 2 * local.map { abs(it.y) }.average().toFloat()
        return Shape.Rect(c, w, h, rot)
    }

    /** Polygon vertices as intersections of least-squares side lines between consecutive [corners]. */
    private fun sideLineVertices(s: List<Vec2>, corners: List<Int>): List<Vec2> {
        val n = s.size; val k = corners.size
        val lines = (0 until k).map { i ->
            val a = corners[i]; val b = corners[(i + 1) % k]
            val span = ((b - a) + n) % n
            val trim = max(1, span / 6)
            val pts = (trim..span - trim).map { s[(a + it) % n] }
            if (pts.size >= 2) Fitting.pcaLine(pts) else s[a] to (s[b] - s[a]).normalized()
        }
        return (0 until k).map { i ->
            val prev = lines[(i + k - 1) % k]; val cur = lines[i]
            Fitting.intersect(prev.first, prev.second, cur.first, cur.second)
                ?.takeIf { it.dist(s[corners[i]]) < bboxDiag(s) * 0.25f } ?: s[corners[i]]
        }
    }

    private fun interiorAngleDeg(a: Vec2, b: Vec2, c: Vec2): Float {
        val u = (a - b).normalized(); val w = (c - b).normalized()
        return acos((u dot w).coerceIn(-1f, 1f)).toDeg()
    }

    private fun fitCurve(s: List<Vec2>, closed: Boolean, diag: Float): Shape {
        val smooth = ArrayList<Vec2>(s.size + 1)
        val n = s.size
        for (i in 0 until n) {
            val p = s[i]
            val a = if (closed) s[(i + n - 1) % n] else s[max(0, i - 1)]
            val b = if (closed) s[(i + 1) % n] else s[min(n - 1, i + 1)]
            smooth.add(if (!closed && (i == 0 || i == n - 1)) p else Vec2((a.x + 2 * p.x + b.x) / 4, (a.y + 2 * p.y + b.y) / 4))
        }
        if (closed) smooth.add(smooth[0])
        val cubics = CurveFit.fit(smooth, 0.01f * diag)
        if (cubics.isEmpty()) return Shape.Path(BezierPath.polyline(s, closed))
        val nodes = ArrayList<PathNode>(cubics.size + 1)
        for (i in 0..cubics.size) {
            val p = if (i < cubics.size) cubics[i].p0 else cubics.last().p3
            val inH = if (i > 0) cubics[i - 1].c2 - p else Vec2(0f, 0f)
            val outH = if (i < cubics.size) cubics[i].c1 - p else Vec2(0f, 0f)
            nodes.add(PathNode(p, NodeType.ALIGNED, inH, outH))
        }
        if (closed) {
            val last = nodes.removeAt(nodes.size - 1)
            nodes[0] = nodes[0].copy(inHandle = last.inHandle)
        }
        return Shape.Path(BezierPath(nodes, closed))
    }

    // --- sampling and corners ---

    internal fun pathLength(p: List<Vec2>, closed: Boolean): Float {
        var l = 0f
        for (i in 1 until p.size) l += p[i].dist(p[i - 1])
        if (closed && p.size > 1) l += p.last().dist(p.first())
        return l
    }

    internal fun bboxDiag(p: List<Vec2>): Float {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (q in p) { x0 = min(x0, q.x); y0 = min(y0, q.y); x1 = max(x1, q.x); y1 = max(y1, q.y) }
        return sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
    }

    /** [n] points evenly spaced by arc length; a closed loop includes the closing edge and doesn't repeat p0. */
    internal fun resample(p: List<Vec2>, n: Int, closed: Boolean): List<Vec2> {
        val line = ArcPolyline(p, closed)
        val count = if (closed) n else n - 1
        return (0 until n).map { line.at(line.length * it / count) }
    }

    /** Corner indices into [s]; open strokes exclude the endpoints. */
    internal fun corners(s: List<Vec2>, closed: Boolean): List<Int> {
        val n = s.size
        val w = STRAW_W
        fun at(i: Int) = if (closed) s[((i % n) + n) % n] else s[i.coerceIn(0, n - 1)]
        val straw = FloatArray(n) { i -> if (closed || i in w until n - w) at(i - w).dist(at(i + w)) else Float.NaN }
        val valid = straw.filter { !it.isNaN() }.sorted()
        if (valid.isEmpty()) return emptyList()
        val threshold = 0.95f * valid[valid.size / 2]
        fun turn(i: Int, k: Int): Float {
            val a = at(i) - at(i - k); val b = at(i + k) - at(i)
            if (a.len < 1e-6f || b.len < 1e-6f) return 0f
            return acos((a.normalized() dot b.normalized()).coerceIn(-1f, 1f)).toDeg()
        }
        // A real corner turns sharply: most of the turn over ±2W already happens within ±W.
        fun significant(i: Int): Boolean {
            val t = turn(i, w)
            return t >= CORNER_MIN_TURN_DEG && t >= CORNER_SHARPNESS * turn(i, 2 * w)
        }
        val found = ArrayList<Int>()
        val range = if (closed) 0 until n else w until n - w
        var i = range.first
        while (i <= range.last) {
            if (!straw[i].isNaN() && straw[i] < threshold) {
                var best = i
                while (i <= range.last && !straw[i].isNaN() && straw[i] < threshold) {
                    if (straw[i] < straw[best]) best = i
                    i++
                }
                if (significant(best)) found.add(best)
            } else i++
        }
        // A run wrapping the seam of a closed loop may report the same corner twice.
        if (closed && found.size >= 2 && (found.first() + n - found.last()) <= 2 * w) {
            if (straw[found.first()] <= straw[found.last()]) found.removeAt(found.size - 1) else found.removeAt(0)
        }

        val list = if (closed) found.toMutableList() else (listOf(0) + found + (n - 1)).toMutableList()
        fun span(a: Int, b: Int) = if (closed) ((b - a) + n) % n else b - a
        fun isLine(a: Int, b: Int, thr: Float): Boolean {
            val m = span(a, b)
            if (m <= 1) return true
            var path = 0f
            for (k in 0 until m) path += at(a + k).dist(at(a + k + 1))
            return path <= 0f || at(a).dist(at(b)) / path >= thr
        }
        // Split segments that are not straight at their straw minimum (if it is a real corner).
        var guard = 0
        var changed = true
        while (changed && guard++ < 64) {
            changed = false
            val pairs = if (closed) list.size else list.size - 1
            for (k in 0 until pairs) {
                val a = list[k]; val b = list[(k + 1) % list.size]
                if (closed && list.size < 2) break
                val m = span(a, b)
                if (m < 4 || isLine(a, b, 0.95f)) continue
                val q = m / 4
                var best = -1
                for (t in q..m - q) {
                    val idx = (a + t) % n
                    if (straw[idx].isNaN()) continue
                    if (best < 0 || straw[idx] < straw[best]) best = idx
                }
                if (best >= 0 && best != a && best != b && significant(best) && best !in list) {
                    list.add(k + 1, best); changed = true; break
                }
            }
        }
        // Merge corners whose neighbours are joined by a nearly straight run.
        changed = true
        guard = 0
        while (changed && guard++ < 64) {
            changed = false
            val size = list.size
            val idxRange = if (closed) 0 until size else 1 until size - 1
            if (closed && size < 3) break
            for (k in idxRange) {
                val a = list[(k - 1 + size) % size]; val b = list[(k + 1) % size]
                if (isLine(a, b, 0.98f)) {
                    list.removeAt(k); changed = true; break
                }
            }
        }
        val result = if (closed) list.sorted() else list.subList(1, list.size - 1).toList()
        return result
    }

    private fun rmsError(s: List<Vec2>, shape: Shape): Float {
        val outline = shape.outline(0.5f)
        var sum = 0.0
        for (p in s) {
            val d = distToPolyline(p, outline, shape.closed)
            sum += d * d
        }
        return sqrt(sum / s.size).toFloat()
    }
}
