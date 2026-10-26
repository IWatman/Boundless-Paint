package app.painter.render

import app.painter.core.stroke.InputPoint

/** Receives canvas input already resolved into strokes and gestures. Coordinates are document pixels. */
interface CanvasInputListener {
    fun onStrokeBegin(p: InputPoint)
    fun onStrokeMove(points: List<InputPoint>)
    fun onStrokeEnd()
    fun onStrokeCancel()
    fun onUndoGesture()
    fun onRedoGesture()
    fun onLongPress(docX: Float, docY: Float)
}
