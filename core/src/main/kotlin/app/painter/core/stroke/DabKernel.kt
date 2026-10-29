package app.painter.core.stroke

import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Texture period in document pixels at 1× texture scale (spec/tools/m2-brush.md §1.4). */
private const val TEXTURE_PERIOD = 512f

/** Guards against a degenerate texture scale or stretch. */
private const val MIN_TEX_SCALE = 1e-3f

private const val MIN_TEX_STRETCH = 1e-3f

/** Degrees → radians. */
private val DEG = (PI / 180.0).toFloat()

/** Renders dabs into one tile at a time; see [TileScheduler]. */
interface DabKernel {
    /** Reads pixels written by earlier dabs outside its own tile (smudge, filters): one dab per pass. */
    val orderDependent: Boolean get() = false

    /** Whether missing tiles are created (false for erase and alpha lock). */
    val createsTiles: Boolean

    /** Half extent of the dab's bounding box in px. */
    fun extent(dab: Dab): Float = dab.halfExtent()

    /**
     * Called on the calling thread before the first tile of [dab] renders, once per dab; only for
     * [orderDependent] kernels, which use it to set up their per-dab state (samples, pickup buffer).
     */
    fun beginDab(dab: Dab) {}

    /** Called on the calling thread after every tile of [dab] is rendered; only for [orderDependent] kernels. */
    fun endDab(dab: Dab) {}

    /**
     * Renders [dab] into [tile] at tile coords ([tx], [ty]) clipped to the canvas [canvasW]×[canvasH].
     * [clip] is the selection tile (null = uniform [clipValue]).
     */
    fun renderTile(tile: Tile, tx: Int, ty: Int, canvasW: Int, canvasH: Int, dab: Dab, clip: MaskTile?, clipValue: Float)
}

/** Pixel operator of [MaskKernel]. */
enum class MaskOp {
    /** Source-over. */
    OVER,

    /** Source-over on colour; destination alpha preserved. */
    OVER_ALPHA_LOCK,

    /** Destination-out. */
    ERASE,
}

/**
 * Head-mask kernel (spec/tools/m2-brush.md §1.1): coverage of a round analytic head (M1 profile, optionally
 * elliptic and rotated) or a [HeadImage] (rotated, aspect-scaled, mip-mapped, softened by hardness), times
 * `flow · colourAlpha · alphaScale`.
 *
 * With [ceiling] (stroke buffers) the dab opacity and the selection value form a ceiling the pixel alpha
 * approaches: `a' = a + (C − a)·k`, C = opacity·clip, applied only while a < C. Without it (direct to layer)
 * both simply multiply the alpha. C = 1 reduces to plain source-over, bit-identical to M1.
 *
 * With [glaze] > 0 (spec/tools/m2-brush.md §1.7) the dab is composited into the buffer as
 * `a = lerp(over(a_buf, a_dab), max(a_buf, a_dab), glaze)` (colour by the same weights), so a stroke crossing
 * itself does not build up; 0 takes the plain ceiling path, bit-identical to the unglazed kernel.
 *
 * With [tex] and [texImage] (spec/tools/m2-brush.md §1.4, S2) the grain scales the dab alpha and the ceiling
 * per pixel: `f = 1 − dab.texDepth·(1 − t)` with `t` sampled at the UV of [TextureParams.mode], and
 * `C = opacity·clip·cap`, `cap = L + (1 − L)·f` ([TextureParams.lowerLimit] L < 1). The ceiling makes repeated
 * dabs of one stroke saturate at `cap` instead of 1. [TextureMode.WARP_ALONG_STROKE] renders as CANVAS_FIXED
 * for now; softness, gradation and texture jitter arrive with S2b. Without a texture the kernel takes exactly
 * the untextured path (one branch per dab, no per-pixel texture work).
 *
 * With [ignoreOpacity] the dab opacity is left out of the alpha: the adhesion load plane (§1.7) accumulates
 * `m·flow` per pixel no matter how much the opacity cap would limit the buffer alpha itself.
 */
