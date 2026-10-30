package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fill brush (spec/tools/m2-brush.md §1.15): the point trajectory is a polygon closed by its last point and the
 * stroke fills it even-odd with AA under no outline at all — the dabs are not drawn.
 */
class FillBrushTest {
    /** Small hard brush: were the outline drawn, it would be a 6 px band along the trajectory. */
    private val brush = BrushParams(size = 6f, hardness = 1f, spacing = 0.5f, smoothing = 0f, opacity = 1f, flow = 1f)

    private fun fill(vararg pts: Float): TileGrid {
        val doc = Document.create(200, 200)
        val e = engine(doc, brush.copy(special = FillBrushParams()), color = Rgba.BLACK)
        val points = (0 until pts.size / 2).map { InputPoint(pts[it * 2], pts[it * 2 + 1], timeMs = it * 4L) }
        drawOpen(e, points)
        e.endPatch()
        return doc.activeRaster!!.pixels
    }

    @Test
    fun aClosedSquareTrajectoryFillsTheSquare() {
        val px = fill(50f, 50f, 150f, 50f, 150f, 150f, 50f, 150f)
        for (p in listOf(60 to 60, 100 to 100, 148 to 100, 100 to 52)) {
            assertEquals(1f, alpha(px, p.first, p.second), 0.01f, "the interior at $p must be solid")
        }
        assertTrue(alpha(px, 100, 40) == 0f, "above the square nothing is painted")
        assertTrue(alpha(px, 40, 100) == 0f, "left of the square nothing is painted")
        // No outline: a 6 px dab on the right edge would reach x = 153.
        assertTrue(alpha(px, 152, 100) == 0f, "the outline dabs must not be drawn, got ${alpha(px, 152, 100)}")
    }

    @Test
    fun aFigureEightIsFilledEvenOdd() {
        // Bowtie through (20,20) → (120,120) → (120,20) → (20,120), closed by the last point.
        val px = fill(20f, 20f, 120f, 120f, 120f, 20f, 20f, 120f)
        assertTrue(alpha(px, 35, 70) > 0.9f, "the left lobe must be filled, got ${alpha(px, 35, 70)}")
        assertTrue(alpha(px, 105, 70) > 0.9f, "the right lobe must be filled, got ${alpha(px, 105, 70)}")
        assertTrue(alpha(px, 70, 35) == 0f, "even-odd: the crossing region must stay empty, got ${alpha(px, 70, 35)}")
        assertTrue(alpha(px, 70, 105) == 0f, "even-odd: the crossing region must stay empty, got ${alpha(px, 70, 105)}")
        // The diagonal edge is antialiased: the first pixel inside the lobe is only partly covered.
        val edge = alpha(px, 35, 35)
        assertTrue(edge > 0f && edge < 1f, "the edge must be antialiased, got $edge")
    }
}
