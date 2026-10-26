package app.painter.render

/** GLSL ES 3.00 sources of the canvas renderer. Blend formulas follow spec/blend-and-color.md. */
internal object Shaders {
    /** Internal pseudo-mode: result = mix(dst, src, opacity) (passthrough groups with opacity). */
    const val MODE_LERP = 100

    const val PREVIEW_NONE = 0
    const val PREVIEW_PAINT = 1
    const val PREVIEW_ERASE = 2

    const val QUAD_VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
void main() { gl_Position = vec4(a_pos * 2.0 - 1.0, 0.0, 1.0); }
"""

    const val BLEND_FS = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D u_dst;
uniform sampler2D u_src;
uniform sampler2D u_buf;
uniform sampler2D u_clip;
uniform sampler2D u_mask;
uniform sampler2D u_clipMask;
uniform int u_mode;
uniform float u_opacity;
uniform int u_preview;
uniform float u_previewOpacity;
uniform bool u_alphaLock;
uniform bool u_useClip;
uniform int u_useMask;
uniform float u_maskDefault;
uniform float u_maskDensity;
uniform int u_useClipMask;
uniform float u_clipMaskDefault;
uniform float u_clipMaskDensity;
out vec4 o;

/** Effective mask factor: 1 - density * (1 - m). */
float maskEff(float m, float density) { return 1.0 - density * (1.0 - m); }

float lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
vec3 clipColor(vec3 c) {
    float l = lum(c);
    float n = min(min(c.r, c.g), c.b);
    float x = max(max(c.r, c.g), c.b);
    if (n < 0.0) c = l + (c - l) * l / max(l - n, 1e-6);
    if (x > 1.0) c = l + (c - l) * (1.0 - l) / max(x - l, 1e-6);
    return c;
}
vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum(c))); }
float sat(vec3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }
vec3 setSat(vec3 c, float s) {
    float mx = max(max(c.r, c.g), c.b);
    float mn = min(min(c.r, c.g), c.b);
    float d = mx - mn;
    if (d <= 1e-6) return vec3(0.0);
    return (c - mn) * s / d;
}

float burn(float b, float s) {
    if (b >= 1.0) return 1.0;
    if (s <= 0.0) return 0.0;
    return 1.0 - min(1.0, (1.0 - b) / s);
}
float dodge(float b, float s) {
    if (b <= 0.0) return 0.0;
    if (s >= 1.0) return 1.0;
    return min(1.0, b / (1.0 - s));
}
float hardLight(float b, float s) {
    return s <= 0.5 ? b * 2.0 * s : b + (2.0 * s - 1.0) - b * (2.0 * s - 1.0);
}
float softLight(float b, float s) {
    if (s <= 0.5) return b - (1.0 - 2.0 * s) * b * (1.0 - b);
    float d = b <= 0.25 ? ((16.0 * b - 12.0) * b + 4.0) * b : sqrt(b);
    return b + (2.0 * s - 1.0) * (d - b);
}
float vivid(float b, float s) { return s <= 0.5 ? burn(b, 2.0 * s) : dodge(b, 2.0 * s - 1.0); }

float sep(int m, float b, float s) {
    if (m == 2) return min(b, s);
    if (m == 3) return b * s;
    if (m == 4) return burn(b, s);
    if (m == 5) return max(0.0, b + s - 1.0);
    if (m == 7) return max(b, s);
    if (m == 8) return b + s - b * s;
    if (m == 9) return dodge(b, s);
    if (m == 10) return min(1.0, b + s);
    if (m == 13) return hardLight(s, b);
    if (m == 14) return softLight(b, s);
    if (m == 15) return hardLight(b, s);
    if (m == 16) return vivid(b, s);
    if (m == 17) return clamp(b + 2.0 * s - 1.0, 0.0, 1.0);
    if (m == 18) return s <= 0.5 ? min(b, 2.0 * s) : max(b, 2.0 * s - 1.0);
    if (m == 19) return vivid(b, s) < 0.5 ? 0.0 : 1.0;
    if (m == 20) return abs(b - s);
    if (m == 21) return b + s - 2.0 * b * s;
    if (m == 22) return 1.0 - abs(1.0 - b - s);
    if (m == 23) return 1.0 - b;
    if (m == 24) return max(0.0, b - s);
    if (m == 25) return s <= 0.0 ? 1.0 : min(1.0, b / s);
    return s;
}

vec3 blendFn(int m, vec3 cb, vec3 cs) {
    if (m == 26) return setLum(setSat(cs, sat(cb)), lum(cb));
    if (m == 27) return setLum(setSat(cb, sat(cs)), lum(cb));
    if (m == 28) return setLum(cs, lum(cb));
    if (m == 29) return setLum(cb, lum(cs));
    if (m == 6) return (cs.r + cs.g + cs.b) < (cb.r + cb.g + cb.b) ? cs : cb;
    if (m == 12) return (cs.r + cs.g + cs.b) > (cb.r + cb.g + cb.b) ? cs : cb;
    return vec3(sep(m, cb.r, cs.r), sep(m, cb.g, cs.g), sep(m, cb.b, cs.b));
}

vec4 composite(int m, vec4 d, vec4 s) {
    float ab = d.a;
    float as = s.a;
    vec3 cb = ab > 0.0 ? d.rgb / ab : vec3(0.0);
    vec3 cs = as > 0.0 ? s.rgb / as : vec3(0.0);
    if (m == 30) return d * (1.0 - as);
    if (m == 31) return d * (lum(cs) * as + (1.0 - as));
    if (m == 32) {
        float mk = lum(cs) * as + (1.0 - as);
        float k = (1.0 - lum(cs)) * as;
        float a = max(ab * mk, k);
        return vec4(cb * (1.0 - k) * a, a);
    }
    float ao = as + ab * (1.0 - as);
    if (m == 11) {
        // BlendMath.ADD: k·Cs + aB·min(1, Cb + Cs·aS)
        float k = as * (1.0 - ab);
        vec3 sc = clamp(cs, 0.0, 1.0);
        return vec4(k * sc + ab * min(vec3(1.0), clamp(cb, 0.0, 1.0) + sc * as), ao);
    }
    if (m <= 1) return vec4(s.rgb + d.rgb * (1.0 - as), ao);
    vec3 b = clamp(blendFn(m, clamp(cb, 0.0, 1.0), clamp(cs, 0.0, 1.0)), 0.0, 1.0);
    return vec4((1.0 - ab) * s.rgb + as * ab * b + (1.0 - as) * d.rgb, ao);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 d = texelFetch(u_dst, p, 0);
    vec4 l = texelFetch(u_src, p, 0);
    float m = u_useMask != 0 ? texelFetch(u_mask, p, 0).r : u_maskDefault;
    float f = u_opacity * maskEff(m, u_maskDensity);
    if (u_useClip) {
        float cm = u_useClipMask != 0 ? texelFetch(u_clipMask, p, 0).r : u_clipMaskDefault;
        f *= texelFetch(u_clip, p, 0).a * maskEff(cm, u_clipMaskDensity);
    }
    if (u_mode == 100) { o = mix(d, l, f); return; }
    if (u_preview != 0) {
        vec4 b = texelFetch(u_buf, p, 0) * u_previewOpacity;
        if (u_preview == 2) l *= 1.0 - b.a;
        else if (u_alphaLock) l = vec4(l.rgb * (1.0 - b.a) + b.rgb * l.a, l.a);
        else l = b + l * (1.0 - b.a);
    }
    o = composite(u_mode, d, l * f);
}
"""

    const val DISPLAY_VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
uniform mat4 u_mvp;
uniform vec2 u_origin;
uniform vec2 u_size;
out vec2 v_uv;
void main() {
    vec2 p = a_pos * u_size;
    v_uv = p / 256.0;
    gl_Position = u_mvp * vec4(u_origin + p, 0.0, 1.0);
}
"""

    const val DISPLAY_FS = """#version 300 es
precision mediump float;
uniform sampler2D u_tex;
in vec2 v_uv;
out vec4 o;
void main() {
    vec4 c = clamp(texture(u_tex, v_uv), 0.0, 1.0);
    float k = mod(floor(gl_FragCoord.x / 12.0) + floor(gl_FragCoord.y / 12.0), 2.0);
    vec3 chk = mix(vec3(1.0), vec3(0.8), k);
    o = vec4(c.rgb + chk * (1.0 - c.a), 1.0);
}
"""
}
