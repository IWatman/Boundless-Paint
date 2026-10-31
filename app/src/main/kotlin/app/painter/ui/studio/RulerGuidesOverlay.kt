package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * M4 radial and array ruler overlays. Radial: 24 rays from the canvas centre; array: a 3x3 guide grid with
 * thirds. Both follow the canvas transform and are drawn above the artwork but below the tool bars.
 */
@Composable
fun RulerGuidesOverlay(state: StudioUiState, modifier: Modifier = Modifier) {
    if (!state.radialGuides && !state.arrayGuides) return
    val t = state.canvasTransform
    val w = state.canvasWidth.toFloat()
    val h = state.canvasHeight.toFloat()
    val color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
    val thin = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)
    Canvas(modifier) {
        if (state.radialGuides) {
            val cx = w / 2f
            val cy = h / 2f
            val radius = maxOf(w, h) * 1.5f
            for (i in 0 until 24) {
                val a = 2 * PI * i / 24
                drawLine(
                    color,
                    screen(t, cx, cy),
                    screen(t, (cx + radius * cos(a)).toFloat(), (cy + radius * sin(a)).toFloat()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        if (state.arrayGuides) {
            for (i in 1..2) {
                val x = w * i / 3f
                val y = h * i / 3f
                drawLine(thin, screen(t, x, 0f), screen(t, x, h), strokeWidth = 1.dp.toPx())
                drawLine(thin, screen(t, 0f, y), screen(t, w, y), strokeWidth = 1.dp.toPx())
            }
            drawPath(
                rectPath(t, 0f, 0f, w, h),
                color,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

private fun rectPath(t: CanvasTransformUi, x0: Float, y0: Float, x1: Float, y1: Float): androidx.compose.ui.graphics.Path {
    val p = androidx.compose.ui.graphics.Path()
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
