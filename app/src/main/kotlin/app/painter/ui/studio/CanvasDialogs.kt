package app.painter.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.ui.components.ParamSlider
import app.painter.ui.theme.AccentFill
import kotlin.math.roundToInt

/** Crop dialog: the kept document rectangle in pixels (prefilled with the selection or a centred area). */
@Composable
fun CropDialog(docW: Int, docH: Int, initial: RectUi, onDismiss: () -> Unit, onConfirm: (RectUi) -> Unit) {
    var left by remember { mutableStateOf(initial.left.roundToInt().toString()) }
    var top by remember { mutableStateOf(initial.top.roundToInt().toString()) }
    var right by remember { mutableStateOf(initial.right.roundToInt().toString()) }
    var bottom by remember { mutableStateOf(initial.bottom.roundToInt().toString()) }
    val l = left.toIntOrNull()
    val t = top.toIntOrNull()
    val r = right.toIntOrNull()
    val b = bottom.toIntOrNull()
    val valid = l != null && t != null && r != null && b != null && l >= 0 && t >= 0 && r > l && b > t &&
        r <= docW && b <= docH
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.canvas_crop)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.canvas_size_hint, docW, docH), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(stringResource(R.string.left), left, { left = it }, Modifier.weight(1f))
                    NumberField(stringResource(R.string.top), top, { top = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(stringResource(R.string.right), right, { right = it }, Modifier.weight(1f))
                    NumberField(stringResource(R.string.bottom), bottom, { bottom = it }, Modifier.weight(1f))
                }
                if (!valid) Text(stringResource(R.string.canvas_bad_rect), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(RectUi(l!!.toFloat(), t!!.toFloat(), r!!.toFloat(), b!!.toFloat())) },
            ) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Canvas-size dialog: dimensions, content scaling and one of five anchors. */
@Composable
fun ResizeCanvasDialog(
    docW: Int,
    docH: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Boolean, Float, Float) -> Unit,
) {
    var width by remember { mutableStateOf(docW.toString()) }
    var height by remember { mutableStateOf(docH.toString()) }
    var scale by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(4) }
    val anchors = listOf(0f to 0f, 0.5f to 0f, 1f to 0f, 0f to 0.5f, 0.5f to 0.5f, 1f to 0.5f, 0f to 1f, 0.5f to 1f, 1f to 1f)
    val w = width.toIntOrNull()
    val h = height.toIntOrNull()
    val valid = w != null && h != null && w in 1..16384 && h in 1..16384
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.canvas_resize)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.canvas_size_hint, docW, docH), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(stringResource(R.string.width), width, { width = it }, Modifier.weight(1f))
                    NumberField(stringResource(R.string.height), height, { height = it }, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = scale, onCheckedChange = { scale = it })
                    Text(stringResource(R.string.scale_content))
                }
                Text(stringResource(R.string.anchor), style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0 until 3) {
                                val i = row * 3 + col
                                BoxAnchor(
                                    selected = anchor == i,
                                    label = when (i) { 0 -> "↖"; 2 -> "↗"; 6 -> "↙"; 8 -> "↘"; 4 -> "·"; else -> " " },
                                    onClick = { anchor = i },
                                )
                            }
                        }
                    }
                }
                if (!valid) Text(stringResource(R.string.canvas_bad_size), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val a = anchors[anchor]
                    onConfirm(w!!, h!!, scale, a.first, a.second)
                },
            ) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Straighten dialog: rotate the artwork by a small angle inside the same canvas. */
@Composable
fun StraightenDialog(onDismiss: () -> Unit, onConfirm: (Float) -> Unit) {
    var angle by remember { mutableStateOf(0f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.canvas_straighten)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ParamSlider(
                    label = stringResource(R.string.angle),
                    value = angle,
                    range = -45f..45f,
                    format = { "${it.roundToInt()}°" },
                    onChange = { angle = it },
                    step = 1f,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { angle = -90f.coerceAtLeast(-45f) }) { Text("-45°") }
                    TextButton(onClick = { angle = 0f }) { Text("0°") }
                    TextButton(onClick = { angle = 45f }) { Text("45°") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(angle) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 5 && v.all { it.isDigit() }) onChange(v) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun BoxAnchor(selected: Boolean, label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) AccentFill else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}
