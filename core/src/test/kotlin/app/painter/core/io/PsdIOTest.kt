package app.painter.core.io

import app.painter.core.blend.BlendMode
import app.painter.core.doc.Document
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PSD export/import of the M3 raster subset (layers, names, opacity, blend, composite fallback). */
class PsdIOTest {

    @Test
    fun roundTripKeepsLayersAndPixels() {
        val doc = Document.create(32, 24)
        val first = doc.activeRaster!!
        first.name = "Ink"
        first.pixels.setPixel(4, 5, 1f, 0f, 0f, 1f)
        val second = doc.newRasterLayer("Shade")
        second.blendMode = BlendMode.MULTIPLY
        second.opacity = 0.5f
        second.pixels.setPixel(10, 11, 0f, 0.5f, 1f, 1f)
        doc.root.add(second)

        val out = ByteArrayOutputStream()
        PsdIO.write(doc, out)
        val bytes = out.toByteArray()
        assertEquals("8BPS", String(bytes, 0, 4, Charsets.US_ASCII))

        val read = PsdIO.read(ByteArrayInputStream(bytes))
        assertEquals(32, read.width)
        assertEquals(24, read.height)
        assertEquals(2, read.root.children.size)
        val ink = read.root.children[0] as app.painter.core.doc.RasterLayer
        val shade = read.root.children[1] as app.painter.core.doc.RasterLayer
        assertEquals("Ink", ink.name)
        assertEquals("Shade", shade.name)
        assertEquals(BlendMode.MULTIPLY, shade.blendMode)
        assertEquals(0.5f, shade.opacity, 0.01f)
        assertTrue(FloatArray(4).also { ink.pixels.getPixel(4, 5, it) }[3] > 0.9f)
        val px = FloatArray(4).also { shade.pixels.getPixel(10, 11, it) }
        assertTrue(px[3] > 0.9f)
        assertTrue(px[2] > 0.8f, "blue survives the round trip")
    }
}
