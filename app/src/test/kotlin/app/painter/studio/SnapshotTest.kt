package app.painter.studio

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.VectorLayer
import app.painter.core.vector.VectorPoint
import app.painter.core.vector.VectorStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTest {
    @Test
    fun snapshotsKeepVectorLayerAndItsPaths() {
        val doc = Document(64, 64, name = "Vectors")
        val layer = doc.newVectorLayer("Vector 1")
        val stroke = VectorStroke(
            points = listOf(VectorPoint(10f, 10f, 1f), VectorPoint(30f, 30f, 0.5f)),
            color = Rgba.BLACK,
            seed = 7L,
        )
        layer.appendStroke(stroke)
        doc.root.add(layer)
        doc.activeId = layer.id

        for (pixels in listOf(true, false)) {
            val snapshot = snapshotDocument(doc, pixels = pixels)
            val copy = snapshot.findNode(layer.id)
            assertTrue(copy is VectorLayer)
            assertEquals(layer.strokes, (copy as VectorLayer).strokes)
        }
    }
}
