package app.painter.ui.studio

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.painter.R
import app.painter.core.doc.VIDEO_TICKS_PER_SECOND
import app.painter.core.doc.VideoEdit
import app.painter.ui.components.IconToggle
import app.painter.ui.components.PainterIconButton
import app.painter.ui.components.PanelSurface
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val VIDEO_RULER_HEIGHT = 28.dp
private val VIDEO_LANE_HEIGHT = 52.dp
private val VIDEO_TRACK_HEADER_WIDTH = 96.dp
private val VIDEO_TRIM_HANDLE_WIDTH = 10.dp
private val VIDEO_SNAP_TOUCH_DP = 12.dp

/**
 * Bottom video timeline (slice S2): transport, ruler, drawing tracks, clip editing and the playhead.
 * Playback and scrubbing preview the clip's frame on the canvas; the panel owns its pointers so timeline
 * drags never reach the drawing canvas.
 */
@Composable
internal fun VideoPanel(
    state: StudioUiState,
    actions: StudioActions,
    modifier: Modifier = Modifier,
    onExport: () -> Unit = {},
) {
    val video = state.video ?: return
    PanelSurface(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            VideoHeader(video, actions, onExport)
            VideoTransport(video, actions)
            VideoEditBar(video, actions)
            VideoAudioBar(video, actions)
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Column(Modifier.width(VIDEO_TRACK_HEADER_WIDTH)) {
                    Spacer(Modifier.height(VIDEO_RULER_HEIGHT))
                    for (track in video.tracks) {
                        VideoTrackHeader(
                            track = track,
                            selected = track.id == video.selectedTrackId,
                            actions = actions,
                            modifier = Modifier.fillMaxWidth().height(VIDEO_LANE_HEIGHT),
                        )
                    }
                    VideoAudioHeader(
                        audio = video.audio,
                        actions = actions,
                        modifier = Modifier.fillMaxWidth().height(VIDEO_LANE_HEIGHT),
                    )
                }
                Box(Modifier.weight(1f)) {
                    Column {
                        VideoRuler(
                            durationTicks = video.durationTicks,
                            playheadTick = video.playheadTick,
                            onSeek = { fraction ->
                                actions.seekVideo((fraction * video.durationTicks).roundToLong())
                            },
                            modifier = Modifier.fillMaxWidth().height(VIDEO_RULER_HEIGHT),
                        )
                        for (track in video.tracks) {
                            VideoTrackLane(
                                video = video,
                                track = track,
                                actions = actions,
                                modifier = Modifier.fillMaxWidth().height(VIDEO_LANE_HEIGHT),
                            )
                        }
                        VideoAudioLane(
                            video = video,
                            actions = actions,
                            modifier = Modifier.fillMaxWidth().height(VIDEO_LANE_HEIGHT),
                        )
                    }
                    PlayheadOverlay(video, Modifier.matchParentSize())
                }
            }
        }
    }
}

/** Header: title, fixed 60 fps label, total duration, a way back to the frames and close. */
@Composable
private fun VideoHeader(video: VideoUi, actions: StudioActions, onExport: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(
            stringResource(R.string.video_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 6.dp),
        )
        Text(
            stringResource(R.string.video_fps),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            formatVideoTime(video.durationTicks),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = actions::toggleAnimationPanel) {
            Text(stringResource(R.string.video_frames_action))
        }
        PainterIconButton(
            Icons.Outlined.FileDownload,
            stringResource(R.string.video_export_action),
            onClick = onExport,
            modifier = Modifier.size(36.dp),
            enabled = video.durationTicks > 0L && !video.recordingAudio,
        )
        PainterIconButton(
            Icons.Outlined.Close,
            stringResource(R.string.close),
            onClick = actions::toggleVideoPanel,
            modifier = Modifier.size(36.dp),
        )
    }
}

