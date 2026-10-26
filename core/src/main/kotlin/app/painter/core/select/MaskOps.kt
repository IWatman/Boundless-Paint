package app.painter.core.select

import app.painter.core.fill.Region
import app.painter.core.raster.BitPlane
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.raster.parallelFor
import app.painter.core.raster.parallelTiles
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pure operations on selection masks (spec §2.3). Pixels outside the canvas always stay 0. */
object MaskOps {

    /**
     * Combines [src] into [dst] in place: NEW = S, ADD = max(M, S), SUBTRACT = min(M, 1 − S),
     * INTERSECT = min(M, S). Returns the keys of tiles whose content changed.
     */
    fun combine(dst: MaskGrid, src: MaskGrid, mode: SelectMode): Set<TileKey> {
        val keys = when (mode) {
            SelectMode.NEW -> dst.keys() + src.keys()
            SelectMode.ADD -> src.keys().toSet()
            SelectMode.SUBTRACT -> src.keys().filter { dst.get(it) != null }.toSet()
            SelectMode.INTERSECT -> dst.keys().toSet()
        }.sortedBy { it.packed }
        val results = parallelTiles(keys) { k ->
            val m = dst.get(k)
            val s = src.get(k)
            val out = MaskTile()
            var changed = false
            for (i in out.data.indices) {
                val mv = m?.let { it.data[i].toInt() and 0xFF } ?: 0
                val sv = s?.let { it.data[i].toInt() and 0xFF } ?: 0
                val v = when (mode) {
                    SelectMode.NEW -> sv
                    SelectMode.ADD -> maxOf(mv, sv)
                    SelectMode.SUBTRACT -> minOf(mv, 255 - sv)
                    SelectMode.INTERSECT -> minOf(mv, sv)
                }
                out.data[i] = v.toByte()
                if (v != mv) changed = true
            }
            if (changed) out else null
        }
        val changed = LinkedHashSet<TileKey>()
        for ((i, k) in keys.withIndex()) {
            val t = results[i] ?: continue
            dst.put(k, if (t.isEmpty()) null else t.also { it.markDirty() })
            changed.add(k)
        }
        return changed
    }

    /** Every pixel of the canvas selected. */
    fun selectAll(m: MaskGrid) {
        m.clear()
        for (k in canvasKeys(m)) m.put(k, MaskTile().also { fillCanvasPart(it, k, m, -1) })
    }

    /** `1 − m` over the canvas; absent tiles become full. */
    fun invert(m: MaskGrid) {
        for (k in canvasKeys(m)) {
            val t = m.get(k)
            val out = MaskTile()
            val bx = k.tx shl Tile.SHIFT; val by = k.ty shl Tile.SHIFT
            for (y in 0 until minOf(Tile.SIZE, m.height - by)) for (x in 0 until minOf(Tile.SIZE, m.width - bx)) {
                val i = (y shl Tile.SHIFT) + x
                out.data[i] = (255 - (t?.let { it.data[i].toInt() and 0xFF } ?: 0)).toByte()
            }
            m.put(k, if (out.isEmpty()) null else out.also { it.markDirty() })
        }
    }

    /** Mask equal to the alpha of [g] inside the canvas. */
    fun fromAlpha(g: TileGrid): MaskGrid {
        val out = MaskGrid(g.width, g.height)
        val keys = g.keys().filter { g.isInBounds(it.tx, it.ty) }.sortedBy { it.packed }
        val tiles = parallelTiles(keys) { k ->
            val t = g.get(k)!!
            val o = MaskTile()
            val bx = k.tx shl Tile.SHIFT; val by = k.ty shl Tile.SHIFT
            var any = false
            for (y in 0 until minOf(Tile.SIZE, g.height - by)) for (x in 0 until minOf(Tile.SIZE, g.width - bx)) {
                val a = t[t.index(x, y) + 3].coerceIn(0f, 1f)
                val v = (a * 255f + 0.5f).toInt()
                if (v != 0) { o.data[(y shl Tile.SHIFT) + x] = v.toByte(); any = true }
            }
            if (any) o else null
        }
        for ((i, k) in keys.withIndex()) tiles[i]?.let { out.put(k, it) }
        return out
    }

    /**
     * Grows ([px] > 0) or shrinks the binarized mask (`m ≥ 0.5`) by a signed EDT; coverage
     * `clamp(e + 0.5 − s, 0, 1)` with [aa], otherwise binary. Soft edges are lost.
     */
    fun expand(m: MaskGrid, px: Float, aa: Boolean): MaskGrid = Region.coverage(binarize(m), px, aa)

