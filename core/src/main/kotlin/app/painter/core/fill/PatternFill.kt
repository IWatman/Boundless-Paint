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
import app.painter.core.raster.applyTiles
import app.painter.core.raster.maskedPatch
import app.painter.core.raster.parallelTiles
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Built-in procedural pattern of the pattern fill (spec §09 «Pattern fill», §21). */
enum class PatternKind {
    /** Two-tone checkerboard. */
    CHECKS,

    /** Polka dots on a square lattice. */
    DOTS,

    /** Diagonal stripes at 45°. */
    DIAGONAL_STRIPES,

    /** Running-bond brick wall with mortar. */
    BRICK,

    /** Herringbone parquet made of 2:1 bricks in alternating diagonal courses. */
    HERRINGBONE,

    /** Canvas/paper grain: a woven value-noise texture. */
    CANVAS,
}

/**
 * Pattern fill settings: the built-in [kind], the [tileSize] in document pixels (one repeat of the motif)
 * and the [rotationDeg] of the pattern around the document origin. The pattern cell rotates with the
 * pattern, so its repeat lattice stays seamless; there is no axis-aligned tile edge to break it.
 */
data class PatternSpec(
    val kind: PatternKind,
    val tileSize: Float = 64f,
    val rotationDeg: Float = 0f,
) {
    companion object {
        const val MIN_TILE = 4f
        const val MAX_TILE = 2048f
    }
}

/**
 * Evaluates one [PatternSpec] in document space. The generator is deterministic and periodic: adding one
 * tile vector to a sample returns the same value, for every pattern and every rotation.
 */
class PatternSampler(spec: PatternSpec) {
    val kind: PatternKind = spec.kind
    val tileSize: Float = spec.tileSize.coerceIn(PatternSpec.MIN_TILE, PatternSpec.MAX_TILE)
    val rotationDeg: Float = ((spec.rotationDeg % 360f) + 360f) % 360f

    private val cosA: Float
    private val sinA: Float
    private val softness: Float

    init {
        val rad = rotationDeg * PI.toFloat() / 180f
        cosA = cos(rad)
        sinA = sin(rad)
        softness = (1f / tileSize).coerceIn(0.0005f, 0.08f)
    }

    /** Coverage 0..1 at document point ([x], [y]). */
    fun at(x: Float, y: Float): Float {
        val u = (cosA * x + sinA * y) / tileSize
        val v = (-sinA * x + cosA * y) / tileSize
        return PatternGenerator.coverage(kind, u, v, softness)
    }
}

/** Deterministic, seamless coverage masks of the built-in pattern fills. */
object PatternGenerator {
    /**
     * Coverage 0..1 of [kind] at pattern coordinates ([u], [v]); one motif repeats every 1 in both axes.
     * [softness] is the edge width in pattern units (normally one document pixel / tile size).
     */
    fun coverage(kind: PatternKind, u: Float, v: Float, softness: Float = 0.01f): Float {
        val s = softness.coerceIn(0.0005f, 0.1f)
        val value = when (kind) {
            PatternKind.CHECKS -> checks(u, v, s)
            PatternKind.DOTS -> dots(u, v, s)
            PatternKind.DIAGONAL_STRIPES -> stripes(u, v, s)
            PatternKind.BRICK -> brick(u, v, s)
            PatternKind.HERRINGBONE -> herringbone(u, v, s)
            PatternKind.CANVAS -> canvas(u, v)
        }
        return value.coerceIn(0f, 1f)
    }

    /** Anti-aliased checkerboard: two cells per tile, blended around their shared edges. */
    private fun checks(u: Float, v: Float, s: Float): Float {
        val cu = u * 2f
        val cv = v * 2f
        val cellU = floor(cu)
        val cellV = floor(cv)
        val fu = cu - cellU
        val fv = cv - cellV
        val edge = minOf(fu, 1f - fu, fv, 1f - fv) * 0.5f
        val solid = smooth(0f, s, edge)
        val light = ((cellU.toInt() + cellV.toInt()) and 1) == 0
        return if (light) 0.5f + 0.5f * solid else 0.5f - 0.5f * solid
    }

    /** Round dot in the centre of each tile. */
    private fun dots(u: Float, v: Float, s: Float): Float {
        val du = fract(u) - 0.5f
        val dv = fract(v) - 0.5f
        val d = sqrt(du * du + dv * dv)
        return 1f - smooth(0.30f - s, 0.30f + s, d)
    }

    /** Two diagonal stripes per tile, running from the lower-left to the upper-right. */
    private fun stripes(u: Float, v: Float, s: Float): Float {
        val phase = fract((u - v) * 2f)
        val aa = s * 2f
        return 1f - smooth(0.5f - aa, 0.5f + aa, phase)
    }

    /**
     * Running bond: two rows per tile, two 2:1 bricks per row. The odd row is shifted by half a brick and
     * the mortar band wraps with the tile.
     */
    private fun brick(u: Float, v: Float, s: Float): Float {
        val rows = 2f
        val ry = v * rows
        val row = floor(ry)
        val ly = ry - row
        val offset = if ((row.toInt() and 1) == 0) 0f else 0.5f
        val lx = fract(u * 2f + offset)
        return band(lx, s * 2f) * band(ly, s * 2f)
    }

    /**
     * Herringbone: four courses of 2:1 bricks per tile. Neighbouring courses lean in opposite directions,
     * with each course shifted by half a brick.
     */
    private fun herringbone(u: Float, v: Float, s: Float): Float {
        val rows = 4f
        val ry = v * rows
        val row = floor(ry)
        val ly = ry - row
        val direction = if ((row.toInt() and 1) == 0) 1f else -1f
        val stagger = row * 0.5f
        val lx = fract((u + direction * ly * 0.25f) * 2f + stagger)
        val lyFract = ly
        return band(lx, s * 2f) * band(lyFract, s * 4f)
    }

