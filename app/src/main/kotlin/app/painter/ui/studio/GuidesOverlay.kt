package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Touch box of a handle in screen dp (larger than the drawn circle). */
private val HandleTouch = 48.dp

/** Alpha of the infinite ruler axis drawn over the canvas. */
private const val AXIS_ALPHA = 0.6f

/**
 * Compose layer above the GL canvas: draws the ruler of [StudioUiState.ruler] as the infinite line through its
 * ends plus the segment itself and, unless the ruler is locked, three round handles — the ends (each moves one
 * end) and the midpoint (which moves the whole segment). Only the 48 dp touch boxes take input, so touches
 * anywhere else still reach the canvas.
 */
@Composable
fun GuidesOverlay(state: StudioUiState, actions: StudioActions, modifier: Modifier = Modifier) {
    val ruler = state.ruler ?: return
    val t = state.canvasTransform
    val accent = MaterialTheme.colorScheme.primary
    val a = docToScreen(t, ruler.ax, ruler.ay)
    val b = docToScreen(t, ruler.bx, ruler.by)
    val middle = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRuler(accent, a, b, showHandles = !ruler.locked)
        }
        if (!ruler.locked) {
            // The end handles follow the finger; the middle handle shifts both ends by the document-space delta.
            DragHandle(a) { p ->
                val d = screenToDoc(t, p.x, p.y)
                actions.moveRulerEnd(0, d.x, d.y)
            }
            DragHandle(b) { p ->
                val d = screenToDoc(t, p.x, p.y)
                actions.moveRulerEnd(1, d.x, d.y)
            }
            val last = remember { FloatArray(2) }
            DragHandle(
                at = middle,
                onStart = { p ->
                    val d = screenToDoc(t, p.x, p.y)
                    last[0] = d.x
                    last[1] = d.y
                },
            ) { p ->
                val d = screenToDoc(t, p.x, p.y)
                actions.moveRuler(d.x - last[0], d.y - last[1])
                last[0] = d.x
                last[1] = d.y
            }
        }
    }
}

/** Ruler axis past the screen edges, the segment [a]–[b] and, when [showHandles], the three handles. */
private fun DrawScope.drawRuler(accent: Color, a: Offset, b: Offset, showHandles: Boolean) {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = sqrt(dx * dx + dy * dy)
    if (len > 0.5f) {
        val reach = size.width + size.height
        val ux = dx / len
        val uy = dy / len
        drawLine(
            accent.copy(alpha = AXIS_ALPHA),
            Offset(a.x - ux * reach, a.y - uy * reach),
            Offset(b.x + ux * reach, b.y + uy * reach),
            strokeWidth = 1.dp.toPx(),
        )
    }
    drawLine(Color.White, a, b, strokeWidth = 3.dp.toPx())
    drawLine(accent, a, b, strokeWidth = 1.5.dp.toPx())
    if (showHandles) {
        drawHandle(a, accent)
        drawHandle(b, accent)
        drawHandle(Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f), accent)
    }
}

/** Round handle: white fill with an [accent] border. */
private fun DrawScope.drawHandle(at: Offset, accent: Color) {
    val r = 7.dp.toPx()
    drawCircle(Color.White, radius = r, center = at)
    drawCircle(accent, radius = r, center = at, style = Stroke(width = 1.5.dp.toPx()))
}

/**
 * Invisible [HandleTouch] square centred on [at] (screen pixels) that reports the absolute finger position
 * while it is dragged. [onStart] receives the finger position at the start of the drag; nothing else of the
 * overlay takes touches.
 */
@Composable
private fun DragHandle(at: Offset, onStart: (Offset) -> Unit = {}, onDrag: (Offset) -> Unit) {
    val half = with(LocalDensity.current) { HandleTouch.toPx() / 2f }
    val latestAt by rememberUpdatedState(at)
    val latestOnStart by rememberUpdatedState(onStart)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val finger = remember { FloatArray(2) }
    Box(
        Modifier
            .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
            .size(HandleTouch)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { local ->
                        finger[0] = latestAt.x - half + local.x
                        finger[1] = latestAt.y - half + local.y
                        latestOnStart(Offset(finger[0], finger[1]))
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        finger[0] += drag.x
                        finger[1] += drag.y
                        latestOnDrag(Offset(finger[0], finger[1]))
                    },
                )
            },
    )
}

/** Maps a document point to view pixels using the canvas transform (same convention as the selection overlay). */
private fun docToScreen(t: CanvasTransformUi, x: Float, y: Float): Offset {
    val rad = Math.toRadians(t.rotationDeg.toDouble())
    val a = (t.zoom * cos(rad)).toFloat()
    val b = (t.zoom * sin(rad)).toFloat()
    return Offset(a * x - b * y + t.tx, b * x + a * y + t.ty)
}

/** Inverse of [docToScreen]: view pixels back to document pixels. */
private fun screenToDoc(t: CanvasTransformUi, x: Float, y: Float): Offset {
    val rad = Math.toRadians(t.rotationDeg.toDouble())
    val a = (t.zoom * cos(rad)).toFloat()
    val b = (t.zoom * sin(rad)).toFloat()
    val det = a * a + b * b
    if (det < 1e-6f) return Offset.Zero
    val dx = x - t.tx
    val dy = y - t.ty
    return Offset((a * dx + b * dy) / det, (-b * dx + a * dy) / det)
}
