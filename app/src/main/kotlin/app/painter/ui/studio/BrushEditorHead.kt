package app.painter.ui.studio

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.painter.R
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.HeadParams
import app.painter.core.stroke.HeadSource
import app.painter.core.stroke.JitterParams
import app.painter.core.stroke.RotationMode
import app.painter.ui.components.ParamSlider
import app.painter.ui.components.formatSliderValue

/** Head sources the editor offers: a generated mask (PROCEDURAL) has no generator yet (spec §1.1). */
private val HeadSources = listOf(HeadSource.ROUND, HeadSource.BITMAP)

/** Rotation modes split into rows that fit the editor panel. */
private val RotationRows = RotationMode.entries.chunked(3)

/**
 * Head tab of the brush editor (spec/tools/m2-brush.md §4.2): the dab source, its shape (hardness, aspect, angle),
 * how the angle follows the stroke, the bristle depth and the per-dab jitter of [JitterParams]. Spacing and flow
 * are stroke parameters and stay on the Stroke tab. Every change goes to [StudioActions.updateEditor], so the
 * preview re-renders.
 *
 * The bitmap head image itself is not chosen here: switching to [HeadSource.BITMAP] keeps the image the brush
 * already references, and importing a PNG head comes with the browser (§4.1).
 */
@Composable
internal fun HeadTab(params: BrushParams, actions: StudioActions) {
    val head = params.head

    /** Applies an edit of the head group on top of the working copy. */
    fun setHead(f: (HeadParams) -> HeadParams) = actions.updateEditor(params.copy(head = f(head)))

    /** Applies an edit of the jitter group on top of the working copy. */
    fun setJitter(f: (JitterParams) -> JitterParams) = actions.updateEditor(params.copy(jitter = f(params.jitter)))

    EditorSection(stringResource(R.string.brush_editor_head_source))
    ChipRow(HeadSources, head.source, { stringResource(headSourceLabel(it)) }) { source ->
        setHead { it.copy(source = source) }
    }
    ParamSlider(
        label = stringResource(R.string.hardness),
        value = params.hardness,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { actions.updateEditor(params.copy(hardness = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_aspect),
        value = head.aspect,
        range = -1f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setHead { h -> h.copy(aspect = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_angle),
        value = head.angle,
        range = 0f..360f,
        format = { formatSliderValue(it, 1f, 0) },
        step = 1f,
        unit = "°",
        onChange = { setHead { h -> h.copy(angle = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_depth),
        value = head.depth,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setHead { h -> h.copy(depth = it) } },
    )

    EditorSection(stringResource(R.string.brush_editor_rotation))
    for (row in RotationRows) {
        ChipRow(row, head.rotationMode, { stringResource(rotationModeLabel(it)) }) { mode ->
            setHead { it.copy(rotationMode = mode) }
        }
    }
    // Follow only matters for the two modes that turn the dab along the stroke (§1.2).
    if (head.rotationMode == RotationMode.TANGENT || head.rotationMode == RotationMode.REVERSE) {
        ParamSlider(
            label = stringResource(R.string.brush_editor_rotation_follow),
            value = head.rotationFollow,
            range = 0f..1f,
            format = { formatSliderValue(it, 1f, 2) },
            step = 0.01f,
            onChange = { setHead { h -> h.copy(rotationFollow = it) } },
        )
    }
    ToggleChipRow(
        labels = listOf(stringResource(R.string.brush_editor_antialias)),
        checked = listOf(head.antialias),
    ) { setHead { h -> h.copy(antialias = !h.antialias) } }

    EditorSection(stringResource(R.string.brush_editor_jitter))
    val jitter = params.jitter
    ParamSlider(
        label = stringResource(R.string.brush_editor_jitter_scatter),
        value = jitter.scatter,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setJitter { j -> j.copy(scatter = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_jitter_size),
        value = jitter.size,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setJitter { j -> j.copy(size = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_jitter_angle),
        value = jitter.angle,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setJitter { j -> j.copy(angle = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_jitter_initial_angle),
        value = jitter.initialAngle,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setJitter { j -> j.copy(initialAngle = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_jitter_flow),
        value = jitter.flow,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setJitter { j -> j.copy(flow = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_jitter_opacity),
        value = jitter.opacity,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setJitter { j -> j.copy(opacity = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_jitter_spacing),
        value = jitter.spacing,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { setJitter { j -> j.copy(spacing = it) } },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_jitter_count),
        value = jitter.count.toFloat(),
        range = 1f..16f,
        format = { formatSliderValue(it, 1f, 0) },
        step = 1f,
        onChange = { setJitter { j -> j.copy(count = it.toInt().coerceAtLeast(1)) } },
    )
}

/** Localized label of a head source: the two the editor offers and the procedural mask as a round dab (§1.1). */
private fun headSourceLabel(source: HeadSource): Int = when (source) {
    HeadSource.BITMAP -> R.string.brush_editor_head_bitmap
    HeadSource.ROUND, HeadSource.PROCEDURAL -> R.string.brush_editor_head_round
}

/** Localized label of a rotation mode (spec/tools/m2-brush.md §1.2). */
private fun rotationModeLabel(mode: RotationMode): Int = when (mode) {
    RotationMode.FIXED -> R.string.brush_editor_rotation_fixed
    RotationMode.TANGENT -> R.string.brush_editor_rotation_tangent
    RotationMode.REVERSE -> R.string.brush_editor_rotation_reverse
    RotationMode.STYLUS_AZIMUTH -> R.string.brush_editor_rotation_azimuth
    RotationMode.STYLUS_TILT -> R.string.brush_editor_rotation_tilt
    RotationMode.TWIST -> R.string.brush_editor_rotation_twist
    RotationMode.RANDOM -> R.string.brush_editor_rotation_random
}
