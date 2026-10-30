package app.painter.core.stroke

import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.assertGridsEqual
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Watercolor (spec/tools/m2-brush.md §1.9): the wet grid keeps the pigment it was given, more bleed spreads it
 * further, a stroke is dry within 4 s of simulated time, the rim of a drying dab is darker than its centre, and a wet
 * stroke commits deterministically.
 */
class WatercolorTest {
    /** The grid of a [size]² document; every test starts from an empty one. */
    private fun sim(params: WatercolorParams, size: Int = 300) = WetSimulation(params, size, size)

    /** One wet dab of [d] px at ([x], [y]) in a fresh batch; [hardness] 1 is a hard edge, 0.5 a soft one. */
    private fun dab(x: Float = 150f, y: Float = 150f, d: Float = 40f, hardness: Float = 0.5f): DabBatch {
        val batch = DabBatch()
        val b = batch.add()
        b.x = x
        b.y = y
        b.d = d
        b.hardness = hardness
        b.flow = 1f
        b.opacity = 1f
        b.r = 0.2f
        b.g = 0.3f
        b.b = 0.8f
        b.a = 1f
        return batch
    }

    /** Number of cells (every 2nd pixel, step [step]) whose pigment alpha is above [limit]. */
    private fun wetCells(sim: WetSimulation, size: Int = 300, step: Int = 2, limit: Float = 0.002f): Int {
        val px = FloatArray(4)
        var n = 0
        for (y in 0 until size step step) {
            for (x in 0 until size step step) {
                sim.pigmentAt(x, y, px)
                if (px[3] > limit) n++
            }
        }
        return n
    }

    @Test
    fun pigmentMassIsConservedWhileWet() {
        val sim = sim(WatercolorParams(bleed = 1f, glaze = 1f, dryout = 0f))
        sim.deposit(dab())
        val m0 = sim.pigmentMass()
        assertTrue(m0 > 0.0, "the dab must deposit pigment")
        repeat(30) { sim.tick() }
        val m = sim.pigmentMass()
        assertTrue(abs(m - m0) <= 0.01 * m0, "pigment mass drifts over the ticks: $m0 → $m")
    }

    @Test
    fun aDropSpreadsMoreWithMoreBleed() {
        val dry = WatercolorParams(bleed = 0.1f, glaze = 0f, dryout = 0f)
        val wet = WatercolorParams(bleed = 1f, glaze = 0f, dryout = 0f)
        val slow = sim(dry).also { it.deposit(dab()); it.dry() }
        val fast = sim(wet).also { it.deposit(dab()); it.dry() }
        val nSlow = wetCells(slow)
        val nFast = wetCells(fast)
        assertTrue(nFast > nSlow, "bleed 1 must spread further than bleed 0.1: $nFast vs $nSlow cells")
    }

    @Test
    fun itDriesWithinFourSeconds() {
        val slow = dryTicks(0f)
        val fast = dryTicks(1f)
        assertTrue(fast < slow, "dryout 1 must dry faster than dryout 0: $fast vs $slow ticks")
    }

    /** Ticks [WetSimulation.dry] runs for a single dab at [dryout]; both invariants of the loop are checked here. */
    private fun dryTicks(dryout: Float): Int {
        val sim = sim(WatercolorParams(bleed = 0.5f, glaze = 0f, dryout = dryout))
        sim.deposit(dab())
        val n = sim.dry()
        assertTrue(sim.isDry, "the grid must be dry after dry(): dryout=$dryout")
        assertTrue(n <= 120, "dry() runs at most 4 s of ticks: $n at dryout=$dryout")
        return n
    }

    @Test
    fun theRimIsDarkerThanTheCentre() {
        val sim = sim(WatercolorParams(bleed = 0.3f, glaze = 1f, dryout = 0.5f))
        sim.deposit(dab(d = 80f, hardness = 1f))
        sim.dry()
        val px = FloatArray(4)
        sim.pigmentAt(150, 150, px)
        val centre = px[3]
        assertTrue(centre > 0f, "the dab must deposit pigment at its centre")
        var ring = 0f
        // The dab is 40 px in radius: the rim sits a little inside it.
        for (k in 0 until 36) {
            val a = k * 10 * PI.toFloat() / 180f
            for (r in 30..38) {
                sim.pigmentAt((150f + r * cos(a)).toInt(), (150f + r * sin(a)).toInt(), px)
                ring = max(ring, px[3])
            }
        }
        assertTrue(ring > centre * 1.05f, "the rim must be darker than the centre: $ring vs $centre")
    }

    @Test
    fun aStrokeCommitsAndIsDeterministic() {
        val params = BrushParams(
            size = 30f, hardness = 0.5f, spacing = 0.1f, smoothing = 0f, opacity = 1f, flow = 0.8f,
            special = WatercolorParams(),
        )
        val points = line(40f, 100f, 160f, 100f, 2f).mapIndexed { i, p -> p.copy(timeMs = i * 8L) }
        val a = render(params, points)
        val b = render(params, points)
        assertTrue(alpha(a, 100, 100) > 0f, "the stroke must commit pigment at its middle")
        assertGridsEqual(a, b, "watercolor: two runs of the same stroke")
    }

    /** Draws [points] with [params] on a fresh [size]² document and returns the pixels of the layer. */
    private fun render(params: BrushParams, points: List<InputPoint>, size: Int = 200): TileGrid {
        val doc = Document.create(size, size)
        val e = engine(doc, params, ctx = StrokeContext(threads = 1, seed = 3L))
        drawOpen(e, points)
        assertNotNull(e.endPatch(), "the stroke must commit a patch")
        return doc.activeRaster!!.pixels
    }
}
