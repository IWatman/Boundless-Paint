package app.painter.core.pick

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.history.fillRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EyedropperTest {
    private fun near(e: Rgba, a: Rgba?, tol: Float = 1.5f / 255) {
        assertNotNull(a)
        assertTrue(abs(e.r - a.r) <= tol && abs(e.g - a.g) <= tol && abs(e.b - a.b) <= tol && abs(e.a - a.a) <= tol, "expected $e got $a")
    }

    @Test
    fun pick_singlePixel_currentLayer() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 10, 10, 11, 11, 0.2f, 0.4f, 0.6f, 1f)
        near(Rgba(0.2f, 0.4f, 0.6f), Eyedropper(doc, PickSource.CURRENT_LAYER, l.id).sample(10.7f, 10.2f))
    }

    @Test
    fun pick_canvas_includesPaper() {
        val doc = Document.create(64, 64)
        doc.paper.color = Rgba(0f, 0f, 1f)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 0, 0, 64, 64, 1f, 0f, 0f, 0.5f)
        near(Rgba(0.5f, 0f, 0.5f), Eyedropper(doc, PickSource.CANVAS, null).sample(5f, 5f), 3f / 255)
    }

    @Test
    fun pick_canvas_hiddenPaperIsWhite() {
        val doc = Document.create(64, 64)
        doc.paper.color = Rgba(0f, 0f, 1f)
        doc.paper.visible = false
        near(Rgba(1f, 1f, 1f), Eyedropper(doc, PickSource.CANVAS, null).sample(5f, 5f))
    }

    @Test
    fun pick_average_premultipliedNoDarkFringe() {
        val doc = Document.create(64, 64)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 10, 10, 11, 11, 1f, 0f, 0f, 0.5f)
        val c = Eyedropper(doc, PickSource.CURRENT_LAYER, l.id, diameter = 5).sample(10f, 10f)
        near(Rgba(1f, 0f, 0f), c)
    }

    @Test
    fun pick_checkerboard_d5_gray() {
        val doc = Document.create(64, 64)
        val l = doc.activeRaster!!
        for (y in 0 until 64) for (x in 0 until 64) {
            val v = if ((x + y) % 2 == 0) 0f else 1f
            l.pixels.setPixel(x, y, v, v, v, 1f)
        }
        val c = Eyedropper(doc, PickSource.CURRENT_LAYER, l.id, diameter = 5).sample(20f, 20f)!!
        assertTrue(abs(c.r - 0.5f) < 0.08f, "got $c")
    }

    @Test
    fun pick_transparent_returnsNull() {
        val doc = Document.create(64, 64)
        assertNull(Eyedropper(doc, PickSource.CURRENT_LAYER, doc.activeId).sample(5f, 5f))
    }

    @Test
    fun pick_outOfBounds_null() {
        val doc = Document.create(64, 64)
        assertNull(Eyedropper(doc, PickSource.CANVAS, null).sample(-5f, 3f))
        assertNull(Eyedropper(doc, PickSource.CANVAS, null).sample(64f, 3f))
    }

    @Test
    fun pick_cacheInvalidatedOnModified() {
        val doc = Document.create(64, 64)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 0, 0, 64, 64, 1f, 0f, 0f, 1f)
        val e = Eyedropper(doc, PickSource.CANVAS, null)
        near(Rgba(1f, 0f, 0f), e.sample(3f, 3f))
        fillRect(l.pixels, 0, 0, 64, 64, 0f, 1f, 0f, 1f)
        near(Rgba(1f, 0f, 0f), e.sample(3f, 3f))
        doc.modified++
        near(Rgba(0f, 1f, 0f), e.sample(3f, 3f))
    }

    @Test
    fun loupe_argb() {
        val doc = Document.create(64, 64)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 5, 5, 6, 6, 1f, 0f, 0f, 1f)
        val out = IntArray(9)
        Eyedropper(doc, PickSource.CURRENT_LAYER, l.id).loupe(5, 5, 1, out)
        assertEquals(0xFFFF0000.toInt(), out[4])
        assertEquals(0, out[0])
    }
}
