package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.assertGridsEqual
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Double brush (spec/tools/m2-brush.md §1.12): the shadow `S = sat(shadow_size + 0.5 − EDT(A ≥ 0.5))` of the
 * stroke buffer is composited under the body. With distance 0 and no rotation the shadow is a uniform outline
 * [DoubleParams.shadowSize] px thick around the body; a brush without [DoubleParams] is the plain stroke.
 */
class DoubleTest {
    /** Full-coverage black band 40 px wide across a 220×220 canvas; its body spans y 80..119 at x 60..160. */
    private val bandBrush = BrushParams(
        size = 40f,
        hardness = 1f,
        spacing = 0.25f,
        smoothing = 0f,
        opacity = 1f,
        flow = 1f,
    )

    private fun stroke(special: SpecialParams?): TileGrid {
        val doc = Document.create(220, 220)
        val e = engine(doc, bandBrush.copy(special = special), color = Rgba.BLACK)
        drawOpen(e, line(60f, 100f, 160f, 100f, 4f))
        e.endPatch()
        return doc.activeRaster!!.pixels
    }

    @Test
    fun distanceZeroGivesAUniformOutline() {
        val px = stroke(DoubleParams(shadowColor = "#000000", shadowSize = 4f, angle = 45f, distance = 0f))
        val tol = 1f / 255f
        assertTrue(alpha(px, 100, 100) > 0.99f, "the body must be solid, got ${alpha(px, 100, 100)}")
        // The outline is above (and, symmetrically, below) the body: full shadow a few px out, nothing further.
        var thickness = 0
        for (y in 70..79) if (alpha(px, 100, y) > 0.5f) thickness++
        assertTrue(abs(thickness - 4) <= 1, "outline thickness $thickness px, expected 4 ± 1")
        assertTrue(alpha(px, 100, 76) > 0.9f, "just outside the body the shadow must be solid, got ${alpha(px, 100, 76)}")
        assertEquals(0f, alpha(px, 100, 72), tol, "the shadow must stop at shadow_size")
        for (y in 76..79) assertEquals(1f, alpha(px, 110, y), tol, "the outline must be uniform at y=$y")
    }

    @Test
    fun withoutDoubleParamsTheStrokeIsPlain() {
        val plain = stroke(null)
        assertGridsEqual(plain, stroke(null), "double params absent")
        assertEquals(0f, alpha(plain, 100, 78), 0f, "a plain stroke must not paint outside its body")
        assertTrue(alpha(plain, 100, 100) > 0.99f, "the body must be solid, got ${alpha(plain, 100, 100)}")
        // Guards the test above: the same brush with the effect on does paint outside the body.
        val doubled = stroke(DoubleParams(shadowSize = 4f, distance = 0f))
        assertTrue(alpha(doubled, 100, 78) > 0.9f, "the effect must paint outside the body, got ${alpha(doubled, 100, 78)}")
    }
}
