package app.painter.render

import android.view.MotionEvent

/** Action a stylus button is bound to (spec/tools/m2-brush.md §4.3). */
enum class PenAction(
    /** True for an action that lasts while the button is held, false for one that fires on the press. */
    val hold: Boolean,
) {
    /** No action for this button state. */
    NONE(false),

    /** Held: a pen touch samples the colour under the tip instead of drawing. */
    EYEDROPPER(true),

    /** Held: a pen touch draws with the Erase mode until the button is released. */
    ERASE(true),

    /** Click: one undo. */
    UNDO(false),

    /** Click: one redo. */
    REDO(false),

    /** Click: opens or closes the brush browser. */
    BRUSH_BROWSER(false),
}

/**
 * Mapping of the two pen buttons to the actions the user assigned (spec/tools/m2-brush.md §4.3 "Button
 * actions"). The defaults are the spec's decision: primary = eyedropper (hold), secondary = undo (click).
 * The mapping is pure, so it is unit-tested on the JVM; [GestureRouter] builds one from the
 * [app.painter.studio.StylusSettings] of the session.
 */
class PenButtons(
    /** Action of the primary (lower) pen button. */
    val primary: PenAction = PenAction.EYEDROPPER,
    /** Action of the secondary (upper) pen button. */
    val secondary: PenAction = PenAction.UNDO,
) {
    /**
     * Action of the buttons in [buttonState] (`MotionEvent.getButtonState()`) for a pointer of
     * [toolType]. Only a pen has buttons: for drivers in mouse mode with `TOOL_TYPE_STYLUS`
     * BUTTON_SECONDARY is the primary button and BUTTON_TERTIARY the secondary one.
     */
    fun action(toolType: Int, buttonState: Int): PenAction = when {
        toolType != MotionEvent.TOOL_TYPE_STYLUS -> PenAction.NONE
        buttonState and PRIMARY != 0 -> primary
        buttonState and SECONDARY != 0 -> secondary
        else -> PenAction.NONE
    }

    /** The hold action armed by [buttonState]: the one that must not fire while the button is down. */
    fun holdAction(toolType: Int, buttonState: Int): PenAction =
        action(toolType, buttonState).takeIf { it.hold } ?: PenAction.NONE

    /** The click action fired by a button press with [buttonState]; [PenAction.NONE] for hold actions. */
    fun clickAction(toolType: Int, buttonState: Int): PenAction =
        action(toolType, buttonState).takeIf { it != PenAction.NONE && !it.hold } ?: PenAction.NONE

    companion object {
        /** Mouse buttons that report the primary pen button: some drivers send a pen as a mouse. */
        private val PRIMARY = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY
        /** Mouse buttons that report the secondary pen button of such a driver. */
        private val SECONDARY = MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_TERTIARY

        /** True for the back of the pen (TOOL_TYPE_ERASER): such a stroke is drawn in Erase mode. */
        fun isEraserEnd(toolType: Int): Boolean = toolType == MotionEvent.TOOL_TYPE_ERASER
    }
}
