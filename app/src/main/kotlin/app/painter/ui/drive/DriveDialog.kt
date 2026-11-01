package app.painter.ui.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.ui.studio.StudioActions
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Google Drive account linking, backup and restore. The Google sheet appears when [StudioActions.driveConnect]
 * or the first token request runs; this dialog only shows the local account and the remote project list.
 */
@Composable
fun DriveDialog(state: DriveUiState, actions: StudioActions, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.account == null) {
                    Text(
                        stringResource(R.string.drive_intro),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = actions::driveConnect,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.drive_connect))
                    }
                } else {
                    Text(
                        stringResource(R.string.drive_connected_as, state.account.email),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.account.displayName?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.lastSyncMs > 0L) {
                        Text(
                            stringResource(R.string.drive_last_sync, formatDate(state.lastSyncMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = actions::driveSyncCurrent,
                        enabled = !state.busy && state.canSyncCurrent,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.drive_backup_current))
                    }
                    OutlinedButton(
                        onClick = actions::driveSyncAll,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.drive_backup_all))
                    }
                    OutlinedButton(
                        onClick = actions::driveSaveDrawing,
                        enabled = !state.busy && state.canSyncCurrent,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.drive_save_drawing))
                    }
                    Text(
                        stringResource(R.string.drive_folders_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.drive_backups),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = actions::driveRefreshBackups, enabled = !state.busy) {
                            Text(stringResource(R.string.drive_refresh))
                        }
                    }
                    if (state.backups.isEmpty()) {
                        Text(
                            stringResource(
                                if (state.loaded) R.string.drive_no_backups else R.string.drive_loading_backups,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.backups.take(10).forEach { backup ->
                            BackupRow(backup, enabled = !state.busy, onRestore = { actions.driveRestore(backup.id) })
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    TextButton(
                        onClick = actions::driveDisconnect,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.drive_sign_out))
                    }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                state.message?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun BackupRow(backup: DriveBackupUi, enabled: Boolean, onRestore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                backup.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatDate(backup.modifiedMs) + " · " + formatSize(backup.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRestore, enabled = enabled) {
            Text(stringResource(R.string.drive_restore))
        }
    }
}

private fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
