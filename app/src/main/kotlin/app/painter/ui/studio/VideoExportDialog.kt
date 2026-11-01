package app.painter.ui.studio

import android.app.ActivityManager
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.doc.VIDEO_TICKS_PER_SECOND
import app.painter.core.video.AudioMix
import app.painter.core.video.VideoExportPlan
import app.painter.studio.LayerLimit
import app.painter.studio.VideoExportOptions
import app.painter.studio.videoExportPresetLabel
import app.painter.video.VideoEncoders
import java.util.Locale

/**
 * Export format/size dialog (spec/VIDEO-MODE.md §5.7). The chips mirror the `VideoEncoderCaps` probe exactly:
 * 720p/1080p/1440p at 60 fps, 4K at 60 fps when supported, otherwise the explicit 4K/30 fallback. A 4K chip is
 * disabled with a reason when the encoder or the memory guard refuses it; there is never a silent rate change.
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
    val largeHeapBytes = remember {
        context.getSystemService(ActivityManager::class.java)
            ?.largeMemoryClass?.toLong()?.times(1024L * 1024L) ?: 0L
    }
    val layerBudget = largeHeapBytes * 4L / 10L
    val encoder = remember { VideoEncoders.best() }
    val choices = remember(encoder, canvasWidth, canvasHeight, largeHeapBytes) {
        encoder?.let {
            VideoExportPlan.choices(canvasWidth, canvasHeight, caps = it, largeHeapBytes = largeHeapBytes)
        } ?: emptyList()
    }
    var selected by remember(choices) { mutableStateOf(VideoExportPlan.defaultChoice(choices)) }
    val active = selected?.takeIf { it.supported } ?: VideoExportPlan.defaultChoice(choices)
    val activeLabel = active?.let { videoExportChoiceLabel(it) } ?: ""
    val plan = active?.plan
    val outputFps = plan?.fps ?: VideoExportPlan.FPS
    val frames = VideoExportPlan.frameCount(video.durationTicks, outputFps)
    val frameLayers = video.tracks
        .flatMap { track -> track.clips.filter { !it.missing }.mapNotNull { it.frameId } }
        .distinct()
        .size
    val missingFrames = video.tracks.sumOf { track -> track.clips.count { it.missing } }
    val audio = video.audio
    val hasAudioClips = audio != null && audio.clips.isNotEmpty()
    val audioMuted = audio?.muted == true
    val audioMissing = audio?.clips?.any { it.missing } == true
    val canIncludeAudio = hasAudioClips && !audioMuted && !audioMissing
    var includeAudio by remember(canIncludeAudio) { mutableStateOf(canIncludeAudio) }
    val audioBeyondEnd = audio?.clips?.any { it.startTick + it.durationTicks > video.durationTicks } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.video_export_duration, formatVideoTime(video.durationTicks)))
                Text(stringResource(R.string.video_export_frames, frames, outputFps))
                Text(stringResource(R.string.video_export_format))
                if (hasAudioClips) {
                    Text(
                        stringResource(R.string.video_export_audio_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = includeAudio,
                            onCheckedChange = { includeAudio = it },
                            enabled = canIncludeAudio,
                        )
                        Text(
                            stringResource(R.string.video_export_include_audio),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (!canIncludeAudio) {
                        Text(
                            stringResource(
                                when {
                                    audioMuted -> R.string.video_export_audio_muted
                                    audioMissing -> R.string.video_export_audio_missing
                                    else -> R.string.video_export_audio_none
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (audioMissing) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (audioBeyondEnd) {
                        Text(
                            stringResource(R.string.video_export_audio_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (choices.isNotEmpty()) {
                    Text(stringResource(R.string.video_export_quality), Modifier.padding(top = 4.dp))
                    // Two short rows keep four chips inside the dialog on a phone.
                    for (row in choices.chunked(3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (choice in row) {
                                ToggleChip(
                                    label = videoExportChoiceLabel(choice),
                                    checked = choice.longSide == active?.longSide && choice.fps == active?.fps,
                                    enabled = choice.supported,
                                    onToggle = { selected = choice },
                                )
                            }
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
                            VideoExportPlan.Refusal.ENCODER -> if (choice.longSide >= VideoExportPlan.FOUR_K_LONG_SIDE) {
                                stringResource(R.string.video_export_encoder_reason_4k)
                            } else {
                                stringResource(
                                    R.string.video_export_encoder_reason,
                                    videoExportPresetLabel(choice.longSide),
                                    choice.fps,
                                )
                            }
                            VideoExportPlan.Refusal.MEMORY -> {
                                val needed = choice.size?.let {
                                    VideoExportPlan.estimatedPeakBytes(it.width, it.height)
                                } ?: 0L
                                stringResource(
                                    R.string.video_export_memory_reason,
                                    Formatter.formatShortFileSize(context, needed),
                                    Formatter.formatShortFileSize(context, largeHeapBytes),
                                )
                            }
                            null -> continue
                        }
                        Text(
                            stringResource(
                                R.string.video_export_unsupported_item,
                                videoExportChoiceLabel(choice),
                                reason,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                plan?.let {
                    val audioBytes = if (includeAudio) {
                        AudioMix.BIT_RATE.toLong() * video.durationTicks / (8L * VIDEO_TICKS_PER_SECOND)
                    } else {
                        0L
                    }
                    Text(
                        stringResource(
                            R.string.video_export_estimate,
                            Formatter.formatShortFileSize(
                                context,
                                VideoExportPlan.estimateBytes(it, video.durationTicks) + audioBytes,
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (layerBudget > 0L) {
                        val canvasLayerLimit = LayerLimit.maxLayers(canvasWidth, canvasHeight, layerBudget)
                        val exportLayerLimit = LayerLimit.maxLayers(it.width, it.height, layerBudget)
                        when {
                            frameLayers > canvasLayerLimit -> {
                                Text(
                                    stringResource(
                                        R.string.video_export_layer_limit_canvas,
                                        canvasLayerLimit,
                                        frameLayers,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            it.longSide >= VideoExportPlan.FOUR_K_LONG_SIDE && frameLayers > exportLayerLimit -> {
                                Text(
                                    stringResource(
                                        R.string.video_export_layer_limit,
                                        activeLabel,
                                        exportLayerLimit,
                                        Formatter.formatShortFileSize(context, layerBudget),
                                        frameLayers,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
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
                    plan?.let {
                        onConfirm(
                            VideoExportOptions(
                                longSide = it.longSide,
                                outputFps = it.fps,
                                includeAudio = includeAudio,
                            ),
                        )
                    }
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

/** Chip label; the 4K rate is always explicit, lower presets run at the timeline's 60 fps. */
@Composable
private fun videoExportChoiceLabel(choice: VideoExportPlan.Choice): String = when {
    choice.longSide >= VideoExportPlan.FOUR_K_LONG_SIDE &&
        choice.fps == VideoExportPlan.FALLBACK_FPS -> stringResource(R.string.video_export_4k30)

    choice.longSide >= VideoExportPlan.FOUR_K_LONG_SIDE -> stringResource(R.string.video_export_4k60)
    else -> videoExportPresetLabel(choice.longSide)
}

private fun formatElapsed(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1000L)
    return String.format(Locale.US, "%d:%02d", seconds / 60L, seconds % 60L)
}
