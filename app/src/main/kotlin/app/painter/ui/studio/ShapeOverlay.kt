package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Alpha of the draft outline over the canvas. */
private const val SHAPE_OUTLINE_ALPHA = 0.6f

/**
 * Compose layer above the GL canvas: the thin outline of the shape draft of [StudioUiState.shape] in the primary
 * colour at [SHAPE_OUTLINE_ALPHA], plus a small round handle on every [ShapeUi.handles] position. Drawing only —
 * the touches of the shape tool go through the canvas routing, so the overlay consumes no input.
 */
@Composable
fun ShapeOverlay(state: StudioUiState, modifier: Modifier = Modifier) {
    val shape = state.shape ?: return
    val t = state.canvasTransform
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (shape.outline.size > 1) {
            val path = Path()
            shape.outline.forEachIndexed { i, p ->
                val q = docToScreen(t, p.x, p.y)
                if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
            }
            if (shape.closed) path.close()
            drawPath(path, accent.copy(alpha = SHAPE_OUTLINE_ALPHA), style = Stroke(width = 1.dp.toPx()))
        }
        for (h in shape.handles) drawShapeHandle(docToScreen(t, h.x, h.y), accent)
    }
}

/** Round handle of the draft: white fill with an [accent] border, smaller than the guide handles. */
private fun DrawScope.drawShapeHandle(at: Offset, accent: Color) {
    val r = 4.dp.toPx()
    drawCircle(Color.White, radius = r, center = at)
    drawCircle(accent, radius = r, center = at, style = Stroke(width = 1.dp.toPx()))
}

/** Maps a document point to view pixels using the canvas transform (same convention as the selection overlay). */
private fun docToScreen(t: CanvasTransformUi, x: Float, y: Float): Offset {
    val rad = Math.toRadians(t.rotationDeg.toDouble())
    val a = (t.zoom * cos(rad)).toFloat()
    val b = (t.zoom * sin(rad)).toFloat()
    return Offset(a * x - b * y + t.tx, b * x + a * y + t.ty)
}
