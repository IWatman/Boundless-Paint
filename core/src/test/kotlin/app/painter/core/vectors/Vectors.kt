package app.painter.core.vectors

import app.painter.core.adjust.AdjustCodec
import app.painter.core.adjust.AdjustLut
import app.painter.core.adjust.AdjustMath
import app.painter.core.adjust.AdjustParams
import app.painter.core.adjust.BlackWhiteParams
import app.painter.core.adjust.BrightnessContrastParams
import app.painter.core.adjust.ColorBalanceParams
import app.painter.core.adjust.ColorShift
import app.painter.core.adjust.CurvesParams
import app.painter.core.adjust.ExposureParams
import app.painter.core.adjust.GradientMapParams
import app.painter.core.adjust.GradientMapStop
import app.painter.core.adjust.HueSaturationParams
import app.painter.core.adjust.InvertParams
import app.painter.core.adjust.LevelsChannel
import app.painter.core.adjust.LevelsParams
import app.painter.core.adjust.PosterizeParams
import app.painter.core.adjust.Pt
import app.painter.core.adjust.TemperatureTintParams
import app.painter.core.adjust.ThresholdParams
import app.painter.core.adjust.VibranceParams
import app.painter.core.blend.BlendMath
import app.painter.core.blend.BlendMode
import app.painter.core.color.Rgba
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Shared CPU↔GPU test vectors (spec/tools/m2-layers-adjust.md L4). `blend.json`: premultiplied dst/src pairs ×
 * opacity, the result of every [BlendMode] by `shaderIndex`. `adjust.json`: premultiplied input pixels and the
 * [AdjustMath.applyTile] output for 6 parameter sets per kind.
 */
