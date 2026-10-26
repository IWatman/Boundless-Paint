package app.painter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.painter.R
import java.util.Locale
import kotlin.math.roundToInt

/** Formats a slider value for display: value * [displayScale] with [decimals] digits. */
fun formatSliderValue(value: Float, displayScale: Float, decimals: Int): String {
    val v = value * displayScale
    return if (decimals <= 0) v.roundToInt().toString() else String.format(Locale.US, "%.${decimals}f", v)
}

/**
 * Linear slider with a label row (label + value + unit) and a track.
 * Drag on the track shows a value bubble; tap on the end buttons changes by ±[step];
 * tap on the value opens numeric input. Display value = value * [displayScale].
 */
@Composable
fun LinearSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    step: Float = 0.01f,
    unit: String = "",
    displayScale: Float = 1f,
    decimals: Int = 0,
    labelHighlighted: Boolean = false,
    onLabelClick: (() -> Unit)? = null,
    onValueChangeFinished: () -> Unit = {},
) {
    var inputOpen by remember { mutableStateOf(false) }
    val valueText = formatSliderValue(value, displayScale, decimals)
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val labelColor = if (labelHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
                fontWeight = if (labelHighlighted) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .weight(1f)
                    .then(if (onLabelClick != null) Modifier.clickable(onClick = onLabelClick) else Modifier)
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            )
            Text(
                if (unit.isEmpty()) valueText else "$valueText $unit",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable { inputOpen = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(Icons.Filled.Remove, stringResource(R.string.cd_decrease)) {
                onValueChange((value - step).coerceIn(valueRange)); onValueChangeFinished()
            }
            SliderTrack(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                bubbleText = { v -> formatSliderValue(v, displayScale, decimals) },
                onValueChangeFinished = onValueChangeFinished,
                modifier = Modifier.weight(1f),
            )
            StepButton(Icons.Filled.Add, stringResource(R.string.cd_increase)) {
                onValueChange((value + step).coerceIn(valueRange)); onValueChangeFinished()
            }
        }
    }
    if (inputOpen) {
        NumberInputDialog(
            title = label,
            initial = valueText,
            unit = unit,
            onDismiss = { inputOpen = false },
            onConfirm = { typed ->
                inputOpen = false
                onValueChange((typed / displayScale).coerceIn(valueRange))
                onValueChangeFinished()
            },
        )
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, cd, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SliderTrack(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    bubbleText: (Float) -> String,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val thumbColor = MaterialTheme.colorScheme.onSurface
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    var dragging by remember { mutableStateOf(false) }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.height(32.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val thumbR = with(density) { 7.dp.toPx() }
        fun valueAt(x: Float): Float {
            val usable = (widthPx - 2 * thumbR).coerceAtLeast(1f)
            val f = ((x - thumbR) / usable).coerceIn(0f, 1f)
            return valueRange.start + f * span
        }
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(valueRange) {
                    detectTapGestures { pos ->
                        currentOnChange(valueAt(pos.x)); currentOnFinished()
                    }
                }
                .pointerInput(valueRange) {
                    var dragValue = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; dragValue = currentValue },
                        onDragEnd = { dragging = false; currentOnFinished() },
                        onDragCancel = { dragging = false; currentOnFinished() },
                    ) { change, dx ->
                        change.consume()
                        val usable = (size.width - 2 * thumbR).coerceAtLeast(1f)
                        dragValue = (dragValue + dx / usable * span).coerceIn(valueRange)
                        currentOnChange(dragValue)
                    }
                },
        ) {
            val cy = size.height / 2
            val h = 4.dp.toPx()
            val usable = size.width - 2 * thumbR
            val x = thumbR + usable * fraction
            drawRoundRect(trackColor, Offset(thumbR, cy - h / 2), Size(usable, h), CornerRadius(h / 2))
            drawRoundRect(accent, Offset(thumbR, cy - h / 2), Size(x - thumbR, h), CornerRadius(h / 2))
            drawCircle(thumbColor, if (dragging) thumbR * 1.25f else thumbR, Offset(x, cy))
            drawCircle(accent, thumbR * 0.55f, Offset(x, cy))
        }
        if (dragging) {
            val x = thumbR + (widthPx - 2 * thumbR) * fraction
            val bubbleW = with(density) { 44.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((x - bubbleW / 2).roundToInt(), with(density) { (-26).dp.roundToPx() }) }
                    .width(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(accent)
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(bubbleText(value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/** Dialog for typing a numeric value. */
@Composable
fun NumberInputDialog(
    title: String,
    initial: String,
    unit: String,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val parsed = text.replace(',', '.').toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = parsed == null,
                suffix = if (unit.isNotEmpty()) ({ Text(unit) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { parsed?.let(onConfirm) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Circular slider: 270° arc with the value in the center. Drag in any direction
 * (right/up increases). Tap opens a popup with a [LinearSlider].
 */
@Composable
fun CircularSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    step: Float = 0.01f,
    unit: String = "",
    displayScale: Float = 1f,
    decimals: Int = 0,
) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    var popupOpen by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(value) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp)
                .pointerInput(valueRange) {
                    detectTapGestures { popupOpen = true }
                }
                .pointerInput(valueRange) {
                    val sens = 160.dp.toPx()
                    detectDragGestures(onDragStart = { dragValue = currentValue }) { change, d ->
                        change.consume()
                        dragValue = (dragValue + (d.x - d.y) / sens * span).coerceIn(valueRange)
                        currentOnChange(dragValue)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize().padding(4.dp)) {
                val sw = 4.dp.toPx()
                val style = Stroke(sw, cap = StrokeCap.Round)
                drawArc(track, 135f, 270f, false, style = style)
                drawArc(accent, 135f, 270f * fraction, false, style = style)
            }
            Text(formatSliderValue(value, displayScale, decimals), style = MaterialTheme.typography.labelLarge)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
    }
    if (popupOpen) {
        Popup(
            alignment = Alignment.TopCenter,
            offset = IntOffset(0, with(LocalDensity.current) { 64.dp.roundToPx() }),
            onDismissRequest = { popupOpen = false },
            properties = PopupProperties(focusable = true),
        ) {
            PanelSurface(Modifier.widthIn(min = 240.dp, max = 300.dp)) {
                LinearSlider(
                    label = label, value = value, onValueChange = onValueChange,
                    valueRange = valueRange, step = step, unit = unit,
                    displayScale = displayScale, decimals = decimals,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
