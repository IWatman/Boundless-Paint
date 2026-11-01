package app.painter.core.video

import app.painter.core.doc.AudioClip
import app.painter.core.doc.VideoAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure sample math of [AudioMix] (spec/VIDEO-MODE.md §9.1). */
class AudioMixTest {

    /** Source that returns a constant stereo value; useful for gain and clipping checks. */
    private class ConstantSource(
        override val frameCount: Long,
        private val left: Float,
        private val right: Float = left,
    ) : AudioSource {
        override fun read(startFrame: Long, count: Int, out: FloatArray, outOffset: Int): Int {
            for (i in 0 until count) {
                out[outOffset + i * AudioMix.CHANNELS] = left
                out[outOffset + i * AudioMix.CHANNELS + 1] = right
            }
            return count
        }
    }

    /** Source whose sample value is `frame / scale`, so wrapping and offsets are observable. */
    private class RampSource(override val frameCount: Long, private val scale: Float = 100_000f) : AudioSource {
        override fun read(startFrame: Long, count: Int, out: FloatArray, outOffset: Int): Int {
            for (i in 0 until count) {
                val value = ((startFrame + i) % frameCount) / scale
                out[outOffset + i * AudioMix.CHANNELS] = value
                out[outOffset + i * AudioMix.CHANNELS + 1] = value
            }
            return count
        }
    }

    private fun clip(
        id: String = "clip",
        file: String = "audio/a.m4a",
        startTick: Long = 0L,
        durationTicks: Long = 60L,
        offset: Long = 0L,
        volume: Float = 1f,
    ) = AudioClip(id, file, "Audio", startTick, durationTicks, offset, volume)

    private fun mix(
        startFrame: Long = 0L,
        frames: Int,
        clips: List<AudioClip>,
        sources: Map<String, AudioSource>,
        track: Float = 1f,
        master: Float = 1f,
        muted: Boolean = false,
    ): FloatArray {
        val out = FloatArray(frames * AudioMix.CHANNELS)
        AudioMix.mixChunk(startFrame, frames, clips, sources, track, master, muted, out)
        return out
    }

    @Test
    fun framesAtTickUsesIntegerSampleRate() {
        assertEquals(0L, AudioMix.framesAtTick(0L))
        assertEquals(800L, AudioMix.framesAtTick(1L))
        assertEquals(48_000L, AudioMix.framesAtTick(60L))
        assertEquals(240_000L, AudioMix.totalFrames(300L))
        assertEquals(0L, AudioMix.totalFrames(0L))
        assertEquals(0L, AudioMix.totalFrames(-10L))
    }

    @Test
    fun gainMultipliesAndClampsAtTwo() {
        assertEquals(0.125f, AudioMix.gain(0.5f, 0.5f, 0.5f), 1e-6f)
        assertEquals(1f, AudioMix.gain(1f, 1f, 1f), 1e-6f)
        assertEquals(AudioMix.MAX_GAIN, AudioMix.gain(2f, 2f, 2f), 1e-6f)
        assertEquals(0f, AudioMix.gain(-1f, 1f, 1f), 1e-6f)
    }

    @Test
    fun sourceFrameHonoursStartEndOffsetAndWrap() {
        val c = clip(startTick = 10L, durationTicks = 20L, offset = 5L)
        assertEquals(4000L, AudioMix.sourceFrame(c, 8000L, 100_000L))
        assertEquals(19_999L, AudioMix.sourceFrame(c, 23_999L, 100_000L))
        assertEquals(-1L, AudioMix.sourceFrame(c, 7999L, 100_000L))
        assertEquals(-1L, AudioMix.sourceFrame(c, 24_000L, 100_000L))
        assertEquals(4000L, AudioMix.sourceFrame(c, 8000L, 10_000L))
        assertEquals(9000L, AudioMix.sourceFrame(c, 13_000L, 10_000L))
        assertEquals(0L, AudioMix.sourceFrame(c, 14_000L, 10_000L))
    }

    @Test
    fun mixesOverlappingClipsWithGain() {
        val sources = mapOf(
            "audio/a.m4a" to ConstantSource(48_000L, 0.5f),
            "audio/b.m4a" to ConstantSource(48_000L, 1f),
        )
        val clips = listOf(clip(durationTicks = 60L), clip(id = "b", file = "audio/b.m4a", startTick = 30L))
        val out = mix(frames = 48_000, clips = clips, sources = sources, track = 0.5f, master = 0.5f)
        // Before the second clip: 0.5 × 1 × 0.5 × 0.5 = 0.125; after: 0.125 + 0.25 = 0.375.
        assertEquals(0.125f, out[0], 1e-6f)
        assertEquals(0.125f, out[(30 * 800 - 2) * 2], 1e-6f)
        assertEquals(0.375f, out[30 * 800 * 2], 1e-6f)
        assertEquals(0.375f, out[35 * 800 * 2], 1e-6f)
        assertEquals(0.375f, out[35 * 800 * 2 + 1], 1e-6f)
    }

    @Test
    fun monoAndStereoChannelsAreKept() {
        val stereo = ConstantSource(10_000L, 0.25f, -0.25f)
        val out = mix(frames = 10, clips = listOf(clip(durationTicks = 1L)), sources = mapOf("audio/a.m4a" to stereo))
        assertEquals(0.25f, out[0], 1e-6f)
        assertEquals(-0.25f, out[1], 1e-6f)
    }

