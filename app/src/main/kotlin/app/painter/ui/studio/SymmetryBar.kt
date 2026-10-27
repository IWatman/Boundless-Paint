package app.painter.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.symmetry.SymmetryType
import app.painter.ui.components.PanelSurface

/**
 * Contextual bar of the symmetry overlay, shown at the top end while [StudioUiState.symmetry] is on: the
 * "−" N "+" stepper of Radial and Kaleido, the "Lock" toggle, the "Crop at axis" toggle of Mirror and Kaleido
 * and "×", which turns the symmetry off.
 */
@Composable
fun SymmetryBar(symmetry: SymmetryUi, actions: StudioActions, modifier: Modifier = Modifier) {
    PanelSurface(modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.symmetry_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
            )
            if (symmetry.type == SymmetryType.RADIAL || symmetry.type == SymmetryType.KALEIDO) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { actions.setSymmetryPlanes(symmetry.planes - 1) }) { Text("−") }
                    Text("${symmetry.planes}", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { actions.setSymmetryPlanes(symmetry.planes + 1) }) { Text("+") }
                }
            }
            SymmetryToggle(stringResource(R.string.symmetry_lock), symmetry.locked) {
                actions.setSymmetryLocked(!symmetry.locked)
            }
            if (symmetry.type != SymmetryType.RADIAL) {
                SymmetryToggle(stringResource(R.string.symmetry_crop), symmetry.crop) {
                    actions.setSymmetryCrop(!symmetry.crop)
                }
            }
            TextButton(onClick = { actions.setSymmetry(null) }) { Text("×") }
        }
    }
}

/** Text button of a bar toggle, drawn in the primary color while it is on. */
@Composable
private fun SymmetryToggle(label: String, on: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
