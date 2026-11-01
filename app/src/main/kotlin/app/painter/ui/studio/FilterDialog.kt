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
import app.painter.core.filter.BokehParams
import app.painter.core.filter.ChromaticParams
import app.painter.core.filter.CrossFilterParams
import app.painter.core.filter.CrystallizeParams
import app.painter.core.filter.DilateParams
import app.painter.core.filter.ExpansionParams
import app.painter.core.filter.HexPixelateParams
import app.painter.core.filter.KaleidoscopeParams
import app.painter.core.filter.PolarCoordinatesParams
import app.painter.core.filter.PointillizeParams
import app.painter.core.filter.RippleParams
import app.painter.core.filter.SphereLensParams
import app.painter.core.filter.TrianglePixelateParams
import app.painter.core.filter.TwirlParams
import app.painter.core.filter.WaveParams
import app.painter.core.filter.EdgesParams
import app.painter.core.filter.ErodeParams
import app.painter.core.filter.FilterParams
import app.painter.core.filter.FisheyeParams
import app.painter.core.filter.GlowParams
import app.painter.core.filter.GodRaysParams
import app.painter.core.filter.GrainParams
import app.painter.core.filter.HighPassParams
import app.painter.core.filter.LensBlurParams
import app.painter.core.filter.LensFlareParams
import app.painter.core.filter.LocalContrastParams
import app.painter.core.filter.MotionBlurParams
import app.painter.core.filter.NoiseParams
import app.painter.core.filter.PixelateParams
import app.painter.core.filter.RadialLinesParams
import app.painter.core.filter.ScatterParams
import app.painter.core.filter.SharpenParams
import app.painter.core.filter.SmoothParams
import app.painter.core.filter.SpeedLinesParams
import app.painter.core.filter.DropShadowParams
import app.painter.core.filter.HalftoneParams
import app.painter.core.filter.LayerGlowParams
import app.painter.core.filter.LayerStrokeParams
import app.painter.core.filter.PanelGridParams
import app.painter.core.filter.ScreentoneParams
import app.painter.core.filter.SpinBlurParams
import app.painter.core.filter.ZoomBlurParams
import app.painter.core.color.Rgba
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
    RADIAL(R.string.filter_radial),
    SPEED(R.string.filter_speed),
    HALFTONE(R.string.filter_halftone),
    STROKE(R.string.filter_layer_stroke),
    SHADOW(R.string.filter_drop_shadow),
    LAYER_GLOW(R.string.filter_layer_glow),
    PANELS(R.string.filter_panels),
    SCREENTONE(R.string.filter_screentone),
    SMOOTH(R.string.filter_smooth),
    LENS_BLUR(R.string.filter_lens_blur),
    HIGH_PASS(R.string.filter_high_pass),
    SCATTER(R.string.filter_scatter),
    GRAIN(R.string.filter_grain),
    LOCAL_CONTRAST(R.string.filter_local_contrast),
    BOKEH(R.string.filter_bokeh),
    LENS_FLARE(R.string.filter_lens_flare),
    GOD_RAYS(R.string.filter_god_rays),
    CROSS(R.string.filter_cross),
    SPHERE(R.string.filter_sphere_lens),
    WAVE(R.string.filter_wave),
    RIPPLE(R.string.filter_ripple),
    TWIRL(R.string.filter_twirl),
    POLAR(R.string.filter_polar),
    EXPANSION(R.string.filter_expansion),
    KALEIDOSCOPE(R.string.filter_kaleidoscope),
    CRYSTALLIZE(R.string.filter_crystallize),
    HEX_PIXELATE(R.string.filter_hex_pixelate),
    TRIANGLE_PIXELATE(R.string.filter_triangle_pixelate),
    POINTILLIZE(R.string.filter_pointillize),
}

/**
 * Filter picker and parameters (spec/filters.md, M3 and M5): a chip per filter and only the sliders that filter
 * needs, plus a common strength. Apply closes the dialog and runs the filter on the active layer.
 */
