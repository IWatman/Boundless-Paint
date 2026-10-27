package app.painter.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.fill.GradientType
import app.painter.ui.components.PanelSurface

/** Display name of a gradient type. */
private fun GradientType.titleRes(): Int = when (this) {
    GradientType.LINEAR -> R.string.gradient_linear
    GradientType.RADIAL -> R.string.gradient_radial
    GradientType.SYMMETRIC -> R.string.gradient_symmetric
    GradientType.ANGULAR -> R.string.gradient_angular
}

/**
 * Contextual bar of the gradient tool, shown at the top centre while a gradient is open: the type picker,
 * "Cancel" (drops the gradient) and "Apply" (records it as one undoable step).
 */
@Composable
fun GradientBar(gradient: GradientUi, actions: StudioActions, modifier: Modifier = Modifier) {
    var typeMenu by remember { mutableStateOf(false) }
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
            TextButton(onClick = actions::gradientCancel) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = actions::gradientApply) { Text(stringResource(R.string.action_apply)) }
        }
    }
}
