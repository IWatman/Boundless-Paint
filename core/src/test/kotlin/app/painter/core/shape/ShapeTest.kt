package app.painter.core.shape

import app.painter.core.geom.Vec2
import app.painter.core.stroke.FillSpec
import app.painter.core.stroke.Interpolation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapeTest {
    private fun near(a: Vec2, b: Vec2, eps: Float = 1e-2f) =
        assertTrue(abs(a.x - b.x) < eps && abs(a.y - b.y) < eps, "$a != $b")

    @Test
    fun ellipseTessellationWithinQuarterPixel() {
        for (e in listOf(Shape.Ellipse(Vec2(0f, 0f), 400f, 150f, 0.3f), Shape.Ellipse(Vec2(5f, 5f), 900f, 20f), Shape.Ellipse(Vec2(0f, 0f), 3f, 3f))) {
            val pts = e.outline()
            for (i in pts.indices) {
                val a = pts[i]; val b = pts[(i + 1) % pts.size]
                for (k in 1..9) {
                    val m = lerp(a, b, k / 10f)
                    val l = toLocal(e.center, e.rot, m)
                    // Radial distance to the exact ellipse along the parametric angle is an upper bound.
                    val t = kotlin.math.atan2(l.y / e.ry, l.x / e.rx)
                    val q = Vec2(e.rx * cos(t), e.ry * sin(t))
                    assertTrue(hypot(l.x - q.x, l.y - q.y) <= 0.26f, "error at $m")
                }
            }
        }
    }

    @Test
    fun shapeStrokeOptions() {
        val rect = Shape.Rect(Vec2(50f, 50f), 40f, 20f)
        val o = ShapeStroke.options(rect, FillSpec())
        assertFalse(o.stabilize); assertEquals(Interpolation.LINEAR, o.interpolation); assertTrue(o.closed)
        assertEquals(FillSpec(), o.fill)
        assertNull(ShapeStroke.options(Shape.Line(Vec2(0f, 0f), Vec2(1f, 1f)), FillSpec()).fill)
        assertTrue(ShapeStroke.points(rect).all { it.pressure == 1f })
        assertEquals(4, ShapeStroke.points(rect).size)
    }

    @Test
    fun rectCornerDragKeepsOppositeCorner() {
        val r = Shape.Rect(Vec2(100f, 100f), 80f, 40f, 0.4f)
        val corners = r.corners()
        for (i in 0..3) {
            val h = ShapeHandles.handles(r).first { it.kind == HandleKind.CORNER && it.index == i }
            val moved = ShapeHandles.drag(r, h, h.pos + Vec2(13f, -7f)) as Shape.Rect
            near(corners[(i + 2) % 4], moved.corners()[(i + 2) % 4])
        }
        val h = ShapeHandles.handles(r).first { it.kind == HandleKind.CORNER && it.index == 2 }
        val sq = ShapeHandles.drag(r, h, h.pos + Vec2(30f, 3f), constrain = true) as Shape.Rect
        assertEquals(sq.w, sq.h, 1e-3f)
        near(corners[0], sq.corners()[0])
    }

    @Test
    fun rectSideAndRotate() {
        val r = Shape.Rect(Vec2(100f, 100f), 80f, 40f)
        val side = ShapeHandles.handles(r).first { it.kind == HandleKind.SIDE && it.index == 1 }
        val s = ShapeHandles.drag(r, side, Vec2(170f, 130f)) as Shape.Rect
        assertEquals(110f, s.w, 1e-3f); assertEquals(40f, s.h, 1e-3f)
        near(Vec2(60f, 80f), s.corners()[0])
        val rot = ShapeHandles.handles(r).first { it.kind == HandleKind.ROTATE }
        val turned = ShapeHandles.drag(r, rot, Vec2(100f + 50f * cos(-PI / 2 + 0.3).toFloat(), 100f + 50f * sin(-PI / 2 + 0.3).toFloat()), constrain = true) as Shape.Rect
        assertEquals(Constrain.snapAngle(0.3f), turned.rot, 1e-4f)
        assertEquals(15f, turned.rot.toDeg(), 1e-3f)
    }

    @Test
    fun constrainLineAndCreate() {
        val l = ShapeFactory.fromDrag(ShapeTool.LINE, Vec2(0f, 0f), Vec2(100f, 22f), constrain = true) as Shape.Line
        assertEquals(15f, l.b.angle.toDeg(), 1e-3f)
        val sq = ShapeFactory.fromDrag(ShapeTool.RECT, Vec2(10f, 10f), Vec2(50f, 30f), constrain = true) as Shape.Rect
        assertEquals(40f, sq.w); assertEquals(40f, sq.h)
        val c = ShapeFactory.fromDrag(ShapeTool.ELLIPSE, Vec2(10f, 10f), Vec2(13f, 14f)) as Shape.Ellipse
        assertEquals(5f, c.rx, 1e-4f); assertEquals(5f, c.ry, 1e-4f)
    }

    @Test
    fun polygonN() {
        for (n in 3..16) {
            val p = Shape.Polygon(Vec2(0f, 0f), 100f, n, 0f)
            val pts = p.outline()
            assertEquals(n, pts.size)
            near(Vec2(100f, 0f), pts[0])
            val side = pts[0].dist(pts[1])
            for (i in pts.indices) assertEquals(side, pts[i].dist(pts[(i + 1) % n]), 1e-2f)
        }
        assertEquals(16, Shape.Polygon(Vec2(0f, 0f), 10f, 40).outline().size)
        val v = ShapeHandles.handles(Shape.Polygon(Vec2(0f, 0f), 10f, 5, 0f)).first { it.kind == HandleKind.VERTEX }
        val moved = ShapeHandles.drag(Shape.Polygon(Vec2(0f, 0f), 10f, 5, 0f), v, Vec2(0f, 20f)) as Shape.Polygon
        assertEquals(20f, moved.radius, 1e-4f)
        near(Vec2(0f, 20f), moved.outline()[0])
    }

    @Test
    fun arcThroughThreePoints() {
        val a = Shape.Arc(Vec2(-100f, 0f), Vec2(100f, 0f), Vec2(0f, -100f))
        val g = a.geometry()!!
        near(Vec2(0f, 0f), g.c); assertEquals(100f, g.r, 1e-3f)
        val pts = a.outline()
        near(a.start, pts.first()); near(a.end, pts.last())
        assertTrue(pts.all { abs(it.len - 100f) < 0.01f })
        assertTrue(pts.all { it.y <= 1e-3f }, "goes through the mid side")
        assertTrue(pts.any { abs(it.x) < 3f && abs(it.y + 100f) < 0.1f })
        val straight = Shape.Arc(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(5f, 0f))
        assertEquals(2, straight.outline().size)
        val arc = ShapeFactory.fromDrag(ShapeTool.ARC, Vec2(0f, 0f), Vec2(100f, 0f)) as Shape.Arc
        assertEquals(25f, abs(arc.mid.y), 1e-3f)
        val mh = ShapeHandles.handles(arc).first { it.kind == HandleKind.ARC_MID }
        val c = ShapeHandles.drag(arc, mh, Vec2(80f, 40f), constrain = true) as Shape.Arc
        near(Vec2(50f, 40f), c.mid)
    }

    @Test
    fun hitTestOrder() {
        val r = Shape.Rect(Vec2(100f, 100f), 200f, 100f)
        val hit = ShapeHandles.hitTest(r, Vec2(5f, 52f))
        assertTrue(hit is ShapeHit.OnHandle && hit.handle.kind == HandleKind.CORNER)
        assertEquals(ShapeHit.OnContour, ShapeHandles.hitTest(r, Vec2(60f, 60f)))
        assertNull(ShapeHandles.hitTest(r, Vec2(150f, 120f)), "inside, away from handles and contour")
    }

    @Test
    fun evenOddStarHasEmptyCentre() {
        val star = (0 until 5).map { polar(Vec2(50f, 50f), 45f, (-PI / 2 + it * 4 * PI / 5).toFloat()) }
        val out = FloatArray(100 * 100)
        PolygonFill.coverage(listOf(star), 0, 0, 100, 100, out)
        assertEquals(0f, out[50 * 100 + 50])
        assertEquals(1f, out[30 * 100 + 50], 1e-6f, "a point inside an arm")
    }

    @Test
    fun circleAreaWithinOnePercent() {
        val circle = Shape.Ellipse(Vec2(64.3f, 60.7f), 40f, 40f).outline()
        val out = FloatArray(128 * 128)
        PolygonFill.coverage(listOf(circle), 0, 0, 128, 128, out)
        val area = out.sum()
        val exact = PI * 40 * 40
        assertTrue(abs(area - exact) / exact < 0.01, "area $area vs $exact")
        assertTrue(out.any { it > 0f && it < 1f }, "anti-aliased edge")
        // Tile offsets agree with a full render.
        val part = FloatArray(32 * 32)
        PolygonFill.coverage(listOf(circle), 32, 32, 32, 32, part)
        for (y in 0 until 32) for (x in 0 until 32) assertEquals(out[(y + 32) * 128 + x + 32], part[y * 32 + x], 1e-6f)
    }

    @Test
    fun holdDetector() {
        val h = HoldDetector()
        h.begin(0f, 0f, 0)
        assertFalse(h.onPoint(2f, 2f, 300))
        assertFalse(h.poll(599))
        assertTrue(h.poll(600))
        assertFalse(h.poll(900), "fires once")

        val j = HoldDetector()
        j.begin(0f, 0f, 0)
        var t = 0L
        repeat(29) { t += 20; assertFalse(j.onPoint(if (it % 2 == 0) 3f else -3f, 2f, t)) }
        assertTrue(j.onPoint(0f, 0f, 610), "jitter < 6 px does not reset")

        val m = HoldDetector()
        m.begin(0f, 0f, 0)
        for (i in 1..100) assertFalse(m.onPoint(i * 7f, 0f, i * 16L), "slow continuous movement never fires")
    }

    @Test
    fun pathEditing() {
        var p = BezierPath(emptyList())
        p = PathEdit.append(p, Vec2(0f, 0f))
        p = PathEdit.append(p, Vec2(100f, 0f), dragTo = Vec2(130f, 0f))
        p = PathEdit.append(p, Vec2(100f, 100f))
        assertEquals(NodeType.ALIGNED, p.nodes[1].type)
        val seg = PathEdit.hitSegment(p, Vec2(50f, 2f), 16f)
        assertEquals(0, seg)
        p = PathEdit.insert(p, seg, Vec2(50f, 0f))
        assertEquals(4, p.nodes.size)
        p = PathEdit.toggle(p, 0)
        assertEquals(NodeType.SMOOTH, p.nodes[0].type)
        p = PathEdit.moveNode(p, 1, Vec2(5f, 3f))
        assertEquals(3, p.nodes.size, "dropped on a neighbour = deleted")
        val flat = p.flatten()
        near(Vec2(0f, 0f), flat.first()); near(Vec2(100f, 100f), flat.last())
    }
}
