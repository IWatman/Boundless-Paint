package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.LayerMask
import app.painter.core.geom.Affine2
import app.painter.core.geom.Vec2
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskPatch
import app.painter.core.raster.TileKey
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.circle
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.gray
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrokeEngineM2Test {
    private val hard = BrushParams(size = 20f, hardness = 1f, smoothing = 0f)

    @Test
    fun cloneIsNotYetSupported() {
        val doc = Document.create(64, 64)
        for (m in listOf(StrokeMode.CLONE)) {
            assertFailsWith<UnsupportedOperationException> { engine(doc, hard, mode = m) }
        }
    }

    @Test
    fun selectionTargetPaintsAndErases() {
        val doc = Document.create(300, 200)
        val e = engine(doc, hard, target = StrokeTarget.Selection, color = Rgba(0f, 0f, 0f))
        drawOpen(e, line(20f, 100f, 280f, 100f, 4f))
        val c = assertIs<StrokeCommit.Mask>(e.end())
        assertEquals(MaskPatch.SELECTION, c.patch.targetId)
        val sel = doc.selection.mask
        assertEquals(255, sel.byteAt(150, 100), "selection ignores colour")
        assertEquals(0, sel.byteAt(150, 150))
        assertTrue(doc.activeRaster!!.pixels.keys().isEmpty(), "layer untouched")
        val er = engine(doc, hard.copy(opacity = 0.5f), target = StrokeTarget.Selection, mode = StrokeMode.ERASE)
        drawOpen(er, line(20f, 100f, 280f, 100f, 4f))
        assertNotNull(er.end())
        assertTrue(abs(sel.byteAt(150, 100) - 128) <= 1)
    }

    @Test
    fun maskTargetUsesLuminance() {
        val doc = Document.create(300, 200)
        val layer = doc.activeRaster!!
        layer.mask = LayerMask(MaskGrid(300, 200, -1))
        val e = engine(doc, hard, target = StrokeTarget.Mask(layer.id), color = Rgba(0f, 0f, 0f))
        drawOpen(e, line(20f, 100f, 280f, 100f, 4f))
        val c = assertIs<StrokeCommit.Mask>(e.end())
        assertEquals(layer.id, c.patch.targetId)
        val m = layer.mask!!.tiles
        assertEquals(0, m.byteAt(150, 100), "black hides")
        assertEquals(255, m.byteAt(150, 150))
        val gray = engine(doc, hard, target = StrokeTarget.Mask(layer.id), color = Rgba(0.5f, 0.5f, 0.5f))
        drawOpen(gray, line(20f, 100f, 280f, 100f, 4f))
        gray.end()
        assertTrue(abs(m.byteAt(150, 100) - 128) <= 1)
        // Before snapshot restores.
        for ((k, t) in c.patch.before) m.put(k, t?.copy())
        assertEquals(255, m.byteAt(150, 100))
    }

    @Test
    fun clipLimitsTheStroke() {
        for (buildup in listOf(false, true)) {
            val doc = Document.create(512, 256)
            val clip = MaskGrid(512, 256)
            for (y in 0 until 256) for (x in 0 until 200) clip.getOrCreate(x shr 8, y shr 8).set(x and 255, y and 255, if (x < 100) 1f else 0.5f)
            val e = engine(doc, hard.copy(buildup = buildup), options = StrokeOptions(clip = clip, stabilize = false))
            drawOpen(e, line(20f, 128f, 490f, 128f, 4f))
            e.endPatch()
            val px = doc.activeRaster!!.pixels
            assertEquals(1f, alpha(px, 50, 128), 1e-3f)
            // Buffered: the selection value is a ceiling; direct: it multiplies each dab, so dabs build up.
            if (buildup) assertTrue(alpha(px, 150, 128) > 0.49f) else assertEquals(0.5f, alpha(px, 150, 128), 0.01f)
            assertEquals(0f, alpha(px, 300, 128))
            assertNull(px.get(TileKey(1, 0)), "no tile outside the selection")
        }
    }

    @Test
    fun mirrorCopiesAreSymmetric() {
        val doc = Document.create(400, 300)
        val mirror = Affine2.reflection(Vec2(200f, 0f), (PI / 2).toFloat())
        val head = HeadImage(gray(32, 32) { x, _ -> if (x < 12) 1f else 0.2f })
        val params = hard.copy(size = 30f, head = HeadParams(source = HeadSource.BITMAP, rotationMode = RotationMode.TANGENT))
        val e = engine(doc, params, BrushResources(head = head), options = StrokeOptions(copies = SymmetryCopies(listOf(mirror))))
        drawOpen(e, line(40f, 60f, 160f, 240f, 4f))
        e.endPatch()
        val px = doc.activeRaster!!.pixels
        var maxErr = 0f
        var n = 0
        for (y in 0 until 300) for (x in 0 until 200) {
            val a = alpha(px, x, y)
            if (a > 0f) n++
            maxErr = maxOf(maxErr, abs(a - alpha(px, 399 - x, y)))
        }
        assertTrue(n > 500)
        assertTrue(maxErr < 0.01f, "mirror error $maxErr")
    }

    @Test
    fun domainDropsDabsInAllCopies() {
        val doc = Document.create(400, 200)
        val mirror = Affine2.reflection(Vec2(200f, 0f), (PI / 2).toFloat())
        val e = engine(doc, hard, options = StrokeOptions(copies = SymmetryCopies(listOf(mirror)) { x, _ -> x < 200f }))
        drawOpen(e, line(20f, 100f, 380f, 100f, 4f))
        e.endPatch()
        val px = doc.activeRaster!!.pixels
        assertTrue(alpha(px, 100, 100) > 0.9f)
        assertTrue(alpha(px, 300, 100) > 0.9f, "mirror of the left half")
        assertTrue(alpha(px, 195, 100) > 0f)
    }

    /** Lifts the pen whenever x crosses 200. */
    private class SplitStage : PointStage {
        private var left = true
        override fun begin(p: InputPoint, out: PointSink) { left = p.x < 200f; out.point(p) }
        override fun add(p: InputPoint, out: PointSink) {
            val l = p.x < 200f
            if (l != left) { out.lift(); left = l }
            out.point(p)
        }
        override fun end(out: PointSink) {}
    }

    @Test
    fun liftStartsSubStrokesWithOwnTaper() {
        val doc = Document.create(400, 200)
        val e = engine(doc, hard.copy(taperStart = 30f, taperEnd = 30f), options = StrokeOptions(stages = listOf(SplitStage())))
        drawOpen(e, line(20f, 100f, 380f, 100f, 4f))
        e.endPatch()
        val px = doc.activeRaster!!.pixels
        fun width(x: Int) = (0 until 200).count { alpha(px, x, it) > 0.5f }
        assertTrue(width(100) >= 18)
        assertTrue(width(194) < 10, "end taper of the first sub-stroke ${width(194)}")
        assertTrue(width(203) < 10, "start taper of the second ${width(203)}")
        assertTrue(width(300) >= 18)
    }

    @Test
    fun closedFillIsEvenOdd() {
        val doc = Document.create(300, 300)
        val sq = listOf(InputPoint(50f, 50f), InputPoint(250f, 50f), InputPoint(250f, 250f), InputPoint(50f, 250f))
        val e = engine(doc, hard.copy(size = 4f), options = StrokeOptions(stabilize = false, interpolation = Interpolation.LINEAR,
            closed = true, fill = FillSpec(0.5f)))
        drawOpen(e, sq)
        e.endPatch()
        val px = doc.activeRaster!!.pixels
        assertEquals(0.5f, alpha(px, 150, 150), 0.01f)
        assertEquals(1f, alpha(px, 150, 50), 0.01f)
        assertEquals(0f, alpha(px, 20, 20))
    }

    @Test
    fun afterSmoothingReRendersStraighterPath() {
        val zig = (0..60).map { i -> InputPoint(20f + i * 6f, 100f + if (i % 2 == 0) 8f else -8f, timeMs = i * 4L) }
        fun spread(after: Float): Int {
            val doc = Document.create(420, 200)
            val e = engine(doc, hard.copy(size = 4f, afterSmoothing = after))
            drawOpen(e, zig)
            e.endPatch()
            val px = doc.activeRaster!!.pixels
            return (0 until 200).count { alpha(px, 200, it) > 0.1f }
        }
        assertTrue(spread(40f) < spread(0f), "${spread(40f)} vs ${spread(0f)}")
    }

    @Test
    fun tangentRotationFollowsCircle() {
        val brush = Brush.of(BrushParams(head = HeadParams(rotationMode = RotationMode.TANGENT)))
        val gen = DabGenerator(brush, BrushResources.EMPTY, StrokeContext(), Rgba.BLACK)
        val batch = DabBatch()
        val angles = ArrayList<Triple<Float, Float, Float>>()
        val w = PathWalker(brush.params, PathSink { s ->
            batch.clear(); val st = gen.emit(s, batch); angles.add(Triple(s.x, s.y, batch[0].angle)); st
        }, deferForDirection = true)
        val pts = circle(300f, 300f, 200f, 720)
        w.begin(pts[0]); pts.drop(1).forEach(w::add); w.end()
        for ((x, y, a) in angles.subList(10, angles.size - 10)) {
            val tangent = atan2(x - 300f, -(y - 300f)) // CCW tangent in y-down coords
            var d = (a - tangent) % (2 * PI).toFloat()
            if (d > PI) d -= (2 * PI).toFloat()
            if (d < -PI) d += (2 * PI).toFloat()
            assertTrue(abs(d) < (3 * PI / 180).toFloat() || abs(abs(d) - PI.toFloat()) < (3 * PI / 180).toFloat(), "angle error ${d * 180 / PI} deg")
        }
    }

    @Test
    fun lazyRopeLagsByRadius() {
        val doc = Document.create(400, 200)
        val e = engine(doc, hard.copy(lazyRadius = 60f))
        drawOpen(e, line(20f, 100f, 300f, 100f, 2f))
        e.endPatch()
        val px = doc.activeRaster!!.pixels
        assertTrue(alpha(px, 230, 100) > 0.9f)
        assertEquals(0f, alpha(px, 260, 100), "tip stays 60 px behind and does not catch up")
    }

    @Test
    fun directModeCancelRestoresLayer() {
        val doc = Document.create(300, 300)
        val px = doc.activeRaster!!.pixels
        px.setPixel(5, 5, 0.5f, 0.5f, 0.5f, 0.5f)
        val before = px.get(TileKey(0, 0))!!
        val e = engine(doc, hard.copy(buildup = true, opacity = 0.5f))
        drawOpen(e, line(0f, 100f, 290f, 100f, 5f))
        assertTrue(px.get(TileKey(0, 0))!!.precise, "live float working copy")
        assertTrue(alpha(px, 100, 100) > 0.4f)
        e.cancel()
        assertTrue(px.get(TileKey(0, 0)) === before)
        assertEquals(0f, alpha(px, 100, 100))
    }
}
