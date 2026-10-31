package app.painter.core.io

import app.painter.core.raster.TileGrid
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TiffCmykTest {
    @Test
    fun writesAValidLittleEndianCmykTiff() {
        val g = TileGrid(4, 3)
        g.setPixel(0, 0, 0f, 0f, 0f, 1f)
        g.setPixel(3, 2, 1f, 1f, 1f, 1f)
        val bytes = TiffCmyk.encode(g, 4, 3)
        assertEquals('I'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals(42, bytes[2].toInt() and 0xFF)
        assertEquals(4 * 3 * 4 + 8 + 2 + 9 * 12 + 4 + 8, bytes.size)
        // Black pixel: C=M=Y=0, K=255. White pixel: all zero.
        val strip = bytes.size - 4 * 3 * 4
        assertEquals(0, bytes[strip].toInt() and 0xFF)
        assertEquals(255, bytes[strip + 3].toInt() and 0xFF)
        val last = strip + 11 * 4
        assertEquals(0, bytes[last].toInt() and 0xFF)
        assertEquals(0, bytes[last + 3].toInt() and 0xFF)
    }
}
