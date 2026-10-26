package app.painter.core.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class Affine2Test {
    private fun near(a: Vec2, b: Vec2) = assertTrue(abs(a.x - b.x) < 1e-3f && abs(a.y - b.y) < 1e-3f, "$a != $b")

    @Test
    fun rotationAroundCenter() {
        val r = Affine2.rotation(Vec2(10f, 10f), (PI / 2).toFloat())
        near(Vec2(10f, 15f), r.map(Vec2(15f, 10f)))
        near(Vec2(10f, 10f), r.map(Vec2(10f, 10f)))
    }

    @Test
    fun reflectionAcrossVerticalAxis() {
        val m = Affine2.reflection(Vec2(100f, 0f), (PI / 2).toFloat())
        near(Vec2(130f, 7f), m.map(Vec2(70f, 7f)))
        assertTrue(m.det < 0f)
    }

    @Test
    fun inverseAndCompose() {
        val t = Affine2.rotation(Vec2(3f, 4f), 0.7f).then(Affine2.scale(2f, 0.5f)).then(Affine2.translation(5f, -2f))
        val p = Vec2(12f, -8f)
        near(p, t.inverse().map(t.map(p)))
        near(t.map(p), Affine2.ID.then(t).map(p))
    }
}
