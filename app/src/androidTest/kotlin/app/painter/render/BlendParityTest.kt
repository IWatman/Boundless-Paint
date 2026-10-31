package app.painter.render

import android.opengl.GLES30 as GL
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.painter.core.adjust.AdjustCodec
import app.painter.core.adjust.AdjustMath
import app.painter.core.adjust.AdjustParams
import app.painter.core.blend.BlendMode
import app.painter.core.blend.BlendMath
import app.painter.core.raster.TileGrid
import app.painter.core.blend.Compositor
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GPU-vs-CPU parity of the canvas renderer, run on a device/emulator.
 *
 * The vectors are the committed CPU references of `:core` (`core/src/test/resources/vectors`, exposed to the
 * androidTest assets by `app/build.gradle.kts`): every case is rendered with the *same* shaders the renderer uses
 * ([Shaders.BLEND_FS], [ADJUST_FS] via [AdjustShader]) into a [GlHarness.SIZE]x[GlHarness.SIZE] RGBA16F target,
 * read back with `glReadPixels(GL_RGBA, GL_FLOAT)` and compared with the CPU result.
 *
 * Run with: `.\gradlew.bat :app:connectedDebugAndroidTest` (or
 * `.\gradlew.bat :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.painter.render.BlendParityTest`).
 */
@RunWith(AndroidJUnit4::class)
class BlendParityTest {

    /** One blend vector: premultiplied backdrop/source and the expected premultiplied result per mode. */
    private class BlendCase(val dst: FloatArray, val src: FloatArray, val opacity: Float, val out: List<FloatArray>)

    /** One adjustment vector: its parameters and the expected straight RGBA output of every input pixel. */
    private class AdjustSet(val kind: String, val params: AdjustParams, val out: FloatArray)

    @Test
    fun blendModes() {
        val (modes, cases) = blendVectors()
        check(cases.isNotEmpty() && modes.isNotEmpty()) { "blend.json has no vectors" }
        for (c in cases) check(c.out.size == modes.size) { "blend.json: out has ${c.out.size} entries, ${modes.size} modes" }

        val gl = GlHarness()
        try {
            val format = gl.renderTargetFormat()
            val dstTex = gl.texture(format)
            val srcTex = gl.texture(format)
            val outTex = gl.texture(format)
            val fbo = gl.framebuffer(outTex)
            val prog = gl.link(Shaders.QUAD_VS, Shaders.BLEND_FS)
            GL.glUseProgram(prog)
            // Exactly the state CanvasRenderer.blendPass sets for a layer without preview, clip or mask.
            val uMode = uniform(prog, "u_mode")
            val uOpacity = uniform(prog, "u_opacity")
            val uGamma = uniform(prog, "u_gamma")
            GL.glUniform1i(uniform(prog, "u_dst"), 0)
            GL.glUniform1i(uniform(prog, "u_src"), 1)
            GL.glUniform1i(uniform(prog, "u_preview"), Shaders.PREVIEW_NONE)
            GL.glUniform1f(uniform(prog, "u_previewOpacity"), 1f)
            GL.glUniform1i(uniform(prog, "u_alphaLock"), 0)
            GL.glUniform1i(uniform(prog, "u_useClip"), 0)
            GL.glUniform1i(uniform(prog, "u_useMask"), 0)
            GL.glUniform1f(uniform(prog, "u_maskDefault"), 1f)
            GL.glUniform1f(uniform(prog, "u_maskDensity"), 1f)
            GL.glUniform1i(uniform(prog, "u_useClipMask"), 0)
            GL.glUniform1f(uniform(prog, "u_clipMaskDefault"), 1f)
            GL.glUniform1f(uniform(prog, "u_clipMaskDensity"), 1f)
            bindSampler(0, dstTex)
            bindSampler(1, srcTex)

            val maxErr = LinkedHashMap<String, Float>()
            val failures = ArrayList<String>()
            var failed = 0
            for ((ci, case) in cases.withIndex()) {
                gl.upload(dstTex, gl.fill(case.dst))
                gl.upload(srcTex, gl.fill(case.src))
                GL.glUniform1f(uOpacity, case.opacity)
                for ((mi, mode) in modes.withIndex()) {
                    // Hard mix is a step at cb + cs = 1: half-float inputs land on either side of it.
                    if (mode == BlendMode.HARD_MIX && nearHardMixStep(case.dst, case.src)) continue
                    // The same vectors in sRGB and in linear light (the document toggle, BlendMath gamma path).
                    for (gammaOn in GAMMA_MODES) {
                        val expected = if (gammaOn) {
                            val cpu = case.dst.copyOf()
                            BlendMath.blendPixel(mode, cpu, 0, case.src, 0, case.opacity, gamma = true)
                            cpu
                        } else {
                            case.out[mi]
                        }
                        val label = if (gammaOn) "${mode.id}+gamma" else mode.id
                        GL.glUniform1i(uGamma, if (gammaOn) 1 else 0)
                        GL.glUniform1i(uMode, mode.shaderIndex)
                        gl.draw(prog, fbo)
                        val got = gl.readPixel(fbo, 0, 0)
                        val err = maxDiff(got, expected)
                        maxErr[label] = maxOf(maxErr[label] ?: 0f, err)
                        val tol = tolerance(mode) * if (gammaOn) GAMMA_TOLERANCE_SCALE else 1f
                        if (err > tol) {
                            failed++
                            if (failures.size < MAX_DETAILS) {
                                failures += "  case #$ci mode=$label opacity=${case.opacity} " +
                                    "dst=${case.dst.show()} src=${case.src.show()}: " +
                                    "gpu=${got.show()} cpu=${expected.show()} err=${err.show()} > $tol"
                            }
                        }
                    }
                }
            }
            if (failed > 0) {
                fail(
                    "blend parity failed: $failed of ${cases.size * modes.size} comparisons exceeded tolerance " +
                        "on ${gl.version} (${formatName(format)} targets)\n" +
                        "max |gpu - cpu| per mode:\n${maxErr.entries.joinToString("\n") { "  ${it.key}: ${it.value.show()}" }}\n" +
                        "first failures:\n${failures.joinToString("\n")}"
                )
            }
        } finally {
            gl.close()
        }
    }

