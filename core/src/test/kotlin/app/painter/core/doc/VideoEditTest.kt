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

    /** Adds a second frame group with [frames] layers and returns its track, without touching [meta]. */
    private fun secondTrack(doc: Document, frames: Int = 2, name: String = "Drawing 2"): VideoTrack {
        val group = GroupNode("frames-b", "Frames B")
        doc.root.add(group)
        repeat(frames) { i -> group.add(doc.newRasterLayer("B${i + 1}")) }
        val layers = group.children.filterIsInstance<RasterLayer>()
        return VideoTrack(
            id = "vtrack-b",
            name = name,
            groupId = group.id,
            clips = layers.mapIndexed { i, layer ->
                VideoClip("b-$i", layer.id, i * 5L, 5L)
            },
        )
    }

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

    @Test
    fun addTrackAddsASecondTrackOnlyOnce() {
        val doc = animationDoc()
        val meta = baseMeta(doc)
        val second = secondTrack(doc)

        val added = VideoEdit.addTrack(meta, second)
        assertEquals(listOf(track(meta).id, second.id), added.tracks.map { it.id })
        assertEquals(2, added.tracks.size)
        assertEquals(second.clips, added.tracks[1].clips)
        // The source block is untouched (copy-on-write).
        assertEquals(1, meta.tracks.size)

        // More than two is rejected, and a track must own a unique id and frame group.
        val third = second.copy(id = "vtrack-c", groupId = "frames-c", name = "Drawing 3")
        assertEquals(added, VideoEdit.addTrack(added, third))
        assertEquals(added, VideoEdit.addTrack(added, second.copy(groupId = "frames-c")))
        assertEquals(added, VideoEdit.addTrack(added, second.copy(id = "vtrack-c")))
        assertEquals(VideoEdit.MAX_TRACKS, added.tracks.size)
    }

    @Test
    fun deleteTrackKeepsTheOtherTrackAndItsClips() {
        val doc = animationDoc()
        val first = track(baseMeta(doc))
        val second = secondTrack(doc)
        val meta = VideoEdit.addTrack(baseMeta(doc), second)

        val deleted = VideoEdit.deleteTrack(meta, second.id)
        assertEquals(listOf(first.id), deleted.tracks.map { it.id })
        assertEquals(first.clips, deleted.tracks.single().clips)

        val last = VideoEdit.deleteTrack(deleted, first.id)
        assertTrue(last.tracks.isEmpty())
        // Unknown tracks and repeated deletion change nothing.
        assertEquals(meta, VideoEdit.deleteTrack(meta, "missing"))
        assertEquals(deleted, VideoEdit.deleteTrack(deleted, second.id))
    }

    @Test
    fun clipEditsStayInsideTheirTrack() {
        val doc = animationDoc()
        val firstMeta = baseMeta(doc)
        val first = track(firstMeta)
        val second = secondTrack(doc)
        val meta = VideoEdit.addTrack(firstMeta, second)

        // An added clip belongs to the target track only.
        val added = VideoEdit.add(meta, second.id, second.clips[0].id, "hold", second.clips[0].frameId, 5, 0)
        assertEquals(first.clips, trackOf(added, first.id).clips)
        assertEquals(listOf("b-0", "hold", "b-1"), trackOf(added, second.id).clips.map { it.id })
        assertEquals(second.id, VideoEdit.trackOfClip(added, "hold")?.id)

        // Delete, trim and move of a second-track clip leave the first track alone.
        val deleted = VideoEdit.delete(added, second.id, "hold")
        assertEquals(first.clips, trackOf(deleted, first.id).clips)
        val trimmed = VideoEdit.trim(added, second.id, "b-0", 8)
        assertEquals(first.clips, trackOf(trimmed, first.id).clips)
        assertEquals(8L, trackOf(trimmed, second.id).clips.first().durationTicks)
        val moved = VideoEdit.move(added, second.id, "b-1", 0)
        assertEquals(first.clips, trackOf(moved, first.id).clips)
        assertEquals(listOf("b-1", "b-0", "hold"), trackOf(moved, second.id).clips.map { it.id })

        // Editing the first track leaves the second one alone.
        val firstTrimmed = VideoEdit.trim(added, first.id, first.clips[0].id, 3)
        assertEquals(trackOf(added, second.id).clips, trackOf(firstTrimmed, second.id).clips)
        // A clip is owned by exactly one track.
        assertEquals(first.id, VideoEdit.trackOfClip(firstTrimmed, first.clips[0].id)?.id)
    }

    @Test
    fun normalizeKeepsBothTracksAndPrunesOnlyOrphanedOnLoad() {
        val doc = animationDoc()
        val firstMeta = baseMeta(doc)
        val second = secondTrack(doc)
        val meta = VideoEdit.addTrack(firstMeta, second).let { two ->
            two.copy(
                tracks = listOf(
                    two.tracks[0].copy(
                        clips = two.tracks[0].clips + VideoClip("late", two.tracks[0].clips[0].frameId, 20, 10),
                    ),
                    two.tracks[1],
                ),
            )
        }

        val normalized = assertNotNull(Video.normalize(meta, doc, clearOrphaned = false))
        assertEquals(2, normalized.tracks.size)
        assertEquals(listOf("late"), normalized.tracks[0].clips.drop(3).map { it.id })
        assertEquals(second.clips, normalized.tracks[1].clips)
        assertEquals(30L, Video.durationTicks(normalized))

        // A track whose group vanished is kept while editing and dropped on load; the other track survives.
        val broken = meta.copy(tracks = listOf(meta.tracks[0], meta.tracks[1].copy(groupId = "gone")))
        assertEquals(2, Video.normalize(broken, doc, clearOrphaned = false)!!.tracks.size)
        val loaded = assertNotNull(Video.normalize(broken, doc, clearOrphaned = true))
        assertEquals(listOf(meta.tracks[0].id), loaded.tracks.map { it.id })
    }

    @Test
    fun visibleFramesCompositesBothTracksAndKeepsGapsBlank() {
        val doc = animationDoc()
        val first = track(baseMeta(doc))
        val second = secondTrack(doc)
        val meta = VideoEdit.addTrack(VideoMeta(tracks = listOf(first)), second)
        val firstFrame = first.clips[0].frameId!!
        val secondFrame = second.clips[0].frameId!!

        val both = Video.visibleFrames(doc, meta, 0)
        assertEquals(true, both[firstFrame])
        assertEquals(true, both[secondFrame])
        assertEquals(true, both[first.groupId])
        assertEquals(true, both[second.groupId])

        // Hide track 1: its frame and group are hidden, track 2 stays visible.
        val hiddenFirst = Video.visibleFrames(
            doc,
            meta.copy(tracks = listOf(meta.tracks[0].copy(visible = false), meta.tracks[1])),
            0,
        )
        assertEquals(false, hiddenFirst[firstFrame])
        assertEquals(false, hiddenFirst[first.groupId])
        assertEquals(true, hiddenFirst[secondFrame])

        // A gap on track 2 does not leak the first track's frame into it.
        val gapped = Video.visibleFrames(
            doc,
            meta.copy(
                tracks = listOf(
                    meta.tracks[0],
                    meta.tracks[1].copy(clips = listOf(VideoClip("gap", null, 0, 5))),
                ),
            ),
            0,
        )
        assertEquals(true, gapped[firstFrame])
        assertEquals(false, gapped[secondFrame])
    }

    private fun trackOf(meta: VideoMeta, id: String): VideoTrack = meta.tracks.first { it.id == id }
}
