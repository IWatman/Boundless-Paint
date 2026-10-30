package app.painter.core.stroke

import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Screentone snap (spec/tools/m2-brush.md §1.14): the dabs paint no pixels, they raise the coverage of the grid
 * nodes they cover and one AA dot of diameter `g·sqrt(c)·1.13` is drawn on every node, centred exactly on it.
 */
class ScreentoneTest {
    private val tone = ScreentoneParams(spacing = 20f, angle = 0f)

    /** Number of rows of column [x] where `alpha ≥ 0.5`, inside [y0]..[y1]. */
    private fun run(px: TileGrid, x: Int, y0: Int, y1: Int): Int =
        (y0..y1).count { alpha(px, x, it) >= 0.5f }

    @Test
    fun aSoftStrokeGrowsItsDotsTowardsTheCentre() {
        val doc = Document.create(240, 240)
        // Hardness 0.2: a 60 px dab fades out over 6..30 px from its centre, so the grid nodes near the stroke
        // centre are covered much more than the ones at its rim.
        val brush = BrushParams(size = 60f, hardness = 0.2f, spacing = 0.25f, smoothing = 0f, opacity = 1f, flow = 1f)
        val e = engine(doc, brush.copy(special = tone))
        drawOpen(e, line(60f, 100f, 180f, 100f, 4f))
        e.endPatch()
        val px = doc.activeRaster!!.pixels
        // Nodes of the g = 20 grid: (100, 100) sits on the stroke, (100, 120) is 20 px off it.
        val centre = run(px, 100, 85, 112)
        val rim = run(px, 100, 113, 130)
        assertTrue(centre >= 20, "the core dot must cover its node, run $centre px")
        assertTrue(rim in 8..18, "the rim dot must be smaller, run $rim px")
        assertTrue(centre > rim + 4, "dots must grow towards the centre: $centre vs $rim px")
        // A node outside the dab is not hit at all.
        assertTrue(alpha(px, 100, 160) == 0f, "no dot outside the dab, got ${alpha(px, 100, 160)}")
    }

    @Test
    fun everyDotIsCentredOnAGridNode() {
        val doc = Document.create(120, 120)
        val brush = BrushParams(size = 2f, hardness = 1f, spacing = 0.5f, smoothing = 0f, opacity = 1f, flow = 1f)
        val e = engine(doc, brush.copy(special = tone))
        // A single tap hits exactly one node of the 20 px grid: (40, 40), 0.5 px from the dab centre.
        e.begin(InputPoint(40.4f, 39.7f))
        e.endPatch()
        val px = doc.activeRaster!!.pixels
        var sum = 0f
        var sx = 0f
        var sy = 0f
        for (y in 0 until 120) for (x in 0 until 120) {
            val a = alpha(px, x, y)
            if (a <= 0f) continue
            sum += a
            sx += a * (x + 0.5f)
            sy += a * (y + 0.5f)
        }
        assertTrue(sum > 0f, "the tap must draw a dot")
        val cx = sx / sum
        val cy = sy / sum
        val nodeX = round(cx / tone.spacing) * tone.spacing
        val nodeY = round(cy / tone.spacing) * tone.spacing
        assertTrue(abs(cx - nodeX) <= 0.5f, "dot centroid x $cx is off node $nodeX")
        assertTrue(abs(cy - nodeY) <= 0.5f, "dot centroid y $cy is off node $nodeY")
    }
}
