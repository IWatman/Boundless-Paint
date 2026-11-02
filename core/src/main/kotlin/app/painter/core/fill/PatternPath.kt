package app.painter.core.fill

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.geom.Vec2
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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Pattern path tool (spec §21): repeats one built-in pattern along a freehand path. A tile is a square of
 * [scale] document pixels holding one motif repeat, rotated by the path tangent when [followTangent] is set
 * or by [angleDeg] otherwise. Tiles are placed every [spacing] pixels of arc length, so the distance between
 * them does not depend on how the gesture was sampled. [opacity] applies to the whole path and [invert] swaps
 * the two colours inside a tile.
 */
data class PatternPathSpec(
    val kind: PatternKind,
    val spacing: Float = DEFAULT_SPACING,
    val scale: Float = DEFAULT_SCALE,
    val followTangent: Boolean = true,
    val angleDeg: Float = 0f,
    val opacity: Float = 1f,
    val invert: Boolean = false,
) {
    companion object {
        const val MIN_SPACING = 8f
        const val MAX_SPACING = 256f
        const val DEFAULT_SPACING = 48f

        const val MIN_SCALE = 8f
        const val MAX_SCALE = 256f
        const val DEFAULT_SCALE = 64f
    }
}

/**
 * One tile of a pattern path: its centre ([x], [y]) in document pixels, its [angleDeg] clockwise from the
 * x axis and its [distance] in pixels from the start of the polyline.
 */
data class PatternPathStamp(val x: Float, val y: Float, val angleDeg: Float, val distance: Float)

/**
 * Renders [PatternPathSpec] along a polyline of document pixels. The pattern motif itself comes from
 * [PatternSampler], exactly like the pattern fill; this object only places, rotates and clips its tiles.
 *
 * Every tile is composited over the active layer inside one [TilePatch], so a whole gesture is one history
 * step. The selection clips the stamped pixels, alpha lock keeps the old alpha and a locked layer is left
 * alone, like [PatternFill].
 */
object PatternPath {
    /** Safety bound of one gesture; a path that long is already many screens wide. */
    const val MAX_STAMPS = 4096

    /**
     * Paints [spec] between [background] and [ink] on [layerId] along [points] and returns the applied patch,
     * or null when nothing changed. [spacing] overrides the spec value and is clamped to
     * [PatternPathSpec.MIN_SPACING]..[PatternPathSpec.MAX_SPACING].
     */
    fun apply(
        doc: Document,
        layerId: String,
        spec: PatternPathSpec,
        background: Rgba,
        ink: Rgba,
        points: List<Vec2>,
        spacing: Float = spec.spacing,
    ): TilePatch? {
        val layer = doc.findNode(layerId) as? RasterLayer ?: return null
        if (layer.locked || spec.opacity <= 0f || (background.a <= 0f && ink.a <= 0f)) return null
        val patch = patch(layer, doc.selection.coverageOrNull(), spec, background, ink, points, spacing)
            ?: return null
        applyTiles(layer.pixels, patch.after)
        doc.touch()
        return patch
    }

