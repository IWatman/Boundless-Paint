package app.painter.ui.studio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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

/** Contours above this point count are not drawn; the bounding box is used instead. */
private const val MAX_CONTOUR_POINTS = 200_000L

/** Duration of one marching-ants cycle (the dash phase advances by one period). */
private const val ANT_CYCLE_MS = 600

/**
 * Compose layer above the GL canvas: draws the live selection draft (rectangle, ellipse or lasso) and the
 * outline of the current selection — the real marching-ants contour when it is available, otherwise the
 * selection bounds as a dashed black/white rectangle. Consumes no input.
 *
 * The mapping to screen pixels comes from [StudioUiState.canvasTransform], which the canvas
 * publishes on every pan/zoom/rotate, so the overlay follows the document.
 */
@Composable
fun SelectionOverlay(state: StudioUiState, modifier: Modifier = Modifier) {
    val draft = state.selectionDraft
    val draftPath = state.selectionDraftPath
    val bounds = state.selectionBounds
    val contour = state.selectionContour?.takeIf { it.isNotEmpty() && contourPoints(it) <= MAX_CONTOUR_POINTS }
    if (draft == null && draftPath == null && bounds == null && contour == null) return
    val transform = state.canvasTransform
    val accent = MaterialTheme.colorScheme.primary
    val ants = if (contour != null) rememberAntPhase() else null
    Canvas(modifier) {
        draft?.let { drawMarquee(transform, it, accent, solid = true) }
        draftPath?.let { drawDraftPath(transform, it, accent) }
        if (contour != null && ants != null) {
            drawAnts(transform, contour, ants.value)
        } else {
            bounds?.let { drawMarquee(transform, it, Color.White, solid = false) }
        }
    }
}

/** Total number of contour points (two floats per point). */
private fun contourPoints(contour: List<FloatArray>): Long {
    var n = 0L
    for (line in contour) n += line.size / 2
    return n
}

/**
 * Dash phase of the marching-ants animation, 0..1 of one dash period. The value is read inside the draw
 * scope, so the animation only invalidates the drawing of the overlay.
 */
@Composable
private fun rememberAntPhase(): State<Float> {
    val transition = rememberInfiniteTransition(label = "selection-ants")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ANT_CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "selection-ants-phase",
    )
}

/** Maps a document point to view pixels using the canvas transform. */
private fun docToScreen(t: CanvasTransformUi, x: Float, y: Float): Offset {
    val rad = Math.toRadians(t.rotationDeg.toDouble())
    val a = (t.zoom * cos(rad)).toFloat()
    val b = (t.zoom * sin(rad)).toFloat()
    return Offset(a * x - b * y + t.tx, b * x + a * y + t.ty)
}

/** Polyline through document-space [pts] mapped to view pixels; left open. */
private fun polyPath(t: CanvasTransformUi, pts: List<PointUi>): Path {
    val p = Path()
    pts.forEachIndexed { i, pt ->
        val o = docToScreen(t, pt.x, pt.y)
        if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
    }
    return p
}

/** Draws the live ellipse/lasso draft outline. */
private fun DrawScope.drawDraftPath(t: CanvasTransformUi, pts: List<PointUi>, color: Color) {
    if (pts.isEmpty()) return
    drawPath(polyPath(t, pts), color, style = Stroke(width = 1.5.dp.toPx()))
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

/** View-space path of document-space contour polylines, each a flat `x0, y0, x1, y1, ...` run. */
private fun contourPath(t: CanvasTransformUi, contour: List<FloatArray>): Path {
    val p = Path()
    for (line in contour) {
        var i = 0
        while (i + 3 < line.size) {
            val a = docToScreen(t, line[i], line[i + 1])
            val b = docToScreen(t, line[i + 2], line[i + 3])
            if (i == 0) p.moveTo(a.x, a.y) else p.lineTo(a.x, a.y)
            p.lineTo(b.x, b.y)
            i += 2
        }
    }
    return p
}

/**
 * Draws the real selection outline as marching ants: a solid white 1 dp stroke underneath a black 1 dp
 * dashed stroke whose offset shifts by one dash period per cycle as [phase] runs from 0 to 1.
 */
private fun DrawScope.drawAnts(t: CanvasTransformUi, contour: List<FloatArray>, phase: Float) {
    val path = contourPath(t, contour)
    val w = 1.dp.toPx()
    val dash = 4.dp.toPx()
    drawPath(path, Color.White, style = Stroke(width = w))
    drawPath(
        path,
        Color.Black,
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), phase * 2f * dash)),
    )
}
