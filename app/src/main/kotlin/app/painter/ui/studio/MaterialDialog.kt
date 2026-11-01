package app.painter.ui.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.color.Rgba
import app.painter.core.material.MaterialKind
import app.painter.core.material.MaterialMode
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
 * current colour and opacity, with the stamp size, rotation, opacity and stroke spacing. Starred materials
 * can be filtered and the last four kinds are offered in a recent row. The Paint/Stamp mode is remembered
 * with the other settings: "Paint" drags the material along the gesture and taps once at the pointer, "Stamp"
 * places a single stamp at the canvas or selection centre.
 */
@Composable
fun MaterialDialog(
    ink: Rgba,
    initial: MaterialSpec? = null,
    initialMode: MaterialMode = MaterialMode.PAINT,
    favourites: Set<MaterialKind> = emptySet(),
    recent: List<MaterialKind> = emptyList(),
    onDismiss: () -> Unit,
    onFavourite: (MaterialKind) -> Unit,
    onStamp: (MaterialSpec) -> Unit,
    onPaint: (MaterialSpec) -> Unit,
) {
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: MaterialKind.BALLOON) }
    var size by remember(initial) { mutableStateOf(initial?.size ?: MaterialSpec.DEFAULT_SIZE) }
    var rotation by remember(initial) { mutableStateOf(initial?.rotationDeg ?: MaterialSpec.DEFAULT_ROTATION) }
    var opacity by remember(initial) { mutableStateOf(initial?.opacity ?: MaterialSpec.DEFAULT_OPACITY) }
    var spacing by remember(initial) { mutableStateOf(initial?.spacing ?: MaterialSpec.DEFAULT_SPACING) }
    var mode by remember(initial, initialMode) { mutableStateOf(initialMode) }
    // A filter without favourites would leave an empty grid, so it falls back to the full library.
    var onlyFavourites by remember(favourites.isEmpty()) { mutableStateOf(false) }
    val previews = remember(ink, rotation, opacity) {
        MaterialKind.entries.associateWith { previewBitmap(it, ink, rotation, opacity) }
    }
    val percent = { v: Float -> "${(v * 100).roundToInt()}%" }
    val spec = { MaterialSpec(kind, size, rotation, opacity, spacing) }
    val visible = if (onlyFavourites && favourites.isNotEmpty()) {
        MaterialKind.entries.filter { it in favourites }
    } else {
        MaterialKind.entries
    }

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
                if (recent.isNotEmpty()) {
                    Text(
                        stringResource(R.string.material_recent),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (option in recent) {
                            MaterialRecentTile(
                                label = stringResource(option.titleRes()),
                                preview = previews.getValue(option),
                                selected = kind == option,
                                onPick = { kind = option },
                            )
                        }
                    }
                }
                if (favourites.isNotEmpty()) {
                    ToggleChip(
                        label = stringResource(R.string.material_favourites),
                        checked = onlyFavourites,
                        onToggle = { onlyFavourites = !onlyFavourites },
                    )
                }
                for (row in visible.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (option in row) {
                            MaterialTile(
                                label = stringResource(option.titleRes()),
                                preview = previews.getValue(option),
                                selected = kind == option,
                                favourite = option in favourites,
                                onFavourite = { onFavourite(option) },
                                onPick = { kind = option },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip(
                        label = stringResource(R.string.material_paint),
                        checked = mode == MaterialMode.PAINT,
                        onToggle = { mode = MaterialMode.PAINT },
                    )
                    ToggleChip(
                        label = stringResource(R.string.material_stamp),
                        checked = mode == MaterialMode.STAMP,
                        onToggle = { mode = MaterialMode.STAMP },
                    )
                }
                Text(
                    stringResource(
                        if (mode == MaterialMode.PAINT) R.string.material_hint else R.string.material_stamp_hint,
                    ),
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
            TextButton(
                onClick = {
                    if (mode == MaterialMode.PAINT) onPaint(spec()) else onStamp(spec())
                },
            ) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One material chooser card: a live stamp preview above its name, with a star in the corner. */
@Composable
private fun MaterialTile(
    label: String,
    preview: ImageBitmap,
    selected: Boolean,
    favourite: Boolean,
    onFavourite: () -> Unit,
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
        Box {
            Image(
                bitmap = preview,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                    .clickable(role = Role.Button, onClick = onFavourite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (favourite) R.string.material_favourite_remove else R.string.material_favourite_add,
                    ),
                    tint = if (favourite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Compact shortcut of a recently used material in the row above the grid. */
@Composable
private fun MaterialRecentTile(
    label: String,
    preview: ImageBitmap,
    selected: Boolean,
    onPick: () -> Unit,
) {
    Column(
        Modifier
            .width(52.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) AccentFill else Color.Transparent)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else PainterTheme.colors.panelBorder,
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onPick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Image(
            bitmap = preview,
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(36.dp)
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