    @Test
    fun adjustments() {
        val root = Json.parseToJsonElement(GlHarness.asset("vectors/adjust.json")).jsonObject
        val inputs = root.getValue("inputs").floats()
        val pixels = inputs.size / 4
        val sets = root.getValue("sets").jsonArray.map { el ->
            val o = el.jsonObject
            val params = o.getValue("params").jsonObject
            AdjustSet(
                kind = params["kind"]?.jsonPrimitive?.content ?: "?",
                params = AdjustCodec.decode(params.toString()),
                out = o.getValue("out").floats(),
            )
        }
        check(sets.isNotEmpty() && pixels > 0) { "adjust.json has no vectors" }
        for (s in sets) check(s.out.size == inputs.size) { "adjust.json: out has ${s.out.size} floats, inputs ${inputs.size}" }

        val gl = GlHarness()
        val shader = AdjustShader(gl::link)
        try {
            val format = gl.renderTargetFormat()
            val srcTex = gl.texture(format)
            val outTex = gl.texture(format)
            val fbo = gl.framebuffer(outTex)
            shader.create()
            // Vector pixels are premultiplied (some have alpha < 1): upload them as they are, at (i, 0).
            val pixelData = FloatArray(GlHarness.SIZE * GlHarness.SIZE * 4)
            inputs.copyInto(pixelData, 0, 0, pixels * 4)
            // adjust.json stores the destructive AdjustMath.applyTile result; the shader draws an adjustment
            // layer, whose CPU reference is Compositor.adjustInto (alpha kept for semi-transparent stops).
            fun layerReference(params: AdjustParams): FloatArray {
                val grid = TileGrid(GlHarness.SIZE, GlHarness.SIZE, precise = true)
                val t = grid.getOrCreate(0, 0)
                for (i in 0 until pixels * 4) t[i] = inputs[i]
                Compositor.adjustInto(grid, params, BlendMode.NORMAL, 1f)
                return FloatArray(pixels * 4) { grid.get(0, 0)!![it] }
            }

            val maxErr = LinkedHashMap<String, Float>()
            val failures = ArrayList<String>()
            var failed = 0
            var skipped = 0
            for ((si, set) in sets.withIndex()) {
                val prepared = AdjustMath.prepare(set.params)
                val reference = layerReference(set.params)
                if (prepared.kindIndex < 0) { // UnknownAdjust: the CPU leaves pixels untouched as well
                    skipped++
                    continue
                }
                val lut = prepared.lut
                val lutTex = if (lut != null) shader.createLut(lut) else 0
                gl.upload(srcTex, pixelData)
                gl.beginPass(fbo)
                GL.glUseProgram(shader.program)
                shader.draw(
                    kind = prepared.kindIndex,
                    uniforms = prepared.uniforms,
                    useLut = lut != null,
                    lutTex = lutTex,
                    dst = srcTex,
                    mode = BlendMode.NORMAL.shaderIndex,
                    opacity = 1f,
                    maskTex = 0,
                    maskDefault = 1f,
                    maskDensity = 1f,
                    clipTex = 0,
                    clipMaskTex = 0,
                    clipMaskDefault = 1f,
                    clipMaskDensity = 1f,
                )
                GL.glBindVertexArray(0)
                gl.checkGl("adjust draw #$si")
                val label = "${set.kind}#$si"
                for (i in 0 until pixels) {
                    if (inputs[i * 4 + 3] <= 0f) continue // zero-alpha pixels are no-ops in the CPU reference
                    val got = gl.readPixel(fbo, i, 0)
                    val expected = FloatArray(4) { reference[i * 4 + it] }
                    var err = 0f
                    for (ch in 0 until 4) err = maxOf(err, kotlin.math.abs(got[ch] - expected[ch]))
                    maxErr[label] = maxOf(maxErr[label] ?: 0f, err)
                    if (err > ADJUST_TOLERANCE) {
                        failed++
                        if (failures.size < MAX_DETAILS) {
                            failures += "  $label pixel #$i: gpu=${got.show()} cpu=${expected.show()} " +
                                "err=${err.show()} > $ADJUST_TOLERANCE"
                        }
                    }
                }
                shader.deleteLut(lutTex)
            }
            if (failed > 0) {
                fail(
                    "adjust parity failed: $failed comparisons exceeded tolerance on ${gl.version} " +
                        "(${formatName(format)} targets, $skipped skipped sets)\n" +
                        "max |gpu - cpu| per set:\n${maxErr.entries.joinToString("\n") { "  ${it.key}: ${it.value.show()}" }}\n" +
                        "first failures:\n${failures.joinToString("\n")}"
                )
            }
        } finally {
            gl.close()
        }
    }

