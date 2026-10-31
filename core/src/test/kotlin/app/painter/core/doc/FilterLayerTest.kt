package app.painter.core.doc

import app.painter.core.blend.Compositor
import app.painter.core.filter.BlurParams
import app.painter.core.raster.TileGrid
import org.junit.Test
import kotlin.test.assertEquals

/** A live filter layer replaces the root content below it with its cached filtered pixels. */
class FilterLayerTest {

    @Test
    fun filterLayerReplacesTheLowerComposite() {
        val doc = Document.create(64, 64)
        val lower = doc.activeRaster!!
        lower.pixels.setPixel(10, 10, 1f, 0f, 0f, 1f)
        val cache = TileGrid(64, 64)
        cache.setPixel(20, 20, 0f, 0f, 1f, 1f)
        val filter = FilterLayer("f", "Blur", BlurParams(), cache)
        doc.root.add(filter)
        val flat = Compositor.flatten(doc, includePaper = false)
        val out = FloatArray(4)
        flat.getPixel(20, 20, out)
        assertEquals(1f, out[3], 0.01f, "the filter cache is visible")
        flat.getPixel(10, 10, out)
        assertEquals(0f, out[3], 0.01f, "the lower layer was replaced, not duplicated")
    }
}
