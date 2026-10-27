package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.adjust.AdjustParams
import app.painter.core.adjust.CurvesParams
import app.painter.core.adjust.MAX_POINTS
import app.painter.core.adjust.MonotoneCurve
import app.painter.core.adjust.Pt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Curves: RGB or per-channel tab with a square graph. Drag a point to move it, tap to add one (up to
 * [MAX_POINTS]), long-press to remove it; the curve is the monotone cubic the renderer uses.
 */
@Composable
internal fun CurvesControls(p: CurvesParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    var channel by rememberSaveable { mutableIntStateOf(0) }
    val points = when (channel) {
        0 -> p.rgb
        1 -> p.r
        2 -> p.g
        else -> p.b
    }
    val setPoints: (List<Pt>) -> Unit = { pts ->
        onChange(
            when (channel) {
                0 -> p.copy(rgb = pts)
                1 -> p.copy(r = pts)
                2 -> p.copy(g = pts)
                else -> p.copy(b = pts)
            },
        )
    }
    Column(Modifier.fillMaxWidth()) {
        AdjustTabs(
            labels = listOf(
                stringResource(R.string.adjust_channel_rgb),
                stringResource(R.string.adjust_channel_r),
                stringResource(R.string.adjust_channel_g),
                stringResource(R.string.adjust_channel_b),
            ),
            selected = channel,
            onSelect = { channel = it },
        )
        CurveGraph(
            points = points,
            onPoints = setPoints,
            onCommit = onCommit,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).height(200.dp),
        )
        Text(
            stringResource(R.string.adjust_curves_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun CurveGraph(
    points: List<Pt>,
    onPoints: (List<Pt>) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentPoints by rememberUpdatedState(points)
    val currentOnPoints by rememberUpdatedState(onPoints)
    val currentOnCommit by rememberUpdatedState(onCommit)
    var dragIndex by remember { mutableIntStateOf(-1) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val curveColor = MaterialTheme.colorScheme.primary
    val curveFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val dotColor = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.surfaceContainerHigh

    Canvas(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        val pts = currentPoints
                        if (pts.size < MAX_POINTS) {
                            currentOnPoints((pts + toPoint(pos, size.width.toFloat(), size.height.toFloat())).sortedBy { it.x })
                            currentOnCommit()
                        }
                    },
                    onLongPress = { pos ->
                        val pts = currentPoints
                        if (pts.size > 2) {
                            val i = nearest(pts, pos, size.width.toFloat(), size.height.toFloat())
                            if (i >= 0) {
                                currentOnPoints(pts.filterIndexed { k, _ -> k != i })
                                currentOnCommit()
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        dragIndex = nearest(currentPoints, pos, size.width.toFloat(), size.height.toFloat())
                    },
                    onDragEnd = {
                        if (dragIndex >= 0) currentOnCommit()
                        dragIndex = -1
                    },
                    onDragCancel = { dragIndex = -1 },
                ) { change, _ ->
                    val i = dragIndex
                    if (i < 0) return@detectDragGestures
                    change.consume()
                    val pts = currentPoints.toMutableList()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val target = toPoint(change.position, w, h)
                    val lo = if (i > 0) pts[i - 1].x + 0.002f else 0f
                    val hi = if (i < pts.lastIndex) pts[i + 1].x - 0.002f else 1f
                    pts[i] = Pt(target.x.coerceIn(lo, max(lo, hi)), target.y)
                    currentOnPoints(pts)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        for (k in 1..3) {
            val x = w * k / 4f
            val y = h * k / 4f
            drawLine(grid.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            drawLine(grid.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        drawLine(grid, Offset(0f, h), Offset(w, 0f), strokeWidth = 1f)

        // The curve the renderer applies (monotone cubic through the points).
        val curve = MonotoneCurve(points)
        val path = Path()
        val steps = 80
        path.moveTo(0f, h - curve.eval(0.0).toFloat() * h)
        for (i in 1..steps) {
            val x = i.toFloat() / steps
            path.lineTo(x * w, h - curve.eval(x.toDouble()).toFloat() * h)
        }
        drawPath(path, curveColor, style = Stroke(width = 2.dp.toPx()))
        val fill = Path().apply {
            addPath(path)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(fill, curveFill)

        val r = 5.dp.toPx()
        points.forEachIndexed { i, pt ->
            val c = Offset(pt.x * w, h - pt.y * h)
            drawCircle(dotColor, if (i == dragIndex) r * 1.4f else r, c)
            drawCircle(curveColor, if (i == dragIndex) r * 0.7f else r * 0.5f, c)
        }
    }
}

/** Canvas position to curve coordinates (x right, y up, both 0..1). */
private fun toPoint(pos: Offset, w: Float, h: Float): Pt =
    Pt((pos.x / w.coerceAtLeast(1f)).coerceIn(0f, 1f), (1f - pos.y / h.coerceAtLeast(1f)).coerceIn(0f, 1f))

/** Index of the point closest to [pos] within a 24 dp touch radius, or -1. */
private fun nearest(points: List<Pt>, pos: Offset, w: Float, h: Float): Int {
    var best = -1
    var bestDist = Float.MAX_VALUE
    points.forEachIndexed { i, pt ->
        val dx = abs(pt.x * w - pos.x)
        val dy = abs((1f - pt.y) * h - pos.y)
        val d = max(dx, dy)
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    val radius = min(max(w, h) * 0.06f, 48f)
    return if (bestDist <= radius) best else -1
}
