package app.painter.render

import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import app.painter.core.stroke.InputPoint
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns raw [MotionEvent]s into strokes (document coordinates) and navigation.
 *
 * - One pointer draws. A stylus/mouse stroke begins on touch-down. A finger stroke is deferred
 *   until the finger moves more than [FINGER_START_SLOP_PX]; a finger that stays still for
 *   [LONG_PRESS_MS] fires [CanvasInputListener.onLongPress] instead, and a quick still tap
 *   produces a dot stroke. Long-press is finger-only.
 * - A second finger within [CANCEL_WINDOW_MS] of stroke start (stroke moved less than
 *   [TAP_SLOP_PX]) cancels the stroke and starts navigation.
 * - Two pointers: pan + pinch-zoom + rotate around the centroid; rotation snaps to multiples of
 *   90° within ±3°. A quick pinch-in (zoom ratio < 0.8 within [TAP_MS]) fits the canvas.
 * - Two/three-finger taps (all down and up within [TAP_MS], movement < [TAP_SLOP_PX]) fire undo/redo.
 * - During a stroke, a tap of another finger cancels the stroke.
 * - While [CanvasInputListener.toolActive] is true, a single pointer drives the tool
 *   ([CanvasInputListener.onToolDown]/[onToolMove]/[onToolUp]) instead of a stroke; a second finger
 *   cancels the tool drag and navigates, and two/three-finger taps still undo/redo.
 *
 * Must be used on the main thread.
 */
class GestureRouter(private val view: View, private val host: Host) {

    /** Owner of the view transform and the listener. */
    interface Host {
        val listener: CanvasInputListener?
        val transform: ViewTransform
        fun onTransformChanged()
        fun requestFit()
    }

    /** Emits motion-predicted points (predicted = true) after the real samples of each move. */
    var predictionEnabled: Boolean = true

    private enum class State { IDLE, PENDING, STROKE, TOOL, NAV, IGNORE }

    private var state = State.IDLE
    private val predictor: MotionEventPredictor? by lazy {
        runCatching { MotionEventPredictor.newInstance(view) }.getOrNull()
    }

    private var gestureStart = 0L
    private var maxPointers = 0
    private val downX = FloatArray(MAX_IDS)
    private val downY = FloatArray(MAX_IDS)

    private var strokeId = -1
    private var strokeStart = 0L
    private var strokeMaxMove = 0f
    private val pending = ArrayList<InputPoint>()
    private val docTmp = FloatArray(2)

    private var extraId = -1
    private var extraDown = 0L
    private var extraValid = false

    private var navActive = false
    private var navStart = 0L
    private var navStartZoom = 1f
    private var tapValid = false
    private val savedTransform = ViewTransform()
    private var anchorDocX = 0f
    private var anchorDocY = 0f
    private var startDist = 1f
    private var startAngle = 0f
    private var startZoom = 1f
    private var startRawRot = 0f
    private var rawRot = 0f

    // Debug input trace (logcat tag PainterInput): per-stroke pressure/tilt range and gestures.
    private var trTool = 0
    private var trCount = 0
    private var trMinP = 1f
    private var trMaxP = 0f
    private var trMaxTilt = 0f

    private fun trace(msg: String) = Log.d(TRACE_TAG, msg)

    private fun traceStroke(end: String) {
        if (trCount > 0) trace("stroke $end tool=$trTool samples=$trCount pressure=%.3f..%.3f maxTilt=%.2f".format(trMinP, trMaxP, trMaxTilt))
        trCount = 0; trMinP = 1f; trMaxP = 0f; trMaxTilt = 0f
    }

    /**
     * Ends the stroke, or cancels it when a stylus never registered pressure: pens emit short
     * zero-pressure contacts on lift-off that would otherwise become empty undo steps.
     */
    private fun finishStroke() {
        val stylus = trTool == MotionEvent.TOOL_TYPE_STYLUS || trTool == MotionEvent.TOOL_TYPE_ERASER
        if (stylus && trCount > 0 && trMaxP <= 0f) {
            traceStroke("drop(zero pressure)")
            host.listener?.onStrokeCancel()
        } else {
            traceStroke("end")
            host.listener?.onStrokeEnd()
        }
    }

