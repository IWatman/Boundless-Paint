package app.painter.core.filter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil

/*
 * Parameters of the M5 blur, light and texture filters (spec/filters.md). They belong to the sealed
 * [FilterParams] family and are written by [FilterCodec] exactly like the M3 ones.
 */

/** Edge-preserving smoothing: a blur that keeps strong outlines. */
@Serializable
@SerialName("smooth")
data class SmoothParams(
    /** 1..32 px. */
    val radius: Float = 4f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "smooth"
    override val reach: Int get() = 2 * ceil(radius.coerceIn(1f, 32f)).toInt()
}

/** Polygonal aperture blur with bright highlights weighted more, like a lens stopped down. */
@Serializable
@SerialName("lens-blur")
data class LensBlurParams(
    /** 0..100 px. */
    val radius: Float = 12f,
    /** 0..1, how much brighter samples dominate the average. */
    val highlightBoost: Float = 0.4f,
    /** 0..360 degrees, rotation of the aperture polygon. */
    val bladeAngleDeg: Float = 0f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "lens-blur"
    override val reach: Int get() = ceil(radius.coerceIn(0f, 100f)).toInt() + 1
}

/** Classic high-pass detail: a grey image of the difference from the blurred source. */
@Serializable
@SerialName("high-pass")
data class HighPassParams(
    /** 0.5..32 px. */
    val radius: Float = 8f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "high-pass"
    override val reach: Int get() = 2 * ceil(radius.coerceIn(0.5f, 32f)).toInt()
}

/** Frosted glass: a smooth random displacement of the pixels by up to [distance] px. */
@Serializable
@SerialName("scatter")
data class ScatterParams(
    /** 1..50 px. */
    val distance: Float = 8f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "scatter"
    override val reach: Int get() = ceil(distance.coerceIn(1f, 50f)).toInt() + 2
}

/** Film grain in the colour channels or in the alpha channel on a [size]-px cell grid. */
@Serializable
@SerialName("grain")
data class GrainParams(
    /** mono, rgb or alpha. */
    val variant: String = "mono",
    /** 1..16 px. */
    val size: Int = 2,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "grain"
    override val reach: Int get() = 0
}

/** Local contrast: luminance detail added back at the [radius] scale. */
@Serializable
@SerialName("local-contrast")
data class LocalContrastParams(
    /** 1..32 px. */
    val radius: Float = 12f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "local-contrast"
    override val reach: Int get() = 2 * ceil(radius.coerceIn(1f, 32f)).toInt()
}

/** Out-of-focus highlights: a disc-shaped bloom of the bright pixels. */
@Serializable
@SerialName("bokeh")
data class BokehParams(
    /** 1..64 px. */
    val size: Float = 12f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "bokeh"
    override val reach: Int get() = ceil(size.coerceIn(1f, 64f)).toInt() + 1
}

/** Additive lens flare with a core, a ring and star streaks at [centerX],[centerY] of the content. */
@Serializable
@SerialName("lens-flare")
data class LensFlareParams(
    /** 0..1 of the content width. */
    val centerX: Float = 0.5f,
    /** 0..1 of the content height. */
    val centerY: Float = 0.5f,
    /** 1..80 px. */
    val size: Float = 40f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "lens-flare"
    override val reach: Int get() = ceil(size.coerceIn(1f, 80f) * 2.5f).toInt().coerceAtLeast(1)
}

/** Light shafts that gather the pixels brighter than [threshold] toward a centre. */
@Serializable
@SerialName("god-rays")
data class GodRaysParams(
    /** 0..1 of the content width. */
    val centerX: Float = 0.5f,
    /** 0..1 of the content height. */
    val centerY: Float = 0.5f,
    /** 1..160 px. */
    val length: Float = 64f,
    /** 0..1 luminance threshold. */
    val threshold: Float = 0.6f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "god-rays"
    override val reach: Int get() = ceil(length.coerceIn(1f, 160f)).toInt() + 2
}

/** Star streaks from the bright pixels, [rays] spikes at [angleDeg]. */
@Serializable
@SerialName("cross")
data class CrossFilterParams(
    /** 0..1 luminance threshold. */
    val threshold: Float = 0.6f,
    /** 1..80 px. */
    val length: Float = 24f,
    /** 0..360 degrees. */
    val angleDeg: Float = 0f,
    /** 2..8 spikes. */
    val rays: Int = 4,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "cross"
    override val reach: Int get() = ceil(length.coerceIn(1f, 80f)).toInt() + 2
}
