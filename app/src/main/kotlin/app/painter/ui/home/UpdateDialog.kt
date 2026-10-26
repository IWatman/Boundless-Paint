package app.painter.ui.home

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
        is UpdateUiState.Updated -> Text(
            stringResource(R.string.update_done, state.version),
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
                    releaseNotesText(notes),
                    style = MaterialTheme.typography.bodySmall,
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
        UpdateUiState.UpToDate, is UpdateUiState.Updated -> TextButton(onClick = onCheck) {
            Text(stringResource(R.string.update_check))
        }
        else -> Unit
    }
}

/**
 * Minimal Markdown for release notes: `**bold**`, `- ` / `* ` bullets and `#` headings; other
 * syntax is shown as typed.
 */
internal fun releaseNotesText(md: String): AnnotatedString = buildAnnotatedString {
    md.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        var line = raw.trimEnd()
        val heading = line.startsWith("#")
        if (heading) line = line.trimStart('#').trimStart()
        val bullet = Regex("^(\\s*)[-*] ").find(line)
        if (bullet != null) line = bullet.groupValues[1] + "• " + line.substring(bullet.value.length)
        if (heading) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        var rest = line
        while (true) {
            val start = rest.indexOf("**")
            val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
            if (start < 0 || end < 0) { append(rest); break }
            append(rest.substring(0, start))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(rest.substring(start + 2, end)) }
            rest = rest.substring(end + 2)
        }
        if (heading) pop()
    }
}
