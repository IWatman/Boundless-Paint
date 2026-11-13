package app.painter.core.fill

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.history.History
import app.painter.core.select.SelectionEdits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LockedAncestorFillRegressionTest {
    @Test
    fun lockedAncestorBlocksBucketLassoAndSelectionEdits() {
        val doc = Document.create(8, 8)
        val layer = doc.activeRaster!!
        layer.pixels.setPixel(2, 2, 1f, 0f, 0f, 1f)
        doc.root.remove(layer)
        val outer = GroupNode("outer", "Outer")
        val inner = GroupNode("inner", "Inner")
        doc.root.add(outer)
        outer.add(inner)
        inner.add(layer)
        outer.locked = true
        val coverage = MaskMath.full(8, 8)
        val color = Rgba(0f, 0f, 1f)
        val poly = floatArrayOf(0f, 0f, 8f, 0f, 8f, 8f, 0f, 8f)
        doc.selection.mask.getOrCreate(0, 0).set(2, 2, 1f)
        val modified = doc.modified

        assertNull(BucketFill.patch(doc, layer.id, coverage, color, 1f, false))
        assertNull(BucketFill.apply(doc, layer.id, coverage, color, 1f, false))
        assertNull(BucketFill.command(doc, layer.id, coverage, color, 1f, false))
        assertNull(LassoFill.apply(doc, layer.id, poly, color, 1f, false))
        assertNull(SelectionEdits.fill(doc, color))
        assertNull(SelectionEdits.fillOutside(doc, color))
        assertNull(SelectionEdits.eraseOutside(doc))
        assertNull(SelectionEdits.delete(doc))
        assertNull(SelectionEdits.cut(doc))
        assertNull(SelectionEdits.isolate(doc))
        val pixel = FloatArray(4)
        layer.pixels.getPixel(2, 2, pixel)
        assertEquals(1f, pixel[0])
        assertEquals(0f, pixel[2])
        assertEquals(modified, doc.modified)

        outer.locked = false
        val command = assertNotNull(SelectionEdits.fill(doc, color))
        val history = History()
        history.execute(doc, command)
        layer.pixels.getPixel(2, 2, pixel)
        assertEquals(1f, pixel[2])
        history.undo(doc)
        layer.pixels.getPixel(2, 2, pixel)
        assertEquals(1f, pixel[0])
    }
}
