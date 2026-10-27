package app.painter.ui.studio

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.doc.EditTarget
import app.painter.core.history.MaskInit

/** ⋮ menu of one layer row: layer commands, grouping, masks, colour tag, lock and reference. */
@Composable
fun LayerRowMenu(
    layer: LayerUi,
    canMergeDown: Boolean,
    canDelete: Boolean,
    canDuplicate: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    actions: StudioActions,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (layer.isAdjustment) {
            MenuItem(stringResource(R.string.adjust), onDismiss, { actions.editAdjustment(layer.id) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        MenuItem(stringResource(R.string.rename), onDismiss, onRename)
        MenuItem(stringResource(R.string.duplicate), onDismiss, {
            actions.duplicateLayer(layer.id)
        }, enabled = canDuplicate)
        MenuItem(stringResource(R.string.clear), onDismiss, {
            actions.clearLayer(layer.id)
        })
        MenuItem(stringResource(R.string.merge_down), onDismiss, {
            actions.mergeDown(layer.id)
        }, enabled = canMergeDown)
        MenuItem(stringResource(R.string.delete), onDismiss, {
            actions.deleteLayer(layer.id)
        }, enabled = canDelete)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (layer.isGroup) {
            MenuItem(stringResource(R.string.ungroup_layers), onDismiss, {
                actions.ungroupLayer(layer.id)
            })
        } else {
            MenuItem(stringResource(R.string.group_layers), onDismiss, {
                actions.groupLayer(layer.id)
            })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (layer.hasMask) {
            MenuItem(stringResource(R.string.mask_target), onDismiss, {
                actions.selectEditTarget(layer.id, EditTarget.MASK)
            })
            MenuItem(
                stringResource(if (layer.maskEnabled) R.string.mask_disable else R.string.mask_enable),
                onDismiss,
                { actions.setMaskEnabled(layer.id, !layer.maskEnabled) },
            )
            MenuItem(stringResource(R.string.mask_invert), onDismiss, {
                actions.invertMask(layer.id)
            })
            MenuItem(stringResource(R.string.mask_apply), onDismiss, {
                actions.applyMask(layer.id)
            })
            MenuItem(stringResource(R.string.mask_delete), onDismiss, {
                actions.deleteMask(layer.id)
            })
        } else {
            MenuItem(stringResource(R.string.mask_reveal_all), onDismiss, {
                actions.addMask(layer.id, MaskInit.REVEAL_ALL)
            })
            MenuItem(stringResource(R.string.mask_hide_all), onDismiss, {
                actions.addMask(layer.id, MaskInit.HIDE_ALL)
            })
            MenuItem(stringResource(R.string.mask_from_alpha), onDismiss, {
                actions.addMask(layer.id, MaskInit.FROM_ALPHA)
            })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            stringResource(R.string.color_tag),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
        )
        ColorTagDots(layer.colorTag) { tag -> actions.setLayerColorTag(layer.id, tag) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MenuItem(
            stringResource(if (layer.locked) R.string.layer_locked else R.string.layer_lock),
            onDismiss,
            { actions.setLayerLocked(layer.id, !layer.locked) },
        )
        MenuItem(
            stringResource(if (layer.reference) R.string.clear_reference else R.string.set_reference),
            onDismiss,
            { actions.setLayerReference(layer.id, !layer.reference) },
        )
    }
}

@Composable
private fun MenuItem(
    label: String,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    DropdownMenuItem(
        text = { Text(label) },
        enabled = enabled,
        onClick = {
            onDismiss()
            onClick()
        },
    )
}
