package app.painter.core.stroke

import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Renders dabs into one tile at a time; see [TileScheduler]. */
interface DabKernel {
    /** Reads pixels written by earlier dabs outside its own tile (smudge, filters): one dab per pass. */
    val orderDependent: Boolean get() = false

    /** Whether missing tiles are created (false for erase and alpha lock). */
    val createsTiles: Boolean

    /** Half extent of the dab's bounding box in px. */
    fun extent(dab: Dab): Float = dab.halfExtent()

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
 */
class MaskKernel(
    private val op: MaskOp,
    private val head: HeadImage? = null,
    private val antialias: Boolean = true,
    private val depth: Float = 0f,
    private val alphaScale: Float = 1f,
    private val ceiling: Boolean = true,
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
        val opac = dab.opacity
        val r = dab.r
        val g = dab.g
        val b = dab.b
        val data = tile

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
                    if (m > 0f) blend(data, i, cov * peak, opac, m, r, g, b)
                }
                i += 4
            }
        }
        tile.markDirty()
    }

    private fun blend(d: Tile, i: Int, k: Float, opac: Float, m: Float, r: Float, g: Float, b: Float) {
        if (ceiling) {
            val c = opac * m
            if (c >= 1f) {
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
            plain(d, i, km, r, g, b)
        }
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
