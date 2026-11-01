package app.painter.core.material

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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Material stroke tool (spec §21): [points] is the polyline of one gesture in document pixels. Stamps are
 * placed every `spacing × stamp size` pixels along the polyline, so a tap gives one stamp at its point and a
 * drag paints the material along the path.
 *
 * All stamps of a gesture are accumulated into one [TilePatch]: overlap builds up like a brush and the caller
 * records exactly one history step. The generator is deterministic, the selection clips the painted pixels and
 * alpha lock keeps the old alpha, like [MaterialStamp].
 */
object MaterialStroke {
    /**
     * Safety bound of one gesture. A stroke that long is already many screens wide; truncating it keeps the
     * procedural sampling bounded on a phone.
     */
    const val MAX_STAMPS = 2048

    /**
     * Paints [spec] with [color] on [layerId] along [points] and returns the applied patch, or null when
     * nothing changed. The layer keeps its alpha when it is alpha-locked and is left alone when it is locked.
     */
    fun apply(
        doc: Document,
        layerId: String,
        spec: MaterialSpec,
        color: Rgba,
        points: List<Vec2>,
        spacing: Float = spec.spacing,
    ): TilePatch? {
        val layer = doc.findNode(layerId) as? RasterLayer ?: return null
        if (layer.locked || spec.opacity <= 0f || color.a <= 0f) return null
        val patch = patch(layer, doc.selection.coverageOrNull(), spec, color, points, spacing) ?: return null
        applyTiles(layer.pixels, patch.after)
        doc.touch()
        return patch
    }

    /**
     * The patch [apply] would produce, without touching [layer]. [selection] null paints the whole layer;
     * [layer] may be a private copy when the stroke runs on a worker thread. [spacing] is a fraction of the
     * stamp size and is clamped to [MaterialSpec.MIN_SPACING]..[MaterialSpec.MAX_SPACING].
     */
    fun patch(
        layer: RasterLayer,
        selection: MaskGrid?,
        spec: MaterialSpec,
        color: Rgba,
        points: List<Vec2>,
        spacing: Float = spec.spacing,
    ): TilePatch? {
        if (layer.locked || spec.opacity <= 0f || color.a <= 0f || points.isEmpty()) return null
        val sampler = MaterialSampler(spec)
        val side = sampler.size.roundToInt()
        val spacingPx = max(1f, side * spacing.coerceIn(MaterialSpec.MIN_SPACING, MaterialSpec.MAX_SPACING))
        val centres = stampCentres(points, spacingPx)
        if (centres.isEmpty()) return null
        val grid = layer.pixels
        val canvas = IntRect(0, 0, grid.width, grid.height)
        val stamps = ArrayList<Stamp>(centres.size)
        val keys = LinkedHashSet<TileKey>()
        for (centre in centres) {
            val left = (centre.x - side * 0.5f).roundToInt()
            val top = (centre.y - side * 0.5f).roundToInt()
            val rect = IntRect(left, top, left + side, top + side).intersect(canvas) ?: continue
            stamps.add(Stamp(centre.x, centre.y, rect))
            keys.addAll(rect.tileKeys(grid.width, grid.height))
        }
        if (stamps.isEmpty()) return null
        val keyList = keys.toList()
        val alpha = spec.opacity.coerceIn(0f, 1f)
        val tiles = parallelTiles(keyList) { key ->
            renderTile(sampler, grid.get(key), key, stamps, color, alpha)
        }
        val result = HashMap<TileKey, Tile?>(keyList.size)
        for ((i, key) in keyList.withIndex()) result[key] = tiles[i]
        return maskedPatch(layer, result, selection, preserveAlpha = layer.alphaLock)
    }

    /**
     * Centres of the stamps of a gesture at a stable [spacing] in document pixels: the first point of [points]
     * and then one stamp every [spacing] pixels along the polyline. Short segments accumulate their length, so
     * the distance between consecutive stamps does not depend on how the gesture was sampled. A single point
     * (a tap) gives exactly one stamp. The result is capped at [MAX_STAMPS] for a pathologically long gesture.
     */
    fun stampCentres(points: List<Vec2>, spacing: Float): List<Vec2> {
        if (points.isEmpty()) return emptyList()
        val first = points[0]
        if (points.size == 1 || spacing <= 0f) return listOf(first)
        val step = max(1f, spacing)
        val out = ArrayList<Vec2>()
        out.add(first)
        var since = 0f
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            if (length <= 0f) continue
            var pos = 0f
            while (out.size < MAX_STAMPS) {
                val need = step - since
                // The next stamp lies past this segment: keep the distance travelled for the next one.
                if (pos + need > length) {
                    since += length - pos
                    break
                }
                pos += need
                val t = pos / length
                out.add(Vec2(a.x + dx * t, a.y + dy * t))
                since = 0f
            }
            if (out.size >= MAX_STAMPS) break
        }
        return out
    }

    /** One stamp of a gesture: its centre and its footprint clipped to the canvas. */
    private class Stamp(val x: Float, val y: Float, val rect: IntRect)

    /** One tile with all stamps of a gesture composited over [before], in gesture order. */
    private fun renderTile(
        sampler: MaterialSampler,
        before: Tile?,
        key: TileKey,
        stamps: List<Stamp>,
        color: Rgba,
        opacity: Float,
    ): Tile {
        val out = before?.copy(false) ?: Tile()
        val bx = key.tx shl Tile.SHIFT
        val by = key.ty shl Tile.SHIFT
        for (stamp in stamps) {
            val rect = stamp.rect
            if (rect.x1 <= bx || rect.x0 >= bx + Tile.SIZE || rect.y1 <= by || rect.y0 >= by + Tile.SIZE) continue
            renderStampInto(sampler, out, key, rect, stamp.x, stamp.y, color, opacity)
        }
        return out
    }
}
