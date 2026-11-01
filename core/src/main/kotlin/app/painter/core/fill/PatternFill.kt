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
import kotlin.math.abs
import kotlin.math.atan2
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

    /** Bold chevron line folded at every half tile. */
    ZIGZAG,

    /** Two antiphase sinusoidal lines per tile. */
    WAVES,

    /** Checkerboard of inset up and down triangles. */
    TRIANGLES,

    /** One five-pointed star per tile. */
    STARS,

    /** Honeycomb of filled hexagonal cells. */
    HEXAGONS,

    /** Wavy fibre lines over a softly modulated board tone. */
    WOOD_GRAIN,
}

/**
 * Pattern fill settings: the built-in [kind], the [tileSize] in document pixels (one repeat of the motif),
 * the [rotationDeg] of the pattern around the document origin and the phase [offsetX]/[offsetY] in document
 * pixels. The pattern cell rotates with the pattern, so its repeat lattice stays seamless; there is no
 * axis-aligned tile edge to break it, and a phase shift does not change the lattice either.
 *
 * [contrast] (0..1) pushes the coverage curve away from 0.5 before the two colours are blended, and
 * [invert] swaps the coverage of the background and the ink. Both stay deterministic and periodic.
 */
data class PatternSpec(
    val kind: PatternKind,
    val tileSize: Float = 64f,
    val rotationDeg: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val contrast: Float = 0f,
    val invert: Boolean = false,
) {
    companion object {
        const val MIN_TILE = 4f
        const val MAX_TILE = 2048f
        const val MAX_OFFSET = 256f
    }
}

/**
 * Evaluates one [PatternSpec] in document space. The generator is deterministic and periodic: adding one
 * tile vector to a sample returns the same value, for every pattern, rotation, phase and contrast.
 */
