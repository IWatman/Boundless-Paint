package app.painter.ui.studio

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import app.painter.ui.components.PainterIconButton
import app.painter.ui.components.PanelSurface
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

private val VIDEO_RULER_HEIGHT = 28.dp
private val VIDEO_LANE_HEIGHT = 52.dp
private val VIDEO_TRACK_HEADER_WIDTH = 96.dp

/**
 * Read-only video timeline (slice S1): header, duration, transport placeholder, ruler, drawing tracks and
 * playhead. Scrubbing and clip selection only change UI state; playback, editing, audio and export are not
 * part of this slice and their controls are omitted or disabled.
 */
@Composable
internal fun VideoPanel(
    state: StudioUiState,
    actions: StudioActions,
    modifier: Modifier = Modifier,
) {
    val video = state.video ?: return
    PanelSurface(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            VideoHeader(video, actions)
            VideoTransport(video)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column(Modifier.width(VIDEO_TRACK_HEADER_WIDTH)) {
                    Spacer(Modifier.height(VIDEO_RULER_HEIGHT))
                    for (track in video.tracks) {
                        VideoTrackHeader(
                            track = track,
                            modifier = Modifier.fillMaxWidth().height(VIDEO_LANE_HEIGHT),
                        )
                    }
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
                    }
                    PlayheadOverlay(video, Modifier.matchParentSize())
                }
            }
            Text(
                stringResource(R.string.video_readonly_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Header: title, fixed 60 fps label, total duration, a way back to the frames and close. */
@Composable
private fun VideoHeader(video: VideoUi, actions: StudioActions) {
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
            Icons.Outlined.Close,
            stringResource(R.string.close),
            onClick = actions::toggleVideoPanel,
            modifier = Modifier.size(36.dp),
        )
    }
}

/** Transport placeholder: playback is not implemented in S1, so the button stays disabled. */
@Composable
private fun VideoTransport(video: VideoUi) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PainterIconButton(
            Icons.Outlined.PlayArrow,
            stringResource(R.string.video_play_unavailable),
            onClick = {},
            modifier = Modifier.size(36.dp),
            enabled = false,
        )
        Text(
            stringResource(
                R.string.video_time,
                formatVideoTime(video.playheadTick),
                formatVideoTime(video.durationTicks),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Eye, name and duration of one track. The eye mirrors the persisted state; it is not editable in S1. */
@Composable
private fun VideoTrackHeader(track: VideoTrackUi, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
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
    BoxWithConstraints(
        modifier
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .clickable { actions.selectVideoClip(null) },
    ) {
        val total = maxOf(1L, video.durationTicks)
        val totalFloat = total.toFloat()
        for (clip in track.clips) {
            val start = maxWidth * (clip.startTick.coerceIn(0L, total) / totalFloat)
            val clipWidth = maxOf(
                2.dp,
                maxWidth * (clip.durationTicks.coerceIn(0L, total) / totalFloat),
            )
            VideoClipBox(
                clip = clip,
                selected = clip.id == video.selectedClipId,
                dimmed = !track.visible,
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

/** One clip rectangle with a thumbnail where it fits and the frame name. */
@Composable
private fun VideoClipBox(
    clip: VideoClipUi,
    selected: Boolean,
    dimmed: Boolean,
    actions: StudioActions,
    modifier: Modifier = Modifier,
) {
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
    Row(
        modifier
            .alpha(if (dimmed) 0.5f else 1f)
            .clip(shape)
            .background(background)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clickable { actions.selectVideoClip(clip.id) }
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp),
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
private fun formatVideoTime(ticks: Long): String {
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
