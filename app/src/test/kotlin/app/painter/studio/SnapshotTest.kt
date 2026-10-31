package app.painter.studio

import app.painter.core.color.Rgba
import app.painter.core.doc.Animation
import app.painter.core.doc.AnimationMeta
import app.painter.core.doc.AnimationPlayback
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.RasterLayer
import app.painter.core.doc.VectorLayer
import app.painter.core.vector.VectorPoint
import app.painter.core.vector.VectorStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun snapshotsKeepAnimationMetadataAndFrameOrder() {
        val doc = Document(32, 32, name = "Animation")
        val group = GroupNode("frames", "Animation")
        doc.root.add(group)
        val first = doc.newRasterLayer("Frame 1")
        val second = doc.newRasterLayer("Frame 2")
        val third = doc.newRasterLayer("Frame 3")
        group.add(first)
        group.add(second)
        group.add(third)
        doc.animation = AnimationMeta(
            fps = 24,
            playback = AnimationPlayback.PINGPONG,
            onionSkin = true,
            onionBefore = 2,
            onionAfter = 1,
            frameGroupId = group.id,
        )
        doc.activeId = second.id
        Animation.normalize(doc)

        for (pixels in listOf(true, false)) {
            val snapshot = snapshotDocument(doc, pixels = pixels)
            assertEquals(doc.animation, snapshot.animation)
            assertEquals(
                listOf(first.id, second.id, third.id),
                Animation.frames(snapshot).map { it.id },
            )
            assertEquals(second.id, snapshot.activeId)
            assertTrue(snapshot.findNode(second.id)!!.visible)
            assertFalse(snapshot.findNode(first.id)!!.visible)
            assertTrue(snapshot.findNode(group.id) is GroupNode)
            if (pixels) {
                assertTrue(snapshot.findNode(first.id) is RasterLayer)
            }
        }
    }
}
