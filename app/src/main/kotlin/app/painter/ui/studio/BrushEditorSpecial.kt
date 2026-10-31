package app.painter.ui.studio

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.ColorStampParams
import app.painter.core.stroke.DoubleParams
import app.painter.core.stroke.FillBrushParams
import app.painter.core.stroke.FillContent
import app.painter.core.stroke.FilterBrushParams
import app.painter.core.stroke.FilterKind
import app.painter.core.stroke.HarmonyParams
import app.painter.core.stroke.HarmonyVariant
import app.painter.core.stroke.ParticlesParams
import app.painter.core.stroke.ScreentoneParams
import app.painter.core.stroke.ScreentoneShape
import app.painter.core.stroke.SpecialParams
import app.painter.core.stroke.ThreeDKind
import app.painter.core.stroke.ThreeDParams
import app.painter.core.stroke.WatercolorParams
import app.painter.ui.components.DevRow
import app.painter.ui.components.ParamSlider
import app.painter.ui.components.formatSliderValue
import app.painter.ui.components.parseHexColor
import app.painter.ui.components.toHexString

/**
 * The special engine of the brush as the tab's chooser lists it: none plus every [SpecialParams] subclass, in the order
 * of spec/tools/m2-brush.md §4.2. [default] is what picking the type starts from; none means a plain stroke.
 */
private enum class SpecialType(val default: SpecialParams?) {
    NONE(null),
    WATERCOLOR(WatercolorParams()),
    PARTICLES(ParticlesParams()),
    HARMONY(HarmonyParams()),
    DOUBLE(DoubleParams()),
    COLOR_STAMP(ColorStampParams()),
    SCREENTONE(ScreentoneParams()),
    FILL(FillBrushParams()),
    FILTER(FilterBrushParams()),
    THREE_D(ThreeDParams()),
}

/** Special types split into rows that fit the editor panel. */
private val SpecialTypeRows = SpecialType.entries.chunked(2)

/** Harmony variants split into rows that fit the editor panel. */
private val HarmonyVariantRows = HarmonyVariant.entries.chunked(3)

/** Variants with their own rule; the rest fall back to SKETCHY (HarmonyEffect TODO S7a) and are disabled. */
private val SupportedHarmonyVariants = setOf(HarmonyVariant.SKETCHY, HarmonyVariant.SHADED, HarmonyVariant.WEB)

/**
 * Special tab of the brush editor (spec/tools/m2-brush.md §4.2): one chip row picks the special engine, the sliders and
 * chips below edit the fields of the picked [SpecialParams] subclass. Picking another type replaces the group with its
 * defaults; picking the type that is already set keeps the edited values, so a tap on the current chip is harmless.
 *
 * Every change goes to [StudioActions.updateEditor].
 */
@Composable
internal fun SpecialTab(params: BrushParams, actions: StudioActions) {
    val special = params.special

    EditorSection(stringResource(R.string.brush_editor_special_type))
    for (row in SpecialTypeRows) {
        ChipRow(row, special.type(), { stringResource(specialTypeLabel(it)) }) { type ->
            val next = when {
                type == SpecialType.NONE -> null
                special != null && special.type() == type -> special
                else -> type.default
            }
            actions.updateEditor(params.copy(special = next))
        }
    }

    when (special) {
        null -> Text(
            stringResource(R.string.brush_editor_special_none_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp),
        )

        is WatercolorParams -> WatercolorControls(special) { actions.updateEditor(params.copy(special = it)) }
        is ParticlesParams -> ParticlesControls(special) { actions.updateEditor(params.copy(special = it)) }
        is HarmonyParams -> HarmonyControls(special) { actions.updateEditor(params.copy(special = it)) }
        is DoubleParams -> DoubleControls(special) { actions.updateEditor(params.copy(special = it)) }
        is ColorStampParams -> ColorStampControls(special) { actions.updateEditor(params.copy(special = it)) }
        is ScreentoneParams -> ScreentoneControls(special) { actions.updateEditor(params.copy(special = it)) }
        is FillBrushParams -> FillControls(special) { actions.updateEditor(params.copy(special = it)) }
        is FilterBrushParams -> FilterControls(special) { actions.updateEditor(params.copy(special = it)) }
        is ThreeDParams -> ThreeDControls(special) { actions.updateEditor(params.copy(special = it)) }
    }
}

