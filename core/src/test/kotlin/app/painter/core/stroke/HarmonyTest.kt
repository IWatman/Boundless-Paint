package app.painter.core.stroke

import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.assertGridsEqual
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Harmony (spec/tools/m2-brush.md §1.11): the dabs paint nothing, the stroke keeps its points and connects the new
 * ones to the earlier ones by the rule of the variant, and its own path is drawn with alpha 1.
 */
class HarmonyTest {
    /** Thin brush: only the lines of the harmony engine paint, and no head. */
    private fun brush(special: HarmonyParams) = BrushParams(
        size = 1f, hardness = 1f, spacing = 0.5f, smoothing = 0f, opacity = 1f, flow = 1f, special = special,
    )

    /** Draws [points] with [params] and returns the pixels of the layer. */
    private fun render(
        params: BrushParams,
        points: List<InputPoint>,
        perPoint: Boolean = false,
        size: Int = 200,
        seed: Long = 7L,
    ): TileGrid {
        val doc = Document.create(size, size)
        val e = engine(doc, params, ctx = StrokeContext(threads = 1, seed = seed))
        drawOpen(e, points, perPoint)
        e.endPatch()
        return doc.activeRaster!!.pixels
    }

    /** Path through [vertices] resampled every [step] px, with a monotonic time stamp. */
    private fun polyline(vararg vertices: FloatArray, step: Float = 2f): List<InputPoint> {
        val out = ArrayList<InputPoint>()
        for (i in 0 until vertices.size - 1) {
            val a = vertices[i]
            val b = vertices[i + 1]
            val seg = line(a[0], a[1], b[0], b[1], step)
            out += if (i == 0) seg else seg.drop(1)
        }
        return out.mapIndexed { i, p -> p.copy(timeMs = i * 4L) }
    }

    /** Sum of the buffer alpha over `x0..x1 × y0..y1`. */
    private fun coverage(px: TileGrid, x0: Int, x1: Int, y0: Int, y1: Int): Float {
        var sum = 0f
        for (y in y0..y1) for (x in x0..x1) sum += alpha(px, x, y)
        return sum
    }

    /** Max buffer alpha over `x0..x1 × y0..y1`. */
    private fun peak(px: TileGrid, x0: Int, x1: Int, y0: Int, y1: Int): Float {
        var max = 0f
        for (y in y0..y1) for (x in x0..x1) max = maxOf(max, alpha(px, x, y))
        return max
    }

    @Test
    fun theResultIsDeterministic() {
        val special = HarmonyParams(variant = HarmonyVariant.SKETCHY)
        val path = polyline(floatArrayOf(40f, 40f), floatArrayOf(160f, 70f), floatArrayOf(60f, 160f))
        val a = render(brush(special), path)
        val b = render(brush(special), path)
        assertGridsEqual(a, b, "two runs of the same stroke")
        // One batch and one point per call must give the same lines: the randomness comes from (seed, index).
        val c = render(brush(special), path, perPoint = true)
        assertGridsEqual(a, c, "batch vs points")
        assertTrue(coverage(a, 0, 199, 0, 199) > 0f, "the stroke must paint")
    }

    @Test
    fun aStrokeReturningOverItselfGainsCoverage() {
        val special = HarmonyParams(variant = HarmonyVariant.SKETCHY)
        val once = polyline(floatArrayOf(40f, 100f), floatArrayOf(160f, 100f))
        // The same span back and forth twice; the return legs sit 3 px lower, as a hand scribbling over its own
        // line does, so the links between the legs fill the band the single pass leaves empty.
        val a = floatArrayOf(40f, 100f)
        val b = floatArrayOf(160f, 100f)
        val a2 = floatArrayOf(40f, 103f)
        val b2 = floatArrayOf(160f, 103f)
        val backAndForth = polyline(a, b, b2, a2, a, b, b2, a2)
        val single = coverage(render(brush(special), once), 45, 155, 98, 104)
        val twice = coverage(render(brush(special), backAndForth), 45, 155, 98, 104)
        assertTrue(single > 0f, "the path line must paint, coverage $single")
        assertTrue(twice > single * 1.5f, "the overlap must gain coverage: $twice vs $single")
    }

    @Test
    fun shadedLinksOnlyPointsCloserThanItsR() {
        // Sides of 80 px: with R = 100 the chords between two sides cross the middle of the triangle, with
        // R = 100 × 0.3 = 30 every link stays on the side its two points share.
        val path = polyline(floatArrayOf(40f, 40f), floatArrayOf(120f, 40f), floatArrayOf(80f, 109.3f))
        val shaded = HarmonyParams(variant = HarmonyVariant.SHADED)
        val dense = peak(render(brush(shaded), path), 76, 84, 59, 67)
        val narrow = peak(render(brush(shaded.copy(spread = 0.3f)), path), 76, 84, 59, 67)
        assertTrue(dense > 0.02f, "R = 100 must link points 80 px apart, peak $dense")
        assertTrue(narrow == 0f, "R = 30 must not, peak $narrow")
    }
}
