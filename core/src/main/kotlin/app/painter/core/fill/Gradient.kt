package app.painter.core.fill

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.history.touch
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch
import app.painter.core.raster.maskedCommit
import app.painter.core.raster.parallelTiles
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

enum class GradientType { LINEAR, RADIAL, SYMMETRIC, ANGULAR }

/** What happens past the ends: clamp, wrap, or mirror. */
enum class Spread { PAD, REPEAT, REFLECT }

/** A color stop; [color] is straight sRGB with alpha, [pos] 0..1. */
data class GradientStop(val pos: Float, val color: Rgba)

/**
 * Gradient from (x0, y0) to (x1, y1) in document pixels (spec §4.4). [repetitions] (1..100) applies to
 * [GradientType.ANGULAR] only. 2..32 stops; extra stops beyond 32 are dropped after sorting.
 */
data class GradientSpec(
    val type: GradientType,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val stops: List<GradientStop>,
    val spread: Spread = Spread.PAD,
    val reverse: Boolean = false,
    val repetitions: Int = 1,
    val dither: Boolean = true,
)

/** Precomputed evaluator of one [GradientSpec]: sorted premultiplied stops and the axis. */
class GradientEvaluator(val spec: GradientSpec) {
    private val pos: FloatArray
    private val col: FloatArray
    private val dx = spec.x1 - spec.x0
    private val dy = spec.y1 - spec.y0
    private val len2 = dx * dx + dy * dy
    private val baseAngle = atan2(dy, dx)

    init {
        val sorted = spec.stops.sortedBy { it.pos }.take(MAX_STOPS)
        pos = FloatArray(sorted.size) { sorted[it].pos.coerceIn(0f, 1f) }
        col = FloatArray(sorted.size * 4)
        for ((i, s) in sorted.withIndex()) {
            val a = s.color.a.coerceIn(0f, 1f)
            col[i * 4] = s.color.r * a; col[i * 4 + 1] = s.color.g * a
            col[i * 4 + 2] = s.color.b * a; col[i * 4 + 3] = a
        }
    }

    /** Gradient parameter 0..1 at the point ([x], [y]) after type, spread and reverse. */
    fun t(x: Float, y: Float): Float {
        val px = x - spec.x0
        val py = y - spec.y0
        if (len2 < 1e-12f) return if (spec.reverse) 1f else 0f
        val raw = when (spec.type) {
            GradientType.LINEAR -> (px * dx + py * dy) / len2
            GradientType.RADIAL -> sqrt((px * px + py * py) / len2)
            GradientType.SYMMETRIC -> abs(px * dx + py * dy) / len2
            GradientType.ANGULAR -> {
                val a = ((atan2(py, px) - baseAngle) / (2 * PI)).toFloat()
                (a - floor(a)) * spec.repetitions.coerceIn(1, 100)
            }
        }
        val t = when {
            spec.spread == Spread.REFLECT -> 1f - abs(mod2(raw) - 1f)
            spec.spread == Spread.REPEAT || spec.type == GradientType.ANGULAR -> fract(raw)
            else -> raw.coerceIn(0f, 1f)
        }
        return if (spec.reverse) 1f - t else t
    }

    /** Premultiplied color at parameter [t] (no dither) into [out]. */
    fun colorAt(t: Float, out: FloatArray) {
        val n = pos.size
        if (n == 0) { out.fill(0f, 0, 4); return }
        if (t <= pos[0] || n == 1) { col.copyInto(out, 0, 0, 4); return }
        if (t >= pos[n - 1]) { col.copyInto(out, 0, (n - 1) * 4, n * 4); return }
        var hi = 1
        while (pos[hi] < t) hi++
        val lo = hi - 1
        val span = pos[hi] - pos[lo]
        val f = if (span <= 0f) 1f else (t - pos[lo]) / span
        for (c in 0 until 4) out[c] = col[lo * 4 + c] + (col[hi * 4 + c] - col[lo * 4 + c]) * f
    }

