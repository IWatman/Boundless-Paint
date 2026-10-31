package app.painter.render

import android.opengl.GLES30 as GL
import app.painter.core.adjust.AdjustLut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GL thread side of one adjustment-layer correction: the fragment program that mirrors
 * `AdjustMath.applyStraight` plus the 1024×1 LUT texture of the node.
 *
 * Program layout: `u_kind` is the `AdjustKind.ALL` index, `u_p[16]` the packed
 * `AdjustMath.uniforms`, `u_lut` the linearly filtered `AdjustLut` table. The pass reads the
 * accumulator tile, fixes it up and composites the result with the same `composite()` the layer
 * program uses, so `NORMAL` is exactly `mix(acc, adj(acc), f)`.
 */
internal class AdjustShader(private val linker: (String, String) -> Int) {
    private var prog = 0
    private var uDst = 0
    private var uMask = 0
    private var uClip = 0
    private var uClipMask = 0
    private var uLut = 0
    private var uKind = 0
    private var uParams = 0
    private var uUseLut = 0
    private var uMode = 0
    private var uOpacity = 0
    private var uUseMask = 0
    private var uMaskDefault = 0
    private var uMaskDensity = 0
    private var uUseClip = 0
    private var uUseClipMask = 0
    private var uClipMaskDefault = 0
    private var uClipMaskDensity = 0
    private var uGamma = 0
    private val one = IntArray(1)
    private var lutBuffer: FloatBuffer? = null

    /** Linked program id (0 before [create]). */
    val program: Int get() = prog

    /** Links the program and resolves its uniforms; call again after every context loss. */
    fun create() {
        prog = linker(Shaders.QUAD_VS, ADJUST_FS)
        uDst = GL.glGetUniformLocation(prog, "u_dst")
        uMask = GL.glGetUniformLocation(prog, "u_mask")
        uClip = GL.glGetUniformLocation(prog, "u_clip")
        uClipMask = GL.glGetUniformLocation(prog, "u_clipMask")
        uLut = GL.glGetUniformLocation(prog, "u_lut")
        uKind = GL.glGetUniformLocation(prog, "u_kind")
        uParams = GL.glGetUniformLocation(prog, "u_p")
        uUseLut = GL.glGetUniformLocation(prog, "u_useLut")
        uMode = GL.glGetUniformLocation(prog, "u_mode")
        uOpacity = GL.glGetUniformLocation(prog, "u_opacity")
        uUseMask = GL.glGetUniformLocation(prog, "u_useMask")
        uMaskDefault = GL.glGetUniformLocation(prog, "u_maskDefault")
        uMaskDensity = GL.glGetUniformLocation(prog, "u_maskDensity")
        uUseClip = GL.glGetUniformLocation(prog, "u_useClip")
        uUseClipMask = GL.glGetUniformLocation(prog, "u_useClipMask")
        uClipMaskDefault = GL.glGetUniformLocation(prog, "u_clipMaskDefault")
        uClipMaskDensity = GL.glGetUniformLocation(prog, "u_clipMaskDensity")
        uGamma = GL.glGetUniformLocation(prog, "u_gamma")
        GL.glUseProgram(prog)
        GL.glUniform1i(uDst, UNIT_DST)
        GL.glUniform1i(uMask, UNIT_MASK)
        GL.glUniform1i(uClip, UNIT_CLIP)
        GL.glUniform1i(uClipMask, UNIT_CLIP_MASK)
        GL.glUniform1i(uLut, UNIT_LUT)
    }

