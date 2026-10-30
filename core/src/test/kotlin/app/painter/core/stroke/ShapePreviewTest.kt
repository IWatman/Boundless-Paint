package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.geom.Vec2
import app.painter.core.shape.Shape
import app.painter.core.shape.ShapeStroke
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.assertGridsEqual
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Shape draft preview (spec STATUS debt 17): the live preview engine closes and fills a closed shape itself, so the
 * closing segment and the fill show before Apply, and [StrokeEngine.end] afterwards commits the same pixels it
 * would have committed without the preview call — no second closing segment and no second fill.
 */
class ShapePreviewTest {
    /** Small hard brush: were the outline drawn, it would be a 6 px band along the trajectory. */
    private val brush = BrushParams(size = 6f, hardness = 1f, spacing = 0.5f, smoothing = 0f, opacity = 1f, flow = 1f)

    /** A 100 × 100 square between (50, 50) and (150, 150), built by the shape tool: closed, the first point not repeated. */
    private val square = Shape.Rect(Vec2(100f, 100f), 100f, 100f)

    private fun points() = ShapeStroke.points(square)

    private fun shapeEngine(doc: Document, fill: FillSpec?, buildup: Boolean = false) = engine(
        doc,
        if (buildup) brush.copy(buildup = true) else brush,
        color = Rgba.BLACK,
        options = ShapeStroke.options(square, fill = fill),
    )

    @Test
    fun thePreviewShowsTheClosingSegmentAndTheFill() {
        val e = shapeEngine(Document.create(200, 200), FillSpec(1f))
        drawOpen(e, points())
        assertTrue(alpha(e.buffer, 100, 100) == 0f, "before previewFinish the buffer holds the outline dabs only")
        assertTrue(alpha(e.buffer, 50, 100) == 0f, "before previewFinish the seam from the last point to the first is open")
        e.previewFinish()
        val inside = alpha(e.buffer, 100, 100)
        assertTrue(inside > 0.99f, "the fill must be in the preview buffer, got $inside")
        val seam = alpha(e.buffer, 50, 100)
        assertTrue(seam > 0f, "the closing segment must be drawn, got $seam")
    }

    @Test
    fun endAfterThePreviewCommitsTheSamePixels() {
        for (buildup in listOf(false, true)) {
            for (opacity in listOf(1f, 0.5f)) {
                val label = "buildup $buildup, fill opacity $opacity"
                val plain = Document.create(200, 200)
                drawOpen(shapeEngine(plain, FillSpec(opacity), buildup), points()).endPatch()

                val previewed = Document.create(200, 200)
                val e = shapeEngine(previewed, FillSpec(opacity), buildup)
                drawOpen(e, points())
                e.previewFinish()
                e.endPatch()

                val a = plain.activeRaster!!.pixels
                val b = previewed.activeRaster!!.pixels
                assertGridsEqual(a, b, "$label: the commit must not differ")
                assertGridsEqual(b, a, "$label: the commit must not differ")
                assertTrue(alpha(b, 100, 100) > 0f, "$label: the committed square is filled, got ${alpha(b, 100, 100)}")
            }
        }
    }
}
