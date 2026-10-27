package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compose layer above the GL canvas: draws the live selection rectangle while dragging and the
 * bounds of the current selection as a dashed black/white outline. Consumes no input.
 *
 * The mapping to screen pixels comes from [StudioUiState.canvasTransform], which the canvas
 * publishes on every pan/zoom/rotate, so the overlay follows the document.
 */
@Composable
fun SelectionOverlay(state: StudioUiState, modifier: Modifier = Modifier) {
    val draft = state.selectionDraft
    val bounds = state.selectionBounds
    if (draft == null && bounds == null) return
    val transform = state.canvasTransform
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        draft?.let { drawMarquee(transform, it, accent, solid = true) }
        bounds?.let { drawMarquee(transform, it, Color.White, solid = false) }
    }
}

/** Maps a document point to view pixels using the canvas transform. */
private fun docToScreen(t: CanvasTransformUi, x: Float, y: Float): Offset {
    val rad = Math.toRadians(t.rotationDeg.toDouble())
    val a = (t.zoom * cos(rad)).toFloat()
    val b = (t.zoom * sin(rad)).toFloat()
    return Offset(a * x - b * y + t.tx, b * x + a * y + t.ty)
}

private fun rectPath(t: CanvasTransformUi, r: RectUi): Path {
    val p = Path()
    val p0 = docToScreen(t, r.left, r.top)
    val p1 = docToScreen(t, r.right, r.top)
    val p2 = docToScreen(t, r.right, r.bottom)
    val p3 = docToScreen(t, r.left, r.bottom)
    p.moveTo(p0.x, p0.y)
    p.lineTo(p1.x, p1.y)
    p.lineTo(p2.x, p2.y)
    p.lineTo(p3.x, p3.y)
    p.close()
    return p
}

/**
 * Draws the rectangle outline: [solid] uses one plain stroke in [color], otherwise a black/white
 * dashed pair so the outline stays visible over any content.
 */
private fun DrawScope.drawMarquee(t: CanvasTransformUi, r: RectUi, color: Color, solid: Boolean) {
    val path = rectPath(t, r)
    if (solid) {
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
        return
    }
    val w = 1.5.dp.toPx()
    val dash = floatArrayOf(6.dp.toPx(), 6.dp.toPx())
    drawPath(path, Color.White, style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(dash, 0f)))
    drawPath(path, Color.Black, style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(dash, dash[0])))
}
