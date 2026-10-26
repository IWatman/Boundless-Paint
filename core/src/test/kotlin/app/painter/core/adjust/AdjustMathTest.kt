package app.painter.core.adjust

import app.painter.core.color.Rgba
import app.painter.core.vectors.Vectors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdjustMathTest {
    private fun apply(p: AdjustParams, r: Float, g: Float, b: Float, a: Float = 1f): FloatArray {
        val px = floatArrayOf(r * a, g * a, b * a, a)
        AdjustMath.applyTile(p, AdjustLut.build(p), px)
        return px
    }

    private fun near(e: Float, v: Float, tol: Float = 1e-6f) = assertTrue(abs(e - v) <= tol, "expected $e, was $v")

    @Test
    fun identityAtDefaults() {
        val neutral = listOf(
            BrightnessContrastParams(), ExposureParams(), HueSaturationParams(), VibranceParams(),
            TemperatureTintParams(), ColorBalanceParams(), LevelsParams(), CurvesParams(),
        )
        val input = Vectors.adjustInputs()
        for (p in neutral) {
            val out = Vectors.adjustOutput(p, input)
            for (i in input.indices) assertTrue(abs(out[i] - input[i]) <= 1e-6f, "${p.kind} at $i: ${input[i]} -> ${out[i]}")
        }
    }

    @Test
    fun unknownIsNoOp() {
        val p = UnknownAdjust(JsonObject(mapOf("kind" to JsonPrimitive("future_thing"))))
        val out = apply(p, 0.3f, 0.6f, 0.9f, 0.5f)
        assertEquals(listOf(0.15f, 0.3f, 0.45f, 0.5f), out.toList())
        assertTrue(AdjustMath.prepare(p).isNoOp)
    }

    @Test
    fun transparentUntouchedAndAlphaKept() {
        for (p in Vectors.adjustSets()) {
            val px = floatArrayOf(0f, 0f, 0f, 0f, 0.2f, 0.1f, 0.05f, 0.4f)
            AdjustMath.applyTile(p, AdjustLut.build(p), px)
            assertEquals(listOf(0f, 0f, 0f, 0f), px.take(4), p.kind)
            if (p !is GradientMapParams) near(0.4f, px[7])
        }
    }

    @Test
    fun curvesTwoPointsIsLine() {
        val c = MonotoneCurve(listOf(Pt(0f, 0.2f), Pt(1f, 0.6f)))
        for (i in 0..10) near(0.2f + 0.4f * i / 10f, c.eval(i / 10.0).toFloat(), 1e-6f)
    }

    @Test
    fun curvesMonotoneAndClampedOutside() {
        val c = MonotoneCurve(listOf(Pt(0.1f, 0.1f), Pt(0.3f, 0.8f), Pt(0.35f, 0.82f), Pt(0.9f, 0.9f)))
        var prev = -1.0
        for (i in 0..1000) {
            val v = c.eval(i / 1000.0)
            assertTrue(v >= prev - 1e-12, "not monotone at $i")
            prev = v
        }
        near(0.1f, c.eval(0.0).toFloat())
        near(0.9f, c.eval(1.0).toFloat())
        for ((x, y) in listOf(0.1 to 0.1, 0.3 to 0.8, 0.35 to 0.82, 0.9 to 0.9)) near(y.toFloat(), c.eval(x).toFloat(), 1e-6f)
    }

    @Test
    fun curvesRemovingInnerNodesGivesStraightLine() {
        val withNode = CurvesParams(rgb = listOf(Pt(0f, 0f), Pt(0.5f, 0.8f), Pt(1f, 1f)))
        val removed = withNode.copy(rgb = withNode.rgb.filterIndexed { i, _ -> i == 0 || i == withNode.rgb.lastIndex })
        near(0.5f, apply(removed, 0.5f, 0.5f, 0.5f)[0], 1e-5f)
        assertTrue(apply(withNode, 0.5f, 0.5f, 0.5f)[0] > 0.75f)
    }

    @Test
    fun curvesChannelThenMaster() {
        val p = CurvesParams(r = listOf(Pt(0f, 0f), Pt(1f, 0.5f)), rgb = listOf(Pt(0f, 1f), Pt(1f, 0f)))
        val out = apply(p, 1f, 1f, 1f)
        near(0.5f, out[0], 1e-5f)
        near(0f, out[1], 1e-5f)
    }

    @Test
    fun curvesAlphaOnlyDestructive() {
        val p = CurvesParams(a = listOf(Pt(0f, 0f), Pt(1f, 0.5f)))
        val px = floatArrayOf(0.5f, 0.5f, 0.5f, 1f)
        AdjustMath.applyTile(p, null, px)
        near(1f, px[3])
        AdjustMath.applyTile(p, null, px, applyAlpha = true)
        near(0.5f, px[3], 1e-5f)
        near(0.25f, px[0], 1e-5f)
    }

    @Test
    fun levelsBoundsAndGamma() {
        val p = LevelsParams(rgb = LevelsChannel(inBlack = 51f, inWhite = 204f))
        near(0f, apply(p, 0.1f, 0.2f, 0.2f)[0], 1e-5f)
        near(1f, apply(p, 0.9f, 0.8f, 0.8f)[0], 1e-5f)
        near(0.5f, apply(p, 0.5f, 0.5f, 0.5f)[0], 2e-3f)
        val out = LevelsParams(rgb = LevelsChannel(outBlack = 51f, outWhite = 204f))
        near(0.2f, apply(out, 0f, 0f, 0f)[0], 1e-5f)
        near(0.8f, apply(out, 1f, 1f, 1f)[0], 1e-5f)
        val gamma = LevelsParams(rgb = LevelsChannel(gamma = 2f))
        near(0.25.toFloat().let { kotlin.math.sqrt(it) }, apply(gamma, 0.25f, 0.25f, 0.25f)[0], 1e-3f)
        val inverted = LevelsParams(rgb = LevelsChannel(inBlack = 200f, inWhite = 100f))
        assertTrue(apply(inverted, 0.5f, 0.5f, 0.5f)[0] in 0f..1f)
    }

    @Test
    fun posterizeLevels() {
        for (n in listOf(2, 3, 4, 8)) {
            val p = PosterizeParams(n)
            val values = HashSet<Int>()
            for (i in 0..100) {
                val x = i / 100f
                val k = x * (n - 1)
                if (abs(k - kotlin.math.floor(k) - 0.5f) < 0.02f) continue
                values += (apply(p, x, x, x)[0] * (n - 1) * 1000).toInt()
            }
            assertEquals(n, values.map { (it + 500) / 1000 }.toSet().size, "levels $n")
        }
    }

    @Test
    fun gradientMapReverse() {
        val p = GradientMapParams(listOf(GradientMapStop(0f, Rgba(1f, 0f, 0f)), GradientMapStop(1f, Rgba(0f, 0f, 1f))))
        val black = apply(p, 0f, 0f, 0f)
        near(1f, black[0], 1e-5f); near(0f, black[2], 1e-5f)
        val rev = apply(p.copy(reverse = true), 0f, 0f, 0f)
        near(0f, rev[0], 1e-5f); near(1f, rev[2], 1e-5f)
        val alpha = GradientMapParams(listOf(GradientMapStop(0f, Rgba(0f, 0f, 0f, 0.5f)), GradientMapStop(1f, Rgba(1f, 1f, 1f, 0.5f))))
        near(0.25f, apply(alpha, 0.5f, 0.5f, 0.5f, 0.5f)[3], 1e-5f)
    }

    @Test
    fun thresholdAndBlackWhite() {
        assertEquals(1f, apply(ThresholdParams(128f), 0.6f, 0.6f, 0.6f)[0])
        assertEquals(0f, apply(ThresholdParams(128f), 0.4f, 0.4f, 0.4f)[0])
        val mid = apply(ThresholdParams(127.5f, 50f), 0.5f, 0.5f, 0.5f)[0]
        near(0.5f, mid, 1e-3f)
        near(0.3f, apply(BlackWhiteParams(), 1f, 0f, 0f)[1], 1e-5f)
    }

    @Test
    fun hueRotationAndColorize() {
        val out = apply(HueSaturationParams(hue = 120f), 1f, 0f, 0f)
        near(0f, out[0], 1e-5f); near(1f, out[1], 1e-5f)
        val gray = apply(HueSaturationParams(saturation = -100f), 0.8f, 0.2f, 0.2f)
        near(gray[0], gray[1], 1e-5f)
        val col = apply(HueSaturationParams(colorize = true, colorHue = 240f, colorSat = 100f), 0.5f, 0.5f, 0.5f)
        assertTrue(col[2] > col[0] && col[2] > col[1])
    }

    @Test
    fun autoLevelsOnSyntheticHistogram() {
        val h = Array(3) { IntArray(256) }
        for (v in 40..200) for (c in 0..2) h[c][v] = 100
        h[0][0] = 5
        h[0][255] = 5
        val p = AdjustMath.autoLevels(h)
        assertEquals(40f, p.r.inBlack)
        assertEquals(200f, p.r.inWhite)
        assertEquals(40f, p.g.inBlack)
        assertEquals(200f, p.b.inWhite)
        assertEquals(LevelsChannel(), p.rgb)
    }

    @Test
    fun serializationRoundTripEveryKind() {
        val all = Vectors.adjustSets() + AdjustKind.ALL.map { AdjustKind.defaults(it)!! }
        for (p in all) {
            val back = AdjustCodec.decode(AdjustCodec.encode(p))
            assertEquals(p, back)
            assertEquals(p.kind, AdjustCodec.toJson(p)["kind"]!!.let { (it as JsonPrimitive).content })
        }
        assertEquals(AdjustKind.ALL.toSet(), all.map { it.kind }.toSet())
    }

    @Test
    fun curvesPointsAreArrays() {
        val s = AdjustCodec.encode(CurvesParams(rgb = listOf(Pt(0f, 0f), Pt(0.5f, 0.6f), Pt(1f, 1f))))
        assertTrue(s.contains("[[0.0,0.0],[0.5,0.6],[1.0,1.0]]"), s)
    }

    @Test
    fun unknownRoundTrip() {
        val raw = """{"kind":"glow_x","radius":3,"nested":{"a":[1,2]}}"""
        val p = AdjustCodec.decode(raw)
        assertIs<UnknownAdjust>(p)
        assertEquals("glow_x", p.kind)
        assertEquals(AdjustCodec.toJson(p), Vectors.json.parseToJsonElement(raw).jsonObject)
        val broken = AdjustCodec.decode("""{"kind":"curves","rgb":"oops"}""")
        assertIs<UnknownAdjust>(broken)
    }

    @Test
    fun lutLayout() {
        val lut = AdjustLut.build(InvertParams())!!
        assertEquals(AdjustLut.SIZE * 4, lut.size)
        assertEquals(1f, lut[0]); assertEquals(0f, lut[(AdjustLut.SIZE - 1) * 4])
        assertEquals(0f, lut[3]); assertEquals(1f, lut[(AdjustLut.SIZE - 1) * 4 + 3])
        assertEquals(null, AdjustLut.build(HueSaturationParams()))
    }
}
