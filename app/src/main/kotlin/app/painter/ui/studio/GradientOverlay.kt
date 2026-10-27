package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.painter.core.fill.GradientType
import app.painter.ui.components.toColor
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Compose layer above the GL canvas: draws the axis of [StudioUiState.gradient] with a round handle on each
 * end, a small circle per stop filled with its own color, and the end circle around p0 for
 * [GradientType.RADIAL]. Consumes no input.
 */
@Composable
fun GradientOverlay(state: StudioUiState, modifier: Modifier = Modifier) {
    val g = state.gradient ?: return
    val t = state.canvasTransform
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val p0 = docToScreen(t, g.p0.x, g.p0.y)
        val p1 = docToScreen(t, g.p1.x, g.p1.y)
        val outer = 3.dp.toPx()
        val inner = 1.5.dp.toPx()
        if (g.type == GradientType.RADIAL) {
            val r = hypot(p1.x - p0.x, p1.y - p0.y)
            drawCircle(Color.White, radius = r, center = p0, style = Stroke(width = outer))
            drawCircle(accent, radius = r, center = p0, style = Stroke(width = inner))
        }
        drawLine(Color.White, p0, p1, strokeWidth = outer)
        drawLine(accent, p0, p1, strokeWidth = inner)
        val stopR = 4.5.dp.toPx()
        for (s in g.stops) {
            val p = Offset(p0.x + (p1.x - p0.x) * s.pos, p0.y + (p1.y - p0.y) * s.pos)
            drawCircle(s.color.toColor(), radius = stopR, center = p)
            drawCircle(Color.White, radius = stopR, center = p, style = Stroke(width = inner))
        }
        val r = 6.dp.toPx()
        for (p in listOf(p0, p1)) {
            drawCircle(Color.White, radius = r, center = p)
            drawCircle(accent, radius = r, center = p, style = Stroke(width = inner))
        }
    }
}

/** Maps a document point to view pixels using the canvas transform (same convention as the selection overlay). */
private fun docToScreen(t: CanvasTransformUi, x: Float, y: Float): Offset {
    val rad = Math.toRadians(t.rotationDeg.toDouble())
    val a = (t.zoom * cos(rad)).toFloat()
    val b = (t.zoom * sin(rad)).toFloat()
    return Offset(a * x - b * y + t.tx, b * x + a * y + t.ty)
}
