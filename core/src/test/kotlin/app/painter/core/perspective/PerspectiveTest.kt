package app.painter.core.perspective

import app.painter.core.geom.Vec2
import app.painter.core.guide.DocRect
import app.painter.core.guide.GridConfig
import app.painter.core.guide.LineTrack
import app.painter.core.guide.OverlayRole
import app.painter.core.guide.Projection
import app.painter.core.guide.SnapContext
import app.painter.core.guide.SnapStage
import app.painter.core.guide.SnapTrack
import app.painter.core.guide.runStage
import app.painter.core.stroke.InputPoint
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerspectiveTest {
    private val w = 1000; private val h = 800

    private fun tracks(cfg: PerspectiveConfig, p: Vec2) =
        ArrayList<SnapTrack>().also { PerspectiveGuide(cfg).tracks(p, SnapContext(), it) }.map { it as LineTrack }

    private fun passesThrough(t: LineTrack, q: Vec2, eps: Float = 0.5f) {
        val pr = Projection()
        t.project(q.x, q.y, Float.NaN, pr)
        assertTrue(abs(pr.x - q.x) < eps && abs(pr.y - q.y) < eps, "track misses $q")
    }

    private fun dot(a: Vec2, b: Vec2) = a.x * b.x + a.y * b.y

    @Test
    fun onePointTrackHitsVp() {
        val cfg = PerspectiveConfig.defaults(PerspectiveType.ONE_POINT, w, h)
        val ts = tracks(cfg, Vec2(100f, 700f))
        assertEquals(3, ts.size)
        passesThrough(ts[0], cfg.vp[0])
        assertEquals(1f, abs(ts[1].dir.x), 1e-5f)
        assertEquals(1f, abs(ts[2].dir.y), 1e-5f)
    }

    @Test
    fun twoPointVerticalIsPerpendicularToHorizon() {
        val cfg = PerspectiveConfig.defaults(PerspectiveType.TWO_POINT, w, h).copy(vp = listOf(Vec2(-300f, 300f), Vec2(1300f, 420f)))
        val ts = tracks(cfg, Vec2(500f, 600f))
        assertEquals(3, ts.size)
        passesThrough(ts[0], cfg.vp[0]); passesThrough(ts[1], cfg.vp[1])
        val hd = PerspectiveGuide.horizonDir(cfg)!!
        assertEquals(0f, dot(ts[2].dir, hd), 1e-5f)
    }

    @Test
    fun threePointVerticalThroughVp3() {
        val cfg = PerspectiveConfig.defaults(PerspectiveType.THREE_POINT, w, h)
        val ts = tracks(cfg, Vec2(450f, 100f))
        assertEquals(3, ts.size)
        passesThrough(ts[2], cfg.vp[2])
        // A roughly vertical stroke snaps onto the line through VP3.
        val pts = (0..30).map { InputPoint(450f + (it % 2) * 0.7f, 100f + it * 3f) }
        val out = runStage(SnapStage(listOf(PerspectiveGuide(cfg)), SnapContext()), pts).all
        val vp = cfg.vp[2]
        val d = Vec2(vp.x - 450f, vp.y - 100f).let { val l = sqrt(it.x * it.x + it.y * it.y); Vec2(it.x / l, it.y / l) }
        for (p in out) assertTrue(abs((p.x - 450f) * d.y - (p.y - 100f) * d.x) < 0.01f)
    }

    @Test
    fun isoAndGridDirections() {
        val iso = tracks(PerspectiveConfig(on = true, type = PerspectiveType.ISO), Vec2(10f, 10f))
        assertEquals(3, iso.size)
        assertEquals(sqrt(3f) / 2, iso[0].dir.x, 1e-5f); assertEquals(0.5f, iso[0].dir.y, 1e-5f)
        assertEquals(-sqrt(3f) / 2, iso[1].dir.x, 1e-5f)
        assertEquals(1f, iso[2].dir.y, 1e-5f)
        val grid = tracks(PerspectiveConfig(on = true, type = PerspectiveType.GRID_2D, horizonDeg = 30f), Vec2(10f, 10f))
        assertEquals(2, grid.size)
        assertEquals(0f, dot(grid[0].dir, grid[1].dir), 1e-5f)
    }

    @Test
    fun startNearVpSkipsFamily() {
        val cfg = PerspectiveConfig.defaults(PerspectiveType.ONE_POINT, w, h)
        assertEquals(2, tracks(cfg, Vec2(cfg.vp[0].x + 2f, cfg.vp[0].y)).size)
        assertTrue(tracks(cfg.copy(snap = false), Vec2(0f, 0f)).isEmpty())
    }

    @Test
    fun overlayRaysConvergeOnVp() {
        val cfg = PerspectiveConfig.defaults(PerspectiveType.TWO_POINT, w, h)
        val view = DocRect(0f, 0f, w.toFloat(), h.toFloat())
        val lines = PerspectiveOverlay.lines(cfg, view, 1f)
        val vp1 = lines.filter { it.role == OverlayRole.VP1 }
        assertEquals(PerspectiveOverlay.RAYS_PER_VP, vp1.size)
        for (l in vp1) {
            val dx = l.b.x - l.a.x; val dy = l.b.y - l.a.y
            val cross = (cfg.vp[0].x - l.a.x) * dy - (cfg.vp[0].y - l.a.y) * dx
            assertTrue(abs(cross) / sqrt(dx * dx + dy * dy) < 0.5f, "ray misses VP")
            assertTrue(l.a.x in -0.01f..w + 0.01f && l.b.y in -0.01f..h + 0.01f)
        }
        assertEquals(1, lines.count { it.role == OverlayRole.HORIZON })
        assertTrue(lines.count { it.role == OverlayRole.VERTICAL } in 14..17)
        // VP inside the view: rays all around.
        val one = PerspectiveOverlay.lines(PerspectiveConfig.defaults(PerspectiveType.ONE_POINT, w, h), view, 1f)
        assertEquals(24, one.count { it.role == OverlayRole.VP1 })
        assertTrue(PerspectiveOverlay.lines(cfg.copy(visible = false), view, 1f).isEmpty())
    }

    @Test
    fun gridThinsWhenZoomedOut() {
        val view = DocRect(0f, 0f, 640f, 640f)
        val cfg = PerspectiveConfig(on = true, type = PerspectiveType.GRID_2D, spacing = 64f)
        assertEquals(22, PerspectiveOverlay.lines(cfg, view, 1f).size)
        assertTrue(PerspectiveOverlay.lines(cfg, view, 20f).size < 22)
    }

    @Test
    fun gridOverlayMinorAndMajor() {
        val g = GridConfig(on = true, minor = 32f, majorEvery = 4)
        val view = DocRect(0f, 0f, 256f, 256f)
        val lines = g.lines(view, 1f)
        assertEquals(6, lines.count { it.role == OverlayRole.MAJOR })
        assertEquals(12, lines.count { it.role == OverlayRole.MINOR })
        assertEquals(0, g.lines(view, 8f).count { it.role == OverlayRole.MINOR }, "minor < 6 screen px hidden")
    }

    @Test
    fun gridNodeSnapping() {
        val cfg = PerspectiveConfig(on = true, type = PerspectiveType.GRID_2D, spacing = 50f, origin = Vec2(10f, 10f))
        val q = assertNotNull(PerspectiveGuide.nearestGridNode(cfg, Vec2(64f, 57f), 12f))
        assertEquals(60f, q.x, 1e-3f); assertEquals(60f, q.y, 1e-3f)
        assertNull(PerspectiveGuide.nearestGridNode(cfg, Vec2(85f, 85f), 12f))
    }
}
