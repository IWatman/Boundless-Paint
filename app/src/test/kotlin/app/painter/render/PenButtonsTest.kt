package app.painter.render

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pen button mapping of spec/tools/m2-brush.md §4.3 (defaults, not configurable yet). */
class PenButtonsTest {
    @Test
    fun primaryButtonIsTheEyedropper() {
        assertEquals(
            PenAction.EYEDROPPER,
            PenButtons.action(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY),
        )
        assertEquals(
            PenAction.EYEDROPPER,
            PenButtons.holdAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY),
        )
    }

    @Test
    fun secondaryButtonIsUndo() {
        assertEquals(
            PenAction.UNDO,
            PenButtons.clickAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_SECONDARY),
        )
        // A click action never arms the hold state, and a hold action never fires on press.
        assertEquals(
            PenAction.NONE,
            PenButtons.holdAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_SECONDARY),
        )
        assertEquals(
            PenAction.NONE,
            PenButtons.clickAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY),
        )
    }

    @Test
    fun mouseModeDriverUsesTheMouseButtons() {
        // A driver in mouse mode reports the pen as TOOL_TYPE_STYLUS with BUTTON_SECONDARY/BUTTON_TERTIARY.
        assertEquals(
            PenAction.EYEDROPPER,
            PenButtons.action(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_SECONDARY),
        )
        assertEquals(
            PenAction.UNDO,
            PenButtons.action(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_TERTIARY),
        )
    }

    @Test
    fun primaryWinsWhileBothButtonsAreDown() {
        val both = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        assertEquals(PenAction.EYEDROPPER, PenButtons.action(MotionEvent.TOOL_TYPE_STYLUS, both))
    }

    @Test
    fun releasedButtonLeavesNoAction() {
        // After the release getButtonState() no longer holds the button: nothing stays armed.
        assertEquals(PenAction.NONE, PenButtons.action(MotionEvent.TOOL_TYPE_STYLUS, 0))
        assertEquals(PenAction.NONE, PenButtons.holdAction(MotionEvent.TOOL_TYPE_STYLUS, 0))
        assertEquals(PenAction.NONE, PenButtons.clickAction(MotionEvent.TOOL_TYPE_STYLUS, 0))
    }

    @Test
    fun otherToolsHaveNoButtons() {
        for (tool in intArrayOf(
            MotionEvent.TOOL_TYPE_FINGER,
            MotionEvent.TOOL_TYPE_MOUSE,
            MotionEvent.TOOL_TYPE_ERASER,
            MotionEvent.TOOL_TYPE_UNKNOWN,
        )) {
            assertEquals(PenAction.NONE, PenButtons.action(tool, MotionEvent.BUTTON_STYLUS_PRIMARY))
            assertEquals(PenAction.NONE, PenButtons.action(tool, MotionEvent.BUTTON_TERTIARY))
        }
    }

    @Test
    fun eraserEndIsTheBackOfThePen() {
        assertTrue(PenButtons.isEraserEnd(MotionEvent.TOOL_TYPE_ERASER))
        assertFalse(PenButtons.isEraserEnd(MotionEvent.TOOL_TYPE_STYLUS))
        assertFalse(PenButtons.isEraserEnd(MotionEvent.TOOL_TYPE_FINGER))
    }
}
