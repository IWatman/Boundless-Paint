package app.painter.core.doc

import app.painter.core.io.ProjectIO
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimationTest {

    private fun animatedDocument(): Triple<Document, GroupNode, List<RasterLayer>> {
        val doc = Document(32, 32, name = "Animation")
        val group = GroupNode("frames", "Animation")
        doc.root.add(group)
        val first = doc.newRasterLayer("Frame 1")
        val second = doc.newRasterLayer("Frame 2")
        val third = doc.newRasterLayer("Frame 3")
        group.add(first)
        group.add(second)
        group.add(third)
        doc.animation = AnimationMeta(frameGroupId = group.id)
        doc.activeId = second.id
        return Triple(doc, group, listOf(first, second, third))
    }

    @Test
    fun metadataKeepsFpsAndOnionCountsInRange() {
        val meta = AnimationMeta(fps = 0, onionBefore = -3, onionAfter = 99)
        assertEquals(AnimationMeta.MIN_FPS, meta.fps)
        assertEquals(0, meta.onionBefore)
        assertEquals(AnimationMeta.MAX_ONION_FRAMES, meta.onionAfter)

        meta.fps = 120
        meta.onionBefore = 2
        meta.onionAfter = -1
        meta.normalize()
        assertEquals(AnimationMeta.MAX_FPS, meta.fps)
        assertEquals(2, meta.onionBefore)
        assertEquals(0, meta.onionAfter)
        assertEquals(AnimationMeta.DEFAULT_FPS, AnimationMeta().fps)
    }

    @Test
    fun framesFollowGroupOrderAndOnlyTheActiveOneIsVisible() {
        val (doc, group, frames) = animatedDocument()
        assertTrue(Animation.normalize(doc))
        assertEquals(frames.map { it.id }, Animation.frames(doc).map { it.id })
        assertEquals(1, Animation.activeIndex(doc))
        assertTrue(frames[1].visible)
        assertFalse(frames[0].visible)
        assertFalse(frames[2].visible)
        assertTrue(group.visible)

        // Deleting/moving the active frame out of the group makes normalize fall back to frame 1.
        doc.activeId = null
        assertTrue(Animation.normalize(doc))
        assertEquals(frames[0].id, doc.activeId)
        assertTrue(frames[0].visible)
        assertFalse(frames[1].visible)
    }

    @Test
    fun missingFrameGroupDropsTheMetadata() {
        val doc = Document.create(8, 8)
        doc.animation = AnimationMeta(frameGroupId = "gone")
        assertFalse(Animation.normalize(doc))
        assertNull(doc.animation)
    }

    @Test
    fun orphanedMetadataCanBeKeptForRedo() {
        val doc = Document.create(8, 8)
        doc.animation = AnimationMeta(frameGroupId = "gone")
        assertFalse(Animation.normalize(doc, clearOrphaned = false))
        assertEquals("gone", doc.animation?.frameGroupId)
        assertFalse(Animation.normalize(doc))
        assertNull(doc.animation)
    }

    @Test
    fun manualStepWrapsAndPlaybackFollowsItsMode() {
        assertEquals(2, Animation.step(0, -1, 3))
        assertEquals(0, Animation.step(2, 1, 3))
        assertEquals(1, Animation.step(1, 0, 3))

        assertEquals(AnimationStep(1, true), Animation.nextStep(0, 3, AnimationPlayback.LOOP))
        assertEquals(AnimationStep(0, true), Animation.nextStep(2, 3, AnimationPlayback.LOOP))
        assertNull(Animation.nextStep(2, 3, AnimationPlayback.ONCE))
        assertEquals(AnimationStep(1, true), Animation.nextStep(0, 3, AnimationPlayback.ONCE))
        assertNull(Animation.nextStep(0, 1, AnimationPlayback.LOOP))

        var index = 0
        var forward = true
        val sequence = mutableListOf(index)
        repeat(5) {
            val step = Animation.nextStep(index, 3, AnimationPlayback.PINGPONG, forward)!!
            index = step.index
            forward = step.forward
            sequence.add(index)
        }
        assertEquals(listOf(0, 1, 2, 1, 0, 1), sequence)
    }

    @Test
    fun projectSaveReopensWithTheSameFramesAndSettings() {
        val dir: File = createTempDirectory("painter-animation").toFile()
        try {
            val (doc, group, frames) = animatedDocument()
            frames[0].pixels.setPixel(1, 1, 1f, 0f, 0f, 1f)
            frames[1].pixels.setPixel(2, 2, 0f, 1f, 0f, 1f)
            doc.animation = AnimationMeta(
                fps = 24,
                playback = AnimationPlayback.ONCE,
                onionSkin = true,
                onionBefore = 2,
                onionAfter = 0,
                frameGroupId = group.id,
            )
            assertTrue(Animation.normalize(doc))

            ProjectIO.save(doc, dir, thumbnailPng = null)
            val loaded = ProjectIO.load(dir)

            assertEquals(doc.animation, loaded.animation)
            assertEquals(frames.map { it.id }, Animation.frames(loaded).map { it.id })
            assertEquals(frames[1].id, loaded.activeId)
            assertTrue(loaded.findNode(frames[1].id)!!.visible)
            assertFalse(loaded.findNode(frames[0].id)!!.visible)
            assertFalse(loaded.findNode(frames[2].id)!!.visible)

            val pixel = FloatArray(4)
            (loaded.findNode(frames[0].id) as RasterLayer).pixels.getPixel(1, 1, pixel)
            assertEquals(1f, pixel[3], 0.01f)
            (loaded.findNode(frames[1].id) as RasterLayer).pixels.getPixel(2, 2, pixel)
            assertEquals(1f, pixel[1], 0.01f)

            // A second save/load of the loaded document keeps the frame order stable.
            ProjectIO.save(loaded, dir, thumbnailPng = null)
            val again = ProjectIO.load(dir)
            assertEquals(frames.map { it.id }, Animation.frames(again).map { it.id })
            assertEquals(doc.animation, again.animation)
        } finally {
            dir.deleteRecursively()
        }
    }
}
