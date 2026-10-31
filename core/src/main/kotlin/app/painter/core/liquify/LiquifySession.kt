package app.painter.core.liquify

import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Liquify "push" tool (spec/ROADMAP.md M3): every dab moves the pixels under the disc along the pointer motion
 * with a smooth falloff. The session edits [grid] in place and remembers the original tiles it touched, so the
 * caller can build one undo step (`TilePatch`) when the gesture ends, or put the originals back on cancel.
 *
 * [size] is the brush diameter in document pixels, [strength] 0..1 scales the push (1 = the pixels follow the
 * pointer exactly at the centre of the disc).
 */
class LiquifySession(
    private val grid: TileGrid,
    var size: Float = 160f,
    var strength: Float = 0.5f,
) {
    private val before = LinkedHashMap<TileKey, Tile?>()
    private val dirty = LinkedHashSet<TileKey>()
    private var lastX = 0f
    private var lastY = 0f
    private var started = false

    /** Keys touched so far, in the order they were first pushed. */
    val touched: Set<TileKey> get() = before.keys

    /** Copies of the touched tiles as they were before the first push (null = the tile did not exist). */
    val beforeTiles: Map<TileKey, Tile?> get() = before

    /** Tiles changed since the last call; the caller uploads them and clears the set. */
    fun takeDirty(): Set<TileKey> {
        if (dirty.isEmpty()) return emptySet()
        val out = LinkedHashSet(dirty)
        dirty.clear()
        return out
    }

    fun begin(x: Float, y: Float) {
        lastX = x
        lastY = y
        started = true
    }

    /** Pushes along the segment from the previous point, splitting it into overlapping dabs. */
    fun move(x: Float, y: Float) {
        if (!started) {
            begin(x, y)
            return
        }
        val dx = x - lastX
        val dy = y - lastY
        val len = hypot(dx, dy)
        if (len < 0.01f) return
        val step = (size * 0.12f).coerceAtLeast(1f)
        val n = ceil(len / step).toInt().coerceIn(1, 64)
        for (i in 1..n) {
            dab(lastX + dx * i / n, lastY + dy * i / n, dx / n, dy / n)
        }
        lastX = x
        lastY = y
    }

    fun end() {
        started = false
    }

    private fun dab(cx: Float, cy: Float, dx: Float, dy: Float) {
        val radius = (size / 2f).coerceAtLeast(1f)
        val k = strength.coerceIn(0f, 1f)
        val pushX = dx * k
        val pushY = dy * k
        if (hypot(pushX, pushY) < 0.02f) return
        val pad = ceil(hypot(pushX, pushY)).toInt() + 2
        val x0 = (cx - radius).toInt().coerceAtLeast(0)
        val y0 = (cy - radius).toInt().coerceAtLeast(0)
        val x1 = (cx + radius).toInt().coerceAtMost(grid.width - 1)
        val y1 = (cy + radius).toInt().coerceAtMost(grid.height - 1)
        if (x1 < x0 || y1 < y0) return
        for (key in grid.keysInRect(x0, y0, x1 + 1, y1 + 1)) {
            warpTile(key, cx, cy, radius, pushX, pushY, pad)
        }
    }

    private fun warpTile(key: TileKey, cx: Float, cy: Float, radius: Float, pushX: Float, pushY: Float, pad: Int) {
        val tileX = key.tx shl Tile.SHIFT
        val tileY = key.ty shl Tile.SHIFT
        val ox = max(0, tileX - pad)
        val oy = max(0, tileY - pad)
        val ex = min(grid.width, tileX + Tile.SIZE + pad)
        val ey = min(grid.height, tileY + Tile.SIZE + pad)
        val w = ex - ox
        val h = ey - oy
        if (w <= 0 || h <= 0) return
        val src = readRect(ox, oy, w, h)
        if (!before.containsKey(key)) before[key] = grid.get(key)?.copy()
        val tile = grid.getOrCreate(key.tx, key.ty)
        val sample = FloatArray(4)
        var changed = false
        val x0 = max(ox, tileX)
        val y0 = max(oy, tileY)
        val x1 = min(ex, tileX + Tile.SIZE)
        val y1 = min(ey, tileY + Tile.SIZE)
        val r2 = radius * radius
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d2 = dx * dx + dy * dy
                if (d2 >= r2) continue
                val fall = 1f - d2 / r2
                val f = fall * fall
                val sx = x - pushX * f
                val sy = y - pushY * f
                if (sx < ox || sx >= ex || sy < oy || sy >= ey) continue
                bilinear(src, w, h, sx - ox, sy - oy, sample)
                tile.write(tile.index(x - tileX, y - tileY), sample)
                changed = true
            }
        }
        if (changed) {
            tile.markDirty()
            dirty.add(key)
        }
    }

    private fun readRect(ox: Int, oy: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h * 4)
        for (key in grid.keysInRect(ox, oy, ox + w, oy + h)) {
            val tile = grid.get(key) ?: continue
            val tx0 = max(ox, key.tx shl Tile.SHIFT)
            val ty0 = max(oy, key.ty shl Tile.SHIFT)
            val tx1 = min(ox + w, (key.tx shl Tile.SHIFT) + Tile.SIZE)
            val ty1 = min(oy + h, (key.ty shl Tile.SHIFT) + Tile.SIZE)
            for (y in ty0 until ty1) {
                val row = y - oy
                for (x in tx0 until tx1) {
                    val i = tile.index(x - (key.tx shl Tile.SHIFT), y - (key.ty shl Tile.SHIFT))
                    val j = (row * w + (x - ox)) * 4
                    out[j] = tile[i]
                    out[j + 1] = tile[i + 1]
                    out[j + 2] = tile[i + 2]
                    out[j + 3] = tile[i + 3]
                }
            }
        }
        return out
    }

    private fun bilinear(buf: FloatArray, w: Int, h: Int, x: Float, y: Float, out: FloatArray) {
        val fx = x.coerceIn(0f, (w - 1).toFloat())
        val fy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val i00 = (y0 * w + x0) * 4
        val i10 = (y0 * w + x1) * 4
        val i01 = (y1 * w + x0) * 4
        val i11 = (y1 * w + x1) * 4
        for (c in 0..3) {
            val top = buf[i00 + c] + (buf[i10 + c] - buf[i00 + c]) * tx
            val bottom = buf[i01 + c] + (buf[i11 + c] - buf[i01 + c]) * tx
            out[c] = top + (bottom - top) * ty
        }
    }
}
