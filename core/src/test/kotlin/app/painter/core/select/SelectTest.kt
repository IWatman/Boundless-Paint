package app.painter.core.select

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.history.History
import app.painter.core.history.fillRect
import app.painter.core.history.pixel
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectTest {
    private fun area(m: MaskGrid): Double {
        var s = 0.0
        for ((_, t) in m.entries()) for (b in t.data) s += (b.toInt() and 0xFF) / 255.0
        return s
    }

    private fun maskHash(m: MaskGrid): String = m.entries().filter { !it.value.isEmpty() }.sortedBy { it.key.packed }
        .joinToString(";") { "${it.key}:${it.value.data.contentHashCode()}" }

    private fun rectMask(w: Int, h: Int, r: IntRect): MaskGrid =
        PolygonRaster.rasterize(RectShape((r.x0 + r.x1) / 2f, (r.y0 + r.y1) / 2f, r.width.toFloat(), r.height.toFloat()).toPolygon(), w, h, false)

    @Test
    fun raster_rect_exactArea() {
        val m = PolygonRaster.rasterize(floatArrayOf(10f, 10f, 50f, 10f, 50f, 50f, 10f, 50f), 300, 300, true)
        assertEquals(1600.0, area(m), 1e-6)
        assertEquals(IntRect(10, 10, 50, 50), m.bounds())
        val n = PolygonRaster.rasterize(floatArrayOf(250f, 250f, 270f, 250f, 270f, 262f, 250f, 262f), 600, 600, false)
        assertEquals(240.0, area(n), 1e-6)
    }

    @Test
    fun raster_circle_areaWithin1pct() {
        val m = PolygonRaster.rasterize(EllipseShape(300f, 300f, 100f, 100f).toPolygon(), 600, 600, true)
        val e = PI * 100 * 100
        assertTrue(abs(area(m) - e) / e < 0.01, "area ${area(m)}")
    }

    @Test
    fun raster_selfIntersecting_nonZero() {
        // Outer square drawn twice in the same direction: winding 2 inside, still selected.
        val sq = floatArrayOf(10f, 10f, 30f, 10f, 30f, 30f, 10f, 30f)
        val m = PolygonRaster.rasterize(sq + sq, 64, 64, false)
        assertEquals(255, m.byteAt(20, 20))
        // Figure-eight: both lobes are selected under non-zero.
        val eight = floatArrayOf(0f, 0f, 20f, 20f, 20f, 0f, 0f, 20f)
        val e = PolygonRaster.rasterize(eight, 64, 64, false)
        assertEquals(255, e.byteAt(3, 10))
        assertEquals(255, e.byteAt(17, 10))
        assertEquals(0, e.byteAt(10, 3))
    }

    @Test
    fun raster_aaOff_binary() {
        val soft = PolygonRaster.rasterize(EllipseShape(40f, 40f, 20.3f, 13.7f, 17f).toPolygon(), 100, 100, true)
        val hard = PolygonRaster.rasterize(EllipseShape(40f, 40f, 20.3f, 13.7f, 17f).toPolygon(), 100, 100, false)
        val sv = soft.get(TileKey(0, 0))!!.data.map { it.toInt() and 0xFF }.toSet()
        val hv = hard.get(TileKey(0, 0))!!.data.map { it.toInt() and 0xFF }.toSet()
        assertTrue(sv.size > 2)
        assertEquals(setOf(0, 255), hv)
    }

    @Test
    fun combine_add_max() {
        val a = rectMask(300, 300, IntRect(0, 0, 100, 100))
        val b = rectMask(300, 300, IntRect(50, 0, 150, 100))
        b.getOrCreate(0, 0).data[(5 shl Tile.SHIFT) + 5] = 100
        MaskOps.combine(a, b, SelectMode.ADD)
        assertEquals(255, a.byteAt(5, 5))
        assertEquals(255, a.byteAt(120, 50))
        assertEquals(0, a.byteAt(200, 50))
    }

    @Test
    fun combine_subtract() {
        val a = rectMask(300, 300, IntRect(0, 0, 100, 100))
        MaskOps.combine(a, rectMask(300, 300, IntRect(50, 0, 150, 100)), SelectMode.SUBTRACT)
        assertEquals(255, a.byteAt(10, 10))
        assertEquals(0, a.byteAt(60, 10))
        assertEquals(5000.0, area(a), 1e-6)
    }

    @Test
    fun combine_intersect() {
        val a = rectMask(600, 300, IntRect(0, 0, 100, 100))
        a.getOrCreate(2, 0).fill(-1)
        MaskOps.combine(a, rectMask(600, 300, IntRect(50, 0, 150, 100)), SelectMode.INTERSECT)
        assertEquals(5000.0, area(a), 1e-6)
    }

    @Test
    fun combine_newReplaces() {
        val a = rectMask(300, 300, IntRect(0, 0, 100, 100))
        val b = rectMask(300, 300, IntRect(200, 200, 210, 210))
        MaskOps.combine(a, b, SelectMode.NEW)
        assertEquals(maskHash(b), maskHash(a))
    }

    @Test
    fun invert_twice_identity() {
        val m = PolygonRaster.rasterize(EllipseShape(200f, 150f, 90f, 60f).toPolygon(), 500, 300, true)
        val orig = maskHash(m)
        MaskOps.invert(m)
        assertEquals(0, m.byteAt(200, 150))
        assertEquals(255, m.byteAt(5, 5))
        MaskOps.invert(m)
        assertEquals(orig, maskHash(m))
    }

    @Test
    fun selectAll_coversCanvasOnly() {
        val m = MaskGrid(300, 270)
        MaskOps.selectAll(m)
        assertEquals(300.0 * 270, area(m), 1e-6)
        assertEquals(IntRect(0, 0, 300, 270), m.bounds())
    }

    @Test
    fun expand_square_by10() {
        val m = rectMask(200, 200, IntRect(50, 50, 100, 100))
        val e = MaskOps.expand(m, 10f, aa = false)
        assertEquals(IntRect(40, 40, 110, 110), e.bounds())
        assertEquals(255, e.byteAt(40, 75))
        assertEquals(0, e.byteAt(41, 41)) // rounded corner
        val c = MaskOps.expand(m, -10f, aa = false)
        assertEquals(IntRect(60, 60, 90, 90), c.bounds())
    }

    @Test
    fun contract_toEmpty() {
        val m = rectMask(200, 200, IntRect(50, 50, 60, 60))
        assertNull(MaskOps.expand(m, -6f, true).bounds())
    }

    @Test
    fun feather_preservesMassApprox() {
        val m = rectMask(400, 400, IntRect(150, 150, 250, 250))
        val f = MaskOps.feather(m, 20f)
        assertTrue(abs(area(f) - 10000) / 10000 < 0.01, "mass ${area(f)}")
        val mid = f.byteAt(150, 200)
        assertTrue(mid in 100..155, "edge $mid")
        assertEquals(255, f.byteAt(200, 200))
        val all = MaskGrid(300, 300).also(MaskOps::selectAll)
        assertEquals(255, MaskOps.feather(all, 10f).byteAt(0, 0))
    }

    @Test
    fun fromAlpha_matchesLayerAlpha() {
        val g = TileGrid(300, 300)
        fillRect(g, 10, 10, 20, 20, 1f, 0f, 0f, 0.5f)
        fillRect(g, 280, 280, 300, 300, 0f, 1f, 0f, 1f)
        val m = MaskOps.fromAlpha(g)
        assertEquals(128, m.byteAt(15, 15))
        assertEquals(255, m.byteAt(290, 290))
        assertEquals(0, m.byteAt(5, 5))
    }

    @Test
    fun maskStroke_add_then_erase() {
        val doc = Document.create(300, 300)
        val h = History()
        val buf = TileGrid(300, 300, precise = true)
        fillRect(buf, 10, 10, 30, 30, 1f, 1f, 1f, 0.5f)
        h.execute(doc, SelectionEdits.maskStroke(doc, buf, erase = false)!!)
        assertEquals(128, doc.selection.mask.byteAt(20, 20))
        h.execute(doc, SelectionEdits.maskStroke(doc, buf, erase = false)!!)
        assertTrue(doc.selection.mask.byteAt(20, 20) in 191..192)
        val full = TileGrid(300, 300, precise = true)
        fillRect(full, 15, 15, 25, 25, 1f, 1f, 1f, 1f)
        h.execute(doc, SelectionEdits.maskStroke(doc, full, erase = true)!!)
        assertEquals(0, doc.selection.mask.byteAt(20, 20))
        assertTrue(doc.selection.mask.byteAt(12, 12) in 191..192)
        h.undo(doc)
        assertTrue(doc.selection.mask.byteAt(20, 20) in 191..192)
    }

    @Test
    fun delete_onlySelected() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 0, 0, 300, 300, 1f, 0f, 0f, 1f)
        val h = History()
        h.execute(doc, SelectionEdits.shape(doc, RectShape(50f, 50f, 20f, 20f), SelectMode.NEW, false)!!)
        h.execute(doc, SelectionEdits.delete(doc)!!)
        assertEquals(0f, pixel(l.pixels, 50, 50)[3])
        assertEquals(1f, pixel(l.pixels, 30, 50)[3])
        h.undo(doc)
        assertEquals(1f, pixel(l.pixels, 50, 50)[3])
    }

    @Test
    fun fill_selection_and_inactive() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        val h = History()
        h.execute(doc, SelectionEdits.shape(doc, RectShape(50f, 50f, 20f, 20f), SelectMode.NEW, false)!!)
        h.execute(doc, SelectionEdits.fill(doc, Rgba(0f, 1f, 0f))!!)
        assertEquals(1f, pixel(l.pixels, 50, 50)[1])
        assertEquals(0f, pixel(l.pixels, 10, 10)[3])
        h.execute(doc, SelectionEdits.setActive(doc, false)!!)
        h.execute(doc, SelectionEdits.fill(doc, Rgba(0f, 0f, 1f))!!)
        assertEquals(1f, pixel(l.pixels, 10, 10)[2])
    }

    @Test
    fun surroundingFillAndEraseKeepTheSelectedIsland() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        val h = History()
        h.execute(doc, SelectionEdits.shape(doc, RectShape(150f, 150f, 30f, 30f), SelectMode.NEW, false)!!)
        h.execute(doc, SelectionEdits.fillOutside(doc, Rgba(0f, 1f, 0f))!!)
        assertEquals(0f, pixel(l.pixels, 150, 150)[3], "inside the lasso is untouched")
        assertEquals(1f, pixel(l.pixels, 20, 20)[1], "outside is filled")
        h.execute(doc, SelectionEdits.eraseOutside(doc)!!)
        assertEquals(0f, pixel(l.pixels, 20, 20)[3], "surrounding erase clears outside")
        assertEquals(0f, pixel(l.pixels, 150, 150)[3], "the selected island is still untouched")
    }

    @Test
    fun copyPaste_roundTrip_pixelsEqual() {
        val doc = Document.create(600, 400)
        val l = doc.activeRaster!!
        for (y in 100 until 200) for (x in 200 until 330) l.pixels.setPixel(x, y, x / 600f * 0.5f, y / 400f * 0.5f, 0.2f, 0.5f)
        val h = History()
        h.execute(doc, SelectionEdits.shape(doc, EllipseShape(260f, 150f, 50f, 40f), SelectMode.NEW, true)!!)
        val f = SelectionEdits.copy(doc, merged = false)!!
        h.execute(doc, SelectionEdits.pasteAsLayer(doc, f, f.bounds))
        val pasted = doc.activeRaster!!
        assertTrue(pasted.id != l.id)
        assertEquals(2, doc.layerCount())
        val m = doc.selection.mask
        val a = FloatArray(4); val b = FloatArray(4)
        for (y in 100 until 200) for (x in 200 until 330) {
            l.pixels.getPixel(x, y, a); pasted.pixels.getPixel(x, y, b)
            val mv = m.value(x, y)
            for (c in 0 until 4) assertEquals(a[c] * mv, b[c], 2e-3f)
        }
        h.execute(doc, SelectionEdits.pasteAsLayer(doc, f, IntRect(0, 0, 1, 1)))
        assertEquals(0f, pixel(doc.activeRaster!!.pixels, 0, 0)[3])
        assertTrue(pixel(doc.activeRaster!!.pixels, 60, 50)[3] > 0f)
    }

    @Test
    fun copyMerged_excludesPaper() {
        val doc = Document.create(300, 300)
        fillRect(doc.activeRaster!!.pixels, 10, 10, 20, 20, 1f, 0f, 0f, 1f)
        val f = SelectionEdits.copy(doc, merged = true)!!
        assertEquals(IntRect(10, 10, 20, 20), f.bounds)
        assertEquals(0f, pixel(f.pixels, 5, 5)[3])
    }

    @Test
    fun cut_and_isolate() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 0, 0, 100, 100, 1f, 0f, 0f, 1f)
        val h = History()
        h.execute(doc, SelectionEdits.shape(doc, RectShape(25f, 25f, 50f, 50f), SelectMode.NEW, false)!!)
        h.execute(doc, SelectionEdits.isolate(doc)!!)
        assertEquals(0f, pixel(l.pixels, 10, 10)[3])
        assertEquals(1f, pixel(l.pixels, 80, 80)[3])
        val iso = doc.activeRaster!!
        assertEquals(1f, pixel(iso.pixels, 10, 10)[3])
        assertEquals(0f, pixel(iso.pixels, 80, 80)[3])
        h.undo(doc)
        assertEquals(1, doc.layerCount())
        assertEquals(1f, pixel(l.pixels, 10, 10)[3])
    }

    @Test
    fun undo_maskPatch_restoresExact() {
        val doc = Document.create(500, 500)
        val h = History()
        h.execute(doc, SelectionEdits.shape(doc, EllipseShape(200f, 200f, 120f, 80f, 30f), SelectMode.NEW, true)!!)
        val first = maskHash(doc.selection.mask)
        h.execute(doc, SelectionEdits.shape(doc, RectShape(300f, 300f, 200f, 100f, 10f), SelectMode.ADD, true)!!)
        h.execute(doc, SelectionEdits.feather(doc, 12f)!!)
        h.execute(doc, SelectionEdits.invert(doc)!!)
        h.undo(doc); h.undo(doc); h.undo(doc)
        assertEquals(first, maskHash(doc.selection.mask))
        h.undo(doc)
        assertTrue(doc.selection.isEmpty)
        h.redo(doc)
        assertEquals(first, maskHash(doc.selection.mask))
    }

    @Test
    fun wand_selectsRegion_andFromLayerAlpha() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        fillRect(l.pixels, 0, 0, 100, 300, 1f, 0f, 0f, 1f)
        val h = History()
        val cmd = SelectionEdits.wand(doc, 10, 10, app.painter.core.fill.RegionParams(), app.painter.core.fill.RefSource.Current, SelectMode.ADD)
        h.execute(doc, assertNotNull(cmd))
        assertEquals(IntRect(0, 0, 100, 300), doc.selection.mask.bounds())
        h.execute(doc, SelectionEdits.deselect(doc)!!)
        assertTrue(doc.selection.isEmpty)
        h.execute(doc, SelectionEdits.fromLayerAlpha(doc, l.id, SelectMode.NEW)!!)
        assertEquals(IntRect(0, 0, 100, 300), doc.selection.mask.bounds())
        h.execute(doc, SelectionEdits.selectAll(doc)!!)
        assertEquals(IntRect(0, 0, 300, 300), doc.selection.mask.bounds())
    }

    @Test
    fun contour_squareMask_4edges() {
        val t = MaskTile()
        for (y in 10 until 20) for (x in 10 until 20) t.data[(y shl Tile.SHIFT) + x] = -1
        val s = MaskOps.contour(t, TileKey(1, 0))
        assertTrue(s.isNotEmpty())
        var len = 0.0
        for (i in s.indices step 4) {
            for (k in 0..1) {
                val x = s[i + k * 2] - 256; val y = s[i + k * 2 + 1]
                val onEdge = abs(x - 10f) < 0.51f || abs(x - 20f) < 0.51f || abs(y - 10f) < 0.51f || abs(y - 20f) < 0.51f
                assertTrue(onEdge, "point $x,$y")
            }
            len += Math.hypot((s[i + 2] - s[i]).toDouble(), (s[i + 3] - s[i + 1]).toDouble())
        }
        assertTrue(abs(len - 40) < 2, "length $len")
        val g = MaskGrid(600, 300)
        g.put(TileKey(0, 0), t)
        val sg = MaskOps.contour(g, TileKey(0, 0))
        assertEquals(s.size, sg.size)
    }
}
