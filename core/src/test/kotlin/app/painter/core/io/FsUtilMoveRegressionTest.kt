package app.painter.core.io

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertThrows

class FsUtilMoveRegressionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `failed copy never exposes a partial destination`() {
        val src = temp.newFolder("source")
        File(src, "data").writeText("original")
        val dst = File(temp.root, "project")

        assertThrows(IOException::class.java) {
            FsUtil.copyForMove(src, dst) { _, candidate ->
                candidate.mkdirs()
                File(candidate, FsUtil.COMPLETE_MARKER).writeText("")
                throw IOException("simulated partial copy")
            }
        }

        assertFalse(dst.exists())
        assertEquals("original", File(src, "data").readText())
        assertEquals(listOf("source"), temp.root.list()!!.sorted())
    }

    @Test fun `false copy result does not expose a destination`() {
        val src = temp.newFolder("source")
        val dst = File(temp.root, "project")
        assertThrows(IOException::class.java) {
            FsUtil.copyForMove(src, dst) { _, candidate ->
                candidate.mkdirs()
                false
            }
        }
        assertFalse(dst.exists())
        assertTrue(src.isDirectory)
    }

    @Test fun `successful copy publishes complete contents and preserves source`() {
        val src = temp.newFolder("source")
        File(src, "data").writeText("complete")
        val dst = File(temp.root, "project")
        FsUtil.copyForMove(src, dst)
        assertEquals("complete", File(dst, "data").readText())
        assertEquals("complete", File(src, "data").readText())
    }

    @Test fun `existing destination is not replaced during publication`() {
        val src = temp.newFolder("source")
        val dst = temp.newFolder("project")
        File(dst, "data").writeText("existing")
        assertThrows(IOException::class.java) { FsUtil.copyForMove(src, dst) }
        assertEquals("existing", File(dst, "data").readText())
        assertTrue(src.isDirectory)
    }

    @Test fun `directory copy fallback fails without touching either tree`() {
        val src = temp.newFolder("source")
        File(src, "data").writeText("original")
        val dst = File(temp.root, "project")

        assertThrows(IOException::class.java) { FsUtil.copyAndDeleteForMove(src, dst) }

        assertEquals("original", File(src, "data").readText())
        assertFalse(dst.exists())
        assertEquals(listOf("source"), temp.root.list()!!.sorted())
    }

    @Test fun `file copy fallback publishes before deleting source`() {
        val src = temp.newFile("source")
        src.writeText("contents")
        val dst = File(temp.root, "target")

        FsUtil.copyAndDeleteForMove(src, dst)

        assertFalse(src.exists())
        assertEquals("contents", dst.readText())
    }

    @Test fun `file deletion failure keeps both complete copies`() {
        val src = temp.newFile("source")
        src.writeText("contents")
        val dst = File(temp.root, "target")

        val error = assertThrows(IOException::class.java) { FsUtil.copyAndDeleteForMove(src, dst) { false } }

        assertTrue(error.message!!.contains("both paths remain"))
        assertEquals("contents", src.readText())
        assertEquals("contents", dst.readText())
    }
}