class PatternSampler(spec: PatternSpec) {
    val kind: PatternKind = spec.kind
    val tileSize: Float = spec.tileSize.coerceIn(PatternSpec.MIN_TILE, PatternSpec.MAX_TILE)
    val rotationDeg: Float = ((spec.rotationDeg % 360f) + 360f) % 360f
    val offsetX: Float = spec.offsetX.coerceIn(-PatternSpec.MAX_OFFSET, PatternSpec.MAX_OFFSET)
    val offsetY: Float = spec.offsetY.coerceIn(-PatternSpec.MAX_OFFSET, PatternSpec.MAX_OFFSET)
    val contrast: Float = spec.contrast.coerceIn(0f, 1f)
    val invert: Boolean = spec.invert

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
        val dx = x - offsetX
        val dy = y - offsetY
        val u = (cosA * dx + sinA * dy) / tileSize
        val v = (-sinA * dx + cosA * dy) / tileSize
        var coverage = PatternGenerator.coverage(kind, u, v, softness)
        if (contrast > 0f) coverage = PatternGenerator.contrastCurve(coverage, contrast)
        return if (invert) 1f - coverage else coverage
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
            PatternKind.ZIGZAG -> zigzag(u, v, s)
            PatternKind.WAVES -> waves(u, v, s)
            PatternKind.TRIANGLES -> triangles(u, v, s)
            PatternKind.STARS -> stars(u, v, s)
            PatternKind.HEXAGONS -> hexagons(u, v, s)
            PatternKind.WOOD_GRAIN -> woodGrain(u, v, s)
        }
        return value.coerceIn(0f, 1f)
    }

    /** Gain of the [PatternSpec.contrast] curve at 1: a coverage of 0.5 ± 0.025 already saturates. */
    const val MAX_CONTRAST_GAIN = 19f

    /**
     * Pushes [coverage] away from 0.5 by [amount] 0..1: identity at 0, a near-binary threshold at 1.
     * The curve is symmetric, so inverting before or after it gives the same result.
     */
    fun contrastCurve(coverage: Float, amount: Float): Float {
        val a = amount.coerceIn(0f, 1f)
        if (a <= 0f) return coverage.coerceIn(0f, 1f)
        val gain = 1f + a * MAX_CONTRAST_GAIN
        return ((coverage - 0.5f) * gain + 0.5f).coerceIn(0f, 1f)
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

    /** Zigzag: one bold chevron line per tile, folded at every half tile. */
    private fun zigzag(u: Float, v: Float, s: Float): Float {
        val amplitude = 0.22f
        val halfWidth = 0.07f
        val t = fract(u)
        val wave = 1f - 4f * abs(t - 0.5f)
        val slope = if (t < 0.5f) amplitude * 4f else -amplitude * 4f
        // Distance to the centre line, measured perpendicular to its local slope.
        val distance = abs(wrapSigned(v - 0.5f - amplitude * wave)) / sqrt(1f + slope * slope)
        return 1f - smooth(halfWidth - s, halfWidth + s, distance)
    }

    /** Waves: two sinusoidal lines per tile, half a tile apart and in antiphase. */
    private fun waves(u: Float, v: Float, s: Float): Float {
        val amplitude = 0.14f
        val halfWidth = 0.055f
        val phase = 2f * PI.toFloat() * u
        val wave = sin(phase)
        val slope = 2f * PI.toFloat() * amplitude * cos(phase)
        val distance = sqrt(1f + slope * slope)
        val first = abs(wrapSigned(v - 0.25f - amplitude * wave)) / distance
        val second = abs(wrapSigned(v - 0.75f + amplitude * wave)) / distance
        return 1f - smooth(halfWidth - s, halfWidth + s, minOf(first, second))
    }

    /** Triangles: an up/down checkerboard of inset triangles, two cells per tile row and column. */
    private fun triangles(u: Float, v: Float, s: Float): Float {
        val cells = 2f
        val x = u * cells
        val y = v * cells
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val up = ((ix + iy) and 1) == 0
        val sd = triangleSdf(x - ix, if (up) y - iy else 1f - (y - iy))
        val aa = s * cells
        return smooth(-aa, aa, -sd)
    }

    /** Signed distance to a triangle inset in the unit cell, with its apex at the top. */
    private fun triangleSdf(x: Float, y: Float): Float {
        val margin = 0.09f
        val halfBase = 0.5f - margin
        val height = 1f - 2f * margin
        val length = sqrt(halfBase * halfBase + height * height)
        val left = (-height * (x - 0.5f) - halfBase * (y - margin)) / length
        val right = (height * (x - 0.5f) - halfBase * (y - margin)) / length
        return maxOf(left, right, y - (1f - margin))
    }

    /** Stars: one five-pointed star per tile, with its upper tip at the top. */
    private fun stars(u: Float, v: Float, s: Float): Float {
        val outer = 0.42f
        val inner = 0.18f
        val du = fract(u) - 0.5f
        val dv = fract(v) - 0.5f
        val radius = sqrt(du * du + dv * dv)
        if (radius < 1e-6f) return 1f
        val twoPi = 2f * PI.toFloat()
        val sectorWidth = twoPi / 10f
        val angle = atan2(dv, du) + PI.toFloat() / 2f
        val phi = angle - twoPi * floor(angle / twoPi)
        val sector = floor(phi / sectorWidth).toInt()
        val t = phi - sector * sectorWidth
        val start = if ((sector and 1) == 0) outer else inner
        val end = if ((sector and 1) == 0) inner else outer
        // Straight edge between an outer tip and the neighbouring inner valley, in polar form.
        val edge = start * end * sin(sectorWidth) /
            (end * sin(sectorWidth - t) + start * sin(t))
        return 1f - smooth(edge - s, edge + s, radius)
    }

    /** Hexagons: a honeycomb of pointy-top hexagons on a two-by-two offset lattice. */
    private fun hexagons(u: Float, v: Float, s: Float): Float {
        val rows = 2f
        val columns = 2f
        val dx = 1f / columns
        val dy = 1f / rows
        val rowBase = floor(v * rows).toInt()
        var nearest = Float.MAX_VALUE
        var nearestU = 0f
        var nearestV = 0f
        for (row in rowBase - 1..rowBase + 1) {
            val shift = if ((row and 1) == 0) 0f else dx * 0.5f
            val columnBase = floor((u - shift) * columns).toInt()
            val cy = row * dy
            for (column in columnBase - 1..columnBase + 1) {
                val du = u - (column * dx + shift)
                val dv = v - cy
                val d2 = du * du + dv * dv
                if (d2 < nearest) {
                    nearest = d2
                    nearestU = du
                    nearestV = dv
                }
            }
        }
        val halfWidth = 0.23f
        val halfHeight = 0.31f
        val leg = sqrt(halfWidth * halfWidth + halfHeight * halfHeight * 0.25f)
        val side = maxOf(
            nearestU - halfWidth,
            -nearestU - halfWidth,
            (halfHeight * 0.5f * nearestU - halfWidth * (nearestV + halfHeight)) / leg,
            (-halfHeight * 0.5f * nearestU - halfWidth * (nearestV + halfHeight)) / leg,
            (halfHeight * 0.5f * nearestU + halfWidth * (nearestV - halfHeight)) / leg,
            (-halfHeight * 0.5f * nearestU + halfWidth * (nearestV - halfHeight)) / leg,
        )
        return smooth(-s, s, -side)
    }

    /** Wood grain: long wavy fibre lines over a softly modulated board tone. */
    private fun woodGrain(u: Float, v: Float, s: Float): Float {
        val warp = 0.65f * valueNoise(u, v, 3, 71) +
            0.35f * valueNoise(u, v, 6, 72) +
            0.15f * valueNoise(u, v, 12, 73)
        val grain = fract(u * 7f + warp)
        val distance = minOf(grain, 1f - grain)
        val line = 1f - smooth(0.11f - s, 0.11f + s, distance)
        val tone = 0.14f + 0.18f * valueNoise(u, v, 2, 74)
        val strength = 0.72f + 0.14f * valueNoise(u, v, 4, 75)
        return tone + strength * line
    }

    /** Coverage of one cell interior with an anti-aliased border of [soft] on both sides. */
    private fun band(x: Float, soft: Float): Float = smooth(0f, soft, x) * smooth(0f, soft, 1f - x)

    /** [v] folded into [-0.5, 0.5]: the signed distance to the nearest repeat line. */
    private fun wrapSigned(v: Float): Float = v - floor(v + 0.5f)

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
 * procedural pattern. The coverage blends [background] (coverage 0) and [ink] (coverage 1) before the
 * result is composited over the layer at [opacity]. Locked layers are left alone and alpha lock keeps
 * the original alpha, like the bucket fill.
 */
object PatternFill {
    /**
     * Fills [layerId] with [spec] between [background] and [ink] at [opacity], clipped by the selection.
     * Returns the patch already applied to the layer, or null when nothing changed.
     */
    fun apply(
        doc: Document,
        layerId: String,
        spec: PatternSpec,
        background: Rgba,
        ink: Rgba,
        opacity: Float,
    ): TilePatch? {
        val layer = doc.findNode(layerId) as? RasterLayer ?: return null
        if (layer.locked || opacity <= 0f || (background.a <= 0f && ink.a <= 0f)) return null
        val patch = patch(layer, doc.selection.coverageOrNull(), spec, background, ink, opacity) ?: return null
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
        background: Rgba,
        ink: Rgba,
        opacity: Float,
    ): TilePatch? {
        if (layer.locked || opacity <= 0f || (background.a <= 0f && ink.a <= 0f)) return null
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
        val tiles = parallelTiles(keys) { k -> renderTile(sampler, grid.get(k), k, background, ink, o) }
        val result = HashMap<TileKey, Tile?>(keys.size)
        for ((i, k) in keys.withIndex()) result[k] = tiles[i]
        return maskedPatch(layer, result, selection, preserveAlpha = layer.alphaLock)
    }

    /**
     * One tile cut by the pattern, blending the two colours with straight alpha and compositing the result
     * (multiplied by [opacity]) over the premultiplied pixels below.
     */
    private fun renderTile(
        sampler: PatternSampler,
        before: Tile?,
        key: TileKey,
        background: Rgba,
        ink: Rgba,
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
                val invCoverage = 1f - coverage
                // Premultiplied colour of the two straight-alpha pattern colours at this coverage.
                val srcA = ((background.a * invCoverage + ink.a * coverage) * opacity).coerceIn(0f, 1f)
                if (before != null) before.read(i, p) else p.fill(0f)
                val inv = 1f - srcA
                if (srcA <= 0f) {
                    out.write(i, p[0], p[1], p[2], p[3])
                } else {
                    out.write(
                        i,
                        (background.r * background.a * invCoverage + ink.r * ink.a * coverage) * opacity + p[0] * inv,
                        (background.g * background.a * invCoverage + ink.g * ink.a * coverage) * opacity + p[1] * inv,
                        (background.b * background.a * invCoverage + ink.b * ink.a * coverage) * opacity + p[2] * inv,
                        srcA + p[3] * inv,
                    )
                }
            }
        }
        return out
    }
}
