package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compose layer above the GL canvas: a crosshair on the clone source of
 * [StudioUiState.cloneSource] while the clone tool is active, so the picked point stays visible.
 * The live sample position is not tracked: only the source point is drawn. Consumes no input.
 */
@Composable
fun CloneOverlay(state: StudioUiState, modifier: Modifier = Modifier) {
    if (state.tool != ToolUi.CLONE) return
    val source = state.cloneSource ?: return
    val t = state.canvasTransform
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val p = docToScreen(t, source.x, source.y)
        val arm = 12.dp.toPx() // two 24 dp lines
        val outer = 3.dp.toPx()
        val inner = 1.5.dp.toPx()
        val h0 = Offset(p.x - arm, p.y)
        val h1 = Offset(p.x + arm, p.y)
        val v0 = Offset(p.x, p.y - arm)
        val v1 = Offset(p.x, p.y + arm)
        drawLine(Color.White, h0, h1, strokeWidth = outer)
        drawLine(Color.White, v0, v1, strokeWidth = outer)
        drawLine(accent, h0, h1, strokeWidth = inner)
        drawLine(accent, v0, v1, strokeWidth = inner)
    }
}

/** Maps a document point to view pixels using the canvas transform (same convention as the gradient overlay). */
private fun docToScreen(t: CanvasTransformUi, x: Float, y: Float): Offset {
    val rad = Math.toRadians(t.rotationDeg.toDouble())
    val a = (t.zoom * cos(rad)).toFloat()
    val b = (t.zoom * sin(rad)).toFloat()
    return Offset(a * x - b * y + t.tx, b * x + a * y + t.ty)
}
