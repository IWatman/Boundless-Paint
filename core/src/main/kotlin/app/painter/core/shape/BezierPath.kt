package app.painter.core.shape

import app.painter.core.geom.Vec2

/** How a path node shapes its tangents (spec G2.3). */
enum class NodeType {
    /** Sharp corner: straight edges, handles ignored. */
    CUSP,

    /** Automatic Catmull-Rom tangents, tension 0.5. */
    SMOOTH,

    /** Explicit, user-dragged tangents [PathNode.inHandle] / [PathNode.outHandle]. */
    ALIGNED,
}

/** One path node; handles are offsets from [p]. */
data class PathNode(
    val p: Vec2,
    val type: NodeType = NodeType.CUSP,
    val inHandle: Vec2 = Vec2(0f, 0f),
    val outHandle: Vec2 = Vec2(0f, 0f),
)

/** One cubic Bézier segment. */
data class Cubic(val p0: Vec2, val c1: Vec2, val c2: Vec2, val p3: Vec2) {
    fun at(t: Float): Vec2 {
        val u = 1 - t
        val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
        return Vec2(a * p0.x + b * c1.x + c * c2.x + d * p3.x, a * p0.y + b * c1.y + c * c2.y + d * p3.y)
    }
}

/** Bézier path shared by the Pen guide and the Path shape. */
data class BezierPath(val nodes: List<PathNode>, val closed: Boolean = false) {

    /** Effective (in, out) handles of node [i] after resolving [NodeType.SMOOTH] and [NodeType.CUSP]. */
    fun handles(i: Int): Pair<Vec2, Vec2> {
        val n = nodes[i]
        return when (n.type) {
            NodeType.CUSP -> ZERO to ZERO
            NodeType.ALIGNED -> n.inHandle to n.outHandle
            NodeType.SMOOTH -> {
                val prev = neighbor(i, -1)
                val next = neighbor(i, +1)
                val t = when {
                    prev != null && next != null -> (next - prev) * (1f / 6f)
                    next != null -> (next - n.p) * (1f / 3f)
                    prev != null -> (n.p - prev) * (1f / 3f)
                    else -> ZERO
                }
                -t to t
            }
        }
    }

    private fun neighbor(i: Int, d: Int): Vec2? {
        val j = i + d
        return when {
            j in nodes.indices -> nodes[j].p
            closed && nodes.size > 2 -> nodes[(j + nodes.size) % nodes.size].p
            else -> null
        }
    }

    /** Segments in order; a closed path includes the last→first segment. */
    fun segments(): List<Cubic> {
        if (nodes.size < 2) return emptyList()
        val count = if (closed) nodes.size else nodes.size - 1
        return (0 until count).map { i ->
            val j = (i + 1) % nodes.size
            Cubic(nodes[i].p, nodes[i].p + handles(i).second, nodes[j].p + handles(j).first, nodes[j].p)
        }
    }

    /**
     * Polyline within [tolerance] px of the curve. For a closed path the first point is not repeated
     * at the end.
     */
    fun flatten(tolerance: Float = DEFAULT_TOLERANCE): List<Vec2> {
        if (nodes.isEmpty()) return emptyList()
        val out = ArrayList<Vec2>()
        out.add(nodes[0].p)
        for (s in segments()) flattenCubic(s, tolerance, out, 0)
        if (closed && out.size > 1 && out.last().dist(out.first()) < 1e-4f) out.removeAt(out.size - 1)
        return out
    }

    companion object {
        const val DEFAULT_TOLERANCE = 0.25f
        private val ZERO = Vec2(0f, 0f)

        /** Path through [points] made only of cusp nodes. */
        fun polyline(points: List<Vec2>, closed: Boolean = false) =
            BezierPath(points.map { PathNode(it) }, closed)

        internal fun flattenCubic(c: Cubic, tol: Float, out: MutableList<Vec2>, depth: Int) {
            val d1 = distToSegment(c.c1, c.p0, c.p3)
            val d2 = distToSegment(c.c2, c.p0, c.p3)
            // Control-polygon deviation bounds curve deviation by 3/4 of it.
            if (depth >= 18 || 0.75f * maxOf(d1, d2) <= tol) {
                out.add(c.p3); return
            }
            val p01 = lerp(c.p0, c.c1, 0.5f); val p12 = lerp(c.c1, c.c2, 0.5f); val p23 = lerp(c.c2, c.p3, 0.5f)
            val a = lerp(p01, p12, 0.5f); val b = lerp(p12, p23, 0.5f); val m = lerp(a, b, 0.5f)
            flattenCubic(Cubic(c.p0, p01, a, m), tol, out, depth + 1)
            flattenCubic(Cubic(m, b, p23, c.p3), tol, out, depth + 1)
        }
    }
}

/** Polyline with a cumulative arc-length table. [closed] adds the last→first edge to the length. */
class ArcPolyline(val points: List<Vec2>, val closed: Boolean) {
    /** `cum[i]` = arc length at vertex i; `cum[segmentCount]` = total length. */
    val cum: FloatArray
    val segmentCount: Int = if (points.size < 2) 0 else if (closed) points.size else points.size - 1
    val length: Float

    init {
        cum = FloatArray(segmentCount + 1)
        for (i in 0 until segmentCount) cum[i + 1] = cum[i] + points[i].dist(points[(i + 1) % points.size])
        length = cum[segmentCount]
    }

    fun segA(i: Int) = points[i]
    fun segB(i: Int) = points[(i + 1) % points.size]

    /** Point at arc length [s] (wrapped on a closed polyline, clamped on an open one). */
    fun at(s: Float): Vec2 {
        if (segmentCount == 0) return points.firstOrNull() ?: Vec2(0f, 0f)
        val ss = if (closed) ((s % length) + length) % length else s.coerceIn(0f, length)
        var lo = 0; var hi = segmentCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cum[mid] <= ss) lo = mid else hi = mid - 1
        }
        val segLen = cum[lo + 1] - cum[lo]
        val t = if (segLen > 0f) (ss - cum[lo]) / segLen else 0f
        return lerp(segA(lo), segB(lo), t)
    }
}
