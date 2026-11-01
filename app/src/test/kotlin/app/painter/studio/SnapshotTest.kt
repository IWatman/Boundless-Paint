package app.painter.studio

import app.painter.core.color.Rgba
import app.painter.core.doc.Animation
import app.painter.core.doc.AnimationMeta
import app.painter.core.doc.AnimationPlayback
import app.painter.core.doc.AudioClip
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.RasterLayer
import app.painter.core.doc.VectorLayer
import app.painter.core.doc.Video
import app.painter.core.doc.VideoAudio
import app.painter.core.io.ProjectIO
import app.painter.core.io.SaveTracker
import app.painter.core.vector.VectorPoint
import app.painter.core.vector.VectorStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

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

    /** An animation converted to video mode: three frames at 12 fps plus one audio clip. */
    private fun videoDocument(): Document {
        val doc = Document(32, 32, name = "Video")
        val group = GroupNode("frames", "Animation")
        doc.root.add(group)
        repeat(3) { i -> group.add(doc.newRasterLayer("Frame ${i + 1}")) }
        doc.animation = AnimationMeta(fps = 12, frameGroupId = group.id)
        doc.activeId = Animation.frames(doc).first().id
        Animation.normalize(doc)
        doc.video = Video.fromAnimation(doc, trackName = "Drawing")!!.copy(
            audio = VideoAudio(
                clips = listOf(AudioClip("aclip", "audio/voice.m4a", "Voice", 0, 300, 0, 0.7f)),
            ),
        )
        return doc
    }

    @Test
    fun snapshotsKeepVideoMetadata() {
        val doc = videoDocument()
        for (pixels in listOf(true, false)) {
            val snapshot = snapshotDocument(doc, pixels = pixels)
            assertEquals(doc.video, snapshot.video)
            assertEquals("audio/voice.m4a", snapshot.video!!.audio!!.clips.single().file)
        }
        val plain = Document(16, 16, name = "Plain")
        assertNull(snapshotDocument(plain).video)
    }

    @Test
    fun autosaveDeltaKeepsVideoBlock() {
        val doc = videoDocument()
        val dir = createTempDirectory("painter-snapshot-video").toFile()
        try {
            ProjectIO.save(doc, dir, null)
            val tracker = SaveTracker()
            tracker.commitAll(doc)

            // Autosave changes no pixels: the manifest still has to carry the video block through the snapshot.
            doc.video = doc.video!!.copy(tracks = doc.video!!.tracks.map { it.copy(name = "Autosaved") })
            val delta = tracker.collect(doc)
            val snapshot = snapshotDocument(doc, pixels = false)
            ProjectIO.saveDelta(snapshot, dir, delta)

            val loaded = ProjectIO.load(dir)
            assertEquals(doc.video, loaded.video)
            assertEquals("Autosaved", loaded.video!!.tracks.single().name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
