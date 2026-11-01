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
import app.painter.core.material.MaterialKind
import app.painter.core.material.MaterialSampler
import app.painter.core.material.MaterialSpec
import app.painter.ui.components.ParamSlider
import app.painter.ui.theme.AccentFill
import app.painter.ui.theme.PainterTheme
import kotlin.math.roundToInt

/** Display name of a built-in material. */
private fun MaterialKind.titleRes(): Int = when (this) {
    MaterialKind.BALLOON -> R.string.material_balloon
    MaterialKind.FABRIC -> R.string.material_fabric
    MaterialKind.WOOD -> R.string.material_wood
    MaterialKind.METAL -> R.string.material_metal
    MaterialKind.STONE -> R.string.material_stone
    MaterialKind.FOLIAGE -> R.string.material_foliage
    MaterialKind.SPARKLE -> R.string.material_sparkle
    MaterialKind.PAPER -> R.string.material_paper
}

/**
 * Material library dialog (spec §21): eight built-in procedural materials shown as live previews in the
 * current colour and opacity, with the stamp size, rotation, opacity and stroke spacing. "Paint" selects the
 * material tool: a drag paints along the gesture and a tap stamps once at the pointer. "Stamp" keeps the
 * one-shot path and places a single stamp at the canvas or selection centre.
 */
@Composable
fun MaterialDialog(
    ink: Rgba,
    initial: MaterialSpec? = null,
    onDismiss: () -> Unit,
    onStamp: (MaterialSpec) -> Unit,
    onPaint: (MaterialSpec) -> Unit,
) {
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: MaterialKind.BALLOON) }
    var size by remember(initial) { mutableStateOf(initial?.size ?: 512f) }
    var rotation by remember(initial) { mutableStateOf(initial?.rotationDeg ?: 0f) }
    var opacity by remember(initial) { mutableStateOf(initial?.opacity ?: 1f) }
    var spacing by remember(initial) { mutableStateOf(initial?.spacing ?: MaterialSpec.DEFAULT_SPACING) }
    val previews = remember(ink, rotation, opacity) {
        MaterialKind.entries.associateWith { previewBitmap(it, ink, rotation, opacity) }
    }
    val percent = { v: Float -> "${(v * 100).roundToInt()}%" }
    val spec = { MaterialSpec(kind, size, rotation, opacity, spacing) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.materials_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (row in MaterialKind.entries.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (option in row) {
                            MaterialTile(
                                label = stringResource(option.titleRes()),
                                preview = previews.getValue(option),
                                selected = kind == option,
                                onPick = { kind = option },
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.material_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                ParamSlider(
                    label = stringResource(R.string.size),
                    value = size,
                    range = MaterialSpec.MIN_SIZE..MaterialSpec.MAX_SIZE,
                    format = { it.roundToInt().toString() },
                    onChange = { size = it },
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
                ParamSlider(
                    label = stringResource(R.string.spacing),
                    value = spacing,
                    range = MaterialSpec.MIN_SPACING..MaterialSpec.MAX_SPACING,
                    format = percent,
                    onChange = { spacing = it },
                    step = 0.01f,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onPaint(spec()) }) {
                    Text(stringResource(R.string.material_paint))
                }
                TextButton(onClick = { onStamp(spec()) }) {
                    Text(stringResource(R.string.material_stamp))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One material chooser card: a live stamp preview above its name. */
@Composable
private fun MaterialTile(
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
 * Small preview of [kind] tinted with [color] at [opacity] over a light checkerboard, so shaped materials and
 * transparency stay visible. The rotation is applied to the same generator the stamp uses.
 */
private fun previewBitmap(
    kind: MaterialKind,
    color: Rgba,
    rotation: Float,
    opacity: Float,
): ImageBitmap {
    val size = 64
    val spec = MaterialSpec(kind, size.toFloat(), rotation, opacity)
    val sampler = MaterialSampler(spec)
    val half = size * 0.5f
    val pixels = IntArray(size * size)
    val sample = FloatArray(2)
    for (y in 0 until size) {
        for (x in 0 until size) {
            sampler.sample(x + 0.5f - half, y + 0.5f - half, sample)
            val alpha = (sample[0] * sampler.opacity * color.a).coerceIn(0f, 1f)
            val sheet = if (((x / 8) + (y / 8)) and 1 == 0) 0xE8 else 0xD2
            val sheetR = ((sheet shr 16) and 0xFF) / 255f
            val sheetG = ((sheet shr 8) and 0xFF) / 255f
            val sheetB = (sheet and 0xFF) / 255f
            val r = (color.r * sample[1]).coerceIn(0f, 1f) * alpha + sheetR * (1f - alpha)
            val g = (color.g * sample[1]).coerceIn(0f, 1f) * alpha + sheetG * (1f - alpha)
            val b = (color.b * sample[1]).coerceIn(0f, 1f) * alpha + sheetB * (1f - alpha)
            pixels[y * size + x] = (0xFF shl 24) or
                (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** 0..1 channel to an 8-bit value. */
private fun channel(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()
