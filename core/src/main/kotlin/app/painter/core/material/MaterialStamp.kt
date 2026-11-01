package app.painter.core.material

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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Built-in procedural material of the material library (spec §21). */
enum class MaterialKind {
    /** Soft satin sphere with a broad sheen. */
    BALLOON,

    /** Woven cloth with over/under threads. */
    FABRIC,

    /** Wood grain with wavy fibre bands. */
    WOOD,

    /** Brushed metal plate with a diagonal sheen. */
    METAL,

    /** Speckled stone slab with an irregular outline. */
    STONE,

    /** Cluster of pointed leaves with a midrib. */
    FOLIAGE,

    /** Scatter of four-point stars with a faint glow. */
    SPARKLE,

    /** Fibrous paper sheet with a deckled edge. */
    PAPER,
}

/**
 * One material stamp: the built-in [kind], the [size] of its square footprint in document pixels, its
 * [rotationDeg] around the stamp centre, the [opacity] applied to the whole stamp and the [spacing] of a
 * painted stroke as a fraction of the stamp size. Every value is clamped by [MaterialSampler] or
 * [MaterialStroke], so an out-of-range slider cannot create unbounded work.
 */
data class MaterialSpec(
    val kind: MaterialKind,
    val size: Float = 512f,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1f,
    val spacing: Float = DEFAULT_SPACING,
) {
    companion object {
        const val MIN_SIZE = 32f
        const val MAX_SIZE = 2048f

        /** Default distance between two stamps of a stroke, as a fraction of the stamp size. */
        const val DEFAULT_SPACING = 0.25f
        const val MIN_SPACING = 0.1f
        const val MAX_SPACING = 1f
    }
}

/**
 * Evaluates one [MaterialSpec] in stamp-local document pixels. The stamp is centred on the origin: [sample]
 * takes a point relative to that centre, rotates it into material space and writes two values to its output:
 * coverage 0..1 and a tone multiplier for the current colour. The generator is pure and deterministic: the
 * same point always gives the same coverage and tone.
 */
class MaterialSampler(spec: MaterialSpec) {
    val kind: MaterialKind = spec.kind
    val size: Float = spec.size.coerceIn(MaterialSpec.MIN_SIZE, MaterialSpec.MAX_SIZE).roundToInt().toFloat()
    val rotationDeg: Float = ((spec.rotationDeg % 360f) + 360f) % 360f
    val opacity: Float = spec.opacity.coerceIn(0f, 1f)

    private val half: Float = size * 0.5f
    private val cosA: Float
    private val sinA: Float

    /** Softness of the outline in material units: about three document pixels at every size. */
    private val aa: Float = 1.5f / half

    init {
        val rad = rotationDeg * PI.toFloat() / 180f
        cosA = cos(rad)
        sinA = sin(rad)
    }

    /**
     * Samples the stamp at ([x], [y]) relative to its centre in document pixels. Writes the coverage 0..1 to
     * `out[0]` and the tone multiplier to `out[1]`; the tone is 1 where the stamp is empty.
     */
    fun sample(x: Float, y: Float, out: FloatArray) {
        val u = (cosA * x + sinA * y) / half
        val v = (-sinA * x + cosA * y) / half
        when (kind) {
            MaterialKind.BALLOON -> balloon(u, v, aa, out)
            MaterialKind.FABRIC -> fabric(u, v, aa, out)
            MaterialKind.WOOD -> wood(u, v, aa, out)
            MaterialKind.METAL -> metal(u, v, aa, out)
            MaterialKind.STONE -> stone(u, v, aa, out)
            MaterialKind.FOLIAGE -> foliage(u, v, aa, out)
            MaterialKind.SPARKLE -> sparkle(u, v, out)
            MaterialKind.PAPER -> paper(u, v, aa, out)
        }
        out[0] = out[0].coerceIn(0f, 1f)
        out[1] = out[1].coerceIn(0f, MAX_TONE)
    }

    /** Coverage 0..1 at ([x], [y]) relative to the stamp centre. */
    fun coverageAt(x: Float, y: Float): Float {
        val out = FloatArray(2)
        sample(x, y, out)
        return out[0]
    }

    /** Tone multiplier at ([x], [y]) relative to the stamp centre. */
    fun toneAt(x: Float, y: Float): Float {
        val out = FloatArray(2)
        sample(x, y, out)
        return out[1]
    }

    companion object {
        /** The brightest tone a material may ask for, so highlights stay tame at full opacity. */
        const val MAX_TONE = 1.6f
    }
}

