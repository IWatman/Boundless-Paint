package app.painter.core.stroke

import app.painter.core.color.ColorConv
import app.painter.core.color.Rgba
import app.painter.core.fill.Edt
import app.painter.core.raster.IntRect
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.raster.alphaBounds
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Double brush (spec/tools/m2-brush.md §1.12): an outline or a drop shadow under the body of a buffered stroke.
 *
 * The body is the stroke buffer itself; the shadow is `S = sat(shadow_size + 0.5 − EDT(A ≥ 0.5))` of the buffer
 * alpha, shifted by ([DoubleParams.distance], [DoubleParams.angle]) and composited as
 * `shadow_color × S × opacity` **under** the body, so the caller runs [compositeShadow] before the body pass.
 * The distance transform is [Edt] over the bbox of the painted tiles plus a halo of `shadow_size + distance`
 * (capped at [MAX_HALO] px), which keeps the cost proportional to the stroke, not to the canvas.
 *
 * `textureOnShadow` and `bodyColor` are not applied yet (TODO(S6b)): the body keeps the buffer colour and the
 * shadow is flat. Erasing and alpha-locked strokes take the plain composite without a shadow.
 */
internal object DoubleEffect {
    /** Largest halo the shadow may add around the painted tiles, px. */
    const val MAX_HALO = 64

    private val DEG = (PI / 180.0).toFloat()

    /**
     * Composites the shadow of [buffer] into the target tiles returned by [prepare] (null = not writable, the
     * pixel is skipped). [opacity] is the stroke opacity cap, [params] the effect settings.
     */
    fun compositeShadow(buffer: TileGrid, params: DoubleParams, opacity: Float, prepare: (TileKey) -> Tile?) {
        if (opacity <= 0f) return
        val radius = params.shadowSize.coerceAtLeast(0f)
        val dist = params.distance
        val halo = ceil(radius + abs(dist)).toInt().coerceIn(0, MAX_HALO)
        val bounds = buffer.alphaBounds() ?: return
        // The bbox of the painted tiles plus the halo, clipped to the canvas (spec §1.12).
        val x0 = ((bounds.x0 shr Tile.SHIFT) shl Tile.SHIFT) - halo
        val y0 = ((bounds.y0 shr Tile.SHIFT) shl Tile.SHIFT) - halo
        val x1 = ((((bounds.x1 - 1) shr Tile.SHIFT) + 1) shl Tile.SHIFT) + halo
        val y1 = ((((bounds.y1 - 1) shr Tile.SHIFT) + 1) shl Tile.SHIFT) + halo
        val area = IntRect(
            x0.coerceAtLeast(0),
            y0.coerceAtLeast(0),
            x1.coerceAtMost(buffer.width),
            y1.coerceAtMost(buffer.height),
        )
        if (area.isEmpty) return
        val w = area.width
        val h = area.height
        // EDT of A ≥ 0.5: the squared distance from every pixel of the area to the nearest body pixel.
        val dsq = Edt.distSq(area) { x, y -> bodyAt(buffer, x, y) }
        val shadow = FloatArray(w * h)
        for (i in dsq.indices) shadow[i] = (radius + 1f - sqrt(dsq[i])).coerceIn(0f, 1f)
        val rad = params.angle * DEG
        val ox = dist * cos(rad)
        val oy = dist * sin(rad)
        val col = ColorConv.parseHex(params.shadowColor) ?: Rgba.BLACK
        var cachedKey = Long.MIN_VALUE
        var tile: Tile? = null
        for (y in 0 until h) {
            val fy = y - oy
            val py = area.y0 + y
            for (x in 0 until w) {
                val v = sampleShadow(shadow, w, h, x - ox, fy)
                if (v <= 0f) continue
                val px = area.x0 + x
                val key = TileKey(px shr Tile.SHIFT, py shr Tile.SHIFT)
                if (key.packed != cachedKey) {
                    cachedKey = key.packed
                    tile = prepare(key)
                }
                val t = tile ?: continue
                val sa = v * opacity
                if (sa <= 0f) continue
                val i = t.index(px and Tile.MASK, py and Tile.MASK)
                val inv = 1f - sa
                t[i] = col.r * sa + t[i] * inv
                t[i + 1] = col.g * sa + t[i + 1] * inv
                t[i + 2] = col.b * sa + t[i + 2] * inv
                t[i + 3] = sa + t[i + 3] * inv
                t.markDirty()
            }
        }
    }

    /** True when the buffer alpha at ([x], [y]) is at least a half: the body of the stroke. */
    private fun bodyAt(buffer: TileGrid, x: Int, y: Int): Boolean {
        val t = buffer.get(x shr Tile.SHIFT, y shr Tile.SHIFT) ?: return false
        return t[t.index(x and Tile.MASK, y and Tile.MASK) + 3] >= 0.5f
    }

    /** Bilinear sample of the shadow plane at ([fx], [fy]) in plane coordinates; 0 outside the plane. */
    private fun sampleShadow(s: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        if (fx <= -1f || fy <= -1f || fx >= w || fy >= h) return 0f
        val ix = fx.toInt()
        val iy = fy.toInt()
        val ax = fx - ix
        val ay = fy - iy
        val v00 = planeAt(s, w, h, ix, iy)
        val v10 = planeAt(s, w, h, ix + 1, iy)
        val v01 = planeAt(s, w, h, ix, iy + 1)
        val v11 = planeAt(s, w, h, ix + 1, iy + 1)
        val top = v00 + (v10 - v00) * ax
        val bot = v01 + (v11 - v01) * ax
        return top + (bot - top) * ay
    }

    private fun planeAt(s: FloatArray, w: Int, h: Int, x: Int, y: Int): Float =
        if (x < 0 || y < 0 || x >= w || y >= h) 0f else s[y * w + x]
}
