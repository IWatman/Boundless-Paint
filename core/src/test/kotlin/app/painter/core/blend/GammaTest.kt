package app.painter.core.blend

import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.TileGrid
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gamma-correct blending (spec/blend-and-color.md "Color pipeline"): with the document toggle on, layers are
 * decoded from sRGB to linear light, blended and encoded back. Off by default, and then the result is bit-for-bit
 * the sRGB reference.
 */
class GammaTest {

    private fun srgb(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private fun encode(v: Float): Float =
        if (v <= 0.0031308f) v * 12.92f else (1.055f * Math.pow(v.toDouble(), 1.0 / 2.4) - 0.055).toFloat()

    private fun assertNear(expected: Float, actual: Float, label: String, eps: Float = 1e-3f) =
        assertTrue(abs(expected - actual) <= eps, "$label: expected $expected but was $actual")

    @Test
    fun transferFunctionsRoundTrip() {
        for (v in listOf(0f, 0.002f, 0.02f, 0.2f, 0.5f, 0.75f, 1f)) {
            assertNear(v, BlendMath.linearToSrgb(BlendMath.srgbToLinear(v)), "round trip $v", 2e-4f)
        }
        assertNear(0f, BlendMath.srgbToLinear(0f), "black")
        assertNear(1f, BlendMath.srgbToLinear(1f), "white", 2e-4f)
    }

    @Test
    fun multiplyDiffersInLinearLight() {
        // 0.5 × 0.5 over an opaque 0.5: sRGB gives 0.25, linear light gives encode(linear(0.5)²) ≈ 0.237.
        val d = floatArrayOf(0.5f, 0.5f, 0.5f, 1f)
        val s = floatArrayOf(0.5f, 0.5f, 0.5f, 1f)
        BlendMath.blendPixel(BlendMode.MULTIPLY, d, 0, s, 0, 1f)
        assertNear(0.25f, d[0], "sRGB multiply")
        val g = floatArrayOf(0.5f, 0.5f, 0.5f, 1f)
        BlendMath.blendPixel(BlendMode.MULTIPLY, g, 0, s, 0, 1f, gamma = true)
        val expected = encode(srgb(0.5f) * srgb(0.5f))
        assertNear(expected, g[0], "linear multiply")
        assertNear(0.237f, g[0], "linear multiply value", 2e-3f)
    }

    @Test
    fun alphaIsPreserved() {
        val d = FloatArray(4)
        val s = floatArrayOf(0.4f, 0.2f, 0.1f, 0.5f) // premultiplied
        BlendMath.blendPixel(BlendMode.NORMAL, d, 0, s, 0, 1f, gamma = true)
        assertNear(0.5f, d[3], "alpha")
        val g = FloatArray(4)
        BlendMath.blendPixel(BlendMode.NORMAL, g, 0, s, 0, 1f)
        assertNear(g[3], d[3], "alpha matches the sRGB path")
    }

    @Test
    fun documentedToggleChangesFlatten() {
        val d = Document(300, 20)
        val base = d.newRasterLayer("base")
        base.pixels.setPixel(5, 5, 0.5f, 0.5f, 0.5f, 1f)
        val top = d.newRasterLayer("top").apply { blendMode = BlendMode.MULTIPLY }
        top.pixels.setPixel(5, 5, 0.5f, 0.5f, 0.5f, 1f)
        d.root.add(base)
        d.root.add(top)

        val px = FloatArray(4)
        Compositor.flatten(d, includePaper = false).getPixel(5, 5, px)
        assertNear(0.25f, px[0], "sRGB by default")

        d.gammaCorrect = true
        val grid: TileGrid = Compositor.flatten(d, includePaper = false)
        grid.getPixel(5, 5, px)
        assertNear(encode(srgb(0.5f) * srgb(0.5f)), px[0], "linear light")

        d.gammaCorrect = false
        Compositor.flatten(d, includePaper = false).getPixel(5, 5, px)
        assertNear(0.25f, px[0], "toggle back")
    }

    @Test
    fun emptySourceKeepsDestination() {
        val d = FloatArray(4)
        val out = FloatArray(4)
        BlendMath.blendPixel(BlendMode.MULTIPLY, d, 0, out, 0, 1f, gamma = true)
        assertEquals(0f, d[0])
        assertEquals(0f, d[3])
    }

    @Test
    fun lerpInLinearLight() {
        val dst = floatArrayOf(0f, 0f, 0f, 1f)
        val src = floatArrayOf(0.5f, 0.5f, 0.5f, 1f)
        BlendMath.lerpPixel(dst, 0, src, 0, 0.5f, gamma = true)
        // Halfway in linear light between black and linear(0.5) = 0.2140, encoded back ≈ 0.361.
        assertNear(encode(srgb(0.5f) * 0.5f), dst[0], "linear lerp", 2e-3f)
        assertNear(1f, dst[3], "alpha unchanged")
        val srgbOnly = floatArrayOf(0f, 0f, 0f, 1f)
        BlendMath.lerpPixel(srgbOnly, 0, src, 0, 0.5f, gamma = false)
        assertNear(0.25f, srgbOnly[0], "sRGB lerp")
    }

    @Test
    fun layerPixelsAreUntouched() {
        val d = Document(300, 20)
        val l: RasterLayer = d.newRasterLayer("l")
        l.pixels.setPixel(1, 1, 0.5f, 0.5f, 0.5f, 1f)
        d.root.add(l)
        d.gammaCorrect = true
        Compositor.flatten(d, includePaper = true)
        val px = FloatArray(4)
        l.pixels.getPixel(1, 1, px)
        assertNear(0.5f, px[0], "source layer stored in sRGB")
    }
}
