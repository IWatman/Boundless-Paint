package app.painter.ui.studio

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.video.VideoExportPlan
import app.painter.studio.VideoExportOptions
import app.painter.studio.videoExportPresetLabel
import app.painter.video.VideoEncoders
import java.util.Locale

/**
 * Export format/size dialog of slice S3 (spec/VIDEO-MODE.md §5.7). Only presets the device encoder accepts at
 * 60 fps are enabled, and the reason is shown before the user can open the file picker. MP4/H.264 is fixed;
 * audio is not offered until S4.
 */
@Composable
internal fun VideoExportDialog(
    video: VideoUi,
    canvasWidth: Int,
    canvasHeight: Int,
    onDismiss: () -> Unit,
    onConfirm: (VideoExportOptions) -> Unit,
) {
    val context = LocalContext.current
    val encoder = remember { VideoEncoders.best() }
    val choices = remember(encoder, canvasWidth, canvasHeight) {
        encoder?.let { VideoExportPlan.choices(canvasWidth, canvasHeight, caps = it) } ?: emptyList()
    }
    val defaultLongSide = remember(choices) { VideoExportPlan.defaultLongSide(choices) }
    var selectedLongSide by remember(choices) { mutableStateOf(defaultLongSide) }
    val active = choices.firstOrNull {
        it.longSide == (selectedLongSide ?: defaultLongSide) && it.supported
    }
    val plan = active?.plan
    val frames = VideoExportPlan.frameCount(video.durationTicks, VideoExportPlan.FPS)
    val missingFrames = video.tracks.sumOf { track -> track.clips.count { it.missing } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.video_export_duration, formatVideoTime(video.durationTicks)))
                Text(stringResource(R.string.video_export_frames, frames, VideoExportPlan.FPS))
                Text(stringResource(R.string.video_export_format))
                Text(
                    stringResource(R.string.video_export_no_audio_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (choices.isNotEmpty()) {
                    Text(stringResource(R.string.video_export_quality), Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (choice in choices) {
                            ToggleChip(
                                label = videoExportPresetLabel(choice.longSide),
                                checked = choice.longSide == (selectedLongSide ?: defaultLongSide),
                                enabled = choice.supported,
                                onToggle = { selectedLongSide = choice.longSide },
                            )
                        }
                    }
                }
                if (encoder == null) {
                    Text(
                        stringResource(R.string.video_export_no_encoder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    for (choice in choices.filter { !it.supported }) {
                        val reason = when (choice.refusal) {
                            VideoExportPlan.Refusal.SIZE -> stringResource(R.string.video_export_size_reason)
                            VideoExportPlan.Refusal.ENCODER -> stringResource(
                                R.string.video_export_encoder_reason,
                                videoExportPresetLabel(choice.longSide),
                                VideoExportPlan.FPS,
                            )
                            null -> continue
                        }
                        Text(
                            stringResource(
                                R.string.video_export_unsupported_item,
                                videoExportPresetLabel(choice.longSide),
                                reason,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                plan?.let {
                    Text(
                        stringResource(
                            R.string.video_export_estimate,
                            Formatter.formatShortFileSize(
                                context,
                                VideoExportPlan.estimateBytes(it, video.durationTicks),
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (missingFrames > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.video_export_missing_frames,
                            missingFrames,
                            missingFrames,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (video.durationTicks <= 0L) {
                    Text(
                        stringResource(R.string.video_export_no_frames),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = plan != null && video.durationTicks > 0L,
                onClick = {
                    plan?.let { onConfirm(VideoExportOptions(it.longSide, it.fps)) }
                },
            ) {
                Text(stringResource(R.string.video_export_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Modal progress/result dialog of the export (spec/VIDEO-MODE.md §5.8). While running the only control is
 * Cancel; Back cancels too. A finished result shows the outcome and a Close button.
 */
@Composable
internal fun VideoExportProgressDialog(
    ui: VideoExportUi,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = ui.outcome == VideoExportOutcome.RUNNING
    val title = if (running) {
        when (ui.phase) {
            VideoExportPhase.PREPARING -> stringResource(R.string.video_export_phase_preparing)
            VideoExportPhase.RENDERING -> stringResource(R.string.video_export_phase_rendering)
            VideoExportPhase.WRITING -> stringResource(R.string.video_export_phase_writing)
        }
    } else {
        stringResource(R.string.video_export_title)
    }
    AlertDialog(
        onDismissRequest = { if (running) onCancel() else onDismiss() },
        title = { Text(title) },
        text = {
            if (running) {
                val total = ui.totalFrames
                val completed = ui.completedFrames.coerceIn(0L, maxOf(0L, total))
                val percent = if (total > 0L) (completed * 100L / total).toInt() else 0
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (total > 0L) {
                        LinearProgressIndicator(
                            progress = { completed.toFloat() / total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.video_export_progress, completed, total, percent),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (ui.elapsedMs > 0L) {
                        Text(
                            stringResource(R.string.video_export_elapsed, formatElapsed(ui.elapsedMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(
                            if (ui.phase == VideoExportPhase.WRITING) {
                                R.string.video_export_partial_note
                            } else {
                                R.string.video_export_cancel_note
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                when (ui.outcome) {
                    VideoExportOutcome.SUCCESS -> Text(stringResource(R.string.video_export_done))
                    VideoExportOutcome.CANCELLED -> Text(stringResource(R.string.video_export_cancelled))
                    VideoExportOutcome.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.video_export_failed))
                        Text(
                            stringResource(
                                R.string.video_export_failed_reason,
                                ui.message ?: stringResource(R.string.unknown_error),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    VideoExportOutcome.RUNNING -> Unit
                }
            }
        },
        confirmButton = {
            if (running) {
                TextButton(enabled = !ui.cancelling, onClick = onCancel) {
                    Text(
                        stringResource(
                            if (ui.cancelling) R.string.video_export_cancelling else R.string.cancel,
                        ),
                    )
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}

private fun formatElapsed(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1000L)
    return String.format(Locale.US, "%d:%02d", seconds / 60L, seconds % 60L)
}
