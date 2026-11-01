package app.painter.video

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.VideoClip
import app.painter.core.doc.VideoMeta
import app.painter.core.doc.VideoTrack
import app.painter.core.video.VideoExportPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Encodes a short synthetic 320 × 240 animation at 60 fps and inspects the MP4 with MediaExtractor
 * (spec/VIDEO-MODE.md §9.2). Skips the test when the device has no buffer-input AVC encoder.
 */
@RunWith(AndroidJUnit4::class)
class VideoExporterTest {

    private fun sourceDocument(durationTicks: Long = 60L): Pair<Document, VideoMeta> {
        val doc = Document(320, 240, name = "Export test")
        val group = GroupNode("frames", "Frames")
        doc.root.add(group)
        val frame = doc.newRasterLayer("Frame 1")
        frame.pixels.setPixel(10, 10, 1f, 0f, 0f, 1f)
        frame.pixels.setPixel(11, 10, 1f, 0f, 0f, 1f)
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

    private fun plan(doc: Document, encoder: AvcEncoder): VideoExportPlan.Plan {
        val choice = VideoExportPlan.choice(doc.width, doc.height, longSide = 320, fps = 60, caps = encoder)
        return choice.plan ?: error("encoder does not support 320x240 at 60 fps")
    }

    @Test
    fun encodesOneSecondAndKeepsTheDocumentUntouched() {
        val (doc, meta) = sourceDocument()
        val frame = doc.findNode(meta.tracks.single().clips.single().frameId!!)
        assertTrue(frame?.visible == true)
        val encoder = encoderOrSkip()
        val formatPlan = plan(doc, encoder)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.cacheDir, "video-export-test-${System.nanoTime()}.mp4")
        var lastProgress = 0L
        try {
            val result = VideoExporter.encode(
                doc, meta, formatPlan, encoder, out,
                onProgress = { done, _ -> lastProgress = done },
            )
            assertEquals(60L, result.frames)
            assertEquals("progress did not reach the last frame", 60L, lastProgress)
            assertTrue("MP4 was not written", out.length() > 0L)
            assertTrue(frame?.visible == true)

            val extractor = MediaExtractor()
            extractor.setDataSource(out.absolutePath)
            try {
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
                assertEquals(formatPlan.width, format.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(formatPlan.height, format.getInteger(MediaFormat.KEY_HEIGHT))
                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                assertTrue(
                    "duration $durationUs is not within one frame of 1 s",
                    kotlin.math.abs(durationUs - 1_000_000L) <= 16_667L,
                )
                extractor.selectTrack(0)
                var samples = 0
                val buffer = java.nio.ByteBuffer.allocate(1 shl 20)
                val info = android.media.MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    samples++
                    extractor.advance()
                }
                assertEquals("unexpected number of access units", 60, samples)
            } finally {
                extractor.release()
            }
        } finally {
            out.delete()
        }
    }

    @Test
    fun fiveSecondsExportsExactly300Frames() {
        val (doc, meta) = sourceDocument(durationTicks = 300L)
        val encoder = encoderOrSkip()
        val formatPlan = plan(doc, encoder)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.cacheDir, "video-export-five-${System.nanoTime()}.mp4")
        try {
            val result = VideoExporter.encode(doc, meta, formatPlan, encoder, out)
            assertEquals(300L, result.frames)
            val extractor = MediaExtractor()
            extractor.setDataSource(out.absolutePath)
            try {
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                assertTrue(
                    "duration $durationUs is not within one frame of 5 s",
                    kotlin.math.abs(durationUs - 5_000_000L) <= 16_667L,
                )
                extractor.selectTrack(0)
                var samples = 0
                val buffer = java.nio.ByteBuffer.allocate(1 shl 20)
                while (extractor.readSampleData(buffer, 0) >= 0) {
                    samples++
                    extractor.advance()
                }
                assertEquals("unexpected number of access units", 300, samples)
            } finally {
                extractor.release()
            }
        } finally {
            out.delete()
        }
    }

    @Test
    fun cancelDeletesTheTempFile() {
        val (doc, meta) = sourceDocument()
        val encoder = encoderOrSkip()
        val formatPlan = plan(doc, encoder)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.cacheDir, "video-export-cancel-${System.nanoTime()}.mp4")
        var cancel = false
        var cancelled = false
        try {
            VideoExporter.encode(
                doc = doc,
                meta = meta,
                plan = formatPlan,
                encoder = encoder,
                output = out,
                onProgress = { _, _ -> cancel = true },
                isCancelled = { cancel },
            )
            fail("the export was not cancelled")
        } catch (expected: VideoExportCancelled) {
            cancelled = true
        } finally {
            assertTrue(cancelled)
            assertFalse("cancelled export left ${out.name}", out.exists())
        }
    }
}
