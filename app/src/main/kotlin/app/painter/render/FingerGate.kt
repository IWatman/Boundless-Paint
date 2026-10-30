package app.painter.render

import android.view.MotionEvent
import app.painter.studio.FingerWithStylus
import app.painter.studio.PalmRejection
import app.painter.studio.StylusSettings

/** How the gesture router treats a touch, see [FingerGate]. */
enum class FingerMode {
    /** The touch draws a stroke (with the finger delays of [GestureRouter]). */
    DRAW,

    /** The touch never starts a stroke: it only pans, zooms and takes part in the undo/redo taps. */
    NAVIGATE,
}

/**
 * Finger and palm rule of the session (spec/tools/m2-brush.md §4.3), decided from [StylusSettings] and what
 * the pen did so far. Pure, so it is unit-tested on the JVM; [GestureRouter] only applies the result.
 *
 * A pen, a mouse and the back of the pen always draw. A finger is reduced to navigation when the palm mode
 * is [PalmRejection.STYLUS_ONLY] or the finger is set to [FingerWithStylus.NAVIGATE_ONLY] and the session has
 * already seen the pen — the stricter of the two settings wins, so the defaults (`STYLUS_ONLY` and
 * `NAVIGATE_ONLY`) mean "the finger does not draw". With [PalmRejection.HOVER_REJECT] a finger is reduced to
 * navigation while the pen hovers and [HOVER_REJECT_MS] after it left hover, even before the first pen touch.
 */
object FingerGate {

    /** How long after the pen left hover a finger touch still counts as a palm ([PalmRejection.HOVER_REJECT]). */
    const val HOVER_REJECT_MS = 300L

    /**
     * Mode of a touch of [toolType]. [penSeen] is true once a stylus or eraser was in contact or in hover in
     * this session, [penHovering] is the current hover state of the pen and [msSincePenHover] the time since
     * it last left hover (−1 when that never happened).
     */
    fun mode(
        settings: StylusSettings,
        toolType: Int,
        penSeen: Boolean,
        penHovering: Boolean,
        msSincePenHover: Long,
    ): FingerMode {
        if (toolType != MotionEvent.TOOL_TYPE_FINGER && toolType != MotionEvent.TOOL_TYPE_UNKNOWN) {
            return FingerMode.DRAW
        }
        if (isHoverReject(settings, penHovering, msSincePenHover)) return FingerMode.NAVIGATE
        val fingerNavigates = settings.fingerWithStylus == FingerWithStylus.NAVIGATE_ONLY
        val stylusOnly = settings.palm == PalmRejection.STYLUS_ONLY
        return if (penSeen && (stylusOnly || fingerNavigates)) FingerMode.NAVIGATE else FingerMode.DRAW
    }

    /** True while the pen hovers and [HOVER_REJECT_MS] after it left, with [PalmRejection.HOVER_REJECT]. */
    private fun isHoverReject(settings: StylusSettings, penHovering: Boolean, msSincePenHover: Long): Boolean {
        if (settings.palm != PalmRejection.HOVER_REJECT) return false
        return penHovering || msSincePenHover in 0..HOVER_REJECT_MS
    }
}
