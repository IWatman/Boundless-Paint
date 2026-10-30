package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.raster.MaskGrid
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Harmony (spec/tools/m2-brush.md §1.11): the dabs of the stroke paint nothing of their own, the stroke keeps its
 * points (every point at least [MIN_STEP] px away from the last kept one) in a spatial hash of cell [radius] and
 * connects each new point to the earlier ones by the rule of [HarmonyParams.variant]. The connections are drawn by
 * [LineRenderer] with `alpha ∝ (1 − dist/radius)`, and the stroke's own path (consecutive kept points) is drawn
 * with alpha 1, so a harmony stroke is visible even without dabs.
 *
 * | variant | `R` (× [HarmonyParams.spread]) | links | `α` |
 * |---|---|---|---|
 * | [HarmonyVariant.SKETCHY] | 80 px | probability `0.3 × density`, shortened 20% on both ends | `0.3·(1 − dist/R)` |
 * | [HarmonyVariant.SHADED] | 100 px | all within `R` | `0.08·(1 − dist/R)` |
 * | [HarmonyVariant.WEB] | 60 px | probability `0.5 × density` | `0.2·(1 − dist/R)` |
 *
 * At most [MAX_NEIGHBOURS] nearest points take part, the history holds [HISTORY] points. The randomness comes from
 * [HashRng] seeded by the stroke seed and the index of the new point, so the result does not depend on batching.
 * The head is not used. Other variants draw as SKETCHY (TODO(S7a)); symmetry copies of the stroke share one link
 * graph (TODO(S7a)).
 */
