package app.painter.ui.studio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.adjust.AdjustParams
import app.painter.core.adjust.BlackWhiteParams
import app.painter.core.adjust.BrightnessContrastParams
import app.painter.core.adjust.ColorBalanceParams
import app.painter.core.adjust.ColorShift
import app.painter.core.adjust.ExposureParams
import app.painter.core.adjust.HueSaturationParams
import app.painter.core.adjust.InvertParams
import app.painter.core.adjust.PosterizeParams
import app.painter.core.adjust.TemperatureTintParams
import app.painter.core.adjust.ThresholdParams
import app.painter.core.adjust.VibranceParams

/** Controls of the kinds that are a handful of numeric sliders (and the odd switch). */
@Composable
internal fun BrightnessContrastControls(
    p: BrightnessContrastParams,
    onChange: (AdjustParams) -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        AdjustSlider(stringResource(R.string.adjust_brightness), p.brightness, -100f..100f, { onChange(p.copy(brightness = it)) }, onCommit)
        AdjustSlider(stringResource(R.string.adjust_contrast), p.contrast, -100f..100f, { onChange(p.copy(contrast = it)) }, onCommit)
    }
}

@Composable
internal fun ExposureControls(p: ExposureParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AdjustSlider(
            stringResource(R.string.adjust_exposure), p.exposure, -5f..5f,
            { onChange(p.copy(exposure = it)) }, onCommit, step = 0.1f, unit = "EV", decimals = 2,
        )
        AdjustSlider(
            stringResource(R.string.adjust_offset), p.offset, -0.5f..0.5f,
            { onChange(p.copy(offset = it)) }, onCommit, step = 0.01f, decimals = 2,
        )
        AdjustSlider(
            stringResource(R.string.adjust_gamma), p.gamma, 0.2f..5f,
            { onChange(p.copy(gamma = it)) }, onCommit, step = 0.05f, decimals = 2,
        )
    }
}

@Composable
internal fun HueSaturationControls(p: HueSaturationParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AdjustSlider(
            stringResource(R.string.adjust_hue), p.hue, -180f..180f,
            { onChange(p.copy(hue = it)) }, onCommit, unit = "°",
        )
        AdjustSlider(stringResource(R.string.adjust_saturation), p.saturation, -100f..100f, { onChange(p.copy(saturation = it)) }, onCommit)
        AdjustSlider(stringResource(R.string.adjust_lightness), p.lightness, -100f..100f, { onChange(p.copy(lightness = it)) }, onCommit)
        AdjustSwitch(stringResource(R.string.adjust_colorize), p.colorize) { on ->
            onChange(p.copy(colorize = on))
            onCommit()
        }
        if (p.colorize) {
            AdjustSlider(
                stringResource(R.string.adjust_color_hue), p.colorHue, 0f..360f,
                { onChange(p.copy(colorHue = it)) }, onCommit, unit = "°",
            )
            AdjustSlider(
                stringResource(R.string.adjust_color_saturation), p.colorSat, 0f..100f,
                { onChange(p.copy(colorSat = it)) }, onCommit,
            )
        }
    }
}

@Composable
internal fun VibranceControls(p: VibranceParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AdjustSlider(stringResource(R.string.adjust_vibrance), p.vibrance, -100f..100f, { onChange(p.copy(vibrance = it)) }, onCommit)
        AdjustSlider(stringResource(R.string.adjust_saturation), p.saturation, -100f..100f, { onChange(p.copy(saturation = it)) }, onCommit)
    }
}

@Composable
internal fun TemperatureTintControls(p: TemperatureTintParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AdjustSlider(stringResource(R.string.adjust_temperature), p.temperature, -100f..100f, { onChange(p.copy(temperature = it)) }, onCommit)
        AdjustSlider(stringResource(R.string.adjust_tint), p.tint, -100f..100f, { onChange(p.copy(tint = it)) }, onCommit)
    }
}

@Composable
internal fun ColorBalanceControls(p: ColorBalanceParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    var range by rememberSaveable { mutableIntStateOf(0) }
    val shift = when (range) {
        0 -> p.shadows
        1 -> p.midtones
        else -> p.highlights
    }
    val setShift: (ColorShift) -> Unit = { s ->
        when (range) {
            0 -> onChange(p.copy(shadows = s))
            1 -> onChange(p.copy(midtones = s))
            else -> onChange(p.copy(highlights = s))
        }
    }
    Column(Modifier.fillMaxWidth()) {
        AdjustTabs(
            labels = listOf(
                stringResource(R.string.adjust_shadows),
                stringResource(R.string.adjust_midtones),
                stringResource(R.string.adjust_highlights),
            ),
            selected = range,
            onSelect = { range = it },
        )
        AdjustSlider(
            stringResource(R.string.adjust_cyan_red), shift.cr, -100f..100f,
            { setShift(shift.copy(cr = it)) }, onCommit, modifier = Modifier.padding(top = 4.dp),
        )
        AdjustSlider(stringResource(R.string.adjust_magenta_green), shift.mg, -100f..100f, { setShift(shift.copy(mg = it)) }, onCommit)
        AdjustSlider(stringResource(R.string.adjust_yellow_blue), shift.yb, -100f..100f, { setShift(shift.copy(yb = it)) }, onCommit)
        AdjustSwitch(stringResource(R.string.adjust_preserve_luminosity), p.preserveLum) { on ->
            onChange(p.copy(preserveLum = on))
            onCommit()
        }
    }
}

@Composable
internal fun InvertControls(p: InvertParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AdjustSwitch(stringResource(R.string.adjust_luminance_only), p.variant == InvertParams.LUMINANCE) { on ->
            onChange(p.copy(variant = if (on) InvertParams.LUMINANCE else InvertParams.RGB))
            onCommit()
        }
    }
}

@Composable
internal fun PosterizeControls(p: PosterizeParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AdjustSlider(
            stringResource(R.string.adjust_posterize_levels), p.levels.toFloat(), 2f..64f,
            { onChange(p.copy(levels = it.toInt())) }, onCommit,
        )
    }
}

@Composable
internal fun ThresholdControls(p: ThresholdParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AdjustSlider(stringResource(R.string.adjust_threshold_level), p.level, 0f..255f, { onChange(p.copy(level = it)) }, onCommit)
        AdjustSlider(stringResource(R.string.adjust_smoothness), p.smooth, 0f..100f, { onChange(p.copy(smooth = it)) }, onCommit)
    }
}

@Composable
internal fun BlackWhiteControls(p: BlackWhiteParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AdjustSlider(stringResource(R.string.adjust_red_weight), p.wR, -200f..200f, { onChange(p.copy(wR = it)) }, onCommit)
        AdjustSlider(stringResource(R.string.adjust_green_weight), p.wG, -200f..200f, { onChange(p.copy(wG = it)) }, onCommit)
        AdjustSlider(stringResource(R.string.adjust_blue_weight), p.wB, -200f..200f, { onChange(p.copy(wB = it)) }, onCommit)
        AdjustSwitch(stringResource(R.string.adjust_tint), p.tint) { on ->
            onChange(p.copy(tint = on))
            onCommit()
        }
        if (p.tint) {
            HexColorField(
                label = stringResource(R.string.adjust_tint_color),
                color = p.tintColor,
                onChange = { onChange(p.copy(tintColor = it)) },
                onCommit = onCommit,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
