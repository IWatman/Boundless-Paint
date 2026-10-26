package app.painter.core.color

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorConvTest {
    private fun near(expected: Float, actual: Float, tol: Float = 1e-3f, label: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$label expected $expected but was $actual")

    private fun nearRgba(e: Rgba, a: Rgba, tol: Float = 1e-3f, label: String = "") {
        near(e.r, a.r, tol, "$label r"); near(e.g, a.g, tol, "$label g")
        near(e.b, a.b, tol, "$label b"); near(e.a, a.a, tol, "$label a")
    }

    private fun grid(): List<Rgba> {
        val steps = listOf(0f, 0.1f, 0.25f, 0.5f, 0.73f, 0.9f, 1f)
        return steps.flatMap { r -> steps.flatMap { g -> steps.map { b -> Rgba(r, g, b) } } }
    }

    @Test
    fun hsbKnownValues() {
        assertEquals(Hsb(0f, 1f, 1f), ColorConv.toHsb(Rgba(1f, 0f, 0f)))
        val h = ColorConv.toHsb(Rgba(0.2f, 0.4f, 0.6f))
        near(210f, h.h); near(0.6667f, h.s); near(0.6f, h.v)
        val gray = ColorConv.toHsb(Rgba(0.5f, 0.5f, 0.5f))
        near(0f, gray.h); near(0f, gray.s); near(0.5f, gray.v)
        nearRgba(Rgba(0f, 1f, 1f), ColorConv.fromHsb(Hsb(180f, 1f, 1f)))
        nearRgba(Rgba(1f, 0f, 1f, 0.5f), ColorConv.fromHsb(Hsb(300f, 1f, 1f), 0.5f))
        nearRgba(Rgba(1f, 0f, 0f), ColorConv.fromHsb(Hsb(360f, 1f, 1f)))
    }

    @Test
    fun hsbRoundTrip() {
        for (c in grid()) nearRgba(c, ColorConv.fromHsb(ColorConv.toHsb(c)), label = "$c")
    }

    @Test
    fun hexFormat() {
        assertEquals("#FF8000", ColorConv.toHex(Rgba(1f, 0.5f, 0f), withAlpha = false))
        assertEquals("#00000000", ColorConv.toHex(Rgba.TRANSPARENT, withAlpha = true))
        assertEquals("#336699CC", ColorConv.toHex(Rgba(0.2f, 0.4f, 0.6f, 0.8f), withAlpha = true))
    }

    @Test
    fun hexParse() {
        nearRgba(Rgba(1f, 0.5333f, 0f), assertNotNull(ColorConv.parseHex("#f80")))
        nearRgba(Rgba(0.2f, 0.4f, 0.6f), assertNotNull(ColorConv.parseHex("336699")))
        nearRgba(Rgba(17 / 255f, 34 / 255f, 51 / 255f, 68 / 255f), assertNotNull(ColorConv.parseHex("#11223344")))
        for (bad in listOf("", "#", "12345", "#1234567", "zzz", "#12G456", "123456789")) {
            assertNull(ColorConv.parseHex(bad), bad)
        }
    }

    @Test
    fun hexRoundTrip() {
        for (c in grid()) {
            val parsed = assertNotNull(ColorConv.parseHex(ColorConv.toHex(c.copy(a = 0.4f), withAlpha = true)))
            nearRgba(c.copy(a = 0.4f), parsed, tol = 0.5f / 255f + 1e-4f)
        }
    }

    @Test
    fun labKnownValues() {
        val white = ColorConv.toLab(Rgba.WHITE)
        near(100f, white.l, 0.01f); near(0f, white.a, 0.01f); near(0f, white.b, 0.01f)
        val black = ColorConv.toLab(Rgba.BLACK)
        near(0f, black.l, 0.01f)
        val red = ColorConv.toLab(Rgba(1f, 0f, 0f))
        near(53.24f, red.l, 0.05f); near(80.09f, red.a, 0.05f); near(67.20f, red.b, 0.05f)
    }

    @Test
    fun labRoundTrip() {
        for (c in grid()) nearRgba(c, ColorConv.fromLab(ColorConv.toLab(c)), label = "$c")
    }

    @Test
    fun labOutOfGamutIsClamped() {
        val c = ColorConv.fromLab(Lab(50f, 200f, -200f), 0.3f)
        for (v in listOf(c.r, c.g, c.b)) assertTrue(v in 0f..1f)
        near(0.3f, c.a)
    }

    @Test
    fun luminanceWeights() {
        near(0.3f, ColorConv.luminance(Rgba(1f, 0f, 0f)))
        near(0.59f, ColorConv.luminance(Rgba(0f, 1f, 0f)))
        near(0.11f, ColorConv.luminance(Rgba(0f, 0f, 1f)))
        near(0.483f, ColorConv.luminance(Rgba(0.8f, 0.3f, 0.6f)))
    }
}
