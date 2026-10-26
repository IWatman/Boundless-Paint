package app.painter.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Invisible slider attached to a button. A drag along the axis (up/right increases) changes the
 * value; [sensitivity] is the drag distance covering the whole [valueRange]. A tap without
 * movement calls [onTap]. Live values are reported through [onValueChange].
 */
fun Modifier.virtualSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    vertical: Boolean = true,
    sensitivity: Dp = 240.dp,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onTap: (() -> Unit)? = null,
): Modifier = composed {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnStart by rememberUpdatedState(onDragStart)
    val currentOnEnd by rememberUpdatedState(onDragEnd)
    val currentOnTap by rememberUpdatedState(onTap)
    pointerInput(vertical, valueRange, sensitivity) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            val spanPx = sensitivity.toPx().coerceAtLeast(1f)
            val span = valueRange.endInclusive - valueRange.start
            var dragging = false
            var origin = Offset.Zero
            var startValue = 0f
            var finished = false
            while (!finished) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    if (dragging) currentOnEnd()
                    break
                }
                if (!change.pressed) {
                    change.consume()
                    if (dragging) currentOnEnd() else currentOnTap?.invoke()
                    finished = true
                    continue
                }
                if (!dragging) {
                    val total = change.position - down.position
                    val along = if (vertical) abs(total.y) else abs(total.x)
                    if (along > slop) {
                        dragging = true
                        origin = change.position
                        startValue = currentValue
                        currentOnStart()
                    }
                }
                if (dragging) {
                    val d = change.position - origin
                    val delta = if (vertical) -d.y else d.x
                    currentOnChange((startValue + delta / spanPx * span).coerceIn(valueRange))
                    change.consume()
                }
            }
        }
    }
}