    // ------------------------------------------------------------------ vectors

    private fun blendVectors(): Pair<List<BlendMode>, List<BlendCase>> {
        val root = Json.parseToJsonElement(GlHarness.asset("vectors/blend.json")).jsonObject
        val modes = root.getValue("modes").jsonArray.map { BlendMode.fromId(it.jsonPrimitive.content) }
        val cases = root.getValue("cases").jsonArray.map { el ->
            val o = el.jsonObject
            BlendCase(
                dst = o.getValue("dst").floats(),
                src = o.getValue("src").floats(),
                opacity = o.getValue("opacity").jsonPrimitive.float,
                out = o.getValue("out").jsonArray.map { it.floats() },
            )
        }
        return modes to cases
    }

    private fun JsonElement.floats(): FloatArray = jsonArray.map { it.jsonPrimitive.float }.toFloatArray()

    // ---------------------------------------------------------------- helpers

    /** Tolerance of one mode: 2e-3, widened to 1e-2 for the non-separable and discontinuous modes. */
    private fun nearHardMixStep(dst: FloatArray, src: FloatArray): Boolean {
        if (dst[3] <= 0f || src[3] <= 0f) return false
        for (ch in 0 until 3) {
            val cb = (dst[ch] / dst[3]).coerceIn(0f, 1f)
            val cs = (src[ch] / src[3]).coerceIn(0f, 1f)
            if (kotlin.math.abs(cb + cs - 1f) < 2e-3f) return true
        }
        return false
    }

    private fun tolerance(mode: BlendMode): Float = when (mode) {
        BlendMode.HUE, BlendMode.SATURATION, BlendMode.COLOR, BlendMode.LUMINOSITY,
        BlendMode.HARD_MIX, BlendMode.DIVIDE, BlendMode.COLOR_DODGE, BlendMode.COLOR_BURN,
        -> 1e-2f
        else -> 2e-3f
    }

    private fun maxDiff(got: FloatArray, expected: FloatArray): Float {
        var err = 0f
        for (i in 0 until minOf(got.size, expected.size)) err = maxOf(err, kotlin.math.abs(got[i] - expected[i]))
        return err
    }

    private fun uniform(prog: Int, name: String): Int = GL.glGetUniformLocation(prog, name)

    private fun bindSampler(unit: Int, tex: Int) {
        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
    }

    private fun formatName(internalFormat: Int): String =
        if (internalFormat == GL.GL_RGBA32F) "RGBA32F" else "RGBA16F"

    private fun FloatArray.show(): String =
        joinToString(",", "[", "]") { String.format(Locale.US, "%.5f", it) }

    private fun Float.show(): String = String.format(Locale.US, "%.5f", this)

    private companion object {
        const val ADJUST_TOLERANCE = 1e-2f
        const val MAX_DETAILS = 12
        /** Tolerances of the linear-light pass: the encode slope near black amplifies half-float steps. */
        val GAMMA_MODES = booleanArrayOf(false, true)
        const val GAMMA_TOLERANCE_SCALE = 4f
    }
}
