package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Distance of the rotate handle from the top edge, in screen dp (the view model hit-tests the same spot). */
private const val ROTATE_HANDLE_DP = 26f

/**
 * Compose layer above the GL canvas: draws the transform cage of [StudioUiState.transform] — the four corners
 * connected, a small square handle on each corner and a round rotate handle above the middle of the top edge.
 * Consumes no input; the document→screen mapping comes from [StudioUiState.canvasTransform].
 *
 * The cage is also what makes a drag readable while it is not being re-rendered: the pixels are only
 * refreshed at about 30 fps (see the view model), the outline follows every move.
 */
@Composable
fun TransformOverlay(state: StudioUiState, modifier: Modifier = Modifier) {
    val cage = state.transform ?: return
    val corners = cage.corners
    if (corners.size != 4) return
    val t = state.canvasTransform
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val pts = corners.map { docToScreen(t, it.x, it.y) }
        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until 4) path.lineTo(pts[i].x, pts[i].y)
        path.close()
        drawPath(path, Color.White, style = Stroke(width = 3.dp.toPx()))
        drawPath(path, accent, style = Stroke(width = 1.5.dp.toPx()))

        val rot = rotateHandleDoc(corners, t.zoom)
        val mid = docToScreen(t, (corners[0].x + corners[1].x) / 2f, (corners[0].y + corners[1].y) / 2f)
        val rotPt = docToScreen(t, rot.x, rot.y)
        drawLine(Color.White, mid, rotPt, strokeWidth = 3.dp.toPx())
        drawLine(accent, mid, rotPt, strokeWidth = 1.5.dp.toPx())
        drawHandle(rotPt, accent, circle = true)

        for (p in pts) drawHandle(p, accent, circle = false)
    }
}

/**
 * Document-space position of the rotate handle: [ROTATE_HANDLE_DP] screen pixels off the middle of the top
 * edge, away from the cage centre ([zoom] is the document→screen scale). Mirrors the view model's hit test.
 */
private fun rotateHandleDoc(corners: List<PointUi>, zoom: Float): PointUi {
    val c0 = corners[0]
    val c1 = corners[1]
    val c2 = corners[2]
    val c3 = corners[3]
    val cx = (c0.x + c1.x + c2.x + c3.x) / 4f
    val cy = (c0.y + c1.y + c2.y + c3.y) / 4f
    val mx = (c0.x + c1.x) / 2f
    val my = (c0.y + c1.y) / 2f
    val ex = c1.x - c0.x
    val ey = c1.y - c0.y
    val len = hypot(ex, ey).coerceAtLeast(1e-4f)
    var nx = -ey / len
    var ny = ex / len
    if ((mx - cx) * nx + (my - cy) * ny < 0f) {
        nx = -nx
        ny = -ny
    }
    val dist = ROTATE_HANDLE_DP / zoom.coerceAtLeast(0.01f)
    return PointUi(mx + nx * dist, my + ny * dist)
}

/** Draws a corner square ([circle] false) or the round rotate handle, white fill with an [accent] border. */
private fun DrawScope.drawHandle(at: Offset, accent: Color, circle: Boolean) {
    val r = 5.dp.toPx()
    if (circle) {
        drawCircle(Color.White, radius = r, center = at)
        drawCircle(accent, radius = r, center = at, style = Stroke(width = 1.5.dp.toPx()))
        return
    }
    val topLeft = Offset(at.x - r, at.y - r)
    val size = Size(r * 2f, r * 2f)
    drawRect(Color.White, topLeft, size)
    drawRect(accent, topLeft, size, style = Stroke(width = 1.5.dp.toPx()))
}

/** Maps a document point to view pixels using the canvas transform (same convention as the selection overlay). */
private fun docToScreen(t: CanvasTransformUi, x: Float, y: Float): Offset {
    val rad = Math.toRadians(t.rotationDeg.toDouble())
    val a = (t.zoom * cos(rad)).toFloat()
    val b = (t.zoom * sin(rad)).toFloat()
    return Offset(a * x - b * y + t.tx, b * x + a * y + t.ty)
}
