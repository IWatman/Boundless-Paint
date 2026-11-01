package app.painter.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VideoEditTest {

    private fun animationDoc(frames: Int = 3, fps: Int = 12): Document {
        val doc = Document(16, 16, name = "Animation")
        val group = GroupNode("frames", "Frames")
        doc.root.add(group)
        for (i in 0 until frames) group.add(doc.newRasterLayer("Frame ${i + 1}"))
        doc.animation = AnimationMeta(fps = fps, frameGroupId = group.id)
        doc.activeId = Animation.frames(doc).firstOrNull()?.id
        return doc
    }

    private fun baseMeta(doc: Document): VideoMeta {
        val meta = assertNotNull(Video.fromAnimation(doc))
        // Three clips of five ticks: 0-5, 5-10, 10-15.
        return meta
    }

    private fun track(meta: VideoMeta): VideoTrack = meta.tracks.single()

    private fun assertInvariant(meta: VideoMeta, buttJointed: Boolean = true) {
        val clips = track(meta).clips
        assertEquals(clips.sortedBy { it.startTick }, clips, "clips must stay sorted")
        for (clip in clips) {
            assertTrue(clip.startTick >= 0L, "negative start: $clip")
            assertTrue(clip.durationTicks > 0L, "zero duration: $clip")
        }
        for (i in 1 until clips.size) {
            val previous = clips[i - 1]
            val current = clips[i]
            val end = previous.startTick + previous.durationTicks
            assertTrue(end <= current.startTick, "overlap: $previous / $current")
            if (buttJointed) assertEquals(end, current.startTick, "gap before $current")
        }
        if (clips.isNotEmpty()) assertEquals(0L, clips.first().startTick, "timeline must start at 0")
    }

    private fun assertNormalizeKeeps(doc: Document, meta: VideoMeta) {
        val normalized = assertNotNull(Video.normalize(meta, doc, clearOrphaned = false))
        assertEquals(track(meta).clips, track(normalized).clips)
    }

    @Test
    fun addAfterSelectionCopiesTheDurationAndRipples() {
        val doc = animationDoc()
        val meta = baseMeta(doc)
        val original = track(meta)
        val first = original.clips[0]
        val edited = VideoEdit.add(
            meta = meta,
            trackId = original.id,
            afterClipId = first.id,
            clipId = "hold",
            frameId = first.frameId,
            durationTicks = first.durationTicks,
            playheadTick = 12,
        )
        val clips = track(edited).clips
        assertEquals(listOf(first.id, "hold", original.clips[1].id, original.clips[2].id), clips.map { it.id })
        assertEquals(listOf(0L, 5L, 10L, 15L), clips.map { it.startTick })
        assertEquals(listOf(5L, 5L, 5L, 5L), clips.map { it.durationTicks })
        assertEquals(first.frameId, clips[1].frameId)
        assertEquals(20L, Video.durationTicks(edited))
        assertInvariant(edited)
        assertNormalizeKeeps(doc, edited)
        // The edit is copy-on-write: the source block is untouched.
        assertEquals(original.clips, track(meta).clips)
    }

    @Test
    fun addWithoutSelectionUsesThePlayheadSlot() {
        val doc = animationDoc()
        val meta = baseMeta(doc)
        val original = track(meta)

        val atStart = VideoEdit.add(meta, original.id, null, "new", original.clips[0].frameId, 5, 0)
        assertEquals(listOf("new", original.clips[0].id, original.clips[1].id, original.clips[2].id),
            track(atStart).clips.map { it.id })
        assertEquals(listOf(0L, 5L, 10L, 15L), track(atStart).clips.map { it.startTick })
        assertInvariant(atStart)

        val inside = VideoEdit.add(meta, original.id, null, "new", original.clips[0].frameId, 5, 7)
        assertEquals(listOf(original.clips[0].id, original.clips[1].id, "new", original.clips[2].id),
            track(inside).clips.map { it.id })
        assertEquals(listOf(0L, 5L, 10L, 15L), track(inside).clips.map { it.startTick })
        assertInvariant(inside)

        val atEnd = VideoEdit.add(meta, original.id, null, "new", original.clips[0].frameId, 5, 99)
        assertEquals(listOf(original.clips[0].id, original.clips[1].id, original.clips[2].id, "new"),
            track(atEnd).clips.map { it.id })
        assertEquals(listOf(0L, 5L, 10L, 15L), track(atEnd).clips.map { it.startTick })
        assertInvariant(atEnd)
    }

    @Test
    fun addBlankClipKeepsAnExplicitPause() {
        val doc = animationDoc()
        val meta = baseMeta(doc)
        val original = track(meta)
        val edited = VideoEdit.add(
            meta, original.id, original.clips[1].id, "blank", null,
            VideoEdit.DEFAULT_CLIP_TICKS, 0,
        )
        val clips = track(edited).clips
        assertEquals(listOf("blank"), clips.filter { it.frameId == null }.map { it.id })
        assertEquals(2, clips.indexOfFirst { it.id == "blank" })
        assertEquals(70L, clips[3].startTick)
        assertEquals(60L, clips[2].durationTicks)
        assertInvariant(edited)
    }

    @Test
    fun deleteRipplesFollowingClipsLeft() {
        val doc = animationDoc()
        val meta = baseMeta(doc)
        val original = track(meta)
        val edited = VideoEdit.delete(meta, original.id, original.clips[1].id)
        val clips = track(edited).clips
        assertEquals(listOf(original.clips[0].id, original.clips[2].id), clips.map { it.id })
        assertEquals(listOf(0L, 5L), clips.map { it.startTick })
        assertEquals(10L, Video.durationTicks(edited))
        assertInvariant(edited)
        assertNormalizeKeeps(doc, edited)
    }

    @Test
    fun trimChangesTheRightEdgeAndRipples() {
        val doc = animationDoc()
        val meta = baseMeta(doc)
        val original = track(meta)
        val first = original.clips[0]

        val longer = VideoEdit.trim(meta, original.id, first.id, 8)
        assertEquals(listOf(8L, 5L, 5L), track(longer).clips.map { it.durationTicks })
        assertEquals(listOf(0L, 8L, 13L), track(longer).clips.map { it.startTick })
        assertInvariant(longer)
        assertNormalizeKeeps(doc, longer)

        val shorter = VideoEdit.trim(meta, original.id, first.id, 3)
        assertEquals(listOf(3L, 5L, 5L), track(shorter).clips.map { it.durationTicks })
        assertEquals(listOf(0L, 3L, 8L), track(shorter).clips.map { it.startTick })
        assertInvariant(shorter)
        assertNormalizeKeeps(doc, shorter)
    }

    @Test
    fun trimToZeroDeletesTheClip() {
        val doc = animationDoc()
        val meta = baseMeta(doc)
        val original = track(meta)
        val deleted = VideoEdit.trim(meta, original.id, original.clips[1].id, 0)
        assertEquals(listOf(original.clips[0].id, original.clips[2].id), track(deleted).clips.map { it.id })
        assertEquals(listOf(0L, 5L), track(deleted).clips.map { it.startTick })
        assertEquals(10L, Video.durationTicks(deleted))
        assertInvariant(deleted)

        val negative = VideoEdit.trim(meta, original.id, original.clips[1].id, -4)
        assertEquals(track(deleted).clips, track(negative).clips)
    }

    @Test
    fun moveRipplesTheTargetSlotAndKeepsOrder() {
        val doc = animationDoc()
        val meta = baseMeta(doc)
        val original = track(meta)
        val a = original.clips[0]
        val b = original.clips[1]
        val c = original.clips[2]

        val toStart = VideoEdit.move(meta, original.id, c.id, 0)
        assertEquals(listOf(c.id, a.id, b.id), track(toStart).clips.map { it.id })
        assertEquals(listOf(0L, 5L, 10L), track(toStart).clips.map { it.startTick })
        assertInvariant(toStart)
        assertNormalizeKeeps(doc, toStart)

        val toEnd = VideoEdit.move(meta, original.id, a.id, 99)
        assertEquals(listOf(b.id, c.id, a.id), track(toEnd).clips.map { it.id })
        assertEquals(listOf(0L, 5L, 10L), track(toEnd).clips.map { it.startTick })
        assertInvariant(toEnd)

        val beforeFirst = VideoEdit.move(meta, original.id, b.id, 1)
        assertEquals(listOf(b.id, a.id, c.id), track(beforeFirst).clips.map { it.id })
        assertInvariant(beforeFirst)

        val sameSlot = VideoEdit.move(meta, original.id, b.id, 5)
        assertEquals(listOf(a.id, b.id, c.id), track(sameSlot).clips.map { it.id })
        assertInvariant(sameSlot)
    }

    @Test
    fun editsPreserveInvariantsAcrossRepeatedOperations() {
        val doc = animationDoc(frames = 4)
        var meta = baseMeta(doc)
        var trackId = track(meta).id
        assertInvariant(meta)

        meta = VideoEdit.add(meta, trackId, track(meta).clips[1].id, "x", track(meta).clips[1].frameId, 3, 0)
        assertInvariant(meta)
        meta = VideoEdit.trim(meta, trackId, "x", 9)
        assertInvariant(meta)
        meta = VideoEdit.move(meta, trackId, "x", 0)
        assertInvariant(meta)
        meta = VideoEdit.delete(meta, trackId, track(meta).clips[0].id)
        assertInvariant(meta)
        meta = VideoEdit.add(meta, trackId, null, "blank", null, 7, 2)
        assertInvariant(meta)
        meta = VideoEdit.move(meta, trackId, "blank", 100)
        assertInvariant(meta)
        assertNormalizeKeeps(doc, meta)
    }

    @Test
    fun unknownTrackOrClipLeavesTheTimelineUnchanged() {
        val doc = animationDoc()
        val meta = baseMeta(doc)
        assertEquals(meta, VideoEdit.add(meta, "missing", null, "x", null, 5, 0))
        assertEquals(meta, VideoEdit.delete(meta, "missing", "x"))
        assertEquals(meta, VideoEdit.trim(meta, "missing", "x", 5))
        assertEquals(meta, VideoEdit.move(meta, "missing", "x", 0))
        val trackId = track(meta).id
        assertEquals(meta, VideoEdit.delete(meta, trackId, "missing"))
        assertEquals(meta, VideoEdit.trim(meta, trackId, "missing", 5))
        assertEquals(meta, VideoEdit.move(meta, trackId, "missing", 0))
    }

    @Test
    fun trackVisibilityAndSnapEdgesAreEditsOfTheMetadata() {
        val doc = animationDoc()
        val meta = baseMeta(doc)
        val track = track(meta)
        val hidden = VideoEdit.setTrackVisible(meta, track.id, false)
        assertTrue(!track(hidden).visible)
        assertEquals(meta, VideoEdit.setTrackVisible(hidden, track.id, true))

        assertEquals(
            listOf(0L, 5L, 5L, 10L, 10L, 15L),
            VideoEdit.edges(track),
        )
        assertEquals(
            listOf(0L, 5L, 10L, 15L),
            VideoEdit.edges(track, excludeId = track.clips[1].id),
        )
    }
}
