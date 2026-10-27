package app.painter.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.fill.GradientType
import app.painter.core.fill.Spread
import app.painter.ui.components.PanelSurface
import kotlin.math.roundToInt

/** Display name of a gradient type. */
private fun GradientType.titleRes(): Int = when (this) {
    GradientType.LINEAR -> R.string.gradient_linear
    GradientType.RADIAL -> R.string.gradient_radial
    GradientType.SYMMETRIC -> R.string.gradient_symmetric
    GradientType.ANGULAR -> R.string.gradient_angular
}

/** Display name of a gradient spread. */
private fun Spread.titleRes(): Int = when (this) {
    Spread.PAD -> R.string.gradient_spread_pad
    Spread.REPEAT -> R.string.gradient_spread_repeat
    Spread.REFLECT -> R.string.gradient_spread_reflect
}

/**
 * Contextual bar of the gradient tool, shown at the top centre while a gradient is open: the type picker, the
 * spread picker, the "Reverse" toggle, an opacity slider, the repetitions stepper of angular gradients,
 * "Cancel" (drops the gradient) and "Apply" (records it as one undoable step).
 */
@Composable
fun GradientBar(gradient: GradientUi, actions: StudioActions, modifier: Modifier = Modifier) {
    var typeMenu by remember { mutableStateOf(false) }
    var spreadMenu by remember { mutableStateOf(false) }
    PanelSurface(modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.tool_gradient),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
            )
            Box {
                TextButton(onClick = { typeMenu = true }) { Text(stringResource(gradient.type.titleRes())) }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    for (type in GradientType.entries) {
                        DropdownMenuItem(
                            text = { Text(stringResource(type.titleRes())) },
                            onClick = {
                                typeMenu = false
                                actions.setGradientType(type)
                            },
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { spreadMenu = true }) { Text(stringResource(gradient.spread.titleRes())) }
                DropdownMenu(expanded = spreadMenu, onDismissRequest = { spreadMenu = false }) {
                    for (spread in Spread.entries) {
                        DropdownMenuItem(
                            text = { Text(stringResource(spread.titleRes())) },
                            onClick = {
                                spreadMenu = false
                                actions.setGradientSpread(spread)
                            },
                        )
                    }
                }
            }
            TextButton(onClick = { actions.setGradientReverse(!gradient.reverse) }) {
                Text(
                    stringResource(R.string.gradient_reverse),
                    color = if (gradient.reverse) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = gradient.opacity,
                onValueChange = actions::setGradientOpacity,
                valueRange = 0f..1f,
                modifier = Modifier.width(120.dp),
            )
            Text(
                "${(gradient.opacity * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (gradient.type == GradientType.ANGULAR) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { actions.setGradientRepetitions(gradient.repetitions - 1) }) {
                        Text("−")
                    }
                    Text("${gradient.repetitions}", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { actions.setGradientRepetitions(gradient.repetitions + 1) }) {
                        Text("+")
                    }
                }
            }
            TextButton(onClick = actions::gradientCancel) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = actions::gradientApply) { Text(stringResource(R.string.action_apply)) }
        }
    }
}
