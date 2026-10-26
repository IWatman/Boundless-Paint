package app.painter.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.update.UpdateUiState

/**
 * In-app update dialog: shows the check result, release notes, download progress and errors.
 *
 * The dialog starts a check itself when opened in [UpdateUiState.Idle].
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    currentVersion: String,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (state is UpdateUiState.Idle) onCheck()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.updates)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.update_current_version, currentVersion),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                UpdateStateBody(state, onCheck, onUpdate)
            }
        },
        confirmButton = { UpdateConfirmButton(state, onCheck, onUpdate) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_close)) }
        },
    )
}

@Composable
private fun UpdateStateBody(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
) {
    when (state) {
        UpdateUiState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.update_checking), style = MaterialTheme.typography.bodyMedium)
        }
        UpdateUiState.UpToDate -> Text(
            stringResource(R.string.update_up_to_date),
            style = MaterialTheme.typography.bodyMedium,
        )
        is UpdateUiState.Available -> Column {
            Text(
                stringResource(R.string.update_new_version, state.info.tagName),
                style = MaterialTheme.typography.titleSmall,
            )
            val notes = state.info.notes.trim()
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.update_notes_title), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    notes,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
        is UpdateUiState.Downloading -> {
            val progress = state.progress
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.update_downloading, ((progress ?: 0f) * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        UpdateUiState.Installing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.bodyMedium)
        }
        UpdateUiState.NeedsPermission -> Text(
            stringResource(R.string.update_needs_permission),
            style = MaterialTheme.typography.bodyMedium,
        )
        is UpdateUiState.Error -> Text(
            stringResource(R.string.update_error, state.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        UpdateUiState.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.update_checking), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UpdateConfirmButton(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
) {
    when (state) {
        is UpdateUiState.Available -> TextButton(onClick = onUpdate) {
            Text(stringResource(R.string.update_now))
        }
        is UpdateUiState.Error -> TextButton(onClick = onCheck) {
            Text(stringResource(R.string.update_retry))
        }
        UpdateUiState.NeedsPermission -> TextButton(onClick = onUpdate) {
            Text(stringResource(R.string.update_grant))
        }
        UpdateUiState.UpToDate -> TextButton(onClick = onCheck) {
            Text(stringResource(R.string.update_check))
        }
        else -> Unit
    }
}
