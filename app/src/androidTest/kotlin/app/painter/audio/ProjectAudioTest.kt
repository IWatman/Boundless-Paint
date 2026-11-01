package app.painter.audio

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.painter.video.AudioTestAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SAF import of slice S4: the picked stream is copied into `audio/` of the project, probed and described by
 * a project-relative path; an unreadable or non-audio file leaves no orphan behind.
 */
@RunWith(AndroidJUnit4::class)
class ProjectAudioTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun shareUri(file: File): Uri {
        val dir = File(context.cacheDir, "share")
        dir.mkdirs()
        val target = File(dir, file.name)
        file.copyTo(target, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    @Test
    fun importCopiesIntoTheProjectAndRejectsGarbageWithoutOrphans() {
        val project = File(context.cacheDir, "audio-import-${System.nanoTime()}")
        val source = File(context.cacheDir, "audio-source-${System.nanoTime()}.wav")
        AudioTestAssets.writeWav(source, seconds = 1.0)
        val imported = ProjectAudio.import(context.contentResolver, shareUri(source), project)
        assertTrue("not a project-relative path: ${imported.relativePath}", imported.relativePath.startsWith("audio/"))
        assertTrue("the copy does not exist", File(project, imported.relativePath).isFile)
        assertEquals(2, imported.info.channels)
        assertEquals(60L, imported.info.durationTicks)

        val junk = File(context.cacheDir, "audio-junk-${System.nanoTime()}.bin")
        junk.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        try {
            ProjectAudio.import(context.contentResolver, shareUri(junk), project)
            fail("garbage was accepted as audio")
        } catch (expected: AudioImportFailure) {
            assertTrue(expected is AudioImportFailure.Unreadable || expected is AudioImportFailure.NoAudioTrack)
        }
        val audioFiles = File(project, "audio").listFiles()?.filter { it.isFile }.orEmpty()
        assertEquals("a rejected import left an orphan", 1, audioFiles.size)
    }
}