    /**
     * Gaussian feather with σ = [radius] / 2 as three separable box passes over the mask bbox plus a margin.
     * At the canvas border the edge pixels are extended, so a full selection stays full.
     */
    fun feather(m: MaskGrid, radius: Float): MaskGrid {
        val b = m.bounds() ?: return MaskGrid(m.width, m.height)
        val sigma = radius / 2f
        if (sigma < 0.25f) return m.copy()
        val boxes = boxRadii(sigma)
        val margin = boxes.sum() + 1
        val area = IntRect(b.x0 - margin, b.y0 - margin, b.x1 + margin, b.y1 + margin)
            .intersect(IntRect(0, 0, m.width, m.height))!!
        val w = area.width; val h = area.height
        val buf = FloatArray(w * h)
        parallelFor(h) { y -> for (x in 0 until w) buf[y * w + x] = m.value(area.x0 + x, area.y0 + y) }
        for (r in boxes) parallelFor(h) { y ->
            val line = FloatArray(w); val tmp = FloatArray(w)
            for (x in 0 until w) line[x] = buf[y * w + x]
            boxLine(line, tmp, w, r)
            for (x in 0 until w) buf[y * w + x] = tmp[x]
        }
        for (r in boxes) parallelFor(w) { x ->
            val line = FloatArray(h); val tmp = FloatArray(h)
            for (y in 0 until h) line[y] = buf[y * w + x]
            boxLine(line, tmp, h, r)
            for (y in 0 until h) buf[y * w + x] = tmp[y]
        }
        return Region.fromArea(m.width, m.height, area, buf)
    }

    /**
     * Iso-0.5 contour of the mask cells owned by tile [key] (marching squares between pixel centers) as
     * segments `x0, y0, x1, y1` in document pixels. Neighbor tiles are read so the outline is seamless.
     */
    fun contour(m: MaskGrid, key: TileKey): FloatArray {
        val bx = key.tx shl Tile.SHIFT; val by = key.ty shl Tile.SHIFT
        val i0 = if (key.tx == 0) -1 else 0
        val j0 = if (key.ty == 0) -1 else 0
        return march(bx, by, i0, j0) { x, y -> m.value(x, y) }
    }

    /** Contour of a single tile [t] at [key], treating pixels outside it as unselected. */
    fun contour(t: MaskTile, key: TileKey): FloatArray {
        val bx = key.tx shl Tile.SHIFT; val by = key.ty shl Tile.SHIFT
        return march(bx, by, -1, -1) { x, y ->
            val lx = x - bx; val ly = y - by
            if (lx !in 0 until Tile.SIZE || ly !in 0 until Tile.SIZE) 0f else t.get(lx, ly)
        }
    }

    /**
     * Applies a selection-brush stroke: the alpha `a` of [buffer] adds (`m + a(1 − m)`) or, with [erase],
     * removes (`m(1 − a)`) selection in place. [opacity] scales `a`. Returns the changed keys.
     */
    fun applyStroke(mask: MaskGrid, buffer: TileGrid, erase: Boolean, opacity: Float = 1f): Set<TileKey> {
        val keys = buffer.keys().filter { mask.isInBounds(it.tx, it.ty) }.sortedBy { it.packed }
        val tiles = parallelTiles(keys) { k ->
            val src = buffer.get(k)!!
            val old = mask.get(k)
            val out = old?.copy() ?: MaskTile()
            val bx = k.tx shl Tile.SHIFT; val by = k.ty shl Tile.SHIFT
            var changed = false
            for (y in 0 until minOf(Tile.SIZE, mask.height - by)) for (x in 0 until minOf(Tile.SIZE, mask.width - bx)) {
                val a = (src[src.index(x, y) + 3] * opacity).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val i = (y shl Tile.SHIFT) + x
                val mv = (out.data[i].toInt() and 0xFF) / 255f
                val nv = if (erase) mv * (1f - a) else mv + a * (1f - mv)
                val b = (nv * 255f + 0.5f).toInt().coerceIn(0, 255)
                if (b != (out.data[i].toInt() and 0xFF)) { out.data[i] = b.toByte(); changed = true }
            }
            if (changed) out else null
        }
        val changed = LinkedHashSet<TileKey>()
        for ((i, k) in keys.withIndex()) {
            val t = tiles[i] ?: continue
            mask.put(k, if (t.isEmpty()) null else t.also { it.markDirty() })
            changed.add(k)
        }
        return changed
    }

