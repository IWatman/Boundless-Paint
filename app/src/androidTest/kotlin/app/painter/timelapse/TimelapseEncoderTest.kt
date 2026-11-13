package app.painter.timelapse

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.painter.core.doc.Document
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class TimelapseEncoderTest {
    @Test
    fun captureDoesNotOverwriteAnExistingFrameAfterAGap() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "timelapse-gap-${System.nanoTime()}")
        val frames = File(root, "timelapse/frames").apply { mkdirs() }
        val existing = File(frames, "000001.jpg")
        val original = byteArrayOf(1, 2, 3)
        existing.writeBytes(original)
        try {
            assertEquals(2, TimelapseStore(root).capture(Document.create(8, 8)))
            assertArrayEquals(original, existing.readBytes())
            assertTrue(File(frames, "000002.jpg").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun encodesAllFramesAndRejectsAnUnreadableFrame() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "timelapse-${System.nanoTime()}")
        root.mkdirs()
        try {
            val image = File(root, "frame.jpg")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            bitmap.recycle()
            val output = File(root, "video.mp4")
            val result = TimelapseEncoder.encode(List(4) { image }, output, 720, 0)
            assertEquals(4, result.frames)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                extractor.selectTrack(0)
                var frames = 0
                val buffer = ByteBuffer.allocate(1 shl 20)
                while (extractor.readSampleData(buffer, 0) >= 0) {
                    frames++
                    extractor.advance()
                }
                assertEquals(4, frames)
            } finally {
                extractor.release()
            }
            val broken = File(root, "broken.jpg").also { it.writeText("not an image") }
            val failure = runCatching { TimelapseEncoder.encode(listOf(image, broken), File(root, "bad.mp4"), 720, 0) }
            assertTrue("Unreadable frames must not produce a successful export", failure.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
}
