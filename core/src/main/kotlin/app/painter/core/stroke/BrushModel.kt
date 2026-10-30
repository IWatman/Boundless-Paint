package app.painter.core.stroke

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class TaperShape {
    /** `f = t`. */
    @SerialName("linear") LINEAR,

    /** `f = 1 − (1 − t)²` (M1 behaviour). */
    @SerialName("smooth") SMOOTH,
}

@Serializable
enum class HeadSource {
    /** Analytic round dab. */
    @SerialName("round") ROUND,

    /** [HeadImage] from [ResourceRefs.head]. */
    @SerialName("bitmap") BITMAP,

    /** Generated mask (rendered as [ROUND] until a generator is registered). */
    @SerialName("procedural") PROCEDURAL,
}

/** How the dab angle follows the stroke (spec/tools/m2-brush.md §1.2). */
@Serializable
enum class RotationMode {
    @SerialName("fixed") FIXED,
    @SerialName("tangent") TANGENT,
    @SerialName("reverse") REVERSE,
    @SerialName("stylus-azimuth") STYLUS_AZIMUTH,
    @SerialName("stylus-tilt") STYLUS_TILT,
    @SerialName("twist") TWIST,
    @SerialName("random") RANDOM,
}

/**
 * Dab shape (spec/brush-engine.md §2.2).
 *
 * @property aspect −1..1: > 0 squashes the height (`sy = 1 − 0.95a`), < 0 the width.
 * @property angle base angle, degrees.
 * @property rotationFollow −1..1 share of the direction followed in [RotationMode.TANGENT]/[RotationMode.REVERSE]
 *   (0 means 1).
 * @property depth bristle depth 0..1: the mask is used as a height map against pressure.
 * @property structure impasto strength 0..1 (S3).
 * @property baseColor hex colour the colour-stamp hue shift is relative to (S6).
 */
@Serializable
data class HeadParams(
    val source: HeadSource = HeadSource.ROUND,
    val color: Boolean = false,
    val invert: Boolean = false,
    val curve: Curve? = null,
    val colorize: Float = 0f,
    val baseColor: String? = null,
    val aspect: Float = 0f,
    val angle: Float = 0f,
    val rotationMode: RotationMode = RotationMode.FIXED,
    val rotationFollow: Float = 0f,
    val orientScreen: Boolean = false,
    val antialias: Boolean = true,
    val dots: Boolean = false,
    val depth: Float = 0f,
    val structure: Float = 0f,
)

/**
 * Per-dab randomness (spec/tools/m2-brush.md §1.3). Amounts are 0..1. Colour jitter: [hue] 1 = ±180°,
 * [saturation] and [brightness] 1 = ±1. `initial*` values are drawn once per stroke.
 */
@Serializable
data class JitterParams(
    val size: Float = 0f,
    val angle: Float = 0f,
    val scatter: Float = 0f,
    val flow: Float = 0f,
    val opacity: Float = 0f,
    val spacing: Float = 0f,
    val count: Int = 1,
    val initialAngle: Float = 0f,
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val brightness: Float = 0f,
    val initialHue: Float = 0f,
    val initialSaturation: Float = 0f,
    val initialBrightness: Float = 0f,
    val particles: ScatterParticles? = null,
)

/** ibis-style scatter particles (S6). [deviation] −1..1, [size] fraction of d or px when [absoluteSize]. */
@Serializable
data class ScatterParticles(
    val count: Int = 1,
    val size: Float = 0.1f,
    val absoluteSize: Boolean = false,
    val deviation: Float = 0f,
    val angleFromCenter: Boolean = false,
)

@Serializable
enum class TextureMode {
    @SerialName("canvas-fixed") CANVAS_FIXED,
    @SerialName("rotate-with-stroke") ROTATE_WITH_STROKE,
    @SerialName("warp-along-stroke") WARP_ALONG_STROKE,
    @SerialName("moving") MOVING,
}

