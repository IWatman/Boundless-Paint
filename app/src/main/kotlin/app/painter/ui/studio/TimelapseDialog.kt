package app.painter.ui.studio

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Timelapse export (M3): duration 15/30/60 s or every recorded frame, quality 720p/1080p/4K, then the system
 * file picker. The H.264 encoding happens on IO; the activity writes the MP4 to the picked document.
 */
@Composable
fun TimelapseDialog(state: StudioUiState, actions: StudioActions, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var duration by remember { mutableIntStateOf(30) }
    var quality by remember { mutableIntStateOf(1080) }
    var pendingDuration = 30
    var pendingQuality = 1080
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) scope.launch {
            val bytes = actions.encodeTimelapse(pendingDuration, pendingQuality)
            if (bytes == null) {
                Toast.makeText(context, R.string.timelapse_empty, Toast.LENGTH_LONG).show()
                onDismiss()
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri) ?: error("no output stream")
                    stream.use { it.write(bytes) }
                }.onFailure { android.util.Log.e("PainterTimelapse", "write failed", it) }.isSuccess
            }
            Toast.makeText(
                context,
                if (ok) R.string.timelapse_exported else R.string.timelapse_failed,
                Toast.LENGTH_LONG,
            ).show()
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timelapse_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.timelapseFrames == 0) {
                    Text(stringResource(R.string.timelapse_empty))
                } else {
                    Text(stringResource(R.string.timelapse_frames, state.timelapseFrames))
                }
                Text(stringResource(R.string.timelapse_duration))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sec in listOf(15, 30, 60)) {
                        ToggleChip(label = "${sec}s", checked = duration == sec, onToggle = { duration = sec })
                    }
                    ToggleChip(
                        label = stringResource(R.string.timelapse_full),
                        checked = duration == 0,
                        onToggle = { duration = 0 },
                    )
                }
                Text(stringResource(R.string.timelapse_quality))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (side in listOf(720, 1080, 2160)) {
                        ToggleChip(
                            label = if (side >= 2160) "4K" else "${side}p",
                            checked = quality == side,
                            onToggle = { quality = side },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.timelapseFrames > 0,
                onClick = {
                    pendingDuration = duration
                    pendingQuality = quality
                    picker.launch("timelapse.mp4")
                },
            ) { Text(stringResource(R.string.timelapse_export)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
