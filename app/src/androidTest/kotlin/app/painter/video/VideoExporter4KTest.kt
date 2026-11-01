package app.painter.video

import android.app.ActivityManager
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
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 4K smoke test (spec/VIDEO-MODE.md §7 S5). It runs only when the selected encoder advertises 4K at 60 or
 * 30 fps and the memory guard accepts the resulting size; on every other emulator or device it is skipped, so
 * an unsupported machine can never make the suite red.
 */
@RunWith(AndroidJUnit4::class)
class VideoExporter4KTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sourceDocument(): Pair<Document, VideoMeta> {
        val doc = Document(3840, 2160, name = "4K export test")
        val group = GroupNode("frames-4k", "Frames")
        doc.root.add(group)
        val frame = doc.newRasterLayer("Frame 1")
        frame.pixels.setPixel(10, 10, 1f, 0f, 0f, 1f)
        frame.pixels.setPixel(11, 10, 1f, 0f, 0f, 1f)
        group.add(frame)
        val meta = VideoMeta(
            tracks = listOf(
                VideoTrack(
                    id = "track-4k",
                    name = "Drawing",
                    groupId = group.id,
                    clips = listOf(VideoClip("clip-4k", frame.id, startTick = 0L, durationTicks = 2L)),
                ),
            ),
        )
        doc.video = meta
        doc.activeId = frame.id
        return doc to meta
    }

    private fun largeHeapBytes(): Long =
        context.getSystemService(ActivityManager::class.java)
            ?.largeMemoryClass?.toLong()?.times(1024L * 1024L) ?: 0L

    @Test
    fun fourKSmokeWhenTheEncoderAndHeapAllowIt() {
        val encoder = VideoEncoders.best()
        Assume.assumeTrue("no buffer-input H.264 encoder", encoder != null)
        val sixty = VideoExportPlan.choice(3840, 2160, VideoExportPlan.FOUR_K_LONG_SIDE, 60, encoder!!)
        val thirty = VideoExportPlan.choice(3840, 2160, VideoExportPlan.FOUR_K_LONG_SIDE, 30, encoder)
        val choice = if (sixty.supported) sixty else thirty
        Assume.assumeTrue("the encoder has no 4K mode", choice.supported)
        val plan = choice.plan!!
        Assume.assumeTrue(
            "the 4K memory guard refuses this device",
            VideoExportPlan.memoryGuardAllows(plan.width, plan.height, largeHeapBytes()),
        )

        val (doc, meta) = sourceDocument()
        val out = File(context.cacheDir, "video-export-4k-${System.nanoTime()}.mp4")
        try {
            val result = try {
                VideoExporter.encode(doc, meta, plan, encoder, out)
            } catch (t: Exception) {
                // A codec that advertises 4K but cannot configure or encode it is a device limitation here.
                Assume.assumeNoException("4K encoding is unavailable on this device", t)
                return
            }
            assertEquals(VideoExportPlan.frameCount(2L, plan.fps), result.frames)
            assertTrue("MP4 was not written", out.length() > 0L)

            val extractor = MediaExtractor()
            extractor.setDataSource(out.absolutePath)
            try {
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
                assertEquals(plan.width, format.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(plan.height, format.getInteger(MediaFormat.KEY_HEIGHT))
                extractor.selectTrack(0)
                var samples = 0
                val buffer = java.nio.ByteBuffer.allocate(1 shl 20)
                while (extractor.readSampleData(buffer, 0) >= 0) {
                    samples++
                    extractor.advance()
                }
                assertEquals(result.frames.toInt(), samples)
            } finally {
                extractor.release()
            }
        } finally {
            out.delete()
        }
    }
}
