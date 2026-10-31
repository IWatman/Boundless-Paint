package app.painter.render

import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import app.painter.core.stroke.InputPoint
import app.painter.studio.StylusSettings
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
 * - The dot of a still finger tap is drawn [DOUBLE_TAP_MS] after the finger lifts: a second tap within
 *   that window and [DOUBLE_TAP_SLOP_PX] of the first is a double tap, which fits the canvas
 *   (spec/tools/m0-m1-tools.md T1) and draws no dot.
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
 * - Pen buttons (spec/tools/m2-brush.md §4.3, [PenButtons]): while the primary button is held a pen touch
 *   samples the colour ([CanvasInputListener.onPickColor]) instead of drawing; a click of the secondary
 *   button undoes. The back of the pen (TOOL_TYPE_ERASER) starts an eraser stroke
 *   ([CanvasInputListener.onStrokeBegin] with `penEraser`). The actions assigned in [stylusSettings] replace
 *   those defaults, and a button assigned to erase arms the same temporary Erase mode.
 * - Finger and palm (spec §4.3, [FingerGate]): once a pen was seen in the session, [StylusSettings.palm] and
 *   [StylusSettings.fingerWithStylus] turn a finger into navigation only, and HOVER_REJECT also rejects finger
 *   touches while the pen hovers and 300 ms after.
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

    /**
     * True when a double tap with one finger fits the canvas (spec/tools/m0-m1-tools.md T1). Turned off by
     * the studio's view settings; a stylus is never affected, its taps always draw.
     */
    var doubleTapFits: Boolean = true

    /**
     * Stylus settings of the session (spec/tools/m2-brush.md §4.3): the pen button assignments, the finger
     * rule and the palm mode. The studio keeps it in step with its own settings flow.
     */
    var stylusSettings: StylusSettings = StylusSettings()
        set(value) {
            field = value
            penButtons = PenButtons(value.primaryAction, value.secondaryAction)
        }

    /** Mapping of the pen buttons, rebuilt whenever [stylusSettings] changes. */
    private var penButtons = PenButtons()

    /** True once a pen (tip, back of the pen or hover) was seen in this session: the finger rule applies then. */
    private var penSeen = false
    /** True while the pen is in the air above the screen, i.e. between ACTION_HOVER_ENTER and _EXIT. */
    private var penHovering = false

    /**
     * Pen buttons held in hover, as the hover events report them. Android 14 drops ACTION_BUTTON_PRESS/RELEASE
     * of a touchscreen pen that is not down ("pointer is not down"), so a click in hover shows up only as a
     * change of the hover events' button state.
     */
    private var hoverButtons = 0
    /** `eventTime` the pen last left hover, −1 while that never happened (see [FingerGate.HOVER_REJECT_MS]). */
    private var hoverLeftAt = -1L

    private enum class State { IDLE, PENDING, STROKE, PICK, TOOL, NAV, IGNORE }

    private var state = State.IDLE
    private val predictor: MotionEventPredictor? by lazy {
        runCatching { MotionEventPredictor.newInstance(view) }.getOrNull()
    }

    private var gestureStart = 0L
    private var maxPointers = 0
    private val downX = FloatArray(MAX_IDS)
    private val downY = FloatArray(MAX_IDS)

    private var strokeId = -1
    /** `InputDevice.descriptor` of the device that started the current stroke, see [CanvasInputListener]. */
    private var strokeDescriptor = CanvasInputListener.DEVICE_DEFAULT
    private var strokeStart = 0L
    private var strokeMaxMove = 0f
    private val pending = ArrayList<InputPoint>()
    private val docTmp = FloatArray(2)

    /** Hold action armed by a stylus button press, [PenAction.NONE] while no such button is down. */
    private var penHold = PenAction.NONE
    /** `eventTime` of the last button click, so a touch with the same button held does not fire it twice. */
    private var lastPenClick = 0L
    /** `eventTime` of the last eyedropper sample; samples of a drag are throttled to [PICK_INTERVAL_MS]. */
    private var lastPick = 0L

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

    /**
     * Points of a still finger tap whose dot is not drawn yet: it waits [DOUBLE_TAP_MS], so the second tap of
     * a double tap can fit the canvas instead. Empty while no dot is pending.
     */
    private var tapPoints: List<InputPoint> = emptyList()
    private var tapDescriptor = CanvasInputListener.DEVICE_DEFAULT
    private var tapUpTime = 0L
    private var tapUpX = 0f
    private var tapUpY = 0f
    private val tapDotRunnable = Runnable { commitTapDot() }

    /** Draws the dot of a still finger tap once its double-tap window has run out. */
    private fun commitTapDot() {
        view.removeCallbacks(tapDotRunnable)
        val pts = tapPoints
        tapPoints = emptyList()
        val l = host.listener ?: return
        if (pts.isEmpty()) return
        l.onStrokeBegin(pts[0], tapDescriptor)
        if (pts.size > 1) l.onStrokeMove(ArrayList(pts.subList(1, pts.size)))
        l.onStrokeEnd()
        traceStroke("end(tap)")
    }

    /** Drops a pending tap dot: the tap turned out to be the first half of a double tap. */
    private fun dropTapDot() {
        view.removeCallbacks(tapDotRunnable)
        tapPoints = emptyList()
    }

    /**
     * Fits the canvas when a finger touches down within [DOUBLE_TAP_MS] and [DOUBLE_TAP_SLOP_PX] of a tap whose
     * dot is still pending; the whole gesture is then ignored, so the double tap draws nothing.
     */
    private fun consumeDoubleTap(e: MotionEvent): Boolean {
        if (!doubleTapFits || tapPoints.isEmpty()) return false
        if (e.eventTime - tapUpTime > DOUBLE_TAP_MS) return false
        if (hypot(e.x - tapUpX, e.y - tapUpY) > DOUBLE_TAP_SLOP_PX) return false
        dropTapDot()
        state = State.IGNORE
        trace("fit(double tap)")
        host.requestFit()
        return true
    }

    /** Handles one touch event; always consumes it. */
    fun onTouchEvent(e: MotionEvent): Boolean {
        // Hover and button events arrive here too (CanvasView.onPenInAir); the predictor
        // only takes the touch stream.
        val touch = e.actionMasked !in HOVER_OR_BUTTON
        if (predictionEnabled && touch) predictor?.record(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> onUp(e)
            MotionEvent.ACTION_CANCEL -> onCancel()
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> onHover(e, true)
            MotionEvent.ACTION_HOVER_EXIT -> onHover(e, false)
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> onPenButton(e)
        }
        return true
    }

    /** Aborts any gesture in progress (cancels an active stroke). */
    fun reset() = onCancel()

    /**
     * Pen hover ([hovering] = ACTION_HOVER_ENTER/MOVE, else ACTION_HOVER_EXIT): only the palm rule of
     * [FingerGate] needs it, a hovering pen never starts a stroke. Hover also counts as "the pen was seen",
     * the stylus section of the settings appears after the first stylus/eraser event (§4.3).
     */
    private fun onHover(e: MotionEvent, hovering: Boolean) {
        val tool = e.getToolType(0)
        if (!isPen(tool)) return
        penSeen = true
        if (hovering != penHovering) trace("pen hover ${if (hovering) "enter" else "exit"}")
        penHovering = hovering
        if (!hovering) hoverLeftAt = e.eventTime
        val buttons = if (hovering) e.buttonState else 0
        if (buttons != hoverButtons) onHoverButtons(tool, buttons, e.eventTime)
    }

    /** Pen buttons of a hovering pen changed to [buttons]: arms the hold action and fires the click of a new press. */
    private fun onHoverButtons(tool: Int, buttons: Int, time: Long) {
        val pressed = buttons and hoverButtons.inv()
        hoverButtons = buttons
        penHold = penButtons.holdAction(tool, buttons)
        if (pressed == 0) return
        trace("pen button press in hover state=$buttons")
        val click = penButtons.clickAction(tool, pressed)
        if (click != PenAction.NONE && state == State.IDLE) fireClick(click, time)
    }

    private fun isPen(tool: Int): Boolean =
        tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    private fun onDown(e: MotionEvent) {
        view.removeCallbacks(longPressRunnable)
        val downTool = e.getToolType(0)
        val downIsFinger = downTool == MotionEvent.TOOL_TYPE_FINGER || downTool == MotionEvent.TOOL_TYPE_UNKNOWN
        // A second finger tap of a double tap fits the canvas and draws nothing; the pen is never affected.
        if (downIsFinger && consumeDoubleTap(e)) return
        // Any other touch lands a tap dot that is still waiting for its double-tap window.
        commitTapDot()
        pending.clear()
        extraId = -1
        navActive = false
        trCount = 0; trMinP = 1f; trMaxP = 0f; trMaxTilt = 0f
        gestureStart = e.eventTime
        maxPointers = 1
        recordDown(e, 0)
        strokeId = e.getPointerId(0)
        strokeDescriptor = e.device?.descriptor ?: CanvasInputListener.DEVICE_DEFAULT
        strokeStart = e.eventTime
        strokeMaxMove = 0f
        val tool = downTool
        if (host.listener?.toolActive == true) {
            // Selection-style tool: raw single-pointer input in document coordinates.
            state = State.TOOL
            val p = point(e, 0, -1, false)
            host.listener?.onToolDown(p.x, p.y)
            return
        }
        if (isPen(tool)) {
            penSeen = true
            penHovering = false
        }
        val action = penAction(tool, e)
        if (action == PenAction.EYEDROPPER) {
            // Primary pen button held: the pen touch samples the colour instead of drawing (§4.3).
            state = State.PICK
            pick(e, 0)
            return
        }
        if (penClick(tool, e)) return
        if (tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_UNKNOWN) {
            // Finger and palm rule of §4.3: once the pen was seen the finger may only navigate.
            if (fingerMode(tool, e.eventTime) == FingerMode.NAVIGATE) {
                trace("finger navigate")
                enterNav(e)
                return
            }
            state = State.PENDING
            pending.add(point(e, 0, -1, false))
            view.postDelayed(longPressRunnable, LONG_PRESS_MS)
        } else {
            state = State.STROKE
            // The back of the pen and a button assigned to erase both draw in Erase for this stroke only.
            val erase = PenButtons.isEraserEnd(tool) || action == PenAction.ERASE
            host.listener?.onStrokeBegin(point(e, 0, -1, false), strokeDescriptor, erase)
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
            State.PICK -> enterNav(e)
            State.NAV -> reanchor(e, -1)
            State.TOOL -> { host.listener?.onToolCancel(); enterNav(e) }
            else -> Unit
        }
    }

    private fun onMove(e: MotionEvent) {
        if (state == State.PICK) {
            // Dragging with the primary pen button held keeps sampling the colour.
            val idx = e.findPointerIndex(strokeId)
            if (idx >= 0 && e.eventTime - lastPick >= PICK_INTERVAL_MS) pick(e, idx)
            return
        }
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
                        l.onStrokeBegin(pending[0], strokeDescriptor)
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
                // The dot is deferred by [DOUBLE_TAP_MS]: a second tap in that window fits instead (see onDown).
                val pts = ArrayList(pending)
                pending.clear()
                if (pts.isNotEmpty() && l != null) {
                    tapPoints = pts
                    tapDescriptor = strokeDescriptor
                    tapUpTime = e.eventTime
                    tapUpX = e.x
                    tapUpY = e.y
                    view.postDelayed(tapDotRunnable, DOUBLE_TAP_MS)
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
        dropTapDot()
        if (state == State.STROKE) { traceStroke("cancel(system)"); host.listener?.onStrokeCancel() } else if (state == State.TOOL) {
            host.listener?.onToolCancel()
        }
        clear()
    }

    /**
     * Stylus button press/release (spec/tools/m2-brush.md §4.3), delivered in hover too: a hold action is
     * armed for the next touch, a click action fires at once.
     */
    private fun onPenButton(e: MotionEvent) {
        val pressed = e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
        val tool = when {
            e.pointerCount > 0 -> e.getToolType(0)
            // A pen in hover may carry no pointer: fall back to the device source (mouse mode included).
            e.isFromSource(InputDevice.SOURCE_STYLUS) || e.isFromSource(InputDevice.SOURCE_MOUSE) ->
                MotionEvent.TOOL_TYPE_STYLUS
            else -> MotionEvent.TOOL_TYPE_UNKNOWN
        }
        // Keep the hover tracking in step, so a driver that sends both events does not fire twice.
        hoverButtons = e.buttonState
        penHold = penButtons.holdAction(tool, e.buttonState)
        // On release the button is already gone from getButtonState(); actionButton names it.
        val mask = if (pressed) e.buttonState else e.buttonState or e.actionButton
        val click = penButtons.clickAction(tool, mask)
        if (pressed && click != PenAction.NONE && state == State.IDLE) fireClick(click, e.eventTime)
    }

    /**
     * Finger rule of §4.3 for a touch of [tool] at [time]: [FingerMode.NAVIGATE] locks the finger to
     * navigation, [FingerMode.DRAW] keeps today's stroke behaviour.
     */
    private fun fingerMode(tool: Int, time: Long): FingerMode =
        FingerGate.mode(
            stylusSettings,
            tool,
            penSeen,
            penHovering,
            if (hoverLeftAt < 0) -1L else time - hoverLeftAt,
        )

    /** Hold action of pointer 0 in [e]: its own button state now, or a button pressed earlier in hover. */
    private fun penAction(tool: Int, e: MotionEvent): PenAction {
        val now = penButtons.action(tool, e.buttonState)
        if (now != PenAction.NONE) return now
        return if (tool == MotionEvent.TOOL_TYPE_STYLUS) penHold else PenAction.NONE
    }

    /**
     * Runs a click action of a pen button that is down while the pen touches the screen (drivers without
     * hover button events); true when the touch is consumed and no stroke starts.
     */
    private fun penClick(tool: Int, e: MotionEvent): Boolean {
        val click = penButtons.clickAction(tool, e.buttonState)
        if (click == PenAction.NONE) return false
        state = State.IGNORE
        // A touch right after the same button fired in hover must not undo twice.
        if (e.eventTime - lastPenClick > CLICK_DEBOUNCE_MS) fireClick(click, e.eventTime)
        return true
    }

    /** Fires a click action of a pen button: undo, redo or the brush browser (§4.3 "Button actions"). */
    private fun fireClick(click: PenAction, time: Long) {
        lastPenClick = time
        trace("pen button ${click.name}")
        when (click) {
            PenAction.UNDO -> host.listener?.onUndoGesture()
            PenAction.REDO -> host.listener?.onRedoGesture()
            PenAction.BRUSH_BROWSER -> host.listener?.onBrushBrowserToggle()
            else -> Unit
        }
    }

    /** Samples the colour under pointer [idx] with the studio eyedropper; samples of a drag are throttled. */
    private fun pick(e: MotionEvent, idx: Int) {
        lastPick = e.eventTime
        val p = point(e, idx, -1, false)
        host.listener?.onPickColor(p.x, p.y)
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
        /** Delay between a still finger tap and its dot: a second tap within it is a double tap (fit). */
        const val DOUBLE_TAP_MS = 280L
        const val DOUBLE_TAP_SLOP_PX = 60f
        /** Pen-in-the-air actions that reach the router besides the touch stream. */
        val HOVER_OR_BUTTON = setOf(
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT,
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE,
        )
        /** Minimum interval between two eyedropper samples of one pen drag. */
        const val PICK_INTERVAL_MS = 40L
        /** Window after a pen button click in which a touch with the same button does not fire it again. */
        const val CLICK_DEBOUNCE_MS = 400L
        private const val MAX_IDS = 32
        private const val TRACE_TAG = "PainterInput"
    }
}
