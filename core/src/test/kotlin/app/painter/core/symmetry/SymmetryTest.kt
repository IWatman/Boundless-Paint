package app.painter.core.symmetry

import app.painter.core.geom.Affine2
import app.painter.core.geom.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import app.painter.core.stroke.SymmetryCopies

class SymmetryTest {
    private val c = Vec2(500f, 400f)
    private val start = Vec2(600f, 300f)

    private fun near(a: Vec2, b: Vec2, eps: Float = 1e-2f) =
        assertTrue(abs(a.x - b.x) < eps && abs(a.y - b.y) < eps, "$a != $b")

    private fun cfg(type: SymmetryType, axes: MirrorAxes = MirrorAxes.V, n: Int = 6, fan: Float = 360f, angle: Float = 90f, crop: Boolean = false) =
        SymmetryConfig(on = true, type = type, center = c, axes = axes, planes = n, fanDeg = fan, angleDeg = angle, crop = crop)

    @Test
    fun copyCounts() {
        assertSame(SymmetryCopies.NONE, SymmetryMath.copies(SymmetryConfig(), start))
        assertEquals(1, SymmetryMath.copies(cfg(SymmetryType.MIRROR), start).transforms.size)
        assertEquals(1, SymmetryMath.copies(cfg(SymmetryType.MIRROR, MirrorAxes.H), start).transforms.size)
        assertEquals(3, SymmetryMath.copies(cfg(SymmetryType.MIRROR, MirrorAxes.VH), start).transforms.size)
        assertEquals(6, SymmetryMath.copies(cfg(SymmetryType.RADIAL, n = 7), start).transforms.size)
        assertEquals(31, SymmetryMath.copies(cfg(SymmetryType.RADIAL, n = 99), start).transforms.size)
        assertEquals(15, SymmetryMath.copies(cfg(SymmetryType.KALEIDO, n = 8), start).transforms.size)
        assertEquals(31, SymmetryMath.copies(cfg(SymmetryType.KALEIDO, n = 40), start).transforms.size, "capped at 32")
    }

    @Test
    fun allTransformsAreIsometries() {
        for (t in SymmetryType.entries) for (a in MirrorAxes.entries) {
            for (m in SymmetryMath.transforms(cfg(t, a, n = 9, angle = 33f), start)) {
                assertEquals(1f, abs(m.det), 1e-4f)
                near(c, m.map(c))
            }
        }
    }

    @Test
    fun mirrorVerticalAndHorizontal() {
        val v = SymmetryMath.transforms(cfg(SymmetryType.MIRROR), start).single()
        near(Vec2(400f, 300f), v.map(start))
        assertTrue(v.det < 0)
        val h = SymmetryMath.transforms(cfg(SymmetryType.MIRROR, MirrorAxes.H), start).single()
        near(Vec2(600f, 500f), h.map(start))
    }

    @Test
    fun mirrorArbitraryAngle() {
        val m = SymmetryMath.transforms(cfg(SymmetryType.MIRROR, angle = 45f), start).single()
        // Axis at 45° through c: a point on the axis is fixed, one on the normal flips.
        near(Vec2(600f, 500f), m.map(Vec2(600f, 500f)))
        val p = Vec2(600f, 300f)
        val q = m.map(p)
        near(Vec2(400f, 500f), q)
    }

    @Test
    fun vhContainsPointReflection() {
        val ts = SymmetryMath.transforms(cfg(SymmetryType.MIRROR, MirrorAxes.VH), start)
        val images = ts.map { it.map(start) }
        near(Vec2(400f, 300f), images[0]); near(Vec2(600f, 500f), images[1]); near(Vec2(400f, 500f), images[2])
    }

    @Test
    fun radialFanSteps() {
        val full = SymmetryMath.transforms(cfg(SymmetryType.RADIAL, n = 4), start)
        assertEquals(listOf(90f, 180f, 270f), full.map { stepDeg(it) })
        val fan = SymmetryMath.transforms(cfg(SymmetryType.RADIAL, n = 4, fan = 90f), start)
        assertEquals(listOf(30f, 60f, 90f), fan.map { stepDeg(it) })
    }

    private fun stepDeg(t: Affine2): Float {
        val d = Math.toDegrees(atan2(t.b, t.a).toDouble()).toFloat()
        return Math.round(((d % 360) + 360) % 360).toFloat()
    }

    @Test
    fun kaleidoHasRotationsAndReflections() {
        val ts = SymmetryMath.transforms(cfg(SymmetryType.KALEIDO, n = 8), start)
        assertEquals(7, ts.count { it.det > 0 })
        assertEquals(8, ts.count { it.det < 0 })
        val radius = hypot(start.x - c.x, start.y - c.y)
        for (t in ts) assertEquals(radius, hypot(t.map(start).x - c.x, t.map(start).y - c.y), 1e-2f)
    }

    @Test
    fun cropDomain() {
        assertNull(SymmetryMath.copies(cfg(SymmetryType.MIRROR), start).domain)
        val d = SymmetryMath.copies(cfg(SymmetryType.MIRROR, crop = true), start).domain!!
        assertTrue(d(700f, 900f)); assertFalse(d(300f, 300f))
        val q = SymmetryMath.copies(cfg(SymmetryType.MIRROR, MirrorAxes.VH, crop = true), start).domain!!
        assertTrue(q(550f, 350f)); assertFalse(q(550f, 450f)); assertFalse(q(450f, 350f))
        val k = SymmetryMath.copies(cfg(SymmetryType.KALEIDO, n = 4, crop = true), Vec2(c.x + 100f, c.y - 10f)).domain!!
        // Wedges of 45° from the 90° axis.
        assertTrue(k(c.x + 100f, c.y - 50f)); assertFalse(k(c.x + 50f, c.y - 100f)); assertFalse(k(c.x + 100f, c.y + 10f))
        assertTrue(k(c.x, c.y))
    }

    @Test
    fun radialIgnoresCrop() {
        assertNull(SymmetryMath.copies(cfg(SymmetryType.RADIAL, crop = true), start).domain)
    }
}
