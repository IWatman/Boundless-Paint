package app.painter.ui.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.adjust.AdjustKind
import app.painter.core.adjust.AdjustParams
import app.painter.core.adjust.BlackWhiteParams
import app.painter.core.adjust.BrightnessContrastParams
import app.painter.core.adjust.ColorBalanceParams
import app.painter.core.adjust.CurvesParams
import app.painter.core.adjust.ExposureParams
import app.painter.core.adjust.GradientMapParams
import app.painter.core.adjust.HueSaturationParams
import app.painter.core.adjust.InvertParams
import app.painter.core.adjust.LevelsParams
import app.painter.core.adjust.PosterizeParams
import app.painter.core.adjust.TemperatureTintParams
import app.painter.core.adjust.ThresholdParams
import app.painter.core.adjust.VibranceParams
import app.painter.core.color.Rgba
import app.painter.ui.components.ColorSwatch
import app.painter.ui.components.LinearSlider
import app.painter.ui.components.PanelSurface
import app.painter.ui.components.parseHexColor
import app.painter.ui.components.toHexString

/**
 * Parameter panel of the adjustment layer in [editor]. Every change previews live on the canvas
 * ([StudioActions.updateAdjustment] with `commit = false`); releasing a control or pressing Done closes the
 * current edit into one history step.
 */
@Composable
fun AdjustmentEditor(editor: AdjustEditorUi, actions: StudioActions, modifier: Modifier = Modifier) {
    val nodeId = editor.nodeId
    val params = editor.params
    val live: (AdjustParams) -> Unit = { actions.updateAdjustment(nodeId, it, commit = false) }
    val commit: () -> Unit = { actions.updateAdjustment(nodeId, params, commit = true) }

    PanelSurface(modifier.widthIn(min = 300.dp, max = 420.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(adjustTitleRes(editor.kind)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                TextButton(onClick = {
                    AdjustKind.defaults(editor.kind)?.let {
                        live(it)
                        commit()
                    }
                }) { Text(stringResource(R.string.adjust_reset)) }
                TextButton(onClick = {
                    commit()
                    actions.closeAdjustmentEditor()
                }) { Text(stringResource(R.string.adjust_done)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp),
            ) {
                AdjustControls(params, live, commit)
            }
        }
    }
}

/** Control block of one adjustment kind. */
@Composable
private fun AdjustControls(params: AdjustParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    when (params) {
        is BrightnessContrastParams -> BrightnessContrastControls(params, onChange, onCommit)
        is ExposureParams -> ExposureControls(params, onChange, onCommit)
        is HueSaturationParams -> HueSaturationControls(params, onChange, onCommit)
        is VibranceParams -> VibranceControls(params, onChange, onCommit)
        is TemperatureTintParams -> TemperatureTintControls(params, onChange, onCommit)
        is ColorBalanceParams -> ColorBalanceControls(params, onChange, onCommit)
        is LevelsParams -> LevelsControls(params, onChange, onCommit)
        is CurvesParams -> CurvesControls(params, onChange, onCommit)
        is GradientMapParams -> GradientMapControls(params, onChange, onCommit)
        is InvertParams -> InvertControls(params, onChange, onCommit)
        is PosterizeParams -> PosterizeControls(params, onChange, onCommit)
        is ThresholdParams -> ThresholdControls(params, onChange, onCommit)
        is BlackWhiteParams -> BlackWhiteControls(params, onChange, onCommit)
        else -> Box(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(stringResource(R.string.adjust_title), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Labelled [LinearSlider] wired to live/commit callbacks: drag previews, release records one step. */
@Composable
internal fun AdjustSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 1f,
    unit: String = "",
    displayScale: Float = 1f,
    decimals: Int = 0,
) {
    LinearSlider(
        label = label,
        value = value,
        onValueChange = onValue,
        modifier = modifier,
        valueRange = valueRange,
        step = step,
        unit = unit,
        displayScale = displayScale,
        decimals = decimals,
        onValueChangeFinished = onCommit,
    )
}

/** Labelled switch used by the kinds with a boolean option. */
@Composable
internal fun AdjustSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Tab row selecting one of [labels] (channels of levels/curves, tonal ranges of colour balance). */
@Composable
internal fun AdjustTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    TabRow(selectedTabIndex = selected, containerColor = Color.Transparent) {
        labels.forEachIndexed { i, label ->
            Tab(
                selected = selected == i,
                onClick = { onSelect(i) },
                text = { Text(label, maxLines = 1) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Swatch + hex text field; commits the typed colour on done or focus loss. */
@Composable
internal fun HexColorField(
    label: String,
    color: Rgba,
    onChange: (Rgba) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(color.toHexString()) { mutableStateOf(color.toHexString()) }
    val parsed = parseHexColor(text)
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ColorSwatch(color, size = 32.dp, shape = MaterialTheme.shapes.small)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            isError = parsed == null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                parseHexColor(text)?.let { c -> onChange(c.copy(a = color.a)) }
                onCommit()
            }),
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}
