package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.assertGridsEqual
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stroke effects (spec/tools/m2-brush.md §1.7, stage S3a): glaze saturates the buffer instead of accumulating, so
 * a self-intersection is not darker than a single pass; glaze = 0 keeps the unglazed stroke bit-identical; a brush
 * blend mode composites the buffered stroke with `BlendMath` and its strength mixes it with the normal result.
 */
class EffectsTest {
    /** Flow < 1 so that overlapping dabs build up alpha unless glaze stops them. */
    private val glazing = BrushParams(size = 40f, hardness = 1f, spacing = 0.25f, smoothing = 0f, flow = 0.3f, opacity = 0.5f)

    /** A stroke that starts vertically, turns and then runs back across its own start: the legs cross at (100, 100). */
    private fun crossingPoints(): List<InputPoint> {
        val pts = ArrayList<InputPoint>()
        fun leg(x0: Float, y0: Float, x1: Float, y1: Float) {
            val l = line(x0, y0, x1, y1, 4f)
            pts.addAll(if (pts.isEmpty()) l else l.drop(1))
        }
        leg(100f, 30f, 100f, 170f)
        leg(100f, 170f, 170f, 100f)
        leg(170f, 100f, 30f, 100f)
        return pts
    }

    private fun crossed(glaze: Float): TileGrid {
        val doc = Document.create(200, 200)
        val params = glazing.copy(effects = EffectParams(glaze = glaze))
        val e = engine(doc, params)
        drawOpen(e, crossingPoints())
        e.endPatch()
        return doc.activeRaster!!.pixels
    }

    @Test
    fun glazeOneIsNotDarkerAtTheCrossing() {
        val px = crossed(1f)
        val single = alpha(px, 100, 60)
        val at = alpha(px, 100, 100)
        assertTrue(single > 0.05f, "the stroke must paint, got $single")
        assertTrue(abs(single - at) <= 1f / 255f, "glaze 1: crossing $at vs single pass $single")
    }

    @Test
    fun withoutGlazeTheCrossingBuildsUp() {
        // Guards the test above: the same path without glaze does darken, so the equality is not trivially true.
        val px = crossed(0f)
        val single = alpha(px, 100, 60)
        val at = alpha(px, 100, 100)
        assertTrue(at > single + 2f / 255f, "glaze 0: crossing $at vs single pass $single")
    }

    @Test
    fun glazeZeroIsThePlainStroke() {
        fun draw(effects: EffectParams): TileGrid {
            val doc = Document.create(200, 200)
            val e = engine(doc, glazing.copy(effects = effects))
            drawOpen(e, crossingPoints())
            e.endPatch()
            return doc.activeRaster!!.pixels
        }
        assertGridsEqual(draw(EffectParams(glaze = 0f)), draw(EffectParams()), "glaze 0")
        val plain = draw(EffectParams())
        val glazed = draw(EffectParams(glaze = 0.5f))
        val p = alpha(plain, 100, 100)
        val g = alpha(glazed, 100, 100)
        assertTrue(abs(g - p) > 2f / 255f, "glaze 0.5 must change the crossing: $g vs $p")
    }

    private fun red(x: Float): BrushParams = BrushParams(
        size = 40f, hardness = 1f, spacing = 0.25f, smoothing = 0f, opacity = 1f, flow = 1f,
        effects = EffectParams(blendMode = "multiply", blendStrength = x),
    )

    /** Composites a red multiply brush over a flat layer of [v] and returns the pixel at (100, 100). */
    private fun over(v: Float, strength: Float): FloatArray {
        val doc = Document.create(200, 200)
        val layer = doc.activeRaster!!.pixels
        for (y in 0 until 200) for (x in 0 until 200) layer.setPixel(x, y, v, v, v, 1f)
        val e = engine(doc, red(strength), color = Rgba(1f, 0f, 0f))
        drawOpen(e, line(40f, 100.5f, 160f, 100.5f, 4f))
        e.endPatch()
        val out = FloatArray(4)
        doc.activeRaster!!.pixels.getPixel(100, 100, out)
        return out
    }

    @Test
    fun multiplyOverWhiteIsTheBrushColor() {
        val px = over(1f, 1f)
        assertEquals(1f, px[0], 1f / 255f, "red channel")
        assertEquals(0f, px[1], 1f / 255f, "green channel")
        assertEquals(0f, px[2], 1f / 255f, "blue channel")
        assertEquals(1f, px[3], 1f / 255f, "alpha")
    }

    @Test
    fun blendStrengthMixesTheModeWithNormal() {
        // On mid grey, multiply darkens to (0.5, 0, 0) while normal stays (1, 0, 0).
        assertEquals(0.5f, over(0.5f, 1f)[0], 2f / 255f, "multiply")
        assertEquals(1f, over(0.5f, 0f)[0], 2f / 255f, "normal at strength 0")
        assertEquals(0.75f, over(0.5f, 0.5f)[0], 2f / 255f, "half multiply")
    }
}