    /**
     * Draws the adjustment into the framebuffer bound by the caller (tile-sized viewport, point quad
     * VAO bound, [program] in use). A 0 texture id means "absent": the matching `u_use*` flag is off
     * and the sampler is not read.
     *
     * @param uniforms packed parameters of `AdjustMath.uniforms`
     * @param lutTex the node's LUT texture, only read when [useLut]
     * @param dst the accumulator tile the correction is applied to
     */
    fun draw(
        kind: Int,
        uniforms: FloatArray,
        useLut: Boolean,
        lutTex: Int,
        dst: Int,
        mode: Int,
        opacity: Float,
        maskTex: Int,
        maskDefault: Float,
        maskDensity: Float,
        clipTex: Int,
        clipMaskTex: Int,
        clipMaskDefault: Float,
        clipMaskDensity: Float,
        /** Blend the correction in linear light (document toggle "gamma-correct"). */
        gamma: Boolean = false,
    ) {
        bind(UNIT_DST, dst)
        bind(UNIT_MASK, maskTex)
        bind(UNIT_CLIP, clipTex)
        bind(UNIT_CLIP_MASK, clipMaskTex)
        bind(UNIT_LUT, lutTex)
        GL.glUniform1i(uKind, kind)
        GL.glUniform1fv(uParams, 16, uniforms, 0)
        GL.glUniform1i(uUseLut, if (useLut) 1 else 0)
        GL.glUniform1i(uMode, mode)
        GL.glUniform1f(uOpacity, opacity)
        GL.glUniform1i(uUseMask, if (maskTex != 0) 1 else 0)
        GL.glUniform1f(uMaskDefault, maskDefault)
        GL.glUniform1f(uMaskDensity, maskDensity)
        GL.glUniform1i(uUseClip, if (clipTex != 0) 1 else 0)
        GL.glUniform1i(uUseClipMask, if (clipMaskTex != 0) 1 else 0)
        GL.glUniform1f(uClipMaskDefault, clipMaskDefault)
        GL.glUniform1f(uClipMaskDensity, clipMaskDensity)
        GL.glUniform1i(uGamma, if (gamma) 1 else 0)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Creates a `SIZE`×1 RGBA16F texture holding [lut] (`SIZE`×4 floats), filtered linearly. */
    fun createLut(lut: FloatArray): Int {
        GL.glGenTextures(1, one, 0)
        val tex = one[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL.GL_RGBA16F, AdjustLut.SIZE, 1)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        writeLut(tex, lut)
        return tex
    }

    /** Replaces every entry of the LUT texture [tex]. */
    fun uploadLut(tex: Int, lut: FloatArray) = writeLut(tex, lut)

    /** Frees the LUT texture [tex] (0 is ignored). */
    fun deleteLut(tex: Int) {
        if (tex == 0) return
        one[0] = tex
        GL.glDeleteTextures(1, one, 0)
    }

    private fun writeLut(tex: Int, lut: FloatArray) {
        val buf = lutBuffer ?: ByteBuffer.allocateDirect(AdjustLut.SIZE * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().also { lutBuffer = it }
        buf.clear()
        buf.put(lut, 0, AdjustLut.SIZE * 4)
        buf.position(0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
        GL.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, AdjustLut.SIZE, 1, GL.GL_RGBA, GL.GL_FLOAT, buf)
    }

    private fun bind(unit: Int, tex: Int) {
        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
    }

    private companion object {
        const val UNIT_DST = 0
        const val UNIT_MASK = 1
        const val UNIT_CLIP = 2
        const val UNIT_CLIP_MASK = 3
        const val UNIT_LUT = 4
    }
}

/**
 * Fragment shader of an adjustment layer: mirrors `AdjustMath.applyStraight` for every `AdjustKind`
 * and composites the result with the shared `composite()` of [Shaders.BLEND_FUNCS].
 */
internal const val ADJUST_FS = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D u_dst;
uniform sampler2D u_mask;
uniform sampler2D u_clip;
uniform sampler2D u_clipMask;
uniform sampler2D u_lut;
uniform int u_kind;
uniform float u_p[16];
uniform bool u_useLut;
uniform int u_mode;
uniform float u_opacity;
uniform int u_useMask;
uniform float u_maskDefault;
uniform float u_maskDensity;
uniform int u_useClip;
uniform int u_useClipMask;
uniform float u_clipMaskDefault;
uniform float u_clipMaskDensity;
uniform int u_gamma;
out vec4 o;
""" + Shaders.BLEND_FUNCS + """
/** AdjustLut.sample: entry i maps i / 1023, linear between entries. */
vec4 lutTexel(float x) { return texture(u_lut, vec2((clamp(x, 0.0, 1.0) * 1023.0 + 0.5) / 1024.0, 0.5)); }
float lutCh(int ch, float x) {
    vec4 v = lutTexel(x);
    if (ch == 0) return v.r;
    if (ch == 1) return v.g;
    return ch == 2 ? v.b : v.a;
}

/** (r, g, b) -> (h 0..1, s, l). */
vec3 rgbToHsl(vec3 c) {
    float mx = max(max(c.r, c.g), c.b);
    float mn = min(min(c.r, c.g), c.b);
    float l = (mx + mn) / 2.0;
    float d = mx - mn;
    if (d <= 0.0) return vec3(0.0, 0.0, l);
    float s = d / (1.0 - abs(2.0 * l - 1.0));
    float h;
    if (mx == c.r) { h = (c.g - c.b) / d; if (h < 0.0) h += 6.0; }
    else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
    else h = (c.r - c.g) / d + 4.0;
    return vec3(fract(h / 6.0), clamp(s, 0.0, 1.0), l);
}

/** (h 0..1, s, l) -> (r, g, b). */
vec3 hslToRgb(vec3 hsl) {
    float h = hsl.x;
    float s = hsl.y;
    float l = clamp(hsl.z, 0.0, 1.0);
    float ch = (1.0 - abs(2.0 * l - 1.0)) * s;
    float h6 = h * 6.0;
    float x = ch * (1.0 - abs(mod(h6, 2.0) - 1.0));
    float m = l - ch / 2.0;
    vec3 rgb = vec3(0.0);
    int i = min(int(h6), 5);
    if (i == 0) rgb = vec3(ch, x, 0.0);
    else if (i == 1) rgb = vec3(x, ch, 0.0);
    else if (i == 2) rgb = vec3(0.0, ch, x);
    else if (i == 3) rgb = vec3(0.0, x, ch);
    else if (i == 4) rgb = vec3(x, 0.0, ch);
    else rgb = vec3(ch, 0.0, x);
    return clamp(rgb + vec3(m), 0.0, 1.0);
}

vec3 hueSaturation(vec3 c) {
    if (u_p[0] == 0.0 && u_p[1] == 0.0 && u_p[2] == 0.0 && u_p[3] == 0.0) return c;
    vec3 hsl = rgbToHsl(c);
    if (u_p[3] > 0.0) {
        hsl.x = u_p[4];
        hsl.y = u_p[5];
    } else {
        hsl.x = fract(hsl.x + u_p[0]);
        hsl.y = clamp(hsl.y * (1.0 + u_p[1]), 0.0, 1.0);
    }
    float v = u_p[2];
    hsl.z = v >= 0.0 ? hsl.z + (1.0 - hsl.z) * v : hsl.z * (1.0 + v);
    return hslToRgb(hsl);
}

vec3 vibrance(vec3 c) {
    if (u_p[0] == 0.0 && u_p[1] == 0.0) return c;
    float l = lum(c);
    float s = max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b);
    float k = (1.0 + u_p[0] * (1.0 - s)) * (1.0 + u_p[1]);
    return clamp(vec3(l) + (c - vec3(l)) * k, 0.0, 1.0);
}

vec3 invertLuminance(vec3 c) {
    vec3 hsl = rgbToHsl(c);
    hsl.z = 1.0 - hsl.z;
    return hslToRgb(hsl);
}

float toLinear(float v) { return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4); }
float toSrgb(float v) {
    float x = clamp(v, 0.0, 1.0);
    return clamp(x <= 0.0031308 ? x * 12.92 : 1.055 * pow(x, 1.0 / 2.4) - 0.055, 0.0, 1.0);
}
float labF(float t) { return t > 216.0 / 24389.0 ? pow(t, 1.0 / 3.0) : (24389.0 / 27.0 * t + 16.0) / 116.0; }
float labFInv(float f) {
    float f3 = f * f * f;
    return f3 > 216.0 / 24389.0 ? f3 : (116.0 * f - 16.0) / (24389.0 / 27.0);
}

