package app.painter.render

import app.painter.core.stroke.InputPoint

/** Receives canvas input already resolved into strokes and gestures. Coordinates are document pixels. */
interface CanvasInputListener {
    /** Starts a stroke at [p]; [deviceDescriptor] is `InputDevice.descriptor` of the device that started it. */
    fun onStrokeBegin(p: InputPoint, deviceDescriptor: String = DEVICE_DEFAULT)
    fun onStrokeMove(points: List<InputPoint>)
    fun onStrokeEnd()
    fun onStrokeCancel()
    fun onUndoGesture()
    fun onRedoGesture()
    fun onLongPress(docX: Float, docY: Float)

    /**
     * True while a tool (e.g. a selection tool) wants raw single-pointer input in document
     * coordinates: [GestureRouter] then calls [onToolDown]/[onToolMove]/[onToolUp] instead of
     * building strokes. Multi-pointer navigation and undo/redo taps are unaffected.
     */
    val toolActive: Boolean get() = false

    /** Single pointer pressed at document position ([x], [y]). */
    fun onToolDown(x: Float, y: Float) {}

    fun onToolMove(x: Float, y: Float) {}

    fun onToolUp(x: Float, y: Float) {}

    /** The tool drag was aborted (second finger, system cancel). */
    fun onToolCancel() {}

    companion object {
        /** Descriptor used when the event carries no [android.view.InputDevice] (mouse, synthetic input). */
        const val DEVICE_DEFAULT = "default"
    }
}
