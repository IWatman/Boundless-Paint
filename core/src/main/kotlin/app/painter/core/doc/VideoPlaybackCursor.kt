package app.painter.core.doc

/** One evaluation of the 60 Hz preview clock. [finished] is true only for a non-looping playback at the end. */
data class VideoPlaybackTick(val tick: Long, val finished: Boolean)

/**
 * Pure tick math of the video preview (spec/VIDEO-MODE.md §5.3). Elapsed time comes from the studio's
 * monotonic clock; a tick is never accumulated from the previous one, so a long preview cannot drift.
 */
object VideoPlaybackCursor {
    /** Ticks covered by [elapsedMs] at 60 ticks per second, integer division as the spec defines it. */
    fun ticksAt(elapsedMs: Long): Long =
        if (elapsedMs <= 0L) 0L else elapsedMs * VIDEO_TICKS_PER_SECOND / 1000L

    /**
     * Tick of a playback that started at [startTick] and has been running for [elapsedMs]. A looping preview
     * wraps and stays inside `0 until duration`; a non-looping one clamps to the exclusive end and reports
     * [VideoPlaybackTick.finished] there.
     */
    fun advance(startTick: Long, elapsedMs: Long, durationTicks: Long, loop: Boolean): VideoPlaybackTick {
        val duration = durationTicks.coerceAtLeast(0L)
        if (duration == 0L) return VideoPlaybackTick(0L, true)
        val raw = (startTick.coerceAtLeast(0L) + ticksAt(elapsedMs)).coerceAtLeast(0L)
        if (loop) return VideoPlaybackTick(raw % duration, false)
        if (raw >= duration) return VideoPlaybackTick(duration, true)
        return VideoPlaybackTick(raw, false)
    }

    /** Clamps a scrub or a stored playhead to the timeline. */
    fun clampTick(tick: Long, durationTicks: Long): Long =
        tick.coerceIn(0L, durationTicks.coerceAtLeast(0L))

    /**
     * Tick playback starts from. A playhead at (or past) the end starts the preview from the beginning, so a
     * second play press after the last frame restarts instead of doing nothing.
     */
    fun startTick(playheadTick: Long, durationTicks: Long): Long {
        val duration = durationTicks.coerceAtLeast(0L)
        if (duration == 0L) return 0L
        val clamped = clampTick(playheadTick, duration)
        return if (clamped >= duration) 0L else clamped
    }
}
