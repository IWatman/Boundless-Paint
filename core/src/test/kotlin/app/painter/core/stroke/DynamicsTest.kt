package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.assertGridsEqual
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicsTest {
    private val flat = Curve(listOf(0f to 1f, 1f to 1f))

    private fun sample(i: Int = 0) = PathSample().apply {
        index = i; x = 100f; y = 100f; pressure = 0.5f; tilt = (PI / 4).toFloat(); azimuth = 1f; twist = 2f
        velocity = 2.5f; distance = 150f; isStylus = true
    }

    private fun dab(params: BrushParams, s: PathSample = sample()): Dab {
        val b = DabBatch()
        DabGenerator(Brush.of(params), BrushResources.EMPTY, StrokeContext(seed = 3), Rgba(0.8f, 0.3f, 0.2f)).emit(s, b)
        return b[0]
    }

    @Test
    fun everySourceDrivesEveryTarget() {
        val lin = Curve.LINEAR
        val base = BrushParams(pressureToSize = Curve.LINEAR, jitter = JitterParams(scatter = 1f, hue = 1f))
        val ref = dab(base)
        for (src in DynSource.entries) for (t in DynTarget.entries) {
            val d = dab(base.copy(dynamics = listOf(DynamicsLink(src, t, lin))))
            val changed = when (t) {
                DynTarget.SIZE -> d.d != ref.d
                DynTarget.FLOW -> d.flow != ref.flow
                DynTarget.OPACITY -> d.opacity != ref.opacity
                DynTarget.SCATTER -> d.x != ref.x || d.y != ref.y
                DynTarget.ANGLE -> d.angle != ref.angle
                DynTarget.HARDNESS -> d.hardness != ref.hardness
                DynTarget.COLOR_JITTER -> d.r != ref.r || d.g != ref.g || d.b != ref.b
                DynTarget.TEX_DEPTH, DynTarget.MIX_IN, DynTarget.DILUTION, DynTarget.BLUR -> true
            }
            assertTrue(changed, "$src -> $t has no effect")
        }
        // Values of the non-rendering targets.
        val d = dab(base.copy(texture = TextureParams(depth = 0.8f), paint = PaintParams(mixIn = 0.4f),
            dynamics = listOf(DynamicsLink(DynSource.PRESSURE, DynTarget.TEX_DEPTH, Curve.LINEAR),
                DynamicsLink(DynSource.PRESSURE, DynTarget.MIX_IN, Curve.LINEAR))))
        assertEquals(0.4f, d.texDepth, 1e-5f)
        assertEquals(0.2f, d.mixIn, 1e-5f)
    }

    @Test
    fun sourceValues() {
        val s = sample()
        fun sizeWith(src: DynSource) = dab(BrushParams(size = 100f, pressureToSize = flat,
            dynamics = listOf(DynamicsLink(src, DynTarget.SIZE, Curve.LINEAR))), s).d
        assertEquals(50f, sizeWith(DynSource.PRESSURE), 1e-3f)
        assertEquals(50f, sizeWith(DynSource.VELOCITY), 1e-3f)
        assertEquals(50f, sizeWith(DynSource.TILT), 1e-3f)
        assertEquals(100f / (2 * PI.toFloat()), sizeWith(DynSource.AZIMUTH), 1e-3f)
        assertEquals(200f / (2 * PI.toFloat()), sizeWith(DynSource.TWIST), 1e-3f)
        assertEquals(50f, sizeWith(DynSource.PROGRESS), 1e-3f)
        val r = sizeWith(DynSource.RANDOM)
        assertTrue(r in 0f..100f)
    }

    @Test
    fun linksOfOneTargetMultiply() {
        val two = dab(BrushParams(size = 100f, pressureToSize = flat, dynamics = listOf(
            DynamicsLink(DynSource.PRESSURE, DynTarget.SIZE, Curve.LINEAR),
            DynamicsLink(DynSource.TILT, DynTarget.SIZE, Curve.LINEAR))))
        assertEquals(25f, two.d, 1e-3f)
        val angle = dab(BrushParams(dynamics = listOf(DynamicsLink(DynSource.PRESSURE, DynTarget.ANGLE, Curve.LINEAR))))
        assertEquals(PI.toFloat(), angle.angle, 1e-4f)
    }

    @Test
    fun flatCurveIsNeutral() {
        val pts = StrokeTestKit.line(20f, 100f, 400f, 120f, 3f).map { it.copy(pressure = 0.6f, isStylus = true) }
        val base = BrushParams(size = 30f)
        val doc1 = Document.create(450, 250)
        val a = engine(doc1, base); drawOpen(a, pts)
        val doc2 = Document.create(450, 250)
        val b = engine(doc2, base.copy(dynamics = DynTarget.entries.filter { it != DynTarget.ANGLE }.map {
            DynamicsLink(DynSource.VELOCITY, it, flat)
        }))
        drawOpen(b, pts)
        assertGridsEqual(a.buffer, b.buffer)
    }

    @Test
    fun velocityShrinksFastSegment() {
        val slow = StrokeTestKit.line(20f, 100f, 300f, 100f, 2f, dtMs = 4L)
        val fast = StrokeTestKit.line(302f, 100f, 600f, 100f, 12f, dtMs = 2L).map { it.copy(timeMs = it.timeMs + slow.last().timeMs + 2) }
        val doc = Document.create(650, 200)
        val e = engine(doc, BrushParams(size = 40f, smoothing = 0f,
            dynamics = listOf(DynamicsLink(DynSource.VELOCITY, DynTarget.SIZE, Curve(listOf(0f to 1f, 1f to 0.2f))))))
        drawOpen(e, slow + fast)
        e.endPatch()
        val px = doc.activeRaster!!.pixels
        fun width(x: Int) = (0 until 200).count { alpha(px, x, it) > 0.5f }
        assertTrue(width(500) < width(150) * 0.7f, "fast ${width(500)} slow ${width(150)}")
    }

    @Test
    fun calibrationReachesFullRange() {
        val c = InputCalibration()
        repeat(5) { c.observeStrokePeak(0.78f) }
        assertTrue(c.pressure(0.78f) >= 0.98f, "p_cal ${c.pressure(0.78f)}")
        c.observeStrokePeak(0.95f)
        assertEquals(0.95f, c.maxPressure, 1e-5f)
        repeat(30) { c.observeStrokePeak(0.5f) }
        assertTrue(c.maxPressure in 0.5f..0.95f)
        val firm = InputCalibration(pressureCurve = Curve(listOf(0f to 0f, 0.5f to 0.3f, 1f to 1f)))
        assertEquals(0.3f, firm.pressure(0.5f), 1e-4f)
    }

    @Test
    fun calibrationIsAppliedToStylusOnly() {
        val cal = InputCalibration(maxPressure = 0.5f, auto = false)
        val doc = Document.create(200, 200)
        val e = engine(doc, BrushParams(size = 100f, pressureToSize = Curve.LINEAR), ctx = StrokeContext(threads = 1, calibration = cal))
        e.begin(InputPoint(100f, 100f, pressure = 0.5f, isStylus = true))
        e.endPatch()
        assertTrue(alpha(doc.activeRaster!!.pixels, 100 + 38, 100) > 0.5f, "0.5 raw = full pressure")
    }

    @Test
    fun tiltOffsetMovesCentre() {
        val d = dab(BrushParams(size = 40f, pressureToSize = flat, tiltOffset = 1f), sample().apply { azimuth = 0f; tilt = (PI / 2).toFloat() })
        assertEquals(120f, d.x, 1e-3f)
        assertEquals(100f, abs(d.y), 1e-3f)
    }
}
