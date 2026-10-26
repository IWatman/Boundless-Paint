package app.painter.core.adjust

import app.painter.core.color.Rgba
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject

/**
 * Parameters of one correction (spec/tools/m2-layers-adjust.md L5). Serialized as a JSON object whose `kind`
 * field selects the type, see [AdjustCodec]. Defaults of every type mean "no effect" where the type has a
 * neutral setting. Instances are immutable; edits go through `copy`.
 */
@Serializable
sealed class AdjustParams {
    /** Stable type id used in files and by the GPU ([AdjustKind]). */
    abstract val kind: String
}

/** A curve or LUT control point, x and y in 0..1. Serialized as `[x, y]`. */
@Serializable(with = PtSerializer::class)
data class Pt(val x: Float, val y: Float)

/** Straight line from (0,0) to (1,1). */
val LINEAR: List<Pt> = listOf(Pt(0f, 0f), Pt(1f, 1f))

@Serializable
@SerialName(AdjustKind.BRIGHTNESS_CONTRAST)
data class BrightnessContrastParams(
    /** -100..100. */
    val brightness: Float = 0f,
    /** -100..100. */
    val contrast: Float = 0f,
) : AdjustParams() {
    override val kind: String get() = AdjustKind.BRIGHTNESS_CONTRAST
}

@Serializable
@SerialName(AdjustKind.EXPOSURE)
data class ExposureParams(
    /** -5..5 EV. */
    val exposure: Float = 0f,
    /** -0.5..0.5, added in linear light. */
    val offset: Float = 0f,
    /** 0.2..5. */
    val gamma: Float = 1f,
) : AdjustParams() {
    override val kind: String get() = AdjustKind.EXPOSURE
}

@Serializable
@SerialName(AdjustKind.HUE_SATURATION)
data class HueSaturationParams(
    /** -180..180 degrees. */
    val hue: Float = 0f,
    /** -100..100. */
    val saturation: Float = 0f,
    /** -100..100. */
    val lightness: Float = 0f,
    val colorize: Boolean = false,
    /** 0..360 degrees, used when [colorize]. */
    val colorHue: Float = 0f,
    /** 0..100, used when [colorize]. */
    val colorSat: Float = 25f,
) : AdjustParams() {
    override val kind: String get() = AdjustKind.HUE_SATURATION
}

@Serializable
@SerialName(AdjustKind.VIBRANCE)
data class VibranceParams(
    /** -100..100, weighted by `1 - S` of the pixel. */
    val vibrance: Float = 0f,
    /** -100..100. */
    val saturation: Float = 0f,
) : AdjustParams() {
    override val kind: String get() = AdjustKind.VIBRANCE
}

@Serializable
@SerialName(AdjustKind.TEMPERATURE_TINT)
data class TemperatureTintParams(
    /** -100..100: shifts Lab b*. */
    val temperature: Float = 0f,
    /** -100..100: shifts Lab a*. */
    val tint: Float = 0f,
) : AdjustParams() {
    override val kind: String get() = AdjustKind.TEMPERATURE_TINT
}

/** Shifts of one tonal range, each -100..100: cyan-red, magenta-green, yellow-blue. */
@Serializable
data class ColorShift(val cr: Float = 0f, val mg: Float = 0f, val yb: Float = 0f)

@Serializable
@SerialName(AdjustKind.COLOR_BALANCE)
data class ColorBalanceParams(
    val shadows: ColorShift = ColorShift(),
    val midtones: ColorShift = ColorShift(),
    val highlights: ColorShift = ColorShift(),
    val preserveLum: Boolean = true,
) : AdjustParams() {
    override val kind: String get() = AdjustKind.COLOR_BALANCE
}

/** Levels of one channel, in 0..255 units except [gamma]. */
@Serializable
data class LevelsChannel(
    /** 0..253. */
    val inBlack: Float = 0f,
    /** 2..255, at least `inBlack + 2`. */
    val inWhite: Float = 255f,
    /** 0.1..9.99. */
    val gamma: Float = 1f,
    val outBlack: Float = 0f,
    val outWhite: Float = 255f,
)

/** Per-channel levels; r/g/b apply first, then [rgb]. */
@Serializable
@SerialName(AdjustKind.LEVELS)
data class LevelsParams(
    val rgb: LevelsChannel = LevelsChannel(),
    val r: LevelsChannel = LevelsChannel(),
    val g: LevelsChannel = LevelsChannel(),
    val b: LevelsChannel = LevelsChannel(),
) : AdjustParams() {
    override val kind: String get() = AdjustKind.LEVELS
}

/**
 * Monotone cubic curves (2..16 points each). r/g/b apply first, then [rgb]. The [a] curve is honored only by
 * destructive application ([AdjustMath.applyTile] with `applyAlpha`).
 */
@Serializable
@SerialName(AdjustKind.CURVES)
data class CurvesParams(
    val rgb: List<Pt> = LINEAR,
    val r: List<Pt> = LINEAR,
    val g: List<Pt> = LINEAR,
    val b: List<Pt> = LINEAR,
    val a: List<Pt> = LINEAR,
) : AdjustParams() {
    override val kind: String get() = AdjustKind.CURVES
}

/** Gradient-map stop: [pos] 0..1, straight sRGB [color]. */
@Serializable
data class GradientMapStop(
    val pos: Float,
    @Serializable(with = RgbaArraySerializer::class) val color: Rgba,
)

@Serializable
@SerialName(AdjustKind.GRADIENT_MAP)
data class GradientMapParams(
    val stops: List<GradientMapStop> = listOf(GradientMapStop(0f, Rgba.BLACK), GradientMapStop(1f, Rgba.WHITE)),
    val reverse: Boolean = false,
) : AdjustParams() {
    override val kind: String get() = AdjustKind.GRADIENT_MAP
}

