package app.painter.ui.studio

import androidx.annotation.StringRes
import app.painter.R
import app.painter.core.adjust.AdjustKind

/** Localized layer title of adjustment kind [kind]; [R.string.adjust_title] for unknown kinds. */
@StringRes
fun adjustTitleRes(kind: String): Int = when (kind) {
    AdjustKind.BRIGHTNESS_CONTRAST -> R.string.adjust_kind_brightness_contrast
    AdjustKind.EXPOSURE -> R.string.adjust_kind_exposure
    AdjustKind.HUE_SATURATION -> R.string.adjust_kind_hue_saturation
    AdjustKind.VIBRANCE -> R.string.adjust_kind_vibrance
    AdjustKind.TEMPERATURE_TINT -> R.string.adjust_kind_temperature_tint
    AdjustKind.COLOR_BALANCE -> R.string.adjust_kind_color_balance
    AdjustKind.LEVELS -> R.string.adjust_kind_levels
    AdjustKind.CURVES -> R.string.adjust_kind_curves
    AdjustKind.GRADIENT_MAP -> R.string.adjust_kind_gradient_map
    AdjustKind.INVERT -> R.string.adjust_kind_invert
    AdjustKind.POSTERIZE -> R.string.adjust_kind_posterize
    AdjustKind.THRESHOLD -> R.string.adjust_kind_threshold
    AdjustKind.BLACK_WHITE -> R.string.adjust_kind_black_white
    else -> R.string.adjust_title
}

/**
 * Short badge drawn instead of a pixel thumbnail in the layer row of an adjustment layer
 * (adjustment layers have no pixels of their own).
 */
fun adjustBadge(kind: String?): String = when (kind) {
    AdjustKind.BRIGHTNESS_CONTRAST -> "B/C"
    AdjustKind.EXPOSURE -> "EV"
    AdjustKind.HUE_SATURATION -> "H/S"
    AdjustKind.VIBRANCE -> "VI"
    AdjustKind.TEMPERATURE_TINT -> "T/T"
    AdjustKind.COLOR_BALANCE -> "CB"
    AdjustKind.LEVELS -> "LV"
    AdjustKind.CURVES -> "CV"
    AdjustKind.GRADIENT_MAP -> "GM"
    AdjustKind.INVERT -> "IN"
    AdjustKind.POSTERIZE -> "PO"
    AdjustKind.THRESHOLD -> "TH"
    AdjustKind.BLACK_WHITE -> "BW"
    else -> "ADJ"
}
