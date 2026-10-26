package app.painter.core.fill

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.history.pixel
import app.painter.core.select.PolygonRaster
import app.painter.core.select.RectShape
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradientTest {
    private val bw = listOf(GradientStop(0f, Rgba.BLACK), GradientStop(1f, Rgba.WHITE))

    private fun spec(type: GradientType, spread: Spread = Spread.PAD, reverse: Boolean = false, reps: Int = 1) =
        GradientSpec(type, 0f, 0f, 100f, 0f, bw, spread, reverse, reps, dither = false)

    @Test
    fun gradient_linear_endpoints() {
        val g = spec(GradientType.LINEAR)
        assertEquals(0f, Gradient.t(g, 0f, 50f), 1e-6f)
        assertEquals(1f, Gradient.t(g, 100f, -20f), 1e-6f)
        assertEquals(0.25f, Gradient.t(g, 25f, 7f), 1e-6f)
        assertEquals(0f, Gradient.t(g, -50f, 0f), 1e-6f)
        val out = FloatArray(4)
        GradientEvaluator(g).colorAt(0.5f, out)
        assertEquals(0.5f, out[0], 1e-6f)
    }

    @Test
    fun gradient_radial() {
        val g = spec(GradientType.RADIAL)
        assertEquals(0.5f, Gradient.t(g, 0f, 50f), 1e-6f)
        assertEquals(0.5f, Gradient.t(g, 30f, 40f), 1e-6f)
        assertEquals(1f, Gradient.t(g, 300f, 0f), 1e-6f)
    }

    @Test
    fun gradient_symmetric() {
        val g = spec(GradientType.SYMMETRIC)
        assertEquals(0.3f, Gradient.t(g, -30f, 0f), 1e-6f)
        assertEquals(0.3f, Gradient.t(g, 30f, 5f), 1e-6f)
    }

    @Test
    fun gradient_angular_reps() {
        val g = spec(GradientType.ANGULAR, reps = 4)
        assertEquals(0.5f, Gradient.t(g, 10f, 10f), 1e-4f) // 45° = 1/8 turn × 4
        assertEquals(0f, Gradient.t(g, 0f, 10f), 1e-4f) // 90° = 1/4 turn × 4
        val one = spec(GradientType.ANGULAR)
        assertEquals(0.5f, Gradient.t(one, -10f, 0f), 1e-4f)
        val refl = spec(GradientType.ANGULAR, Spread.REFLECT, reps = 1)
        assertEquals(0.5f, Gradient.t(refl, -10f, 0f), 1e-4f)
        assertEquals(0.5f, Gradient.t(spec(GradientType.ANGULAR, Spread.REFLECT, reps = 2), 0f, 10f), 1e-4f)
        assertEquals(0.5f, Gradient.t(spec(GradientType.ANGULAR, Spread.REFLECT, reps = 2), 0f, -10f), 1e-4f)
    }

    @Test
    fun gradient_spread_repeat_reflect() {
        assertEquals(0.5f, Gradient.t(spec(GradientType.LINEAR, Spread.REPEAT), 150f, 0f), 1e-5f)
        assertEquals(0.75f, Gradient.t(spec(GradientType.LINEAR, Spread.REFLECT), 125f, 0f), 1e-5f)
        assertEquals(0.25f, Gradient.t(spec(GradientType.LINEAR, Spread.REFLECT), -25f, 0f), 1e-5f)
        assertEquals(1f, Gradient.t(spec(GradientType.LINEAR, Spread.PAD), 150f, 0f), 1e-5f)
    }

    @Test
    fun gradient_reverse() {
        assertEquals(0.75f, Gradient.t(spec(GradientType.LINEAR, reverse = true), 25f, 0f), 1e-6f)
    }

    @Test
    fun gradient_premultInterp_noDarkFringe() {
        val g = GradientSpec(GradientType.LINEAR, 0f, 0f, 100f, 0f,
            listOf(GradientStop(0f, Rgba(1f, 0f, 0f, 1f)), GradientStop(1f, Rgba(0f, 0f, 0f, 0f))), dither = false)
        val out = FloatArray(4)
        GradientEvaluator(g).colorAt(0.5f, out)
        assertEquals(0.5f, out[3], 1e-6f)
        assertEquals(1f, out[0] / out[3], 1e-6f)
    }

    @Test
    fun gradient_dither_deterministic() {
        val g = spec(GradientType.LINEAR).copy(dither = true)
        val a = FloatArray(4); val b = FloatArray(4)
        for (i in 0 until 50) {
            Gradient.eval(g, i * 3, i * 7, a)
            GradientEvaluator(g).eval(i * 3, i * 7, b)
            assertTrue(a.contentEquals(b))
        }
    }

    @Test
    fun gradient_dither_boundedAmplitude() {
        val g = spec(GradientType.LINEAR).copy(dither = true)
        val plain = GradientEvaluator(g.copy(dither = false))
        val d = GradientEvaluator(g)
        val a = FloatArray(4); val b = FloatArray(4)
        var sum = 0.0; var n = 0
        for (y in 0 until 64) for (x in 0 until 100) {
            plain.eval(x, y, a); d.eval(x, y, b)
            for (c in 0 until 3) {
                assertTrue(abs(a[c] - b[c]) <= 1f / 255 + 1e-6f)
                assertTrue(b[c] <= b[3] + 1e-6f && b[c] >= 0f)
                sum += b[c] - a[c]; n++
            }
        }
        assertTrue(abs(sum / n) < 0.2 / 255, "mean ${sum / n}")
    }

    @Test
    fun gradient_stops_unsorted_sorted() {
        val g = GradientSpec(GradientType.LINEAR, 0f, 0f, 100f, 0f,
            listOf(GradientStop(1f, Rgba.WHITE), GradientStop(0.5f, Rgba(1f, 0f, 0f)), GradientStop(0f, Rgba.BLACK)), dither = false)
        val out = FloatArray(4)
        val e = GradientEvaluator(g)
        e.colorAt(0.5f, out)
        assertEquals(1f, out[0]); assertEquals(0f, out[1])
        e.colorAt(0.25f, out)
        assertEquals(0.5f, out[0], 1e-6f); assertEquals(0f, out[1], 1e-6f)
        e.colorAt(0.75f, out)
        assertEquals(1f, out[0], 1e-6f); assertEquals(0.5f, out[1], 1e-6f)
    }

    @Test
    fun gradient_respectsSelection() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        val sel = PolygonRaster.rasterize(RectShape(50f, 50f, 40f, 40f).toPolygon(), 300, 300, false)
        for ((k, t) in sel.entries()) doc.selection.mask.put(k, t)
        val g = GradientSpec(GradientType.LINEAR, 0f, 0f, 300f, 0f, bw)
        val patch = Gradient.apply(doc, l.id, g, 1f, null)!!
        assertTrue(patch.after.isNotEmpty())
        assertEquals(1f, pixel(l.pixels, 50, 50)[3], 1e-3f)
        assertEquals(0f, pixel(l.pixels, 200, 50)[3])
        val region = PolygonRaster.rasterize(RectShape(40f, 50f, 20f, 300f).toPolygon(), 300, 300, false)
        val doc2 = Document.create(300, 300)
        Gradient.apply(doc2, doc2.activeId!!, g, 0.5f, region)
        assertEquals(0.5f, pixel(doc2.activeRaster!!.pixels, 40, 100)[3], 3e-3f)
        assertEquals(0f, pixel(doc2.activeRaster!!.pixels, 100, 100)[3])
    }
}
