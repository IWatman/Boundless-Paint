package app.painter.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** A toggle in a [MiniToolbar]. */
data class MiniToolbarItem(
    val icon: ImageVector,
    val contentDescription: String,
    val checked: Boolean,
    val onClick: () -> Unit,
)

/**
 * Small vertical toolbar of an active tool (stacked at the top-right). The first icon is the tool
 * itself; tapping it closes the tool. Remaining items are toggles.
 */
@Composable
fun MiniToolbar(
    toolIcon: ImageVector,
    toolDescription: String,
    onClose: () -> Unit,
    items: List<MiniToolbarItem>,
    modifier: Modifier = Modifier,
) {
    PanelColumn(modifier.padding(2.dp)) {
        IconToggle(toolIcon, toolDescription, checked = true, onCheckedChange = { onClose() })
        if (items.isNotEmpty()) {
            HorizontalDivider(Modifier.width(28.dp).padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        items.forEach { item ->
            IconToggle(item.icon, item.contentDescription, item.checked, onCheckedChange = { item.onClick() })
        }
    }
}
