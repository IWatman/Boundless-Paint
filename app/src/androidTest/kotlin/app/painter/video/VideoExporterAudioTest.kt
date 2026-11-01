package app.painter.video

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.painter.audio.AudioMixFailure
import app.painter.audio.AudioMixer
import app.painter.core.doc.AudioClip
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.VideoAudio
import app.painter.core.doc.VideoClip
import app.painter.core.doc.VideoMeta
import app.painter.core.doc.VideoTrack
import app.painter.core.video.VideoExportPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Muxes a generated AAC mix into the MP4 and inspects both tracks (spec/VIDEO-MODE.md §9.2): two tracks, the
 * audio starts at 0 within one AAC frame and never extends past the video end.
 */
@RunWith(AndroidJUnit4::class)
class VideoExporterAudioTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sourceDocument(durationTicks: Long = 60L): Pair<Document, VideoMeta> {
        val doc = Document(320, 240, name = "Audio export test")
        val group = GroupNode("frames", "Frames")
        doc.root.add(group)
        val frame = doc.newRasterLayer("Frame 1")
        frame.pixels.setPixel(10, 10, 1f, 0f, 0f, 1f)
        group.add(frame)
        val meta = VideoMeta(
            tracks = listOf(
                VideoTrack(
                    id = "track",
                    name = "Drawing",
                    groupId = group.id,
                    clips = listOf(VideoClip("clip", frame.id, startTick = 0L, durationTicks = durationTicks)),
                ),
            ),
        )
        doc.video = meta
        doc.activeId = frame.id
        return doc to meta
    }

    private fun encoderOrSkip(): AvcEncoder {
        val encoder = VideoEncoders.best()
        Assume.assumeTrue("no buffer-input H.264 encoder", encoder != null)
        return encoder!!
    }

    @Test
    fun muxesAacTrackStartingAtZeroAndCutsItAtTheVideoEnd() {
        val encoder = encoderOrSkip()
        val (doc, meta) = sourceDocument(durationTicks = 60L)
        val project = File(context.cacheDir, "exporter-audio-${System.nanoTime()}")
        AudioTestAssets.writeWav(File(project, "audio/voice.wav"), seconds = 2.0)
        val mix = File(context.cacheDir, "exporter-mix-${System.nanoTime()}.m4a")
        try {
            // The mix is 2 s long while the video is 1 s: the muxer must truncate the extra audio.
            try {
                AudioMixer.mix(
                    projectDir = project,
                    audio = VideoAudio(
                        clips = listOf(
                            AudioClip("a", "audio/voice.wav", "Voice", 0L, 120L, 0L, 1f),
                        ),
                    ),
                    durationTicks = 120L,
                    output = mix,
                )
            } catch (failure: AudioMixFailure) {
                if (failure.kind == AudioMixFailure.Kind.UNSUPPORTED_CODEC) {
                    Assume.assumeNoException("no usable audio decoder on this device", failure)
                }
                throw failure
            }
            val plan = VideoExportPlan.choice(
                doc.width,
                doc.height,
                longSide = 320,
                fps = 60,
                caps = encoder,
            ).plan ?: error("encoder does not support 320x240 at 60 fps")
            val out = File(context.cacheDir, "exporter-audio-${System.nanoTime()}.mp4")
            try {
                val result = VideoExporter.encode(
                    doc = doc,
                    meta = meta,
                    plan = plan,
                    encoder = encoder,
                    output = out,
                    audio = mix,
                )
                assertEquals(60L, result.frames)
                val extractor = MediaExtractor()
                extractor.setDataSource(out.absolutePath)
                try {
                    assertEquals(2, extractor.trackCount)
                    var audioTrack = -1
                    var videoTrack = -1
                    for (index in 0 until extractor.trackCount) {
                        val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) audioTrack = index
                        if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) videoTrack = index
                    }
                    assertTrue("no video track", videoTrack >= 0)
                    assertTrue("no AAC track", audioTrack >= 0)
                    extractor.selectTrack(audioTrack)
                    val buffer = java.nio.ByteBuffer.allocate(1 shl 16)
                    assertTrue("no audio samples", extractor.readSampleData(buffer, 0) >= 0)
                    val first = extractor.sampleTime
                    assertTrue("first AAC sample starts at $first", abs(first) <= 23_220L)
                    var last = first
                    extractor.advance()
                    while (true) {
                        if (extractor.readSampleData(buffer, 0) < 0) break
                        last = extractor.sampleTime
                        extractor.advance()
                    }
                    assertTrue(
                        "audio extends past the 1 s video: $last",
                        last <= 1_000_000L + 23_220L,
                    )
                } finally {
                    extractor.release()
                }
            } finally {
                out.delete()
            }
        } finally {
            mix.delete()
        }
    }
}
