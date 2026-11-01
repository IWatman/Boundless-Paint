package app.painter.ui.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.color.Rgba
import app.painter.core.fill.PatternKind
import app.painter.core.fill.PatternSampler
import app.painter.core.fill.PatternSpec
import app.painter.ui.components.ColorSwatch
import app.painter.ui.components.HsvUi
import app.painter.ui.components.ParamSlider
import app.painter.ui.components.toHexString
import app.painter.ui.components.toHsvUi
import app.painter.ui.components.toRgba
import app.painter.ui.theme.AccentFill
import app.painter.ui.theme.PainterTheme
import kotlin.math.roundToInt

/** Which of the two pattern colours the sliders below the swatches edit. */
private enum class PatternColorRole { BACKGROUND, INK }

/** Display name of a built-in pattern. */
private fun PatternKind.titleRes(): Int = when (this) {
    PatternKind.CHECKS -> R.string.pattern_checks
    PatternKind.DOTS -> R.string.pattern_dots
    PatternKind.DIAGONAL_STRIPES -> R.string.pattern_stripes
    PatternKind.BRICK -> R.string.pattern_brick
    PatternKind.HERRINGBONE -> R.string.pattern_herringbone
    PatternKind.CANVAS -> R.string.pattern_grain
    PatternKind.ZIGZAG -> R.string.pattern_zigzag
    PatternKind.WAVES -> R.string.pattern_waves
    PatternKind.TRIANGLES -> R.string.pattern_triangles
    PatternKind.STARS -> R.string.pattern_stars
    PatternKind.HEXAGONS -> R.string.pattern_hexagons
    PatternKind.WOOD_GRAIN -> R.string.pattern_wood_grain
}

/**
 * Pattern fill dialog (spec §09): one of twelve built-in procedural patterns blended between a background
 * and an ink colour, with tile size, rotation, phase offset, contrast, invert and opacity. Both swatches
 * start at the current ink. Every control updates the twelve live previews; Apply fills the active layer or
 * the selection as one undo step.
 */
