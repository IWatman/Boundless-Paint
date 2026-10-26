package app.painter.core.adjust

import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A correction ready to run: GPU kind index, packed uniforms and LUT. Build once per parameter change
 * ([AdjustMath.prepare]); the renderer uploads [uniforms] as `u_params[16]` and [lut] as `u_lut`.
 */
class PreparedAdjust(val params: AdjustParams, val kindIndex: Int, val uniforms: FloatArray, val lut: FloatArray?) {
    /** True when the correction can never change a pixel (unknown kind). */
    val isNoOp: Boolean get() = kindIndex < 0
}

/**
 * CPU reference of every correction kind (spec/tools/m2-layers-adjust.md L5). Per-pixel formulas read the same
 * packed uniforms the GPU program gets, LUT kinds read [AdjustLut]; GLSL must mirror [applyStraight].
 *
 * Uniform layout (`u_params`, unused slots 0):
 * - hue_saturation: hue/360, saturation/100, lightness/100, colorize 0|1, colorHue/360, colorSat/100;
 * - vibrance: vibrance/100, saturation/100;
 * - temperature_tint: 0.3·temperature, 0.3·tint (Lab b*, a* shifts);
 * - color_balance: shadows cr, mg, yb, midtones cr, mg, yb, highlights cr, mg, yb (each /100), preserveLum 0|1;
 * - invert: 1 for luminance variant (LUT otherwise);
 * - threshold: level/255, smooth/255;
 * - black_white: wR/100, wG/100, wB/100, tint 0|1, tint r, g, b.
 */
object AdjustMath {
    const val UNIFORMS = 16

    private val scratch = ThreadLocal.withInitial { FloatArray(4) }

    /** Prepares [p]; [lut] defaults to [AdjustLut.build]. */
    fun prepare(p: AdjustParams, lut: FloatArray? = AdjustLut.build(p)): PreparedAdjust =
        PreparedAdjust(p, if (p is UnknownAdjust) -1 else AdjustKind.index(p.kind), uniforms(p), lut)

    /** Packed per-pixel parameters of [p] (see the class doc for the layout). */
    fun uniforms(p: AdjustParams): FloatArray {
        val u = FloatArray(UNIFORMS)
        when (p) {
            is HueSaturationParams -> {
                u[0] = p.hue.coerceIn(-180f, 180f) / 360f
                u[1] = p.saturation.coerceIn(-100f, 100f) / 100f
                u[2] = p.lightness.coerceIn(-100f, 100f) / 100f
                u[3] = if (p.colorize) 1f else 0f
                u[4] = p.colorHue.coerceIn(0f, 360f) / 360f
                u[5] = p.colorSat.coerceIn(0f, 100f) / 100f
            }
            is VibranceParams -> {
                u[0] = p.vibrance.coerceIn(-100f, 100f) / 100f
                u[1] = p.saturation.coerceIn(-100f, 100f) / 100f
            }
            is TemperatureTintParams -> {
                u[0] = 0.3f * p.temperature.coerceIn(-100f, 100f)
                u[1] = 0.3f * p.tint.coerceIn(-100f, 100f)
            }
            is ColorBalanceParams -> {
                for ((i, s) in listOf(p.shadows, p.midtones, p.highlights).withIndex()) {
                    u[i * 3] = s.cr.coerceIn(-100f, 100f) / 100f
                    u[i * 3 + 1] = s.mg.coerceIn(-100f, 100f) / 100f
                    u[i * 3 + 2] = s.yb.coerceIn(-100f, 100f) / 100f
                }
                u[9] = if (p.preserveLum) 1f else 0f
            }
            is InvertParams -> u[0] = if (p.variant == InvertParams.LUMINANCE) 1f else 0f
            is ThresholdParams -> {
                u[0] = p.level.coerceIn(0f, 255f) / 255f
                u[1] = p.smooth.coerceIn(0f, 100f) / 255f
            }
            is BlackWhiteParams -> {
                u[0] = p.wR.coerceIn(-200f, 200f) / 100f
                u[1] = p.wG.coerceIn(-200f, 200f) / 100f
                u[2] = p.wB.coerceIn(-200f, 200f) / 100f
                u[3] = if (p.tint) 1f else 0f
                u[4] = p.tintColor.r; u[5] = p.tintColor.g; u[6] = p.tintColor.b
            }
            else -> {}
        }
        return u
    }