/**
 * Material stamp tool (spec §21): paints one procedural material at the centre of the canvas, or of the
 * selection, with the current colour. The stamp is composited over the active layer through the selection,
 * keeps the alpha of an alpha-locked layer and leaves a locked layer alone, like the pattern fill. The result
 * is one [TilePatch], so the caller records one undo step.
 */
object MaterialStamp {
    /**
     * Stamps [spec] with [color] on [layerId] and returns the applied patch, or null when nothing changed.
     * The stamp is placed at the selection centre when [doc] has a selection, otherwise at the canvas centre.
     */
    fun apply(doc: Document, layerId: String, spec: MaterialSpec, color: Rgba): TilePatch? {
        val layer = doc.findNode(layerId) as? RasterLayer ?: return null
        if (layer.locked || spec.opacity <= 0f || color.a <= 0f) return null
        val patch = patch(layer, doc.selection.coverageOrNull(), spec, color) ?: return null
        applyTiles(layer.pixels, patch.after)
        doc.touch()
        return patch
    }

    /**
     * The patch [apply] would produce, without touching [layer]. [selection] null stamps at the canvas centre;
     * [layer] may be a private copy when the stamp runs on a worker thread.
     */
    fun patch(
        layer: RasterLayer,
        selection: MaskGrid?,
        spec: MaterialSpec,
        color: Rgba,
    ): TilePatch? {
        if (layer.locked || spec.opacity <= 0f || color.a <= 0f) return null
        val grid = layer.pixels
        val bounds = selection?.bounds()
        val centreX = if (bounds != null) (bounds.x0 + bounds.x1) * 0.5f else grid.width * 0.5f
        val centreY = if (bounds != null) (bounds.y0 + bounds.y1) * 0.5f else grid.height * 0.5f
        val side = spec.size.coerceIn(MaterialSpec.MIN_SIZE, MaterialSpec.MAX_SIZE).roundToInt()
        val left = (centreX - side * 0.5f).roundToInt()
        val top = (centreY - side * 0.5f).roundToInt()
        val rect = IntRect(left, top, left + side, top + side)
            .intersect(IntRect(0, 0, grid.width, grid.height)) ?: return null
        val keys = rect.tileKeys(grid.width, grid.height)
        if (keys.isEmpty()) return null
        val sampler = MaterialSampler(spec)
        val alpha = spec.opacity.coerceIn(0f, 1f)
        val tiles = parallelTiles(keys) { key ->
            renderTile(sampler, grid.get(key), key, rect, centreX, centreY, color, alpha)
        }
        val result = HashMap<TileKey, Tile?>(keys.size)
        for ((i, key) in keys.withIndex()) result[key] = tiles[i]
        return maskedPatch(layer, result, selection, preserveAlpha = layer.alphaLock)
    }

    /**
     * One tile with the stamp composited over [before]. Pixels outside the stamp rectangle keep their value;
     * the output is premultiplied like the rest of the raster.
     */
    private fun renderTile(
        sampler: MaterialSampler,
        before: Tile?,
        key: TileKey,
        rect: IntRect,
        centreX: Float,
        centreY: Float,
        color: Rgba,
        opacity: Float,
    ): Tile {
        val out = before?.copy(false) ?: Tile()
        renderStampInto(sampler, out, key, rect, centreX, centreY, color, opacity)
        return out
    }
}

/**
 * Composites one material stamp over [out] inside [rect] and the [key] tile. [out] is both the destination
 * and the shadow of what is painted so far, so several stamps of a stroke can be accumulated in order into
 * one tile. Pixels outside [rect] keep their value.
 */
internal fun renderStampInto(
    sampler: MaterialSampler,
    out: Tile,
    key: TileKey,
    rect: IntRect,
    centreX: Float,
    centreY: Float,
    color: Rgba,
    opacity: Float,
) {
    val bx = key.tx shl Tile.SHIFT
    val by = key.ty shl Tile.SHIFT
    val x0 = max(rect.x0, bx)
    val y0 = max(rect.y0, by)
    val x1 = minOf(rect.x1, bx + Tile.SIZE)
    val y1 = minOf(rect.y1, by + Tile.SIZE)
    val sample = FloatArray(2)
    val pixel = FloatArray(4)
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            sampler.sample(x + 0.5f - centreX, y + 0.5f - centreY, sample)
            val coverage = sample[0] * opacity
            if (coverage <= 0f) continue
            val sourceAlpha = (color.a * coverage).coerceIn(0f, 1f)
            if (sourceAlpha <= 0f) continue
            val tone = sample[1]
            val i = out.index(x - bx, y - by)
            out.read(i, pixel)
            val keep = 1f - sourceAlpha
            out.write(
                i,
                (color.r * tone).coerceIn(0f, 1f) * sourceAlpha + pixel[0] * keep,
                (color.g * tone).coerceIn(0f, 1f) * sourceAlpha + pixel[1] * keep,
                (color.b * tone).coerceIn(0f, 1f) * sourceAlpha + pixel[2] * keep,
                sourceAlpha + pixel[3] * keep,
            )
        }
    }
}

