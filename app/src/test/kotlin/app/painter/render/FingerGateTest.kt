package app.painter.render

import android.view.MotionEvent
import app.painter.studio.FingerWithStylus
import app.painter.studio.PalmRejection
import app.painter.studio.StylusSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Finger and palm rule of spec/tools/m2-brush.md §4.3 (§"Finger with an active stylus", "Palm rejection"). */
class FingerGateTest {

    private val finger = MotionEvent.TOOL_TYPE_FINGER

    private fun mode(
        settings: StylusSettings,
        toolType: Int = finger,
        penSeen: Boolean = true,
        penHovering: Boolean = false,
        msSincePenHover: Long = -1L,
    ): FingerMode = FingerGate.mode(settings, toolType, penSeen, penHovering, msSincePenHover)

    @Test
    fun defaultSettingsLetTheFingerDrawUntilThePenIsSeen() {
        val settings = StylusSettings()
        assertEquals(FingerMode.DRAW, mode(settings, penSeen = false))
        assertEquals(FingerMode.NAVIGATE, mode(settings))
    }

    @Test
    fun drawingFingerNeedsBothSettingsToAllowIt() {
        // Palm "off" and finger "draw" is the finger-only setup.
        val fingerOnly = StylusSettings(palm = PalmRejection.OFF, fingerWithStylus = FingerWithStylus.DRAW)
        assertEquals(FingerMode.DRAW, mode(fingerOnly))

        // The default palm mode is the stricter one: "stylus only" means the finger does not draw.
        val stylusOnly = StylusSettings(fingerWithStylus = FingerWithStylus.DRAW)
        assertEquals(FingerMode.NAVIGATE, mode(stylusOnly))

        // And "navigate only" blocks the finger with the palm rejection turned off.
        val navigateOnly = StylusSettings(palm = PalmRejection.OFF, fingerWithStylus = FingerWithStylus.NAVIGATE_ONLY)
        assertEquals(FingerMode.DRAW, mode(navigateOnly, penSeen = false))
        assertEquals(FingerMode.NAVIGATE, mode(navigateOnly))
    }

    @Test
    fun hoverRejectIgnoresTheFingerAroundPenHover() {
        val settings = StylusSettings(palm = PalmRejection.HOVER_REJECT, fingerWithStylus = FingerWithStylus.DRAW)
        assertEquals(FingerMode.NAVIGATE, mode(settings, penSeen = false, penHovering = true))
        assertEquals(FingerMode.NAVIGATE, mode(settings, penSeen = false, msSincePenHover = 0L))
        assertEquals(FingerMode.NAVIGATE, mode(settings, penSeen = false, msSincePenHover = FingerGate.HOVER_REJECT_MS))
        assertEquals(FingerMode.DRAW, mode(settings, penSeen = false, msSincePenHover = FingerGate.HOVER_REJECT_MS + 1))
        assertEquals(FingerMode.DRAW, mode(settings, penSeen = false, msSincePenHover = -1L))
    }

    @Test
    fun hoverRejectDoesNotBlockTheFingerForever() {
        val settings = StylusSettings(palm = PalmRejection.HOVER_REJECT, fingerWithStylus = FingerWithStylus.DRAW)
        // The pen was used in this session, but the palm mode only rejects around hover.
        assertEquals(FingerMode.DRAW, mode(settings, msSincePenHover = 5_000L))
    }

    @Test
    fun penMouseAndEraserKeepDrawing() {
        val settings = StylusSettings()
        for (tool in intArrayOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER, MotionEvent.TOOL_TYPE_MOUSE)) {
            assertEquals(tool.toString(), FingerMode.DRAW, mode(settings, toolType = tool, penHovering = true))
        }
    }
}
