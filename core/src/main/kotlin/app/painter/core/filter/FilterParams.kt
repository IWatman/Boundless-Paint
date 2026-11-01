package app.painter.core.filter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil

/**
 * Parameters of one destructive filter (spec/filters.md, the M3 and M5 sets). Serialized by kotlinx with the
 * `kind` discriminator, like the brush specials. [reach] is the largest distance, in pixels, the filter reads
 * outside the pixel it writes: the engine pads every tile patch by it, so tiles never show seams.
 */
@Serializable
sealed class FilterParams {
    abstract val kind: String
    abstract val reach: Int

    /**
     * True for generators that draw over the whole canvas (or selection) instead of filtering the painted
     * pixels: an empty layer is still a valid target for them.
     */
    open val coversCanvas: Boolean get() = false
}

/** Gaussian-like blur: three box passes of [radius] px. [strength] mixes the result with the source. */
@Serializable
@SerialName("blur")
data class BlurParams(
    val radius: Float = 8f,
    /** 0..1. */
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "blur"
    override val reach: Int get() = ceil(radius.coerceIn(0f, 250f)).toInt()
}

/** Directional blur along [angleDeg] over [distance] px (half to each side). */
@Serializable
@SerialName("motion-blur")
data class MotionBlurParams(
    val distance: Float = 24f,
    val angleDeg: Float = 0f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "motion-blur"
    override val reach: Int get() = ceil(distance.coerceIn(0f, 500f) / 2f).toInt()
}

/** Square pixelation: one average colour per [cell]×[cell] block, aligned to the canvas grid. */
@Serializable
@SerialName("pixelate")
data class PixelateParams(
    val cell: Int = 12,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "pixelate"
    override val reach: Int get() = cell.coerceIn(2, 200)
}

/** Unsharp mask: `src + (src - blur(src, radius)) · amount`. */
@Serializable
@SerialName("sharpen")
data class SharpenParams(
    /** 0..5, 1 is a strong edge boost. */
    val amount: Float = 0.8f,
    val radius: Float = 1.5f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "sharpen"
    override val reach: Int get() = ceil(radius.coerceIn(0.1f, 100f)).toInt()
}

/** Soft bloom: blur the pixels brighter than [threshold] and screen them over the source. */
@Serializable
@SerialName("glow")
data class GlowParams(
    /** 0..1 luminance threshold. */
    val threshold: Float = 0.6f,
    val radius: Float = 16f,
    /** 0..2. */
    val strength: Float = 0.8f,
) : FilterParams() {
    override val kind: String get() = "glow"
    override val reach: Int get() = ceil(radius.coerceIn(0f, 250f)).toInt()
}

/** Additive noise, deterministic per pixel and [seed]. */
@Serializable
@SerialName("noise")
data class NoiseParams(
    /** 0..1. */
    val amount: Float = 0.15f,
    val mono: Boolean = true,
    val seed: Long = 1L,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "noise"
    override val reach: Int get() = 0
}

/** Sobel edge magnitude as a grey line over the layer's own alpha. */
@Serializable
@SerialName("edges")
data class EdgesParams(
    /** 0..3, scales the edge magnitude. */
    val amount: Float = 1f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "edges"
    override val reach: Int get() = 1
}

/** Red and blue channels shifted in opposite directions along [angleDeg]. */
@Serializable
@SerialName("chromatic")
data class ChromaticParams(
    val distance: Float = 4f,
    val angleDeg: Float = 0f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "chromatic"
    override val reach: Int get() = ceil(distance.coerceIn(0f, 200f)).toInt()
}

/**
 * Lens distortion around the centre of the painted area: [amount] > 0 bulges (fisheye), < 0 pinches.
 * [amount] is -1..1; [scale] is the radius of the affected area relative to the content.
 */
@Serializable
@SerialName("fisheye")
data class FisheyeParams(
    val amount: Float = 0.5f,
    val strength: Float = 1f,
    /** 0.2..1 of the content radius. */
    val scale: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "fisheye"
    override val reach: Int get() = 256
}

/** Morphological dilate: grows the painted area by [radius] px (the alpha expands). */
@Serializable
@SerialName("dilate")
data class DilateParams(
    val radius: Int = 3,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "dilate"
    override val reach: Int get() = radius.coerceIn(1, 20)
}

