package app.painter.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.filter.BlurParams
import app.painter.core.filter.ChromaticParams
import app.painter.core.filter.DilateParams
import app.painter.core.filter.EdgesParams
import app.painter.core.filter.ErodeParams
import app.painter.core.filter.FilterParams
import app.painter.core.filter.FisheyeParams
import app.painter.core.filter.GlowParams
import app.painter.core.filter.MotionBlurParams
import app.painter.core.filter.NoiseParams
import app.painter.core.filter.PixelateParams
import app.painter.core.filter.SharpenParams
import app.painter.core.filter.SpinBlurParams
import app.painter.core.filter.ZoomBlurParams
import app.painter.ui.components.ParamSlider
import kotlin.math.roundToInt

/** The M3 filters the dialog offers, with their label. */
private enum class FilterKindUi(val label: Int) {
    BLUR(R.string.filter_blur),
    MOTION(R.string.filter_motion),
    PIXELATE(R.string.filter_pixelate),
    SHARPEN(R.string.filter_sharpen),
    GLOW(R.string.filter_glow),
    NOISE(R.string.filter_noise),
    EDGES(R.string.filter_edges),
    CHROMATIC(R.string.filter_chromatic),
    FISHEYE(R.string.filter_fisheye),
    DILATE(R.string.filter_dilate),
    ERODE(R.string.filter_erode),
    ZOOM(R.string.filter_zoom),
    SPIN(R.string.filter_spin),
}

/**
 * Filter picker and parameters (spec/filters.md, M3): a chip per filter and only the sliders that filter
 * needs, plus a common strength. Apply closes the dialog and runs the filter on the active layer.
 */
