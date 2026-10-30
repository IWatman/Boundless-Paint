package app.painter.ui.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.stroke.BrushParams
import app.painter.ui.components.PanelSurface
import app.painter.ui.components.ParamSlider
import app.painter.ui.components.formatSliderValue

/** Right-side panel of the editor: the canvas stays visible next to it (spec/tools/m2-brush.md §4.2). */
private val EditorWidth = 440.dp

/** Max height of the panel: the tab content scrolls on a short screen instead of running past the toolbar. */
private val EditorMaxHeight = 560.dp

/** Height of the preview pad that shows the reference S-curve with the edited parameters. */
private val PreviewHeight = 160.dp

/** Tab labels in display order; only the first tab is implemented so far. */
private val EditorTabs = listOf(
    R.string.brush_editor_tab_stroke,
    R.string.brush_editor_tab_head,
    R.string.brush_editor_tab_texture,
    R.string.brush_editor_tab_paint,
    R.string.brush_editor_tab_special,
)

/**
 * The brush editor (spec/tools/m2-brush.md §4.2, parts 1–2): the brush name, the live preview pad, the Cancel/Reset/
 * Confirm buttons and the tabs. Stroke, Head and Texture have controls; Paint and Special still say "Coming soon".
 *
 * The working copy lives in [StudioUiState.editorParams] and the whole panel is stateless about it: every slider
 * sends the edited [BrushParams] to [StudioActions.updateEditor]. Confirm applies the copy to the current mode's
 * brush (part 4 will also save it), Cancel and Reset are the other two buttons.
 */
@Composable
internal fun BrushEditor(state: StudioUiState, actions: StudioActions, modifier: Modifier = Modifier) {
    val params = state.editorParams ?: return
    var tab by rememberSaveable { mutableIntStateOf(0) }
    PanelSurface(modifier.width(EditorWidth).heightIn(max = EditorMaxHeight)) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                state.editorBrushName.ifBlank { stringResource(R.string.brush_editor_blend_name) },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp),
            )
            PreviewPad(
                preview = state.editorPreview,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = actions::cancelEditor) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = actions::resetEditor) { Text(stringResource(R.string.brush_editor_reset)) }
                Box(Modifier.weight(1f))
                TextButton(onClick = actions::confirmEditor) {
                    Text(stringResource(R.string.brush_editor_confirm), fontWeight = FontWeight.SemiBold)
                }
            }
            EditorTabRow(selected = tab, onPick = { tab = it })
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when (tab) {
                    0 -> StrokeTab(params = params, actions = actions)
                    1 -> HeadTab(params = params, actions = actions)
                    2 -> TextureTab(params = params, actions = actions, hasImage = state.editorHasTexture)
                    else -> ComingSoonTab()
                }
            }
        }
    }
}

/** White pad with the last rendered preview; [ContentScale.Fit] keeps the aspect of the rendered stroke. */
@Composable
private fun PreviewPad(preview: ImageBitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(PreviewHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** One tab: its label and, while picked, an accent underline. */
@Composable
private fun EditorTabRow(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        EditorTabs.forEachIndexed { index, label ->
            val on = index == selected
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onPick(index) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .width(if (on) 20.dp else 0.dp)
                        .height(2.dp)
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}

/**
 * Basic stroke parameters (spec/tools/m2-brush.md §4.2): size and its range, opacity, flow, spacing,
 * smoothing and both taper lengths (hardness lives on the Head tab). Ranges follow the model: fractions are 0..1, smoothing uses the 0..100 UI
 * scale, taper lengths are document pixels.
 */
@Composable
private fun StrokeTab(params: BrushParams, actions: StudioActions) {
    val minSize = params.sizeMin.coerceAtLeast(0.5f)
    val maxSize = params.sizeMax.coerceAtLeast(minSize + 1f)

    ParamSlider(
        label = stringResource(R.string.size),
        value = params.size.coerceIn(minSize, maxSize),
        range = minSize..maxSize,
        format = { formatSliderValue(it, 1f, 0) },
        step = 1f,
        onChange = { actions.updateEditor(params.copy(size = it.coerceIn(minSize, maxSize))) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_size_min),
        value = params.sizeMin.coerceIn(1f, maxSize),
        range = 1f..maxSize,
        format = { formatSliderValue(it, 1f, 0) },
        step = 1f,
        onChange = {
            val v = it.coerceIn(1f, params.sizeMax)
            actions.updateEditor(params.copy(sizeMin = v, size = params.size.coerceAtLeast(v)))
        },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_size_max),
        value = params.sizeMax.coerceIn(minSize, 2000f),
        range = minSize..2000f,
        format = { formatSliderValue(it, 1f, 0) },
        step = 5f,
        onChange = {
            val v = it.coerceAtLeast(params.sizeMin)
            actions.updateEditor(params.copy(sizeMax = v, size = params.size.coerceAtMost(v)))
        },
    )
    ParamSlider(
        label = stringResource(R.string.opacity),
        value = params.opacity,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { actions.updateEditor(params.copy(opacity = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.flow),
        value = params.flow,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { actions.updateEditor(params.copy(flow = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_spacing),
        value = params.spacing,
        range = 0.01f..2f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { actions.updateEditor(params.copy(spacing = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_smoothing),
        value = params.smoothing,
        range = 0f..100f,
        format = { formatSliderValue(it, 1f, 0) },
        step = 1f,
        onChange = { actions.updateEditor(params.copy(smoothing = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_taper_start),
        value = params.taperStart,
        range = 0f..200f,
        format = { formatSliderValue(it, 1f, 0) },
        step = 1f,
        onChange = { actions.updateEditor(params.copy(taperStart = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_taper_end),
        value = params.taperEnd,
        range = 0f..200f,
        format = { formatSliderValue(it, 1f, 0) },
        step = 1f,
        onChange = { actions.updateEditor(params.copy(taperEnd = it)) },
    )
}
