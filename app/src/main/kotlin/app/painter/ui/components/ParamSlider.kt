package app.painter.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One parameter slider of a tool panel (spec/tools/m2-brush.md §4.2): a [LinearSlider] over a model range with a
 * caller-provided value format, so a panel row stays a single statement.
 *
 * @param format display text of a value without the unit; the numeric input starts from it and edits it back.
 * @param step amount the minus/plus buttons change the raw value by.
 * @param unit suffix shown after the value ("px", "%"), empty by default.
 */
@Composable
fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    step: Float = ((range.endInclusive - range.start) / 100f).takeIf { it > 0f } ?: 0.01f,
    unit: String = "",
) {
    LinearSlider(
        label = label,
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        valueRange = range,
        step = step,
        unit = unit,
        valueText = format,
    )
}
