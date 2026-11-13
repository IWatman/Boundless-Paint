package app.painter.core.canvas

import app.painter.core.doc.Document
import app.painter.core.doc.LayerMask
import app.painter.core.history.History
import app.painter.core.raster.MaskGrid
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasSamplingRegressionTest {
    @Test
    fun identityScalePreservesPixelsLayerMaskAndSelectionThroughUndoRedo() {
        val doc = Document.create(8, 8)
        val layer = doc.activeRaster!!
        layer.pixels.setPixel(3, 4, 1f, 0f, 0f, 1f)
        layer.mask = LayerMask(MaskGrid(8, 8, 0)).also { it.tiles.getOrCreate(0, 0).set(3, 4, 1f) }
        doc.selection.mask.getOrCreate(0, 0).set(3, 4, 1f)
        val history = History()
        history.execute(doc, canvasCommand(doc, CanvasOp.CanvasSize(8, 8, scaleContent = true)))

        fun check() {
            val pixel = FloatArray(4)
            layer.pixels.getPixel(3, 4, pixel)
            assertEquals(1f, pixel[0], 0.001f)
            assertEquals(1f, pixel[3], 0.001f)
            layer.pixels.getPixel(2, 4, pixel)
            assertEquals(0f, pixel[3], 0.001f)
            assertEquals(255, layer.mask!!.tiles.byteAt(3, 4))
            assertEquals(0, layer.mask!!.tiles.byteAt(2, 4))
            assertEquals(255, doc.selection.mask.byteAt(3, 4))
            assertEquals(0, doc.selection.mask.byteAt(2, 4))
        }
        check()
        history.undo(doc)
        check()
        history.redo(doc)
        check()
    }

    @Test
    fun upscaleUsesPixelCentersForPixelsAndMasks() {
        val doc = Document.create(4, 4)
        val layer = doc.activeRaster!!
        layer.pixels.setPixel(1, 1, 1f, 0f, 0f, 1f)
        doc.selection.mask.getOrCreate(0, 0).set(1, 1, 1f)
        History().execute(doc, canvasCommand(doc, CanvasOp.CanvasSize(8, 8, scaleContent = true)))
        val pixel = FloatArray(4)
        layer.pixels.getPixel(2, 2, pixel)
        assertEquals(0.5625f, pixel[3], 0.01f)
        layer.pixels.getPixel(3, 3, pixel)
        assertEquals(0.5625f, pixel[3], 0.01f)
        assertEquals(255, doc.selection.mask.byteAt(2, 2))
        assertEquals(255, doc.selection.mask.byteAt(3, 3))
        assertEquals(0, doc.selection.mask.byteAt(1, 2))
    }
}
