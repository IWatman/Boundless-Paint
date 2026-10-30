package app.painter.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reference stroke of the brush editor preview pad (spec/tools/m2-brush.md §4.2). */
class PreviewStrokeTest {
    private val width = 480
    private val height = 160

    @Test
    fun pointsFitThePad() {
        val points = PreviewStroke.points(width, height)
        assertTrue("too few segments: ${points.size}", points.size > 16)
        for (p in points) {
            assertTrue("x ${p.x} outside the pad", p.x in 0f..width.toFloat())
            assertTrue("y ${p.y} outside the pad", p.y in 0f..height.toFloat())
            assertTrue("preview samples must carry pressure", p.isStylus)
        }
        for (i in 1 until points.size) {
            assertTrue("x must grow left to right at $i", points[i].x > points[i - 1].x)
        }
    }

    @Test
    fun pressureEnvelopeRisesAndFalls() {
        val points = PreviewStroke.points(width, height)
        assertEquals(0.15f, points.first().pressure, 1e-3f)
        assertEquals(0.15f, points.last().pressure, 1e-3f)
        assertEquals(1f, points.maxOf { it.pressure }, 1e-3f)
    }

    @Test
    fun curveCrossesTheCentreLine() {
        val centre = height / 2f
        val points = PreviewStroke.points(width, height)
        assertTrue("no upper half of the S", points.any { it.y < centre - 1f })
        assertTrue("no lower half of the S", points.any { it.y > centre + 1f })
    }

    @Test
    fun timeIsMonotonic() {
        val points = PreviewStroke.points(width, height)
        for (i in 1 until points.size) {
            assertTrue("time must grow at $i", points[i].timeMs > points[i - 1].timeMs)
        }
    }
}