    /** Pixels with `m ≥ 0.5`. */
    fun binarize(m: MaskGrid): BitPlane {
        val out = BitPlane(m.width, m.height)
        val keys = m.keys().filter { m.isInBounds(it.tx, it.ty) }.sortedBy { it.packed }
        parallelTiles(keys) { k ->
            val t = m.get(k)!!
            val bx = k.tx shl Tile.SHIFT; val by = k.ty shl Tile.SHIFT
            for (y in 0 until minOf(Tile.SIZE, m.height - by)) for (x in 0 until minOf(Tile.SIZE, m.width - bx)) {
                if ((t.data[(y shl Tile.SHIFT) + x].toInt() and 0xFF) >= 128) out[bx + x, by + y] = true
            }
        }
        return out
    }

    /** Binary mask of [r] (255 where set). */
    fun fromBits(r: BitPlane): MaskGrid = r.bounds()?.let { Region.toMask(r, it) } ?: MaskGrid(r.width, r.height)

    private fun canvasKeys(m: MaskGrid): List<TileKey> = IntRect(0, 0, m.width, m.height).tileKeys(m.width, m.height)

    private fun fillCanvasPart(t: MaskTile, k: TileKey, m: MaskGrid, v: Byte) {
        val bx = k.tx shl Tile.SHIFT; val by = k.ty shl Tile.SHIFT
        val w = minOf(Tile.SIZE, m.width - bx)
        for (y in 0 until minOf(Tile.SIZE, m.height - by)) {
            val row = y shl Tile.SHIFT
            t.data.fill(v, row, row + w)
        }
        t.markDirty()
    }

    /** Radii of three box filters approximating a Gaussian of [sigma]. */
    internal fun boxRadii(sigma: Float): IntArray {
        val n = 3
        val wIdeal = sqrt(12.0 * sigma * sigma / n + 1)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0)
        val mm = mIdeal.roundToInt()
        return IntArray(n) { ((if (it < mm) wl else wu) - 1) / 2 }
    }

    /** Box blur of radius [r] with clamped ends. */
    private fun boxLine(src: FloatArray, dst: FloatArray, n: Int, r: Int) {
        if (r <= 0) { src.copyInto(dst, 0, 0, n); return }
        val inv = 1f / (2 * r + 1)
        var acc = 0.0
        for (j in -r..r) acc += src[j.coerceIn(0, n - 1)]
        for (i in 0 until n) {
            dst[i] = (acc * inv).toFloat()
            acc += src[(i + r + 1).coerceAtMost(n - 1)] - src[(i - r).coerceAtLeast(0)]
        }
    }

    private fun march(bx: Int, by: Int, i0: Int, j0: Int, v: (Int, Int) -> Float): FloatArray {
        val out = ArrayList<Float>()
        for (j in j0 until Tile.SIZE) {
            val y = by + j
            for (i in i0 until Tile.SIZE) {
                val x = bx + i
                val tl = v(x, y); val tr = v(x + 1, y); val br = v(x + 1, y + 1); val bl = v(x, y + 1)
                val c = (if (tl >= ISO) 8 else 0) or (if (tr >= ISO) 4 else 0) or
                    (if (br >= ISO) 2 else 0) or (if (bl >= ISO) 1 else 0)
                if (c == 0 || c == 15) continue
                val cx = x + 0.5f; val cy = y + 0.5f
                val top = floatArrayOf(cx + lerpT(tl, tr), cy)
                val right = floatArrayOf(cx + 1f, cy + lerpT(tr, br))
                val bottom = floatArrayOf(cx + lerpT(bl, br), cy + 1f)
                val left = floatArrayOf(cx, cy + lerpT(tl, bl))
                val centerIn = (tl + tr + br + bl) / 4f >= ISO
                fun seg(a: FloatArray, b: FloatArray) { out.add(a[0]); out.add(a[1]); out.add(b[0]); out.add(b[1]) }
                when (c) {
                    1, 14 -> seg(left, bottom)
                    2, 13 -> seg(bottom, right)
                    3, 12 -> seg(left, right)
                    4, 11 -> seg(top, right)
                    6, 9 -> seg(top, bottom)
                    7, 8 -> seg(left, top)
                    5 -> if (centerIn) { seg(left, top); seg(bottom, right) } else { seg(top, right); seg(left, bottom) }
                    10 -> if (centerIn) { seg(top, right); seg(left, bottom) } else { seg(left, top); seg(bottom, right) }
                }
            }
        }
        return out.toFloatArray()
    }

    private fun lerpT(a: Float, b: Float): Float = if (a == b) 0.5f else ((ISO - a) / (b - a)).coerceIn(0f, 1f)

    private const val ISO = 0.5f
}