@Serializable
@SerialName(AdjustKind.INVERT)
data class InvertParams(
    /** [RGB] inverts channels; [LUMINANCE] inverts HSL lightness only. */
    val variant: String = RGB,
) : AdjustParams() {
    override val kind: String get() = AdjustKind.INVERT

    companion object {
        const val RGB = "rgb"
        const val LUMINANCE = "luminance"
    }
}

@Serializable
@SerialName(AdjustKind.POSTERIZE)
data class PosterizeParams(
    /** 2..64. */
    val levels: Int = 8,
) : AdjustParams() {
    override val kind: String get() = AdjustKind.POSTERIZE
}

@Serializable
@SerialName(AdjustKind.THRESHOLD)
data class ThresholdParams(
    /** 0..255. */
    val level: Float = 128f,
    /** 0..100: width of the smooth step, in level units. */
    val smooth: Float = 0f,
) : AdjustParams() {
    override val kind: String get() = AdjustKind.THRESHOLD
}

@Serializable
@SerialName(AdjustKind.BLACK_WHITE)
data class BlackWhiteParams(
    /** Channel weights in percent, -200..200. */
    val wR: Float = 30f,
    val wG: Float = 59f,
    val wB: Float = 11f,
    val tint: Boolean = false,
    @Serializable(with = RgbaArraySerializer::class) val tintColor: Rgba = Rgba(0.88f, 0.78f, 0.62f),
) : AdjustParams() {
    override val kind: String get() = AdjustKind.BLACK_WHITE
}

/** A correction of a kind this build does not know: renders as no effect, written back unchanged. */
data class UnknownAdjust(val raw: JsonObject) : AdjustParams() {
    override val kind: String get() = (raw["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
}

/** Stable kind ids and GPU indices of the corrections. Never reorder or reuse indices. */
object AdjustKind {
    const val BRIGHTNESS_CONTRAST = "brightness_contrast"
    const val EXPOSURE = "exposure"
    const val HUE_SATURATION = "hue_saturation"
    const val VIBRANCE = "vibrance"
    const val TEMPERATURE_TINT = "temperature_tint"
    const val COLOR_BALANCE = "color_balance"
    const val LEVELS = "levels"
    const val CURVES = "curves"
    const val GRADIENT_MAP = "gradient_map"
    const val INVERT = "invert"
    const val POSTERIZE = "posterize"
    const val THRESHOLD = "threshold"
    const val BLACK_WHITE = "black_white"

    /** Known kinds in GPU index order: `ALL.indexOf(kind)` is the shader's `u_kind`. */
    val ALL: List<String> = listOf(
        BRIGHTNESS_CONTRAST, EXPOSURE, HUE_SATURATION, VIBRANCE, TEMPERATURE_TINT, COLOR_BALANCE,
        LEVELS, CURVES, GRADIENT_MAP, INVERT, POSTERIZE, THRESHOLD, BLACK_WHITE,
    )

    /** GPU index of [kind], or -1 when unknown. */
    fun index(kind: String): Int = ALL.indexOf(kind)

    /** Default instance of every known kind. */
    fun defaults(kind: String): AdjustParams? = when (kind) {
        BRIGHTNESS_CONTRAST -> BrightnessContrastParams()
        EXPOSURE -> ExposureParams()
        HUE_SATURATION -> HueSaturationParams()
        VIBRANCE -> VibranceParams()
        TEMPERATURE_TINT -> TemperatureTintParams()
        COLOR_BALANCE -> ColorBalanceParams()
        LEVELS -> LevelsParams()
        CURVES -> CurvesParams()
        GRADIENT_MAP -> GradientMapParams()
        INVERT -> InvertParams()
        POSTERIZE -> PosterizeParams()
        THRESHOLD -> ThresholdParams()
        BLACK_WHITE -> BlackWhiteParams()
        else -> null
    }

    /** Default English layer name of [kind]; the UI may localize it. */
    fun title(kind: String): String = when (kind) {
        BRIGHTNESS_CONTRAST -> "Brightness/Contrast"
        EXPOSURE -> "Exposure"
        HUE_SATURATION -> "Hue/Saturation"
        VIBRANCE -> "Vibrance"
        TEMPERATURE_TINT -> "Temperature/Tint"
        COLOR_BALANCE -> "Color balance"
        LEVELS -> "Levels"
        CURVES -> "Curves"
        GRADIENT_MAP -> "Gradient map"
        INVERT -> "Invert"
        POSTERIZE -> "Posterize"
        THRESHOLD -> "Threshold"
        BLACK_WHITE -> "Black & white"
        else -> "Adjustment"
    }
}

internal object PtSerializer : KSerializer<Pt> {
    private val delegate = ListSerializer(Float.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Pt) = delegate.serialize(encoder, listOf(value.x, value.y))

    override fun deserialize(decoder: Decoder): Pt {
        val l = delegate.deserialize(decoder)
        require(l.size == 2) { "point must be [x, y]" }
        return Pt(l[0], l[1])
    }
}

/** [Rgba] as `[r, g, b, a]` floats (lossless). */
internal object RgbaArraySerializer : KSerializer<Rgba> {
    private val delegate = ListSerializer(Float.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Rgba) =
        delegate.serialize(encoder, listOf(value.r, value.g, value.b, value.a))

    override fun deserialize(decoder: Decoder): Rgba {
        val l = delegate.deserialize(decoder)
        require(l.size == 3 || l.size == 4) { "color must be [r, g, b(, a)]" }
        return Rgba(l[0], l[1], l[2], l.getOrElse(3) { 1f })
    }
}
