package app.painter.render

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pen button mapping of spec/tools/m2-brush.md §4.3: the default assignments and user-chosen ones. */
class PenButtonsTest {

    private val defaults = PenButtons()

    @Test
    fun primaryButtonIsTheEyedropper() {
        assertEquals(
            PenAction.EYEDROPPER,
            defaults.action(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY),
        )
        assertEquals(
            PenAction.EYEDROPPER,
            defaults.holdAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY),
        )
    }

    @Test
    fun secondaryButtonIsUndo() {
        assertEquals(
            PenAction.UNDO,
            defaults.clickAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_SECONDARY),
        )
        // A click action never arms the hold state, and a hold action never fires on press.
        assertEquals(
            PenAction.NONE,
            defaults.holdAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_SECONDARY),
        )
        assertEquals(
            PenAction.NONE,
            defaults.clickAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY),
        )
    }

    @Test
    fun mouseModeDriverUsesTheMouseButtons() {
        // A driver in mouse mode reports the pen as TOOL_TYPE_STYLUS with BUTTON_SECONDARY/BUTTON_TERTIARY.
        assertEquals(
            PenAction.EYEDROPPER,
            defaults.action(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_SECONDARY),
        )
        assertEquals(
            PenAction.UNDO,
            defaults.action(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_TERTIARY),
        )
    }

    @Test
    fun primaryWinsWhileBothButtonsAreDown() {
        val both = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        assertEquals(PenAction.EYEDROPPER, defaults.action(MotionEvent.TOOL_TYPE_STYLUS, both))
        assertEquals(PenAction.ERASE, PenButtons(PenAction.ERASE, PenAction.REDO).action(MotionEvent.TOOL_TYPE_STYLUS, both))
    }

    @Test
    fun releasedButtonLeavesNoAction() {
        // After the release getButtonState() no longer holds the button: nothing stays armed.
        assertEquals(PenAction.NONE, defaults.action(MotionEvent.TOOL_TYPE_STYLUS, 0))
        assertEquals(PenAction.NONE, defaults.holdAction(MotionEvent.TOOL_TYPE_STYLUS, 0))
        assertEquals(PenAction.NONE, defaults.clickAction(MotionEvent.TOOL_TYPE_STYLUS, 0))
    }

    @Test
    fun assignedActionsReplaceTheDefaults() {
        val buttons = PenButtons(PenAction.ERASE, PenAction.REDO)
        // Erase is a hold action: it arms the temporary Erase mode and never fires on the press.
        assertEquals(PenAction.ERASE, buttons.holdAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertEquals(PenAction.NONE, buttons.clickAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY))
        // Redo is a click action, also for a driver in mouse mode.
        assertEquals(PenAction.REDO, buttons.clickAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_SECONDARY))
        assertEquals(PenAction.NONE, buttons.holdAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_SECONDARY))
        assertEquals(PenAction.ERASE, buttons.action(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_SECONDARY))
        assertEquals(PenAction.REDO, buttons.action(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_TERTIARY))
    }

    @Test
    fun brushBrowserIsAClickAction() {
        val buttons = PenButtons(PenAction.BRUSH_BROWSER, PenAction.UNDO)
        assertEquals(PenAction.BRUSH_BROWSER, buttons.clickAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertEquals(PenAction.NONE, buttons.holdAction(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY))
    }

    @Test
    fun unassignedButtonDoesNothing() {
        val none = PenButtons(PenAction.NONE, PenAction.NONE)
        for (state in intArrayOf(MotionEvent.BUTTON_STYLUS_PRIMARY, MotionEvent.BUTTON_STYLUS_SECONDARY, 0)) {
            assertEquals(PenAction.NONE, none.action(MotionEvent.TOOL_TYPE_STYLUS, state))
            assertEquals(PenAction.NONE, none.holdAction(MotionEvent.TOOL_TYPE_STYLUS, state))
            assertEquals(PenAction.NONE, none.clickAction(MotionEvent.TOOL_TYPE_STYLUS, state))
        }
    }

    @Test
    fun onlyEyedropperAndEraseAreHoldActions() {
        for (action in PenAction.values()) {
            assertEquals(action.name, action == PenAction.EYEDROPPER || action == PenAction.ERASE, action.hold)
        }
    }

    @Test
    fun otherToolsHaveNoButtons() {
        for (tool in intArrayOf(
            MotionEvent.TOOL_TYPE_FINGER,
            MotionEvent.TOOL_TYPE_MOUSE,
            MotionEvent.TOOL_TYPE_ERASER,
            MotionEvent.TOOL_TYPE_UNKNOWN,
        )) {
            assertEquals(PenAction.NONE, defaults.action(tool, MotionEvent.BUTTON_STYLUS_PRIMARY))
            assertEquals(PenAction.NONE, defaults.action(tool, MotionEvent.BUTTON_TERTIARY))
        }
    }

    @Test
    fun eraserEndIsTheBackOfThePen() {
        assertTrue(PenButtons.isEraserEnd(MotionEvent.TOOL_TYPE_ERASER))
        assertFalse(PenButtons.isEraserEnd(MotionEvent.TOOL_TYPE_STYLUS))
        assertFalse(PenButtons.isEraserEnd(MotionEvent.TOOL_TYPE_FINGER))
    }
}
