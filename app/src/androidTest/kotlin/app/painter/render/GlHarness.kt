package app.painter.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30 as GL
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Headless EGL/GLES3 harness of the renderer parity tests: an offscreen ES3 context on a 1x1 pbuffer,
 * the [Shaders.QUAD_VS] full-viewport quad, float textures/FBOs of [SIZE]x[SIZE] and a single-pixel
 * readback. Create and use it on one thread only; [close] releases the context.
 */
internal class GlHarness : AutoCloseable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val one = IntArray(1)
    private var quadVao = 0
    private var quadVbo = 0
    private val pixelBuf: FloatBuffer =
        ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val texBuf: FloatBuffer =
        ByteBuffer.allocateDirect(SIZE * SIZE * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Full-viewport quad VAO (input 0 of [Shaders.QUAD_VS]); bound by [beginPass]. */
    val quad: Int get() = quadVao

    /** GL version string, for assertion messages. */
    val version: String get() = GL.glGetString(GL.GL_VERSION) ?: "?"

    init {
        createContext()
        createQuad()
    }

    // ------------------------------------------------------------------ EGL

    private fun createContext() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed: 0x${hex(EGL14.eglGetError())}" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) {
            "eglInitialize failed: 0x${hex(EGL14.eglGetError())}"
        }
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, num, 0) && num[0] > 0 &&
                configs[0] != null
        ) { "no EGL config with EGL_OPENGL_ES3_BIT + pbuffer: 0x${hex(EGL14.eglGetError())}" }
        val config = configs[0]!!
        surface = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed: 0x${hex(EGL14.eglGetError())}" }
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext(ES3) failed: 0x${hex(EGL14.eglGetError())}" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "eglMakeCurrent failed: 0x${hex(EGL14.eglGetError())}"
        }
    }

    private fun createQuad() {
        GL.glGenVertexArrays(1, one, 0)
        quadVao = one[0]
        GL.glGenBuffers(1, one, 0)
        quadVbo = one[0]
        // Same corner quad the renderer uploads: a_pos in 0..1, mapped by QUAD_VS to NDC.
        val quadData = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quadData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(quadData).position(0)
        GL.glBindVertexArray(quadVao)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, quadVbo)
        GL.glBufferData(GL.GL_ARRAY_BUFFER, quadData.size * 4, buf, GL.GL_STATIC_DRAW)
        GL.glEnableVertexAttribArray(0)
        GL.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 8, 0)
        GL.glBindVertexArray(0)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
        checkGl("quad setup")
    }

    // ------------------------------------------------------ programs/textures

    /** Compiles and links [vs] + [fs]; throws with the info logs when the driver rejects them. */
    fun link(vs: String, fs: String): Int {
        val v = compile(GL.GL_VERTEX_SHADER, vs)
        val f = compile(GL.GL_FRAGMENT_SHADER, fs)
        val prog = GL.glCreateProgram()
        GL.glAttachShader(prog, v)
        GL.glAttachShader(prog, f)
        GL.glLinkProgram(prog)
        val ok = IntArray(1)
        GL.glGetProgramiv(prog, GL.GL_LINK_STATUS, ok, 0)
        val log = GL.glGetProgramInfoLog(prog)
        GL.glDeleteShader(v)
        GL.glDeleteShader(f)
        if (ok[0] == 0) throw AssertionError("program link failed: $log")
        checkGl("link")
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val s = GL.glCreateShader(type)
        GL.glShaderSource(s, src)
        GL.glCompileShader(s)
        val ok = IntArray(1)
        GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw AssertionError("shader compile failed: ${GL.glGetShaderInfoLog(s)}")
        return s
    }

    /** Allocates a [SIZE]x[SIZE] NEAREST/CLAMP_TO_EDGE texture of [internalFormat]; [pixels] is optional. */
    fun texture(internalFormat: Int, pixels: FloatArray? = null): Int {
        GL.glGenTextures(1, one, 0)
        val tex = one[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, internalFormat, SIZE, SIZE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        if (pixels != null) upload(tex, pixels)
        checkGl("texture")
        return tex
    }

    /** Replaces all [SIZE]*[SIZE] RGBA pixels of [tex]. */
    fun upload(tex: Int, pixels: FloatArray) {
        require(pixels.size == SIZE * SIZE * 4) { "expected ${SIZE * SIZE * 4} floats, got ${pixels.size}" }
        texBuf.clear()
        texBuf.put(pixels, 0, pixels.size)
        texBuf.position(0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
        GL.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL.GL_RGBA, GL.GL_FLOAT, texBuf)
        checkGl("upload")
    }

    /** FBO with [colorTex] on GL_COLOR_ATTACHMENT0; [framebufferOrZero] is the non-asserting variant. */
    fun framebuffer(colorTex: Int): Int {
        val fbo = framebufferOrZero(colorTex)
        check(fbo != 0) {
            "FBO incomplete for texture $colorTex: 0x${hex(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER))}"
        }
        return fbo
    }

    private fun framebufferOrZero(colorTex: Int): Int {
        GL.glGenFramebuffers(1, one, 0)
        val fbo = one[0]
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, colorTex, 0)
        val status = GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        if (status != GL.GL_FRAMEBUFFER_COMPLETE) {
            GL.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            return 0
        }
        return fbo
    }

    /**
     * Internal format of the render targets: RGBA16F when the driver can render to it, RGBA32F otherwise.
     * Both keep 16-bit+ precision on the readback path, which the parity tolerances rely on.
     */
    fun renderTargetFormat(): Int {
        for (format in intArrayOf(GL.GL_RGBA16F, GL.GL_RGBA32F)) {
            val tex = texture(format)
            val fbo = framebufferOrZero(tex)
            if (fbo != 0) {
                GL.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                GL.glDeleteTextures(1, intArrayOf(tex), 0)
                return format
            }
        }
        throw AssertionError(
            "no floating point color attachment on this device ($version): RGBA16F and RGBA32F are both " +
                "incomplete, so the parity vectors cannot be compared"
        )
    }

    // ------------------------------------------------------------------ draw

    /** Binds [fbo] as the draw target, sets a [SIZE]x[SIZE] viewport and binds the quad VAO. */
    fun beginPass(fbo: Int) {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
        GL.glViewport(0, 0, SIZE, SIZE)
        GL.glBindVertexArray(quadVao)
    }

    /** One full-viewport quad pass of [prog] into [fbo] (uniforms must be set by the caller). */
    fun draw(prog: Int, fbo: Int) {
        beginPass(fbo)
        GL.glUseProgram(prog)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        GL.glBindVertexArray(0)
        checkGl("draw")
    }

    /** Reads the single pixel ([x], [y]) of [fbo] as RGBA floats. */
    fun readPixel(fbo: Int, x: Int, y: Int): FloatArray {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
        pixelBuf.clear()
        GL.glReadPixels(x, y, 1, 1, GL.GL_RGBA, GL.GL_FLOAT, pixelBuf)
        checkGl("readPixel")
        val out = FloatArray(4)
        pixelBuf.position(0)
        pixelBuf.get(out)
        return out
    }

    /** Fails with the GL error name when one is pending (turn-around check after a pass). */
    fun checkGl(where: String) {
        val err = GL.glGetError()
        if (err != GL.GL_NO_ERROR) throw AssertionError("GL error 0x${hex(err)} at $where ($version)")
    }

    // --------------------------------------------------------------- helpers

    /** A [SIZE]x[SIZE] RGBA buffer whose every pixel is [pixel]. */
    fun fill(pixel: FloatArray): FloatArray {
        val out = FloatArray(SIZE * SIZE * 4)
        var i = 0
        while (i < out.size) {
            out[i] = pixel[0]
            out[i + 1] = pixel[1]
            out[i + 2] = pixel[2]
            out[i + 3] = pixel[3]
            i += 4
        }
        return out
    }

    override fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (quadVbo != 0) GL.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
            if (quadVao != 0) GL.glDeleteVertexArrays(1, intArrayOf(quadVao), 0)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }

    internal companion object {
        /** Side of every parity render target (also the whole layer tile the shaders expect). */
        const val SIZE = 256

        fun hex(v: Int): String = Integer.toHexString(v)

        /** Reads a text asset of the test APK (`vectors/…` from core/src/test/resources). */
        fun asset(name: String): String =
            InstrumentationRegistry.getInstrumentation().context.assets
                .open(name)
                .bufferedReader()
                .use { it.readText() }
    }
}