/** Soft satin sphere: a shaded ball with a broad sheen and faint horizontal ribs. */
private fun balloon(u: Float, v: Float, aa: Float, out: FloatArray) {
    val r = sqrt(u * u + v * v)
    val alpha = discAlpha(r, 0.98f, aa)
    if (alpha <= 0f) {
        empty(out)
        return
    }
    val nx = u / 0.98f
    val ny = v / 0.98f
    val nz = sqrt((1f - nx * nx - ny * ny).coerceAtLeast(0f))
    // The light sits above and to the left, slightly in front of the balloon.
    val diffuse = (nx * -0.46f + ny * -0.54f + nz * 0.70f).coerceAtLeast(0f)
    val satin = diffuse.pow(3.5f)
    val sheen = diffuse.pow(14f)
    val band = 0.5f + 0.5f * sin(v * 17f + 0.6f * noise(u * 2.5f, v * 2.5f, 21))
    var tone = 0.30f + 0.80f * diffuse + 0.30f * satin + 0.35f * sheen
    tone += 0.10f * smooth(0.15f, 0.85f, r) * (band - 0.5f)
    tone *= 1f - 0.22f * smooth(0.80f, 1f, r)
    out[0] = alpha
    out[1] = tone
}

/** Woven cloth: warp and weft threads alternate over and under on a checkerboard. */
private fun fabric(u: Float, v: Float, aa: Float, out: FloatArray) {
    val alpha = squareAlpha(u, v, aa)
    if (alpha <= 0f) {
        empty(out)
        return
    }
    val threads = 26f
    val x = (u * 0.5f + 0.5f) * threads
    val y = (v * 0.5f + 0.5f) * threads
    val ix = floor(x).toInt()
    val iy = floor(y).toInt()
    val fx = x - ix
    val fy = y - iy
    val warp = sin(PI.toFloat() * fx)
    val weft = sin(PI.toFloat() * fy)
    val warpOnTop = ((ix + iy) and 1) == 0
    val top = if (warpOnTop) warp else weft
    val under = if (warpOnTop) weft else warp
    val slub = hash01(ix shr 2, iy, 31)
    val fuzz = noise(x * 3.1f, y * 3.1f, 32)
    var tone = 0.42f + 0.44f * top + 0.20f * under + 0.14f * (slub - 0.5f) + 0.10f * (fuzz - 0.5f)
    // The gap where both thread profiles fall to zero reads as a shadow in the weave.
    tone -= 0.12f * (1f - warp) * (1f - weft)
    tone *= 0.94f + 0.06f * noise(u * 1.5f, v * 1.5f, 33)
    out[0] = alpha
    out[1] = tone
}

/** Wood grain: wavy vertical growth bands with long pores and a faint sheen. */
private fun wood(u: Float, v: Float, aa: Float, out: FloatArray) {
    val alpha = squareAlpha(u, v, aa)
    if (alpha <= 0f) {
        empty(out)
        return
    }
    val warp = 0.55f * noise(v * 1.3f, u * 0.35f, 41) + 0.45f * noise(v * 3.1f, u * 1.2f, 42)
    val rings = u * 5.5f + 0.55f * warp
    val phase = fract(rings)
    val latewood = 1f - smooth(0f, 0.16f, abs(phase - 0.72f))
    val fine = 0.5f + 0.5f * sin(fract(rings * 9f) * TAU)
    val pores = smooth(0.74f, 0.90f, noise(u * 55f, v * 6f, 45))
    var tone = 0.54f + 0.24f * noise(u * 2.2f, v * 1.4f, 43) + 0.10f * (fine - 0.5f)
    tone -= 0.30f * latewood
    tone -= 0.12f * pores
    tone *= 0.95f + 0.05f * sin(v * 2.6f + u * 1.1f)
    out[0] = alpha
    out[1] = tone
}