    /** Dithered premultiplied color of pixel ([x], [y]), sampled at its center. */
    fun eval(x: Int, y: Int, out: FloatArray) {
        colorAt(t(x + 0.5f, y + 0.5f), out)
        if (!spec.dither) return
        val a = (out[3] + Gradient.noise(x, y, 3)).coerceIn(0f, 1f)
        out[3] = a
        for (c in 0 until 3) out[c] = (out[c] + Gradient.noise(x, y, c)).coerceIn(0f, a)
    }

    private fun fract(v: Float) = v - floor(v)
    private fun mod2(v: Float) = v - 2f * floor(v / 2f)

    companion object {
        const val MAX_STOPS = 32
    }
}

/** Gradient math and application (spec §4.4). */
object Gradient {
    fun t(g: GradientSpec, x: Float, y: Float): Float = GradientEvaluator(g).t(x, y)

    /** Dithered premultiplied color of pixel ([x], [y]). Prefer [GradientEvaluator] in loops. */
    fun eval(g: GradientSpec, x: Int, y: Int, out: FloatArray) = GradientEvaluator(g).eval(x, y, out)

    /**
     * Draws [g] on [layerId] with normal OVER at [opacity], limited by the selection and by [region] when
     * given. Respects alpha lock. Returns the patch already applied, or null when nothing changed.
     */
    fun apply(doc: Document, layerId: String, g: GradientSpec, opacity: Float, region: MaskGrid?): TilePatch? {
        val layer = doc.findNode(layerId) as? RasterLayer ?: return null
        if (layer.locked || opacity <= 0f) return null
        val sel = doc.selection.coverageOrNull()
        val mask = when {
            region == null -> sel
            else -> MaskMath.multiply(region, sel)
        }
        val keys = mask?.keys()?.filter { mask.isInBounds(it.tx, it.ty) }?.sortedBy { it.packed }
            ?: IntRect(0, 0, doc.width, doc.height).tileKeys(doc.width, doc.height)
        val ev = GradientEvaluator(g)
        val o = opacity.coerceIn(0f, 1f)
        val tiles = parallelTiles(keys) { k -> renderTile(ev, layer.pixels.get(k), k, o) }
        val result = HashMap<TileKey, Tile?>()
        for ((i, k) in keys.withIndex()) result[k] = tiles[i]
        val patch = maskedCommit(layer, result, mask, layer.alphaLock) ?: return null
        doc.touch()
        return patch
    }

    private fun renderTile(ev: GradientEvaluator, before: Tile?, k: TileKey, o: Float): Tile {
        val out = Tile()
        val g = FloatArray(4)
        val p = FloatArray(4)
        val bx = k.tx shl Tile.SHIFT
        val by = k.ty shl Tile.SHIFT
        for (y in 0 until Tile.SIZE) for (x in 0 until Tile.SIZE) {
            val i = out.index(x, y)
            ev.eval(bx + x, by + y, g)
            if (before != null) before.read(i, p) else p.fill(0f)
            val inv = 1f - g[3] * o
            out.write(i, g[0] * o + p[0] * inv, g[1] * o + p[1] * inv, g[2] * o + p[2] * inv, g[3] * o + p[3] * inv)
        }
        return out
    }

    /** Triangular dither noise `(h1 + h2 − 1) / 255` for pixel ([x], [y]) and [channel]; deterministic. */
    fun noise(x: Int, y: Int, channel: Int): Float =
        (hash01(x, y, channel * 2) + hash01(x, y, channel * 2 + 1) - 1f) / 255f

    private fun hash01(x: Int, y: Int, k: Int): Float {
        var h = x * -0x7259_4cbd xor y * -0x27e9_c7bf xor k * -0x34e5_4ce1
        h = h xor (h ushr 16)
        h *= -0x7a14_3595
        h = h xor (h ushr 13)
        h *= -0x3d4d_51cb
        h = h xor (h ushr 16)
        return (h ushr 8) / 16777216f
    }
}
