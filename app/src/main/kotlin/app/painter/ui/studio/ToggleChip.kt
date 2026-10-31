package app.painter.ui.studio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.painter.ui.components.DevBadge
import app.painter.ui.theme.AccentFill
import app.painter.ui.theme.PainterTheme

/** Compact height of a toggle chip, so that a bar of toggles stays one short row. */
private val ToggleChipHeight = 32.dp

/**
 * On/off toggle of a tool bar: an accent fill and a check mark while [checked], a muted label otherwise, so that
 * a toggle reads differently from the plain action buttons next to it. [enabled] = false greys it out.
 */
@Composable
fun ToggleChip(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    dev: Boolean = false,
    onToggle: () -> Unit,
) {
    FilterChip(
        selected = checked,
        onClick = onToggle,
        enabled = enabled,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                if (dev) DevBadge()
            }
        },
        leadingIcon = if (checked) {
            {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
        modifier = Modifier.height(ToggleChipHeight),
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = AccentFill,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = checked,
            borderColor = PainterTheme.colors.panelBorder,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
        ),
    )
}