object Vectors {
    private val levels = floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f)
    private val alphas = floatArrayOf(0f, 0.25f, 0.5f, 1f)

    val json = Json { prettyPrint = false }

    /** Blend cases as (dst, src, opacity). */
    fun blendCases(): List<Triple<FloatArray, FloatArray, Float>> {
        val out = ArrayList<Triple<FloatArray, FloatArray, Float>>()
        for (ib in 0 until 5) for (iS in 0 until 5) for (ab in alphas) for (aS in alphas) {
            val cb = floatArrayOf(levels[ib], levels[(ib + 2) % 5], levels[(ib + 4) % 5])
            val cs = floatArrayOf(levels[iS], levels[(iS + 1) % 5], levels[(iS + 3) % 5])
            val dst = floatArrayOf(cb[0] * ab, cb[1] * ab, cb[2] * ab, ab)
            val src = floatArrayOf(cs[0] * aS, cs[1] * aS, cs[2] * aS, aS)
            for (op in floatArrayOf(1f, 0.5f)) out.add(Triple(dst, src, op))
        }
        return out
    }

    fun blendResult(mode: BlendMode, dst: FloatArray, src: FloatArray, opacity: Float): FloatArray {
        val d = dst.copyOf()
        BlendMath.blendPixel(mode, d, 0, src, 0, opacity)
        return d
    }

    fun blend(): JsonObject {
        val modes = BlendMode.entries.sortedBy { it.shaderIndex }
        val cases = blendCases().map { (d, s, op) ->
            JsonObject(
                mapOf(
                    "dst" to arr(d), "src" to arr(s), "opacity" to JsonPrimitive(op),
                    "out" to JsonArray(modes.map { arr(blendResult(it, d, s, op)) }),
                ),
            )
        }
        return JsonObject(mapOf("modes" to JsonArray(modes.map { JsonPrimitive(it.id) }), "cases" to JsonArray(cases)))
    }

    /** Premultiplied input pixels of adjust.json. */
    fun adjustInputs(): FloatArray {
        val v = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val out = ArrayList<Float>()
        for (r in v) for (g in v) for (b in v) out += listOf(r, g, b, 1f)
        val extra = listOf(Rgba(0.9f, 0.3f, 0.1f, 0.5f), Rgba(0.2f, 0.6f, 0.9f, 0.25f), Rgba(0.4f, 0.4f, 0.4f, 0.75f))
        for (c in extra) out += listOf(c.r * c.a, c.g * c.a, c.b * c.a, c.a)
        out += listOf(0f, 0f, 0f, 0f)
        return out.toFloatArray()
    }

    /** Six parameter sets per kind. */
    fun adjustSets(): List<AdjustParams> = listOf(
        BrightnessContrastParams(), BrightnessContrastParams(40f, 0f), BrightnessContrastParams(-60f, 0f),
        BrightnessContrastParams(0f, 50f), BrightnessContrastParams(0f, -70f), BrightnessContrastParams(25f, 80f),
        ExposureParams(), ExposureParams(1f), ExposureParams(-2f), ExposureParams(0f, 0.1f),
        ExposureParams(0f, 0f, 2.2f), ExposureParams(0.5f, -0.05f, 0.6f),
        HueSaturationParams(), HueSaturationParams(hue = 90f), HueSaturationParams(saturation = -100f),
        HueSaturationParams(saturation = 50f, lightness = 30f), HueSaturationParams(lightness = -50f),
        HueSaturationParams(colorize = true, colorHue = 200f, colorSat = 60f),
        VibranceParams(), VibranceParams(80f), VibranceParams(-50f), VibranceParams(0f, 40f),
        VibranceParams(0f, -100f), VibranceParams(50f, 20f),
        TemperatureTintParams(), TemperatureTintParams(50f), TemperatureTintParams(-80f),
        TemperatureTintParams(0f, 40f), TemperatureTintParams(0f, -60f), TemperatureTintParams(30f, 30f),
        ColorBalanceParams(), ColorBalanceParams(shadows = ColorShift(50f, 0f, -30f)),
        ColorBalanceParams(midtones = ColorShift(-40f, 60f, 0f)),
        ColorBalanceParams(highlights = ColorShift(0f, -20f, 70f)),
        ColorBalanceParams(midtones = ColorShift(80f, 0f, 0f), preserveLum = false),
        ColorBalanceParams(ColorShift(20f, 20f, 20f), ColorShift(-20f, 10f, 0f), ColorShift(10f, -10f, 30f)),
        LevelsParams(), LevelsParams(rgb = LevelsChannel(30f, 220f)), LevelsParams(rgb = LevelsChannel(gamma = 2f)),
        LevelsParams(rgb = LevelsChannel(outBlack = 40f, outWhite = 200f)),
        LevelsParams(r = LevelsChannel(50f, 255f), b = LevelsChannel(0f, 180f, 0.5f)),
        LevelsParams(rgb = LevelsChannel(10f, 245f, 1.3f), g = LevelsChannel(outWhite = 230f)),
        CurvesParams(), CurvesParams(rgb = listOf(Pt(0f, 0f), Pt(0.5f, 0.7f), Pt(1f, 1f))),
        CurvesParams(rgb = listOf(Pt(0f, 0f), Pt(0.25f, 0.15f), Pt(0.75f, 0.85f), Pt(1f, 1f))),
        CurvesParams(rgb = listOf(Pt(0f, 1f), Pt(1f, 0f))),
        CurvesParams(r = listOf(Pt(0f, 0.1f), Pt(1f, 0.9f)), g = listOf(Pt(0f, 0f), Pt(0.4f, 0.6f), Pt(1f, 1f))),
        CurvesParams(b = listOf(Pt(0.2f, 0f), Pt(0.8f, 1f)), rgb = listOf(Pt(0f, 0.05f), Pt(1f, 0.95f))),
        GradientMapParams(), GradientMapParams(reverse = true),
        GradientMapParams(listOf(GradientMapStop(0f, Rgba(0.1f, 0f, 0.3f)), GradientMapStop(1f, Rgba(1f, 0.9f, 0.2f)))),
        GradientMapParams(
            listOf(
                GradientMapStop(0f, Rgba.BLACK), GradientMapStop(0.5f, Rgba(1f, 0f, 0f)), GradientMapStop(1f, Rgba.WHITE),
            ),
        ),
        GradientMapParams(listOf(GradientMapStop(0.2f, Rgba(0f, 0f, 1f, 0.5f)), GradientMapStop(0.8f, Rgba(0f, 1f, 0f)))),
        GradientMapParams(listOf(GradientMapStop(0f, Rgba(0.5f, 0.5f, 0.5f)), GradientMapStop(1f, Rgba(0f, 0f, 0f))), true),
        InvertParams(), InvertParams(InvertParams.LUMINANCE), InvertParams(), InvertParams(InvertParams.LUMINANCE),
        InvertParams(), InvertParams(InvertParams.LUMINANCE),
        PosterizeParams(), PosterizeParams(2), PosterizeParams(3), PosterizeParams(4), PosterizeParams(16),
        PosterizeParams(64),
        ThresholdParams(), ThresholdParams(64f), ThresholdParams(200f), ThresholdParams(128f, 20f),
        ThresholdParams(100f, 100f), ThresholdParams(0f),
        BlackWhiteParams(), BlackWhiteParams(100f, 0f, 0f), BlackWhiteParams(-50f, 150f, 0f),
        BlackWhiteParams(tint = true), BlackWhiteParams(20f, 20f, 20f, true, Rgba(0.5f, 0.8f, 1f)),
        BlackWhiteParams(200f, -100f, 30f),
    )

    fun adjustOutput(p: AdjustParams, input: FloatArray): FloatArray =
        input.copyOf().also { AdjustMath.applyTile(p, AdjustLut.build(p), it) }

    fun adjust(): JsonObject {
        val input = adjustInputs()
        val sets = adjustSets().map { p ->
            JsonObject(mapOf("params" to AdjustCodec.toJson(p), "out" to arr(adjustOutput(p, input))))
        }
        return JsonObject(mapOf("inputs" to arr(input), "sets" to JsonArray(sets)))
    }

    fun floats(e: JsonElement): FloatArray = (e as JsonArray).map { (it as JsonPrimitive).content.toFloat() }.toFloatArray()

    private fun arr(v: FloatArray) = JsonArray(v.map { JsonPrimitive(it) })
}

/** Entry point of `:core:genVectors`: writes `blend.json` and `adjust.json` into the directory in `args[0]`. */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "src/test/resources/vectors")
    dir.mkdirs()
    File(dir, "blend.json").writeText(Vectors.json.encodeToString(JsonObject.serializer(), Vectors.blend()))
    File(dir, "adjust.json").writeText(Vectors.json.encodeToString(JsonObject.serializer(), Vectors.adjust()))
    println("vectors written to ${dir.absolutePath}")
}