/** Transport: stop/rewind, play/pause, current time and the preview-only loop switch. */
@Composable
private fun VideoTransport(video: VideoUi, actions: StudioActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val enabled = video.durationTicks > 0L && !video.recordingAudio
        PainterIconButton(
            Icons.Outlined.Stop,
            stringResource(R.string.video_stop),
            onClick = actions::stopVideo,
            modifier = Modifier.size(36.dp),
            enabled = enabled,
        )
        PainterIconButton(
            if (video.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            stringResource(if (video.playing) R.string.video_pause else R.string.video_play),
            onClick = { actions.setVideoPlaying(!video.playing) },
            modifier = Modifier.size(36.dp),
            enabled = enabled,
            tint = if (video.playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(
                R.string.video_time,
                formatVideoTime(video.playheadTick),
                formatVideoTime(video.durationTicks),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        IconToggle(
            Icons.Outlined.Loop,
            stringResource(R.string.video_loop_preview),
            checked = video.loopPreview,
            onCheckedChange = actions::setVideoLoopsPreview,
            modifier = Modifier.size(36.dp),
        )
        Text(
            stringResource(R.string.video_loop_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Add, blank, delete and snap controls above the tracks, plus the drawing-track menu. */
@Composable
private fun VideoEditBar(video: VideoUi, actions: StudioActions) {
    val track = video.tracks.firstOrNull { it.id == video.selectedTrackId } ?: video.tracks.firstOrNull()
    val selected = track?.clips?.firstOrNull { it.id == video.selectedClipId }
    val addFrameId = selected?.takeUnless { it.missing }?.frameId
        ?: track?.clips?.firstNotNullOfOrNull { if (it.missing) null else it.frameId }
    var deleteTrack by remember { mutableStateOf<VideoTrackUi?>(null) }
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PainterIconButton(
            Icons.Outlined.Add,
            stringResource(R.string.video_add_clip),
            onClick = { actions.addVideoClip(addFrameId) },
            modifier = Modifier.size(36.dp),
            enabled = addFrameId != null,
        )
        TextButton(onClick = { actions.addVideoClip(null) }, enabled = track != null) {
            Text(stringResource(R.string.video_add_blank))
        }
        PainterIconButton(
            Icons.Outlined.Delete,
            stringResource(R.string.video_delete_clip),
            onClick = { selected?.let { actions.deleteVideoClip(it.id) } },
            modifier = Modifier.size(36.dp),
            enabled = selected != null,
        )
        VideoTrackMenu(video, actions, onDeleteTrack = { deleteTrack = it })
        Spacer(Modifier.weight(1f))
        ToggleChip(
            label = stringResource(R.string.video_snap),
            checked = video.snapEnabled,
            onToggle = { actions.setVideoSnap(!video.snapEnabled) },
        )
    }
    deleteTrack?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTrack = null },
            title = { Text(stringResource(R.string.video_track_delete_title)) },
            text = { Text(stringResource(R.string.video_track_delete_message, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.deleteVideoTrack(target.id)
                        deleteTrack = null
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTrack = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Track list with selection, "add track" and a guarded "delete track"; one button keeps the edit bar short. */
@Composable
private fun VideoTrackMenu(
    video: VideoUi,
    actions: StudioActions,
    onDeleteTrack: (VideoTrackUi) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = video.tracks.firstOrNull { it.id == video.selectedTrackId }
    Box {
        PainterIconButton(
            Icons.Outlined.Layers,
            stringResource(R.string.video_tracks),
            onClick = { open = true },
            modifier = Modifier.size(36.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (track in video.tracks) {
                DropdownMenuItem(
                    text = { Text(track.name) },
                    leadingIcon = {
                        Icon(
                            if (track.id == video.selectedTrackId) Icons.Outlined.Check else Icons.Outlined.Layers,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        open = false
                        actions.selectVideoTrack(track.id)
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.video_track_add)) },
                enabled = video.canAddTrack && video.tracks.size < VideoEdit.MAX_TRACKS,
                onClick = {
                    open = false
                    actions.addVideoTrack()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.video_track_delete)) },
                enabled = video.tracks.size > 1 && selected != null,
                onClick = {
                    open = false
                    selected?.let(onDeleteTrack)
                },
            )
        }
    }
}

/** Import/record, audio clip actions and the three real gain controls of the audio track. */
@Composable
private fun VideoAudioBar(video: VideoUi, actions: StudioActions) {
    val audio = video.audio
    val clips = audio?.clips ?: emptyList()
    val hasClips = clips.isNotEmpty()
    val selected = clips.firstOrNull { it.id == video.selectedClipId }
    val muted = audio?.muted ?: false
    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PainterIconButton(
                Icons.Outlined.LibraryMusic,
                stringResource(R.string.video_audio_import),
                onClick = actions::importAudio,
                modifier = Modifier.size(36.dp),
                enabled = !video.recordingAudio,
            )
            PainterIconButton(
                if (video.recordingAudio) Icons.Outlined.Stop else Icons.Outlined.Mic,
                stringResource(
                    if (video.recordingAudio) R.string.video_audio_stop else R.string.video_audio_record,
                ),
                onClick = {
                    if (video.recordingAudio) actions.stopAudioRecording() else actions.startAudioRecording()
                },
                modifier = Modifier.size(36.dp),
                tint = if (video.recordingAudio) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            PainterIconButton(
                Icons.Outlined.Delete,
                stringResource(R.string.video_audio_delete_clip),
                onClick = { selected?.let { actions.deleteAudioClip(it.id) } },
                modifier = Modifier.size(36.dp),
                enabled = selected != null && !video.recordingAudio,
            )
            IconToggle(
                if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                stringResource(if (muted) R.string.video_audio_unmute else R.string.video_audio_mute),
                checked = muted,
                onCheckedChange = actions::setAudioMuted,
                modifier = Modifier.size(36.dp),
                enabled = hasClips,
            )
            if (video.recordingAudio) {
                Text(
                    stringResource(R.string.video_audio_recording, formatVideoTime(video.recordingMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (video.audioPreparing) {
                Text(
                    stringResource(R.string.video_audio_preparing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hasClips) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected != null) {
                    AudioVolumeSlider(
                        label = stringResource(R.string.video_audio_clip_volume),
                        value = selected.volume,
                        onChange = { actions.setAudioClipVolume(selected.id, it) },
                    )
                }
                AudioVolumeSlider(
                    label = stringResource(R.string.video_audio_track_volume),
                    value = audio?.volume ?: 1f,
                    onChange = actions::setAudioTrackVolume,
                )
                AudioVolumeSlider(
                    label = stringResource(R.string.video_audio_master_volume),
                    value = audio?.masterVolume ?: 1f,
                    onChange = actions::setAudioMasterVolume,
                )
            }
        }
        if (video.audioPreviewUnavailable) {
            Text(
                stringResource(R.string.video_audio_preview_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        video.audioMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** One 0..1 gain slider with a local drag value; the model is updated once, on release. */
@Composable
private fun AudioVolumeSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    var local by remember(label, value) { mutableStateOf(value.coerceIn(0f, 1f)) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = 0f..1f,
            modifier = Modifier.width(96.dp).padding(horizontal = 4.dp),
        )
        Text(
            "${(local * 100f).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp),
        )
    }
}

/** Speaker, name and end time of the audio track. */
@Composable
private fun VideoAudioHeader(
    audio: VideoAudioUi?,
    actions: StudioActions,
    modifier: Modifier = Modifier,
) {
    val clips = audio?.clips ?: emptyList()
    val muted = audio?.muted ?: false
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(enabled = clips.isNotEmpty()) { actions.setAudioMuted(!muted) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = stringResource(
                    if (muted) R.string.video_audio_unmute else R.string.video_audio_mute,
                ),
                modifier = Modifier.size(18.dp),
                tint = if (clips.isEmpty() || muted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                stringResource(R.string.video_audio_track),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatVideoTime(clips.maxOfOrNull { it.startTick + it.durationTicks } ?: 0L),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Lane of the audio track: overlapping clips share the lane, the hint invites the first import. */
@Composable
private fun VideoAudioLane(
    video: VideoUi,
    actions: StudioActions,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    val audio = video.audio
    val clips = audio?.clips ?: emptyList()
    BoxWithConstraints(
        modifier
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .clickable { actions.selectVideoClip(null) },
    ) {
        if (clips.isEmpty()) {
            Text(
                stringResource(R.string.video_audio_empty),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
            )
        } else {
            val total = maxOf(1L, video.durationTicks)
            val totalFloat = total.toFloat()
            val laneWidthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
            val threshold = VideoTimeline.deltaTicks(
                with(LocalDensity.current) { VIDEO_SNAP_TOUCH_DP.toPx() },
                laneWidthPx,
                total,
            )
            for (clip in clips) {
                val start = maxWidth * (clip.startTick.coerceIn(0L, total) / totalFloat)
                val clipWidth = maxOf(
                    2.dp,
                    maxWidth * (clip.durationTicks.coerceIn(0L, total) / totalFloat),
                )
                val targets = ArrayList<Long>(clips.size * 2 + 1)
                targets += video.playheadTick
                for (other in clips) {
                    if (other.id == clip.id) continue
                    targets += other.startTick
                    targets += other.startTick + other.durationTicks
                }
                AudioClipBox(
                    clip = clip,
                    selected = clip.id == video.selectedClipId,
                    dimmed = audio?.muted == true,
                    snapEnabled = video.snapEnabled,
                    snapTargets = targets,
                    snapThresholdTicks = threshold,
                    totalTicks = total,
                    laneWidthPx = laneWidthPx,
                    actions = actions,
                    modifier = Modifier
                        .offset(x = start)
                        .width(clipWidth)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** One audio clip rectangle: tap selects, long-press drags freely, the right edge trims (no ripple). */
@Composable
private fun AudioClipBox(
    clip: AudioClipUi,
    selected: Boolean,
    dimmed: Boolean,
    snapEnabled: Boolean,
    snapTargets: List<Long>,
    snapThresholdTicks: Long,
    totalTicks: Long,
    laneWidthPx: Float,
    actions: StudioActions,
    modifier: Modifier = Modifier,
) {
    var dragOffsetPx by remember(clip.id) { mutableStateOf(0f) }
    var trimStartDuration by remember(clip.id) { mutableStateOf(clip.durationTicks) }
    var trimDragPx by remember(clip.id) { mutableStateOf(0f) }
    val currentClip by rememberUpdatedState(clip)
    val currentSnapEnabled by rememberUpdatedState(snapEnabled)
    val currentSnapTargets by rememberUpdatedState(snapTargets)
    val currentSnapThreshold by rememberUpdatedState(snapThresholdTicks)
    val currentTotalTicks by rememberUpdatedState(totalTicks)
    val currentLaneWidth by rememberUpdatedState(laneWidthPx)
    val shape = RoundedCornerShape(4.dp)
    val background = if (clip.missing) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = if (clip.missing) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val description = stringResource(
        R.string.video_audio_clip_accessible,
        clip.name,
        formatVideoTime(clip.durationTicks),
    )
    val trimDescription = stringResource(R.string.video_audio_trim_clip)
    Row(
        modifier
            .graphicsLayer { translationX = dragOffsetPx }
            .alpha(if (dimmed) 0.5f else 1f)
            .clip(shape)
            .background(background)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .pointerInput(clip.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        actions.setVideoPlaying(false)
                        actions.selectVideoClip(clip.id)
                        dragOffsetPx = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffsetPx += amount.x
                    },
                    onDragEnd = {
                        val target = VideoTimeline.movedStart(
                            startTick = currentClip.startTick,
                            deltaPx = dragOffsetPx,
                            widthPx = currentLaneWidth,
                            durationTicks = currentTotalTicks,
                            snap = currentSnapEnabled,
                            targets = currentSnapTargets,
                            thresholdTicks = currentSnapThreshold,
                        )
                        dragOffsetPx = 0f
                        actions.moveAudioClip(currentClip.id, target)
                    },
                    onDragCancel = { dragOffsetPx = 0f },
                )
            }
            .clickable { actions.selectVideoClip(clip.id) }
            .semantics { contentDescription = description }
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (clip.missing) Icons.AutoMirrored.Outlined.VolumeOff else Icons.Outlined.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = content,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            if (clip.missing) stringResource(R.string.video_audio_clip_missing) else clip.name,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .width(VIDEO_TRIM_HANDLE_WIDTH)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.45f else 0.18f))
                .pointerInput(clip.id) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            actions.setVideoPlaying(false)
                            actions.selectVideoClip(clip.id)
                            trimStartDuration = currentClip.durationTicks
                            trimDragPx = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            trimDragPx += dragAmount
                        },
                        onDragEnd = {
                            val ticks = VideoTimeline.trimDuration(
                                startTick = currentClip.startTick,
                                currentDurationTicks = trimStartDuration,
                                deltaPx = trimDragPx,
                                widthPx = currentLaneWidth,
                                durationTicks = currentTotalTicks,
                                snap = currentSnapEnabled,
                                targets = currentSnapTargets,
                                thresholdTicks = currentSnapThreshold,
                            )
                            trimDragPx = 0f
                            actions.setAudioClipDuration(currentClip.id, ticks)
                        },
                        onDragCancel = { trimDragPx = 0f },
                    )
                }
                .semantics { contentDescription = trimDescription },
        )
    }
}

/** Ruler with second marks and labels; tap or drag seeks. */
@Composable
private fun VideoRuler(
    durationTicks: Long,
    playheadTick: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val textSize = with(density) { 10.sp.toPx() }
    val minLabelPx = with(density) { 44.dp.toPx() }
    val minTickPx = with(density) { 4.dp.toPx() }
    val top = with(density) { 14.dp.toPx() }
    val baseline = with(density) { 11.dp.toPx() }
    val description = stringResource(R.string.video_scrub)
    Canvas(
        modifier
            .semantics {
                contentDescription = description
                stateDescription = formatVideoTime(playheadTick)
            }
            .pointerInput(durationTicks) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width
                    if (width <= 0) return@awaitEachGesture
                    down.consume()
                    onSeek((down.position.x / width).coerceIn(0f, 1f))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        onSeek((change.position.x / width).coerceIn(0f, 1f))
                        change.consume()
                    }
                }
            },
    ) {
        val width = size.width
        if (width <= 0f) return@Canvas
        val total = durationTicks.coerceAtLeast(1L)
        val seconds = maxOf(1L, (total + VIDEO_TICKS_PER_SECOND - 1) / VIDEO_TICKS_PER_SECOND)
        val pxPerSecond = width / seconds.toFloat()

        paint.textSize = textSize
        paint.color = labelColor.toArgb()
        var labelStep = 1L
        for (candidate in longArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600)) {
            labelStep = candidate
            if (candidate * pxPerSecond >= minLabelPx || candidate >= seconds) break
        }

        var tick = 0L
        while (tick <= seconds) {
            val x = (tick * pxPerSecond).coerceAtMost(width)
            drawLine(
                color = tickColor,
                start = Offset(x, top + minTickPx),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            tick += maxOf(1L, ceil(minTickPx / pxPerSecond).toLong())
        }

        var labelTick = 0L
        while (labelTick <= seconds) {
            val x = (labelTick * pxPerSecond).coerceAtMost(width)
            drawLine(
                color = tickColor,
                start = Offset(x, top),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            val label = formatVideoClock(labelTick * VIDEO_TICKS_PER_SECOND)
            val tx = x + 2.dp.toPx()
            if (tx + paint.measureText(label) <= width) {
                drawContext.canvas.nativeCanvas.drawText(label, tx, baseline, paint)
            }
            labelTick += labelStep
        }
        drawLine(tickColor, Offset(0f, size.height), Offset(width, size.height), 1.dp.toPx())
    }
}

/** Eye, name and duration of one track; tapping the header selects the track for edits. */
@Composable
private fun VideoTrackHeader(
    track: VideoTrackUi,
    selected: Boolean,
    actions: StudioActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
                RoundedCornerShape(4.dp),
            )
            .clickable { actions.selectVideoTrack(track.id) }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable { actions.setVideoTrackVisible(track.id, !track.visible) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (track.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = stringResource(
                    if (track.visible) R.string.video_track_visible else R.string.video_track_hidden,
                ),
                modifier = Modifier.size(18.dp),
                tint = if (track.visible) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                track.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatVideoTime(track.clips.maxOfOrNull { it.startTick + it.durationTicks } ?: 0L),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Lane of one track: clip rectangles positioned by their tick range. Gaps stay transparent. */
@Composable
private fun VideoTrackLane(
    video: VideoUi,
    track: VideoTrackUi,
    actions: StudioActions,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    val density = LocalDensity.current
    val selected = track.id == video.selectedTrackId
    BoxWithConstraints(
        modifier
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                },
            )
            .clickable {
                actions.selectVideoTrack(track.id)
                actions.selectVideoClip(null)
            },
    ) {
        val total = maxOf(1L, video.durationTicks)
        val totalFloat = total.toFloat()
        val laneWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val snapThreshold = VideoTimeline.deltaTicks(
            with(density) { VIDEO_SNAP_TOUCH_DP.toPx() },
            laneWidthPx,
            total,
        )
        for (clip in track.clips) {
            val start = maxWidth * (clip.startTick.coerceIn(0L, total) / totalFloat)
            val clipWidth = maxOf(
                2.dp,
                maxWidth * (clip.durationTicks.coerceIn(0L, total) / totalFloat),
            )
            // Snap to the playhead and to the other clips, never to the clip being dragged.
            val targets = ArrayList<Long>(track.clips.size * 2 + 1)
            targets += video.playheadTick
            for (other in track.clips) {
                if (other.id == clip.id) continue
                targets += other.startTick
                targets += other.startTick + other.durationTicks
            }
            VideoClipBox(
                clip = clip,
                selected = clip.id == video.selectedClipId,
                dimmed = !track.visible,
                snapEnabled = video.snapEnabled,
                snapTargets = targets,
                snapThresholdTicks = snapThreshold,
                totalTicks = total,
                laneWidthPx = laneWidthPx,
                actions = actions,
                modifier = Modifier
                    .offset(x = start)
                    .width(clipWidth)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp),
            )
        }
    }
}

/** One clip rectangle: tap selects, long-press drags, the right edge trims. */
@Composable
private fun VideoClipBox(
    clip: VideoClipUi,
    selected: Boolean,
    dimmed: Boolean,
    snapEnabled: Boolean,
    snapTargets: List<Long>,
    snapThresholdTicks: Long,
    totalTicks: Long,
    laneWidthPx: Float,
    actions: StudioActions,
    modifier: Modifier = Modifier,
) {
    var dragOffsetPx by remember(clip.id) { mutableStateOf(0f) }
    var trimStartDuration by remember(clip.id) { mutableStateOf(clip.durationTicks) }
    var trimDragPx by remember(clip.id) { mutableStateOf(0f) }
    val currentClip by rememberUpdatedState(clip)
    val currentSnapEnabled by rememberUpdatedState(snapEnabled)
    val currentSnapTargets by rememberUpdatedState(snapTargets)
    val currentSnapThreshold by rememberUpdatedState(snapThresholdTicks)
    val currentTotalTicks by rememberUpdatedState(totalTicks)
    val currentLaneWidth by rememberUpdatedState(laneWidthPx)
    val shape = RoundedCornerShape(4.dp)
    val background = when {
        clip.missing -> MaterialTheme.colorScheme.errorContainer
        clip.frameId == null -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        clip.missing -> MaterialTheme.colorScheme.onErrorContainer
        clip.frameId == null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val description = stringResource(
        R.string.video_clip_accessible,
        clip.label,
        formatVideoTime(clip.durationTicks),
    )
    val trimDescription = stringResource(R.string.video_trim_clip)
    Row(
        modifier
            .graphicsLayer { translationX = dragOffsetPx }
            .alpha(if (dimmed) 0.5f else 1f)
            .clip(shape)
            .background(background)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .pointerInput(clip.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        actions.setVideoPlaying(false)
                        actions.selectVideoClip(clip.id)
                        dragOffsetPx = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffsetPx += amount.x
                    },
                    onDragEnd = {
                        val target = VideoTimeline.movedStart(
                            startTick = currentClip.startTick,
                            deltaPx = dragOffsetPx,
                            widthPx = currentLaneWidth,
                            durationTicks = currentTotalTicks,
                            snap = currentSnapEnabled,
                            targets = currentSnapTargets,
                            thresholdTicks = currentSnapThreshold,
                        )
                        dragOffsetPx = 0f
                        actions.moveVideoClip(currentClip.id, target)
                    },
                    onDragCancel = { dragOffsetPx = 0f },
                )
            }
            .clickable { actions.selectVideoClip(clip.id) }
            .semantics { contentDescription = description }
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbnail = clip.thumbnail
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(
            clip.label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .width(VIDEO_TRIM_HANDLE_WIDTH)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.45f else 0.18f))
                .pointerInput(clip.id) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            actions.setVideoPlaying(false)
                            actions.selectVideoClip(clip.id)
                            trimStartDuration = currentClip.durationTicks
                            trimDragPx = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            trimDragPx += dragAmount
                        },
                        onDragEnd = {
                            val ticks = VideoTimeline.trimDuration(
                                startTick = currentClip.startTick,
                                currentDurationTicks = trimStartDuration,
                                deltaPx = trimDragPx,
                                widthPx = currentLaneWidth,
                                durationTicks = currentTotalTicks,
                                snap = currentSnapEnabled,
                                targets = currentSnapTargets,
                                thresholdTicks = currentSnapThreshold,
                            )
                            trimDragPx = 0f
                            actions.setVideoClipDuration(currentClip.id, ticks)
                        },
                        onDragCancel = { trimDragPx = 0f },
                    )
                }
                .semantics { contentDescription = trimDescription },
        )
    }
}

/** Vertical playhead across the ruler and every lane. It creates no pointer input of its own. */
@Composable
private fun PlayheadOverlay(video: VideoUi, modifier: Modifier = Modifier) {
    val color: Color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val total = video.durationTicks.coerceAtLeast(1L)
        val fraction = (video.playheadTick.coerceIn(0L, total)).toFloat() / total
        val x = fraction * size.width
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(x, 0f))
    }
}

/** `m:ss.cc`; minutes are not split into hours, as the panel header asks for a short duration. */
internal fun formatVideoTime(ticks: Long): String {
    val centis = (ticks.coerceAtLeast(0L) * 100L + VIDEO_TICKS_PER_SECOND / 2) / VIDEO_TICKS_PER_SECOND
    val minutes = centis / 6000L
    val seconds = (centis / 100L) % 60L
    val fraction = centis % 100L
    return String.format(Locale.US, "%d:%02d.%02d", minutes, seconds, fraction)
}

/** `m:ss` (or `h:mm:ss`) for ruler labels. */
private fun formatVideoClock(ticks: Long): String {
    val totalSeconds = ticks.coerceAtLeast(0L) / VIDEO_TICKS_PER_SECOND
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
