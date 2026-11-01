package app.painter.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoTest {

    private fun animationDoc(
        frames: Int,
        fps: Int = 12,
        playback: AnimationPlayback = AnimationPlayback.LOOP,
    ): Document {
        val doc = Document(16, 16, name = "Animation")
        val group = GroupNode("frames", "Frames")
        doc.root.add(group)
        for (i in 0 until frames) group.add(doc.newRasterLayer("Frame ${i + 1}"))
        doc.animation = AnimationMeta(fps = fps, playback = playback, frameGroupId = group.id)
        doc.activeId = Animation.frames(doc).firstOrNull()?.id
        return doc
    }

    private fun framesOf(doc: Document): List<RasterLayer> = Animation.frames(doc)

    @Test
    fun conversionMakesOneClipPerStep() {
        val doc = animationDoc(3, fps = 12)
        val meta = assertNotNull(Video.fromAnimation(doc, trackName = "Drawing"))
        val track = meta.tracks.single()
        val frames = framesOf(doc)

        assertEquals("vtrack-${doc.animation!!.frameGroupId}", track.id)
        assertEquals("Drawing", track.name)
        assertEquals(doc.animation!!.frameGroupId, track.groupId)
        assertTrue(track.visible)
        assertEquals(frames.map { it.id }, track.clips.map { it.frameId })
        assertEquals(listOf(0L, 5L, 10L), track.clips.map { it.startTick })
        assertEquals(listOf(5L, 5L, 5L), track.clips.map { it.durationTicks })
        assertEquals(
            listOf("vclip-0-${frames[0].id}", "vclip-1-${frames[1].id}", "vclip-2-${frames[2].id}"),
            track.clips.map { it.id },
        )
        assertEquals(15L, Video.durationTicks(meta))
        assertNull(meta.audio)
        // The source animation is kept untouched for older builds.
        assertEquals(12, doc.animation!!.fps)
        assertEquals(AnimationPlayback.LOOP, doc.animation!!.playback)
    }

    @Test
    fun pingPongExpandsIntoOneCycle() {
        val doc = animationDoc(4, fps = 12, playback = AnimationPlayback.PINGPONG)
        val frames = framesOf(doc)
        val track = assertNotNull(Video.fromAnimation(doc)).tracks.single()
        assertEquals(
            listOf(frames[0].id, frames[1].id, frames[2].id, frames[3].id, frames[2].id, frames[1].id),
            track.clips.map { it.frameId },
        )
        assertEquals(30L, Video.durationTicks(VideoMeta(tracks = listOf(track))))
    }

    @Test
    fun oneFrameAnimationConvertsForEveryPlaybackMode() {
        for (mode in AnimationPlayback.entries) {
            val doc = animationDoc(1, fps = 12, playback = mode)
            val meta = assertNotNull(Video.fromAnimation(doc))
            assertEquals(1, meta.tracks.single().clips.size)
            assertEquals(5L, meta.tracks.single().clips.single().durationTicks)
            assertEquals(5L, Video.durationTicks(meta))
        }
    }

    @Test
    fun emptyAnimationDoesNotConvert() {
        assertNull(Video.fromAnimation(Document(8, 8)))
        val doc = Document(8, 8)
        val group = GroupNode("frames", "Frames")
        doc.root.add(group)
        doc.animation = AnimationMeta(frameGroupId = group.id)
        assertNull(Video.fromAnimation(doc))
    }

    @Test
    fun tickBoundariesRoundHalfUpWithoutDrift() {
        val expected = mapOf(
            60 to listOf(1L, 2L, 12L),
            30 to listOf(2L, 4L, 24L),
            24 to listOf(3L, 5L, 30L),
            12 to listOf(5L, 10L, 60L),
            1 to listOf(60L, 120L, 720L),
        )
        for ((fps, ticks) in expected) {
            assertEquals(0L, Video.tickAt(0, fps))
            assertEquals(ticks[0], Video.tickAt(1, fps), "fps $fps, step 1")
            assertEquals(ticks[1], Video.tickAt(2, fps), "fps $fps, step 2")
            assertEquals(ticks[2], Video.tickAt(12, fps), "fps $fps, step 12")
        }
        // Half a tick rounds up.
        assertEquals(2L, Video.tickAt(1, 40))
        assertEquals(1L, Video.tickAt(1, 60))
        assertEquals(0L, Video.tickAt(-5, 12))
    }

    @Test
    fun sequenceExpandsPlaybackModes() {
        assertTrue(Video.sequence(0, AnimationPlayback.LOOP).isEmpty())
        assertEquals(listOf(0, 1, 2), Video.sequence(3, AnimationPlayback.LOOP))
        assertEquals(listOf(0, 1, 2), Video.sequence(3, AnimationPlayback.ONCE))
        assertEquals(listOf(0), Video.sequence(1, AnimationPlayback.PINGPONG))
        assertEquals(listOf(0, 1), Video.sequence(2, AnimationPlayback.PINGPONG))
        assertEquals(listOf(0, 1, 2, 1), Video.sequence(3, AnimationPlayback.PINGPONG))
        assertEquals(listOf(0, 1, 2, 3, 2, 1), Video.sequence(4, AnimationPlayback.PINGPONG))
    }

    @Test
    fun normalizeClampsSortsAndRipplesDrawingClips() {
        val doc = animationDoc(3)
        val frames = framesOf(doc)
        val base = assertNotNull(Video.fromAnimation(doc)).tracks.single()
        val meta = VideoMeta(
            tracks = listOf(
                base.copy(
                    clips = listOf(
                        VideoClip("b", frames[1].id, 10, 10),
                        VideoClip("a", frames[0].id, 5, 10),
                        VideoClip("zero", frames[2].id, 3, 0),
                        VideoClip("negative", frames[2].id, -2, 5),
                    ),
                ),
            ),
        )

        val normalized = assertNotNull(Video.normalize(meta, doc, clearOrphaned = false))
        val clips = normalized.tracks.single().clips
        assertEquals(listOf("negative", "a", "b"), clips.map { it.id })
        assertEquals(listOf(0L, 5L, 15L), clips.map { it.startTick })
        assertEquals(listOf(3L, 10L, 5L), clips.map { it.durationTicks })
        assertEquals(20L, Video.durationTicks(normalized))
    }

    @Test
    fun normalizeKeepsMissingFramesWhileEditingAndPrunesThemOnLoad() {
        val doc = animationDoc(2)
        val track = assertNotNull(Video.fromAnimation(doc)).tracks.single()
        val meta = VideoMeta(
            tracks = listOf(track.copy(clips = listOf(VideoClip("missing", "gone", 0, 5)))),
        )

        val kept = assertNotNull(Video.normalize(meta, doc, clearOrphaned = false))
        assertEquals(listOf("missing"), kept.tracks.single().clips.map { it.id })

        val pruned = assertNotNull(Video.normalize(meta, doc, clearOrphaned = true))
        assertTrue(pruned.tracks.single().clips.isEmpty())
    }

    @Test
    fun normalizeDropsOrphanedTracksOnlyWhenAsked() {
        val doc = animationDoc(1)
        val track = assertNotNull(Video.fromAnimation(doc)).tracks.single()
        val orphan = track.copy(groupId = "missing-group")

        assertNotNull(Video.normalize(VideoMeta(tracks = listOf(orphan)), doc, clearOrphaned = false))
        assertNull(Video.normalize(VideoMeta(tracks = listOf(orphan)), doc, clearOrphaned = true))
        assertNull(Video.normalize(VideoMeta(), doc, clearOrphaned = false))
    }

    @Test
    fun normalizeAudioClampsVolumesAndOffsetsButAllowsOverlap() {
        val doc = animationDoc(1)
        val track = assertNotNull(Video.fromAnimation(doc)).tracks.single()
        val meta = VideoMeta(
            tracks = listOf(track),
            audio = VideoAudio(
                clips = listOf(
                    AudioClip("late", "audio/b.m4a", "Late", 5, 10, volume = 0f),
                    AudioClip("early", "audio/a.m4a", "Early", -10, 20, sourceOffsetTicks = -3, volume = 2f),
                    AudioClip("zero", "audio/c.m4a", "Zero", 5, 0),
                ),
                volume = 2f,
                masterVolume = -1f,
                muted = true,
            ),
        )

        val normalized = assertNotNull(Video.normalize(meta, doc, clearOrphaned = false))
        val audio = assertNotNull(normalized.audio)
        assertEquals(listOf("early", "late"), audio.clips.map { it.id })
        assertEquals(0L, audio.clips[0].startTick)
        assertEquals(0L, audio.clips[0].sourceOffsetTicks)
        assertEquals(1f, audio.clips[0].volume)
        assertEquals(5L, audio.clips[1].startTick)
        assertEquals(1f, audio.volume)
        assertEquals(0f, audio.masterVolume)
        assertTrue(audio.muted)
        // Audio never extends the drawing duration.
        assertEquals(Video.durationTicks(VideoMeta(tracks = listOf(track))), Video.durationTicks(normalized))
    }

    @Test
    fun clipAtUsesStartInclusiveAndEndExclusive() {
        val doc = animationDoc(2)
        val clip = VideoClip("c", framesOf(doc)[0].id, 5, 5)
        val track = assertNotNull(Video.fromAnimation(doc)).tracks.single().copy(clips = listOf(clip))

        assertNull(Video.clipAt(track, 4))
        assertEquals("c", Video.clipAt(track, 5)?.id)
        assertEquals("c", Video.clipAt(track, 9)?.id)
        assertNull(Video.clipAt(track, 10))
        assertNull(Video.clipAt(track, -1))
    }

    @Test
    fun visibleFramesHidesTheWholeTrackFirst() {
        val doc = animationDoc(3)
        val frames = framesOf(doc)
        val group = Animation.group(doc)!!
        val track = assertNotNull(Video.fromAnimation(doc)).tracks.single()
        val meta = VideoMeta(tracks = listOf(track))

        val first = Video.visibleFrames(doc, meta, 0)
        assertEquals(true, first[frames[0].id])
        assertEquals(false, first[frames[1].id])
        assertEquals(false, first[frames[2].id])
        assertEquals(true, first[group.id])

        // A gap and the end of the timeline render transparent.
        val gap = Video.visibleFrames(doc, meta, 15)
        assertEquals(false, gap[frames[0].id])
        assertEquals(false, gap[frames[1].id])
        assertEquals(false, gap[frames[2].id])

        val hidden = Video.visibleFrames(doc, VideoMeta(tracks = listOf(track.copy(visible = false))), 5)
        assertEquals(false, hidden[group.id])
        assertEquals(false, hidden[frames[1].id])

        frames[1].hideInRecording = true
        val recording = Video.visibleFrames(doc, meta, 5)
        assertEquals(false, recording[frames[1].id])
    }

    @Test
    fun visibleFramesFollowClipBoundariesAndBlankClips() {
        val doc = animationDoc(2)
        val frames = framesOf(doc)
        val base = assertNotNull(Video.fromAnimation(doc)).tracks.single()
        val track = base.copy(
            clips = listOf(
                VideoClip("first", frames[0].id, 0, 5),
                VideoClip("gap", null, 5, 5),
                VideoClip("second", frames[1].id, 10, 5),
            ),
        )
        val meta = VideoMeta(tracks = listOf(track))

        assertEquals(true, Video.visibleFrames(doc, meta, 0)[frames[0].id])
        assertEquals(false, Video.visibleFrames(doc, meta, 5)[frames[0].id])
        assertEquals(true, Video.visibleFrames(doc, meta, 10)[frames[1].id])
        assertEquals(true, Video.visibleFrames(doc, meta, 14)[frames[1].id])
    }
}
