package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaperProfileTest {
    private fun sizes(params: BrushParams, pts: List<InputPoint>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        val gen = DabGenerator(Brush.of(params), BrushResources.EMPTY, StrokeContext(), Rgba.BLACK)
        val batch = DabBatch()
        val w = PathWalker(params, PathSink { s ->
            batch.clear()
            val step = gen.emit(s, batch)
            if (batch.size > 0) out.add(s.x to batch[0].d)
            step
        })
        w.begin(pts[0]); pts.drop(1).forEach(w::add); w.end()
        return out
    }

    @Test
    fun linearVersusSmoothTaper() {
        val pts = line(0f, 0f, 400f, 0f, 4f)
        val lin = sizes(BrushParams(size = 20f, spacing = 0.05f, taperStart = 100f, taperShape = TaperShape.LINEAR), pts)
        val smo = sizes(BrushParams(size = 20f, spacing = 0.05f, taperStart = 100f, taperShape = TaperShape.SMOOTH), pts)
        val l50 = lin.minByOrNull { abs(it.first - 50f) }!!.second
        val s50 = smo.minByOrNull { abs(it.first - 50f) }!!.second
        assertEquals(10f, l50, 0.4f)
        assertEquals(15f, s50, 0.4f)
    }

    @Test
    fun taperSizeAndOpacityAmounts() {
        val gen = DabGenerator(Brush.of(BrushParams(size = 20f, pressureToSize = Curve(listOf(0f to 1f, 1f to 1f)), taperSize = 0.5f, taperOpacity = 1f)),
            BrushResources.EMPTY, StrokeContext(), Rgba.BLACK)
        val b = DabBatch()
        gen.emit(PathSample().apply { taper = 0.5f }, b)
        assertEquals(15f, b[0].d, 1e-4f)
        assertEquals(0.5f, b[0].flow, 1e-4f)
    }

    @Test
    fun profileMiddleIsConstantOn880px() {
        val thinThickThin = Curve(listOf(0f to 0.1f, 1f / 3 to 1f, 2f / 3 to 1f, 1f to 0.1f))
        val params = BrushParams(size = 30f, spacing = 0.1f, smoothing = 0f, pressureToSize = Curve(listOf(0f to 1f, 1f to 1f)),
            profiles = Profiles(size = thinThickThin))
        val doc = Document.create(1100, 200)
        val e = engine(doc, params)
        val pts = line(50f, 100f, 1050f, 100f, 5f)
        e.begin(pts[0]); e.add(pts.drop(1)); e.endPatch()
        val px = doc.activeRaster!!.pixels
        fun width(x: Int) = (0 until 200).count { alpha(px, x, it) > 0.5f }
        val mid = width(550)
        assertTrue(mid in 26..32, "mid width $mid")
        for (x in listOf(115, 200, 400, 700, 985)) assertEquals(mid, width(x), "x=$x")
        assertTrue(width(55) < 10 && width(1045) < 10)
    }

    @Test
    fun shortStrokeIsReRenderedWithEqualSections() {
        val profile = Curve(listOf(0f to 0.2f, 0.5f to 1f, 1f to 0.2f))
        val params = BrushParams(size = 40f, spacing = 0.05f, smoothing = 0f, pressureToSize = Curve(listOf(0f to 1f, 1f to 1f)),
            profiles = Profiles(size = profile))
        val doc = Document.create(300, 200)
        val e = engine(doc, params)
        val pts = line(100f, 100f, 190f, 100f, 3f)
        e.begin(pts[0]); e.add(pts.drop(1)); e.endPatch()
        val px = doc.activeRaster!!.pixels
        fun width(x: Int) = (0 until 200).count { alpha(px, x, it) > 0.5f }
        // Symmetric profile on a 90 px stroke: equal widths at mirrored positions.
        assertTrue(abs(width(115) - width(175)) <= 2, "${width(115)} vs ${width(175)}")
        assertTrue(width(145) > width(110) + 6)
    }
}
