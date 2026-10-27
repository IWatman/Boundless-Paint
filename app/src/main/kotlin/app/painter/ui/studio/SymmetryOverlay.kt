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
import app.painter.core.symmetry.MirrorAxes
import app.painter.core.symmetry.SymmetryType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Distance of the rotate handle from the centre, in screen dp. */
private const val ROTATE_HANDLE_DP = 120f

/** Touch box of a handle in screen dp (larger than the drawn circle). */
private val HandleTouch = 48.dp

/** Alpha of the guide lines drawn over the canvas. */
private const val AXIS_ALPHA = 0.6f

/**
 * Compose layer above the GL canvas: draws the axes of [StudioUiState.symmetry] through its centre and, unless
 * the symmetry is locked, a round centre handle plus a rotate handle on the main axis 120 dp away from it.
 * Only the two 48 dp touch boxes take input, so touches anywhere else still reach the canvas.
 */
@Composable
fun SymmetryOverlay(state: StudioUiState, actions: StudioActions, modifier: Modifier = Modifier) {
    val sym = state.symmetry ?: return
    val t = state.canvasTransform
    val accent = MaterialTheme.colorScheme.primary
    val center = docToScreen(t, sym.centerX, sym.centerY)
    val rotate = with(LocalDensity.current) {
        val d = ROTATE_HANDLE_DP.dp.toPx()
        val rad = Math.toRadians(sym.angleDeg.toDouble())
        Offset(center.x + (cos(rad) * d).toFloat(), center.y + (sin(rad) * d).toFloat())
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val reach = size.width + size.height
            val stroke = 1.dp.toPx()
            val line = accent.copy(alpha = AXIS_ALPHA)
            for (axis in axisLines(sym)) {
                val rad = Math.toRadians(axis.deg.toDouble())
                val dx = cos(rad).toFloat()
                val dy = sin(rad).toFloat()
                val from = if (axis.ray) center else Offset(center.x - dx * reach, center.y - dy * reach)
                drawLine(line, from, Offset(center.x + dx * reach, center.y + dy * reach), strokeWidth = stroke)
            }
            if (!sym.locked) {
                drawHandle(center, accent)
                drawHandle(rotate, accent)
            }
        }
        if (!sym.locked) {
            // The centre handle drags the centre; the rotate handle turns the main axis to the finger direction.
            DragHandle(center) { p ->
                val d = screenToDoc(t, p.x, p.y)
                actions.moveSymmetryCenter(d.x, d.y)
            }
            DragHandle(rotate) { p ->
                val d = screenToDoc(t, p.x, p.y)
                val deg = Math.toDegrees(atan2((d.y - sym.centerY).toDouble(), (d.x - sym.centerX).toDouble()))
                actions.setSymmetryAngle(deg.toFloat())
            }
        }
    }
}

/** One overlay axis: [deg] in screen degrees, [ray] when it starts at the centre instead of crossing it. */
private class AxisLine(val deg: Float, val ray: Boolean)

/**
 * Axes to draw for [sym]: a line per mirror axis (V at [SymmetryUi.angleDeg], H perpendicular to it), N rays of
 * Radial from the centre and N full lines of Kaleido.
 */
private fun axisLines(sym: SymmetryUi): List<AxisLine> = when (sym.type) {
    SymmetryType.MIRROR -> when (sym.axes) {
        MirrorAxes.V -> listOf(AxisLine(sym.angleDeg, false))
        MirrorAxes.H -> listOf(AxisLine(sym.angleDeg - 90f, false))
        MirrorAxes.VH -> listOf(AxisLine(sym.angleDeg, false), AxisLine(sym.angleDeg - 90f, false))
    }
    SymmetryType.RADIAL -> {
        val n = sym.planes.coerceAtLeast(2)
        List(n) { k -> AxisLine(sym.angleDeg + k * 360f / n, true) }
    }
    SymmetryType.KALEIDO -> {
        val n = sym.planes.coerceAtLeast(2)
        List(n) { k -> AxisLine(sym.angleDeg + k * 180f / n, false) }
    }
}

/** Round handle: white fill with an [accent] border. */
private fun DrawScope.drawHandle(at: Offset, accent: Color) {
    val r = 7.dp.toPx()
    drawCircle(Color.White, radius = r, center = at)
    drawCircle(accent, radius = r, center = at, style = Stroke(width = 1.5.dp.toPx()))
}

/**
 * Invisible [HandleTouch] square centred on [at] (screen pixels) that reports the absolute finger position while
 * it is dragged. The square itself is [size] dp; nothing else of the overlay takes touches.
 */
@Composable
private fun DragHandle(at: Offset, onDrag: (Offset) -> Unit) {
    val half = with(LocalDensity.current) { HandleTouch.toPx() / 2f }
    val latestAt by rememberUpdatedState(at)
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
