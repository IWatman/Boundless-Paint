package app.painter.core.history

import app.painter.core.blend.Compositor
import app.painter.core.canvas.CanvasOp
import app.painter.core.canvas.canvasCommand
import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.LayerMask
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.stroke.BrushParams
import app.painter.core.vector.VectorPoint
import app.painter.core.vector.VectorStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VectorOperationGuardTest {
    private fun vector(doc: Document) = doc.newVectorLayer("Vector").also {
        it.appendStroke(VectorStroke(points = listOf(VectorPoint(4f, 4f, 1f)), brush = BrushParams(), color = Rgba.BLACK))
        it.pixels.setPixel(4, 4, 1f, 0f, 0f, 1f)
        it.mask = LayerMask(MaskGrid(doc.width, doc.height, 0))
        it.mask!!.tiles.getOrCreate(0, 0).set(4, 4, 1f)
    }

    @Test
    fun canvasOperationsRejectNestedHiddenVectorWithoutChangingDocument() {
        val doc = Document.create(32, 32)
        val group = GroupNode("group", "Group")
        val layer = vector(doc).also { it.visible = false; it.locked = true }
        group.add(layer)
        doc.root.add(group)
        val paths = layer.strokes.toList()
        val pixels = layer.pixels
        val mask = layer.mask
        val operations = listOf(
            CanvasOp.Crop(IntRect(1, 1, 20, 20)),
            CanvasOp.CanvasSize(40, 40),
            CanvasOp.CanvasSize(40, 40, scaleContent = true),
            CanvasOp.Straighten(20f),
        )
        for (op in operations) {
            val error = assertFailsWith<VectorOperationException> { canvasCommand(doc, op) }
            assertEquals(layer.id, error.layerId)
            assertTrue(error.message!!.contains("Canvas operation"))
            assertEquals(32, doc.width)
            assertEquals(32, doc.height)
            assertSame(pixels, layer.pixels)
            assertSame(mask, layer.mask)
            assertEquals(paths, layer.strokes)
            assertSame(group, layer.parent)
        }
    }

    @Test
    fun mergeRejectsVectorOnEitherSideWithoutMutation() {
        for (vectorOnTop in listOf(false, true)) {
            val doc = Document.create(32, 32)
            val raster = doc.activeRaster!!
            val layer = vector(doc)
            if (vectorOnTop) doc.root.add(layer) else doc.root.add(layer, 0)
            val upper = if (vectorOnTop) layer else raster
            val originalOrder = doc.root.children.toList()
            val paths = layer.strokes.toList()
            val history = History()
            val error = assertFailsWith<VectorOperationException> { history.execute(doc, MergeDown(upper.id, Compositor)) }
            assertEquals(layer.id, error.layerId)
            assertTrue(error.message!!.contains("Merge down"))
            assertEquals(originalOrder, doc.root.children)
            assertEquals(paths, layer.strokes)
            assertEquals(0, history.undoSize)
            assertEquals(1f, layer.mask!!.tiles.value(4, 4))
            assertEquals(1f, FloatArray(4).also { layer.pixels.getPixel(4, 4, it) }[3])
        }
    }

    @Test
    fun mergeRejectsGroupsContainingVectorsOnEitherSide() {
        for (groupOnTop in listOf(false, true)) {
            val doc = Document.create(32, 32)
            val raster = doc.activeRaster!!
            val layer = vector(doc)
            val group = GroupNode("group", "Group").also { it.add(layer) }
            if (groupOnTop) doc.root.add(group) else doc.root.add(group, 0)
            val upper = if (groupOnTop) group else raster
            val before = doc.root.children.toList()
            val error = assertFailsWith<VectorOperationException> { History().execute(doc, MergeDown(upper.id, Compositor)) }
            assertEquals(layer.id, error.layerId)
            assertEquals(before, doc.root.children)
            assertSame(group, layer.parent)
            assertEquals(1, layer.strokes.size)
        }
    }
}
