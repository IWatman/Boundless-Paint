package app.painter.core.stroke

import app.painter.core.stroke.StrokeTestKit.circle
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathWalkerTest {
    private class Rec(val x: Float, val y: Float, val d: Float, val dx: Float, val dy: Float, val v: Float, val ending: Boolean)

    private fun walk(
        points: List<InputPoint>,
        step: Float,
        params: BrushParams = BrushParams(),
        interpolation: Interpolation = Interpolation.CATMULL_ROM,
        closed: Boolean = false,
    ): List<Rec> {
        val out = ArrayList<Rec>()
        val w = PathWalker(params, PathSink { s ->
            out.add(Rec(s.x, s.y, s.distance, s.dirX, s.dirY, s.velocity, s.ending)); step
        }, interpolation, closed)
        w.begin(points.first())
        points.drop(1).forEach(w::add)
        w.end()
        return out
    }

    @Test
    fun stepComesFromSink() {
        val r = walk(line(0f, 10f, 300f, 10f, 7f), 13f)
        for (i in 1 until r.size - 1) assertEquals(13f, r[i].d - r[i - 1].d, 0.01f)
    }

    @Test
    fun directionAndVelocityOnStraightLine() {
        // 4 px every 2 ms = 2 px/ms at zoom 1.
        val pts = line(0f, 50f, 400f, 50f, 4f, dtMs = 2L)
        val r = walk(pts, 5f)
        for (s in r.drop(1)) {
            assertEquals(1f, s.dx, 1e-4f)
            assertEquals(0f, s.dy, 1e-4f)
        }
        val late = r.drop(r.size / 2).dropLast(3)
        for (s in late) assertEquals(2f, s.v, 0.1f)
    }

    @Test
    fun directionFollowsCircleTangent() {
        val r = walk(circle(200f, 200f, 100f, 400), 4f)
        for (s in r.subList(20, r.size - 20)) {
            val rx = s.x - 200f
            val ry = s.y - 200f
            val len = sqrt(rx * rx + ry * ry)
            val dot = (rx * s.dx + ry * s.dy) / len
            assertTrue(abs(dot) < 0.08f, "tangent·radius = $dot")
            assertTrue(rx * s.dy - ry * s.dx > 0f, "counter-clockwise")
        }
    }

    @Test
    fun linearInterpolationHitsCorners() {
        val sq = listOf(InputPoint(10f, 10f), InputPoint(110f, 10f), InputPoint(110f, 110f))
        val r = walk(sq, 1f, interpolation = Interpolation.LINEAR)
        val corner = r.minByOrNull { abs(it.x - 110f) + abs(it.y - 10f) }!!
        assertEquals(110f, corner.x, 1e-3f)
        assertEquals(10f, corner.y, 1e-3f)
        assertEquals(200f, r.last().d, 0.01f)
    }

    @Test
    fun closedPathHasNoSeamDoubleAndNoTaper() {
        val sq = listOf(InputPoint(10f, 10f), InputPoint(110f, 10f), InputPoint(110f, 110f), InputPoint(10f, 110f))
        val params = BrushParams(taperStart = 50f, taperEnd = 50f)
        val tapers = ArrayList<Float>()
        val ds = ArrayList<Float>()
        val w = PathWalker(params, PathSink { s -> tapers.add(s.taper); ds.add(s.distance); 10f }, Interpolation.LINEAR, closed = true)
        w.begin(sq[0]); sq.drop(1).forEach(w::add); w.end()
        assertTrue(tapers.all { it == 1f })
        assertEquals(40, ds.size, "400 px loop / 10 px step without a second dab at the seam")
        assertEquals(390f, ds.last(), 0.01f)
    }

    @Test
    fun endingSamplesKnowTheTotal() {
        val r = ArrayList<Pair<Float, Float>>()
        val w = PathWalker(BrushParams(taperEnd = 40f), PathSink { s -> if (s.ending) r.add(s.total to s.remaining); 5f })
        val pts = line(0f, 0f, 200f, 0f, 5f)
        w.begin(pts[0]); pts.drop(1).forEach(w::add)
        assertTrue(r.isEmpty())
        w.end()
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { abs(it.first - 200f) < 0.01f })
        assertTrue(r.first().second >= 40f - 5f)
    }

    @Test
    fun resamplerAdapterMatchesSpacing() {
        val xs = ArrayList<Float>()
        val res = StrokeResampler(BrushParams(size = 20f, spacing = 0.1f, smoothing = 0f)) { x, _, _, _, _, _ -> xs.add(x) }
        val pts = line(10f, 50f, 210f, 50f, 5f)
        res.begin(pts[0]); pts.drop(1).forEach(res::add); res.end()
        for (i in 1 until xs.size - 1) assertEquals(2f, xs[i] - xs[i - 1], 0.02f)
        assertEquals(xs.size, res.emittedDabs)
    }
}
