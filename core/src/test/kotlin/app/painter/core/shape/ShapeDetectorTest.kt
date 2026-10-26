package app.painter.core.shape

import app.painter.core.geom.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShapeDetectorTest {
    /**
     * Hand-like noise: a low-frequency offset (Gaussian knots, σ = 1% of the bbox diagonal, smoothly
     * interpolated) plus small per-point jitter.
     */
    private fun noisy(clean: List<Vec2>, rnd: Random, sigmaFrac: Float = 0.01f): List<Vec2> {
        val d = ShapeDetector.bboxDiag(clean)
        val knots = 10
        fun g() = (rnd.nextGaussian() * sigmaFrac * d).toFloat()
        val kx = FloatArray(knots + 1) { g() }; val ky = FloatArray(knots + 1) { g() }
        return clean.mapIndexed { i, p ->
            val t = i.toFloat() / (clean.size - 1) * knots
            val k = t.toInt().coerceAtMost(knots - 1); val f = t - k
            val s = (1 - cos(f * PI.toFloat())) / 2
            Vec2(
                p.x + kx[k] + (kx[k + 1] - kx[k]) * s + (rnd.nextGaussian() * 0.002 * d).toFloat(),
                p.y + ky[k] + (ky[k + 1] - ky[k]) * s + (rnd.nextGaussian() * 0.002 * d).toFloat(),
            )
        }
    }

    private fun Random.nextGaussian(): Double {
        val u = nextDouble().coerceAtLeast(1e-12); val v = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * cos(2 * PI * v)
    }

    private fun polyPoints(v: List<Vec2>, closed: Boolean, n: Int = 160): List<Vec2> {
        val line = ArcPolyline(v, closed)
        val count = n - 1
        return (0..count).map { line.at(line.length * it / count) }
    }

    private fun curvePoints(n: Int = 160, f: (Double) -> Vec2) = (0 until n).map { f(it / (n - 1.0)) }

    private fun rot(p: Vec2, c: Vec2, deg: Double): Vec2 = toWorld(c, (deg * PI / 180).toFloat(), p - c)

    private val generators: Map<ShapeKind, (Random) -> List<Vec2>> = mapOf(
        ShapeKind.LINE to { r ->
            val a = r.nextDouble() * 2 * PI
            curvePoints { t -> Vec2((200 + 300 * t * cos(a)).toFloat(), (200 + 300 * t * sin(a)).toFloat()) }
        },
        ShapeKind.ARC to { r ->
            val a0 = r.nextDouble() * 2 * PI; val sweep = (100 + 80 * r.nextDouble()) * PI / 180
            curvePoints { t -> Vec2((300 + 150 * cos(a0 + sweep * t)).toFloat(), (300 + 150 * sin(a0 + sweep * t)).toFloat()) }
        },
        ShapeKind.CIRCLE to { r ->
            val a0 = r.nextDouble() * 2 * PI
            curvePoints(200) { t -> Vec2((300 + 120 * cos(a0 + 2 * PI * t)).toFloat(), (300 + 120 * sin(a0 + 2 * PI * t)).toFloat()) }
        },
        ShapeKind.ELLIPSE to { r ->
            val a0 = r.nextDouble() * 2 * PI; val rt = r.nextDouble() * 180
            curvePoints(200) { t ->
                rot(Vec2((300 + 170 * cos(a0 + 2 * PI * t)).toFloat(), (300 + 80 * sin(a0 + 2 * PI * t)).toFloat()), Vec2(300f, 300f), rt)
            }
        },
        ShapeKind.TRIANGLE to { _ -> polyPoints(listOf(Vec2(100f, 300f), Vec2(400f, 280f), Vec2(230f, 60f), Vec2(100f, 300f)), false) },
        ShapeKind.RECT to { _ ->
            val c = Vec2(300f, 300f)
            polyPoints(listOf(Vec2(200f, 240f), Vec2(400f, 240f), Vec2(400f, 360f), Vec2(200f, 360f), Vec2(200f, 240f)).map { rot(it, c, 30.0) }, false)
        },
        ShapeKind.QUAD to { _ -> polyPoints(listOf(Vec2(100f, 100f), Vec2(340f, 100f), Vec2(270f, 250f), Vec2(140f, 250f), Vec2(100f, 100f)), false) },
        ShapeKind.POLYGON to { _ ->
            polyPoints((0..5).map { polar(Vec2(300f, 300f), 130f, (-PI / 2 + it * 2 * PI / 5).toFloat()) }, false, 200)
        },
        ShapeKind.POLYLINE to { _ -> polyPoints(listOf(Vec2(0f, 0f), Vec2(120f, 120f), Vec2(240f, 0f), Vec2(360f, 120f)), false) },
        ShapeKind.CURVE to { _ -> curvePoints { t -> Vec2((400 * t).toFloat(), (100 * sin(2 * PI * t)).toFloat()) } },
    )

    @Test
    fun recognisesNoisyFigures() {
        val report = StringBuilder()
        var failed = false
        for ((kind, gen) in generators) {
            val rnd = Random(kind.ordinal * 1000 + 7)
            val wrong = HashMap<String, Int>()
            var ok = 0
            repeat(50) {
                val pts = noisy(gen(rnd), rnd)
                val got = ShapeDetector.detect(pts).firstOrNull()?.kind
                if (got == kind) ok++ else wrong.merge(got.toString(), 1, Int::plus)
            }
            report.append("$kind: $ok/50 $wrong\n")
            if (ok < 48) failed = true
        }
        println(report)
        assertTrue(!failed, "accuracy below 95%:\n$report")
    }

    @Test
    fun fittedGeometryIsClose() {
        val circle = curvePoints(200) { t -> Vec2((300 + 120 * cos(2 * PI * t)).toFloat(), (300 + 120 * sin(2 * PI * t)).toFloat()) }
        val c = ShapeDetector.detect(circle).first()
        assertEquals(ShapeKind.CIRCLE, c.kind)
        val e = c.shape as Shape.Ellipse
        assertEquals(300f, e.center.x, 0.5f); assertEquals(120f, e.rx, 0.5f)
        val rect = generators.getValue(ShapeKind.RECT)(Random(1))
        val r = ShapeDetector.detect(rect).first().shape as Shape.Rect
        assertEquals(30f, r.rot.toDeg(), 1f); assertEquals(200f, r.w, 3f); assertEquals(120f, r.h, 3f)
        val axisRect = polyPoints(listOf(Vec2(0f, 0f), Vec2(200f, 2f), Vec2(200f, 102f), Vec2(0f, 100f), Vec2(0f, 0f)), false)
        assertEquals(0f, (ShapeDetector.detect(axisRect).first().shape as Shape.Rect).rot, "snapped to axes under 4°")
    }

    @Test
    fun curveIsAlwaysLast() {
        for ((_, gen) in generators) {
            val res = ShapeDetector.detect(gen(Random(3)))
            assertEquals(ShapeKind.CURVE, res.last().kind)
            assertTrue(res.size <= ShapeDetector.MAX_RESULTS)
        }
    }

    @Test
    fun priorityChangesWinner() {
        // A slightly squashed circle: both Circle and Ellipse are plausible.
        val pts = curvePoints(200) { t -> Vec2((300 + 120 * cos(2 * PI * t)).toFloat(), (300 + 100 * sin(2 * PI * t)).toFloat()) }
        val base = ShapeDetector.detect(pts)
        assertEquals(ShapeKind.ELLIPSE, base.first().kind)
        val tri = polyPoints(listOf(Vec2(100f, 300f), Vec2(400f, 280f), Vec2(230f, 60f), Vec2(100f, 300f)), false)
        val plain = ShapeDetector.detect(noisy(tri, Random(5)))
        assertEquals(ShapeKind.TRIANGLE, plain.first().kind)
        // Curve is the fallback and stays last whatever its priority (spec G3.4 step 5).
        val second = plain.drop(1).firstOrNull { it.kind != ShapeKind.CURVE }?.kind ?: ShapeKind.CURVE
        val boosted = ShapeDetector.detect(noisy(tri, Random(5)), DetectOptions(priorities = mapOf(ShapeKind.TRIANGLE to 0.5f, second to 2f)))
        if (second == ShapeKind.CURVE) {
            assertEquals(ShapeKind.TRIANGLE, boosted.first().kind)
            assertEquals(ShapeKind.CURVE, boosted.last().kind)
        } else {
            assertEquals(second, boosted.first().kind)
        }
    }

    @Test
    fun shortStrokeGivesNothing() {
        assertTrue(ShapeDetector.detect(listOf(Vec2(0f, 0f), Vec2(10f, 5f), Vec2(20f, 3f))).isEmpty())
        assertTrue(ShapeDetector.detect(listOf(Vec2(0f, 0f), Vec2(10f, 5f)), DetectOptions(screenToDoc = 0.1f)).isNotEmpty())
    }

    @Test
    fun manyCornersGiveEllipse() {
        val star = (0..24).map { polar(Vec2(300f, 300f), if (it % 2 == 0) 150f else 135f, (it * 2 * PI / 24).toFloat()) }
        val res = ShapeDetector.detect(polyPoints(star, false, 300))
        assertTrue(res.all { it.kind == ShapeKind.CIRCLE || it.kind == ShapeKind.ELLIPSE || it.kind == ShapeKind.CURVE })
        assertTrue(res.first().kind == ShapeKind.CIRCLE || res.first().kind == ShapeKind.ELLIPSE, "${res.map { it.kind }}")
    }
}
