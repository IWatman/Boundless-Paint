package app.painter.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AudioMixerCacheTest {
    private val dir = Files.createTempDirectory("audio-cache-test").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun pruneKeepsCurrentAndNewestOtherMixes() {
        val files = (0..5).map { index ->
            File(dir, "$index.m4a").apply {
                writeBytes(byteArrayOf(1))
                assertTrue(setLastModified(1_000_000L + index * 10_000L))
            }
        }
        AudioMixer.prune(dir, keep = files.first().name)

        assertEquals(setOf("0.m4a", "3.m4a", "4.m4a", "5.m4a"),
            dir.listFiles().orEmpty().map { it.name }.toSet())
    }
}
