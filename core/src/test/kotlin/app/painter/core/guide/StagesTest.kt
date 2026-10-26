package app.painter.core.guide

import app.painter.core.geom.Vec2
import app.painter.core.stroke.InputPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StagesTest {
    private fun wiggle(n: Int, dt: Long = 8L) =
        (0 until n).map { InputPoint(it * 3f, 40f * sin(it * 0.1f), timeMs = it * dt) }

    @Test
    fun lazyTipStaysWithinRadiusAndDoesNotCatchUp() {
        val pts = wiggle(200)
        val lazy = LazyRopeStage(60f)
        val sink = RecordingSink()
        lazy.begin(pts[0], sink)
        for (p in pts.drop(1)) {
            lazy.add(p, sink)
            val q = lazy.tip!!
            assertTrue(hypot(p.x - q.x, p.y - q.y) <= 60f + 1e-3f)
        }
        val lastOut = sink.all.last()
        lazy.end(sink)
        assertEquals(lastOut, sink.all.last(), "no catch-up on pen-up")
        val gap = hypot(pts.last().x - lastOut.x, pts.last().y - lastOut.y)
        assertEquals(60f, gap, 0.5f)
    }

    @Test
    fun lazyZeroIsIdentity() {
        val pts = wiggle(50)
        assertEquals(pts, runStage(LazyRopeStage(0f), pts).all)
    }

    @Test
    fun elasticIncreasesRadius() {
        val fast = (0 until 50).map { InputPoint(it * 40f, 0f, timeMs = it * 8L) }
        val plain = LazyRopeStage(60f, 0f)
        val elastic = LazyRopeStage(60f, 1f)
        runStage(plain, fast); runStage(elastic, fast)
        assertEquals(60f, plain.currentRadius, 1e-4f)
        assertEquals(120f, elastic.currentRadius, 1e-3f)
    }

    private fun zigzag(moves: Int, len: Float = 60f): List<InputPoint> {
        val out = ArrayList<InputPoint>()
        var x = 0f
        out += InputPoint(0f, 0f)
        for (m in 0 until moves) {
            val dir = if (m % 2 == 0) 1f else -1f
            repeat((len / 2).toInt()) { x += dir * 2f; out += InputPoint(x, 0.3f * (out.size % 3)) }
        }
        return out
    }

    @Test
    fun hatchingFiveMovesGiveThreeForwardStrokes() {
        val out = runStage(HatchingStage(), zigzag(5))
        assertEquals(3, out.nonEmpty.size)
        for (s in out.nonEmpty) {
            for (i in 1 until s.size) assertTrue(s[i].x >= s[i - 1].x - 2f, "forward only")
        }
    }

    @Test
    fun hatchingHysteresisIgnoresSmallReversals() {
        val pts = ArrayList<InputPoint>()
        for (i in 0..50) pts += InputPoint(i * 2f - if (i % 5 == 4) 3f else 0f, 0f)
        assertEquals(1, runStage(HatchingStage(), pts).nonEmpty.size)
    }

    @Test
    fun hatchingFollowsSnappedTrack() {
        val ruler = LinesConfig(on = true, a = Vec2(0f, 0f), b = Vec2(100f, 0f), perpendicular = false)
        val snap = SnapStage(listOf(LinesGuide(ruler)), SnapContext())
        val hatch = HatchingStage(snap)
        val sink = RecordingSink()
        val pts = zigzag(5).map { it.copy(y = it.y + 50f + it.x * 0.05f) }
        Chain(listOf(snap, hatch), sink).run(pts)
        assertEquals(3, sink.nonEmpty.size)
        assertTrue(sink.all.all { abs(it.y - 50f) < 0.01f })
    }

    @Test
    fun guideStateBuildsStagesInOrder() {
        val st = GuideState(
            lazy = LazyConfig(on = true),
            lines = LinesConfig(on = true),
            hatching = HatchingConfig(on = true),
        )
        val stages = st.buildStages(SnapContext())
        assertEquals(listOf(LazyRopeStage::class, SnapStage::class, HatchingStage::class), stages.map { it::class })
        assertTrue(st.hasActiveSnap)
        assertTrue(GuideState().buildStages(SnapContext()).isEmpty())
    }
}
