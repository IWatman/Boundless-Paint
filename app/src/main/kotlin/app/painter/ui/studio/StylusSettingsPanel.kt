package app.painter.ui.studio

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.render.PenAction
import app.painter.studio.CurvePreset
import app.painter.studio.FingerWithStylus
import app.painter.studio.PalmRejection
import app.painter.studio.StylusSettings
import app.painter.ui.components.PanelSurface
import app.painter.ui.theme.PainterTheme
import kotlin.math.pow

/** Width of the panel: one row of chips and a two-word pen action fit next to their label. */
private val PanelWidth = 300.dp

/** Max height of the panel: the content scrolls on a short screen instead of running under the toolbar. */
private val PanelMaxHeight = 440.dp

/** Size of the curve preview; its area is the unit square of the curve, pressure up. */
private val PreviewWidth = 132.dp
private val PreviewHeight = 96.dp

/**
 * Panel of the stylus settings (spec/tools/m2-brush.md §4.3), opened from the Options menu: the pressure curve
 * preset with a preview of it, the action of both pen buttons, what a finger does next to the pen and the palm
 * rejection. Every tap sends the whole [StylusSettings] to [StudioActions.setStylusSettings]; the settings come
 * back through [StudioUiState.stylusSettings], so the panel never holds a copy of them.
 */
@Composable
fun StylusSettingsPanel(
    settings: StylusSettings,
    actions: StudioActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelSurface(modifier) {
        Column(
            Modifier
                .width(PanelWidth)
                .heightIn(max = PanelMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.stylus),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.stylus_done)) }
            }
            SectionLabel(stringResource(R.string.stylus_pressure_curve))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CurvePreset.entries.forEach { preset ->
                    ToggleChip(stringResource(preset.label()), preset == settings.curvePreset) {
                        actions.setStylusSettings(settings.copy(curvePreset = preset))
                    }
                }
            }
            CurvePreview(
                preset = settings.curvePreset,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 2.dp),
            )
            HorizontalDivider()
            PenActionRow(R.string.stylus_primary_button, settings.primaryAction) {
                actions.setStylusSettings(settings.copy(primaryAction = it))
            }
            PenActionRow(R.string.stylus_secondary_button, settings.secondaryAction) {
                actions.setStylusSettings(settings.copy(secondaryAction = it))
            }
            HorizontalDivider()
            SectionLabel(stringResource(R.string.stylus_finger))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FingerWithStylus.entries.forEach { rule ->
                    ToggleChip(stringResource(rule.label()), rule == settings.fingerWithStylus) {
                        actions.setStylusSettings(settings.copy(fingerWithStylus = rule))
                    }
                }
            }
            HorizontalDivider()
            SectionLabel(stringResource(R.string.stylus_palm))
            PalmRejection.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToggleChip(stringResource(mode.label()), mode == settings.palm) {
                        actions.setStylusSettings(settings.copy(palm = mode))
                    }
                    Text(
                        stringResource(mode.hint()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Small muted caption above a group of controls. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * Preview of the pressure curve: the curve of [preset] as y = x^γ over the preview area, the linear reference
 * (γ 1) behind it and the axes along the left and bottom edges. Pressure grows to the right, the dab to the top.
 */
@Composable
private fun CurvePreview(preset: CurvePreset, modifier: Modifier = Modifier) {
    val curveColor = MaterialTheme.colorScheme.primary
    val axisColor = PainterTheme.colors.panelBorder
    val hairline = 1.dp
    val stroke = 2.dp
    Canvas(modifier.width(PreviewWidth).height(PreviewHeight)) {
        val w = size.width
        val h = size.height
        drawLine(axisColor, Offset(0f, h), Offset(w, h), hairline.toPx())
        drawLine(axisColor, Offset(0f, 0f), Offset(0f, h), hairline.toPx())
        drawLine(axisColor.copy(alpha = 0.4f), Offset(0f, h), Offset(w, 0f), hairline.toPx())
        val path = Path()
        for (i in 0..PREVIEW_STEPS) {
            val x = i / PREVIEW_STEPS.toFloat()
            val y = x.pow(preset.gamma)
            val px = x * w
            val py = h - y * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, curveColor, style = Stroke(width = stroke.toPx()))
    }
}

/**
 * One line of the panel: the label of a pen button and the action bound to it, which a drop-down over all
 * [PenAction]s replaces ([PenAction.NONE] included: a button may stay unbound).
 */
@Composable
private fun PenActionRow(@StringRes label: Int, action: PenAction, onPick: (PenAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { open = true }) { Text(stringResource(action.label())) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                PenAction.entries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(stringResource(entry.label())) },
                        onClick = {
                            open = false
                            onPick(entry)
                        },
                    )
                }
            }
        }
    }
}

/** [R.string] label of the curve preset; "Soft" and "Firm" name the gamma, so the chip needs no number. */
@StringRes
private fun CurvePreset.label(): Int = when (this) {
    CurvePreset.SOFT -> R.string.stylus_curve_soft
    CurvePreset.NORMAL -> R.string.stylus_curve_normal
    CurvePreset.FIRM -> R.string.stylus_curve_firm
}

/** [R.string] label of what a finger does next to the pen. */
@StringRes
private fun FingerWithStylus.label(): Int = when (this) {
    FingerWithStylus.DRAW -> R.string.stylus_finger_draw
    FingerWithStylus.NAVIGATE_ONLY -> R.string.stylus_finger_navigate
}

/** [R.string] label of the palm rejection mode. */
@StringRes
private fun PalmRejection.label(): Int = when (this) {
    PalmRejection.OFF -> R.string.stylus_palm_off
    PalmRejection.STYLUS_ONLY -> R.string.stylus_palm_stylus_only
    PalmRejection.HOVER_REJECT -> R.string.stylus_palm_hover
}

/** [R.string] one-line explanation of the palm rejection mode, shown next to its chip. */
@StringRes
private fun PalmRejection.hint(): Int = when (this) {
    PalmRejection.OFF -> R.string.stylus_palm_off_hint
    PalmRejection.STYLUS_ONLY -> R.string.stylus_palm_stylus_only_hint
    PalmRejection.HOVER_REJECT -> R.string.stylus_palm_hover_hint
}

/** [R.string] readable label of a pen action, with "(hold)" for the actions that last while the button is down. */
@StringRes
private fun PenAction.label(): Int = when (this) {
    PenAction.NONE -> R.string.pen_action_none
    PenAction.EYEDROPPER -> R.string.pen_action_eyedropper
    PenAction.ERASE -> R.string.pen_action_erase
    PenAction.UNDO -> R.string.pen_action_undo
    PenAction.REDO -> R.string.pen_action_redo
    PenAction.BRUSH_BROWSER -> R.string.pen_action_brush_browser
}

/** Samples of the drawn curve: one point per pixel column of the preview is plenty. */
private const val PREVIEW_STEPS = 24