    /** Woven canvas/paper grain from periodic value noise plus a fine weave. */
    private fun canvas(u: Float, v: Float): Float {
        var sum = 0f
        var amplitude = 0.45f
        var total = 0f
        var period = 16
        for (octave in 0 until 4) {
            sum += valueNoise(u, v, period, 23 + octave) * amplitude
            total += amplitude
            amplitude *= 0.55f
            period *= 2
        }
        val noise = sum / total
        val weave = 0.5f + 0.5f * sin(2f * PI.toFloat() * 24f * u) * sin(2f * PI.toFloat() * 24f * v)
        return 0.32f + 0.68f * (0.62f * noise + 0.38f * weave)
    }

    /** Coverage of one cell interior with an anti-aliased border of [soft] on both sides. */
    private fun band(x: Float, soft: Float): Float = smooth(0f, soft, x) * smooth(0f, soft, 1f - x)

    /** Periodic value noise on a [period] × [period] lattice. */
    private fun valueNoise(u: Float, v: Float, period: Int, seed: Int): Float {
        val x = u * period
        val y = v * period
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val x0 = Math.floorMod(ix, period)
        val x1 = Math.floorMod(ix + 1, period)
        val y0 = Math.floorMod(iy, period)
        val y1 = Math.floorMod(iy + 1, period)
        val n00 = hash01(x0, y0, seed)
        val n10 = hash01(x1, y0, seed)
        val n01 = hash01(x0, y1, seed)
        val n11 = hash01(x1, y1, seed)
        return lerp(lerp(n00, n10, sx), lerp(n01, n11, sx), sy)
    }

    private fun hash01(x: Int, y: Int, seed: Int): Float {
        var h = x * -0x61c8_86eb xor y * -0x7a14_3595 xor seed * -0x27e9_c7bf
        h = h xor (h ushr 16)
        h *= -0x7a14_3595
        h = h xor (h ushr 13)
        h *= -0x3d4d_51cb
        h = h xor (h ushr 16)
        return (h ushr 8) / 16777216f
    }

    private fun fract(v: Float): Float = v - floor(v)

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

/**
 * Pattern fill (spec §09): fills the active layer, or the selection when one is in effect, with a
 * procedural pattern mask multiplied by one color. Locked layers are left alone and alpha lock keeps the
 * original alpha, like the bucket fill.
 */
object PatternFill {
    /**
     * Fills [layerId] with [spec] in [color] at [opacity], clipped by the selection. Returns the patch
     * already applied to the layer, or null when nothing changed.
     */
    fun apply(doc: Document, layerId: String, spec: PatternSpec, color: Rgba, opacity: Float): TilePatch? {
        val layer = doc.findNode(layerId) as? RasterLayer ?: return null
        if (layer.locked || opacity <= 0f || color.a <= 0f) return null
        val patch = patch(layer, doc.selection.coverageOrNull(), spec, color, opacity) ?: return null
        applyTiles(layer.pixels, patch.after)
        doc.touch()
        return patch
    }

    /**
     * The patch [apply] would produce, without touching [layer]. [selection] null fills the whole canvas;
     * [layer] may be a private copy when the fill runs on a worker thread.
     */
    fun patch(
        layer: RasterLayer,
        selection: MaskGrid?,
        spec: PatternSpec,
        color: Rgba,
        opacity: Float,
    ): TilePatch? {
        if (layer.locked || opacity <= 0f || color.a <= 0f) return null
        val grid = layer.pixels
        val keys = when {
            selection == null -> IntRect(0, 0, grid.width, grid.height).tileKeys(grid.width, grid.height)
            else -> selection.keys()
                .filter { selection.isInBounds(it.tx, it.ty) && !(selection.get(it)?.isEmpty() ?: true) }
                .sortedBy { it.packed }
        }
        if (keys.isEmpty()) return null
        val sampler = PatternSampler(spec)
        val o = opacity.coerceIn(0f, 1f)
        val tiles = parallelTiles(keys) { k -> renderTile(sampler, grid.get(k), k, color, o) }
        val result = HashMap<TileKey, Tile?>(keys.size)
        for ((i, k) in keys.withIndex()) result[k] = tiles[i]
        return maskedPatch(layer, result, selection, preserveAlpha = layer.alphaLock)
    }

    /** One tile of solid [color] cut by the pattern mask (normal OVER on the pixels below). */
    private fun renderTile(
        sampler: PatternSampler,
        before: Tile?,
        key: TileKey,
        color: Rgba,
        opacity: Float,
    ): Tile {
        val out = Tile()
        val p = FloatArray(4)
        val bx = key.tx shl Tile.SHIFT
        val by = key.ty shl Tile.SHIFT
        for (y in 0 until Tile.SIZE) {
            for (x in 0 until Tile.SIZE) {
                val i = out.index(x, y)
                val coverage = sampler.at(bx + x + 0.5f, by + y + 0.5f)
                val k = (coverage * opacity * color.a).coerceIn(0f, 1f)
                if (before != null) before.read(i, p) else p.fill(0f)
                val inv = 1f - k
                out.write(
                    i,
                    color.r * k + p[0] * inv,
                    color.g * k + p[1] * inv,
                    color.b * k + p[2] * inv,
                    k + p[3] * inv,
                )
            }
        }
        return out
    }
}
