package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Manuscript canvas guides (M4): trim, bleed and safe-area rectangles inside the document. The outer rectangle
 * is the bleed edge (3%), the middle the trim (5%), the inner the safe area (10%).
 */
@Composable
fun ManuscriptOverlay(state: StudioUiState, modifier: Modifier = Modifier) {
    if (!state.manuscriptGuides) return
    val t = state.canvasTransform
    val w = state.canvasWidth.toFloat()
    val h = state.canvasHeight.toFloat()
    val bleed = MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
    val trim = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    val safe = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
    Canvas(modifier) {
        listOf(0.03f to bleed, 0.05f to trim, 0.10f to safe).forEach { (k, color) ->
            val path = rect(t, w * k, h * k, w * (1 - k), h * (1 - k))
            drawPath(path, color, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

private fun rect(t: CanvasTransformUi, x0: Float, y0: Float, x1: Float, y1: Float): Path {
    val p = Path()
    val pts = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
    pts.forEachIndexed { i, (x, y) ->
        val o = screen(t, x, y)
        if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
    }
    p.close()
    return p
}

private fun screen(t: CanvasTransformUi, x: Float, y: Float): Offset {
    val rad = Math.toRadians(t.rotationDeg.toDouble())
    val a = (t.zoom * cos(rad)).toFloat()
    val b = (t.zoom * sin(rad)).toFloat()
    return Offset(a * x - b * y + t.tx, b * x + a * y + t.ty)
}
