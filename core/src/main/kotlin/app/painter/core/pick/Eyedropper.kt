package app.painter.core.pick

import app.painter.core.blend.Compositor
import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import kotlin.math.floor

/** Where the eyedropper reads from. */
enum class PickSource { CANVAS, CURRENT_LAYER }

/**
 * Color sampling (spec §1). [CANVAS][PickSource.CANVAS] reads the composite of all visible layers over the
 * paper (a hidden paper counts as white); [CURRENT_LAYER][PickSource.CURRENT_LAYER] reads layer [layerId]
 * as is. Tiles are cached for the session and dropped when [Document.modified] changes.
 */
class Eyedropper(
    private val doc: Document,
    val source: PickSource,
    val layerId: String?,
    diameter: Int = 1,
) {
    /** Sample circle diameter, 1..100 px. */
    var diameter: Int = diameter.coerceIn(1, 100)
        set(v) { field = v.coerceIn(1, 100) }

    private val cache = HashMap<TileKey, Tile?>()
    private var cachedAt = doc.modified
    private val px = FloatArray(4)

    /**
     * Average of the premultiplied pixels in the sample circle centered on pixel (floor x, floor y),
     * un-premultiplied, with alpha 1. Null when the circle misses the canvas or its mean alpha < 1/255.
     */
    fun sample(x: Float, y: Float): Rgba? {
        val cx = floor(x).toInt(); val cy = floor(y).toInt()
        val r = diameter / 2f
        val r2 = r * r
        val ri = r.toInt()
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var sa = 0.0
        var n = 0
        for (dy in -ri..ri) for (dx in -ri..ri) {
            if (dx * dx + dy * dy > r2 && !(dx == 0 && dy == 0)) continue
            val px0 = cx + dx; val py0 = cy + dy
            if (px0 < 0 || py0 < 0 || px0 >= doc.width || py0 >= doc.height) continue
            pixel(px0, py0, px)
            sr += px[0]; sg += px[1]; sb += px[2]; sa += px[3]
            n++
        }
        if (n == 0) return null
        val a = sa / n
        if (a < 1.0 / 255) return null
        return Rgba(
            (sr / n / a).toFloat().coerceIn(0f, 1f),
            (sg / n / a).toFloat().coerceIn(0f, 1f),
            (sb / n / a).toFloat().coerceIn(0f, 1f),
            1f,
        )
    }

    /**
     * Pixels around ([x], [y]) for the loupe: (2·[radius] + 1)² ARGB8888 values, row-major, straight color.
     * Outside the canvas is 0 (transparent).
     */
    fun loupe(x: Int, y: Int, radius: Int, out: IntArray) {
        val side = 2 * radius + 1
        require(out.size >= side * side)
        for (j in 0 until side) for (i in 0 until side) {
            val px0 = x - radius + i; val py0 = y - radius + j
            out[j * side + i] = if (px0 < 0 || py0 < 0 || px0 >= doc.width || py0 >= doc.height) 0 else {
                pixel(px0, py0, px)
                argb(px)
            }
        }
    }

    /** Drops cached tiles (called automatically when the document changes). */
    fun invalidate() = cache.clear()

    private fun pixel(x: Int, y: Int, out: FloatArray) {
        if (doc.modified != cachedAt) { cache.clear(); cachedAt = doc.modified }
        val key = TileKey(x shr Tile.SHIFT, y shr Tile.SHIFT)
        val t = if (cache.containsKey(key)) cache[key] else load(key).also { cache[key] = it }
        if (t == null) out.fill(0f) else t.read(t.index(x and Tile.MASK, y and Tile.MASK), out)
        if (source == PickSource.CANVAS && !doc.paper.visible) {
            val k = 1f - out[3]
            out[0] += k; out[1] += k; out[2] += k; out[3] = 1f
        }
    }

    private fun load(key: TileKey): Tile? = when (source) {
        PickSource.CANVAS -> Compositor.flattenTile(doc, key, includePaper = true)
        PickSource.CURRENT_LAYER -> (layerId?.let(doc::findNode) as? RasterLayer)?.pixels?.get(key)?.copy()
    }

    private fun argb(p: FloatArray): Int {
        val a = p[3].coerceIn(0f, 1f)
        if (a <= 0f) return 0
        fun ch(v: Float) = ((v / a).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return ((a * 255f + 0.5f).toInt() shl 24) or (ch(p[0]) shl 16) or (ch(p[1]) shl 8) or ch(p[2])
    }
}