class MaskKernel(
    private val op: MaskOp,
    private val head: HeadImage? = null,
    private val antialias: Boolean = true,
    private val depth: Float = 0f,
    private val alphaScale: Float = 1f,
    private val ceiling: Boolean = true,
    private val glaze: Float = 0f,
    private val tex: TextureParams? = null,
    private val texImage: TextureImage? = null,
    private val brushSize: Float = 0f,
    private val ignoreOpacity: Boolean = false,
) : DabKernel {
    override val createsTiles: Boolean get() = op == MaskOp.OVER

    override fun extent(dab: Dab): Float = dab.halfExtent(square = head != null)

    override fun renderTile(tile: Tile, tx: Int, ty: Int, canvasW: Int, canvasH: Int, dab: Dab, clip: MaskTile?, clipValue: Float) {
        val alpha = dab.flow * dab.a * alphaScale
        if (alpha <= 0f || dab.d <= 0f) return
        val cx = dab.x
        val cy = dab.y
        val diameter = dab.d
        val radius = max(diameter * 0.5f, 0.5f)
        val tiny = diameter < 1f
        val peak = (if (tiny && antialias) alpha * diameter * diameter else alpha).coerceAtMost(1f)
        val ext = extent(dab)

        val x0 = max(0, floor(cx - ext).toInt())
        val y0 = max(0, floor(cy - ext).toInt())
        val x1 = min(canvasW - 1, ceil(cx + ext).toInt())
        val y1 = min(canvasH - 1, ceil(cy + ext).toInt())
        val baseX = tx shl Tile.SHIFT
        val baseY = ty shl Tile.SHIFT
        val px0 = max(x0, baseX) - baseX
        val px1 = min(x1, baseX + Tile.MASK) - baseX
        val py0 = max(y0, baseY) - baseY
        val py1 = min(y1, baseY + Tile.MASK) - baseY
        if (px0 > px1 || py0 > py1) return

        val h = dab.hardness.coerceIn(0f, 1f)
        val rOut = radius + 0.5f
        val rIn = max(0f, min(radius * h, radius - 0.5f))
        val band = rOut - rIn
        val rOut2 = rOut * rOut
        val rIn2 = rIn * rIn
        val rad2 = radius * radius
        val circle = head == null && dab.sx == 1f && dab.sy == 1f
        val cs = cos(dab.angle)
        val sn = sin(dab.angle)
        val isx = 1f / dab.sx
        val isy = 1f / dab.sy
        val pyr = head?.pyramid(h)
        val dEff = max(diameter, 1f)
        val level = pyr?.level(dEff * min(dab.sx, dab.sy)) ?: 0
        val invD = 1f / dEff
        val kDepth = if (depth > 0f) (depth * (1f - dab.pressure)).coerceIn(0f, 0.999f) else 0f
        val opac = if (ignoreOpacity) 1f else dab.opacity
        val r = dab.r
        val g = dab.g
        val b = dab.b
        val data = tile

        // Texture (§1.4): a per-pixel grain factor scales the dab alpha and, with a lower limit, the ceiling.
        // Textured dabs take the loop below; untextured ones fall through to the untouched M1 loop.
        val tp = tex
        val grainImage = texImage
        val tDepth = dab.texDepth.coerceIn(0f, 1f)
        if (tp != null && grainImage != null && tDepth > 0f) {
            var angle = tp.angle * DEG
            var ox = 0f
            var oy = 0f
            when (tp.mode) {
                // TODO(S2b): warp-along-stroke (u = s, v = signed distance to the path) renders as canvas-fixed.
                TextureMode.CANVAS_FIXED, TextureMode.WARP_ALONG_STROKE -> Unit
                TextureMode.ROTATE_WITH_STROKE -> {
                    angle += dab.angle
                    ox = cx
                    oy = cy
                }
                TextureMode.MOVING -> {
                    ox = cx
                    oy = cy
                }
            }
            var sc = tp.scale
            if (tp.scaleWithSize && brushSize > 0f) sc *= dab.d / brushSize
            val period = TEXTURE_PERIOD * max(sc, MIN_TEX_SCALE)
            val inU = 1f / (period * max(tp.stretch, MIN_TEX_STRETCH))
            val inV = 1f / period
            val ca = cos(angle)
            val sa = sin(angle)
            // Affine UV in periods: u = x·tXU + y·tYU + tCU, v = x·tXV + y·tYV + tCV.
            val tXU = ca * inU
            val tYU = sa * inU
            val tCU = -(ox * ca + oy * sa) * inU
            val tXV = -sa * inV
            val tYV = ca * inV
            val tCV = (ox * sa - oy * ca) * inV
            val tLow = tp.lowerLimit.coerceIn(0f, 1f)
            for (py in py0..py1) {
                val dy = baseY + py + 0.5f - cy
                val dy2 = dy * dy
                if (circle && dy2 >= rOut2) continue
                val yc = baseY + py + 0.5f
                val rowU = yc * tYU + tCU
                val rowV = yc * tYV + tCV
                var i = (py * Tile.SIZE + px0) * 4
                for (px in px0..px1) {
                    val dx = baseX + px + 0.5f - cx
                    val cov: Float
                    if (circle) {
                        val d2 = dx * dx + dy2
                        cov = if (!antialias) {
                            if (d2 <= rad2) 1f else 0f
                        } else if (d2 >= rOut2) {
                            0f
                        } else if (d2 <= rIn2) {
                            1f
                        } else {
                            val t = (sqrt(d2) - rIn) / band
                            1f - t * t * (3f - 2f * t)
                        }
                    } else {
                        val lx = (dx * cs + dy * sn) * isx
                        val ly = (-dx * sn + dy * cs) * isy
                        if (pyr == null) {
                            val d2 = lx * lx + ly * ly
                            cov = if (!antialias) {
                                if (d2 <= rad2) 1f else 0f
                            } else if (d2 >= rOut2) {
                                0f
                            } else if (d2 <= rIn2) {
                                1f
                            } else {
                                val t = (sqrt(d2) - rIn) / band
                                1f - t * t * (3f - 2f * t)
                            }
                        } else {
                            var u = 0.5f + lx * invD
                            if (dab.flipX) u = 1f - u
                            var c = pyr.sample(level, u, 0.5f + ly * invD)
                            if (kDepth > 0f) c = ((c - kDepth) / (1f - kDepth)).coerceIn(0f, 1f)
                            cov = if (!antialias) (if (c >= 0.5f) 1f else 0f) else c
                        }
                    }
                    if (cov > 0f) {
                        val m = if (clip != null) clip.data[(py shl Tile.SHIFT) + px].toInt().and(0xFF) / 255f else clipValue
                        if (m > 0f) {
                            val xc = baseX + px + 0.5f
                            val t = grainImage.sample(xc * tXU + rowU, xc * tXV + rowV)
                            val f = 1f - tDepth * (1f - t)
                            blend(data, i, cov * peak, opac, m, r, g, b, if (tLow < 1f) tLow + (1f - tLow) * f else f)
                        }
                    }
                    i += 4
                }
            }
            tile.markDirty()
            return
        }

        for (py in py0..py1) {
            val dy = baseY + py + 0.5f - cy
            val dy2 = dy * dy
            if (circle && dy2 >= rOut2) continue
            var i = (py * Tile.SIZE + px0) * 4
            for (px in px0..px1) {
                val dx = baseX + px + 0.5f - cx
                var cov: Float
                if (circle) {
                    val d2 = dx * dx + dy2
                    cov = if (!antialias) {
                        if (d2 <= rad2) 1f else 0f
                    } else if (d2 >= rOut2) {
                        0f
                    } else if (d2 <= rIn2) {
                        1f
                    } else {
                        val t = (sqrt(d2) - rIn) / band
                        1f - t * t * (3f - 2f * t)
                    }
                } else {
                    val lx = (dx * cs + dy * sn) * isx
                    val ly = (-dx * sn + dy * cs) * isy
                    if (pyr == null) {
                        val d2 = lx * lx + ly * ly
                        cov = if (!antialias) {
                            if (d2 <= rad2) 1f else 0f
                        } else if (d2 >= rOut2) {
                            0f
                        } else if (d2 <= rIn2) {
                            1f
                        } else {
                            val t = (sqrt(d2) - rIn) / band
                            1f - t * t * (3f - 2f * t)
                        }
                    } else {
                        var u = 0.5f + lx * invD
                        if (dab.flipX) u = 1f - u
                        cov = pyr.sample(level, u, 0.5f + ly * invD)
                        if (kDepth > 0f) cov = ((cov - kDepth) / (1f - kDepth)).coerceIn(0f, 1f)
                        if (!antialias) cov = if (cov >= 0.5f) 1f else 0f
                    }
                }
                if (cov > 0f) {
                    val m = if (clip != null) clip.data[(py shl Tile.SHIFT) + px].toInt().and(0xFF) / 255f else clipValue
                    if (m > 0f) blend(data, i, cov * peak, opac, m, r, g, b, 1f)
                }
                i += 4
            }
        }
        tile.markDirty()
    }

    private fun blend(d: Tile, i: Int, k: Float, opac: Float, m: Float, r: Float, g: Float, b: Float, cap: Float) {
        if (ceiling) {
            val c = if (cap >= 1f) opac * m else opac * m * cap
            if (glaze > 0f && op == MaskOp.OVER) {
                glazed(d, i, k, c, r, g, b)
            } else if (c >= 1f) {
                plain(d, i, k, r, g, b)
            } else if (op != MaskOp.OVER) {
                plain(d, i, k * c, r, g, b)
            } else {
                val a = d[i + 3]
                if (a >= c) return
                d[i] = d[i] + (r * c - d[i]) * k
                d[i + 1] = d[i + 1] + (g * c - d[i + 1]) * k
                d[i + 2] = d[i + 2] + (b * c - d[i + 2]) * k
                d[i + 3] = a + (c - a) * k
            }
        } else {
            val km = if (opac >= 1f && m >= 1f) k else k * opac * m
            plain(d, i, if (cap >= 1f) km else km * cap, r, g, b)
        }
    }

    /**
     * Glazed ceiling blend (§1.7): `a = lerp(over(a_buf, a_dab), max(a_buf, a_dab), glaze)`, colour by the same
     * weights. The dab alpha in the pixel is `c·k`; when the dab does not win the max, the buffer colour stays.
     */
    private fun glazed(d: Tile, i: Int, k: Float, c: Float, r: Float, g: Float, b: Float) {
        val a = d[i + 3]
        val aOver: Float
        val rOver: Float
        val gOver: Float
        val bOver: Float
        if (c >= 1f) {
            val inv = 1f - k
            aOver = k + a * inv
            rOver = r * k + d[i] * inv
            gOver = g * k + d[i + 1] * inv
            bOver = b * k + d[i + 2] * inv
        } else {
            aOver = a + (c - a) * k
            rOver = d[i] + (r * c - d[i]) * k
            gOver = d[i + 1] + (g * c - d[i + 1]) * k
            bOver = d[i + 2] + (b * c - d[i + 2]) * k
        }
        val dab = if (c >= 1f) k else c * k
        val aMax = max(a, dab)
        val rMax: Float
        val gMax: Float
        val bMax: Float
        if (aMax > a) {
            rMax = r * aMax
            gMax = g * aMax
            bMax = b * aMax
        } else {
            rMax = d[i]
            gMax = d[i + 1]
            bMax = d[i + 2]
        }
        val w = glaze
        d[i] = rOver + (rMax - rOver) * w
        d[i + 1] = gOver + (gMax - gOver) * w
        d[i + 2] = bOver + (bMax - bOver) * w
        d[i + 3] = aOver + (aMax - aOver) * w
    }

    private fun plain(d: Tile, i: Int, sa: Float, r: Float, g: Float, b: Float) {
        val inv = 1f - sa
        when (op) {
            MaskOp.OVER -> {
                d[i] = r * sa + d[i] * inv
                d[i + 1] = g * sa + d[i + 1] * inv
                d[i + 2] = b * sa + d[i + 2] * inv
                d[i + 3] = sa + d[i + 3] * inv
            }
            MaskOp.OVER_ALPHA_LOCK -> {
                val da = d[i + 3]
                d[i] = r * sa * da + d[i] * inv
                d[i + 1] = g * sa * da + d[i + 1] * inv
                d[i + 2] = b * sa * da + d[i + 2] * inv
            }
            MaskOp.ERASE -> scaleAll(d, i, inv)
        }
    }

    private fun scaleAll(d: Tile, i: Int, k: Float) {
        d[i] *= k; d[i + 1] *= k; d[i + 2] *= k; d[i + 3] *= k
    }
}
