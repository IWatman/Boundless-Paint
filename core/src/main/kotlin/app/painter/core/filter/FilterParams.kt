package app.painter.core.filter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil

/**
 * Parameters of one destructive filter (spec/filters.md, the M3 set). Serialized by kotlinx with the `kind`
 * discriminator, like the brush specials. [reach] is the largest distance, in pixels, the filter reads outside
 * the pixel it writes: the engine pads every tile patch by it, so tiles never show seams.
 */
@Serializable
sealed class FilterParams {
    abstract val kind: String
    abstract val reach: Int
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
