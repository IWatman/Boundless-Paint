package app.painter.ui.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.adjust.AdjustKind

/**
 * "Add adjustment" entry of the layer panel header: a row (icon only when the panel is collapsed) that
 * opens a menu with the [AdjustKind.ALL] kinds under their localized names.
 */
@Composable
fun AdjustmentAddEntry(actions: StudioActions, expanded: Boolean, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = stringResource(R.string.add_adjustment)) { menuOpen = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Tune,
                stringResource(R.string.add_adjustment),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Text(
                    stringResource(R.string.add_adjustment),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AdjustKindMenu(menuOpen, { menuOpen = false }) { kind ->
            menuOpen = false
            actions.addAdjustmentLayer(kind)
        }
    }
}

/** Menu listing every adjustment kind; [onPick] receives the [AdjustKind] id. */
@Composable
fun AdjustKindMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 520.dp),
    ) {
        Text(
            stringResource(R.string.add_adjustment),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        )
        AdjustKind.ALL.forEach { kind ->
            DropdownMenuItem(
                text = { Text(stringResource(adjustTitleRes(kind))) },
                onClick = {
                    onDismiss()
                    onPick(kind)
                },
            )
        }
    }
}
