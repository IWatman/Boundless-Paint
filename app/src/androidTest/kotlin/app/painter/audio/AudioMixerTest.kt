package app.painter.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.painter.core.doc.AudioClip
import app.painter.core.doc.VideoAudio
import app.painter.core.video.AudioMix
import app.painter.video.AudioTestAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Decodes a generated WAV, mixes two overlapping clips, encodes AAC-LC and inspects the M4A with
 * `MediaExtractor` (spec/VIDEO-MODE.md §9.2). Skips only when the device has no usable audio decoder.
 */
@RunWith(AndroidJUnit4::class)
class AudioMixerTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun projectWithWav(seconds: Double = 1.0): File {
        val project = File(context.cacheDir, "audio-mixer-${System.nanoTime()}")
        AudioTestAssets.writeWav(File(project, "audio/voice.wav"), seconds = seconds)
        return project
    }

    private fun timeline(): VideoAudio = VideoAudio(
        clips = listOf(
            AudioClip("a", "audio/voice.wav", "Voice", startTick = 0, durationTicks = 60, volume = 0.5f),
            AudioClip("b", "audio/voice.wav", "Voice 2", startTick = 30, durationTicks = 90, volume = 0.5f),
        ),
        volume = 0.8f,
        masterVolume = 0.9f,
    )

    @Test
    fun mixesOverlappingClipsIntoAacOfTheVideoDuration() {
        val project = projectWithWav()
        val output = File(context.cacheDir, "audio-mix-${System.nanoTime()}.m4a")
        try {
            try {
                AudioMixer.mix(project, timeline(), durationTicks = 120L, output = output)
            } catch (failure: AudioMixFailure) {
                if (failure.kind == AudioMixFailure.Kind.UNSUPPORTED_CODEC) {
                    Assume.assumeNoException("no usable audio decoder on this device", failure)
                }
                throw failure
            }
            assertTrue("the M4A was not written", output.length() > 0L)
            val extractor = MediaExtractor()
            extractor.setDataSource(output.absolutePath)
            try {
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
                assertEquals(AudioMix.SAMPLE_RATE, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(AudioMix.CHANNELS, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                val expectedUs = 2_000_000L
                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                assertTrue(
                    "mixed duration $durationUs is not within one AAC frame of 2 s",
                    abs(durationUs - expectedUs) <= 23_220L,
                )
                extractor.selectTrack(0)
                val buffer = java.nio.ByteBuffer.allocate(1 shl 16)
                assertTrue("no AAC samples", extractor.readSampleData(buffer, 0) >= 0)
                val first = extractor.sampleTime
                assertTrue("first AAC sample starts at $first", abs(first) <= 23_220L)
                var samples = 1
                var last = first
                extractor.advance()
                while (true) {
                    if (extractor.readSampleData(buffer, 0) < 0) break
                    last = extractor.sampleTime
                    samples++
                    extractor.advance()
                }
                assertTrue("no AAC samples", samples > 0)
                assertTrue(
                    "audio extends past the video end: last $last",
                    last <= expectedUs + 23_220L,
                )
            } finally {
                extractor.release()
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun prepareReusesTheCacheAndInvalidatesItOnAGainEdit() {
        val project = projectWithWav()
        val timeline = timeline()
        val first = AudioMixer.prepare(project, timeline, 120L, context.cacheDir)
        val second = AudioMixer.prepare(project, timeline, 120L, context.cacheDir)
        assertEquals(first.absolutePath, second.absolutePath)
        val edited = timeline.copy(masterVolume = 0.5f)
        val third = AudioMixer.prepare(project, edited, 120L, context.cacheDir)
        assertNotEquals(first.absolutePath, third.absolutePath)
    }
}