/** Grain texture (S2, spec/tools/m2-brush.md §1.4). Model only in S0/S1. */
@Serializable
data class TextureParams(
    val mode: TextureMode = TextureMode.CANVAS_FIXED,
    val depth: Float = 0.5f,
    val lowerLimit: Float = 1f,
    val scale: Float = 1f,
    val stretch: Float = 1f,
    val angle: Float = 0f,
    val softness: Float = 0f,
    val structure: Float = 0f,
    val invert: Boolean = false,
    val curve: Curve? = null,
    val scaleWithSize: Boolean = false,
    val gradation: Float = 0f,
    val jitterPos: Boolean = false,
    val jitterScale: Boolean = false,
    val jitterAngle: Boolean = false,
    val antialias: Boolean = true,
)

/** Colour pickup (S4, spec/tools/m2-brush.md §1.8). Model only in S0/S1. */
@Serializable
data class PaintParams(
    val mixIn: Float = 0f,
    val dilution: Float = 0f,
    val wet: Boolean = false,
    val pull: Float = 0.5f,
    val blur: Float = 0f,
    val blendFlow: Float = 1f,
)

/** Stroke effects (S3, spec/tools/m2-brush.md §1.7). [blendMode] is a `BlendMode.id`. Model only in S0/S1. */
@Serializable
data class EffectParams(
    val glaze: Float = 0f,
    val wetEdges: Float = 0f,
    val adhesion: Float = 0f,
    val blendMode: String = "normal",
    val blendStrength: Float = 1f,
    val separateStrokes: Boolean = true,
)

/** Size and flow along the stroke: x ∈ [0, ⅓] = first [startPx], [⅓, ⅔] = middle, [⅔, 1] = last [endPx]. */
@Serializable
data class Profiles(
    val size: Curve? = null,
    val flow: Curve? = null,
    val startPx: Float = 60f,
    val endPx: Float = 60f,
)

/** Inputs of dynamics; all normalised to 0..1 (spec/tools/m2-brush.md §1.5). */
@Serializable
enum class DynSource { PRESSURE, VELOCITY, TILT, AZIMUTH, TWIST, RANDOM, PROGRESS }

/** Targets of dynamics. */
@Serializable
enum class DynTarget { SIZE, FLOW, OPACITY, SCATTER, ANGLE, HARDNESS, TEX_DEPTH, MIX_IN, DILUTION, COLOR_JITTER, BLUR }

/** One dynamics curve: `target ← curve(source)`. Links of one target multiply (angle: add). */
@Serializable
data class DynamicsLink(val source: DynSource, val target: DynTarget, val curve: Curve)

/** Special brush engines (S6–S9). Model only in S0/S1: the engine ignores them. */
@Serializable
sealed interface SpecialParams

@Serializable
@SerialName("watercolor")
data class WatercolorParams(val bleed: Float = 0.5f, val glaze: Float = 0.5f, val mixIn: Float = 0f, val dryout: Float = 0.5f) :
    SpecialParams

@Serializable
@SerialName("particles")
data class ParticlesParams(
    val attractors: Int = 8,
    val particles: Int = 40,
    val radius: Float = 1f,
    val size: Float = 0.05f,
    val overshoot: Float = 0.5f,
    /** True: each particle leaves a continuous trail (sparks, smoke); false: one stamp per step (dust, splatter). */
    val trail: Boolean = true,
) : SpecialParams

@Serializable
enum class HarmonyVariant { SKETCHY, SHADED, CHROME, FUR, LONG_FUR, WEB, SQUARES, CIRCLES, RIBBON, CURVY, GRID }

@Serializable
@SerialName("harmony")
data class HarmonyParams(val variant: HarmonyVariant = HarmonyVariant.SKETCHY, val spread: Float = 1f, val density: Float = 1f) :
    SpecialParams

@Serializable
@SerialName("double")
data class DoubleParams(
    val bodyColor: String? = null,
    val shadowColor: String = "#000000",
    val shadowSize: Float = 4f,
    val angle: Float = 45f,
    val distance: Float = 0f,
    val textureOnShadow: Boolean = false,
) : SpecialParams

