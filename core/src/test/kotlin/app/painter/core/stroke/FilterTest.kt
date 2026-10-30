package app.painter.core.stroke

import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Filter brush on the layer path (spec/tools/m2-brush.md §1.16, stage S5a): each dab replaces the layer under itself
 * with `lerp(src, F(src), m·flow·|strength|)`, so a blur stroke flattens a checkerboard only under the stroke, a
 * sharpen adds an overshoot at an edge, and strength 0 leaves the layer bit-identical.
 */
class FilterTest {
    /** Full flow, hardness 1 and tight spacing, so `m·flow·|strength|` reaches its maximum under the stroke. */
    private val filtering = BrushParams(size = 40f, hardness = 1f, spacing = 0.25f, smoothing = 0f, flow = 1f, opacity = 1f)

    private fun filter(kind: FilterKind, strength: Float) =
        filtering.copy(special = FilterBrushParams(filter = kind, onStroke = false, strength = strength))

    /** 300×120 canvas: a 1 px checkerboard of 0.2 and 0.8 grey. */
    private fun checkerDoc(): Document {
        val doc = Document.create(300, 120)
        val px = doc.activeRaster!!.pixels
        for (y in 0 until 120) {
            for (x in 0 until 300) {
                val v = if ((x + y) and 1 == 0) 0.2f else 0.8f
                px.setPixel(x, y, v, v, v, 1f)
            }
        }
        return doc
    }

    private fun pixel(px: TileGrid, x: Int, y: Int): FloatArray {
        val out = FloatArray(4)
        px.getPixel(x, y, out)
        return out
    }

    /** All four channels of every pixel of [px], row-major. */
    private fun snapshot(px: TileGrid): FloatArray {
        val out = FloatArray(px.width * px.height * 4)
        val p = FloatArray(4)
        var i = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                px.getPixel(x, y, p)
                out[i] = p[0]; out[i + 1] = p[1]; out[i + 2] = p[2]; out[i + 3] = p[3]
                i += 4
            }
        }
        return out
    }

    @Test
    fun zeroStrengthIsABitExactNoOp() {
        val doc = checkerDoc()
        val grid = doc.activeRaster!!.pixels
        val before = snapshot(grid)
        val e = engine(doc, filter(FilterKind.BLUR, 0f))
        drawOpen(e, line(60f, 60f, 240f, 60f, 4f))
        e.endPatch()
        val after = snapshot(grid)
        for (i in before.indices) assertEquals(before[i], after[i], 0f, "channel $i changed")
    }

    @Test
    fun blurSmoothsOnlyUnderTheStroke() {
        val doc = checkerDoc()
        val before = snapshot(doc.activeRaster!!.pixels)
        val e = engine(doc, filter(FilterKind.BLUR, 1f))
        drawOpen(e, line(60f, 60f, 240f, 60f, 4f))
        e.endPatch()
        val px = doc.activeRaster!!.pixels
        // Under the stroke the checkerboard collapses towards its mean 0.5 and the 1 px contrast is gone.
        for (x in 120 until 180 step 7) {
            val a = pixel(px, x, 60)[0]
            val b = pixel(px, x + 1, 60)[0]
            assertTrue(abs(a - 0.5f) < 0.1f, "($x, 60) is blurred towards the mean, got $a")
            assertTrue(abs(a - b) < 0.15f, "the 1 px contrast is gone, got $a vs $b")
        }
        // Blur reads a halo but writes only under the dab: 30 px away nothing changes.
        for (x in 0 until 300 step 17) {
            for (y in intArrayOf(10, 25, 95, 110)) {
                assertEquals(before[(y * 300 + x) * 4], pixel(px, x, y)[0], 0f, "far pixel ($x, $y) changed")
            }
        }
    }

    @Test
    fun sharpenRaisesTheContrastAtAnEdge() {
        val doc = Document.create(300, 120)
        val px = doc.activeRaster!!.pixels
        for (y in 0 until 120) {
            for (x in 0 until 300) {
                val v = if (x < 150) 0.3f else 0.7f
                px.setPixel(x, y, v, v, v, 1f)
            }
        }
        val before = pixel(px, 151, 60)[0] - pixel(px, 149, 60)[0]
        val e = engine(doc, filter(FilterKind.SHARPEN, 0.5f))
        drawOpen(e, line(100f, 60f, 200f, 60f, 4f))
        e.endPatch()
        val after = pixel(px, 151, 60)[0] - pixel(px, 149, 60)[0]
        assertTrue(after > before + 0.02f, "the unsharp mask overshoots at the edge: $before → $after")
    }
}