@Composable
fun FilterDialog(ink: Rgba, onDismiss: () -> Unit, onApply: (FilterParams) -> Unit) {
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
    var count by remember { mutableStateOf(60f) }
    var thickness by remember { mutableStateOf(2f) }
    var inner by remember { mutableStateOf(0.15f) }
    var strokeInner by remember { mutableStateOf(false) }
    var strokeOuter by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf(2f) }
    var cols by remember { mutableStateOf(2f) }
    var margin by remember { mutableStateOf(40f) }
    var gutter by remember { mutableStateOf(24f) }
    var toneShape by remember { mutableStateOf("dot") }
    var highlightBoost by remember { mutableStateOf(0.4f) }
    var bladeAngle by remember { mutableStateOf(0f) }
    var grainVariant by remember { mutableStateOf("mono") }
    var grainSize by remember { mutableStateOf(2f) }
    var bokehSize by remember { mutableStateOf(12f) }
    var flareSize by remember { mutableStateOf(40f) }
    var rayLength by remember { mutableStateOf(64f) }
    var crossLength by remember { mutableStateOf(24f) }
    var centerX by remember { mutableStateOf(0.5f) }
    var centerY by remember { mutableStateOf(0.5f) }
    var crossRays by remember { mutableStateOf(4f) }
    var sphereRadius by remember { mutableStateOf(120f) }
    var amplitude by remember { mutableStateOf(12f) }
    var wavelength by remember { mutableStateOf(64f) }
    var frequency by remember { mutableStateOf(3f) }
    var twirlAngle by remember { mutableStateOf(180f) }
    var twirlRadius by remember { mutableStateOf(150f) }
    var polarMode by remember { mutableStateOf("rect-to-polar") }
    var segments by remember { mutableStateOf(6f) }
    var crystallizeSize by remember { mutableStateOf(24f) }
    var hexSize by remember { mutableStateOf(16f) }
    var triangleSize by remember { mutableStateOf(16f) }
    var pointSize by remember { mutableStateOf(8f) }
    var pointUseInk by remember { mutableStateOf(false) }
    val inkHex = remember(ink) {
        "#%02X%02X%02X".format(
            (ink.r.coerceIn(0f, 1f) * 255).roundToInt(),
            (ink.g.coerceIn(0f, 1f) * 255).roundToInt(),
            (ink.b.coerceIn(0f, 1f) * 255).roundToInt(),
        )
    }

    val params = kind.params(
        radius, amount, distance, angle, cell, threshold, mono, scale, strength,
        count, thickness, inner, inkHex, strokeInner, strokeOuter, rows, cols, margin, gutter, toneShape,
        highlightBoost, bladeAngle, grainVariant, grainSize, bokehSize, flareSize, rayLength, crossLength,
        centerX, centerY, crossRays, sphereRadius, amplitude, wavelength, frequency, twirlAngle, twirlRadius,
        polarMode, segments, crystallizeSize, hexSize, triangleSize, pointSize, pointUseInk,
    )
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
                FilterKindUi.entries.chunked(4).forEach { row ->
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
                    FilterKindUi.RADIAL -> {
                        ParamSlider(
                            stringResource(R.string.count), count, 6f..300f, { it.roundToInt().toString() },
                            { count = it }, step = 1f,
                        )
                        ParamSlider(
                            stringResource(R.string.thickness), thickness, 1f..20f, { "%.1f".format(it) },
                            { thickness = it }, step = 0.5f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.inner_radius), inner, 0f..0.8f, percent,
                            { inner = it }, step = 0.01f,
                        )
                    }
                    FilterKindUi.SPEED -> {
                        ParamSlider(
                            stringResource(R.string.angle), angle, 0f..360f, { it.roundToInt().toString() },
                            { angle = it }, step = 1f, unit = "°",
                        )
                        ParamSlider(
                            stringResource(R.string.count), count, 4f..200f, { it.roundToInt().toString() },
                            { count = it }, step = 1f,
                        )
                        ParamSlider(
                            stringResource(R.string.thickness), thickness, 1f..20f, { "%.1f".format(it) },
                            { thickness = it }, step = 0.5f, unit = "px",
                        )
                    }
                    FilterKindUi.HALFTONE -> ParamSlider(
                        stringResource(R.string.cell_size), cell, 2f..48f, { it.roundToInt().toString() },
                        { cell = it }, step = 1f, unit = "px",
                    )
                    FilterKindUi.STROKE -> {
                        ParamSlider(
                            stringResource(R.string.width), thickness, 1f..50f, { it.roundToInt().toString() },
                            { thickness = it }, step = 1f, unit = "px",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToggleChip(stringResource(R.string.outer), strokeOuter) { strokeOuter = !strokeOuter }
                            ToggleChip(stringResource(R.string.inner), strokeInner) { strokeInner = !strokeInner }
                        }
                        ParamSlider(
                            stringResource(R.string.opacity), amount, 0f..1f, percent,
                            { amount = it }, step = 0.01f,
                        )
                    }
                    FilterKindUi.SHADOW -> {
                        ParamSlider(
                            stringResource(R.string.angle), angle, 0f..360f, { it.roundToInt().toString() },
                            { angle = it }, step = 1f, unit = "°",
                        )
                        ParamSlider(
                            stringResource(R.string.distance), distance, 0f..100f, { it.roundToInt().toString() },
                            { distance = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.blur), radius, 0f..50f, { it.roundToInt().toString() },
                            { radius = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.opacity), amount, 0f..1f, percent,
                            { amount = it }, step = 0.01f,
                        )
                    }
                    FilterKindUi.LAYER_GLOW -> {
                        ParamSlider(
                            stringResource(R.string.radius), radius, 1f..80f, { it.roundToInt().toString() },
                            { radius = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.opacity), amount, 0f..1f, percent,
                            { amount = it }, step = 0.01f,
                        )
                    }
                    FilterKindUi.PANELS -> {
                        ParamSlider(
                            stringResource(R.string.rows), rows, 1f..8f, { it.roundToInt().toString() },
                            { rows = it }, step = 1f,
                        )
                        ParamSlider(
                            stringResource(R.string.columns), cols, 1f..8f, { it.roundToInt().toString() },
                            { cols = it }, step = 1f,
                        )
                        ParamSlider(
                            stringResource(R.string.margin), margin, 0f..300f, { it.roundToInt().toString() },
                            { margin = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.gutter), gutter, 0f..100f, { it.roundToInt().toString() },
                            { gutter = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.thickness), thickness, 1f..30f, { it.roundToInt().toString() },
                            { thickness = it }, step = 1f, unit = "px",
                        )
                    }
                    FilterKindUi.SCREENTONE -> {
                        ParamSlider(
                            stringResource(R.string.cell_size), cell, 2f..48f, { it.roundToInt().toString() },
                            { cell = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.angle), angle, 0f..180f, { it.roundToInt().toString() },
                            { angle = it }, step = 1f, unit = "°",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToggleChip(stringResource(R.string.dots), toneShape == "dot") { toneShape = "dot" }
                            ToggleChip(stringResource(R.string.lines), toneShape == "line") { toneShape = "line" }
                            ToggleChip(stringResource(R.string.cross), toneShape == "cross") { toneShape = "cross" }
                        }
                    }
                    FilterKindUi.SMOOTH -> ParamSlider(
                        stringResource(R.string.radius), radius, 1f..32f, { it.roundToInt().toString() },
                        { radius = it }, step = 1f, unit = "px",
                    )
                    FilterKindUi.LENS_BLUR -> {
                        ParamSlider(
                            stringResource(R.string.radius), radius, 1f..100f, { it.roundToInt().toString() },
                            { radius = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.highlight_boost), highlightBoost, 0f..1f, percent,
                            { highlightBoost = it }, step = 0.01f,
                        )
                        ParamSlider(
                            stringResource(R.string.blade_angle), bladeAngle, 0f..360f,
                            { it.roundToInt().toString() }, { bladeAngle = it }, step = 1f, unit = "°",
                        )
                    }
                    FilterKindUi.HIGH_PASS -> ParamSlider(
                        stringResource(R.string.radius), radius, 0.5f..32f, { "%.1f".format(it) },
                        { radius = it }, step = 0.1f, unit = "px",
                    )
                    FilterKindUi.SCATTER -> ParamSlider(
                        stringResource(R.string.distance), distance, 1f..50f, { it.roundToInt().toString() },
                        { distance = it }, step = 1f, unit = "px",
                    )
                    FilterKindUi.GRAIN -> {
                        ParamSlider(
                            stringResource(R.string.size), grainSize, 1f..16f, { it.roundToInt().toString() },
                            { grainSize = it }, step = 1f, unit = "px",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToggleChip(
                                stringResource(R.string.monochrome), grainVariant == "mono",
                            ) { grainVariant = "mono" }
                            ToggleChip(
                                stringResource(R.string.rgb), grainVariant == "rgb",
                            ) { grainVariant = "rgb" }
                            ToggleChip(
                                stringResource(R.string.alpha), grainVariant == "alpha",
                            ) { grainVariant = "alpha" }
                        }
                    }
                    FilterKindUi.LOCAL_CONTRAST -> ParamSlider(
                        stringResource(R.string.radius), radius, 1f..32f, { it.roundToInt().toString() },
                        { radius = it }, step = 1f, unit = "px",
                    )
                    FilterKindUi.BOKEH -> ParamSlider(
                        stringResource(R.string.size), bokehSize, 1f..64f, { it.roundToInt().toString() },
                        { bokehSize = it }, step = 1f, unit = "px",
                    )
                    FilterKindUi.LENS_FLARE -> {
                        ParamSlider(
                            stringResource(R.string.center_x), centerX, 0f..1f, percent,
                            { centerX = it }, step = 0.01f,
                        )
                        ParamSlider(
                            stringResource(R.string.center_y), centerY, 0f..1f, percent,
                            { centerY = it }, step = 0.01f,
                        )
                        ParamSlider(
                            stringResource(R.string.size), flareSize, 1f..80f, { it.roundToInt().toString() },
                            { flareSize = it }, step = 1f, unit = "px",
                        )
                    }
                    FilterKindUi.GOD_RAYS -> {
                        ParamSlider(
                            stringResource(R.string.center_x), centerX, 0f..1f, percent,
                            { centerX = it }, step = 0.01f,
                        )
                        ParamSlider(
                            stringResource(R.string.center_y), centerY, 0f..1f, percent,
                            { centerY = it }, step = 0.01f,
                        )
                        ParamSlider(
                            stringResource(R.string.length), rayLength, 1f..160f, { it.roundToInt().toString() },
                            { rayLength = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.threshold), threshold, 0f..1f, percent,
                            { threshold = it }, step = 0.01f,
                        )
                    }
                    FilterKindUi.CROSS -> {
                        ParamSlider(
                            stringResource(R.string.threshold), threshold, 0f..1f, percent,
                            { threshold = it }, step = 0.01f,
                        )
                        ParamSlider(
                            stringResource(R.string.length), crossLength, 1f..80f, { it.roundToInt().toString() },
                            { crossLength = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.angle), angle, 0f..360f, { it.roundToInt().toString() },
                            { angle = it }, step = 1f, unit = "°",
                        )
                        ParamSlider(
                            stringResource(R.string.rays), crossRays, 2f..8f, { it.roundToInt().toString() },
                            { crossRays = it }, step = 1f,
                        )
                    }
                    FilterKindUi.SPHERE -> {
                        ParamSlider(
                            stringResource(R.string.radius), sphereRadius, 8f..300f,
                            { it.roundToInt().toString() }, { sphereRadius = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.center_x), centerX, 0f..1f, percent,
                            { centerX = it }, step = 0.01f,
                        )
                        ParamSlider(
                            stringResource(R.string.center_y), centerY, 0f..1f, percent,
                            { centerY = it }, step = 0.01f,
                        )
                    }
                    FilterKindUi.WAVE -> {
                        ParamSlider(
                            stringResource(R.string.amplitude), amplitude, 0f..100f,
                            { it.roundToInt().toString() }, { amplitude = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.wavelength), wavelength, 4f..400f,
                            { it.roundToInt().toString() }, { wavelength = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.angle), angle, 0f..360f, { it.roundToInt().toString() },
                            { angle = it }, step = 1f, unit = "°",
                        )
                    }
                    FilterKindUi.RIPPLE -> {
                        ParamSlider(
                            stringResource(R.string.amplitude), amplitude, 0f..50f,
                            { it.roundToInt().toString() }, { amplitude = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.frequency), frequency, 0.2f..20f,
                            { "%.1f".format(it) }, { frequency = it }, step = 0.1f, unit = "/100px",
                        )
                        ParamSlider(
                            stringResource(R.string.center_x), centerX, 0f..1f, percent,
                            { centerX = it }, step = 0.01f,
                        )
                        ParamSlider(
                            stringResource(R.string.center_y), centerY, 0f..1f, percent,
                            { centerY = it }, step = 0.01f,
                        )
                    }
                    FilterKindUi.TWIRL -> {
                        ParamSlider(
                            stringResource(R.string.angle), twirlAngle, -720f..720f,
                            { it.roundToInt().toString() }, { twirlAngle = it }, step = 5f, unit = "°",
                        )
                        ParamSlider(
                            stringResource(R.string.radius), twirlRadius, 8f..500f,
                            { it.roundToInt().toString() }, { twirlRadius = it }, step = 1f, unit = "px",
                        )
                        ParamSlider(
                            stringResource(R.string.center_x), centerX, 0f..1f, percent,
                            { centerX = it }, step = 0.01f,
                        )
                        ParamSlider(
                            stringResource(R.string.center_y), centerY, 0f..1f, percent,
                            { centerY = it }, step = 0.01f,
                        )
                    }
                    FilterKindUi.POLAR -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToggleChip(
                            stringResource(R.string.polar_rect_to_polar),
                            polarMode == "rect-to-polar",
                        ) { polarMode = "rect-to-polar" }
                        ToggleChip(
                            stringResource(R.string.polar_polar_to_rect),
                            polarMode == "polar-to-rect",
                        ) { polarMode = "polar-to-rect" }
                    }
                    FilterKindUi.EXPANSION -> {}
                    FilterKindUi.KALEIDOSCOPE -> {
                        ParamSlider(
                            stringResource(R.string.segments), segments, 1f..12f,
                            { it.roundToInt().toString() }, { segments = it }, step = 1f,
                        )
                        ParamSlider(
                            stringResource(R.string.center_x), centerX, 0f..1f, percent,
                            { centerX = it }, step = 0.01f,
                        )
                        ParamSlider(
                            stringResource(R.string.center_y), centerY, 0f..1f, percent,
                            { centerY = it }, step = 0.01f,
                        )
                    }
                    FilterKindUi.CRYSTALLIZE -> ParamSlider(
                        stringResource(R.string.size), crystallizeSize, 2f..100f,
                        { it.roundToInt().toString() }, { crystallizeSize = it }, step = 1f, unit = "px",
                    )
                    FilterKindUi.HEX_PIXELATE -> ParamSlider(
                        stringResource(R.string.size), hexSize, 2f..100f,
                        { it.roundToInt().toString() }, { hexSize = it }, step = 1f, unit = "px",
                    )
                    FilterKindUi.TRIANGLE_PIXELATE -> ParamSlider(
                        stringResource(R.string.size), triangleSize, 2f..100f,
                        { it.roundToInt().toString() }, { triangleSize = it }, step = 1f, unit = "px",
                    )
                    FilterKindUi.POINTILLIZE -> {
                        ParamSlider(
                            stringResource(R.string.size), pointSize, 2f..40f,
                            { it.roundToInt().toString() }, { pointSize = it }, step = 1f, unit = "px",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToggleChip(
                                stringResource(R.string.white), !pointUseInk,
                            ) { pointUseInk = false }
                            ToggleChip(
                                stringResource(R.string.color), pointUseInk,
                            ) { pointUseInk = true }
                        }
                    }
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
    count: Float,
    thickness: Float,
    inner: Float,
    ink: String,
    strokeInner: Boolean,
    strokeOuter: Boolean,
    rows: Float,
    cols: Float,
    margin: Float,
    gutter: Float,
    toneShape: String,
    highlightBoost: Float,
    bladeAngle: Float,
    grainVariant: String,
    grainSize: Float,
    bokehSize: Float,
    flareSize: Float,
    rayLength: Float,
    crossLength: Float,
    centerX: Float,
    centerY: Float,
    crossRays: Float,
    sphereRadius: Float,
    amplitude: Float,
    wavelength: Float,
    frequency: Float,
    twirlAngle: Float,
    twirlRadius: Float,
    polarMode: String,
    segments: Float,
    crystallizeSize: Float,
    hexSize: Float,
    triangleSize: Float,
    pointSize: Float,
    pointUseInk: Boolean,
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
    FilterKindUi.RADIAL -> RadialLinesParams(count.roundToInt(), inner, thickness, random = 0.4f, color = ink, strength = strength)
    FilterKindUi.SPEED -> SpeedLinesParams(angle, count.roundToInt(), thickness, length = 0.9f, color = ink, strength = strength)
    FilterKindUi.HALFTONE -> HalftoneParams(cell.roundToInt(), ink, strength)
    FilterKindUi.STROKE -> LayerStrokeParams(
        width = thickness.roundToInt(), inner = strokeInner, outer = strokeOuter,
        color = ink, opacity = amount, strength = strength,
    )
    FilterKindUi.SHADOW -> DropShadowParams(
        angleDeg = angle, distance = distance.roundToInt(), blur = radius.roundToInt(),
        color = ink, opacity = amount, strength = strength,
    )
    FilterKindUi.LAYER_GLOW -> LayerGlowParams(
        radius = radius.roundToInt(), color = ink, opacity = amount, strength = strength,
    )
    FilterKindUi.PANELS -> PanelGridParams(
        rows = rows.roundToInt(), cols = cols.roundToInt(), margin = margin.roundToInt(),
        gutter = gutter.roundToInt(), thickness = thickness.roundToInt(), color = ink, strength = strength,
    )
    FilterKindUi.SCREENTONE -> ScreentoneParams(
        cell = cell.roundToInt(), angleDeg = angle, shape = toneShape, color = ink, strength = strength,
    )
    FilterKindUi.SMOOTH -> SmoothParams(radius = radius, strength = strength)
    FilterKindUi.LENS_BLUR -> LensBlurParams(
        radius = radius, highlightBoost = highlightBoost, bladeAngleDeg = bladeAngle, strength = strength,
    )
    FilterKindUi.HIGH_PASS -> HighPassParams(radius = radius, strength = strength)
    FilterKindUi.SCATTER -> ScatterParams(distance = distance, strength = strength)
    FilterKindUi.GRAIN -> GrainParams(
        variant = grainVariant, size = grainSize.roundToInt(), strength = strength,
    )
    FilterKindUi.LOCAL_CONTRAST -> LocalContrastParams(radius = radius, strength = strength)
    FilterKindUi.BOKEH -> BokehParams(size = bokehSize, strength = strength)
    FilterKindUi.LENS_FLARE -> LensFlareParams(
        centerX = centerX, centerY = centerY, size = flareSize, strength = strength,
    )
    FilterKindUi.GOD_RAYS -> GodRaysParams(
        centerX = centerX, centerY = centerY, length = rayLength, threshold = threshold, strength = strength,
    )
    FilterKindUi.CROSS -> CrossFilterParams(
        threshold = threshold, length = crossLength, angleDeg = angle, rays = crossRays.roundToInt(),
        strength = strength,
    )
    FilterKindUi.SPHERE -> SphereLensParams(
        radius = sphereRadius, centerX = centerX, centerY = centerY, strength = strength,
    )
    FilterKindUi.WAVE -> WaveParams(
        amplitude = amplitude, wavelength = wavelength, angleDeg = angle, strength = strength,
    )
    FilterKindUi.RIPPLE -> RippleParams(
        amplitude = amplitude, frequency = frequency, centerX = centerX, centerY = centerY,
        strength = strength,
    )
    FilterKindUi.TWIRL -> TwirlParams(
        angleDeg = twirlAngle, radius = twirlRadius, centerX = centerX, centerY = centerY,
        strength = strength,
    )
    FilterKindUi.POLAR -> PolarCoordinatesParams(variant = polarMode, strength = strength)
    FilterKindUi.EXPANSION -> ExpansionParams(strength = strength)
    FilterKindUi.KALEIDOSCOPE -> KaleidoscopeParams(
        segments = segments.roundToInt(), centerX = centerX, centerY = centerY, strength = strength,
    )
    FilterKindUi.CRYSTALLIZE -> CrystallizeParams(size = crystallizeSize.roundToInt(), strength = strength)
    FilterKindUi.HEX_PIXELATE -> HexPixelateParams(size = hexSize.roundToInt(), strength = strength)
    FilterKindUi.TRIANGLE_PIXELATE -> TrianglePixelateParams(size = triangleSize.roundToInt(), strength = strength)
    FilterKindUi.POINTILLIZE -> PointillizeParams(
        size = pointSize.roundToInt(), background = if (pointUseInk) ink else "#FFFFFF", strength = strength,
    )
}
