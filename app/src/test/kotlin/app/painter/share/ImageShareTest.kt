package app.painter.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImageShareTest {

    @Test
    fun fileNameKeepsAnOrdinaryProjectName() {
        assertEquals("Sketch 12.png", ImageShare.fileName("Sketch 12"))
    }

    @Test
    fun fileNameReplacesPathAndReservedCharacters() {
        assertEquals("a_b_c_d_e_f_g.png", ImageShare.fileName("a/b\\c:d*e?f\"g"))
        assertEquals("line_break.png", ImageShare.fileName("line\nbreak"))
    }

    @Test
    fun fileNameFallsBackForBlankOrDotOnlyNames() {
        assertEquals("Drawing.png", ImageShare.fileName("   "))
        assertEquals("Drawing.png", ImageShare.fileName("..."))
    }

    @Test
    fun fileNameStaysShortEnoughForReceivingApps() {
        val name = ImageShare.fileName("x".repeat(200))
        assertEquals(84, name.length)
        assertTrue(name.endsWith(".png"))
    }

    @Test
    fun writeLeavesOnlyTheCurrentDrawingInTheShareCache() {
        val cache = Files.createTempDirectory("boundless-share").toFile()

        ImageShare.write(cache, "First", byteArrayOf(1))
        val file = ImageShare.write(cache, "Second", byteArrayOf(2, 3))

        assertEquals("Second.png", file.name)
        assertTrue(file.readBytes().contentEquals(byteArrayOf(2, 3)))
        val names = File(cache, "share").list()?.toList().orEmpty()
        assertEquals(listOf("Second.png"), names)
    }
}
