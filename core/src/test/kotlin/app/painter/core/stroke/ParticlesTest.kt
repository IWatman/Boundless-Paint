package app.painter.core.stroke

import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.assertGridsEqual
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Particles (spec/tools/m2-brush.md §1.10): the path dabs are not drawn, they drive a particle system that stamps
 * the stroke with the particle head; the result is deterministic and a high overshoot splashes past a sharp turn.
 */
class ParticlesTest {
    /** Particle brush: the path dabs only feed the system, so nothing is drawn without it. */
    private fun brush(special: ParticlesParams, size: Float = 20f) = BrushParams(
        size = size, hardness = 0.6f, spacing = 0.1f, smoothing = 0f, opacity = 1f, flow = 1f, special = special,
    )

    /** Draws [points] with [params] and returns the pixels of the layer. */
    private fun render(
        params: BrushParams,
        points: List<InputPoint>,
        perPoint: Boolean = false,
        size: Int = 200,
        seed: Long = 7L,
    ): TileGrid {
        val doc = Document.create(size, size)
        val e = engine(doc, params, ctx = StrokeContext(threads = 1, seed = seed))
        drawOpen(e, points, perPoint)
        e.endPatch()
        return doc.activeRaster!!.pixels
    }

    /** Path through [vertices] resampled every [step] px, with a monotonic time stamp. */
    private fun polyline(vararg vertices: FloatArray, step: Float = 2f): List<InputPoint> {
        val out = ArrayList<InputPoint>()
        for (i in 0 until vertices.size - 1) {
            val a = vertices[i]
            val b = vertices[i + 1]
            val seg = line(a[0], a[1], b[0], b[1], step)
            out += if (i == 0) seg else seg.drop(1)
        }
        return out.mapIndexed { i, p -> p.copy(timeMs = i * 4L) }
    }

    /** Distance from `(px, py)` to the polyline through [vertices]. */
    private fun distance(px: Float, py: Float, vertices: Array<FloatArray>): Float {
        var min = Float.MAX_VALUE
        for (i in 0 until vertices.size - 1) {
            val ax = vertices[i][0]
            val ay = vertices[i][1]
            val dx = vertices[i + 1][0] - ax
            val dy = vertices[i + 1][1] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0f) (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f) else 0f
            val cx = ax + dx * t - px
            val cy = ay + dy * t - py
            val d = sqrt(cx * cx + cy * cy)
            if (d < min) min = d
        }
        return min
    }

    /** Farthest painted pixel from the polyline through [vertices]. */
    private fun maxDistance(px: TileGrid, vertices: Array<FloatArray>, size: Int = 200): Float {
        var max = 0f
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (alpha(px, x, y) > 0.02f) max = maxOf(max, distance(x + 0.5f, y + 0.5f, vertices))
            }
        }
        return max
    }

    /** A 90° turn: right along y = 50 to x = 120, then down to y = 160. */
    private val corner = arrayOf(floatArrayOf(40f, 50f), floatArrayOf(120f, 50f), floatArrayOf(120f, 160f))

    private fun cornerPath(): List<InputPoint> = polyline(*corner)

    @Test
    fun theResultIsDeterministic() {
        val special = ParticlesParams(attractors = 8, particles = 40, radius = 0.8f, size = 0.25f, overshoot = 0.5f)
        val path = cornerPath()
        val a = render(brush(special), path)
        val b = render(brush(special), path)
        assertGridsEqual(a, b, "two runs of the same stroke")
        // One batch and one point per call must stamp the same dabs: the state is keyed by the step index only.
        val c = render(brush(special), path, perPoint = true)
        assertGridsEqual(a, c, "batch vs points")
        assertTrue(maxDistance(a, corner) > 0f, "the particles must paint")
    }

    @Test
    fun overshootSplashesPastASharpTurn() {
        // The particle head is small, so the coverage outside the path is the spray of the particles.
        val base = ParticlesParams(attractors = 6, particles = 48, radius = 0.6f, size = 0.25f)
        val settled = maxDistance(render(brush(base.copy(overshoot = 0f)), cornerPath()), corner)
        val splashing = maxDistance(render(brush(base.copy(overshoot = 2f)), cornerPath()), corner)
        assertTrue(settled > 0f, "overshoot 0 must paint, max distance $settled")
        assertTrue(
            splashing > settled + 2f,
            "overshoot 2 must spray farther from the path: $splashing vs $settled",
        )
    }
}
