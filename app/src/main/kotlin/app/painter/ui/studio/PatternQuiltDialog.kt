package app.painter.ui.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.color.Rgba
import app.painter.core.fill.PatternKind
import app.painter.core.fill.PatternQuilt
import app.painter.core.fill.PatternSampler
import app.painter.core.fill.PatternSpec
import app.painter.core.fill.QuiltSpec
import app.painter.core.raster.IntRect
import app.painter.ui.components.HsvUi
import app.painter.ui.components.ParamSlider
import app.painter.ui.components.toHexString
import app.painter.ui.components.toHsvUi
import app.painter.ui.components.toRgba
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pattern quilt dialog (spec §21): one of the twelve built-in patterns repeated in a rows × columns grid.
 * A live preview shows the gap grid and the cells exactly as the tool lays them out on the canvas, or on
 * the selection when one is active. Rows, columns, gap, tile size, rotation, both colours, invert and
 * opacity are editable; Apply fills the active layer or the selection as one undo step.
 */
@Composable
fun PatternQuiltDialog(
    ink: Rgba,
    canvasWidth: Int,
    canvasHeight: Int,
    selection: RectUi?,
    onDismiss: () -> Unit,
    onApply: (QuiltSpec, Rgba, Rgba, Float) -> Unit,
) {
    var kind by remember { mutableStateOf(PatternKind.CHECKS) }
    var tileSize by remember { mutableStateOf(64f) }
    var rotation by remember { mutableStateOf(0f) }
    var invert by remember { mutableStateOf(false) }
    var opacity by remember { mutableStateOf(1f) }
    var rows by remember { mutableStateOf(QuiltSpec.DEFAULT_ROWS.toFloat()) }
    var columns by remember { mutableStateOf(QuiltSpec.DEFAULT_COLUMNS.toFloat()) }
    var gap by remember { mutableStateOf(QuiltSpec.DEFAULT_GAP) }
    var editing by remember { mutableStateOf(PatternColorRole.BACKGROUND) }
    // The two colours are kept as HSV so that hue survives a trip through zero saturation or brightness.
    val alpha = ink.a.coerceIn(0f, 1f)
    var backgroundHsv by remember { mutableStateOf(ink.toHsvUi()) }
    var inkHsv by remember { mutableStateOf(ink.toHsvUi()) }
    val background = backgroundHsv.toRgba(alpha)
    val patternInk = inkHsv.toRgba(alpha)
    val patternSpec = PatternSpec(
        kind = kind,
        tileSize = tileSize,
        rotationDeg = rotation,
        invert = invert,
    )
    val spec = QuiltSpec(
        pattern = patternSpec,
        rows = rows.roundToInt(),
        columns = columns.roundToInt(),
        gap = gap,
    )
    val bounds = remember(canvasWidth, canvasHeight, selection) {
        quiltBounds(canvasWidth, canvasHeight, selection)
    }
    val previews = remember(patternSpec, background, patternInk, opacity) {
        PatternKind.entries.associateWith {
            previewBitmap(patternSpec.copy(kind = it), background, patternInk, opacity)
        }
    }
    val gridPreview = remember(spec, background, patternInk, opacity, bounds) {
        quiltPreviewBitmap(spec, bounds, background, patternInk, opacity)
    }
    val activeHsv = if (editing == PatternColorRole.BACKGROUND) backgroundHsv else inkHsv
    val setActiveHsv: (HsvUi) -> Unit = { value ->
        if (editing == PatternColorRole.BACKGROUND) backgroundHsv = value else inkHsv = value
    }
    val activeColor = if (editing == PatternColorRole.BACKGROUND) background else patternInk
    val percent = { v: Float -> "${(v * 100).roundToInt()}%" }
    val whole = { v: Float -> v.roundToInt().toString() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pattern_quilt_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Image(
                    bitmap = gridPreview,
                    contentDescription = stringResource(R.string.pattern_quilt_title),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .clip(MaterialTheme.shapes.small),
                )
                Text(
                    stringResource(R.string.pattern_quilt_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                for (row in PatternKind.entries.chunked(4)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (option in row) {
                            PatternTile(
                                label = stringResource(option.titleRes()),
                                preview = previews.getValue(option),
                                selected = kind == option,
                                onPick = { kind = option },
                                width = 60.dp,
                                previewSize = 40.dp,
                            )
                        }
                    }
                }
                ParamSlider(
                    label = stringResource(R.string.pattern_quilt_rows),
                    value = rows,
                    range = QuiltSpec.MIN_CELLS.toFloat()..QuiltSpec.MAX_CELLS.toFloat(),
                    format = whole,
                    onChange = { rows = it },
                    step = 1f,
                )
                ParamSlider(
                    label = stringResource(R.string.pattern_quilt_columns),
                    value = columns,
                    range = QuiltSpec.MIN_CELLS.toFloat()..QuiltSpec.MAX_CELLS.toFloat(),
                    format = whole,
                    onChange = { columns = it },
                    step = 1f,
                )
                ParamSlider(
                    label = stringResource(R.string.pattern_quilt_gap),
                    value = gap,
                    range = 0f..QuiltSpec.MAX_GAP,
                    format = whole,
                    onChange = { gap = it },
                    step = 1f,
                    unit = "px",
                )
                ParamSlider(
                    label = stringResource(R.string.pattern_quilt_scale),
                    value = tileSize,
                    range = 8f..256f,
                    format = whole,
                    onChange = { tileSize = it },
                    step = 1f,
                    unit = "px",
                )
                ParamSlider(
                    label = stringResource(R.string.pattern_rotation),
                    value = rotation,
                    range = 0f..360f,
                    format = whole,
                    onChange = { rotation = it },
                    step = 1f,
                    unit = "°",
                )
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
                    format = whole,
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

/** The canvas, or the selection when it exists, clamped to the canvas. */
private fun quiltBounds(canvasWidth: Int, canvasHeight: Int, selection: RectUi?): IntRect {
    val full = IntRect(0, 0, canvasWidth, canvasHeight)
    if (selection == null) return full
    val left = selection.left.roundToInt().coerceIn(0, canvasWidth)
    val top = selection.top.roundToInt().coerceIn(0, canvasHeight)
    val right = selection.right.roundToInt().coerceIn(0, canvasWidth)
    val bottom = selection.bottom.roundToInt().coerceIn(0, canvasHeight)
    return IntRect(left, top, right, bottom).intersect(full) ?: full
}

/**
 * Live preview of [spec] over [bounds] on a checkerboard sheet. The quilt geometry, the tile scale and the
 * gap are scaled down to the preview, so the picture matches the grid the tool will paint.
 */
private fun quiltPreviewBitmap(
    spec: QuiltSpec,
    bounds: IntRect,
    background: Rgba,
    ink: Rgba,
    opacity: Float,
): ImageBitmap {
    val width = 240
    val height = 132
    val pixels = IntArray(width * height)
    for (y in 0 until height) for (x in 0 until width) {
        val sheet = if ((((x / 8) + (y / 8)) and 1) == 0) 0xE8 else 0xD2
        pixels[y * width + x] = (0xFF shl 24) or (sheet shl 16) or (sheet shl 8) or sheet
    }
    if (bounds.isEmpty) return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    val scale = min(width.toFloat() / bounds.width, height.toFloat() / bounds.height)
    val drawW = (bounds.width * scale).roundToInt().coerceIn(1, width)
    val drawH = (bounds.height * scale).roundToInt().coerceIn(1, height)
    val left = (width - drawW) / 2
    val top = (height - drawH) / 2
    // A non-zero gap keeps at least one preview pixel, so the control stays visible on a large canvas.
    val previewGap = if (spec.gap <= 0f) 0f else (spec.gap * scale).coerceAtLeast(1f)
    val preview = spec.copy(
        pattern = spec.pattern.copy(
            tileSize = (spec.pattern.tileSize * scale).coerceAtLeast(PatternSpec.MIN_TILE),
        ),
        gap = previewGap,
    )
    val layout = PatternQuilt.layout(
        IntRect(0, 0, drawW, drawH),
        preview.rows,
        preview.columns,
        preview.gap,
    )
    val sampler = PatternSampler(preview.pattern)
    val o = opacity.coerceIn(0f, 1f)
    for (cell in layout.cells) {
        val x0 = ceil(cell.x0 - 0.5f).toInt().coerceAtLeast(0)
        val y0 = ceil(cell.y0 - 0.5f).toInt().coerceAtLeast(0)
        val x1 = ceil(cell.x1 - 0.5f).toInt().coerceAtMost(drawW)
        val y1 = ceil(cell.y1 - 0.5f).toInt().coerceAtMost(drawH)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val coverage = sampler.at(x + 0.5f - cell.x0, y + 0.5f - cell.y0)
                val invCoverage = 1f - coverage
                val a = ((background.a * invCoverage + ink.a * coverage) * o).coerceIn(0f, 1f)
                val index = (top + y) * width + left + x
                val sheet = pixels[index]
                val keep = 1f - a
                val r = (background.r * background.a * invCoverage + ink.r * ink.a * coverage) * o +
                    ((sheet shr 16) and 0xFF) / 255f * keep
                val g = (background.g * background.a * invCoverage + ink.g * ink.a * coverage) * o +
                    ((sheet shr 8) and 0xFF) / 255f * keep
                val b = (background.b * background.a * invCoverage + ink.b * ink.a * coverage) * o +
                    (sheet and 0xFF) / 255f * keep
                pixels[index] = (0xFF shl 24) or
                    (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
            }
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** 0..1 channel to an 8-bit value. */
private fun channel(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()