@Composable
fun PatternDialog(
    ink: Rgba,
    onDismiss: () -> Unit,
    onApply: (PatternSpec, Rgba, Rgba, Float) -> Unit,
) {
    var kind by remember { mutableStateOf(PatternKind.CHECKS) }
    var tileSize by remember { mutableStateOf(64f) }
    var rotation by remember { mutableStateOf(0f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var invert by remember { mutableStateOf(false) }
    var opacity by remember { mutableStateOf(1f) }
    var editing by remember { mutableStateOf(PatternColorRole.BACKGROUND) }
    // The two colours are kept as HSV so that hue survives a trip through zero saturation or brightness.
    val alpha = ink.a.coerceIn(0f, 1f)
    var backgroundHsv by remember { mutableStateOf(ink.toHsvUi()) }
    var inkHsv by remember { mutableStateOf(ink.toHsvUi()) }
    val background = backgroundHsv.toRgba(alpha)
    val patternInk = inkHsv.toRgba(alpha)
    val spec = PatternSpec(kind, tileSize, rotation, offsetX, offsetY, contrast, invert)
    val previews = remember(spec, background, patternInk, opacity) {
        PatternKind.entries.associateWith { previewBitmap(spec.copy(kind = it), background, patternInk, opacity) }
    }
    val activeHsv = if (editing == PatternColorRole.BACKGROUND) backgroundHsv else inkHsv
    val setActiveHsv: (HsvUi) -> Unit = { value ->
        if (editing == PatternColorRole.BACKGROUND) backgroundHsv = value else inkHsv = value
    }
    val activeColor = if (editing == PatternColorRole.BACKGROUND) background else patternInk
    val percent = { v: Float -> "${(v * 100).roundToInt()}%" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pattern_fill_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (row in PatternKind.entries.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (option in row) {
                            PatternTile(
                                label = stringResource(option.titleRes()),
                                preview = previews.getValue(option),
                                selected = kind == option,
                                onPick = { kind = option },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PatternColorChoice(
                        label = stringResource(R.string.pattern_background),
                        color = background,
                        selected = editing == PatternColorRole.BACKGROUND,
                        onClick = { editing = PatternColorRole.BACKGROUND },
                        modifier = Modifier.weight(1f),
                    )
                    PatternColorChoice(
                        label = stringResource(R.string.pattern_ink),
                        color = patternInk,
                        selected = editing == PatternColorRole.INK,
                        onClick = { editing = PatternColorRole.INK },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            if (editing == PatternColorRole.BACKGROUND) R.string.pattern_background
                            else R.string.pattern_ink,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "#" + activeColor.toHexString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ParamSlider(
                    label = stringResource(R.string.hue),
                    value = activeHsv.h,
                    range = 0f..360f,
                    format = { it.roundToInt().toString() },
                    onChange = { setActiveHsv(activeHsv.copy(h = it)) },
                    step = 1f,
                    unit = "°",
                )
                ParamSlider(
                    label = stringResource(R.string.saturation),
                    value = activeHsv.s,
                    range = 0f..1f,
                    format = percent,
                    onChange = { setActiveHsv(activeHsv.copy(s = it)) },
                    step = 0.01f,
                )
                ParamSlider(
                    label = stringResource(R.string.brightness),
                    value = activeHsv.v,
                    range = 0f..1f,
                    format = percent,
                    onChange = { setActiveHsv(activeHsv.copy(v = it)) },
                    step = 0.01f,
                )
                ParamSlider(
                    label = stringResource(R.string.pattern_tile_size),
                    value = tileSize,
                    range = 8f..256f,
                    format = { it.roundToInt().toString() },
                    onChange = { tileSize = it },
                    step = 1f,
                    unit = "px",
                )
                ParamSlider(
                    label = stringResource(R.string.pattern_rotation),
                    value = rotation,
                    range = 0f..360f,
                    format = { it.roundToInt().toString() },
                    onChange = { rotation = it },
                    step = 1f,
                    unit = "°",
                )
                ParamSlider(
                    label = stringResource(R.string.pattern_offset_x),
                    value = offsetX,
                    range = -PatternSpec.MAX_OFFSET..PatternSpec.MAX_OFFSET,
                    format = { it.roundToInt().toString() },
                    onChange = { offsetX = it },
                    step = 1f,
                    unit = "px",
                )
                ParamSlider(
                    label = stringResource(R.string.pattern_offset_y),
                    value = offsetY,
                    range = -PatternSpec.MAX_OFFSET..PatternSpec.MAX_OFFSET,
                    format = { it.roundToInt().toString() },
                    onChange = { offsetY = it },
                    step = 1f,
                    unit = "px",
                )
                ParamSlider(
                    label = stringResource(R.string.pattern_contrast),
                    value = contrast,
                    range = 0f..1f,
                    format = percent,
                    onChange = { contrast = it },
                    step = 0.01f,
                )
                AdjustSwitch(
                    label = stringResource(R.string.pattern_invert),
                    checked = invert,
                    onCheckedChange = { invert = it },
                )
                ParamSlider(
                    label = stringResource(R.string.opacity),
                    value = opacity,
                    range = 0f..1f,
                    format = percent,
                    onChange = { opacity = it },
                    step = 0.01f,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(spec, background, patternInk, opacity) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One of the two pattern colour buttons: a swatch and its role, accent-highlighted while its sliders are open. */
@Composable
private fun PatternColorChoice(
    label: String,
    color: Rgba,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) AccentFill else Color.Transparent)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else PainterTheme.colors.panelBorder,
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ColorSwatch(color, size = 28.dp, shape = MaterialTheme.shapes.small, selected = selected)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One pattern chooser card: a live mask preview tinted with both pattern colours above its name. */
@Composable
private fun PatternTile(
    label: String,
    preview: ImageBitmap,
    selected: Boolean,
    onPick: () -> Unit,
) {
    Column(
        Modifier
            .width(76.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) AccentFill else Color.Transparent)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else PainterTheme.colors.panelBorder,
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onPick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Image(
            bitmap = preview,
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.small),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Small tile preview of [spec] blended between [background] and [ink] at [opacity] over a neutral sheet,
 * so transparency and the phase offset stay visible.
 */
private fun previewBitmap(
    spec: PatternSpec,
    background: Rgba,
    ink: Rgba,
    opacity: Float,
): ImageBitmap {
    val size = 56
    val sampler = PatternSampler(spec)
    val pixels = IntArray(size * size)
    val sheet = 0xE8E8E8
    val o = opacity.coerceIn(0f, 1f)
    val sr = (sheet shr 16) and 0xFF
    val sg = (sheet shr 8) and 0xFF
    val sb = sheet and 0xFF
    for (y in 0 until size) {
        for (x in 0 until size) {
            val c = sampler.at(x + 0.5f, y + 0.5f)
            val inv = 1f - c
            // Straight-alpha blend of the two pattern colours, then OVER the neutral sheet.
            val a = ((background.a * inv + ink.a * c) * o).coerceIn(0f, 1f)
            val r = (background.r * background.a * inv + ink.r * ink.a * c) * o + sr / 255f * (1f - a)
            val g = (background.g * background.a * inv + ink.g * ink.a * c) * o + sg / 255f * (1f - a)
            val b = (background.b * background.a * inv + ink.b * ink.a * c) * o + sb / 255f * (1f - a)
            pixels[y * size + x] = (0xFF shl 24) or
                (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** 0..1 channel to an 8-bit value. */
private fun channel(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()