    /**
     * Applies [p] in place to premultiplied RGBA pixels [px] (4 floats per pixel, any count). Pixels with zero
     * alpha are left untouched; alpha is kept except for gradient_map (`α·α_stop`) and, with [applyAlpha]
     * (destructive mode only), the curves `a` channel. A null [lut] is built on demand for LUT kinds.
     */
    fun applyTile(p: AdjustParams, lut: FloatArray?, px: FloatArray, applyAlpha: Boolean = false) =
        applyTile(prepare(p, lut ?: AdjustLut.build(p)), px, applyAlpha)

    /** [applyTile] with prepared parameters. */
    fun applyTile(prep: PreparedAdjust, px: FloatArray, applyAlpha: Boolean = false) {
        if (prep.isNoOp) return
        val c = scratch.get()
        val alphaCurve = applyAlpha && prep.params is CurvesParams && prep.lut != null
        var i = 0
        while (i + 3 < px.size) {
            val a = px[i + 3]
            if (a > 0f) {
                val inv = 1f / a
                c[0] = (px[i] * inv).coerceIn(0f, 1f)
                c[1] = (px[i + 1] * inv).coerceIn(0f, 1f)
                c[2] = (px[i + 2] * inv).coerceIn(0f, 1f)
                c[3] = 1f
                applyStraight(prep, c)
                val na = if (alphaCurve) AdjustLut.sample(prep.lut!!, 3, a) else a * c[3]
                px[i] = c[0] * na; px[i + 1] = c[1] * na; px[i + 2] = c[2] * na; px[i + 3] = na
            }
            i += 4
        }
    }

    /** A copy of [tile] with [prep] applied (same storage precision). */
    fun adjustedCopy(prep: PreparedAdjust, tile: Tile, applyAlpha: Boolean = false): Tile {
        val px = tile.toFloats()
        applyTile(prep, px, applyAlpha)
        val out = Tile(tile.precise)
        var i = 0
        while (i < Tile.CHANNELS) { out.write(i, px, i); i += 4 }
        return out
    }

    /**
     * Applies [prep] to one straight color `c[0..2]` (clamped 0..1) in place. `c[3]` receives the source alpha
     * factor of the result: the stop alpha for gradient_map, 1 otherwise.
     */
    fun applyStraight(prep: PreparedAdjust, c: FloatArray) {
        val u = prep.uniforms
        val lut = prep.lut
        c[3] = 1f
        when (prep.params) {
            is GradientMapParams -> {
                val t = lum(c[0], c[1], c[2])
                c[0] = AdjustLut.sample(lut!!, 0, t); c[1] = AdjustLut.sample(lut, 1, t)
                c[2] = AdjustLut.sample(lut, 2, t); c[3] = AdjustLut.sample(lut, 3, t)
            }
            is HueSaturationParams -> hueSaturation(u, c)
            is VibranceParams -> vibrance(u, c)
            is TemperatureTintParams -> temperatureTint(u, c)
            is ColorBalanceParams -> colorBalance(u, c)
            is ThresholdParams -> {
                val g = lum(c[0], c[1], c[2])
                val w = u[1]
                val v = if (w > 0f) smoothstep(u[0] - w / 2f, u[0] + w / 2f, g) else if (g >= u[0]) 1f else 0f
                c[0] = v; c[1] = v; c[2] = v
            }
            is BlackWhiteParams -> {
                val g = (u[0] * c[0] + u[1] * c[1] + u[2] * c[2]).coerceIn(0f, 1f)
                if (u[3] > 0f) { c[0] = g * u[4]; c[1] = g * u[5]; c[2] = g * u[6] } else { c[0] = g; c[1] = g; c[2] = g }
            }
            is InvertParams -> if (lut == null) invertLuminance(c) else perChannel(lut, c)
            is UnknownAdjust -> {}
            else -> if (lut != null) perChannel(lut, c)
        }
    }

