package app.painter.core.transform

import app.painter.core.doc.Document
import app.painter.core.geom.Vec2
import app.painter.core.history.History
import app.painter.core.history.gridHash
import app.painter.core.history.pixel
import app.painter.core.raster.IntRect
import app.painter.core.raster.TileGrid
import app.painter.core.select.PolygonRaster
import app.painter.core.select.RectShape
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransformTest {
    private fun noise(g: TileGrid, r: IntRect, seed: Int = 1) {
        val rnd = Random(seed)
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) {
            val a = rnd.nextFloat()
            g.setPixel(x, y, rnd.nextFloat() * a, rnd.nextFloat() * a, rnd.nextFloat() * a, a)
        }
    }

    private fun maskHash(doc: Document) = doc.selection.mask.entries().filter { !it.value.isEmpty() }
        .sortedBy { it.key.packed }.joinToString(";") { "${it.key}:${it.value.data.contentHashCode()}" }

    @Test
    fun affine_compose_inverse() {
        val a = Affine.trs(10f, -3f, 2f, 0.5f, 30f, 5f, 7f)
        val b = Affine.trs(-4f, 8f, 1.5f, 1.5f, -70f, 0f, 0f)
        val ab = a.then(b)
        val p = FloatArray(2); val q = FloatArray(2); val r = FloatArray(2)
        a.forward(3f, 4f, p); b.forward(p[0], p[1], q); ab.forward(3f, 4f, r)
        assertEquals(q[0], r[0], 1e-3f); assertEquals(q[1], r[1], 1e-3f)
        ab.inverse(r[0], r[1], p)
        assertEquals(3f, p[0], 1e-3f); assertEquals(4f, p[1], 1e-3f)
        assertNull(Affine(0f, 0f, 0f, 0f, 1f, 1f).inverted())
    }

    @Test
    fun trs_aroundPivot() {
        val m = Affine.trs(0f, 0f, 2f, 2f, 90f, 10f, 10f)
        val p = FloatArray(2)
        m.forward(10f, 10f, p)
        assertEquals(10f, p[0]); assertEquals(10f, p[1])
        m.forward(11f, 10f, p) // x axis → +y on screen, doubled
        assertEquals(10f, p[0]); assertEquals(12f, p[1])
    }

    @Test
    fun homography_identityQuad() {
        val q = floatArrayOf(0f, 0f, 100f, 0f, 100f, 50f, 0f, 50f)
        val h = assertNotNull(Projective.fromQuads(q, q))
        val p = FloatArray(2)
        h.forward(37f, 21f, p)
        assertEquals(37f, p[0], 1e-3f); assertEquals(21f, p[1], 1e-3f)
    }

    @Test
    fun homography_squareToTrapezoid_cornersExact() {
        val src = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val dst = floatArrayOf(20f, 0f, 80f, 0f, 100f, 100f, 0f, 100f)
        val h = assertNotNull(Projective.fromQuads(src, dst))
        val p = FloatArray(2)
        for (i in 0 until 4) {
            h.forward(src[i * 2], src[i * 2 + 1], p)
            assertEquals(dst[i * 2], p[0], 1e-3f); assertEquals(dst[i * 2 + 1], p[1], 1e-3f)
            h.inverse(dst[i * 2], dst[i * 2 + 1], p)
            assertEquals(src[i * 2], p[0], 1e-3f); assertEquals(src[i * 2 + 1], p[1], 1e-3f)
        }
    }

    @Test
    fun homography_degenerate_null() {
        val src = floatArrayOf(0f, 0f, 100f, 0f, 200f, 0f, 0f, 100f)
        val dst = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        assertNull(Projective.fromQuads(src, dst))
        assertNull(Projective.fromQuads(dst, src))
        assertTrue(!CageMath.isConvexQuad(floatArrayOf(0f, 0f, 100f, 0f, 20f, 20f, 0f, 100f)))
        assertTrue(CageMath.isConvexQuad(dst))
    }

    @Test
    fun warp_identityGrid_isIdentity() {
        val src = IntRect(10, 20, 110, 90)
        val pre = Projective.IDENTITY
        val w = BezierWarp(pre, BezierWarp.initialCtrl(pre, src), src, 16)
        val p = FloatArray(2)
        for ((x, y) in listOf(10.5f to 20.5f, 57.3f to 44.1f, 109.5f to 89.5f)) {
            assertTrue(w.inverse(x, y, p))
            assertEquals(x, p[0], 1e-3f); assertEquals(y, p[1], 1e-3f)
        }
        assertTrue(!w.inverse(5f, 5f, p))
    }

    @Test
    fun warp_cornersInterpolate() {
        val src = IntRect(0, 0, 90, 90)
        val pre = Projective.IDENTITY
        val ctrl = BezierWarp.initialCtrl(pre, src)
        ctrl[30] += 20f; ctrl[31] += 10f // bottom-right control point
        val w = BezierWarp(pre, ctrl, src, 32)
        val p = FloatArray(2)
        w.evalPatch(1f, 1f, p)
        assertEquals(110f, p[0], 1e-3f); assertEquals(100f, p[1], 1e-3f)
        w.evalPatch(0f, 0f, p)
        assertEquals(0f, p[0], 1e-4f)
        assertTrue(w.inverse(109.9f, 99.9f, p))
        assertEquals(90f, p[0], 0.5f); assertEquals(90f, p[1], 0.5f)
        val moved = BezierWarp.drag(BezierWarp.initialCtrl(pre, src), 0.3f, 0.6f, 5f, -2f)
        BezierWarp(pre, moved, src).evalPatch(0.3f, 0.6f, p)
        assertEquals(27f + 5f, p[0], 1e-3f); assertEquals(54f - 2f, p[1], 1e-3f)
    }

    @Test
    fun resample_nearest_integerShift_exact() = shiftExact(Resample.NEAREST)

    @Test
    fun resample_bicubic_integerShift_exact() = shiftExact(Resample.BICUBIC)

    private fun shiftExact(mode: Resample) {
        val doc = Document.create(600, 400)
        val l = doc.activeRaster!!
        noise(l.pixels, IntRect(100, 100, 300, 260))
        val want = TileGrid(600, 400)
        noise(want, IntRect(137, 81, 337, 241))
        val s = TransformSession(doc, listOf(l.id))
        s.commit(Affine.translation(37f, -19f), mode)
        assertEquals(gridHash(want.also { it.compact() }), gridHash(l.pixels.also { it.compact() }))
    }

    @Test
    fun resample_bicubic_noOvershoot() {
        val g = TileGrid(64, 64)
        for (y in 0 until 64) for (x in 0 until 64) if (x >= 32) g.setPixel(x, y, 1f, 1f, 1f, 1f)
        val out = FloatArray(4)
        for (i in 0 until 40) {
            Resampler.sample(g, null, 28f + i * 0.2f, 10.3f, Resample.BICUBIC, out)
            assertTrue(out[3] in 0f..1f && out[0] <= out[3] && out[0] >= 0f)
        }
        Resampler.sample(g, null, 32f, 10.5f, Resample.BILINEAR, out)
        assertEquals(0.5f, out[3], 1e-4f)
    }

    @Test
    fun downscale_usesMip_checkerboardGray() {
        for (scale in floatArrayOf(0.5f, 0.25f)) {
            val doc = Document.create(512, 512)
            val l = doc.activeRaster!!
            for (y in 0 until 256) for (x in 0 until 256) {
                val v = ((x + y) % 2).toFloat()
                l.pixels.setPixel(x, y, v, v, v, 1f)
            }
            TransformSession(doc, listOf(l.id)).commit(Affine.trs(0f, 0f, scale, scale, 0f, 0f, 0f), Resample.BICUBIC)
            val n = (256 * scale).toInt()
            for (y in 2 until n - 2) for (x in 2 until n - 2) {
                val p = pixel(l.pixels, x, y)
                assertEquals(0.5f, p[0], 1.5f / 255, "scale $scale at $x,$y")
                assertEquals(1f, p[3], 1.5f / 255)
            }
        }
        assertEquals(0, Resampler.levelFor(0.5f))
        assertEquals(1, Resampler.levelFor(0.25f))
        assertEquals(2, Resampler.levelFor(0.2f))
    }

    @Test
    fun rotate90x4_identity() {
        for (mode in Resample.entries) {
            val doc = Document.create(512, 512)
            val l = doc.activeRaster!!
            noise(l.pixels, IntRect(100, 150, 300, 260))
            val orig = gridHash(l.pixels.also { it.compact() })
            repeat(4) { TransformSession(doc, listOf(l.id)).commit(Affine.rotation(90f, 256f, 256f), mode) }
            assertEquals(orig, gridHash(l.pixels.also { it.compact() }), "mode $mode")
        }
    }

    @Test
    fun flipTwice_identity() {
        val doc = Document.create(512, 300)
        val l = doc.activeRaster!!
        noise(l.pixels, IntRect(10, 10, 300, 200))
        val orig = gridHash(l.pixels.also { it.compact() })
        repeat(2) { TransformSession(doc, listOf(l.id)).commit(Affine.flip(true, 155f, 0f), Resample.BICUBIC) }
        assertEquals(orig, gridHash(l.pixels.also { it.compact() }))
        val f = Affine.flip(false, 3f, 40f).then(Affine.flip(false, 3f, 40f))
        assertEquals(Affine.IDENTITY, f)
    }

    @Test
    fun distortIdentity_withinOneLevel() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        noise(l.pixels, IntRect(20, 20, 120, 120))
        val before = l.pixels.copy()
        val s = TransformSession(doc, listOf(l.id))
        val cage = FloatRect.of(s.cage!!)
        val h = assertNotNull(CageMath.distort(cage, CageMath.quad(Affine.IDENTITY, cage)))
        s.commit(h, Resample.BICUBIC)
        val a = FloatArray(4); val b = FloatArray(4)
        for (y in 0 until 140) for (x in 0 until 140) {
            before.getPixel(x, y, a); l.pixels.getPixel(x, y, b)
            for (c in 0 until 4) assertEquals(a[c], b[c], 1f / 255)
        }
    }

    @Test
    fun session_withSelection_restUntouched() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        noise(l.pixels, IntRect(0, 0, 100, 200))
        val sel = PolygonRaster.rasterize(RectShape(50f, 50f, 40f, 40f).toPolygon(), 300, 300, false)
        for ((k, t) in sel.entries()) doc.selection.mask.put(k, t)
        val before = l.pixels.copy()
        TransformSession(doc, listOf(l.id)).commit(Affine.translation(100f, 0f), Resample.NEAREST)
        val a = FloatArray(4); val b = FloatArray(4)
        for (y in 0 until 200) for (x in 0 until 200) {
            val inSrc = x in 30 until 70 && y in 30 until 70
            val inDst = x in 130 until 170 && y in 30 until 70
            l.pixels.getPixel(x, y, b)
            when {
                inSrc -> assertEquals(0f, b[3])
                inDst -> { before.getPixel(x - 100, y, a); assertTrue(a.contentEquals(b)) }
                else -> { before.getPixel(x, y, a); assertTrue(a.contentEquals(b), "$x,$y") }
            }
        }
        assertEquals(IntRect(130, 30, 170, 70), doc.selection.mask.bounds())
    }

    @Test
    fun session_multiLayer_singleCommand() {
        val doc = Document.create(400, 400)
        val l1 = doc.activeRaster!!
        val l2 = doc.newRasterLayer("2").also { doc.root.add(it) }
        val l3 = doc.newRasterLayer("3").also { doc.root.add(it) }
        for ((i, l) in listOf(l1, l2, l3).withIndex()) noise(l.pixels, IntRect(10 + i * 20, 10, 150, 150), i)
        val sel = PolygonRaster.rasterize(RectShape(80f, 80f, 100f, 100f, 15f).toPolygon(), 400, 400, true)
        for ((k, t) in sel.entries()) doc.selection.mask.put(k, t)
        val hashes = listOf(l1, l2, l3).map { gridHash(it.pixels) }
        val mh = maskHash(doc)
        val h = History()
        h.push(TransformSession(doc, listOf(l1.id, l2.id, l3.id)).commit(Affine.trs(5f, 9f, 1.3f, 0.8f, 33f, 80f, 80f), Resample.BICUBIC))
        assertEquals(1, h.undoSize)
        assertTrue(maskHash(doc) != mh)
        h.undo(doc)
        assertEquals(hashes, listOf(l1, l2, l3).map { gridHash(it.pixels) })
        assertEquals(mh, maskHash(doc))
    }

    @Test
    fun session_offCanvasClipped() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        noise(l.pixels, IntRect(0, 0, 100, 100))
        TransformSession(doc, listOf(l.id)).commit(Affine.translation(250f, 250f), Resample.NEAREST)
        assertTrue(pixel(l.pixels, 260, 260)[3] > 0f)
        for ((k, t) in l.pixels.entries()) {
            for (y in 0 until 256) for (x in 0 until 256) {
                val gx = (k.tx shl 8) + x; val gy = (k.ty shl 8) + y
                if (gx >= 300 || gy >= 300) assertEquals(0f, t[t.index(x, y) + 3])
            }
        }
        assertTrue(l.pixels.keys().all { l.pixels.isInBounds(it.tx, it.ty) })
    }

    @Test
    fun session_cancel_noHistory() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        noise(l.pixels, IntRect(0, 0, 100, 100))
        val sel = PolygonRaster.rasterize(RectShape(50f, 50f, 40f, 40f).toPolygon(), 300, 300, false)
        for ((k, t) in sel.entries()) doc.selection.mask.put(k, t)
        val orig = gridHash(l.pixels)
        val s = TransformSession(doc, listOf(l.id))
        assertEquals(0f, pixel(l.pixels, 50, 50)[3]) // rest shown during the session
        assertEquals(IntRect(30, 30, 70, 70), s.cage)
        s.cancel()
        assertEquals(orig, gridHash(l.pixels))
    }

    @Test
    fun session_maskTransformed() {
        val doc = Document.create(300, 300)
        val sel = PolygonRaster.rasterize(RectShape(50f, 50f, 40f, 40f).toPolygon(), 300, 300, false)
        for ((k, t) in sel.entries()) doc.selection.mask.put(k, t)
        val s = TransformSession(doc, emptyList(), TransformSession.Target.SELECTION_MASK)
        assertEquals(IntRect(30, 30, 70, 70), s.cage)
        val h = History()
        h.push(s.commit(Affine.translation(10f, 20f), Resample.NEAREST))
        assertEquals(IntRect(40, 50, 80, 90), doc.selection.mask.bounds())
        h.undo(doc)
        assertEquals(IntRect(30, 30, 70, 70), doc.selection.mask.bounds())
    }

    @Test
    fun stamp_createsLayerOnce() {
        val doc = Document.create(300, 300)
        val l = doc.activeRaster!!
        noise(l.pixels, IntRect(0, 0, 50, 50))
        val s = TransformSession(doc, listOf(l.id))
        val h = History()
        h.push(s.stamp(Affine.translation(100f, 0f), Resample.NEAREST))
        h.push(s.stamp(Affine.translation(200f, 0f), Resample.NEAREST))
        assertEquals(2, doc.layerCount())
        assertEquals(l.id, doc.activeId)
        val st = doc.root.children.last() as app.painter.core.doc.RasterLayer
        assertEquals(l.name + " stamps", st.name)
        assertTrue(pixel(st.pixels, 110, 10)[3] > 0f && pixel(st.pixels, 210, 10)[3] > 0f)
        h.push(s.commit(Affine.IDENTITY, Resample.NEAREST))
        assertTrue(pixel(l.pixels, 10, 10)[3] > 0f)
    }

    @Test
    fun snap_move_edges() {
        val d = Snap.move(FloatRect(3f, 50f, 103f, 150f), 400, 300, 8f)
        assertEquals(-3f, d.x); assertEquals(0f, d.y)
        val c = Snap.move(FloatRect(145f, 97f, 245f, 197f), 400, 300, 8f)
        assertEquals(5f, c.x); assertEquals(3f, c.y)
        assertEquals(Vec2(0f, 0f), Snap.move(FloatRect(50f, 50f, 60f, 60f), 400, 300, 8f))
    }

    @Test
    fun snap_angle_90() {
        assertEquals(90f, Snap.angle(92.5f))
        assertEquals(95f, Snap.angle(95f))
        assertEquals(-180f, Snap.angle(-178f))
        assertEquals(45f, Snap.angle(44f, 15f, 3f))
        assertEquals(1f, Snap.scale(1.015f))
    }

    @Test
    fun cage_scaleHandle_corner() {
        val cage = FloatRect(0f, 0f, 100f, 50f)
        val m = CageMath.scaleHandle(Affine.IDENTITY, cage, Handle.BOTTOM_RIGHT, Vec2(150f, 75f), uniform = true)
        val p = FloatArray(2)
        m.forward(100f, 50f, p)
        assertEquals(150f, p[0], 1e-3f); assertEquals(75f, p[1], 1e-3f)
        m.forward(50f, 25f, p)
        assertEquals(50f, p[0], 1e-3f)
        val s = CageMath.scaleHandle(Affine.IDENTITY, cage, Handle.RIGHT, Vec2(200f, 10f), uniform = true)
        s.forward(0f, 0f, p)
        assertEquals(0f, p[0], 1e-3f)
        s.forward(100f, 50f, p)
        assertTrue(abs(p[0] - 200f) < 1e-3f && abs(p[1] - 50f) < 1e-3f)
    }
}
