package app.painter.core.shape

import app.painter.core.geom.Vec2
import app.painter.core.raster.IntRect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Even-odd polygon coverage with 4×4 supersampling (spec G3.3). Rings are closed implicitly.
 * Callers rasterize per tile: pass the tile rect, get coverage 0..1 per pixel.
 */
object PolygonFill {
    private const val SS = 4

    /** Pixel bounds of [rings] (half-open), or null when empty. */
    fun bounds(rings: List<List<Vec2>>): IntRect? {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (r in rings) for (p in r) {
            x0 = min(x0, p.x); y0 = min(y0, p.y); x1 = max(x1, p.x); y1 = max(y1, p.y)
        }
        if (x0 > x1) return null
        return IntRect(floor(x0).toInt(), floor(y0).toInt(), ceil(x1).toInt() + 1, ceil(y1).toInt() + 1)
    }

    /**
     * Writes coverage of the [w]×[h] pixel block at ([x0], [y0]) into [out] (row-major, size ≥ w·h).
     * Sample (i, j) of pixel (x, y) is at `(x + (i + 0.5)/4, y + (j + 0.5)/4)`.
     */
    fun coverage(rings: List<List<Vec2>>, x0: Int, y0: Int, w: Int, h: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0, w * h, 0f)
        val xs = FloatArray(64)
        var crossings = xs
        val inv = 1f / (SS * SS)
        for (row in 0 until h * SS) {
            val sy = y0 + (row + 0.5f) / SS
            var n = 0
            for (ring in rings) {
                val m = ring.size
                if (m < 3) continue
                for (k in 0 until m) {
                    val a = ring[k]; val b = ring[(k + 1) % m]
                    if ((a.y <= sy) == (b.y <= sy)) continue
                    val x = a.x + (sy - a.y) / (b.y - a.y) * (b.x - a.x)
                    if (n == crossings.size) crossings = crossings.copyOf(n * 2)
                    crossings[n++] = x
                }
            }
            if (n < 2) continue
            java.util.Arrays.sort(crossings, 0, n)
            val py = row / SS
            val base = py * w
            var k = 0
            while (k + 1 < n) {
                // Sample columns c (global, in 1/4 px) with c + 0.5 inside [xa, xb).
                val ca = max(ceil((crossings[k] - x0) * SS - 0.5f).toInt(), 0)
                val cb = min(ceil((crossings[k + 1] - x0) * SS - 0.5f).toInt(), w * SS)
                var c = ca
                while (c < cb) {
                    val px = c / SS
                    val end = min(cb, (px + 1) * SS)
                    out[base + px] += (end - c) * inv
                    c = end
                }
                k += 2
            }
        }
    }
}