/** Brushed metal: fine horizontal streaks, a diagonal sheen and a thin plate bevel. */
private fun metal(u: Float, v: Float, aa: Float, out: FloatArray) {
    val alpha = squareAlpha(u, v, aa)
    if (alpha <= 0f) {
        empty(out)
        return
    }
    val brush = 0.60f * noise(u * 2.6f, v * 150f, 51) + 0.40f * noise(u * 7.5f, v * 380f, 52)
    val sheen = 0.5f + 0.5f * sin((u * 0.55f + v * 0.85f) * PI.toFloat() + 0.6f)
    val scratch = smooth(0.80f, 0.96f, noise(u * 4.2f, v * 500f, 53))
    var tone = 0.46f + 0.30f * sheen + 0.24f * (brush - 0.5f) + 0.16f * scratch
    val rim = max(abs(u), abs(v))
    tone += 0.10f * (1f - smooth(0f, 0.025f, abs(rim - 0.94f)))
    tone *= 1f - 0.18f * smooth(0.90f, 1f, rim)
    out[0] = alpha
    out[1] = tone
}

/** Speckled stone: an irregular slab with mineral mottles, flecks and thin veins. */
private fun stone(u: Float, v: Float, aa: Float, out: FloatArray) {
    val r = sqrt(u * u + v * v)
    val boundary = 0.80f +
        0.10f * noise(u * 2.1f + 3.7f, v * 2.1f - 1.3f, 61) +
        0.05f * noise(u * 5.3f, v * 5.3f, 62)
    val alpha = discAlpha(r, boundary.coerceIn(0.72f, 0.96f), aa)
    if (alpha <= 0f) {
        empty(out)
        return
    }
    val base = fbm(u * 2.0f, v * 2.0f, 5, 63)
    val grain = noise(u * 34f, v * 34f, 64)
    val light = smooth(0.74f, 0.86f, grain)
    val dark = smooth(0.74f, 0.86f, 1f - grain)
    var tone = 0.48f + 0.30f * base + 0.14f * light - 0.10f * dark
    val vein = 1f - smooth(0f, 0.035f, abs(noise(u * 4.5f, v * 4.5f, 65) - 0.5f))
    tone -= 0.14f * vein
    out[0] = alpha
    out[1] = tone
}

/** Leaf cluster: seven pointed leaves around a small stem, each with a dark midrib. */
private fun foliage(u: Float, v: Float, aa: Float, out: FloatArray) {
    var alpha = 0f
    var tone = 0f
    val leaves = 7
    for (i in 0 until leaves) {
        val angle = -PI.toFloat() / 2f + i * TAU / leaves + 0.16f * sin(i * 2.3f)
        val ca = cos(angle)
        val sa = sin(angle)
        val along = u * ca + v * sa
        val across = -u * sa + v * ca
        val t = (along - 0.04f) / 0.92f
        if (t <= 0f || t >= 1f) continue
        val halfWidth = 0.25f * sin(PI.toFloat() * t).pow(0.65f)
        val a = 1f - smooth(-aa, aa, abs(across) - halfWidth)
        if (a <= alpha) continue
        val shade = 0.55f + 0.35f * t + 0.10f * hash01(i, 7, 81)
        val edgeShade = 1f - 0.18f * smooth(halfWidth * 0.55f, halfWidth, abs(across))
        val midrib = 1f - smooth(0f, 0.025f, abs(across))
        alpha = a
        tone = shade * edgeShade - 0.16f * midrib + 0.05f * (noise(u * 6f, v * 6f, 82) - 0.5f)
    }
    val stem = discAlpha(sqrt(u * u + v * v), 0.09f, aa)
    if (stem > alpha) {
        alpha = stem
        tone = 0.42f
    }
    if (alpha <= 0f) {
        empty(out)
        return
    }
    out[0] = alpha
    out[1] = tone
}

/** Four-point stars of several sizes with a faint glow; coordinates use the same stamp units as the others. */
private fun sparkle(u: Float, v: Float, out: FloatArray) {
    var starAlpha = 0f
    var starTone = 0f
    var glow = 0f
    for (i in SPARKLES.indices step 3) {
        val dx = u - SPARKLES[i]
        val dy = v - SPARKLES[i + 1]
        val radius = SPARKLES[i + 2]
        val qx = abs(dx) / radius
        val qy = abs(dy) / radius
        val shape = sqrt(qx) + sqrt(qy)
        val a = 1f - smooth(0.94f, 1.06f, shape)
        if (a > starAlpha) {
            starAlpha = a
            starTone = 1.38f - 0.24f * shape
        }
        val distance = sqrt(dx * dx + dy * dy) / (radius * 2.6f)
        val halo = 0.18f * (1f - smooth(0f, 1f, distance)).pow(2f)
        if (halo > glow) glow = halo
    }
    if (starAlpha <= 0f && glow <= 0f) {
        empty(out)
        return
    }
    out[0] = max(starAlpha, glow)
    out[1] = if (starAlpha >= glow) starTone else 1.10f
}

