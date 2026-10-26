package app.painter.core.stroke

import app.painter.core.doc.Document
import app.painter.core.stroke.StrokeTestKit.assertGridsEqual
import app.painter.core.stroke.StrokeTestKit.circle
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.gray
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeterminismTest {
    private val head = HeadImage(gray(64, 64) { x, y -> if ((x / 8 + y / 8) % 2 == 0 && (x - 32) * (x - 32) + (y - 32) * (y - 32) < 900) 1f else 0.3f })

    private val params = BrushParams(
        size = 60f, spacing = 0.15f, smoothing = 20f,
        head = HeadParams(source = HeadSource.BITMAP, rotationMode = RotationMode.RANDOM, aspect = 0.3f),
        jitter = JitterParams(size = 0.4f, angle = 0.5f, scatter = 0.8f, flow = 0.3f, count = 3, hue = 0.2f),
        dynamics = listOf(DynamicsLink(DynSource.RANDOM, DynTarget.FLOW, Curve.linear(0.3f))),
    )

    private fun render(threads: Int, perPoint: Boolean, seed: Long = 99L): Document {
        val doc = Document.create(900, 700)
        val e = engine(doc, params, BrushResources(head = head), StrokeContext(threads = threads, seed = seed))
        drawOpen(e, circle(450f, 350f, 280f, 300), perPoint)
        e.endPatch()
        return doc
    }

    @Test
    fun threadCountAndBatchingDoNotChangePixels() {
        val ref = render(1, perPoint = false)
        val four = render(4, perPoint = false)
        val points = render(4, perPoint = true)
        assertTrue(ref.activeRaster!!.pixels.keys().size >= 6, "stroke must span many tiles")
        assertGridsEqual(ref.activeRaster!!.pixels, four.activeRaster!!.pixels, "1 vs 4 threads")
        assertGridsEqual(ref.activeRaster!!.pixels, points.activeRaster!!.pixels, "batch vs points")
    }

    @Test
    fun seedChangesTheStroke() {
        val a = render(2, false, seed = 1L).activeRaster!!.pixels
        val b = render(2, false, seed = 2L).activeRaster!!.pixels
        var same = true
        for (k in a.keys()) if (b.get(k) == null || !a.get(k)!!.contentEquals(b.get(k)!!)) same = false
        assertFalse(same)
    }
}
