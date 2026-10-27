package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.adjust.AdjustParams
import app.painter.core.adjust.GradientMapParams
import app.painter.core.adjust.GradientMapStop
import app.painter.core.adjust.MAX_GRADIENT_STOPS
import app.painter.core.color.Rgba
import app.painter.ui.components.PainterIconButton
import app.painter.ui.components.toColor

/**
 * Gradient map: a preview bar, the reverse switch and the list of stops (colour + position). The map colours
 * the image by its luminance; stops may be added up to [MAX_GRADIENT_STOPS] and removed down to two.
 */
@Composable
internal fun GradientMapControls(p: GradientMapParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    val stops = p.stops.sortedBy { it.pos }
    Column(Modifier.fillMaxWidth()) {
        GradientPreview(stops, p.reverse)
        AdjustSwitch(stringResource(R.string.adjust_reverse), p.reverse) { on ->
            onChange(p.copy(reverse = on))
            onCommit()
        }
        stops.forEach { stop ->
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HexColorField(
                        label = stringResource(R.string.adjust_color),
                        color = stop.color,
                        onChange = { c ->
                            onChange(p.copy(stops = p.stops.map { if (it === stop) it.copy(color = c) else it }))
                        },
                        onCommit = onCommit,
                        modifier = Modifier.weight(1f),
                    )
                    PainterIconButton(
                        Icons.Outlined.Delete,
                        stringResource(R.string.adjust_remove_stop),
                        onClick = {
                            if (p.stops.size > 2) {
                                onChange(p.copy(stops = p.stops.filterNot { it === stop }))
                                onCommit()
                            }
                        },
                        enabled = p.stops.size > 2,
                        modifier = Modifier.size(40.dp),
                    )
                }
                AdjustSlider(
                    label = stringResource(R.string.adjust_position),
                    value = stop.pos,
                    valueRange = 0f..1f,
                    onValue = { v ->
                        onChange(p.copy(stops = p.stops.map { if (it === stop) it.copy(pos = v) else it }))
                    },
                    onCommit = onCommit,
                    step = 0.01f,
                    unit = "%",
                    displayScale = 100f,
                )
            }
        }
        TextButton(
            onClick = {
                if (p.stops.size < MAX_GRADIENT_STOPS) {
                    onChange(p.copy(stops = (p.stops + GradientMapStop(newStopPos(stops), newStopColor(stops))).sortedBy { it.pos }))
                    onCommit()
                }
            },
            enabled = p.stops.size < MAX_GRADIENT_STOPS,
            modifier = Modifier.padding(top = 4.dp),
        ) { Text(stringResource(R.string.adjust_add_stop)) }
    }
}

/** Bar showing the map from black to white input luminance. */
@Composable
private fun GradientPreview(stops: List<GradientMapStop>, reverse: Boolean) {
    val colors: List<Color> = stops.map { it.color.toColor() }
    val positions: List<Float> = if (reverse) stops.map { 1f - it.pos } else stops.map { it.pos }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .padding(top = 4.dp)
            .clip(MaterialTheme.shapes.small),
    ) {
        val pairs = positions.zip(colors).sortedBy { it.first }
        if (pairs.size >= 2) {
            drawRect(
                Brush.horizontalGradient(
                    *pairs.map { it.first to it.second }.toTypedArray(),
                    startX = 0f,
                    endX = size.width,
                ),
            )
        } else if (colors.isNotEmpty()) {
            drawRect(colors.first())
        }
    }
}

/** Position of a new stop: the middle of the widest gap between the sorted stops. */
private fun newStopPos(stops: List<GradientMapStop>): Float {
    if (stops.isEmpty()) return 0.5f
    if (stops.size == 1) return if (stops[0].pos < 0.5f) 1f else 0f
    var bestGap = -1f
    var pos = 0.5f
    for (i in 0 until stops.size - 1) {
        val gap = stops[i + 1].pos - stops[i].pos
        if (gap > bestGap) {
            bestGap = gap
            pos = (stops[i].pos + stops[i + 1].pos) / 2f
        }
    }
    return pos
}

/** Colour of a new stop: the colour of the stop before the new position (white when there is none). */
private fun newStopColor(stops: List<GradientMapStop>): Rgba {
    val below = stops.lastOrNull { it.pos <= newStopPos(stops) }
    return below?.color ?: stops.firstOrNull()?.color ?: Rgba.WHITE
}
