package app.painter.core.guide

import app.painter.core.geom.Vec2
import app.painter.core.stroke.InputPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SnapStageTest {
    private val ruler = LinesConfig(on = true, a = Vec2(0f, 500f), b = Vec2(1000f, 500f))

    private fun ray(x: Float, y: Float, deg: Float, n: Int, step: Float, jitter: Float = 0f, seed: Int = 1): List<InputPoint> {
        val r = Random(seed)
        val a = deg * PI.toFloat() / 180f
        return (0 until n).map {
            val j = if (it == 0) 0f else (r.nextFloat() - 0.5f) * 2 * jitter
            InputPoint(x + cos(a) * step * it + j, y + sin(a) * step * it - j)
        }
    }

    @Test
    fun holdsBackUntilTwelvePxThenFlushesProjected() {
        val stage = SnapStage(listOf(LinesGuide(ruler)), SnapContext(1f, 10f))
        val sink = RecordingSink()
        val pts = ray(100f, 100f, 0f, 10, 2f)
        stage.begin(pts[0], sink)
        for (i in 1..5) stage.add(pts[i], sink)
        assertEquals(0, sink.all.size, "held back below 12 px")
        stage.add(pts[6], sink)
        assertEquals(7, sink.all.size, "flushed at 12 px")
        assertNotNull(stage.lockedTrack)
        assertTrue(sink.all.all { abs(it.y - 100f) < 1e-3f })
    }

    @Test
    fun shortStrokePassesThroughUnchanged() {
        val stage = SnapStage(listOf(LinesGuide(ruler)), SnapContext())
        val pts = listOf(InputPoint(10f, 10f), InputPoint(13f, 14f), InputPoint(15f, 11f))
        val out = runStage(stage, pts).all
        assertEquals(pts, out)
    }

    @Test
    fun cancelResets() {
        val stage = SnapStage(listOf(LinesGuide(ruler)), SnapContext())
        val sink = RecordingSink()
        stage.begin(InputPoint(0f, 0f), sink)
        stage.add(InputPoint(3f, 0f), sink)
        stage.cancel()
        stage.end(sink)
        assertEquals(0, sink.all.size)
    }

    @Test
    fun parallelAtTenDegreesPerpendicularAtEighty() {
        val par = runStage(SnapStage(listOf(LinesGuide(ruler)), SnapContext()), ray(100f, 100f, 10f, 40, 3f, jitter = 0.8f))
        assertTrue(par.all.all { abs(it.y - 100f) < 0.01f }, "parallel track")
        val perp = runStage(SnapStage(listOf(LinesGuide(ruler)), SnapContext()), ray(100f, 100f, 80f, 40, 3f, jitter = 0.8f))
        assertTrue(perp.all.all { abs(it.x - 100f) < 0.01f }, "perpendicular track")
    }

    @Test
    fun twentyJitteryStrokesStayOnTheirLines() {
        repeat(20) { k ->
            val y0 = 50f + 20f * k
            val out = runStage(SnapStage(listOf(LinesGuide(ruler)), SnapContext()), ray(30f, y0, 3f, 60, 4f, 1.5f, seed = k))
            assertTrue(out.all.all { abs(it.y - y0) < 0.01f })
        }
    }

    @Test
    fun priorityBreaksTies() {
        // Ellipse radial line and ruler parallel coincide in direction: Ellipse wins over Lines.
        val ell = EllipseGuide(EllipseConfig(on = true, c = Vec2(0f, 100f), rx = 50f, ratio = 1f))
        val stage = SnapStage(listOf(LinesGuide(ruler), ell), SnapContext())
        runStage(stage, ray(200f, 100f, 0f, 20, 2f))
        assertEquals(SnapPriority.ELLIPSE, stage.lockedTrack!!.priority)
        assertTrue(stage.lockedTrack is LineTrack)
    }

    @Test
    fun withoutGuidesPointsPass() {
        val pts = ray(0f, 0f, 33f, 20, 3f)
        assertEquals(pts, runStage(SnapStage(emptyList(), SnapContext()), pts).all)
    }

    @Test
    fun lockDistanceScalesWithZoom() {
        val stage = SnapStage(listOf(LinesGuide(ruler)), SnapContext(screenToDoc = 0.5f))
        val sink = RecordingSink()
        stage.begin(InputPoint(0f, 0f), sink)
        stage.add(InputPoint(6f, 0f), sink)
        assertSame(stage.lockedTrack, stage.lockedTrack)
        assertNotNull(stage.lockedTrack, "6 doc px = 12 screen px at zoom 2")
    }
}