@Composable
fun FilterDialog(onDismiss: () -> Unit, onApply: (FilterParams) -> Unit) {
    var kind by remember { mutableStateOf(FilterKindUi.BLUR) }
    var radius by remember { mutableStateOf(8f) }
    var amount by remember { mutableStateOf(1f) }
    var distance by remember { mutableStateOf(24f) }
    var angle by remember { mutableStateOf(0f) }
    var cell by remember { mutableStateOf(12f) }
    var threshold by remember { mutableStateOf(0.6f) }
    var mono by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(1f) }
    var strength by remember { mutableStateOf(1f) }

    val params = kind.params(radius, amount, distance, angle, cell, threshold, mono, scale, strength)
    val percent = { v: Float -> "${(v * 100).roundToInt()}%" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filters)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterKindUi.entries.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { k ->
                            ToggleChip(
                                label = stringResource(k.label),
                                checked = kind == k,
                                onToggle = { kind = k },
                            )
                        }
                    }
                }
                when (kind) {
                    FilterKindUi.BLUR -> ParamSlider(
                        stringResource(R.string.radius), radius, 0f..120f, { it.roundToInt().toString() },
                        { radius = it }, step = 1f, unit = "px",
                    )
                    FilterKindUi.MOTION -> {
                        ParamSlider(
                            stringResource(R.string.distance), distance, 0f..200f, { it.roundToInt().toString() },
                            { distance = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.angle), angle, 0f..360f, { it.roundToInt().toString() },
                            { angle = it }, step = 1f, unit = "°",
                        )
                    }
                    FilterKindUi.PIXELATE -> ParamSlider(
                        stringResource(R.string.cell_size), cell, 2f..64f, { it.roundToInt().toString() },
                        { cell = it }, step = 1f, unit = "px",
                    )
                    FilterKindUi.SHARPEN -> {
                        ParamSlider(
                            stringResource(R.string.amount), amount, 0f..3f, { "%.2f".format(it) },
                            { amount = it }, step = 0.05f,
                        )
                        ParamSlider(
                            stringResource(R.string.radius), radius, 0.5f..20f, { "%.1f".format(it) },
                            { radius = it }, step = 0.1f, unit = "px",
                        )
                    }
                    FilterKindUi.GLOW -> {
                        ParamSlider(
                            stringResource(R.string.threshold), threshold, 0f..1f, percent,
                            { threshold = it }, step = 0.01f,
                        )
                        ParamSlider(
                            stringResource(R.string.radius), radius, 1f..120f, { it.roundToInt().toString() },
                            { radius = it }, step = 1f, unit = "px",
                        )
                    }
                    FilterKindUi.NOISE -> {
                        ParamSlider(
                            stringResource(R.string.amount), amount, 0f..1f, percent,
                            { amount = it }, step = 0.01f,
                        )
                        ToggleChip(
                            label = stringResource(R.string.monochrome),
                            checked = mono,
                            onToggle = { mono = !mono },
                        )
                    }
                    FilterKindUi.EDGES -> ParamSlider(
                        stringResource(R.string.amount), amount, 0f..3f, { "%.2f".format(it) },
                        { amount = it }, step = 0.05f,
                    )
                    FilterKindUi.CHROMATIC -> {
                        ParamSlider(
                            stringResource(R.string.distance), distance, 0f..30f, { it.roundToInt().toString() },
                            { distance = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.angle), angle, 0f..360f, { it.roundToInt().toString() },
                            { angle = it }, step = 1f, unit = "°",
                        )
                    }
                    FilterKindUi.FISHEYE -> {
                        ParamSlider(
                            stringResource(R.string.amount), amount, -1f..1f, { "%.2f".format(it) },
                            { amount = it }, step = 0.05f,
                        )
                        ParamSlider(
                            stringResource(R.string.scale), scale, 0.2f..1f, percent,
                            { scale = it }, step = 0.01f,
                        )
                    }
                    FilterKindUi.DILATE, FilterKindUi.ERODE -> ParamSlider(
                        stringResource(R.string.radius), radius, 1f..20f, { it.roundToInt().toString() },
                        { radius = it }, step = 1f, unit = "px",
                    )
                    FilterKindUi.ZOOM -> ParamSlider(
                        stringResource(R.string.amount), amount, 0f..0.9f, percent,
                        { amount = it }, step = 0.01f,
                    )
                    FilterKindUi.SPIN -> ParamSlider(
                        stringResource(R.string.angle), angle, -180f..180f, { it.roundToInt().toString() },
                        { angle = it }, step = 1f, unit = "°",
                    )
                }
                ParamSlider(
                    stringResource(R.string.strength), strength, 0f..1f, percent,
                    { strength = it }, step = 0.01f,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(params) }) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Builds the core parameters of [kind] from the slider values of the dialog. */
private fun FilterKindUi.params(
    radius: Float,
    amount: Float,
    distance: Float,
    angle: Float,
    cell: Float,
    threshold: Float,
    mono: Boolean,
    scale: Float,
    strength: Float,
): FilterParams = when (this) {
    FilterKindUi.BLUR -> BlurParams(radius, strength)
    FilterKindUi.MOTION -> MotionBlurParams(distance, angle, strength)
    FilterKindUi.PIXELATE -> PixelateParams(cell.roundToInt(), strength)
    FilterKindUi.SHARPEN -> SharpenParams(amount, radius, strength)
    FilterKindUi.GLOW -> GlowParams(threshold, radius, strength)
    FilterKindUi.NOISE -> NoiseParams(amount, mono, seed = 1L, strength = strength)
    FilterKindUi.EDGES -> EdgesParams(amount, strength)
    FilterKindUi.CHROMATIC -> ChromaticParams(distance, angle, strength)
    FilterKindUi.FISHEYE -> FisheyeParams(amount, strength, scale)
    FilterKindUi.DILATE -> DilateParams(radius.roundToInt(), strength)
    FilterKindUi.ERODE -> ErodeParams(radius.roundToInt(), strength)
    FilterKindUi.ZOOM -> ZoomBlurParams(amount, strength)
    FilterKindUi.SPIN -> SpinBlurParams(angle, strength)
}
