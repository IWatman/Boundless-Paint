package app.painter.core.stroke

import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.assertGridsEqual
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smudge ([StrokeMode.BLEND], spec/tools/m2-brush.md §1.8, stage S4a): no colour is deposited, the dab samples the
 * active layer on an n×n grid, blurs that sample by `blur`, stamps the pickup buffer `P` and then drags `P` towards
 * the local colour by `1 − pull·0.98`. So a stroke across a red|blue boundary smears purple past the boundary, a
 * high pull carries the colour further, a uniform colour stays as it was, and the tile patch undoes the stroke.
 */
class SmudgeTest {
    /** Full flow, so `m·flow·blendFlow` reaches 1 in the middle of the dab and the stamp is the pickup itself. */
    private val smudging = BrushParams(size = 40f, hardness = 1f, spacing = 0.25f, smoothing = 0f, flow = 1f, opacity = 1f)

    /** 300×120 canvas, opaque red left of the middle column and opaque blue right of it. */
    private fun boundaryDoc(): Document {
        val doc = Document.create(300, 120)
        val px = doc.activeRaster!!.pixels
        for (y in 0 until 120) {
            for (x in 0 until 300) {
                if (x < 150) px.setPixel(x, y, 1f, 0f, 0f, 1f) else px.setPixel(x, y, 0f, 0f, 1f, 1f)
            }
        }
        return doc
    }

    /** Smudges (60, 60) → (240, 60), from the red half into the blue one, and returns the layer. */
    private fun cross(pull: Float): TileGrid {
        val doc = boundaryDoc()
        val e = engine(doc, smudging.copy(paint = PaintParams(pull = pull)), mode = StrokeMode.BLEND)
        drawOpen(e, line(60f, 60f, 240f, 60f, 4f))
        e.endPatch()
        return doc.activeRaster!!.pixels
    }

    private fun pixel(px: TileGrid, x: Int, y: Int): FloatArray {
        val out = FloatArray(4)
        px.getPixel(x, y, out)
        return out
    }

    /** The rightmost x on the stroke row where the red of the red half is still present: the smear length. */
    private fun smearEnd(px: TileGrid): Int {
        var last = 150
        for (x in 150 until 300) if (pixel(px, x, 60)[0] >= 0.1f) last = x
        return last
    }

    @Test
    fun smudgeDragsColourPastTheBoundary() {
        // Half pull: the pickup follows the local colour slowly enough to carry red well into the blue half, where
        // it mixes with the blue the dab also picks up — the pixel is neither the red nor the blue of the layer.
        val px = cross(0.5f)
        val far = pixel(px, 210, 60)
        assertTrue(far[0] > 0.15f, "red 60 px past the boundary, got ${far.toList()}")
        assertTrue(far[2] > 0.15f, "mixed with the blue there, got ${far.toList()}")
        val blue = pixel(px, 280, 60)
        assertTrue(blue[2] > 0.95f && blue[0] < 0.02f, "beyond the stroke the blue half stays blue, got ${blue.toList()}")
    }

    @Test
    fun highPullSmearsFurther() {
        // pull only scales how fast P follows the local colour, so the stroke drags red deeper into the blue half.
        val strong = smearEnd(cross(0.9f))
        val weak = smearEnd(cross(0.2f))
        assertTrue(weak > 150, "even a low pull smears, got $weak")
        assertTrue(strong > weak + 10, "pull 0.9 must smear further: $strong vs $weak")
    }

    @Test
    fun uniformColourIsUnchanged() {
        val doc = Document.create(200, 120)
        val px = doc.activeRaster!!.pixels
        for (y in 0 until 120) for (x in 0 until 200) px.setPixel(x, y, 0.4f, 0.6f, 0.3f, 1f)
        // blur mixes in the mean of the sample and pull 0.9 keeps the pickup: neither may change a flat area.
        val e = engine(doc, smudging.copy(paint = PaintParams(pull = 0.9f, blur = 0.5f)), mode = StrokeMode.BLEND)
        drawOpen(e, line(40f, 60.5f, 160f, 60.5f, 4f))
        e.endPatch()
        for (x in 20 until 180 step 13) {
            for (y in 42 until 80 step 11) {
                val p = pixel(px, x, y)
                assertEquals(0.4f, p[0], 1f / 255f, "red at ($x, $y)")
                assertEquals(0.6f, p[1], 1f / 255f, "green at ($x, $y)")
                assertEquals(0.3f, p[2], 1f / 255f, "blue at ($x, $y)")
                assertEquals(1f, p[3], 1f / 255f, "alpha at ($x, $y)")
            }
        }
    }

    @Test
    fun undoRestoresTheLayer() {
        val doc = boundaryDoc()
        val layer = doc.activeRaster!!.pixels
        val before = layer.copy()
        val e = engine(doc, smudging.copy(paint = PaintParams(pull = 0.9f)), mode = StrokeMode.BLEND)
        drawOpen(e, line(60f, 60f, 240f, 60f, 4f))
        val patch = requireNotNull(e.endPatch()) { "the stroke must commit a patch" }
        assertTrue(pixel(layer, 200, 60)[0] > 0.5f, "the stroke must drag red over the blue")
        // Undo = putting the before-snapshots back, as the document does with a TilePatch.
        for ((key, tile) in patch.before) layer.put(key, tile)
        assertGridsEqual(before, layer, "undo")
    }
}