    /**
     * The patch [apply] would produce, without touching [layer]. [selection] null paints the whole canvas;
     * [layer] may be a private copy when the path runs on a worker thread.
     */
    fun patch(
        layer: RasterLayer,
        selection: MaskGrid?,
        spec: PatternPathSpec,
        background: Rgba,
        ink: Rgba,
        points: List<Vec2>,
        spacing: Float = spec.spacing,
    ): TilePatch? {
        if (layer.locked || spec.opacity <= 0f || (background.a <= 0f && ink.a <= 0f)) return null
        val scale = spec.scale.coerceIn(PatternPathSpec.MIN_SCALE, PatternPathSpec.MAX_SCALE)
        val centres = stamps(points, spacing, spec.followTangent, spec.angleDeg)
        if (centres.isEmpty()) return null
        val half = scale * 0.5f
        val grid = layer.pixels
        val canvas = IntRect(0, 0, grid.width, grid.height)
        val stamped = ArrayList<Stamp>(centres.size)
        val keys = LinkedHashSet<TileKey>()
        for (centre in centres) {
            val rad = centre.angleDeg * PI.toFloat() / 180f
            val cosA = cos(rad)
            val sinA = sin(rad)
            // Half extents of the rotated square along the document axes.
            val extent = half * (abs(cosA) + abs(sinA))
            val left = floor(centre.x - extent).toInt()
            val top = floor(centre.y - extent).toInt()
            val rect = IntRect(left, top, ceil(centre.x + extent).toInt(), ceil(centre.y + extent).toInt())
                .intersect(canvas) ?: continue
            stamped.add(Stamp(centre.x, centre.y, cosA, sinA, rect))
            keys.addAll(rect.tileKeys(grid.width, grid.height))
        }
        if (stamped.isEmpty()) return null
        val keyList = keys.toList()
        val sampler = PatternSampler(PatternSpec(kind = spec.kind, tileSize = scale, invert = spec.invert))
        val alpha = spec.opacity.coerceIn(0f, 1f)
        val tiles = parallelTiles(keyList) { key ->
            renderTile(sampler, grid.get(key), key, stamped, half, background, ink, alpha)
        }
        val result = HashMap<TileKey, Tile?>(keyList.size)
        for ((i, key) in keyList.withIndex()) result[key] = tiles[i]
        return maskedPatch(layer, result, selection, preserveAlpha = layer.alphaLock)
    }

    /**
     * Centres of the tiles of a path at a stable arc-length [spacing]: the first point and then one tile every
     * [spacing] pixels along the polyline. Short segments accumulate their length, so the distance between
     * consecutive tiles does not depend on how the gesture was sampled. A path of one point (a tap) gives one
     * tile, a path of coincident points gives one tile, and an empty path gives none.
     *
     * The angle is the direction of the segment the tile lands on. A tile that lands exactly on a vertex takes
     * the segment it closes, and the first tile takes the first segment with a non-zero length.
     * [followTangent] false uses [angleDeg] for every tile instead.
     */
    fun stamps(
        points: List<Vec2>,
        spacing: Float,
        followTangent: Boolean,
        angleDeg: Float,
    ): List<PatternPathStamp> {
        if (points.isEmpty()) return emptyList()
        val step = spacing.coerceIn(PatternPathSpec.MIN_SPACING, PatternPathSpec.MAX_SPACING)
        val fixed = normalizeAngle(angleDeg)
        val segments = segments(points)
        if (segments.isEmpty()) {
            val angle = if (followTangent) 0f else fixed
            return listOf(PatternPathStamp(points[0].x, points[0].y, angle, 0f))
        }
        val out = ArrayList<PatternPathStamp>()
        val firstAngle = if (followTangent) segments[0].angleDeg else fixed
        out.add(PatternPathStamp(points[0].x, points[0].y, firstAngle, 0f))
        var since = 0f
        var travelled = 0f
        for (segment in segments) {
            var pos = 0f
            while (out.size < MAX_STAMPS) {
                val need = step - since
                // The next tile lies past this segment: keep the distance travelled for the next one.
                if (pos + need > segment.length) {
                    since += segment.length - pos
                    break
                }
                pos += need
                since = 0f
                val t = pos / segment.length
                val angle = if (followTangent) segment.angleDeg else fixed
                out.add(
                    PatternPathStamp(
                        segment.ax + segment.dx * t,
                        segment.ay + segment.dy * t,
                        angle,
                        travelled + pos,
                    ),
                )
            }
            if (out.size >= MAX_STAMPS) break
            travelled += segment.length
        }
        return out
    }

    /** One non-zero segment of the polyline with its direction in degrees. */
    private class Segment(
        val ax: Float,
        val ay: Float,
        val dx: Float,
        val dy: Float,
        val length: Float,
        val angleDeg: Float,
    )

