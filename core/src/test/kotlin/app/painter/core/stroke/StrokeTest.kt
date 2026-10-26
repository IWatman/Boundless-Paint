package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class StrokeTest {
    private fun line(x0: Float, x1: Float, y: Float, step: Float): List<InputPoint> {
        val out = ArrayList<InputPoint>()
        var x = x0
        while (x <= x1 + 1e-3f) { out.add(InputPoint(x, y)); x += step }
        return out
    }

    private fun draw(
        doc: Document,
        params: BrushParams,
        points: List<InputPoint>,
        mode: StrokeMode = StrokeMode.PAINT,
        color: Rgba = Rgba(1f, 0f, 0f),
    ): Pair<StrokeEngine, app.painter.core.raster.TilePatch?> {
        val e = StrokeEngine(doc, doc.activeId!!, params, color, mode)
        e.begin(points.first())
        e.add(points.drop(1))
        return e to e.end()
    }

    private fun alpha(grid: TileGrid, x: Int, y: Int): Float {
        val px = FloatArray(4)
        grid.getPixel(x, y, px)
        return px[3]
    }

    @Test
    fun spacingIsAccurateOnStraightLine() {
        val params = BrushParams(size = 20f, spacing = 0.1f, smoothing = 0f)
        val xs = ArrayList<Float>()
        val r = StrokeResampler(params) { x, _, _, _, _, _ -> xs.add(x) }
        val pts = line(10f, 210f, 50f, 5f)
        r.begin(pts.first())
        pts.drop(1).forEach(r::add)
        r.end()
        assertTrue(xs.size > 90)
        val expected = 2f
        for (i in 1 until xs.size - 1) {
            val d = xs[i] - xs[i - 1]
            assertTrue(abs(d - expected) <= expected * 0.01f, "gap $i = $d")
        }
        assertEquals(210f, xs.last(), 0.01f)
    }

    @Test
    fun spacingCarriesAcrossUnevenInput() {
        val params = BrushParams(size = 30f, spacing = 0.25f, smoothing = 0f)
        val xs = ArrayList<Float>()
        val r = StrokeResampler(params) { x, _, _, _, _, _ -> xs.add(x) }
        val rnd = Random(1)
        r.begin(InputPoint(0f, 10f))
        var x = 0f
        while (x < 300f) { x += 0.3f + rnd.nextFloat() * 13f; r.add(InputPoint(x, 10f)) }
        r.end()
        for (i in 1 until xs.size - 1) assertEquals(7.5f, xs[i] - xs[i - 1], 0.075f)
    }

    @Test
    fun noGapsAlongLine() {
        val doc = Document.create(256, 128)
        draw(doc, BrushParams(size = 10f), line(10f, 210f, 50.5f, 7f))
        val px = doc.activeRaster!!.pixels
        for (x in 10..210) assertTrue(alpha(px, x, 50) > 0.5f, "gap at $x: ${alpha(px, x, 50)}")
        writeGolden(px, "stroke_line.png")
    }

    @Test
    fun opacityCapsOverlappingDabs() {
        val doc = Document.create(256, 128)
        val (engine, patch) = draw(doc, BrushParams(size = 24f, opacity = 0.5f, flow = 0.6f, hardness = 1f),
            line(20f, 200f, 60f, 3f) + line(20f, 200f, 62f, 3f).reversed())
        assertNotNull(patch)
        assertEquals(0.5f, engine.previewOpacity)
        var maxA = 0f
        for ((_, t) in doc.activeRaster!!.pixels.entries()) {
            var i = 3
            while (i < t.data.size) { maxA = maxOf(maxA, t.data[i]); i += 4 }
        }
        assertTrue(maxA <= 0.5f + 1e-4f, "max alpha $maxA")
        assertTrue(maxA > 0.49f, "max alpha $maxA")
    }

    @Test
    fun eraseReducesAlpha() {
        val doc = Document.create(256, 128)
        val px = doc.activeRaster!!.pixels
        for (y in 0 until 128) for (x in 0 until 256) px.setPixel(x, y, 0.2f, 0.4f, 0.6f, 1f)
        draw(doc, BrushParams(size = 16f, opacity = 0.7f), line(30f, 220f, 64f, 4f), StrokeMode.ERASE)
        assertTrue(alpha(px, 100, 64) < 0.31f)
        assertTrue(alpha(px, 100, 64) > 0.29f)
        assertEquals(1f, alpha(px, 100, 10))
    }

    @Test
    fun alphaLockKeepsTransparentPixels() {
        val doc = Document.create(256, 128)
        val layer = doc.activeRaster!!
        for (y in 40 until 90) for (x in 0 until 100) layer.pixels.setPixel(x, y, 0f, 0f, 1f, 1f)
        layer.alphaLock = true
        draw(doc, BrushParams(size = 20f), line(20f, 200f, 64f, 4f), color = Rgba(1f, 0f, 0f))
        val p = FloatArray(4)
        layer.pixels.getPixel(150, 64, p)
        assertEquals(0f, p[3])
        assertEquals(0f, p[0])
        layer.pixels.getPixel(50, 64, p)
        assertEquals(1f, p[3])
        assertTrue(p[0] > 0.9f && p[2] < 0.1f)
        layer.pixels.getPixel(50, 30, p)
        assertEquals(0f, p[3])
    }

    @Test
    fun alphaLockBuildupKeepsTransparentPixels() {
        val doc = Document.create(512, 128)
        val layer = doc.activeRaster!!
        for (y in 40 until 90) for (x in 0 until 100) layer.pixels.setPixel(x, y, 0f, 0f, 1f, 1f)
        layer.alphaLock = true
        draw(doc, BrushParams(size = 20f, buildup = true), line(20f, 400f, 64f, 4f))
        assertEquals(0f, alpha(layer.pixels, 150, 64))
        assertEquals(1f, alpha(layer.pixels, 50, 64))
        assertNull(layer.pixels.get(1, 0))
    }

    @Test
    fun patchRestoresOriginal() {
        for (buildup in listOf(false, true)) {
            for (mode in StrokeMode.entries) {
                val doc = Document.create(600, 300)
                val px = doc.activeRaster!!.pixels
                val rnd = Random(7)
                for (y in 0 until 200) for (x in 0 until 300) {
                    val a = rnd.nextFloat()
                    px.setPixel(x, y, a * 0.3f, a * 0.5f, a, a)
                }
                val original = px.copy()
                val (_, patch) = draw(doc, BrushParams(size = 30f, buildup = buildup, opacity = 0.8f),
                    line(10f, 560f, 150f, 6f), mode)
                assertNotNull(patch)
                for (k in patch.after.keys) {
                    val a = patch.after[k]
                    val cur = px.get(k)
                    if (a == null) assertNull(cur) else assertTrue(a.data.contentEquals(cur!!.data))
                }
                for ((k, t) in patch.before) px.put(k, t?.copy())
                assertEquals(original.keys(), px.keys(), "mode=$mode buildup=$buildup")
                for (k in original.keys()) {
                    assertTrue(original.get(k)!!.data.contentEquals(px.get(k)!!.data), "$k $mode $buildup")
                }
            }
        }
    }

    @Test
    fun cancelLeavesLayerUnchanged() {
        for (buildup in listOf(false, true)) {
            val doc = Document.create(300, 300)
            val px = doc.activeRaster!!.pixels
            px.setPixel(5, 5, 0.5f, 0.5f, 0.5f, 0.5f)
            val original = px.copy()
            val e = StrokeEngine(doc, doc.activeId!!, BrushParams(buildup = buildup), Rgba.BLACK, StrokeMode.PAINT)
            val pts = line(0f, 290f, 100f, 5f)
            e.begin(pts[0]); e.add(pts.drop(1))
            assertTrue(e.takeDirty().isNotEmpty())
            e.cancel()
            assertEquals(original.keys(), px.keys())
            for (k in original.keys()) assertTrue(original.get(k)!!.data.contentEquals(px.get(k)!!.data))
            assertTrue(e.buffer.keys().isEmpty())
        }
    }

    @Test
    fun bufferTouchesOnlyTilesUnderDabs() {
        val doc = Document.create(1024, 1024)
        val e = StrokeEngine(doc, doc.activeId!!, BrushParams(size = 10f), Rgba.BLACK, StrokeMode.PAINT)
        e.begin(InputPoint(100f, 100f))
        e.add(listOf(InputPoint(150f, 100f), InputPoint(200f, 100f), InputPoint(900f, 900f, predicted = true)))
        assertEquals(setOf(app.painter.core.raster.TileKey(0, 0)), e.takeDirty())
        assertTrue(e.takeDirty().isEmpty())
        e.end()
        assertEquals(setOf(app.painter.core.raster.TileKey(0, 0)), doc.activeRaster!!.pixels.keys())
    }

    @Test
    fun tapLeavesRoundDot() {
        val doc = Document.create(64, 64)
        draw(doc, BrushParams(size = 10f, taperStart = 20f, taperEnd = 20f), listOf(InputPoint(32f, 32f)))
        val px = doc.activeRaster!!.pixels
        assertTrue(alpha(px, 32, 32) > 0.99f)
        assertEquals(0f, alpha(px, 32, 40))
    }

    @Test
    fun taperNarrowsEnds() {
        val doc = Document.create(512, 128)
        draw(doc, BrushParams(size = 20f, taperStart = 60f, taperEnd = 60f, smoothing = 0f),
            line(20f, 480f, 64.5f, 5f))
        val px = doc.activeRaster!!.pixels
        fun width(x: Int) = (40 until 90).count { alpha(px, x, it) > 0.5f }
        assertTrue(width(250) >= 18, "mid ${width(250)}")
        assertTrue(width(25) < 8, "start ${width(25)}")
        assertTrue(width(476) < 8, "end ${width(476)}")
        assertTrue(alpha(px, 470, 64) > 0f)
        writeGolden(px, "stroke_taper.png")
    }

    @Test
    fun stabilizerReducesJitter() {
        val rnd = Random(42)
        val raw = (0 until 600).map { i ->
            InputPoint(i.toFloat(), 100f + 40f * sin(i / 40f) + (rnd.nextFloat() - 0.5f) * 6f, timeMs = i * 8L)
        }
        fun jitter(s: Float): Double {
            val st = Stabilizer(s, 0f)
            val out = ArrayList<InputPoint>()
            out.add(st.begin(raw[0]))
            for (p in raw.drop(1)) out.add(st.add(p))
            var sum = 0.0
            for (i in 1 until out.size - 1) {
                val d = out[i + 1].y - 2 * out[i].y + out[i - 1].y
                sum += d * d
            }
            return sqrt(sum / (out.size - 2))
        }
        val j0 = jitter(0f)
        val j30 = jitter(30f)
        val j60 = jitter(60f)
        val j90 = jitter(90f)
        assertTrue(j30 < j0 && j60 < j30 && j90 < j60, "$j0 $j30 $j60 $j90")
    }

    @Test
    fun stabilizerEndReachesPenUp() {
        val st = Stabilizer(100f, 50f)
        st.begin(InputPoint(0f, 0f))
        for (i in 1..20) st.add(InputPoint(i * 10f, 0f, timeMs = i * 8L))
        val tail = st.end()
        assertEquals(200f, tail.last().x)
        for (i in 1 until tail.size) assertTrue(tail[i].x >= tail[i - 1].x)
    }

    @Test
    fun speedSmoothingAddsLag() {
        fun lagAt(speed: Float): Float {
            val st = Stabilizer(20f, speed)
            st.begin(InputPoint(0f, 0f))
            var last = 0f
            for (i in 1..10) last = st.add(InputPoint(i * 20f, 0f, timeMs = i * 8L)).x
            return 200f - last
        }
        assertTrue(lagAt(100f) > lagAt(0f))
    }

    @Test
    fun curveIsMonotoneAndHonoursMin() {
        val c = Curve(listOf(0f to 0f, 0.3f to 0.1f, 0.5f to 0.8f, 0.7f to 0.82f, 1f to 1f))
        var prev = -1f
        for (i in 0..1000) {
            val v = c.eval(i / 1000f)
            assertTrue(v >= prev - 1e-6f, "at $i: $v < $prev")
            assertTrue(v in 0f..1f)
            prev = v
        }
        assertEquals(0f, c.eval(0f), 1e-6f)
        assertEquals(0.8f, c.eval(0.5f), 1e-6f)
        assertEquals(1f, c.eval(1f), 1e-6f)
        val lin = Curve.linear(0.2f)
        assertEquals(0.2f, lin.eval(0f), 1e-6f)
        assertEquals(0.6f, lin.eval(0.5f), 1e-6f)
        assertEquals(1f, lin.eval(2f), 1e-6f)
    }

    @Test
    fun paramsSerializeRoundTrip() {
        val p = BrushParams(size = 33f, pressureToFlow = Curve(listOf(0f to 0f, 0.5f to 0.7f, 1f to 1f), 0.1f))
        val json = Json.encodeToString(BrushParams.serializer(), p)
        val back = Json.decodeFromString(BrushParams.serializer(), json)
        assertEquals(p, back)
        assertEquals(p.pressureToFlow!!.eval(0.3f), back.pressureToFlow!!.eval(0.3f))
    }

    @Test
    fun pressureControlsSize() {
        val sizes = ArrayList<Float>()
        val r = StrokeResampler(BrushParams(size = 50f)) { _, _, s, _, _, _ -> sizes.add(s) }
        r.begin(InputPoint(0f, 0f, pressure = 0f))
        r.add(InputPoint(100f, 0f, pressure = 0f))
        r.end()
        assertTrue(sizes.all { abs(it - 10f) < 1e-3f })
        val doc = Document.create(64, 64)
        val e = StrokeEngine(doc, doc.activeId!!, BrushParams(size = 50f), Rgba.BLACK, StrokeMode.PAINT)
        e.begin(InputPoint(32f, 32f, pressure = 0f))
        e.end()
        assertTrue(alpha(doc.activeRaster!!.pixels, 32 + 20, 32) > 0.5f, "finger must be full pressure")
    }

    private fun writeGolden(grid: TileGrid, name: String) {
        val img = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_ARGB)
        val p = FloatArray(4)
        for (y in 0 until grid.height) for (x in 0 until grid.width) {
            grid.getPixel(x, y, p)
            val a = p[3]
            fun c(v: Float) = ((if (a > 0f) v / a else 0f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            img.setRGB(x, y, ((a * 255f + 0.5f).toInt() shl 24) or (c(p[0]) shl 16) or (c(p[1]) shl 8) or c(p[2]))
        }
        val dir = File("build/stroke-golden").apply { mkdirs() }
        ImageIO.write(img, "png", File(dir, name))
    }
}
