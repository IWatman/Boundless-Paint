package app.painter.core.guide

import app.painter.core.geom.Vec2
import app.painter.core.shape.NodeType
import app.painter.core.shape.PathNode
import app.painter.core.stroke.InputPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuidesTest {
    private fun tracks(g: SnapGuide, p: Vec2, ctx: SnapContext = SnapContext(1f, 20f)) =
        ArrayList<SnapTrack>().also { g.tracks(p, ctx, it) }

    private val proj = Projection()

    @Test
    fun linesMagnetInsideAndOutside() {
        val g = LinesGuide(LinesConfig(on = true, a = Vec2(0f, 100f), b = Vec2(200f, 100f), magnet = true))
        val inside = tracks(g, Vec2(50f, 120f))
        assertEquals(1, inside.size)
        inside[0].project(70f, 130f, Float.NaN, proj)
        assertEquals(100f, proj.y, 1e-4f)
        assertTrue(tracks(g, Vec2(50f, 140f)).isEmpty(), "40 px away is free")
        assertEquals(1, tracks(g, Vec2(50f, 120f), SnapContext(2f, 0f)).size)
    }

    @Test
    fun linesEdgeOffsetsHalfBrushTowardStart() {
        val cfg = LinesConfig(on = true, a = Vec2(0f, 100f), b = Vec2(200f, 100f), magnet = true, edge = true)
        val g = LinesGuide(cfg)
        tracks(g, Vec2(50f, 110f))[0].project(80f, 0f, Float.NaN, proj)
        val above = proj.y
        tracks(g, Vec2(50f, 90f))[0].project(80f, 0f, Float.NaN, proj)
        val below = proj.y
        assertEquals(20f, abs(above - below), 1e-4f)
        assertEquals(10f, abs(above - 100f), 1e-4f)
        assertTrue((above - 100f) * (below - 100f) < 0f, "sides differ")
    }

    @Test
    fun linesPerpendicularToggle() {
        val cfg = LinesConfig(on = true, a = Vec2(0f, 0f), b = Vec2(100f, 100f))
        assertEquals(2, tracks(LinesGuide(cfg), Vec2(5f, 9f)).size)
        assertEquals(1, tracks(LinesGuide(cfg.copy(perpendicular = false)), Vec2(5f, 9f)).size)
    }

    @Test
    fun ellipseConcentricKeepsUnitRadius() {
        val cfg = EllipseConfig(on = true, c = Vec2(300f, 200f), rx = 120f, ratio = 0.4f, angleDeg = 30f)
        val g = EllipseGuide(cfg)
        val p0 = Vec2(400f, 260f)
        val r0 = g.unitRadius(p0)
        val conc = tracks(g, p0).first { it is EllipseTrack }
        var s = Float.NaN
        for (i in 0 until 200) {
            val a = i * 0.05
            conc.project((300 + 250 * cos(a)).toFloat(), (200 + 90 * sin(a)).toFloat(), s, proj)
            s = proj.s
            assertEquals(r0, g.unitRadius(Vec2(proj.x, proj.y)), 1e-2f)
        }
        assertTrue(s > 9f * r0 * 0.9f, "s is continuous past a full turn: $s")
    }

    @Test
    fun ellipseRadialPassesThroughCentre() {
        val cfg = EllipseConfig(on = true, c = Vec2(300f, 200f), rx = 120f, ratio = 0.5f)
        val radial = tracks(EllipseGuide(cfg), Vec2(380f, 260f)).first { it is LineTrack } as LineTrack
        radial.project(300f, 200f, Float.NaN, proj)
        assertEquals(300f, proj.x, 0.5f); assertEquals(200f, proj.y, 0.5f)
        // A stroke heading to the centre locks the radial track.
        val pts = (0..30).map { InputPoint(380f - it * 4f, 260f - it * 3f + (it % 3) * 0.5f) }
        val out = runStage(SnapStage(listOf(EllipseGuide(cfg)), SnapContext()), pts).all
        for (p in out) {
            val cross = (p.x - 300f) * 60f - (p.y - 200f) * 80f
            assertTrue(abs(cross) / 100f < 0.5f)
        }
    }

    @Test
    fun ratioOneIsCircle() {
        val g = EllipseGuide(EllipseConfig(on = true, c = Vec2(0f, 0f), rx = 50f, ratio = 1f, angleDeg = 17f))
        val t = tracks(g, Vec2(30f, 40f)).first { it is EllipseTrack }
        for (i in 0 until 36) {
            val a = i * 10.0 * PI / 180
            t.project((cos(a) * 77).toFloat(), (sin(a) * 77).toFloat(), Float.NaN, proj)
            assertEquals(50f, sqrt(proj.x * proj.x + proj.y * proj.y), 1e-3f)
        }
    }

    @Test
    fun ellipseMagnetSnapsToContour() {
        val g = EllipseGuide(EllipseConfig(on = true, c = Vec2(0f, 0f), rx = 100f, ratio = 1f, magnet = true))
        assertEquals(1, tracks(g, Vec2(110f, 0f)).size)
        assertTrue(tracks(g, Vec2(150f, 0f)).isEmpty())
    }

    // --- Pen ---

    private fun sPath(closed: Boolean = false): PenGuide {
        val nodes = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 30f), Vec2(0f, 30f), Vec2(0f, 60f), Vec2(100f, 60f))
            .map { PathNode(it, NodeType.SMOOTH) }
        return PenGuide(PenConfig(on = true, nodes = nodes, closed = closed, locked = true))
    }

    @Test
    fun penSIsContinuousAndDoesNotJumpLoops() {
        val t = tracks(sPath(), Vec2(0f, 0f)).single()
        t.project(0f, 0f, Float.NaN, proj)
        var s = proj.s
        // 18 px below the first row is nearer the second row (12 px), but the window keeps us on row 1.
        for (i in 0..60) {
            val before = s
            t.project(i.toFloat(), 18f, s, proj)
            s = proj.s
            assertTrue(proj.y < 5f, "stayed on first row at x=$i: ${proj.y}")
            assertTrue(abs(s - before) < 3f, "s continuous")
        }
        t.project(30f, 18f, Float.NaN, proj)
        assertEquals(30f, proj.y, 1f)
    }

    @Test
    fun penClosedWrapsAround() {
        val sq = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 100f), Vec2(0f, 100f)).map { PathNode(it) }
        val t = tracks(PenGuide(PenConfig(on = true, nodes = sq, closed = true, locked = true)), Vec2(0f, 0f)).single()
        t.project(1f, -2f, Float.NaN, proj)
        var s = proj.s
        val pts = ArrayList<Vec2>()
        repeat(2) {
            for (i in 0..99 step 2) pts += Vec2(i.toFloat(), -3f)
            for (i in 0..99 step 2) pts += Vec2(103f, i.toFloat())
            for (i in 100 downTo 1 step 2) pts += Vec2(i.toFloat(), 103f)
            for (i in 100 downTo 1 step 2) pts += Vec2(-3f, i.toFloat())
        }
        for (p in pts) { t.project(p.x, p.y, s, proj); s = proj.s }
        assertTrue(s > 750f, "two laps: s = $s")
    }

    @Test
    fun penGlobalSearchWithoutHint() {
        val t = tracks(sPath(), Vec2(0f, 0f)).single()
        t.project(50f, 63f, Float.NaN, proj)
        // Lands on the bottom row (rows at y = 0, 30, 60); SMOOTH nodes bow that row slightly below 60.
        assertEquals(60f, proj.y, 3f)
    }

    @Test
    fun penNeedsLock() {
        val nodes = listOf(PathNode(Vec2(0f, 0f)), PathNode(Vec2(10f, 0f)))
        assertTrue(GuideState(pen = PenConfig(on = true, nodes = nodes)).snapGuides().isEmpty())
        assertEquals(1, GuideState(pen = PenConfig(on = true, nodes = nodes, locked = true)).snapGuides().size)
    }
}
