package app.painter.ui.studio

import android.view.MotionEvent
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.render.PenAction
import app.painter.studio.CalibrationWizard
import app.painter.studio.CurvePreset
import app.painter.studio.FingerWithStylus
import app.painter.studio.PalmRejection
import app.painter.studio.StylusSettings
import app.painter.ui.components.PanelSurface
import app.painter.ui.components.formatSliderValue
import app.painter.ui.theme.PainterTheme
import kotlin.math.pow

/** Width of the panel: one row of chips and a two-word pen action fit next to their label. */
private val PanelWidth = 300.dp

/** Max height of the panel: the content scrolls on a short screen instead of running under the toolbar. */
private val PanelMaxHeight = 440.dp

/** Size of the curve preview; its area is the unit square of the curve, pressure up. */
private val PreviewWidth = 132.dp
private val PreviewHeight = 96.dp

/** Press pad of the calibration wizard, its live pressure bar and the corners/border of both. */
private val PadHeight = 72.dp
private val BarHeight = 8.dp
private val PadCorner = 6.dp
private val Hairline = 1.dp

/**
 * Panel of the stylus settings (spec/tools/m2-brush.md §4.3), opened from the Options menu: the pressure curve
 * preset with a preview of it, the action of both pen buttons, what a finger does next to the pen and the palm
 * rejection. Every tap sends the whole [StylusSettings] to [StudioActions.setStylusSettings]; the settings come
 * back through [StudioUiState.stylusSettings], so the panel never holds a copy of them. The pressure
 * calibration works the same way: [calibration] comes from [StudioUiState.calibration], the wizard sends the
 * range it found to [StudioActions.applyCalibration] and "Reset to auto" to [StudioActions.resetCalibration].
 */
@Composable
fun StylusSettingsPanel(
    settings: StylusSettings,
    calibration: CalibrationUi,
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
            SectionLabel(stringResource(R.string.stylus_calibration))
            CalibrationSection(calibration = calibration, actions = actions)
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

/**
 * Pressure calibration group of the panel (spec/tools/m2-brush.md §4.3): the range the pen draws with, either
 * "Auto" or the fixed minimum and maximum, the wizard that measures the range and "Reset to auto". The presses
 * live in a [CalibrationWizard]; [step] and [presses] mirror it for the step text and the n/3 progress.
 */
@Composable
private fun CalibrationSection(calibration: CalibrationUi, actions: StudioActions) {
    var calibrating by remember { mutableStateOf(false) }
    var wizard by remember { mutableStateOf(CalibrationWizard()) }
    var step by remember { mutableStateOf(CalibrationWizard.Step.LIGHT) }
    var presses by remember { mutableIntStateOf(0) }
    var live by remember { mutableFloatStateOf(0f) }
    var outcome by remember { mutableStateOf<CalibrationWizard.Outcome?>(null) }
    var allPens by remember { mutableStateOf(false) }

    Text(
        if (calibration.auto) {
            stringResource(R.string.stylus_calibration_auto)
        } else {
            stringResource(
                R.string.stylus_calibration_fixed,
                formatSliderValue(calibration.min, 1f, 2),
                formatSliderValue(calibration.max, 1f, 2),
            )
        },
        style = MaterialTheme.typography.labelLarge,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(
            onClick = {
                wizard = CalibrationWizard()
                step = wizard.step
                presses = wizard.presses
                live = 0f
                outcome = null
                allPens = false
                calibrating = true
            },
        ) { Text(stringResource(R.string.stylus_calibrate)) }
        TextButton(
            onClick = {
                calibrating = false
                outcome = null
                allPens = false
                live = 0f
                actions.resetCalibration()
            },
        ) { Text(stringResource(R.string.stylus_reset_auto)) }
    }
    if (calibrating) {
        Text(
            stringResource(
                if (step == CalibrationWizard.Step.LIGHT) R.string.stylus_calibrate_light
                else R.string.stylus_calibrate_hard,
            ),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            stringResource(R.string.stylus_calibrate_progress, presses),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PressureBar(live)
        PressurePad(
            onLive = { live = it },
            onPress = { peak, descriptor ->
                val result = wizard.feed(peak)
                step = wizard.step
                presses = wizard.presses
                outcome = result
                if (result is CalibrationWizard.Outcome.Ready) {
                    // A pad that saw no InputDevice applies the range to every pen; the panel says so.
                    allPens = descriptor == null
                    actions.applyCalibration(descriptor, result.min, result.max)
                    calibrating = false
                }
            },
        )
        TextButton(onClick = {
            calibrating = false
            live = 0f
            outcome = null
        }) { Text(stringResource(R.string.stylus_calibrate_cancel)) }
    }
    when (val result = outcome) {
        is CalibrationWizard.Outcome.Ready -> Text(
            stringResource(
                R.string.stylus_calibrate_done,
                formatSliderValue(result.min, 1f, 2),
                formatSliderValue(result.max, 1f, 2),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        CalibrationWizard.Outcome.Rejected -> Text(
            stringResource(R.string.stylus_calibrate_failed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        else -> Unit
    }
    if (allPens) {
        Text(
            stringResource(R.string.stylus_calibrate_all_pens),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Live bar of the raw pressure the [PressurePad] reads right now; empty while the pen is up. */
@Composable
private fun PressureBar(pressure: Float, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(BarHeight)) {
        val corners = CornerRadius(size.height / 2f)
        drawRoundRect(trackColor, cornerRadius = corners)
        val filled = size.width * pressure.coerceIn(0f, 1f)
        if (filled > 0f) drawRoundRect(barColor, size = Size(filled, size.height), cornerRadius = corners)
    }
}

/**
 * Press pad of the wizard: a [Box] that reads raw stylus pressure from the pointer events it receives. One
 * press is down…up; [onPress] gets the peak pressure of it together with the `InputDevice.descriptor` of the
 * event, which is null when the platform reports no device. Fingers and mice are ignored; [onLive] follows the
 * pressure of the pen that is down.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PressurePad(
    onLive: (Float) -> Unit,
    onPress: (Float, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(PadCorner)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = PainterTheme.colors.panelBorder
    var down by remember { mutableStateOf(false) }
    var peak by remember { mutableFloatStateOf(0f) }
    var descriptor by remember { mutableStateOf<String?>(null) }
    Box(
        modifier
            .fillMaxWidth()
            .height(PadHeight)
            .background(trackColor, shape)
            .border(Hairline, borderColor, shape)
            .pointerInteropFilter { event ->
                val tool = event.getToolType(0)
                val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
                when {
                    !stylus -> false
                    event.actionMasked == MotionEvent.ACTION_DOWN -> {
                        down = true
                        descriptor = event.device?.descriptor
                        peak = event.getPressure(0)
                        onLive(peak)
                        true
                    }
                    event.actionMasked == MotionEvent.ACTION_MOVE -> {
                        if (down) {
                            // The samples of the batch sit between the events; the peak needs all of them.
                            var p = event.getPressure(0)
                            for (h in 0 until event.historySize) p = maxOf(p, event.getHistoricalPressure(0, h))
                            peak = maxOf(peak, p)
                            onLive(p)
                        }
                        true
                    }
                    event.actionMasked == MotionEvent.ACTION_UP -> {
                        if (down) {
                            down = false
                            onLive(0f)
                            onPress(peak, descriptor)
                        }
                        true
                    }
                    else -> {
                        // A cancelled press (the panel scrolled, the pen left the screen) measures nothing.
                        down = false
                        onLive(0f)
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.stylus_calibrate_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Samples of the drawn curve: one point per pixel column of the preview is plenty. */
private const val PREVIEW_STEPS = 24
