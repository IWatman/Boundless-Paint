package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.assertGridsEqual
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JitterScatterTest {
    private fun wavy(): List<InputPoint> {
        val rnd = Random(3)
        return (0 until 200).map { i ->
            InputPoint(20f + i * 3f, 200f + 80f * kotlin.math.sin(i / 15f) + rnd.nextFloat(), pressure = 0.3f + 0.7f * rnd.nextFloat(),
                isStylus = true, timeMs = i * 5L)
        }
    }

    /** The M1 pipeline (Stabilizer → StrokeResampler → DabRenderer) as a reference. */
    private fun m1Buffer(params: BrushParams, color: Rgba, pts: List<InputPoint>, w: Int, h: Int, finish: Boolean): TileGrid {
        val grid = TileGrid(w, h, precise = true)
        val r = DabRenderer()
        val res = StrokeResampler(params) { x, y, size, flow, _, _ ->
            r.render(grid, x, y, size, params.hardness, color.r, color.g, color.b, flow * color.a, DabOp.OVER)
        }
        val st = Stabilizer(params.smoothing, params.smoothingSpeed)
        res.begin(st.begin(pts[0]))
        for (p in pts.drop(1)) res.add(st.add(p))
        if (finish) {
            for (p in st.end()) res.add(p)
            res.end()
        }
        return grid
    }

    @Test
    fun zeroJitterIsBitExactM1() {
        val color = Rgba(0.2f, 0.5f, 0.9f, 0.8f)
        for (params in listOf(
            BrushParams(size = 25f, flow = 0.6f, hardness = 0.5f, taperStart = 40f, taperEnd = 40f),
            BrushParams(size = 0.7f, spacing = 0.3f),
            BrushParams(size = 80f, pressureToFlow = Curve.linear(0.1f), smoothing = 50f),
        )) {
            val pts = wavy()
            val doc = Document.create(700, 400)
            val e = engine(doc, params, color = color, ctx = StrokeContext(threads = 3))
            drawOpen(e, pts)
            assertGridsEqual(m1Buffer(params, color, pts, 700, 400, finish = false), e.buffer, "streaming, params $params")
            e.endPatch()
            // M1 composited the float buffer once into half tiles of an empty layer.
            val ref = m1Buffer(params, color, pts, 700, 400, finish = true)
            val half = TileGrid(700, 400)
            for ((k, t) in ref.entries()) if (!t.isEmpty()) half.put(k, t.copy(precise = false))
            assertGridsEqual(half, doc.activeRaster!!.pixels, "committed, params $params")
        }
    }

    @Test
    fun scatterMeanIsZero() {
        val brush = Brush.of(BrushParams(size = 50f, jitter = JitterParams(scatter = 1f)))
        val gen = DabGenerator(brush, BrushResources.EMPTY, StrokeContext(seed = 5L), Rgba.BLACK)
        val batch = DabBatch(16)
        val s = PathSample()
        var sumN = 0.0
        var sumT = 0.0
        var maxN = 0f
        val n = 10_000
        for (i in 0 until n) {
            s.index = i; s.x = 100f; s.y = 100f; s.dirX = 1f; s.dirY = 0f
            batch.clear()
            gen.emit(s, batch)
            val d = batch[0]
            sumT += d.x - 100f
            sumN += d.y - 100f
            maxN = maxOf(maxN, abs(d.y - 100f))
        }
        assertTrue(abs(sumN / n) < 0.05 * 50, "normal mean ${sumN / n}")
        assertTrue(abs(sumT / n) < 0.05 * 50, "tangent mean ${sumT / n}")
        assertTrue(maxN in 45f..50f, "max normal offset $maxN")
    }

    @Test
    fun countEmitsDabsWithDistinctIndices() {
        val brush = Brush.of(BrushParams(jitter = JitterParams(count = 4, size = 0.5f)))
        val gen = DabGenerator(brush, BrushResources.EMPTY, StrokeContext(), Rgba.BLACK)
        val batch = DabBatch()
        val s = PathSample().also { it.index = 3 }
        gen.emit(s, batch)
        assertEquals(4, batch.size)
        assertEquals(listOf(48, 49, 50, 51), (0 until 4).map { batch[it].index })
        assertTrue((0 until 4).map { batch[it].d }.toSet().size > 1)
    }

    @Test
    fun colorJitterStaysNearColor() {
        val brush = Brush.of(BrushParams(jitter = JitterParams(hue = 0.1f, brightness = 0.2f)))
        val gen = DabGenerator(brush, BrushResources.EMPTY, StrokeContext(seed = 9), Rgba(1f, 0f, 0f))
        val batch = DabBatch()
        val s = PathSample()
        var differs = 0
        for (i in 0 until 200) {
            s.index = i; batch.clear(); gen.emit(s, batch)
            val d = batch[0]
            assertTrue(d.b <= 0.01f || d.g <= 0.01f, "hue stays within ±18° of red")
            if (abs(d.r - 1f) > 1e-3f || d.g > 1e-3f || d.b > 1e-3f) differs++
        }
        assertTrue(differs > 150)
    }
}