/** Morphological erode: shrinks the painted area by [radius] px. */
@Serializable
@SerialName("erode")
data class ErodeParams(
    val radius: Int = 3,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "erode"
    override val reach: Int get() = radius.coerceIn(1, 20)
}

/** Radial blur toward the centre of the painted area (zoom blur). */
@Serializable
@SerialName("zoom-blur")
data class ZoomBlurParams(
    /** 0..0.9 of the way to the centre. */
    val amount: Float = 0.35f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "zoom-blur"
    override val reach: Int get() = 256
}

/** Rotational blur around the centre of the painted area (spin blur). */
@Serializable
@SerialName("spin-blur")
data class SpinBlurParams(
    /** -180..180 degrees of the arc, positive is clockwise. */
    val angleDeg: Float = 30f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "spin-blur"
    override val reach: Int get() = 256
}

/** Radial lines from the centre of the canvas (a Draw generator). */
@Serializable
@SerialName("radial-lines")
data class RadialLinesParams(
    val count: Int = 60,
    /** Inner radius as a fraction of the content radius (0..0.8). */
    val inner: Float = 0.15f,
    val thickness: Float = 2f,
    /** Length randomness 0..1: 0 = all lines reach the edge. */
    val random: Float = 0.4f,
    val color: String = "#000000",
    val seed: Long = 1L,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "radial-lines"
    override val reach: Int get() = 0
    override val coversCanvas: Boolean get() = true
}

/** Parallel speed lines across the canvas at [angleDeg] (a Draw generator). */
@Serializable
@SerialName("speed-lines")
data class SpeedLinesParams(
    val angleDeg: Float = 0f,
    val count: Int = 40,
    val thickness: Float = 2f,
    /** Length as a fraction of the canvas diagonal (0.2..1). */
    val length: Float = 0.9f,
    val color: String = "#000000",
    val seed: Long = 1L,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "speed-lines"
    override val reach: Int get() = 0
    override val coversCanvas: Boolean get() = true
}

/** Halftone: the layer's luminance becomes dots of [color] on white, one dot per [cell] px. */
@Serializable
@SerialName("halftone")
data class HalftoneParams(
    val cell: Int = 8,
    val color: String = "#000000",
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "halftone"
    override val reach: Int get() = 0
}

/** Raster layer style: an outline outside, inside or on both sides of the painted pixels. */
@Serializable
@SerialName("layer-stroke")
data class LayerStrokeParams(
    val width: Int = 4,
    val inner: Boolean = false,
    val outer: Boolean = true,
    val color: String = "#000000",
    val opacity: Float = 1f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "layer-stroke"
    override val reach: Int get() = width.coerceIn(1, 100)
}

/** Raster layer style: a drop shadow behind the painted pixels. */
@Serializable
@SerialName("drop-shadow")
data class DropShadowParams(
    val angleDeg: Float = 45f,
    val distance: Int = 8,
    val blur: Int = 6,
    val color: String = "#000000",
    val opacity: Float = 0.6f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "drop-shadow"
    override val reach: Int get() = (distance.coerceIn(0, 200) + blur.coerceIn(0, 100)).coerceAtLeast(1)
}

/** Raster layer style: a soft glow around the painted pixels. */
@Serializable
@SerialName("layer-glow")
data class LayerGlowParams(
    val radius: Int = 12,
    val color: String = "#FFFFFF",
    val opacity: Float = 0.9f,
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "layer-glow"
    override val reach: Int get() = radius.coerceIn(1, 100)
}

/** Screentone generator: dots, parallel lines or a cross hatch on a [cell]-px grid. */
@Serializable
@SerialName("screentone")
data class ScreentoneParams(
    val cell: Int = 8,
    val angleDeg: Float = 45f,
    /** dot, line or cross. */
    val shape: String = "dot",
    val color: String = "#000000",
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "screentone"
    override val reach: Int get() = 0
    override val coversCanvas: Boolean get() = true
}

/** Draw generator: a page divided into [rows] x [cols] panels by margins and gutters. */
@Serializable
@SerialName("panel-grid")
data class PanelGridParams(
    val rows: Int = 2,
    val cols: Int = 2,
    val margin: Int = 40,
    val gutter: Int = 24,
    val thickness: Int = 4,
    val color: String = "#FFFFFF",
    val strength: Float = 1f,
) : FilterParams() {
    override val kind: String get() = "panel-grid"
    override val reach: Int get() = 0
    override val coversCanvas: Boolean get() = true
}