    private val longPressRunnable = Runnable {
        if (state == State.PENDING) {
            trace("longPress")
            state = State.IGNORE
            val p = pending.firstOrNull()
            pending.clear()
            if (p != null) host.listener?.onLongPress(p.x, p.y)
        }
    }

    /** Handles one touch event; always consumes it. */
    fun onTouchEvent(e: MotionEvent): Boolean {
        if (predictionEnabled) predictor?.record(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> onUp(e)
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }

    /** Aborts any gesture in progress (cancels an active stroke). */
    fun reset() = onCancel()

    private fun onDown(e: MotionEvent) {
        view.removeCallbacks(longPressRunnable)
        pending.clear()
        extraId = -1
        navActive = false
        trCount = 0; trMinP = 1f; trMaxP = 0f; trMaxTilt = 0f
        gestureStart = e.eventTime
        maxPointers = 1
        recordDown(e, 0)
        strokeId = e.getPointerId(0)
        strokeStart = e.eventTime
        strokeMaxMove = 0f
        val tool = e.getToolType(0)
        if (host.listener?.toolActive == true) {
            // Selection-style tool: raw single-pointer input in document coordinates.
            state = State.TOOL
            val p = point(e, 0, -1, false)
            host.listener?.onToolDown(p.x, p.y)
            return
        }
        if (tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_UNKNOWN) {
            state = State.PENDING
            pending.add(point(e, 0, -1, false))
            view.postDelayed(longPressRunnable, LONG_PRESS_MS)
        } else {
            state = State.STROKE
            host.listener?.onStrokeBegin(point(e, 0, -1, false))
        }
    }

    private fun onPointerDown(e: MotionEvent) {
        val idx = e.actionIndex
        recordDown(e, idx)
        maxPointers = max(maxPointers, e.pointerCount)
        when (state) {
            State.PENDING -> {
                view.removeCallbacks(longPressRunnable)
                pending.clear()
                enterNav(e)
            }
            State.STROKE -> {
                if (e.eventTime - strokeStart < CANCEL_WINDOW_MS && strokeMaxMove < TAP_SLOP_PX) {
                    traceStroke("cancel(2nd pointer)")
                    host.listener?.onStrokeCancel()
                    enterNav(e)
                } else if (extraId < 0) {
                    extraId = e.getPointerId(idx)
                    extraDown = e.eventTime
                    extraValid = true
                }
            }
            State.NAV -> reanchor(e, -1)
            State.TOOL -> { host.listener?.onToolCancel(); enterNav(e) }
            else -> Unit
        }
    }

    private fun onMove(e: MotionEvent) {
        when (state) {
            State.PENDING -> {
                val idx = e.findPointerIndex(strokeId)
                if (idx < 0) return
                appendSamples(e, idx, pending)
                val moved = movedFromDown(e, idx)
                strokeMaxMove = max(strokeMaxMove, moved)
                if (moved > FINGER_START_SLOP_PX) {
                    view.removeCallbacks(longPressRunnable)
                    state = State.STROKE
                    strokeStart = e.eventTime
                    val l = host.listener
                    if (l != null) {
                        l.onStrokeBegin(pending[0])
                        if (pending.size > 1) l.onStrokeMove(ArrayList(pending.subList(1, pending.size)))
                    }
                    pending.clear()
                }
            }
            State.STROKE -> {
                val idx = e.findPointerIndex(strokeId)
                if (idx >= 0) {
                    strokeMaxMove = max(strokeMaxMove, movedFromDown(e, idx))
                    val pts = ArrayList<InputPoint>(e.historySize + 4)
                    appendSamples(e, idx, pts)
                    if (predictionEnabled) appendPredicted(pts)
                    host.listener?.onStrokeMove(pts)
                }
                if (extraId >= 0 && extraValid) {
                    val xi = e.findPointerIndex(extraId)
                    if (xi >= 0 && movedFromDown(e, xi) > TAP_SLOP_PX) extraValid = false
                }
            }
            State.NAV -> {
                if (tapValid) {
                    for (i in 0 until e.pointerCount) {
                        if (movedFromDown(e, i) > TAP_SLOP_PX) { tapValid = false; break }
                    }
                }
                if (navActive && e.pointerCount == 2) applyNav(e)
            }
            State.TOOL -> { val ti = e.findPointerIndex(strokeId)
                if (ti >= 0) { val p = point(e, ti, -1, false); host.listener?.onToolMove(p.x, p.y) }

            }
            else -> Unit
        }
    }

    private fun onPointerUp(e: MotionEvent) {
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        when (state) {
            State.STROKE -> {
                if (id == strokeId) {
                    finishStroke()
                    state = State.IGNORE
                } else if (id == extraId) {
                    if (extraValid && e.eventTime - extraDown < TAP_MS) {
                        traceStroke("cancel(tap)")
                        host.listener?.onStrokeCancel()
                        state = State.IGNORE
                    }
                    extraId = -1
                }
            }
            State.NAV -> {
                val remaining = e.pointerCount - 1
                if (remaining < 2 && navActive && !tapValid &&
                    e.eventTime - navStart < TAP_MS &&
                    host.transform.zoom / navStartZoom < QUICK_PINCH_RATIO
                ) {
                    navActive = false
                    state = State.IGNORE
                    trace("fit(quick pinch)")
                    host.requestFit()
                    return
                }
                reanchor(e, idx)
            }
            else -> Unit
        }
    }

    private fun onUp(e: MotionEvent) {
        view.removeCallbacks(longPressRunnable)
        val l = host.listener
        when (state) {
            State.PENDING -> {
                if (pending.isNotEmpty() && l != null) {
                    l.onStrokeBegin(pending[0])
                    if (pending.size > 1) l.onStrokeMove(ArrayList(pending.subList(1, pending.size)))
                    l.onStrokeEnd()
                    traceStroke("end(tap)")
                }
            }
            State.STROKE -> {
                val idx = e.findPointerIndex(strokeId)
                if (idx >= 0 && l != null) {
                    val pts = ArrayList<InputPoint>(e.historySize + 1)
                    appendSamples(e, idx, pts)
                    if (pts.isNotEmpty()) l.onStrokeMove(pts)
                }
                finishStroke()
            }
            State.NAV -> {
                val t = host.transform
                trace("nav end pointers=$maxPointers tap=$tapValid zoom=%.3f rot=%.1f".format(t.zoom, t.rotationDeg))
                if (tapValid && e.eventTime - gestureStart < TAP_MS) {
                    host.transform.set(savedTransform)
                    host.onTransformChanged()
                    when (maxPointers) {
                        2 -> { trace("undo gesture"); l?.onUndoGesture() }
                        3 -> { trace("redo gesture"); l?.onRedoGesture() }
                    }
                }
            }
            State.TOOL -> { val ti = e.findPointerIndex(strokeId)
                if (ti >= 0) { val p = point(e, ti, -1, false); l?.onToolUp(p.x, p.y) }

            }
            else -> Unit
        }
        clear()
    }

    private fun onCancel() {
        view.removeCallbacks(longPressRunnable)
        if (state == State.STROKE) { traceStroke("cancel(system)"); host.listener?.onStrokeCancel() } else if (state == State.TOOL) {
            host.listener?.onToolCancel()
        }
        clear()
    }

    private fun clear() {
        state = State.IDLE
        pending.clear()
        strokeId = -1
        extraId = -1
        navActive = false
    }

    private fun enterNav(e: MotionEvent) {
        trace("nav start")
        state = State.NAV
        navStart = e.eventTime
        navStartZoom = host.transform.zoom
        tapValid = true
        savedTransform.set(host.transform)
        rawRot = host.transform.rotationDeg
        reanchor(e, -1)
    }

    /** Re-captures the navigation baseline from the pointers that remain (excluding [excludeIdx]). */
    private fun reanchor(e: MotionEvent, excludeIdx: Int) {
        var i0 = -1
        var i1 = -1
        var count = 0
        for (i in 0 until e.pointerCount) {
            if (i == excludeIdx) continue
            count++
            if (i0 < 0) i0 = i else if (i1 < 0) i1 = i
        }
        if (count != 2) {
            navActive = false
            return
        }
        navActive = true
        val x0 = e.getX(i0); val y0 = e.getY(i0)
        val x1 = e.getX(i1); val y1 = e.getY(i1)
        host.transform.screenToDoc((x0 + x1) * 0.5f, (y0 + y1) * 0.5f, docTmp)
        anchorDocX = docTmp[0]
        anchorDocY = docTmp[1]
        startDist = max(1f, hypot(x1 - x0, y1 - y0))
        startAngle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        startZoom = host.transform.zoom
        startRawRot = rawRot
    }

    private fun applyNav(e: MotionEvent) {
        val x0 = e.getX(0); val y0 = e.getY(0)
        val x1 = e.getX(1); val y1 = e.getY(1)
        val dist = max(1f, hypot(x1 - x0, y1 - y0))
        val angle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        rawRot = startRawRot + ViewTransform.normalizeDeg(angle - startAngle)
        host.transform.placeDocPoint(
            anchorDocX, anchorDocY,
            (x0 + x1) * 0.5f, (y0 + y1) * 0.5f,
            startZoom * dist / startDist,
            ViewTransform.snapRotation(rawRot),
        )
        host.onTransformChanged()
    }

    private fun recordDown(e: MotionEvent, idx: Int) {
        val id = e.getPointerId(idx)
        if (id in 0 until MAX_IDS) {
            downX[id] = e.getX(idx)
            downY[id] = e.getY(idx)
        }
    }

    private fun movedFromDown(e: MotionEvent, idx: Int): Float {
        val id = e.getPointerId(idx)
        if (id !in 0 until MAX_IDS) return 0f
        return hypot(e.getX(idx) - downX[id], e.getY(idx) - downY[id])
    }

    private fun appendSamples(e: MotionEvent, idx: Int, out: MutableList<InputPoint>, predicted: Boolean = false) {
        for (h in 0 until e.historySize) out.add(point(e, idx, h, predicted))
        out.add(point(e, idx, -1, predicted))
    }

    private fun appendPredicted(out: MutableList<InputPoint>) {
        val pe = predictor?.predict() ?: return
        try {
            var idx = pe.findPointerIndex(strokeId)
            if (idx < 0) idx = 0
            if (pe.pointerCount > idx) appendSamples(pe, idx, out, predicted = true)
        } finally {
            pe.recycle()
        }
    }

    /** Converts sample [h] (−1 = current) of pointer [idx] to a document-space [InputPoint]. */
    private fun point(e: MotionEvent, idx: Int, h: Int, predicted: Boolean): InputPoint {
        val cur = h < 0
        val sx = if (cur) e.getX(idx) else e.getHistoricalX(idx, h)
        val sy = if (cur) e.getY(idx) else e.getHistoricalY(idx, h)
        val tool = e.getToolType(idx)
        val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        val pressure = if (stylus) {
            (if (cur) e.getPressure(idx) else e.getHistoricalPressure(idx, h)).coerceIn(0f, 1f)
        } else {
            1f
        }
        val tilt = if (stylus) {
            if (cur) e.getAxisValue(MotionEvent.AXIS_TILT, idx)
            else e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h)
        } else {
            0f
        }
        val orientation = if (cur) e.getAxisValue(MotionEvent.AXIS_ORIENTATION, idx)
        else e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, idx, h)
        if (!predicted) {
            trTool = tool; trCount++
            if (pressure < trMinP) trMinP = pressure
            if (pressure > trMaxP) trMaxP = pressure
            if (tilt > trMaxTilt) trMaxTilt = tilt
        }
        val t = host.transform
        t.screenToDoc(sx, sy, docTmp)
        return InputPoint(
            x = docTmp[0],
            y = docTmp[1],
            pressure = pressure,
            tilt = tilt,
            azimuth = orientation - Math.toRadians(t.rotationDeg.toDouble()).toFloat(),
            timeMs = if (cur) e.eventTime else e.getHistoricalEventTime(h),
            isStylus = stylus,
            predicted = predicted,
        )
    }

    companion object {
        const val LONG_PRESS_MS = 600L
        const val CANCEL_WINDOW_MS = 150L
        const val TAP_MS = 250L
        const val TAP_SLOP_PX = 20f
        const val FINGER_START_SLOP_PX = 4f
        const val QUICK_PINCH_RATIO = 0.8f
        private const val MAX_IDS = 32
        private const val TRACE_TAG = "PainterInput"
    }
}
