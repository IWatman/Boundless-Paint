package app.painter.render

import android.view.MotionEvent

/** Action a stylus button is bound to (spec/tools/m2-brush.md §4.3). */
enum class PenAction {
    /** No action for this button state. */
    NONE,

    /** Held: a pen touch samples the colour under the tip instead of drawing. */
    EYEDROPPER,

    /** Click: undo, fired once on the button press. */
    UNDO,
}

/**
 * Default pen button mapping of the spec (§4.3 "Button actions"): primary = eyedropper (hold),
 * secondary = undo (click). Assignments are not configurable yet — the stylus settings screen will
 * make them so; the mapping itself is pure, so it is unit-tested on the JVM.
 */
object PenButtons {
    /** Mouse buttons that report the primary pen button: some drivers send a pen as a mouse. */
    private val PRIMARY = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY
    /** Mouse buttons that report the secondary pen button of such a driver. */
    private val SECONDARY = MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_TERTIARY

    /**
     * Action of the buttons in [buttonState] (`MotionEvent.getButtonState()`) for a pointer of
     * [toolType]. Only a pen has buttons: for drivers in mouse mode with `TOOL_TYPE_STYLUS`
     * BUTTON_SECONDARY is the primary button and BUTTON_TERTIARY the secondary one.
     */
    fun action(toolType: Int, buttonState: Int): PenAction = when {
        toolType != MotionEvent.TOOL_TYPE_STYLUS -> PenAction.NONE
        buttonState and PRIMARY != 0 -> PenAction.EYEDROPPER
        buttonState and SECONDARY != 0 -> PenAction.UNDO
        else -> PenAction.NONE
    }

    /** The hold action armed by [buttonState]: the one that must not fire while the button is down. */
    fun holdAction(toolType: Int, buttonState: Int): PenAction =
        action(toolType, buttonState).takeIf { it == PenAction.EYEDROPPER } ?: PenAction.NONE

    /** The click action fired by a button press with [buttonState]; [PenAction.NONE] for hold actions. */
    fun clickAction(toolType: Int, buttonState: Int): PenAction =
        action(toolType, buttonState).takeIf { it == PenAction.UNDO } ?: PenAction.NONE

    /** True for the back of the pen (TOOL_TYPE_ERASER): such a stroke is drawn in Erase mode. */
    fun isEraserEnd(toolType: Int): Boolean = toolType == MotionEvent.TOOL_TYPE_ERASER
}
