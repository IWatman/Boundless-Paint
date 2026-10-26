package app.painter.core.stroke

import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How a dab is combined with the target pixels (all premultiplied). */
enum class DabOp {
    /** Source-over. */
    OVER,

    /** Source-over on color only; destination alpha is preserved. Missing tiles are skipped. */
    OVER_ALPHA_LOCK,

    /** Destination-out: `dst *= 1 − a`. Missing tiles are skipped. */
    ERASE,
}

/** Notified once per touched tile per dab, before any pixel of it is modified. */
fun interface TileTouchListener {
    /** [tile] is the tile's current state, or null if it does not exist yet. */
    fun beforeModify(key: TileKey, tile: Tile?)
}

/**
 * CPU renderer of round procedural dabs.
 *
 * Profile: coverage is 1 inside `r·hardness` and falls off with smoothstep to 0 at `r`;
 * the edge band is widened to at least ±0.5 px around `r` for analytic anti-aliasing.
 * Dabs smaller than 1 px are drawn at 1 px with alpha scaled by `diameter²`.
 * Only tiles under the dab's bounding box (clipped to the grid) are visited.
 */
class DabRenderer {
    /**
     * Renders one dab into [target].
     *
     * @param r straight red 0..1 (likewise [g], [b]).
     * @param alpha peak dab alpha (flow × color alpha × any other factors).
     */
    fun render(
        target: TileGrid,
        cx: Float,
        cy: Float,
        diameter: Float,
        hardness: Float,
        r: Float,
        g: Float,
        b: Float,
        alpha: Float,
        op: DabOp,
        listener: TileTouchListener? = null,
    ) {
        if (alpha <= 0f || diameter <= 0f) return
        val radius = max(diameter * 0.5f, 0.5f)
        val peak = (if (diameter < 1f) alpha * diameter * diameter else alpha).coerceAtMost(1f)
        val h = hardness.coerceIn(0f, 1f)
        val rOut = radius + 0.5f
        val rIn = max(0f, min(radius * h, radius - 0.5f))
        val band = rOut - rIn
        val rOut2 = rOut * rOut
        val rIn2 = rIn * rIn

        val x0 = max(0, floor(cx - rOut).toInt())
        val y0 = max(0, floor(cy - rOut).toInt())
        val x1 = min(target.width - 1, ceil(cx + rOut).toInt())
        val y1 = min(target.height - 1, ceil(cy + rOut).toInt())
        if (x0 > x1 || y0 > y1) return

        for (ty in (y0 shr Tile.SHIFT)..(y1 shr Tile.SHIFT)) {
            for (tx in (x0 shr Tile.SHIFT)..(x1 shr Tile.SHIFT)) {
                val existing = target.get(tx, ty)
                if (existing == null && op != DabOp.OVER) continue
                listener?.beforeModify(TileKey(tx, ty), existing)
                val tile = existing ?: target.getOrCreate(tx, ty)
                val baseX = tx shl Tile.SHIFT
                val baseY = ty shl Tile.SHIFT
                val px0 = max(x0, baseX) - baseX
                val px1 = min(x1, baseX + Tile.MASK) - baseX
                val py0 = max(y0, baseY) - baseY
                val py1 = min(y1, baseY + Tile.MASK) - baseY
                val data = tile
                for (py in py0..py1) {
                    val dy = baseY + py + 0.5f - cy
                    val dy2 = dy * dy
                    if (dy2 >= rOut2) continue
                    var i = (py * Tile.SIZE + px0) * 4
                    for (px in px0..px1) {
                        val dx = baseX + px + 0.5f - cx
                        val d2 = dx * dx + dy2
                        if (d2 < rOut2) {
                            val cov = if (d2 <= rIn2) {
                                1f
                            } else {
                                val t = (sqrt(d2) - rIn) / band
                                1f - t * t * (3f - 2f * t)
                            }
                            val sa = cov * peak
                            val inv = 1f - sa
                            when (op) {
                                DabOp.OVER -> {
                                    data[i] = r * sa + data[i] * inv
                                    data[i + 1] = g * sa + data[i + 1] * inv
                                    data[i + 2] = b * sa + data[i + 2] * inv
                                    data[i + 3] = sa + data[i + 3] * inv
                                }
                                DabOp.OVER_ALPHA_LOCK -> {
                                    val da = data[i + 3]
                                    data[i] = r * sa * da + data[i] * inv
                                    data[i + 1] = g * sa * da + data[i + 1] * inv
                                    data[i + 2] = b * sa * da + data[i + 2] * inv
                                }
                                DabOp.ERASE -> {
                                    data[i] *= inv
                                    data[i + 1] *= inv
                                    data[i + 2] *= inv
                                    data[i + 3] *= inv
                                }
                            }
                        }
                        i += 4
                    }
                }
                tile.markDirty()
            }
        }
    }
}
