package app.painter.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.color.Rgba
import app.painter.core.fill.PatternKind
import app.painter.core.fill.PatternPathSpec
import app.painter.core.fill.PatternSpec
import app.painter.ui.components.ParamSlider
import app.painter.ui.components.toHsvUi
import app.painter.ui.components.toRgba
import kotlin.math.roundToInt

/**
 * Pattern path dialog (spec §21): one of twelve built-in procedural patterns repeated along a freehand path,
 * blended between a background and an ink colour. Spacing and tile scale are document pixels, the tiles either
 * follow the path tangent or keep a fixed angle, and the opacity and invert switch apply to the whole path.
 * Apply switches the canvas to the transient path mode; nothing is painted until the user drags.
 */
@Composable
fun PatternPathDialog(
    ink: Rgba,
    onDismiss: () -> Unit,
    onApply: (PatternPathSpec, Rgba, Rgba) -> Unit,
) {
    var kind by remember { mutableStateOf(PatternKind.CHECKS) }
    var spacing by remember { mutableStateOf(PatternPathSpec.DEFAULT_SPACING) }
    var scale by remember { mutableStateOf(PatternPathSpec.DEFAULT_SCALE) }
    var followTangent by remember { mutableStateOf(true) }
    var angle by remember { mutableStateOf(0f) }
    var opacity by remember { mutableStateOf(1f) }
    var invert by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(PatternColorRole.BACKGROUND) }
    // The two colours are kept as HSV so that hue survives a trip through zero saturation or brightness.
    val alpha = ink.a.coerceIn(0f, 1f)
    var backgroundHsv by remember { mutableStateOf(ink.toHsvUi()) }
    var inkHsv by remember { mutableStateOf(ink.toHsvUi()) }
    val background = backgroundHsv.toRgba(alpha)
    val patternInk = inkHsv.toRgba(alpha)
    val previews = remember(background, patternInk, invert, opacity) {
        PatternKind.entries.associateWith {
            previewBitmap(
                PatternSpec(kind = it, tileSize = PREVIEW_TILE, invert = invert),
                background,
                patternInk,
                opacity,
            )
        }
    }
    val spec = PatternPathSpec(kind, spacing, scale, followTangent, angle, opacity, invert)
    val percent = { v: Float -> "${(v * 100).roundToInt()}%" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pattern_path_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (row in PatternKind.entries.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (option in row) {
                            PatternTile(
                                label = stringResource(option.titleRes()),
                                preview = previews.getValue(option),
                                selected = kind == option,
                                onPick = { kind = option },
                            )
                        }
                    }
                }
                PatternColorEditor(
                    backgroundHsv = backgroundHsv,
                    inkHsv = inkHsv,
                    editing = editing,
                    onEditingChange = { editing = it },
                    onBackgroundHsv = { backgroundHsv = it },
                    onInkHsv = { inkHsv = it },
                    background = background,
                    ink = patternInk,
                )
                ParamSlider(
                    label = stringResource(R.string.spacing),
                    value = spacing,
                    range = PatternPathSpec.MIN_SPACING..PatternPathSpec.MAX_SPACING,
                    format = { it.roundToInt().toString() },
                    onChange = { spacing = it },
                    step = 1f,
                    unit = "px",
                )
                ParamSlider(
                    label = stringResource(R.string.pattern_path_scale),
                    value = scale,
                    range = PatternPathSpec.MIN_SCALE..PatternPathSpec.MAX_SCALE,
                    format = { it.roundToInt().toString() },
                    onChange = { scale = it },
                    step = 1f,
                    unit = "px",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip(
                        label = stringResource(R.string.pattern_path_follow),
                        checked = followTangent,
                        onToggle = { followTangent = true },
                    )
                    ToggleChip(
                        label = stringResource(R.string.pattern_path_fixed),
                        checked = !followTangent,
                        onToggle = { followTangent = false },
                    )
                }
                if (!followTangent) {
                    ParamSlider(
                        label = stringResource(R.string.angle),
                        value = angle,
                        range = 0f..360f,
                        format = { it.roundToInt().toString() },
                        onChange = { angle = it },
                        step = 1f,
                        unit = "°",
                    )
                }
                AdjustSwitch(
                    label = stringResource(R.string.pattern_invert),
                    checked = invert,
                    onCheckedChange = { invert = it },
                )
                ParamSlider(
                    label = stringResource(R.string.opacity),
                    value = opacity,
                    range = 0f..1f,
                    format = percent,
                    onChange = { opacity = it },
                    step = 0.01f,
                )
                Text(
                    stringResource(R.string.pattern_path_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(spec, background, patternInk) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One pattern preview shows exactly one motif tile, whatever the document-pixel scale of the path. */
private const val PREVIEW_TILE = 56f
