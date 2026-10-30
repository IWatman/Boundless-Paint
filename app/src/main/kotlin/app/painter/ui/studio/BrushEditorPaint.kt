package app.painter.ui.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.blend.BlendMode
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.EffectParams
import app.painter.core.stroke.PaintParams
import app.painter.ui.components.ParamSlider
import app.painter.ui.components.formatSliderValue

/** Height cap of the blend mode dropdown: the list is long and the panel is short. */
private val BlendMenuMaxHeight = 420.dp

/**
 * Paint tab of the brush editor (spec/tools/m2-brush.md §4.2): colour pickup — how much of the canvas colour the dab
 * mixes in, dilution, the wet switch, pull, blur and blend flow of [PaintParams] — and, below it, the stroke effects of
 * [EffectParams]: glaze, wet edges, adhesion, the blend mode with its strength, and the buildup switch of the brush.
 *
 * Every change goes to [StudioActions.updateEditor], so the preview re-renders with the edited copy.
 */
@Composable
internal fun PaintTab(params: BrushParams, actions: StudioActions) {
    val paint = params.paint
    val effects = params.effects

    /** Applies an edit of the colour pickup group. */
    fun setPaint(f: (PaintParams) -> PaintParams) = actions.updateEditor(params.copy(paint = f(paint)))

    /** Applies an edit of the stroke effects group. */
    fun setEffects(f: (EffectParams) -> EffectParams) = actions.updateEditor(params.copy(effects = f(effects)))

    EditorSection(stringResource(R.string.brush_editor_paint_mixing))
    ParamSlider(
        label = stringResource(R.string.brush_editor_paint_mix),
        value = paint.mixIn,
        range = 0f..1f,
        format = { formatSliderValue(it, 100f, 0) },
        step = 0.01f,
        unit = "%",
        onChange = { setPaint { p -> p.copy(mixIn = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_paint_dilution),
        value = paint.dilution,
        range = 0f..1f,
        format = { formatSliderValue(it, 100f, 0) },
        step = 0.01f,
        unit = "%",
        onChange = { setPaint { p -> p.copy(dilution = it) } },
    )
    ToggleChipRow(
        labels = listOf(stringResource(R.string.brush_editor_paint_wet)),
        checked = listOf(paint.wet),
    ) { setPaint { p -> p.copy(wet = !p.wet) } }
    ParamSlider(
        label = stringResource(R.string.brush_editor_paint_pull),
        value = paint.pull,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setPaint { p -> p.copy(pull = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_paint_blur),
        value = paint.blur,
        range = 0f..1f,
        format = { formatSliderValue(it, 100f, 0) },
        step = 0.01f,
        unit = "%",
        onChange = { setPaint { p -> p.copy(blur = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_paint_blend_flow),
        value = paint.blendFlow,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setPaint { p -> p.copy(blendFlow = it) } },
    )

    EditorSection(stringResource(R.string.brush_editor_effects))
    ParamSlider(
        label = stringResource(R.string.brush_editor_effect_glaze),
        value = effects.glaze,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setEffects { e -> e.copy(glaze = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_effect_wet_edges),
        value = effects.wetEdges,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setEffects { e -> e.copy(wetEdges = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_effect_adhesion),
        value = effects.adhesion,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setEffects { e -> e.copy(adhesion = it) } },
    )
    // The effect stores a BlendMode.id, so the dropdown reads and writes the stable string.
    BlendModeRow(effects.blendMode) { mode -> setEffects { e -> e.copy(blendMode = mode.id) } }
    ParamSlider(
        label = stringResource(R.string.brush_editor_effect_blend_strength),
        value = effects.blendStrength,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setEffects { e -> e.copy(blendStrength = it) } },
    )
    ToggleChipRow(
        labels = listOf(stringResource(R.string.brush_editor_effect_buildup)),
        checked = listOf(params.buildup),
    ) { actions.updateEditor(params.copy(buildup = !params.buildup)) }
}

/**
 * One dropdown row of the tab: the current blend mode's localized name, opening the grouped list of
 * [BlendModeMenuContent]. Passthrough is hidden: a brush paints, it has nothing to pass through.
 */
@Composable
private fun BlendModeRow(blendMode: String, onPick: (BlendMode) -> Unit) {
    val current = BlendMode.fromId(blendMode)
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = true }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.blend_mode),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp, horizontal = 4.dp),
            )
            Text(
                blendModeLabel(current),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = BlendMenuMaxHeight),
        ) {
            BlendModeMenuContent(current, includePassthrough = false) { mode ->
                expanded = false
                onPick(mode)
            }
        }
    }
}
