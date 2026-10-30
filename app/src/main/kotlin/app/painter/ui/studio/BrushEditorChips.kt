package app.painter.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R

/**
 * Placeholder of a tab the editor does not fill in yet (Paint and Special come in part 3): a muted line instead of
 * an empty tab.
 */
@Composable
internal fun ComingSoonTab() {
    Text(
        stringResource(R.string.brush_editor_coming_soon),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

/**
 * Heading of a group of controls of a brush editor tab (spec/tools/m2-brush.md §4.2): an accent label above the
 * sliders of the group, so that a long tab reads as sections.
 */
@Composable
internal fun EditorSection(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/**
 * One row of single-choice chips: an enum option list with readable labels, the picked one highlighted. The panel
 * is narrower than a long enum, so the caller splits it into rows with [List.chunked].
 */
@Composable
internal fun <T> ChipRow(options: List<T>, selected: T?, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (option in options) {
            ToggleChip(label = label(option), checked = option == selected, onToggle = { onPick(option) })
        }
    }
}

/**
 * Row of on/off chips: [labels] with the current state [checked] of each, a tap reports the index of the chip.
 * [enabled] = false greys the row out, e.g. a parameter whose resource the brush does not have.
 */
@Composable
internal fun ToggleChipRow(
    labels: List<String>,
    checked: List<Boolean>,
    enabled: Boolean = true,
    onToggle: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((index, label) in labels.withIndex()) {
            ToggleChip(
                label = label,
                checked = checked.getOrElse(index) { false },
                enabled = enabled,
                onToggle = { onToggle(index) },
            )
        }
    }
}
