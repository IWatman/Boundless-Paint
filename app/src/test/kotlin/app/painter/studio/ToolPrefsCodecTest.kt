package app.painter.studio

import app.painter.core.color.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPrefsCodecTest {

    @Test
    fun encodesColorAsArgbHex() {
        assertEquals("#CC336699", ToolPrefsCodec.encodeColor(Rgba(0.2f, 0.4f, 0.6f, 0.8f)))
        assertEquals("#FF000000", ToolPrefsCodec.encodeColor(Rgba.BLACK))
        assertEquals("#FFFFFFFF", ToolPrefsCodec.encodeColor(Rgba.WHITE))
        assertEquals("#00000000", ToolPrefsCodec.encodeColor(Rgba.TRANSPARENT))
    }

    @Test
    fun colorRoundTrip() {
        val back = ToolPrefsCodec.decodeColor("#CC336699")!!
        assertEquals(0x33 / 255f, back.r, 1e-6f)
        assertEquals(0x66 / 255f, back.g, 1e-6f)
        assertEquals(0x99 / 255f, back.b, 1e-6f)
        assertEquals(0xCC / 255f, back.a, 1e-6f)
        // A leading `#` is optional, hex digits may be lower case.
        assertEquals(back, ToolPrefsCodec.decodeColor("cc336699"))
    }

    @Test
    fun rejectsGarbageColors() {
        assertNull(ToolPrefsCodec.decodeColor(null))
        assertNull(ToolPrefsCodec.decodeColor(""))
        assertNull(ToolPrefsCodec.decodeColor("#FFF"))
        assertNull(ToolPrefsCodec.decodeColor("#GGGGGGGG"))
    }

    @Test
    fun paletteRoundTrip() {
        val palette = listOf(Rgba.BLACK, Rgba(0x33 / 255f, 0x66 / 255f, 1f), Rgba.WHITE)
        val text = ToolPrefsCodec.encodePalette(palette)
        assertEquals("#FF000000,#FF3366FF,#FFFFFFFF", text)
        assertEquals(palette, ToolPrefsCodec.decodePalette(text))
    }

    @Test
    fun emptyPalette() {
        assertEquals("", ToolPrefsCodec.encodePalette(emptyList()))
        assertTrue(ToolPrefsCodec.decodePalette(null).isEmpty())
        assertTrue(ToolPrefsCodec.decodePalette("").isEmpty())
    }

    @Test
    fun dropsBrokenPaletteEntries() {
        val decoded = ToolPrefsCodec.decodePalette("#FF000000,broken,#FFFFFFFF")
        assertEquals(2, decoded.size)
        assertEquals(Rgba.BLACK, decoded[0])
        assertEquals(Rgba.WHITE, decoded[1])
    }
}
