package app.painter.ui.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import app.painter.core.fill.PatternGenerator
import app.painter.core.fill.PatternKind
import app.painter.ui.components.ParamSlider
import app.painter.ui.theme.AccentFill
import app.painter.ui.theme.PainterTheme
import kotlin.math.roundToInt

/** Display name of a built-in pattern. */
private fun PatternKind.titleRes(): Int = when (this) {
    PatternKind.CHECKS -> R.string.pattern_checks
    PatternKind.DOTS -> R.string.pattern_dots
    PatternKind.DIAGONAL_STRIPES -> R.string.pattern_stripes
    PatternKind.BRICK -> R.string.pattern_brick
    PatternKind.HERRINGBONE -> R.string.pattern_herringbone
    PatternKind.CANVAS -> R.string.pattern_grain
}

/**
 * Pattern fill dialog (spec §09): one of six built-in procedural patterns tinted by the current ink, with
 * tile size, rotation and opacity. Apply fills the active layer or the selection as one undo step.
 */
@Composable
fun PatternDialog(
    ink: Rgba,
    onDismiss: () -> Unit,
    onApply: (PatternKind, Float, Float, Float) -> Unit,
) {
    var kind by remember { mutableStateOf(PatternKind.CHECKS) }
    var tileSize by remember { mutableStateOf(64f) }
    var rotation by remember { mutableStateOf(0f) }
    var opacity by remember { mutableStateOf(1f) }
    val previews = remember(ink) { PatternKind.entries.associateWith { previewBitmap(it, ink) } }
    val percent = { v: Float -> "${(v * 100).roundToInt()}%" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pattern_fill_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
            TextButton(onClick = { onApply(kind, tileSize, rotation, opacity) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One pattern chooser card: a live mask preview tinted with [ink] above its name. */
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

/** Small tile preview of [kind] over a neutral sheet, tinted with [ink]. */
private fun previewBitmap(kind: PatternKind, ink: Rgba): ImageBitmap {
    val size = 56
    val cell = size / 2.25f
    val pixels = IntArray(size * size)
    val bg = 0xE8E8E8
    val ir = (ink.r.coerceIn(0f, 1f) * 255f).roundToInt()
    val ig = (ink.g.coerceIn(0f, 1f) * 255f).roundToInt()
    val ib = (ink.b.coerceIn(0f, 1f) * 255f).roundToInt()
    val ia = ink.a.coerceIn(0f, 1f)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val coverage = PatternGenerator.coverage(kind, x / cell, y / cell, 0.035f) * ia
            val r = (ir * coverage + ((bg shr 16) and 0xFF) * (1f - coverage)).roundToInt()
            val g = (ig * coverage + ((bg shr 8) and 0xFF) * (1f - coverage)).roundToInt()
            val b = (ib * coverage + (bg and 0xFF) * (1f - coverage)).roundToInt()
            pixels[y * size + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
