package app.painter.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoPlaybackCursorTest {

    @Test
    fun ticksComeFromElapsedTimeNotFromThePreviousTick() {
        assertEquals(0L, VideoPlaybackCursor.ticksAt(0))
        assertEquals(0L, VideoPlaybackCursor.ticksAt(16))
        assertEquals(1L, VideoPlaybackCursor.ticksAt(17))
        assertEquals(60L, VideoPlaybackCursor.ticksAt(1_000))
        assertEquals(120L, VideoPlaybackCursor.ticksAt(2_000))
        assertEquals(74L, VideoPlaybackCursor.ticksAt(1_234))
        // Integer division, so a long preview never accumulates a rounding error.
        assertEquals(36_000L, VideoPlaybackCursor.ticksAt(600_000))
        assertEquals(0L, VideoPlaybackCursor.ticksAt(-50))
    }

    @Test
    fun advanceAddsTheStartTick() {
        assertEquals(VideoPlaybackTick(10L, false), VideoPlaybackCursor.advance(10, 0, 100, false))
        assertEquals(VideoPlaybackTick(70L, false), VideoPlaybackCursor.advance(10, 1_000, 100, false))
        assertEquals(VideoPlaybackTick(70L, false), VideoPlaybackCursor.advance(10, 1_000, 100, true))
        assertEquals(VideoPlaybackTick(10L, false), VideoPlaybackCursor.advance(10, 0, 100, true))
    }

    @Test
    fun nonLoopingPlaybackClampsAndReportsFinished() {
        assertEquals(VideoPlaybackTick(99L, false), VideoPlaybackCursor.advance(90, 150, 100, false))
        assertEquals(VideoPlaybackTick(100L, true), VideoPlaybackCursor.advance(90, 200, 100, false))
        assertEquals(VideoPlaybackTick(100L, true), VideoPlaybackCursor.advance(0, 50_000, 100, false))
        assertEquals(VideoPlaybackTick(3600L, true), VideoPlaybackCursor.advance(0, 600_000, 3600, false))
    }

    @Test
    fun loopingPlaybackWrapsAndNeverLeavesTheTimeline() {
        assertEquals(VideoPlaybackTick(10L, false), VideoPlaybackCursor.advance(50, 1000, 100, true))
        // Starting at 40 and running one second lands exactly on the exclusive end, which restarts at zero.
        assertEquals(VideoPlaybackTick(0L, false), VideoPlaybackCursor.advance(40, 1000, 100, true))
        assertEquals(VideoPlaybackTick(2L, false), VideoPlaybackCursor.advance(90, 200, 100, true))
        assertEquals(VideoPlaybackTick(50L, false), VideoPlaybackCursor.advance(250, 0, 100, true))
        assertEquals(VideoPlaybackTick(0L, false), VideoPlaybackCursor.advance(0, 600_000, 3600, true))
        assertEquals(VideoPlaybackTick(0L, false), VideoPlaybackCursor.advance(1, 0, 1, true))
    }

    @Test
    fun emptyTimelineFinishesImmediately() {
        assertEquals(VideoPlaybackTick(0L, true), VideoPlaybackCursor.advance(0, 0, 0, true))
        assertEquals(VideoPlaybackTick(0L, true), VideoPlaybackCursor.advance(50, 1000, 0, false))
        assertEquals(VideoPlaybackTick(0L, true), VideoPlaybackCursor.advance(-5, -100, -10, true))
    }

    @Test
    fun scrubClampsAndPlaybackRestartsFromTheBeginningAtTheEnd() {
        assertEquals(0L, VideoPlaybackCursor.clampTick(-5, 100))
        assertEquals(55L, VideoPlaybackCursor.clampTick(55, 100))
        assertEquals(100L, VideoPlaybackCursor.clampTick(150, 100))
        assertEquals(0L, VideoPlaybackCursor.clampTick(10, -1))

        assertEquals(42L, VideoPlaybackCursor.startTick(42, 100))
        assertEquals(0L, VideoPlaybackCursor.startTick(100, 100))
        assertEquals(0L, VideoPlaybackCursor.startTick(120, 100))
        assertEquals(0L, VideoPlaybackCursor.startTick(42, 0))
        assertTrue(VideoPlaybackCursor.startTick(0, 1) == 0L)
    }
}
