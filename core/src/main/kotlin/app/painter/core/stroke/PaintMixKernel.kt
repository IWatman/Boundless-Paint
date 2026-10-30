package app.painter.core.stroke

import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Paint-with-pickup kernel ([StrokeMode.PAINT] with `mix_in` or `dilution` > 0, spec/tools/m2-brush.md §1.8, S4b):
 * the dab deposits a colour mixed from the brush colour and the colours it picks up. Writes into the layer, so the
 * engine uses it on the direct path only.
 *
 * Per dab the colour `C` under the dab is sampled from the active layer [source] on an n×n grid in the dab frame
 * (n = min(64, ceil d), bilinear, premultiplied) and averaged with the dab mask. The blur does not change that
 * average (`C' = lerp(C, mean(C), blur)`), so only `S = mean(C')` is needed. The stroke keeps one reservoir colour
 * `R` and one paint colour, both premultiplied RGBA: `R = lerp(R, S, 1 − pull)` and
 * `paint = lerp(paint, S, mix_in·m̄)`, where `m̄` is the mean coverage of the dab mask and [Dab.mixIn]/
 * [Dab.dilution] carry the dynamics result of the dab. The dab deposits `lerp(paint, R, dilution)` with the normal
 * coverage `m·flow·opacity·alphaScale`. The paint starts as the brush colour, the reservoir as `S` of the first dab,
 * so `dilution` 1 without `mix_in` deposits picked-up colour only.
 *
 * With [wet] false the layer alpha under the dab is never reduced (`a_out = max(a_dst, over)`), with [wet] true the
 * alpha is composited like the colour. With [alphaLock] the layer alpha is left untouched (the premultiplied colour
 * is clamped to it) and no tiles are created. [orderDependent]: a dab reads pixels written by the previous one, so
 * the scheduler renders it in a pass of its own, and [reset] restarts the reservoir and the paint on a re-render.
 */
class PaintMixKernel(
    private val source: TileGrid,
    private val pull: Float,
    private val wet: Boolean = false,
    private val alphaLock: Boolean = false,
    private val alphaScale: Float = 1f,
) : DabKernel {
    override val orderDependent: Boolean get() = true

    override val createsTiles: Boolean get() = !alphaLock

    /** Bilinear sampling of the layer under the dab, shared with [SmudgeKernel]. */
    private val sampler = DabSampler(source)

    /** Reservoir `R` and running paint colour of the stroke, premultiplied RGBA. */
    private val reservoir = FloatArray(4)
    private val paint = FloatArray(4)

    /** Colour the current dab deposits, premultiplied RGBA, `lerp(paint, R, dilution)`. */
    private val deposit = FloatArray(4)

    /** True until the first dab of the stroke has initialised [reservoir] and [paint]. */
    private var cold = true

    /** Forgets the reservoir and the paint; the next dab starts the stroke again (re-render of the stroke). */
    fun reset() {
        cold = true
    }

    override fun beginDab(dab: Dab) {
        val d = max(dab.d, 1f)
        val n = min(MAX_GRID, max(1, ceil(d).toInt()))
        val mask = DabMask(dab)
        val cs = cos(dab.angle)
        val sn = sin(dab.angle)
        val mean = FloatArray(4)
        val s = FloatArray(4)
        var sum = 0f
        var coverage = 0f
        for (i in 0 until n) {
            val b = ((i + 0.5f) / n - 0.5f) * d * dab.sy
            for (j in 0 until n) {
                val a = ((j + 0.5f) / n - 0.5f) * d * dab.sx
                // The dab-local offset (lx·sx, ly·sy) rotated into document space.
                val dx = a * cs - b * sn
                val dy = a * sn + b * cs
                sampler.sample(dab.x + dx - 0.5f, dab.y + dy - 0.5f, s)
                val m = mask.cov(dx, dy)
                coverage += m
                if (m > 0f) {
                    sum += m
                    mean[0] += s[0] * m; mean[1] += s[1] * m; mean[2] += s[2] * m; mean[3] += s[3] * m
                }
            }
        }
        if (sum > 0f) for (k in 0 until 4) mean[k] /= sum
        if (cold) {
            // S of the first dab is the whole reservoir, so dilution alone starts from the layer colours.
            mean.copyInto(reservoir)
            val a = dab.a
            paint[0] = dab.r * a; paint[1] = dab.g * a; paint[2] = dab.b * a; paint[3] = a
            cold = false
        }
        val keep = 1f - pull.coerceIn(0f, 1f)
        for (k in 0 until 4) reservoir[k] += (mean[k] - reservoir[k]) * keep
        val mix = dab.mixIn.coerceIn(0f, 1f) * (coverage / (n * n))
        if (mix > 0f) for (k in 0 until 4) paint[k] += (mean[k] - paint[k]) * mix
        val dil = dab.dilution.coerceIn(0f, 1f)
        for (k in 0 until 4) deposit[k] = paint[k] + (reservoir[k] - paint[k]) * dil
    }

    override fun renderTile(tile: Tile, tx: Int, ty: Int, canvasW: Int, canvasH: Int, dab: Dab, clip: MaskTile?, clipValue: Float) {
        val flow = dab.flow * dab.opacity * alphaScale
        if (dab.d <= 0f || flow <= 0f) return
        val ext = extent(dab)
        val x0 = max(0, floor(dab.x - ext).toInt())
        val y0 = max(0, floor(dab.y - ext).toInt())
        val x1 = min(canvasW - 1, ceil(dab.x + ext).toInt())
        val y1 = min(canvasH - 1, ceil(dab.y + ext).toInt())
        val baseX = tx shl Tile.SHIFT
        val baseY = ty shl Tile.SHIFT
        val px0 = max(x0, baseX) - baseX
        val px1 = min(x1, baseX + Tile.MASK) - baseX
        val py0 = max(y0, baseY) - baseY
        val py1 = min(y1, baseY + Tile.MASK) - baseY
        if (px0 > px1 || py0 > py1) return

        val mask = DabMask(dab)
        val r = deposit[0]
        val g = deposit[1]
        val b = deposit[2]
        val a = deposit[3]
        for (py in py0..py1) {
            val dy = baseY + py + 0.5f - dab.y
            var i = (py * Tile.SIZE + px0) * 4
            for (px in px0..px1) {
                val dx = baseX + px + 0.5f - dab.x
                val cov = mask.cov(dx, dy)
                if (cov > 0f) {
                    val m = if (clip != null) clip.data[(py shl Tile.SHIFT) + px].toInt().and(0xFF) / 255f else clipValue
                    val k = min(cov * flow * m, 1f)
                    if (k > 0f) {
                        val inv = 1f - k
                        if (alphaLock) {
                            // Alpha lock (§1.8): the alpha is carried over, so the colour stays premultiplied by it.
                            val da = tile[i + 3]
                            tile[i] = (r * k + tile[i] * inv).coerceIn(0f, da)
                            tile[i + 1] = (g * k + tile[i + 1] * inv).coerceIn(0f, da)
                            tile[i + 2] = (b * k + tile[i + 2] * inv).coerceIn(0f, da)
                        } else {
                            tile[i] = r * k + tile[i] * inv
                            tile[i + 1] = g * k + tile[i + 1] * inv
                            tile[i + 2] = b * k + tile[i + 2] * inv
                            // Dry paint (§1.8): a dab never thins the layer out; wet paint composites its alpha.
                            val over = a * k + tile[i + 3] * inv
                            tile[i + 3] = if (wet) over else max(tile[i + 3], over)
                        }
                    }
                }
                i += 4
            }
        }
        tile.markDirty()
    }
}