/** Fibrous paper: a deckled sheet with fine horizontal and vertical fibres and a few specks. */
private fun paper(u: Float, v: Float, aa: Float, out: FloatArray) {
    val rim = max(abs(u), abs(v))
    val deckle = 0.012f * (noise(u * 9f, v * 9f, 71) - 0.5f) + 0.008f * (noise(u * 23f, v * 23f, 72) - 0.5f)
    val alpha = 1f - smooth(1f - aa, 1f + aa, rim + deckle)
    if (alpha <= 0f) {
        empty(out)
        return
    }
    val coarse = fbm(u * 2.6f, v * 2.6f, 3, 73)
    val grain = noise(u * 46f, v * 46f, 74)
    val fibreH = smooth(0.72f, 0.90f, noise(u * 2.5f, v * 110f, 75))
    val fibreV = smooth(0.74f, 0.92f, noise(u * 120f, v * 2.5f, 76))
    var tone = 0.90f + 0.08f * (coarse - 0.5f) + 0.05f * (grain - 0.5f)
    tone -= 0.10f * fibreH + 0.08f * fibreV
    tone -= 0.08f * smooth(0.86f, 0.95f, noise(u * 34f, v * 34f, 77))
    out[0] = alpha
    out[1] = tone
}

/** Four-point stars of the sparkle material: x, y and radius triplets in stamp units. */
private val SPARKLES = floatArrayOf(
    0f, 0f, 0.34f,
    -0.52f, -0.42f, 0.16f,
    0.48f, 0.34f, 0.13f,
    0.42f, -0.52f, 0.10f,
    -0.36f, 0.46f, 0.11f,
    0.10f, 0.58f, 0.07f,
    -0.62f, 0.12f, 0.06f,
    0.66f, -0.14f, 0.05f,
)

private const val TAU = 2f * PI.toFloat()

/** Coverage of a disc of [radius] with an anti-aliased rim of [aa] on both sides. */
private fun discAlpha(r: Float, radius: Float, aa: Float): Float = 1f - smooth(radius - aa, radius + aa, r)

/** Coverage of the square |u|, |v| <= 1 with an anti-aliased edge. */
private fun squareAlpha(u: Float, v: Float, aa: Float): Float =
    1f - smooth(1f - aa, 1f + aa, max(abs(u), abs(v)))

private fun empty(out: FloatArray) {
    out[0] = 0f
    out[1] = 1f
}

private fun fract(v: Float): Float = v - floor(v)

private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Fractal value noise with [octaves] octaves, normalised to 0..1. */
private fun fbm(u: Float, v: Float, octaves: Int, seed: Int): Float {
    var sum = 0f
    var amplitude = 0.5f
    var total = 0f
    var frequency = 1f
    for (octave in 0 until octaves) {
        sum += amplitude * noise(u * frequency, v * frequency, seed + octave)
        total += amplitude
        amplitude *= 0.5f
        frequency *= 2f
    }
    return if (total > 0f) sum / total else 0f
}

/** Bilinear value noise on the integer lattice, 0..1. */
private fun noise(x: Float, y: Float, seed: Int): Float {
    val ix = floor(x).toInt()
    val iy = floor(y).toInt()
    val fx = x - ix
    val fy = y - iy
    val sx = fx * fx * (3f - 2f * fx)
    val sy = fy * fy * (3f - 2f * fy)
    return lerp(
        lerp(hash01(ix, iy, seed), hash01(ix + 1, iy, seed), sx),
        lerp(hash01(ix, iy + 1, seed), hash01(ix + 1, iy + 1, seed), sx),
        sy,
    )
}

/** Deterministic hash of two lattice points and a seed, 0..1. */
private fun hash01(x: Int, y: Int, seed: Int): Float {
    var h = x * -0x61c8_86eb xor y * -0x7a14_3595 xor seed * -0x27e9_c7bf
    h = h xor (h ushr 16)
    h *= -0x7a14_3595
    h = h xor (h ushr 13)
    h *= -0x3d4d_51cb
    h = h xor (h ushr 16)
    return (h ushr 8) / 16777216f
}