    /**
     * Auto levels from per-channel histograms ([hist] = 3 arrays of 256 counts for R, G, B): clips [clip] of the
     * pixels at each end of every channel.
     */
    fun autoLevels(hist: Array<IntArray>, clip: Float = 0.001f): LevelsParams {
        fun channel(h: IntArray): LevelsChannel {
            val total = h.sum().toLong()
            if (total == 0L) return LevelsChannel()
            val cut = (total * clip).toLong()
            var lo = 0
            var acc = 0L
            while (lo < 255 && acc + h[lo] <= cut) { acc += h[lo]; lo++ }
            var hi = 255
            acc = 0L
            while (hi > 0 && acc + h[hi] <= cut) { acc += h[hi]; hi-- }
            if (hi - lo < 2) return LevelsChannel()
            return LevelsChannel(inBlack = lo.toFloat().coerceAtMost(253f), inWhite = hi.toFloat())
        }
        return LevelsParams(r = channel(hist[0]), g = channel(hist[1]), b = channel(hist[2]))
    }

    /** Histograms (R, G, B; 256 bins of straight 8-bit values) of the non-transparent pixels of [grid]. */
    fun histogram(grid: TileGrid): Array<IntArray> {
        val h = Array(3) { IntArray(256) }
        val p = FloatArray(4)
        for ((key, t) in grid.entries()) {
            val bx = key.tx shl Tile.SHIFT
            val by = key.ty shl Tile.SHIFT
            for (y in 0 until Tile.SIZE) {
                if (by + y >= grid.height) break
                for (x in 0 until Tile.SIZE) {
                    if (bx + x >= grid.width) break
                    t.read(t.index(x, y), p)
                    if (p[3] <= 0f) continue
                    for (ch in 0..2) h[ch][((p[ch] / p[3]).coerceIn(0f, 1f) * 255f + 0.5f).toInt()]++
                }
            }
        }
        return h
    }

    /** Luminance 0.3R + 0.59G + 0.11B (same weights as the blend modes). */
    fun lum(r: Float, g: Float, b: Float): Float = 0.3f * r + 0.59f * g + 0.11f * b

