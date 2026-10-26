package app.painter.core.stroke

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Even-odd polygon coverage with 4×4 supersampling (4 sub-scanlines, 4 sub-columns per pixel). Used for
 * [FillSpec] of closed shapes.
 */
object PolygonFill {
    private const val SUB = 4

    /**
     * Calls [pixel] with (x, y, coverage 0..1] for every covered pixel inside [width]×[height]. Rows are visited
     * top to bottom. [xs]/[ys] are the vertices; the polygon closes itself.
     */
    fun rasterize(xs: FloatArray, ys: FloatArray, width: Int, height: Int, pixel: (Int, Int, Float) -> Unit) {
        val n = xs.size
        if (n < 3) return
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (i in 0 until n) {
            minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
            minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
        }
        val y0 = max(0, floor(minY).toInt())
        val y1 = min(height - 1, ceil(maxY).toInt())
        val x0 = max(0, floor(minX).toInt())
        val x1 = min(width - 1, ceil(maxX).toInt())
        if (y0 > y1 || x0 > x1) return
        val w = x1 - x0 + 1
        val acc = FloatArray(w)
        val cross = FloatArray(n)
        for (y in y0..y1) {
            acc.fill(0f)
            var any = false
            for (sub in 0 until SUB) {
                val sy = y + (sub + 0.5f) / SUB
                var c = 0
                for (i in 0 until n) {
                    val j = if (i + 1 == n) 0 else i + 1
                    val ya = ys[i]
                    val yb = ys[j]
                    if ((ya <= sy && yb > sy) || (yb <= sy && ya > sy)) {
                        cross[c++] = xs[i] + (sy - ya) / (yb - ya) * (xs[j] - xs[i])
                    }
                }
                if (c < 2) continue
                java.util.Arrays.sort(cross, 0, c)
                var k = 0
                while (k + 1 < c) {
                    val a = cross[k]
                    val b = cross[k + 1]
                    for (sx in 0 until SUB) {
                        val off = (sx + 0.5f) / SUB
                        val first = ceil(a - off).toInt().coerceAtLeast(x0)
                        val last = (ceil(b - off).toInt() - 1).coerceAtMost(x1)
                        for (x in first..last) acc[x - x0] += 1f / (SUB * SUB)
                        if (first <= last) any = true
                    }
                    k += 2
                }
            }
            if (!any) continue
            for (i in 0 until w) if (acc[i] > 0f) pixel(x0 + i, y, min(1f, acc[i]))
        }
    }
}