/** Watercolour (S7): how far the paint bleeds, the glaze, the canvas colour mixed in and the dryout of the stroke. */
@Composable
private fun WatercolorControls(params: WatercolorParams, onChange: (WatercolorParams) -> Unit) {
    ParamSlider(
        label = stringResource(R.string.brush_editor_wc_bleed),
        value = params.bleed,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { onChange(params.copy(bleed = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_wc_glaze),
        value = params.glaze,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { onChange(params.copy(glaze = it)) },
    )
    // The engine does not pick up the layer colour yet (WetSimulation TODO S8b): disabled, not a lying slider.
    DevRow(label = stringResource(R.string.brush_editor_paint_mix))
    ParamSlider(
        label = stringResource(R.string.brush_editor_wc_dryout),
        value = params.dryout,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { onChange(params.copy(dryout = it)) },
    )
}

/** Particles (S8): the attractor count, how many particles the stroke spawns and how they move and are stamped. */
@Composable
private fun ParticlesControls(params: ParticlesParams, onChange: (ParticlesParams) -> Unit) {
    ParamSlider(
        label = stringResource(R.string.brush_editor_particles_attractors),
        value = params.attractors.toFloat(),
        range = 0f..32f,
        format = { formatSliderValue(it, 1f, 0) },
        step = 1f,
        onChange = { onChange(params.copy(attractors = it.toInt().coerceAtLeast(0))) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_particles_count),
        value = params.particles.toFloat(),
        range = 1f..200f,
        format = { formatSliderValue(it, 1f, 0) },
        step = 1f,
        onChange = { onChange(params.copy(particles = it.toInt().coerceAtLeast(1))) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_particles_radius),
        value = params.radius,
        range = 0.1f..5f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.05f,
        onChange = { onChange(params.copy(radius = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_particles_size),
        value = params.size,
        range = 0.01f..0.5f,
        format = { formatSliderValue(it, 1f, 3) },
        step = 0.005f,
        onChange = { onChange(params.copy(size = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_particles_overshoot),
        value = params.overshoot,
        range = 0f..2f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { onChange(params.copy(overshoot = it)) },
    )
    ToggleChipRow(
        labels = listOf(stringResource(R.string.brush_editor_particles_trail)),
        checked = listOf(params.trail),
    ) { onChange(params.copy(trail = !params.trail)) }
}

/** Harmony (S6): the variant plus how far the copies spread and how dense the pattern is. */
@Composable
private fun HarmonyControls(params: HarmonyParams, onChange: (HarmonyParams) -> Unit) {
    EditorSection(stringResource(R.string.brush_editor_harmony_variant))
    for (row in HarmonyVariantRows) {
        ChipRow(
            row,
            params.variant,
            { stringResource(harmonyVariantLabel(it)) },
            enabled = { it in SupportedHarmonyVariants },
            dev = { it !in SupportedHarmonyVariants },
        ) { variant ->
            onChange(params.copy(variant = variant))
        }
    }
    ParamSlider(
        label = stringResource(R.string.brush_editor_harmony_spread),
        value = params.spread,
        range = 0f..4f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { onChange(params.copy(spread = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_harmony_density),
        value = params.density,
        range = 0f..4f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { onChange(params.copy(density = it)) },
    )
}

/** Double brush (S9): a body copy and an offset shadow copy, each with its own colour and size. */
@Composable
private fun DoubleControls(params: DoubleParams, onChange: (DoubleParams) -> Unit) {
    // The body copy still keeps the stroke colour: DoubleEffect does not apply bodyColor yet (TODO S6b).
    DevRow(label = stringResource(R.string.brush_editor_double_body_color))
    HexColorRow(
        label = stringResource(R.string.brush_editor_double_shadow_color),
        hint = "#000000",
        value = params.shadowColor,
    ) { color -> if (color != null) onChange(params.copy(shadowColor = color)) }
    ParamSlider(
        label = stringResource(R.string.brush_editor_double_shadow_size),
        value = params.shadowSize,
        range = 0f..50f,
        format = { formatSliderValue(it, 1f, 1) },
        step = 0.5f,
        onChange = { onChange(params.copy(shadowSize = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_angle),
        value = params.angle,
        range = 0f..360f,
        format = { formatSliderValue(it, 1f, 0) },
        step = 1f,
        unit = "°",
        onChange = { onChange(params.copy(angle = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_double_distance),
        value = params.distance,
        range = -100f..100f,
        format = { formatSliderValue(it, 1f, 1) },
        step = 0.5f,
        onChange = { onChange(params.copy(distance = it)) },
    )
    ToggleChipRow(
        labels = listOf(stringResource(R.string.brush_editor_double_texture_on_shadow)),
        checked = listOf(params.textureOnShadow),
        enabled = false,
        dev = true,
    ) { onChange(params.copy(textureOnShadow = !params.textureOnShadow)) }
}

/**
 * One hex colour field of the double brush: the model stores a `#RRGGBB` string, an edit is committed once the text
 * parses. Empty text reports null, which the model reads as "the colour of the stroke" for the body copy.
 */
@Composable
private fun HexColorRow(label: String, hint: String, value: String?, onCommit: (String?) -> Unit) {
    val hex = value.orEmpty().removePrefix("#")
    var text by remember(hex) { mutableStateOf(hex) }
    val parsed = parseHexColor(text)
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.take(6)
            when {
                text.isEmpty() -> onCommit(null)
                else -> parseHexColor(text)?.let { onCommit("#" + it.toHexString().lowercase()) }
            }
        },
        singleLine = true,
        isError = text.isNotEmpty() && parsed == null,
        prefix = { Text("#") },
        label = { Text(label) },
        placeholder = { Text(hint) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** Colour stamp (S9): how much of the dabs take the picked colour instead of the head's own. */
@Composable
private fun ColorStampControls(params: ColorStampParams, onChange: (ColorStampParams) -> Unit) {
    ParamSlider(
        label = stringResource(R.string.brush_editor_color_stamp_colorize),
        value = params.colorize,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { onChange(params.copy(colorize = it)) },
    )
}

/** Screentone (S9): the cell spacing, its angle, the cell shape and whether the cells follow the brush size. */
@Composable
private fun ScreentoneControls(params: ScreentoneParams, onChange: (ScreentoneParams) -> Unit) {
    EditorSection(stringResource(R.string.brush_editor_screentone_shape))
    ChipRow(ScreentoneShape.entries, params.shape, { stringResource(screentoneShapeLabel(it)) }) { shape ->
        onChange(params.copy(shape = shape))
    }
    ParamSlider(
        label = stringResource(R.string.brush_editor_spacing),
        value = params.spacing,
        range = 1f..50f,
        format = { formatSliderValue(it, 1f, 1) },
        step = 0.5f,
        onChange = { onChange(params.copy(spacing = it)) },
    )
    ParamSlider(
        label = stringResource(R.string.brush_editor_angle),
        value = params.angle,
        range = 0f..360f,
        format = { formatSliderValue(it, 1f, 0) },
        step = 1f,
        unit = "°",
        onChange = { onChange(params.copy(angle = it)) },
    )
    ToggleChipRow(
        labels = listOf(stringResource(R.string.brush_editor_texture_scale_with_size)),
        checked = listOf(params.scaleWithSize),
    ) { onChange(params.copy(scaleWithSize = !params.scaleWithSize)) }
}

/** Fill brush (S9): what the stroke fills with and whether the preview draws it. */
@Composable
private fun FillControls(params: FillBrushParams, onChange: (FillBrushParams) -> Unit) {
    EditorSection(stringResource(R.string.brush_editor_fill_content))
    ChipRow(
        FillContent.entries,
        params.content,
        { stringResource(fillContentLabel(it)) },
        enabled = { it == FillContent.SOLID },
        dev = { it != FillContent.SOLID },
    ) { content ->
        onChange(params.copy(content = content))
    }
    ToggleChipRow(
        labels = listOf(stringResource(R.string.brush_editor_fill_preview)),
        checked = listOf(params.drawPreview),
    ) { onChange(params.copy(drawPreview = !params.drawPreview)) }
}

/** Filter brush (S9): the filter itself, whether it runs while the stroke is drawn, and its strength. */
@Composable
private fun FilterControls(params: FilterBrushParams, onChange: (FilterBrushParams) -> Unit) {
    EditorSection(stringResource(R.string.brush_editor_filter_kind))
    ChipRow(FilterKind.entries, params.filter, { stringResource(filterKindLabel(it)) }) { kind ->
        onChange(params.copy(filter = kind))
    }
    ToggleChipRow(
        labels = listOf(stringResource(R.string.brush_editor_filter_on_stroke)),
        checked = listOf(params.onStroke),
    ) { onChange(params.copy(onStroke = !params.onStroke)) }
    ParamSlider(
        label = stringResource(R.string.brush_editor_filter_strength),
        value = params.strength,
        range = 0f..1f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { onChange(params.copy(strength = it)) },
    )
}

/** 3D (S9): the solid the stroke is swept into and how fast it twists along the stroke. */
@Composable
private fun ThreeDControls(params: ThreeDParams, onChange: (ThreeDParams) -> Unit) {
    EditorSection(stringResource(R.string.brush_editor_3d_kind))
    ChipRow(ThreeDKind.entries, params.kind, { stringResource(threeDKindLabel(it)) }) { kind ->
        onChange(params.copy(kind = kind))
    }
    ParamSlider(
        label = stringResource(R.string.brush_editor_3d_twist_rate),
        value = params.twistRate,
        range = 0f..4f,
        format = { formatSliderValue(it, 1f, 2) },
        step = 0.01f,
        onChange = { onChange(params.copy(twistRate = it)) },
    )
}

/** Type of the set special group, for the chooser's highlighted chip; none stands for a plain stroke. */
private fun SpecialParams?.type(): SpecialType = when (this) {
    null -> SpecialType.NONE
    is WatercolorParams -> SpecialType.WATERCOLOR
    is ParticlesParams -> SpecialType.PARTICLES
    is HarmonyParams -> SpecialType.HARMONY
    is DoubleParams -> SpecialType.DOUBLE
    is ColorStampParams -> SpecialType.COLOR_STAMP
    is ScreentoneParams -> SpecialType.SCREENTONE
    is FillBrushParams -> SpecialType.FILL
    is FilterBrushParams -> SpecialType.FILTER
    is ThreeDParams -> SpecialType.THREE_D
}

/** Localized name of a special engine type. */
private fun specialTypeLabel(type: SpecialType): Int = when (type) {
    SpecialType.NONE -> R.string.brush_editor_special_none
    SpecialType.WATERCOLOR -> R.string.brush_editor_special_watercolor
    SpecialType.PARTICLES -> R.string.brush_editor_special_particles
    SpecialType.HARMONY -> R.string.brush_editor_special_harmony
    SpecialType.DOUBLE -> R.string.brush_editor_special_double
    SpecialType.COLOR_STAMP -> R.string.brush_editor_special_color_stamp
    SpecialType.SCREENTONE -> R.string.brush_editor_special_screentone
    SpecialType.FILL -> R.string.brush_editor_special_fill
    SpecialType.FILTER -> R.string.brush_editor_special_filter
    SpecialType.THREE_D -> R.string.brush_editor_special_three_d
}

/** Localized name of a harmony pattern (spec/tools/m2-brush.md §4.2). */
private fun harmonyVariantLabel(variant: HarmonyVariant): Int = when (variant) {
    HarmonyVariant.SKETCHY -> R.string.brush_editor_harmony_sketchy
    HarmonyVariant.SHADED -> R.string.brush_editor_harmony_shaded
    HarmonyVariant.CHROME -> R.string.brush_editor_harmony_chrome
    HarmonyVariant.FUR -> R.string.brush_editor_harmony_fur
    HarmonyVariant.LONG_FUR -> R.string.brush_editor_harmony_long_fur
    HarmonyVariant.WEB -> R.string.brush_editor_harmony_web
    HarmonyVariant.SQUARES -> R.string.brush_editor_harmony_squares
    HarmonyVariant.CIRCLES -> R.string.brush_editor_harmony_circles
    HarmonyVariant.RIBBON -> R.string.brush_editor_harmony_ribbon
    HarmonyVariant.CURVY -> R.string.brush_editor_harmony_curvy
    HarmonyVariant.GRID -> R.string.brush_editor_harmony_grid
}

/** Localized name of a screentone cell shape. */
private fun screentoneShapeLabel(shape: ScreentoneShape): Int = when (shape) {
    ScreentoneShape.DOT -> R.string.brush_editor_screentone_dot
    ScreentoneShape.LINE -> R.string.brush_editor_screentone_line
    ScreentoneShape.CROSS -> R.string.brush_editor_screentone_cross
}

/** Localized name of a fill content. */
private fun fillContentLabel(content: FillContent): Int = when (content) {
    FillContent.SOLID -> R.string.brush_editor_fill_solid
    FillContent.PATTERN -> R.string.brush_editor_fill_pattern
    FillContent.PROCEDURAL -> R.string.brush_editor_fill_procedural
}

/** Localized name of a filter brush kind. */
private fun filterKindLabel(kind: FilterKind): Int = when (kind) {
    FilterKind.BLUR -> R.string.brush_editor_filter_blur
    FilterKind.SHARPEN -> R.string.brush_editor_filter_sharpen
    FilterKind.GLOW -> R.string.brush_editor_filter_glow
}

/** Localized name of a 3D solid. */
private fun threeDKindLabel(kind: ThreeDKind): Int = when (kind) {
    ThreeDKind.TUBE -> R.string.brush_editor_3d_tube
    ThreeDKind.RIBBON -> R.string.brush_editor_3d_ribbon
    ThreeDKind.TWISTY -> R.string.brush_editor_3d_twisty
}
