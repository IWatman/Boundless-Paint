package app.painter.core.canvas

import app.painter.core.doc.Document
import app.painter.core.history.History
import app.painter.core.raster.IntRect
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Crop, canvas size and straighten (M3): all layers, mask and selection move together, one undo step. */
class CanvasOpsTest {

    private fun doc(w: Int = 512, h: Int = 512): Pair<Document, app.painter.core.doc.RasterLayer> {
        val d = Document.create(w, h)
        val layer = d.activeRaster!!
        layer.pixels.setPixel(100, 100, 1f, 1f, 1f, 1f)
        return d to layer
    }

    private fun alpha(d: Document, x: Int, y: Int): Float =
        FloatArray(4).also { d.activeRaster!!.pixels.getPixel(x, y, it) }[3]

    @Test
    fun cropMovesThePixelsAndTheSelection() {
        val (d, layer) = doc()
        d.selection.mask.getOrCreate(0, 0).set(100, 100, 1f)
        val history = History()
        history.execute(d, canvasCommand(d, CanvasOp.Crop(IntRect(50, 50, 250, 250)), "Crop"))

        assertEquals(200, d.width)
        assertEquals(200, d.height)
        assertEquals(1f, alpha(d, 50, 50), 0.01f, "the pixel moved with the crop")
        assertEquals(1f, d.selection.mask.value(50, 50), 0.01f, "the selection moved too")

        history.undo(d)
        assertEquals(512, d.width)
        assertEquals(1f, alpha(d, 100, 100), 0.01f)
        assertEquals(1f, d.selection.mask.value(100, 100), 0.01f)
    }

    @Test
    fun canvasSizeKeepsTheContentAtTheAnchor() {
        val (d, _) = doc()
        val history = History()
        history.execute(
            d,
            canvasCommand(d, CanvasOp.CanvasSize(600, 700, scaleContent = false, anchorX = 0f, anchorY = 0f)),
        )
        assertEquals(600, d.width)
        assertEquals(700, d.height)
        assertEquals(1f, alpha(d, 100, 100), 0.01f)
        assertEquals(0f, alpha(d, 400, 400))
    }

    @Test
    fun scalingTheContentUsesTheNewSize() {
        val (d, layer) = doc(256, 256)
        history(d, CanvasOp.CanvasSize(512, 512, scaleContent = true))
        assertEquals(512, d.width)
        // A 4x4 paint block at (100,100) becomes a block near (200,200), still painted.
        assertTrue(alpha(d, 200, 200) > 0.5f, "the content scaled up")
    }

    @Test
    fun straightenRotatesAroundTheCentre() {
        val (d, _) = doc()
        history(d, CanvasOp.Straighten(90f))
        // (100,100) is 156 px up-left of the centre; a clockwise turn puts it up-right: (412,100).
        assertTrue(alpha(d, 412, 100) > 0.1f, "the pixel rotated clockwise")
        assertTrue(alpha(d, 100, 100) < 0.1f, "the old place is empty")
    }

    @Test
    fun masksFollowTheCrop() {
        val (d, layer) = doc()
        d.root.add(app.painter.core.doc.GroupNode("g", "Group"))
        val mask = app.painter.core.doc.LayerMask(app.painter.core.raster.MaskGrid(512, 512, 0))
        mask.tiles.getOrCreate(0, 0).set(100, 100, 1f)
        layer.mask = mask
        history(d, CanvasOp.Crop(IntRect(50, 50, 250, 250)))
        assertEquals(1f, layer.mask!!.tiles.value(50, 50), 0.01f)
        assertEquals(200, layer.mask!!.tiles.width)
    }

    private fun history(d: Document, op: CanvasOp) = History().also {
        it.execute(d, canvasCommand(d, op))
    }

    @Test
    fun clampedCropCannotBeEmpty() {
        val (d, _) = doc()
        assertEquals(IntRect(511, 511, 512, 512), clampCrop(d, IntRect(900, 900, 1000, 1000)))
    }
}