    private fun perChannel(lut: FloatArray, c: FloatArray) {
        c[0] = AdjustLut.sample(lut, 0, c[0])
        c[1] = AdjustLut.sample(lut, 1, c[1])
        c[2] = AdjustLut.sample(lut, 2, c[2])
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun hueSaturation(u: FloatArray, c: FloatArray) {
        if (u[0] == 0f && u[1] == 0f && u[2] == 0f && u[3] == 0f) return
        rgbToHsl(c)
        if (u[3] > 0f) {
            c[0] = u[4]; c[1] = u[5]
        } else {
            c[0] = fract(c[0] + u[0])
            c[1] = (c[1] * (1f + u[1])).coerceIn(0f, 1f)
        }
        val v = u[2]
        c[2] = if (v >= 0f) c[2] + (1f - c[2]) * v else c[2] * (1f + v)
        hslToRgb(c)
    }

    private fun vibrance(u: FloatArray, c: FloatArray) {
        if (u[0] == 0f && u[1] == 0f) return
        val l = lum(c[0], c[1], c[2])
        val s = max(c[0], max(c[1], c[2])) - min(c[0], min(c[1], c[2]))
        val k = (1f + u[0] * (1f - s)) * (1f + u[1])
        for (i in 0..2) c[i] = (l + (c[i] - l) * k).coerceIn(0f, 1f)
    }

    private fun temperatureTint(u: FloatArray, c: FloatArray) {
        if (u[0] == 0f && u[1] == 0f) return
        rgbToLab(c)
        c[1] += u[1]
        c[2] += u[0]
        labToRgb(c)
    }

    private fun colorBalance(u: FloatArray, c: FloatArray) {
        var any = false
        for (i in 0 until 9) if (u[i] != 0f) any = true
        if (!any) return
        val l = lum(c[0], c[1], c[2])
        val sh = 1f - smoothstep(0f, 0.5f, l)
        val mid = 1f - abs(2f * l - 1f)
        val hi = smoothstep(0.5f, 1f, l)
        for (ch in 0..2) c[ch] = (c[ch] + 0.25f * (sh * u[ch] + mid * u[3 + ch] + hi * u[6 + ch])).coerceIn(0f, 1f)
        if (u[9] > 0f) setLum(c, l)
    }

    private fun invertLuminance(c: FloatArray) {
        rgbToHsl(c)
        c[2] = 1f - c[2]
        hslToRgb(c)
    }

    /** Shifts [c] to luminance [l], pulling out-of-range channels toward gray (W3C ClipColor). */
    private fun setLum(c: FloatArray, l: Float) {
        val d = l - lum(c[0], c[1], c[2])
        c[0] += d; c[1] += d; c[2] += d
        val cl = lum(c[0], c[1], c[2])
        val n = min(c[0], min(c[1], c[2]))
        val x = max(c[0], max(c[1], c[2]))
        if (n < 0f) {
            val k = cl - n
            for (i in 0..2) c[i] = if (k > 1e-6f) cl + (c[i] - cl) * cl / k else cl
        }
        if (x > 1f) {
            val k = x - cl
            for (i in 0..2) c[i] = if (k > 1e-6f) cl + (c[i] - cl) * (1f - cl) / k else cl
        }
        for (i in 0..2) c[i] = c[i].coerceIn(0f, 1f)
    }

    private fun fract(v: Float): Float = v - floor(v)

    /** In place: (r, g, b) -> (h 0..1, s, l). */
    private fun rgbToHsl(c: FloatArray) {
        val r = c[0]; val g = c[1]; val b = c[2]
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d <= 0f) { c[0] = 0f; c[1] = 0f; c[2] = l; return }
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (mx) {
            r -> ((g - b) / d).let { if (it < 0f) it + 6f else it }
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        c[0] = fract(h); c[1] = s.coerceIn(0f, 1f); c[2] = l
    }

    /** In place: (h 0..1, s, l) -> (r, g, b). */
    private fun hslToRgb(c: FloatArray) {
        val h = c[0]; val s = c[1]; val l = c[2].coerceIn(0f, 1f)
        val ch = (1f - abs(2f * l - 1f)) * s
        val h6 = h * 6f
        val x = ch * (1f - abs(h6 % 2f - 1f))
        val m = l - ch / 2f
        var r = 0f; var g = 0f; var b = 0f
        when (min(h6.toInt(), 5)) {
            0 -> { r = ch; g = x }
            1 -> { r = x; g = ch }
            2 -> { g = ch; b = x }
            3 -> { g = x; b = ch }
            4 -> { r = x; b = ch }
            else -> { r = ch; b = x }
        }
        c[0] = (r + m).coerceIn(0f, 1f); c[1] = (g + m).coerceIn(0f, 1f); c[2] = (b + m).coerceIn(0f, 1f)
    }

    private fun toLinear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    private fun toSrgb(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return (if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f).coerceIn(0f, 1f)
    }

    private fun labF(t: Float): Float = if (t > 216f / 24389f) cbrt(t) else (24389f / 27f * t + 16f) / 116f

    private fun labFInv(f: Float): Float { val f3 = f * f * f; return if (f3 > 216f / 24389f) f3 else (116f * f - 16f) / (24389f / 27f) }

    /** In place: sRGB -> CIE Lab (D65), L 0..100. */
    private fun rgbToLab(c: FloatArray) {
        val r = toLinear(c[0]); val g = toLinear(c[1]); val b = toLinear(c[2])
        val fx = labF((0.4124564f * r + 0.3575761f * g + 0.1804375f * b) / 0.95047f)
        val fy = labF(0.2126729f * r + 0.7151522f * g + 0.0721750f * b)
        val fz = labF((0.0193339f * r + 0.1191920f * g + 0.9503041f * b) / 1.08883f)
        c[0] = 116f * fy - 16f; c[1] = 500f * (fx - fy); c[2] = 200f * (fy - fz)
    }

    /** In place: CIE Lab (D65) -> sRGB, clamped. */
    private fun labToRgb(c: FloatArray) {
        val fy = (c[0] + 16f) / 116f
        val x = labFInv(fy + c[1] / 500f) * 0.95047f
        val y = labFInv(fy)
        val z = labFInv(fy - c[2] / 200f) * 1.08883f
        c[0] = toSrgb(3.2404542f * x - 1.5371385f * y - 0.4985314f * z)
        c[1] = toSrgb(-0.9692660f * x + 1.8760108f * y + 0.0415560f * z)
        c[2] = toSrgb(0.0556434f * x - 0.2040259f * y + 1.0572252f * z)
    }
}
