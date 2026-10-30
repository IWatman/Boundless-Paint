package app.painter.ui.studio

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.TextureMode
import app.painter.core.stroke.TextureParams
import app.painter.ui.components.ParamSlider
import app.painter.ui.components.formatSliderValue

/** Texture modes split into rows that fit the editor panel. */
private val TextureModeRows = TextureMode.entries.chunked(2)

/**
 * Texture tab of the brush editor (spec/tools/m2-brush.md §4.2, §1.4): the grain switch, the UV mode, depth, lower
 * limit, the mapping (scale, stretch, angle, softness), invert, gradation and the three texture-jitter switches.
 *
 * The grain itself is the brush's texture image, which the tab does not choose: a brush without one gets a disabled
 * switch and a hint. Turning the switch on starts from default [TextureParams] and keeps the image the brush
 * already references, so the resource is never lost. Every change goes to [StudioActions.updateEditor].
 */
@Composable
internal fun TextureTab(params: BrushParams, actions: StudioActions, hasImage: Boolean) {
    val texture = params.texture

    /** Applies an edit of the texture group, starting from the defaults when the grain is still off. */
    fun setTexture(f: (TextureParams) -> TextureParams) {
        actions.updateEditor(params.copy(texture = f(texture ?: TextureParams())))
    }

    ToggleChipRow(
        labels = listOf(stringResource(R.string.brush_editor_texture_enable)),
        checked = listOf(texture != null),
        enabled = hasImage,
    ) {
        actions.updateEditor(params.copy(texture = if (texture == null) TextureParams() else null))
    }
    if (!hasImage) {
        Text(
            stringResource(R.string.brush_editor_texture_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        )
    }
    if (texture == null) return

    EditorSection(stringResource(R.string.brush_editor_texture_mode))
    for (row in TextureModeRows) {
        ChipRow(row, texture.mode, { stringResource(textureModeLabel(it)) }) { mode ->
            setTexture { t -> t.copy(mode = mode) }
        }
    }
    ParamSlider(
        label = stringResource(R.string.brush_editor_depth),
        value = texture.depth,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setTexture { t -> t.copy(depth = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_texture_lower_limit),
        value = texture.lowerLimit,
        range = 0f..1f,
        format = { formatSliderValue(it, 100f, 0) },
        step = 0.01f,
        unit = "%",
        onChange = { setTexture { t -> t.copy(lowerLimit = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_texture_scale),
        value = texture.scale,
        range = 0.05f..10f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.05f,
        onChange = { setTexture { t -> t.copy(scale = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_texture_stretch),
        value = texture.stretch,
        range = 0.1f..10f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.1f,
        onChange = { setTexture { t -> t.copy(stretch = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_angle),
        value = texture.angle,
        range = 0f..360f,
        format = { formatSliderValue(it, 1f, 0) },
        step = 1f,
        unit = "°",
        onChange = { setTexture { t -> t.copy(angle = it) } },
    )
    // Softness is baked into the texture image when the resources are built (§1.4): the view model rebuilds the
    // preview resources from the working copy for exactly this reason.
    ParamSlider(
        label = stringResource(R.string.brush_editor_texture_softness),
        value = texture.softness,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setTexture { t -> t.copy(softness = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_texture_gradation),
        value = texture.gradation,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setTexture { t -> t.copy(gradation = it) } },
    )

    EditorSection(stringResource(R.string.brush_editor_texture_jitter))
    ToggleChipRow(
        labels = listOf(stringResource(R.string.brush_editor_texture_jitter_pos)),
        checked = listOf(texture.jitterPos),
    ) { setTexture { t -> t.copy(jitterPos = !t.jitterPos) } }
    ToggleChipRow(
        labels = listOf(stringResource(R.string.brush_editor_texture_jitter_scale)),
        checked = listOf(texture.jitterScale),
    ) { setTexture { t -> t.copy(jitterScale = !t.jitterScale) } }
    ToggleChipRow(
        labels = listOf(stringResource(R.string.brush_editor_texture_jitter_angle)),
        checked = listOf(texture.jitterAngle),
    ) { setTexture { t -> t.copy(jitterAngle = !t.jitterAngle) } }
    ToggleChipRow(
        labels = listOf(
            stringResource(R.string.brush_editor_texture_invert),
            stringResource(R.string.brush_editor_texture_scale_with_size),
        ),
        checked = listOf(texture.invert, texture.scaleWithSize),
    ) { index ->
        setTexture { t -> if (index == 0) t.copy(invert = !t.invert) else t.copy(scaleWithSize = !t.scaleWithSize) }
    }
}

/** Localized label of a texture mode: where the UV of the grain comes from (spec/tools/m2-brush.md §1.4). */
private fun textureModeLabel(mode: TextureMode): Int = when (mode) {
    TextureMode.CANVAS_FIXED -> R.string.brush_editor_texture_mode_canvas
    TextureMode.ROTATE_WITH_STROKE -> R.string.brush_editor_texture_mode_rotate
    TextureMode.WARP_ALONG_STROKE -> R.string.brush_editor_texture_mode_warp
    TextureMode.MOVING -> R.string.brush_editor_texture_mode_moving
}