internal class HarmonyEffect(
    private val params: HarmonyParams,
    private val seed: Long,
    private val brushSize: Float,
    width: Int,
    height: Int,
    color: Rgba,
    clip: MaskGrid? = null,
) {
    private val lines = LineRenderer(width, height, color, clip)

    /** Rule of the variant, see the table above. */
    private val rule = ruleOf(params.variant)

    /** Link distance `R = R(variant) × spread`, px. */
    // Brush files may come from outside (import): keep spread finite and bounded.
    private val radius = rule.radius * (if (params.spread.isFinite()) params.spread else 1f).coerceIn(MIN_SPREAD, MAX_SPREAD)

    /** Link probability multiplier (`density`). */
    private val density = params.density.coerceAtLeast(0f)

    /** Kept points as a ring buffer: the id of a point is its index in the point stream, the slot is `id % HISTORY`. */
    private val xs = FloatArray(HISTORY)
    private val ys = FloatArray(HISTORY)

    /** Path length from the stroke start to each kept point, px: tells another pass from the same run of the path. */
    private val arcs = FloatArray(HISTORY)

    /** Spatial hash of cell [radius]: packed cell index → ids of the kept points inside the cell. */
    private val cells = HashMap<Long, ArrayList<Int>>()

    /** Number of kept points, that is the id of the next one. */
    private var count = 0L

    /** Feeds the dabs of [batch] (the path of the stroke) and draws the connections of every new point. */
    fun add(batch: DabBatch, target: TileAccess) {
        for (i in 0 until batch.size) addPoint(batch[i].x, batch[i].y, target)
    }

    /** Drops the points: a re-rendered stroke replays its dabs, so the link graph must be rebuilt from them. */
    fun reset() {
        cells.clear()
        count = 0L
    }

    /** Keeps `(x, y)` unless it is closer than [MIN_STEP] px to the last kept point, then links it and stores it. */
    private fun addPoint(x: Float, y: Float, target: TileAccess) {
        if (count > 0) {
            val last = slotOf(count - 1)
            val dx = x - xs[last]
            val dy = y - ys[last]
            if (dx * dx + dy * dy < MIN_STEP * MIN_STEP) return
            // The stroke's own path: alpha 1, so the line of the stroke is visible without dabs.
            lines.stroke(target, xs[last], ys[last], x, y, brushSize, 1f)
        }
        val arc = if (count > 0) {
            val last = slotOf(count - 1)
            arcs[last] + hypot(x - xs[last], y - ys[last])
        } else {
            0f
        }
        val id = count++
        val slot = slotOf(id)
        xs[slot] = x
        ys[slot] = y
        arcs[slot] = arc
        link(id, x, y, target)
        cells.getOrPut(cellKey(cellOf(x), cellOf(y))) { ArrayList() }.add(id.toInt())
    }

    /** Draws the links of point [id] at `(x, y)` to its nearest kept neighbours (at most [MAX_NEIGHBOURS]). */
    private fun link(id: Long, x: Float, y: Float, target: TileAccess) {
        val r = radius
        val r2 = r * r
        // Ids below [oldest] fell out of the ring buffer; their slots already hold newer points.
        val oldest = id - HISTORY + 1
        val ci = cellOf(x)
        val cj = cellOf(y)
        val cands = ArrayList<Long>()
        for (j in cj - 1..cj + 1) {
            for (i in ci - 1..ci + 1) {
                val list = cells[cellKey(i, j)] ?: continue
                list.removeAll { it < oldest }
                for (p in list) {
                    val s = slotOf(p.toLong())
                    val dx = x - xs[s]
                    val dy = y - ys[s]
                    val d2 = dx * dx + dy * dy
                    if (d2 > r2) continue
                    // Only other passes link: a neighbour reached along the path by a much longer way than the
                    // straight line is a return or a loop. Neighbours on the same run would just thicken the path
                    // (and fill loops solid).
                    if (arcs[slotOf(id)] - arcs[s] < sqrt(d2) * SAME_RUN) continue
                    // The bits of a positive float keep their order, so the packed pairs sort by distance.
                    cands.add((d2.toRawBits().toLong() shl 32) or (p.toLong() and 0xFFFFFFFFL))
                }
            }
        }
        if (cands.isEmpty()) return
        cands.sort()
        val chance = rule.chance * density
        if (chance <= 0f) return
        val n = min(cands.size, MAX_NEIGHBOURS)
        for (k in 0 until n) {
            val c = cands[k]
            if (chance < 1f && HashRng.rnd(seed, CH_LINK, id * MAX_NEIGHBOURS + k) >= chance) continue
            val dist = sqrt(Float.fromBits((c ushr 32).toInt()))
            val near = 1f - dist / r
            // Close neighbours link more often than far ones, so the hatching thins out towards the edge.
            if (HashRng.rnd(seed, CH_NEAR, id * MAX_NEIGHBOURS + k) >= near) continue
            val alpha = rule.alpha * near
            if (alpha <= 0f) continue
            val s = slotOf((c and 0xFFFFFFFFL))
            val xj = xs[s]
            val yj = ys[s]
            // A random shortening per end, so the links do not stop on one straight edge.
            val key = id * MAX_NEIGHBOURS + k
            val f0 = rule.shorten * (0.25f + 1.5f * HashRng.rnd(seed, CH_SHORTEN, key * 2))
            val f1 = rule.shorten * (0.25f + 1.5f * HashRng.rnd(seed, CH_SHORTEN, key * 2 + 1))
            lines.stroke(
                target,
                x + (xj - x) * f0,
                y + (yj - y) * f0,
                x + (xj - x) * (1f - f1),
                y + (yj - y) * (1f - f1),
                brushSize,
                alpha,
            )
        }
    }

    private fun slotOf(id: Long): Int = (id % HISTORY).toInt()

    private fun cellOf(v: Float): Int = floor(v / radius).toInt()

    private fun cellKey(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)

    /** Links of [variant]; the extra variants fall back to SKETCHY (TODO(S7a)). */
    private fun ruleOf(variant: HarmonyVariant): Rule = when (variant) {
        // Tuned on the brush sheet with hand-like strokes: faint links that build up where passes meet.
        HarmonyVariant.SKETCHY -> Rule(60f, 0.3f, 0.07f, 0.2f)
        HarmonyVariant.SHADED -> Rule(45f, 1f, 0.03f, 0f)
        HarmonyVariant.WEB -> Rule(50f, 0.5f, 0.06f, 0f)
        // TODO(S7a): CHROME, FUR, LONG_FUR, RIBBON, CURVY, GRID, CIRCLES and SQUARES link as SKETCHY for now.
        else -> Rule(60f, 0.3f, 0.07f, 0.2f)
    }

    /** One row of the variant table: link distance, probability per neighbour, alpha of the link, end shortening. */
    private class Rule(val radius: Float, val chance: Float, val alpha: Float, val shorten: Float)

    private companion object {
        /** Kept points of one stroke (spec §1.11). */
        const val HISTORY = 4000

        /** Nearest neighbours taken into account per point (spec §1.11). */
        const val MAX_NEIGHBOURS = 64

        /** A point closer than this to the last kept one is dropped, px. */
        const val MIN_STEP = 2f

        /** Smallest `spread`, so that the cell of the hash never collapses. */
        const val MIN_SPREAD = 0.05f
        /** Upper bound of the spread factor; links beyond a few hundred px are meaningless. */
        const val MAX_SPREAD = 8f

        /** Random channel of the links; a private stream of this engine, not an [RngChannel]. */
        const val CH_LINK = 17
        const val CH_NEAR = 20
        const val CH_SHORTEN = 21

        /** A neighbour whose path distance is below this multiple of the straight distance is on the same run. */
        const val SAME_RUN = 1.6f
    }
}
