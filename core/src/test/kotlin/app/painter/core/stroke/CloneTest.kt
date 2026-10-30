package app.painter.core.stroke

import app.painter.core.doc.Document
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Clone mode (spec/tools/m2-brush.md §1.18, stage S5a): the dab deposits `src(p − offset)` of a snapshot taken when
 * the stroke begins, so a clone onto an empty layer copies the source, and a clone into the source layer itself still
 * copies the original pixels instead of smearing its own writes.
 */
class CloneTest {
    /** Full flow, hardness 1 and tight spacing, so the stroke area is fully covered by `m·flow`. */
    private val cloning = BrushParams(size = 40f, hardness = 1f, spacing = 0.25f, smoothing = 0f, flow = 1f, opacity = 1f)

    private fun pixel(doc: Document, x: Int, y: Int): FloatArray {
        val out = FloatArray(4)
        doc.activeRaster!!.pixels.getPixel(x, y, out)
        return out
    }

    @Test
    fun cloneWithoutOffsetReproducesTheSourceLayer() {
        val doc = Document.create(300, 120)
        val a = doc.activeRaster!!
        for (y in 0 until 120) {
            for (x in 0 until 300) a.pixels.setPixel(x, y, x / 299f, y / 119f, 0.25f, 1f)
        }
        val b = doc.newRasterLayer("B")
        doc.root.add(b)
        doc.activeId = b.id
        val e = engine(doc, cloning, mode = StrokeMode.CLONE, ctx = StrokeContext(threads = 1, clone = CloneSource(layerId = a.id)))
        drawOpen(e, line(60f, 60f, 240f, 60f, 4f))
        e.endPatch()
        // Under the stroke layer B holds the pixels of A.
        for (x in 100 until 200 step 11) {
            for (y in 52 until 70 step 6) {
                val p = pixel(doc, x, y)
                assertEquals(x / 299f, p[0], 2f / 255f, "red at ($x, $y)")
                assertEquals(y / 119f, p[1], 2f / 255f, "green at ($x, $y)")
                assertEquals(0.25f, p[2], 2f / 255f, "blue at ($x, $y)")
                assertEquals(1f, p[3], 2f / 255f, "alpha at ($x, $y)")
            }
        }
        // Outside the stroke layer B stays empty.
        assertEquals(0f, pixel(doc, 20, 20)[3], 0f, "layer B above the stroke")
        assertEquals(0f, pixel(doc, 280, 100)[3], 0f, "layer B beside the stroke")
    }

    @Test
    fun cloneIntoSameLayerUsesTheSnapshot() {
        val doc = Document.create(300, 120)
        val px = doc.activeRaster!!.pixels
        for (y in 0 until 120) {
            for (x in 0 until 300) px.setPixel(x, y, x / 299f, 0f, 0f, 1f)
        }
        val e = engine(doc, cloning, mode = StrokeMode.CLONE, ctx = StrokeContext(threads = 1, clone = CloneSource(offsetX = 40f)))
        drawOpen(e, line(100f, 60f, 240f, 60f, 4f))
        e.endPatch()
        // Every pixel under the stroke holds the source 40 px to its left, not a pixel an earlier dab already rewrote.
        for (x in 120 until 235 step 7) {
            for (y in 52 until 70 step 6) {
                assertEquals((x - 40) / 299f, pixel(doc, x, y)[0], 2f / 255f, "red at ($x, $y)")
            }
        }
        // Far from the stroke the ramp is untouched.
        assertTrue(pixel(doc, 280, 60)[0] > 0.9f, "beyond the stroke the layer keeps its own pixels")
    }
}
