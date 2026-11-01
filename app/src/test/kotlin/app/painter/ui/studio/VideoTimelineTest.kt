package app.painter.ui.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTimelineTest {

    @Test
    fun fractionAndTickMapBothWays() {
        assertEquals(0L, VideoTimeline.tickAtFraction(0f, 600))
        assertEquals(300L, VideoTimeline.tickAtFraction(0.5f, 600))
        assertEquals(51L, VideoTimeline.tickAtFraction(0.5f, 101))
        assertEquals(600L, VideoTimeline.tickAtFraction(2f, 600))
        assertEquals(0L, VideoTimeline.tickAtFraction(-1f, 600))
        assertEquals(0L, VideoTimeline.tickAtFraction(0.7f, 0))

        assertEquals(0f, VideoTimeline.fractionAtTick(0, 600), 0.0001f)
        assertEquals(0.5f, VideoTimeline.fractionAtTick(300, 600), 0.0001f)
        assertEquals(1f, VideoTimeline.fractionAtTick(900, 600), 0.0001f)
        assertEquals(0f, VideoTimeline.fractionAtTick(-20, 600), 0.0001f)
        assertEquals(0f, VideoTimeline.fractionAtTick(20, 0), 0.0001f)
    }

    @Test
    fun dragPixelsBecomeTicks() {
        assertEquals(60L, VideoTimeline.deltaTicks(100f, 1000f, 600))
        assertEquals(-30L, VideoTimeline.deltaTicks(-50f, 1000f, 600))
        assertEquals(0L, VideoTimeline.deltaTicks(100f, 0f, 600))
        assertEquals(0L, VideoTimeline.deltaTicks(100f, 1000f, 0))
    }

    @Test
    fun snappingPrefersTargetsThenTheHalfSecondGrid() {
        val targets = listOf(0L, 15L, 30L)
        assertEquals(15L, VideoTimeline.snappedTick(14, targets, 3))
        assertEquals(12L, VideoTimeline.snappedTick(12, targets, 1))
        assertEquals(30L, VideoTimeline.snappedTick(31, targets, 4))
        assertEquals(60L, VideoTimeline.snappedTick(46, targets, 20))
        assertEquals(0L, VideoTimeline.snappedTick(-5, targets, 10))
        assertEquals(14L, VideoTimeline.snappedTick(14, targets, 0))
        // Explicit targets win a tie with the grid.
        assertEquals(30L, VideoTimeline.snappedTick(29, listOf(30L), 10))
    }

    @Test
    fun movedStartSnapsTheNewClipStart() {
        val targets = listOf(112L)
        assertEquals(110L, VideoTimeline.movedStart(50, 100f, 1000f, 600, false, targets, 4))
        assertEquals(112L, VideoTimeline.movedStart(50, 100f, 1000f, 600, true, targets, 4))
        assertEquals(0L, VideoTimeline.movedStart(50, -500f, 1000f, 600, false, targets, 4))
        assertEquals(600L, VideoTimeline.movedStart(50, 5000f, 1000f, 600, false, targets, 4))
    }

    @Test
    fun trimDurationSnapsTheRightEdgeAndCanReachZero() {
        val targets = listOf(118L)
        assertEquals(120L, VideoTimeline.trimDuration(0, 60, 100f, 1000f, 600, false, targets, 4))
        assertEquals(118L, VideoTimeline.trimDuration(0, 57, 100f, 1000f, 600, true, targets, 4))
        assertEquals(0L, VideoTimeline.trimDuration(10, 20, -1000f, 1000f, 600, false, targets, 4))
        assertEquals(30L, VideoTimeline.trimDuration(0, 60, -50f, 1000f, 600, true, listOf(30L), 4))
    }
}
