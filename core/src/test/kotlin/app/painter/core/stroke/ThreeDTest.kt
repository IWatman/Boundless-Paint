package app.painter.core.stroke

import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.assertGridsEqual
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 3D stamp (spec/tools/m2-brush.md §1.17): the dabs paint a shaded disk through a `z` plane, so a densely sampled
 * straight tube has a continuous highlight on one row, its dark side is bottom-right, and the result does not depend
 * on how the stroke is batched.
 */
class ThreeDTest {
    /** A round brush with [kind]; the spacing is deliberately larger than 5% so the engine must tighten it. */
    private fun brush(kind: ThreeDKind, spacing: Float = 0.4f) = BrushParams(
        size = 20f, hardness = 1f, spacing = spacing, smoothing = 0f, opacity = 1f, flow = 1f,
        special = ThreeDParams(kind = kind),
    )

    /** Draws [points] with [params] and returns the pixels of the layer. */
    private fun render(params: BrushParams, points: List<InputPoint>, size: Int = 200, seed: Long = 3L): TileGrid {
        val doc = Document.create(size, size)
        val e = engine(doc, params, ctx = StrokeContext(threads = 1, seed = seed))
        drawOpen(e, points)
        e.endPatch()
        return doc.activeRaster!!.pixels
    }

    /** Premultiplied brightness of a pixel (the red brush turns the highlight towards white, so all channels count). */
    private fun luminance(px: TileGrid, x: Int, y: Int): Float {
        val p = FloatArray(4)
        px.getPixel(x, y, p)
        return p[0] + p[1] + p[2]
    }

    /** Row of the brightest pixel of column [x] inside `cy − r..cy + r`. */
    private fun brightestRow(px: TileGrid, x: Int, cy: Int, r: Int): Int {
        var best = cy
        var bestV = -1f
        for (y in cy - r..cy + r) {
            val v = luminance(px, x, y)
            if (v > bestV) {
                bestV = v
                best = y
            }
        }
        return best
    }

    @Test
    fun aStraightTubeHasAContinuousHighlight() {
        val x0 = 40
        val x1 = 160
        val cy = 100
        val px = render(brush(ThreeDKind.TUBE), line(x0.toFloat(), cy.toFloat(), x1.toFloat(), cy.toFloat(), 2f))
        val from = x0 + ((x1 - x0) * 0.1f).toInt()
        val to = x1 - ((x1 - x0) * 0.1f).toInt()
        val reference = brightestRow(px, (from + to) / 2, cy, 12)
        for (x in from..to) {
            val r = brightestRow(px, x, cy, 12)
            assertTrue(abs(r - reference) <= 1, "highlight row changes over the stroke: $r vs $reference at x=$x")
        }
        assertTrue(luminance(px, (from + to) / 2, cy) > 0f, "the tube must paint")
    }

    @Test
    fun theDarkSideIsBottomRight() {
        val cy = 100
        val px = render(brush(ThreeDKind.TUBE), line(40f, cy.toFloat(), 160f, cy.toFloat(), 2f))
        var above = 0f
        var below = 0f
        var nAbove = 0
        var nBelow = 0
        for (x in 52..148) {
            for (y in cy - 9..cy - 1) {
                above += luminance(px, x, y)
                nAbove++
            }
            for (y in cy + 1..cy + 9) {
                below += luminance(px, x, y)
                nBelow++
            }
        }
        val a = above / nAbove
        val b = below / nBelow
        assertTrue(b < a, "the bottom-right side must be darker: $b vs $a")
    }

    @Test
    fun theResultIsDeterministic() {
        val pts = line(40f, 60f, 160f, 120f, 2f)
        for (kind in ThreeDKind.entries) {
            val params = brush(kind, spacing = 0.3f)
            val a = render(params, pts)
            val b = render(params, pts)
            assertGridsEqual(a, b, "$kind: two runs of the same stroke")
        }
    }
}