    /** Non-zero segments of [points] in path order; zero-length steps are skipped. */
    private fun segments(points: List<Vec2>): List<Segment> {
        val out = ArrayList<Segment>(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            if (length <= 0f) continue
            val angle = normalizeAngle(atan2(dy, dx) * 180f / PI.toFloat())
            out.add(Segment(a.x, a.y, dx, dy, length, angle))
        }
        return out
    }

    /** One placed tile: centre, unit rotation of the tile and its rotated footprint clipped to the canvas. */
    private class Stamp(
        val x: Float,
        val y: Float,
        val cosA: Float,
        val sinA: Float,
        val rect: IntRect,
    )

    /** One tile with all stamps of the path composited over [before], in path order. */
    private fun renderTile(
        sampler: PatternSampler,
        before: Tile?,
        key: TileKey,
        stamps: List<Stamp>,
        half: Float,
        background: Rgba,
        ink: Rgba,
        opacity: Float,
    ): Tile {
        val out = before?.copy(false) ?: Tile()
        val bx = key.tx shl Tile.SHIFT
        val by = key.ty shl Tile.SHIFT
        for (stamp in stamps) {
            val rect = stamp.rect
            if (rect.x1 <= bx || rect.x0 >= bx + Tile.SIZE || rect.y1 <= by || rect.y0 >= by + Tile.SIZE) continue
            renderStampInto(sampler, out, key, rect, stamp, half, background, ink, opacity)
        }
        return out
    }

    /**
     * Composites one rotated tile over [out] inside [rect] and the [key] tile. The square edge is
     * anti-aliased over one pixel; pixels outside the square keep their value.
     */
    private fun renderStampInto(
        sampler: PatternSampler,
        out: Tile,
        key: TileKey,
        rect: IntRect,
        stamp: Stamp,
        half: Float,
        background: Rgba,
        ink: Rgba,
        opacity: Float,
    ) {
        val bx = key.tx shl Tile.SHIFT
        val by = key.ty shl Tile.SHIFT
        val x0 = max(rect.x0, bx)
        val y0 = max(rect.y0, by)
        val x1 = minOf(rect.x1, bx + Tile.SIZE)
        val y1 = minOf(rect.y1, by + Tile.SIZE)
        val pixel = FloatArray(4)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val dx = x + 0.5f - stamp.x
                val dy = y + 0.5f - stamp.y
                // Tile-local pixels: [0, scale] square with the motif repeating once across it.
                val lx = stamp.cosA * dx + stamp.sinA * dy + half
                val ly = -stamp.sinA * dx + stamp.cosA * dy + half
                val rim = max(abs(lx - half), abs(ly - half))
                val edge = (half - rim + 0.5f).coerceIn(0f, 1f)
                if (edge <= 0f) continue
                val coverage = sampler.at(lx, ly)
                val inverse = 1f - coverage
                // The square edge fades the whole two-colour tile out over one pixel.
                val mask = edge * opacity
                val srcA = ((background.a * inverse + ink.a * coverage) * mask).coerceIn(0f, 1f)
                if (srcA <= 0f) continue
                val i = out.index(x - bx, y - by)
                out.read(i, pixel)
                val keep = 1f - srcA
                out.write(
                    i,
                    (background.r * background.a * inverse + ink.r * ink.a * coverage) * mask + pixel[0] * keep,
                    (background.g * background.a * inverse + ink.g * ink.a * coverage) * mask + pixel[1] * keep,
                    (background.b * background.a * inverse + ink.b * ink.a * coverage) * mask + pixel[2] * keep,
                    srcA + pixel[3] * keep,
                )
            }
        }
    }

    /** [angleDeg] folded into [0, 360). */
    private fun normalizeAngle(angleDeg: Float): Float = ((angleDeg % 360f) + 360f) % 360f
}
