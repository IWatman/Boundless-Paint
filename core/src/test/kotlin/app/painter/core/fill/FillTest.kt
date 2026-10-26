package app.painter.core.fill

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.history.History
import app.painter.core.history.TilePatchCommand
import app.painter.core.history.fillRect
import app.painter.core.history.pixel
import app.painter.core.raster.BitPlane
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.select.MaskOps
import app.painter.core.select.PolygonRaster
import app.painter.core.select.RectShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FillTest {
    private fun src(g: app.painter.core.raster.TileGrid) = PixelSource { g.get(it) }

    @Test
    fun fillable_chebyshevMetric() {
        val doc = Document.create(64, 64)
        val g = doc.activeRaster!!.pixels
        fillRect(g, 0, 0, 64, 64, 0.5f, 0.5f, 0.5f, 1f)
        fillRect(g, 0, 0, 1, 1, 0.6f, 0.6f, 0.6f, 1f) // Chebyshev 0.1, Euclidean 0.17
        fillRect(g, 1, 0, 2, 1, 0.5f, 0.5f, 0.62f, 1f) // 0.12
        val f = Region.fillable(src(g), 64, 64, Rgba(0.5f, 0.5f, 0.5f), RegionParams(tolerance = 0.11f))
        assertTrue(f[0, 0])
        assertFalse(f[1, 0])
        assertTrue(f[5, 5])
    }

    @Test
    fun fillable_alphaBoundary() {
        val g = app.painter.core.raster.TileGrid(64, 64)
        fillRect(g, 0, 0, 10, 10, 1f, 0f, 0f, 1f)
        fillRect(g, 10, 0, 20, 10, 0f, 0f, 1f, 1f)
        fillRect(g, 20, 0, 30, 10, 0f, 0f, 1f, 0.5f)
        val f = Region.fillable(src(g), 64, 64, Rgba(1f, 0f, 0f), RegionParams(boundary = Boundary.ALPHA, tolerance = 0.1f))
        assertTrue(f[15, 5])
        assertFalse(f[25, 5])
        assertFalse(f[40, 40])
    }

    @Test
    fun fillable_tol100_all() {
        val g = app.painter.core.raster.TileGrid(300, 300)
        fillRect(g, 0, 0, 100, 100, 1f, 0f, 0f, 1f)
        val f = Region.fillable(src(g), 300, 300, Rgba.TRANSPARENT, RegionParams(tolerance = 1f))
        assertEquals(300L * 300, f.count())
    }

    @Test
    fun flood_4connected_diagonalBlocks() {
        val f = BitPlane(32, 32)
        for (y in 0 until 32) for (x in 0 until 32) f[x, y] = x != y
        val r = Region.flood(f, 20, 2)
        assertTrue(r[31, 0])
        assertFalse(r[2, 20])
        assertEquals((32L * 32 - 32) / 2, r.count())
    }

    @Test
    fun flood_nonContiguous_equalsF() {
        val doc = Document.create(64, 64)
        val g = doc.activeRaster!!.pixels
        fillRect(g, 0, 0, 10, 10, 1f, 0f, 0f, 1f)
        fillRect(g, 30, 30, 40, 40, 1f, 0f, 0f, 1f)
        val m = Region.compute(doc, RefSource.Current, 5, 5, RegionParams(contiguous = false))!!
        assertEquals(255, m.byteAt(35, 35))
        val c = Region.compute(doc, RefSource.Current, 5, 5, RegionParams())!!
        assertEquals(0, c.byteAt(35, 35))
    }

    @Test
    fun flood_largeNoStackOverflow() {
        val f = BitPlane(4096, 4096)
        for (y in 0 until 4096) for (x in 0 until 4096) f[x, y] = true
        val r = Region.flood(f, 100, 100)
        assertEquals(4096L * 4096, r.count())
    }

    /** Ring of line art (3 px thick) with a [gap] px opening on the right. */
    private fun ringDoc(gap: Int): Document {
        val doc = Document.create(200, 200)
        val g = doc.activeRaster!!.pixels
        for (y in 0 until 200) for (x in 0 until 200) {
            val d = sqrt(((x + 0.5 - 100) * (x + 0.5 - 100) + (y + 0.5 - 100) * (y + 0.5 - 100)))
            if (d in 50.0..53.0 && !(x > 140 && abs(y + 0.5 - 100) < gap / 2.0)) g.setPixel(x, y, 0f, 0f, 0f, 1f)
        }
        return doc
    }

    @Test
    fun gap_closesSmallGap() {
        val doc = ringDoc(3)
        val leak = Region.compute(doc, RefSource.Current, 100, 100, RegionParams(gapPx = 0))!!
        assertEquals(255, leak.byteAt(5, 5))
        val closed = Region.compute(doc, RefSource.Current, 100, 100, RegionParams(gapPx = 4))!!
        assertEquals(0, closed.byteAt(5, 5))
        assertEquals(255, closed.byteAt(100, 100))
        assertEquals(255, closed.byteAt(100, 52 + 0))
        assertTrue(closed.bounds()!!.x1 < 165)
    }

    @Test
    fun gap_seedInGap_fallback() {
        val doc = ringDoc(3)
        val r = Region.compute(doc, RefSource.Current, 151, 100, RegionParams(gapPx = 4))!!
        assertEquals(255, r.byteAt(5, 5))
    }

    @Test
    fun edt_matchesBruteForce_random64() {
        val rnd = Random(7)
        val r = BitPlane(64, 64)
        for (i in 0 until 60) r[rnd.nextInt(64), rnd.nextInt(64)] = true
        val area = IntRect(0, 0, 64, 64)
        val s = Edt.signed(r, area)
        val pts = (0 until 64 * 64).filter { r[it % 64, it / 64] }
        for (y in 0 until 64) for (x in 0 until 64) {
            val want = if (r[x, y]) {
                val d = (0 until 64 * 64).filter { !r[it % 64, it / 64] }
                    .minOf { sqrt(((it % 64 - x) * (it % 64 - x) + (it / 64 - y) * (it / 64 - y)).toDouble()) }
                -(d - 0.5)
            } else {
                pts.minOf { sqrt(((it % 64 - x) * (it % 64 - x) + (it / 64 - y) * (it / 64 - y)).toDouble()) } - 0.5
            }
            assertEquals(want, s[y * 64 + x].toDouble(), 1e-4, "at $x,$y")
        }
    }

    @Test
    fun expand_positive_negative() {
        val r = BitPlane(100, 100)
        for (y in 40 until 60) for (x in 40 until 60) r[x, y] = true
        val p = Region.coverage(r, 3f, aa = false)
        assertEquals(IntRect(37, 37, 63, 63), p.bounds())
        val n = Region.coverage(r, -3f, aa = false)
        assertEquals(IntRect(43, 43, 57, 57), n.bounds())
        assertEquals(IntRect(40, 40, 60, 60), Region.coverage(r, 0f, true).bounds())
    }

    @Test
    fun aa_coverageRamp() {
        val r = BitPlane(100, 100)
        for (y in 40 until 60) for (x in 40 until 60) r[x, y] = true
        val m = Region.coverage(r, 1.5f, aa = true)
        assertEquals(255, m.byteAt(39, 50))
        assertEquals(128, m.byteAt(38, 50))
        assertEquals(0, m.byteAt(37, 50))
    }

    @Test
    fun bucket_respectsSelection() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        doc.selection.mask.also { m -> for (y in 0 until 50) for (x in 0 until 50) m.getOrCreate(0, 0).data[(y shl 8) + x] = -1 }
        val cov = Region.compute(doc, RefSource.Current, 10, 10, RegionParams())!!
        assertNotNull(BucketFill.apply(doc, l.id, cov, Rgba(1f, 0f, 0f), 1f, false))
        assertEquals(1f, pixel(l.pixels, 10, 10)[0])
        assertEquals(0f, pixel(l.pixels, 100, 100)[3])
    }

    @Test
    fun bucket_alphaLock() {
        val doc = Document.create(64, 64)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 0, 0, 10, 10, 0f, 0f, 1f, 0.5f)
        l.alphaLock = true
        BucketFill.apply(doc, l.id, MaskMath.full(64, 64), Rgba(1f, 0f, 0f), 1f, false)
        val p = pixel(l.pixels, 5, 5)
        assertEquals(0.5f, p[3], 1e-3f)
        assertEquals(0.5f, p[0], 1e-3f)
        assertEquals(0f, pixel(l.pixels, 20, 20)[3])
    }

    @Test
    fun bucket_erase() {
        val doc = Document.create(64, 64)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 0, 0, 64, 64, 1f, 0f, 0f, 1f)
        val cov = PolygonRaster.rasterize(RectShape(10f, 10f, 20f, 20f).toPolygon(), 64, 64, false)
        BucketFill.apply(doc, l.id, cov, Rgba.BLACK, 0.5f, erase = true)
        assertEquals(0.5f, pixel(l.pixels, 10, 10)[3], 1e-3f)
        assertEquals(1f, pixel(l.pixels, 40, 40)[3])
    }

    @Test
    fun bucket_canvasRef_ignoresPaper() {
        val doc = Document.create(64, 64)
        doc.paper.color = Rgba(0f, 1f, 0f)
        val line = doc.newRasterLayer("line")
        doc.root.add(line)
        for (y in 0 until 64) line.pixels.setPixel(30, y, 0f, 0f, 0f, 1f)
        val cov = Region.compute(doc, RefSource.Canvas, 5, 5, RegionParams())!!
        assertEquals(IntRect(0, 0, 30, 64), cov.bounds())
    }

    @Test
    fun bucket_sourceLayer() {
        val doc = Document.create(64, 64)
        val paint = doc.activeRaster!!
        val ref = doc.newRasterLayer("ref")
        doc.root.add(ref)
        for (y in 0 until 64) ref.pixels.setPixel(20, y, 0f, 0f, 0f, 1f)
        val cov = Region.compute(doc, RefSource.Layer(ref.id), 5, 5, RegionParams())!!
        BucketFill.apply(doc, paint.id, cov, Rgba(1f, 0f, 0f), 1f, false)
        assertEquals(1f, pixel(paint.pixels, 5, 5)[3])
        assertEquals(0f, pixel(paint.pixels, 30, 5)[3])
        assertTrue(ref.pixels.get(0, 0)!!.let { it[it.index(5, 5) + 3] } == 0f)
    }

    @Test
    fun lassoFill_area() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        val poly = FloatArray(512) { i ->
            val a = 2 * PI * (i / 2) / 256
            if (i % 2 == 0) (150 + 80 * cos(a)).toFloat() else (150 + 80 * sin(a)).toFloat()
        }
        LassoFill.apply(doc, l.id, poly, Rgba(0f, 0f, 1f), 1f, false)
        var sum = 0.0
        val p = FloatArray(4)
        for (y in 0 until 300) for (x in 0 until 300) { l.pixels.getPixel(x, y, p); sum += p[3] }
        val e = PI * 80 * 80
        assertTrue(abs(sum - e) / e < 0.01)
    }

    @Test
    fun undo_fill_singleCommand() {
        val doc = Document.create(600, 600)
        val l = doc.activeRaster!!
        val h = History()
        val patch = BucketFill.apply(doc, l.id, Region.compute(doc, RefSource.Current, 1, 1, RegionParams())!!, Rgba.BLACK, 1f, false)!!
        h.push(TilePatchCommand(patch, "Fill"))
        assertEquals(1, h.undoSize)
        assertEquals(1f, pixel(l.pixels, 500, 500)[3])
        h.undo(doc)
        assertTrue(l.pixels.keys().isEmpty())
    }

    @Test
    fun recolor_lastFill_sameHistoryEntry() {
        val doc = Document.create(64, 64)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 0, 0, 64, 64, 1f, 1f, 1f, 1f)
        val h = History()
        val cmd = BucketFill.command(doc, l.id, MaskMath.full(64, 64), Rgba(1f, 0f, 0f), 1f, false)!!
        h.push(cmd)
        cmd.recolor(doc, Rgba(0f, 0f, 1f))
        assertEquals(1, h.undoSize)
        assertEquals(1f, pixel(l.pixels, 3, 3)[2])
        assertEquals(0f, pixel(l.pixels, 3, 3)[0])
        h.undo(doc)
        assertEquals(1f, pixel(l.pixels, 3, 3)[0])
        h.redo(doc)
        assertEquals(1f, pixel(l.pixels, 3, 3)[2])
    }

    @Test
    fun region_seedOutside_null() {
        val doc = Document.create(64, 64)
        assertNull(Region.compute(doc, RefSource.Canvas, -1, 0, RegionParams()))
        assertTrue(MaskOps.expand(MaskGrid(64, 64), 3f, true).bounds() == null)
    }
}
