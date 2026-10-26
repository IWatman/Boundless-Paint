package app.painter.core.vectors

import app.painter.core.adjust.AdjustCodec
import app.painter.core.blend.BlendMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Regression of BlendMath / AdjustMath against the committed vectors (regenerate with `:core:genVectors`). */
class VectorsTest {
    private fun load(name: String): JsonObject {
        val s = assertNotNull(javaClass.getResourceAsStream("/vectors/$name"), "missing vectors/$name").bufferedReader().readText()
        return Vectors.json.parseToJsonElement(s).jsonObject
    }

    @Test
    fun blendVectors() {
        val v = load("blend.json")
        val modes = (v["modes"] as JsonArray).map { BlendMode.fromId((it as JsonPrimitive).content) }
        assertEquals(BlendMode.entries.sortedBy { it.shaderIndex }, modes)
        val cases = v["cases"] as JsonArray
        assertEquals(Vectors.blendCases().size, cases.size)
        assertTrue(cases.size >= 400)
        for (c in cases) {
            val o = c.jsonObject
            val dst = Vectors.floats(o["dst"]!!)
            val src = Vectors.floats(o["src"]!!)
            val op = (o["opacity"] as JsonPrimitive).content.toFloat()
            val outs = o["out"] as JsonArray
            for ((mi, m) in modes.withIndex()) {
                val expected = Vectors.floats(outs[mi])
                val actual = Vectors.blendResult(m, dst, src, op)
                for (i in 0..3) {
                    assertTrue(abs(expected[i] - actual[i]) <= 1e-6f, "$m dst=${dst.toList()} src=${src.toList()} op=$op")
                }
            }
        }
    }

    @Test
    fun adjustVectors() {
        val v = load("adjust.json")
        val input = Vectors.floats(v["inputs"]!!)
        val sets = v["sets"] as JsonArray
        assertEquals(Vectors.adjustSets().size, sets.size)
        for (s in sets) {
            val p = AdjustCodec.fromJson(s.jsonObject["params"]!!.jsonObject)
            val expected = Vectors.floats(s.jsonObject["out"]!!)
            val actual = Vectors.adjustOutput(p, input)
            for (i in input.indices) assertTrue(abs(expected[i] - actual[i]) <= 1e-6f, "${p.kind} $p at $i")
        }
    }

    @Test
    fun sixSetsPerKind() {
        val byKind = Vectors.adjustSets().groupingBy { it.kind }.eachCount()
        assertEquals(13, byKind.size)
        assertTrue(byKind.values.all { it == 6 }, byKind.toString())
    }
}
