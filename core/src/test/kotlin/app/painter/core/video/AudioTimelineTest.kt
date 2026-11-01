package app.painter.core.video

import app.painter.core.doc.AudioClip
import app.painter.core.doc.VideoAudio
import app.painter.core.doc.VideoMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure edits and normalization of [AudioTimeline] (spec/VIDEO-MODE.md §4.5). */
class AudioTimelineTest {

    private fun clip(
        id: String,
        start: Long = 0L,
        duration: Long = 60L,
        offset: Long = 0L,
        volume: Float = 1f,
        file: String = "audio/$id.m4a",
    ) = AudioClip(id, file, id, start, duration, offset, volume)

    private fun meta(vararg clips: AudioClip, volume: Float = 1f, master: Float = 1f, muted: Boolean = false) =
        VideoMeta(audio = VideoAudio(clips.toList(), volume, master, muted))

    @Test
    fun insertCreatesTrackSortsAndClamps() {
        val empty = VideoMeta()
        assertNull(empty.audio)
        val one = AudioTimeline.insert(empty, clip("b", start = 90L))
        assertEquals(listOf("b"), one.audio!!.clips.map { it.id })
        val two = AudioTimeline.insert(one, clip("a", start = -10L, duration = 30L))
        assertEquals(listOf("a", "b"), two.audio!!.clips.map { it.id })
        assertEquals(0L, two.audio!!.clips[0].startTick)
        assertEquals(20L, two.audio!!.clips[0].durationTicks)
    }

    @Test
    fun insertDropsZeroLengthClips() {
        val meta = meta(clip("a"))
        assertSame(meta, AudioTimeline.insert(meta, clip("b", duration = 0L)))
    }

    @Test
    fun moveAllowsOverlapAndClampsAtZero() {
        val meta = meta(clip("a", start = 0L), clip("b", start = 120L))
        val moved = AudioTimeline.move(meta, "b", -50L)
        val b = moved.audio!!.clips.first { it.id == "b" }
        assertEquals(0L, b.startTick)
        val overlapped = AudioTimeline.move(meta, "b", 30L)
        val overlapping = overlapped.audio!!.clips.first { it.id == "b" }
        assertEquals(30L, overlapping.startTick)
        assertEquals(60L, overlapping.durationTicks)
    }

    @Test
    fun trimZeroDeletesAndPositiveKeepsOtherClips() {
        val meta = meta(clip("a"), clip("b", start = 120L))
        val trimmed = AudioTimeline.trim(meta, "a", 30L)
        assertEquals(30L, trimmed.audio!!.clips.first { it.id == "a" }.durationTicks)
        assertEquals(120L, trimmed.audio!!.clips.first { it.id == "b" }.startTick)
        val deleted = AudioTimeline.trim(meta, "a", 0L)
        assertEquals(listOf("b"), deleted.audio!!.clips.map { it.id })
    }

    @Test
    fun volumesClampToZeroOneAndMuteIsIndependent() {
        val meta = meta(clip("a"))
        assertEquals(0f, AudioTimeline.setClipVolume(meta, "a", -1f).audio!!.clips.single().volume, 0f)
        assertEquals(1f, AudioTimeline.setClipVolume(meta, "a", 3f).audio!!.clips.single().volume, 0f)
        assertEquals(0.25f, AudioTimeline.setTrackVolume(meta, 0.25f).audio!!.volume, 0f)
        assertEquals(1f, AudioTimeline.setMasterVolume(meta, 2f).audio!!.masterVolume, 0f)
        assertTrue(AudioTimeline.setMuted(meta, true).audio!!.muted)
    }

    @Test
    fun normalizeSortsDropsZeroAndClampsEveryField() {
        val audio = VideoAudio(
            clips = listOf(
                AudioClip("b", "audio/b.m4a", "B", 120, -5, -3, 2f),
                AudioClip("a", "audio/a.m4a", "A", -20, 40, -1, -0.5f),
                AudioClip("c", "audio/c.m4a", "C", 0, 0, 0, 1f),
            ),
            volume = -1f,
            masterVolume = 4f,
        )
        val normalized = AudioTimeline.normalize(audio)
        assertEquals(listOf("a"), normalized.clips.map { it.id })
        assertEquals(0L, normalized.clips[0].startTick)
        assertEquals(20L, normalized.clips[0].durationTicks)
        assertEquals(0L, normalized.clips[0].sourceOffsetTicks)
        assertEquals(0f, normalized.clips[0].volume, 0f)
        assertEquals(0f, normalized.volume, 0f)
        assertEquals(1f, normalized.masterVolume, 0f)
    }

    @Test
    fun normalizeMetaKeepsTracksAndMissingAudioStaysMissing() {
        val meta = meta(clip("a", start = 60L, duration = 30L), clip("b", start = 30L))
        val normalized = AudioTimeline.normalize(meta.copy(audio = meta.audio!!.copy(volume = 5f)))
        assertEquals(listOf("b", "a"), normalized.audio!!.clips.map { it.id })
        assertNull(AudioTimeline.normalize(VideoMeta()).audio)
    }

    @Test
    fun endTickAndClampToVideo() {
        val audio = VideoAudio(clips = listOf(clip("a", start = 60L, duration = 120L), clip("b", start = 0L, duration = 30L)))
        assertEquals(180L, AudioTimeline.endTick(audio))
        val clamped = AudioTimeline.clampToVideo(audio, 100L)
        assertEquals(listOf("b", "a"), clamped.clips.map { it.id })
        assertEquals(30L, clamped.clips[0].durationTicks)
        assertEquals(40L, clamped.clips[1].durationTicks)
        assertTrue(AudioTimeline.clampToVideo(audio, 0L).clips.isEmpty())
    }

    @Test
    fun missingClipsUseProjectRelativePaths() {
        val audio = VideoAudio(clips = listOf(clip("a"), clip("b", file = "audio/b.m4a")))
        val missing = AudioTimeline.missingClips(audio) { it == "audio/a.m4a" }
        assertEquals(listOf("b"), missing.map { it.id })
    }
}
