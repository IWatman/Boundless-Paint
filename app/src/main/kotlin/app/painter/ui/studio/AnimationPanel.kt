package app.painter.ui.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.doc.AnimationMeta
import app.painter.core.doc.AnimationPlayback
import app.painter.ui.components.IconToggle
import app.painter.ui.components.PainterIconButton
import app.painter.ui.components.PanelSurface

/**
 * Bottom animation timeline: frame thumbnails, transport controls, playback mode, fps and onion-skin
 * distances. On a document without frames it only offers to turn the active layer into frame 1.
 */
@Composable
internal fun AnimationPanel(
    state: StudioUiState,
    actions: StudioActions,
    modifier: Modifier = Modifier,
) {
    val animation = state.animation
    PanelSurface(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.animation_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                if (animation != null) {
                    PlaybackMenu(animation, actions)
                    IconToggle(
                        Icons.Outlined.Visibility,
                        stringResource(R.string.animation_onion_skin),
                        checked = animation.onionSkin,
                        onCheckedChange = actions::setAnimationOnionSkin,
                    )
                    PainterIconButton(
                        Icons.Outlined.Videocam,
                        stringResource(
                            if (state.video == null) R.string.video_mode_menu else R.string.video_timeline_menu,
                        ),
                        onClick = {
                            if (state.video == null) actions.startVideoMode() else actions.toggleVideoPanel()
                        },
                        modifier = Modifier.size(36.dp),
                    )
                }
                PainterIconButton(
                    Icons.Outlined.Close,
                    stringResource(R.string.close),
                    onClick = actions::toggleAnimationPanel,
                    modifier = Modifier.size(36.dp),
                )
            }

            if (animation == null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.animation_start_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Button(onClick = actions::startAnimation) {
                        Text(stringResource(R.string.animation_start))
                    }
                }
                return@Column
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PainterIconButton(
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    stringResource(R.string.animation_previous),
                    onClick = { actions.stepAnimationFrame(-1) },
                    modifier = Modifier.size(36.dp),
                )
                PainterIconButton(
                    if (animation.playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    stringResource(if (animation.playing) R.string.animation_stop else R.string.animation_play),
                    onClick = { actions.setAnimationPlaying(!animation.playing) },
                    modifier = Modifier.size(36.dp),
                    tint = if (animation.playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                PainterIconButton(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    stringResource(R.string.animation_next),
                    onClick = { actions.stepAnimationFrame(1) },
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(6.dp))
                PainterIconButton(
                    Icons.Outlined.Add,
                    stringResource(R.string.animation_add_frame),
                    onClick = actions::addAnimationFrame,
                    modifier = Modifier.size(36.dp),
                )
                PainterIconButton(
                    Icons.Outlined.Delete,
                    stringResource(R.string.animation_delete_frame),
                    onClick = actions::deleteAnimationFrame,
                    modifier = Modifier.size(36.dp),
                    enabled = animation.frames.size > 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${animation.activeIndex + 1}/${animation.frames.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyRow(
                Modifier.fillMaxWidth().height(78.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(animation.frames, key = { it.id }) { frame ->
                    FrameThumb(
                        frame = frame,
                        active = frame.index == animation.activeIndex,
                        onClick = { actions.selectAnimationFrame(frame.id) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CountStepper(
                    label = stringResource(R.string.animation_fps),
                    value = animation.fps,
                    min = AnimationMeta.MIN_FPS,
                    max = AnimationMeta.MAX_FPS,
                    onChange = actions::setAnimationFps,
                )
                if (animation.onionSkin) {
                    CountStepper(
                        label = stringResource(R.string.animation_onion_before),
                        value = animation.onionBefore,
                        min = 0,
                        max = AnimationMeta.MAX_ONION_FRAMES,
                        onChange = { actions.setAnimationOnionFrames(it, animation.onionAfter) },
                    )
                    CountStepper(
                        label = stringResource(R.string.animation_onion_after),
                        value = animation.onionAfter,
                        min = 0,
                        max = AnimationMeta.MAX_ONION_FRAMES,
                        onChange = { actions.setAnimationOnionFrames(animation.onionBefore, it) },
                    )
                }
            }
        }
    }
}

/** One frame thumbnail; the active frame gets the accent border. */
@Composable
private fun FrameThumb(frame: AnimationFrameUi, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Column(
        Modifier
            .width(58.dp)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    if (active) 2.dp else 1.dp,
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            frame.thumbnail?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.animation_frame_label, frame.index + 1),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                )
            }
        }
        Text(
            stringResource(R.string.animation_frame_number, frame.index + 1),
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/** Small "- value +" stepper used for fps and the onion-skin distances. */
@Composable
private fun CountStepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(end = 2.dp),
        )
        PainterIconButton(
            Icons.Outlined.Remove,
            contentDescription = null,
            onClick = { onChange((value - 1).coerceAtLeast(min)) },
            modifier = Modifier.size(30.dp),
            enabled = value > min,
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        PainterIconButton(
            Icons.Outlined.Add,
            contentDescription = null,
            onClick = { onChange((value + 1).coerceAtMost(max)) },
            modifier = Modifier.size(30.dp),
            enabled = value < max,
        )
    }
}

/** Playback mode menu ("Loop", "Ping-pong", "Play once"). */
@Composable
private fun PlaybackMenu(animation: AnimationUi, actions: StudioActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(playbackLabel(animation.playback)),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ArrowDropDown, stringResource(R.string.animation_playback))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (mode in AnimationPlayback.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(playbackLabel(mode))) },
                    trailingIcon = {
                        if (animation.playback == mode) {
                            Icon(Icons.Outlined.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        open = false
                        actions.setAnimationPlayback(mode)
                    },
                )
            }
        }
    }
}

private fun playbackLabel(mode: AnimationPlayback): Int = when (mode) {
    AnimationPlayback.LOOP -> R.string.animation_loop
    AnimationPlayback.PINGPONG -> R.string.animation_pingpong
    AnimationPlayback.ONCE -> R.string.animation_once
}