    @Test
    fun longClipLoopsAtTheSourceEnd() {
        val ramp = RampSource(1000L)
        val out = mix(
            frames = 2000,
            clips = listOf(clip(durationTicks = 60L)),
            sources = mapOf("audio/a.m4a" to ramp),
        )
        assertEquals(out[0], out[1000 * 2], 1e-6f)
        assertEquals(out[2], out[1000 * 2 + 2], 1e-6f)
        assertEquals(999f / 100_000f, out[999 * 2], 1e-6f)
        assertEquals(0f, out[1000 * 2], 1e-6f)
    }

    @Test
    fun sourceOffsetSkipsTheBeginning() {
        val ramp = RampSource(10_000L)
        val out = mix(
            frames = 10,
            clips = listOf(clip(durationTicks = 1L, offset = 5L)),
            sources = mapOf("audio/a.m4a" to ramp),
        )
        assertEquals(4000f / 100_000f, out[0], 1e-6f)
        assertEquals(4001f / 100_000f, out[2], 1e-6f)
    }

    @Test
    fun hardClampsTheSumOfOverlappingFullGainClips() {
        val source = ConstantSource(10_000L, 1f)
        val clips = listOf(clip(), clip(id = "b"))
        val out = mix(frames = 10, clips = clips, sources = mapOf("audio/a.m4a" to source))
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(1f, out[1], 1e-6f)
    }

    @Test
    fun mutedMissingGapAndSilenceProduceSilence() {
        val source = ConstantSource(10_000L, 1f)
        val muted = mix(frames = 10, clips = listOf(clip()), sources = mapOf("audio/a.m4a" to source), muted = true)
        assertArrayEquals(FloatArray(20), muted, 0f)
        val missing = mix(frames = 10, clips = listOf(clip()), sources = emptyMap())
        assertArrayEquals(FloatArray(20), missing, 0f)
        val gap = mix(frames = 10, clips = listOf(clip(startTick = 60L)), sources = mapOf("audio/a.m4a" to source))
        assertArrayEquals(FloatArray(20), gap, 0f)
    }

    @Test
    fun chunkedMixEqualsOneChunk() {
        val source = RampSource(3000L)
        val clips = listOf(clip(durationTicks = 5L), clip(id = "b", startTick = 2L, volume = 0.5f))
        val sources = mapOf("audio/a.m4a" to source)
        val whole = mix(frames = 4000, clips = clips, sources = sources, track = 0.75f, master = 0.9f)
        val chunked = FloatArray(4000 * AudioMix.CHANNELS)
        var offset = 0
        var frame = 0L
        for (size in intArrayOf(111, 800, 1, 1500, 1588)) {
            val out = mix(startFrame = frame, frames = size, clips = clips, sources = sources, track = 0.75f, master = 0.9f)
            System.arraycopy(out, 0, chunked, offset, out.size)
            offset += out.size
            frame += size
        }
        assertArrayEquals(whole, chunked, 1e-6f)
    }

    @Test
    fun cacheKeyChangesOnEveryAudioEdit() {
        val audio = VideoAudio(
            clips = listOf(
                AudioClip("a", "audio/a.m4a", "A", 0, 60, 0, 1f),
                AudioClip("b", "audio/b.m4a", "B", 60, 60, 0, 1f),
            ),
        )
        val facts = mapOf(
            "audio/a.m4a" to AudioMix.FileFacts(100L, 1L),
            "audio/b.m4a" to AudioMix.FileFacts(200L, 2L),
        )
        val base = AudioMix.cacheKey(audio, 120L, facts)
        assertEquals(base, AudioMix.cacheKey(audio.copy(clips = audio.clips.reversed()), 120L, facts))
        assertNotEquals(base, AudioMix.cacheKey(audio, 121L, facts))
        assertNotEquals(base, AudioMix.cacheKey(audio.copy(volume = 0.5f), 120L, facts))
        assertNotEquals(base, AudioMix.cacheKey(audio.copy(masterVolume = 0.5f), 120L, facts))
        assertNotEquals(base, AudioMix.cacheKey(audio.copy(muted = true), 120L, facts))
        assertNotEquals(base, AudioMix.cacheKey(audio.copy(clips = listOf(audio.clips[0].copy(volume = 0.5f), audio.clips[1])), 120L, facts))
        assertNotEquals(base, AudioMix.cacheKey(audio.copy(clips = listOf(audio.clips[0].copy(startTick = 1), audio.clips[1])), 120L, facts))
        assertNotEquals(base, AudioMix.cacheKey(audio.copy(clips = listOf(audio.clips[0].copy(durationTicks = 59), audio.clips[1])), 120L, facts))
        assertNotEquals(base, AudioMix.cacheKey(audio.copy(clips = listOf(audio.clips[0].copy(sourceOffsetTicks = 1), audio.clips[1])), 120L, facts))
        val changedFile = facts + ("audio/a.m4a" to AudioMix.FileFacts(101L, 1L))
        assertNotEquals(base, AudioMix.cacheKey(audio, 120L, changedFile))
        val changedTime = facts + ("audio/a.m4a" to AudioMix.FileFacts(100L, 9L))
        assertNotEquals(base, AudioMix.cacheKey(audio, 120L, changedTime))
        assertTrue(base.length == 64)
    }
}