@Serializable
@SerialName("color-stamp")
data class ColorStampParams(val colorize: Float = 1f) : SpecialParams

@Serializable
enum class ScreentoneShape { DOT, LINE, CROSS }

@Serializable
@SerialName("screentone")
data class ScreentoneParams(
    val spacing: Float = 8f,
    val angle: Float = 45f,
    val scaleWithSize: Boolean = false,
    val shape: ScreentoneShape = ScreentoneShape.DOT,
) : SpecialParams

@Serializable
enum class FillContent { SOLID, PATTERN, PROCEDURAL }

@Serializable
@SerialName("fill")
data class FillBrushParams(val content: FillContent = FillContent.SOLID, val drawPreview: Boolean = true) : SpecialParams

@Serializable
enum class FilterKind { BLUR, SHARPEN, GLOW }

@Serializable
@SerialName("filter")
data class FilterBrushParams(val filter: FilterKind = FilterKind.BLUR, val onStroke: Boolean = false, val strength: Float = 0.5f) :
    SpecialParams

@Serializable
enum class ThreeDKind { TUBE, RIBBON, TWISTY }

@Serializable
@SerialName("3d")
data class ThreeDParams(val kind: ThreeDKind = ThreeDKind.TUBE, val twistRate: Float = 1f) : SpecialParams

/** Image references; `asset:<path>` is inside the APK, `brush:<file>` inside the `.pbrush`. */
@Serializable
data class ResourceRefs(val head: String? = null, val texture: String? = null, val particle: String? = null)

/**
 * Display name: a plain string, or translations keyed by language (`{"ru": …, "en": …}` in JSON).
 * [text] is the fallback (English when present).
 */
@Serializable(with = BrushNameSerializer::class)
data class BrushName(val text: String, val translations: Map<String, String> = emptyMap()) {
    /** Name in [lang], falling back to [text]. */
    fun get(lang: String): String = translations[lang] ?: text

    companion object {
        fun of(vararg pairs: Pair<String, String>): BrushName {
            val m = linkedMapOf(*pairs)
            return BrushName(m["en"] ?: m.values.first(), m)
        }
    }
}

/** Writes a plain string when there are no translations, otherwise the translation object. */
object BrushNameSerializer : KSerializer<BrushName> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.painter.core.stroke.BrushName")

    override fun serialize(encoder: Encoder, value: BrushName) {
        val out = encoder as? JsonEncoder ?: throw SerializationException("BrushName supports JSON only")
        out.encodeJsonElement(
            if (value.translations.isEmpty()) JsonPrimitive(value.text)
            else JsonObject(value.translations.mapValues { JsonPrimitive(it.value) }),
        )
    }

    override fun deserialize(decoder: Decoder): BrushName {
        val input = decoder as? JsonDecoder ?: throw SerializationException("BrushName supports JSON only")
        return when (val e = input.decodeJsonElement()) {
            is JsonPrimitive -> BrushName(e.content)
            is JsonObject -> {
                val m = e.mapValues { it.value.jsonPrimitive.content }
                if (m.isEmpty()) BrushName("") else BrushName(m["en"] ?: m.values.first(), m)
            }
            else -> throw SerializationException("bad brush name")
        }
    }
}

/**
 * A brush preset (brush.json schema 1, spec/tools/m2-brush.md §3.2). Built-in ids are `<category>.<name>`
 * slugs, user brushes use UUIDs; [parentId] points at the preset this one was derived from.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Brush(
    @EncodeDefault val schema: Int = SCHEMA,
    val id: String,
    val parentId: String? = null,
    val name: BrushName = BrushName(""),
    val category: String = "",
    val tags: List<String> = emptyList(),
    val params: BrushParams = BrushParams(),
    val resources: ResourceRefs = ResourceRefs(),
) {
    companion object {
        const val SCHEMA = 1

        /** Anonymous brush around M1 [params]. */
        fun of(params: BrushParams, id: String = "anonymous"): Brush = Brush(id = id, params = params)
    }
}