/** sRGB -> CIE Lab (D65), L 0..100. */
vec3 rgbToLab(vec3 c) {
    float r = toLinear(c.r);
    float g = toLinear(c.g);
    float b = toLinear(c.b);
    float fx = labF((0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047);
    float fy = labF(0.2126729 * r + 0.7151522 * g + 0.0721750 * b);
    float fz = labF((0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883);
    return vec3(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz));
}

/** CIE Lab (D65) -> sRGB, clamped. */
vec3 labToRgb(vec3 lab) {
    float fy = (lab.x + 16.0) / 116.0;
    float x = labFInv(fy + lab.y / 500.0) * 0.95047;
    float y = labFInv(fy);
    float z = labFInv(fy - lab.z / 200.0) * 1.08883;
    return vec3(
        toSrgb(3.2404542 * x - 1.5371385 * y - 0.4985314 * z),
        toSrgb(-0.9692660 * x + 1.8760108 * y + 0.0415560 * z),
        toSrgb(0.0556434 * x - 0.2040259 * y + 1.0572252 * z));
}

vec3 temperatureTint(vec3 c) {
    if (u_p[0] == 0.0 && u_p[1] == 0.0) return c;
    vec3 lab = rgbToLab(c);
    lab.y += u_p[1];
    lab.z += u_p[0];
    return labToRgb(lab);
}

vec3 colorBalance(vec3 c) {
    bool any = false;
    for (int i = 0; i < 9; i++) { if (u_p[i] != 0.0) any = true; }
    if (!any) return c;
    float l = lum(c);
    // 1 - smoothstep: the shadows range is written in increasing edge order (reversed edges are undefined).
    float sh = 1.0 - smoothstep(0.0, 0.5, l);
    float mid = 1.0 - abs(2.0 * l - 1.0);
    float hi = smoothstep(0.5, 1.0, l);
    vec3 r = vec3(
        clamp(c.r + 0.25 * (sh * u_p[0] + mid * u_p[3] + hi * u_p[6]), 0.0, 1.0),
        clamp(c.g + 0.25 * (sh * u_p[1] + mid * u_p[4] + hi * u_p[7]), 0.0, 1.0),
        clamp(c.b + 0.25 * (sh * u_p[2] + mid * u_p[5] + hi * u_p[8]), 0.0, 1.0));
    return u_p[9] > 0.0 ? setLum(r, l) : r;
}

vec3 thresholdFn(vec3 c) {
    float g = lum(c);
    float w = u_p[1];
    float v = w > 0.0 ? smoothstep(u_p[0] - w / 2.0, u_p[0] + w / 2.0, g) : (g >= u_p[0] ? 1.0 : 0.0);
    return vec3(v);
}

vec3 blackWhite(vec3 c) {
    float g = clamp(u_p[0] * c.r + u_p[1] * c.g + u_p[2] * c.b, 0.0, 1.0);
    return u_p[3] > 0.0 ? g * vec3(u_p[4], u_p[5], u_p[6]) : vec3(g);
}

/** AdjustMath.applyStraight: straight color in, straight color + source alpha factor out. */
vec4 adjustStraight(vec3 c) {
    if (u_kind == 2) return vec4(hueSaturation(c), 1.0);
    if (u_kind == 3) return vec4(vibrance(c), 1.0);
    if (u_kind == 4) return vec4(temperatureTint(c), 1.0);
    if (u_kind == 5) return vec4(colorBalance(c), 1.0);
    if (u_kind == 11) return vec4(thresholdFn(c), 1.0);
    if (u_kind == 12) return vec4(blackWhite(c), 1.0);
    if (u_kind == 8) return u_useLut ? lutTexel(lum(c)) : vec4(c, 1.0);
    if (!u_useLut) return u_kind == 9 ? vec4(invertLuminance(c), 1.0) : vec4(c, 1.0);
    return vec4(lutCh(0, c.r), lutCh(1, c.g), lutCh(2, c.b), 1.0);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 d = texelFetch(u_dst, p, 0);
    if (d.a <= 0.0) { o = d; return; }
    float m = u_useMask != 0 ? texelFetch(u_mask, p, 0).r : u_maskDefault;
    float f = u_opacity * maskEff(m, u_maskDensity);
    if (u_useClip != 0) {
        float cm = u_useClipMask != 0 ? texelFetch(u_clipMask, p, 0).r : u_clipMaskDefault;
        f *= texelFetch(u_clip, p, 0).a * maskEff(cm, u_clipMaskDensity);
    }
    if (f <= 0.0) { o = d; return; }
    vec3 cb = clamp(d.rgb / d.a, 0.0, 1.0);
    vec4 s = adjustStraight(cb);
    o = compositeG(u_mode, vec4(cb, 1.0), vec4(s.rgb * s.a, s.a) * f, u_gamma) * d.a;
}
"""
