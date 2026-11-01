package app.painter.ui.studio

import app.painter.core.doc.VIDEO_TICKS_PER_SECOND
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Pure coordinate and snap math of the video timeline. The panel passes the pointer position in pixels; this
 * object turns it into ticks, so the gesture code stays free of timeline arithmetic and is testable on the JVM.
 */
object VideoTimeline {
    /** Snap grid of the timeline: half a second, so whole seconds are grid points too. */
    const val GRID_TICKS = VIDEO_TICKS_PER_SECOND / 2L

    /** Fraction of the ruler/lane width to a tick, clamped to the timeline. */
    fun tickAtFraction(fraction: Float, durationTicks: Long): Long {
        val duration = durationTicks.coerceAtLeast(0L)
        if (duration == 0L) return 0L
        val f = fraction.coerceIn(0f, 1f)
        return (f * duration).roundToLong().coerceIn(0L, duration)
    }

    /** Tick to a fraction of the ruler/lane width; 0 when the timeline is empty. */
    fun fractionAtTick(tick: Long, durationTicks: Long): Float {
        val duration = durationTicks.coerceAtLeast(0L)
        if (duration == 0L) return 0f
        return (tick.coerceIn(0L, duration).toFloat() / duration).coerceIn(0f, 1f)
    }

    /** Horizontal drag of [deltaPx] on a lane [widthPx] wide, expressed in ticks. */
    fun deltaTicks(deltaPx: Float, widthPx: Float, durationTicks: Long): Long {
        if (widthPx <= 0f) return 0L
        val width = widthPx
        val duration = durationTicks.coerceAtLeast(0L)
        if (duration == 0L) return 0L
        return (deltaPx / width * duration).roundToLong()
    }

    /**
     * Snaps [candidate] to the nearest of [targets] or of the half-second grid within [thresholdTicks].
     * Explicit targets win a distance tie, so the playhead and clip edges are preferred over the grid.
     */
    fun snappedTick(
        candidate: Long,
        targets: List<Long>,
        thresholdTicks: Long,
        gridTicks: Long = GRID_TICKS,
    ): Long {
        val threshold = thresholdTicks.coerceAtLeast(0L)
        if (threshold == 0L) return candidate
        var best = candidate
        var bestDistance = threshold + 1L
        for (target in targets) {
            val distance = abs(target - candidate)
            if (distance <= threshold && distance < bestDistance) {
                best = target
                bestDistance = distance
            }
        }
        if (gridTicks > 0L) {
            val lower = Math.floorDiv(candidate, gridTicks) * gridTicks
            for (gridTarget in longArrayOf(lower, lower + gridTicks)) {
                val distance = abs(gridTarget - candidate)
                if (distance <= threshold && distance < bestDistance) {
                    best = gridTarget
                    bestDistance = distance
                }
            }
        }
        return best
    }

    /** Start tick of a moved clip after a horizontal drag, optionally snapped. */
    fun movedStart(
        startTick: Long,
        deltaPx: Float,
        widthPx: Float,
        durationTicks: Long,
        snap: Boolean,
        targets: List<Long>,
        thresholdTicks: Long,
    ): Long {
        val candidate = (startTick + deltaTicks(deltaPx, widthPx, durationTicks))
            .coerceIn(0L, durationTicks.coerceAtLeast(0L))
        return if (snap) snappedTick(candidate, targets, thresholdTicks) else candidate
    }

    /**
     * New duration of a right-edge trim after a horizontal drag. The end is snapped, then the duration is the
     * difference to [startTick]; a drag that reaches or passes the start returns 0, which the studio treats as
     * a delete.
     */
    fun trimDuration(
        startTick: Long,
        currentDurationTicks: Long,
        deltaPx: Float,
        widthPx: Float,
        durationTicks: Long,
        snap: Boolean,
        targets: List<Long>,
        thresholdTicks: Long,
    ): Long {
        val candidateEnd = (startTick + currentDurationTicks + deltaTicks(deltaPx, widthPx, durationTicks))
            .coerceAtLeast(startTick)
        val end = if (snap) snappedTick(candidateEnd, targets, thresholdTicks) else candidateEnd
        return (end - startTick).coerceAtLeast(0L)
    }
}
