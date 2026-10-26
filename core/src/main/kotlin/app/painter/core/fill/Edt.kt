package app.painter.core.fill

import app.painter.core.raster.BitPlane
import app.painter.core.raster.IntRect
import app.painter.core.raster.parallelFor
import kotlin.math.sqrt

/**
 * Exact Euclidean distance transform (Felzenszwalb–Huttenlocher): 1D lower envelopes of parabolas,
 * columns first, then rows, both passes parallel over lines (spec §4.2). Distances are between pixel centers.
 */
object Edt {
    /** Squared distance for pixels with no feature in the area. */
    const val INF = 1e20f

    /**
     * Signed distance over [area] (row-major, `area.width × area.height`): outside [r] it is
     * `d_in − 0.5` (distance to the nearest pixel of R), inside it is `−(d_out − 0.5)`. Pixels outside
     * [area] are not seen, so the area should include a margin around R.
     */
    fun signed(r: BitPlane, area: IntRect): FloatArray {
        val dIn = distSq(area) { x, y -> r[x, y] }
        val dOut = distSq(area) { x, y -> !r[x, y] }
        val w = area.width
        for (i in dIn.indices) {
            val x = area.x0 + i % w
            val y = area.y0 + i / w
            dIn[i] = if (r[x, y]) -(sqrt(dOut[i]) - 0.5f) else sqrt(dIn[i]) - 0.5f
        }
        return dIn
    }

    /** Squared distance from every pixel of [area] to the nearest pixel of [area] where [feature] holds. */
    fun distSq(area: IntRect, feature: (x: Int, y: Int) -> Boolean): FloatArray {
        val w = area.width
        val h = area.height
        val f = FloatArray(w * h)
        parallelFor(h) { y ->
            val row = y * w
            for (x in 0 until w) f[row + x] = if (feature(area.x0 + x, area.y0 + y)) 0f else INF
        }
        parallelFor(w) { x ->
            val s = Scratch(h)
            var any = false
            for (y in 0 until h) { val v = f[y * w + x]; s.f[y] = v; if (v == 0f) any = true }
            if (any) {
                transform1d(s, h)
                for (y in 0 until h) f[y * w + x] = s.d[y]
            }
        }
        parallelFor(h) { y ->
            val s = Scratch(w)
            val row = y * w
            var any = false
            for (x in 0 until w) { val v = f[row + x]; s.f[x] = v; if (v < INF) any = true }
            if (any) {
                transform1d(s, w)
                for (x in 0 until w) f[row + x] = s.d[x]
            }
        }
        return f
    }

    private class Scratch(n: Int) {
        val f = FloatArray(n)
        val d = FloatArray(n)
        val v = IntArray(n)
        val z = DoubleArray(n + 1)
    }

    private fun transform1d(s: Scratch, n: Int) {
        val f = s.f; val v = s.v; val z = s.z
        var k = 0
        v[0] = 0
        z[0] = Double.NEGATIVE_INFINITY
        z[1] = Double.POSITIVE_INFINITY
        for (q in 1 until n) {
            var sv = intersect(f, q, v[k])
            while (sv <= z[k]) {
                k--
                sv = intersect(f, q, v[k])
            }
            k++
            v[k] = q
            z[k] = sv
            z[k + 1] = Double.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = (q - v[k]).toDouble()
            s.d[q] = (dq * dq + f[v[k]]).toFloat().coerceAtMost(INF)
        }
    }

    private fun intersect(f: FloatArray, q: Int, p: Int): Double {
        val fq = f[q].toDouble(); val fp = f[p].toDouble()
        return ((fq + q.toDouble() * q) - (fp + p.toDouble() * p)) / (2.0 * (q - p))
    }
}
