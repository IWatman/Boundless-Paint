package app.painter.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R

/**
 * Small "still in development" marker of a control that is visible in the UI but has no engine behind it yet.
 * The control itself is disabled next to it; the icon and the content description keep the state accessible.
 */
@Composable
fun DevBadge(modifier: Modifier = Modifier) {
    Icon(
        Icons.Outlined.Construction,
        contentDescription = stringResource(R.string.in_development),
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.size(14.dp).padding(start = 2.dp),
    )
}

/** A disabled row with a label and the development badge, replacing a control that is not implemented yet. */
@Composable
fun DevRow(label: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DevBadge()
    }
}
