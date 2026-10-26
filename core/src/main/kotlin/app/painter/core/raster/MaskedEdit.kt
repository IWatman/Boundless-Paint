package app.painter.core.raster

import app.painter.core.doc.RasterLayer
import java.util.stream.IntStream

/**
 * Builds the patch that blends [result] into [layer] through [mask] without touching the layer:
 * `out = before · (1 − m) + after · m` on premultiplied pixels (spec/tools/m2-select-transform-fill.md §0.3).
 *
 * A null [mask] means full coverage: the result tiles replace the layer tiles as they are. A null value in
 * [result] stands for a fully transparent tile. With [preserveAlpha] (alpha lock) every output pixel keeps
 * the alpha it had before, only its color changes. Tiles that end up unchanged are left out; null is
 * returned when nothing changes.
 */
fun maskedPatch(
    layer: RasterLayer,
    result: Map<TileKey, Tile?>,
    mask: MaskGrid?,
    preserveAlpha: Boolean = false,
): TilePatch? {
    val grid = layer.pixels
    val keys = result.keys.toList()
    val outs = parallelTiles(keys) { key -> blendTile(grid.get(key), result[key], mask, key, preserveAlpha) }
    val before = LinkedHashMap<TileKey, Tile?>()
    val after = LinkedHashMap<TileKey, Tile?>()
    for ((i, key) in keys.withIndex()) {
        val o = outs[i] ?: continue
        before[key] = grid.get(key)?.copy()
        after[key] = o.tile
    }
    if (before.isEmpty()) return null
    return TilePatch(layer.id, before, after)
}

/** [maskedPatch] applied to [layer] right away. Returns the applied patch, or null when nothing changed. */
fun maskedCommit(
    layer: RasterLayer,
    result: Map<TileKey, Tile?>,
    mask: MaskGrid?,
    preserveAlpha: Boolean = false,
): TilePatch? {
    val patch = maskedPatch(layer, result, mask, preserveAlpha) ?: return null
    applyTiles(layer.pixels, patch.after)
    return patch
}

/** Writes copies of [tiles] into [grid] (null removes the tile). */
fun applyTiles(grid: TileGrid, tiles: Map<TileKey, Tile?>) {
    for ((k, t) in tiles) grid.put(k, t?.copy()?.also { it.markDirty() })
}

private class Out(val tile: Tile?)

/** Returns null when the tile does not change. */
private fun blendTile(before: Tile?, after: Tile?, mask: MaskGrid?, key: TileKey, preserveAlpha: Boolean): Out? {
    val mt = mask?.get(key)
    val maskDefault = mask?.let { it.defaultValue.toInt() and 0xFF } ?: 255
    if (mt == null && maskDefault == 0) return null
    if (before == null && after == null) return null
    val full = mt == null && maskDefault == 255
    if (full && !preserveAlpha) {
        val out = after?.takeUnless { it.isEmpty() }?.copy(false)
        if (out == null) return if (before == null || before.isEmpty()) null else Out(null)
        return if (before != null && before.contentEquals(out)) null else Out(out)
    }
    if (preserveAlpha && before == null) return null
    val out = before?.copy(false) ?: Tile()
    val b = FloatArray(4)
    val a = FloatArray(4)
    val bx = key.tx shl Tile.SHIFT
    val by = key.ty shl Tile.SHIFT
    val uniformM = if (mt == null) maskDefault / 255f else -1f
    var changed = false
    for (y in 0 until Tile.SIZE) {
        val inY = by + y < (mask?.height ?: Int.MAX_VALUE)
        for (x in 0 until Tile.SIZE) {
            val p = (y shl Tile.SHIFT) + x
            val m = when {
                uniformM >= 0f -> uniformM
                else -> (mt!!.data[p].toInt() and 0xFF) / 255f
            }
            if (m <= 0f || !inY || (mask != null && bx + x >= mask.width)) continue
            val i = p shl 2
            if (before != null) before.read(i, b) else b.fill(0f)
            if (after != null) after.read(i, a) else a.fill(0f)
            val k = 1f - m
            var r = b[0] * k + a[0] * m
            var g = b[1] * k + a[1] * m
            var bl = b[2] * k + a[2] * m
            var al = b[3] * k + a[3] * m
            if (preserveAlpha) {
                val target = b[3]
                if (al > 1e-7f) {
                    val s = target / al
                    r *= s; g *= s; bl *= s
                } else {
                    r = 0f; g = 0f; bl = 0f
                }
                al = target
            }
            if (r != b[0] || g != b[1] || bl != b[2] || al != b[3]) {
                out.write(i, r, g, bl, al)
                changed = true
            }
        }
    }
    if (!changed) return null
    if (before != null && out.contentEquals(before)) return null
    return Out(if (out.isEmpty()) null else out)
}

/**
 * Runs [f] for every item on the common fork-join pool and returns the results in input order.
 * Each call must only write to data it owns (one tile), so results are deterministic.
 */
internal fun <K, R> parallelTiles(items: List<K>, f: (K) -> R): List<R> {
    if (items.size <= 1) return items.map(f)
    @Suppress("UNCHECKED_CAST")
    val out = arrayOfNulls<Any?>(items.size) as Array<R>
    IntStream.range(0, items.size).parallel().forEach { out[it] = f(items[it]) }
    return out.asList()
}

/** Runs [f] for 0 until [n] in parallel (rows, bands, columns). */
internal fun parallelFor(n: Int, f: (Int) -> Unit) {
    if (n <= 1) { if (n == 1) f(0); return }
    IntStream.range(0, n).parallel().forEach { f(it) }
}

/** Tight bounds of pixels with non-zero alpha inside the canvas, or null when there are none. */
fun TileGrid.alphaBounds(): IntRect? {
    var r: IntRect? = null
    for ((k, t) in entries()) {
        if (!isInBounds(k.tx, k.ty)) continue
        val bx = k.tx shl Tile.SHIFT
        val by = k.ty shl Tile.SHIFT
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (y in 0 until minOf(Tile.SIZE, height - by)) for (x in 0 until minOf(Tile.SIZE, width - bx)) {
            if (t[t.index(x, y) + 3] <= 0f) continue
            if (x < x0) x0 = x
            if (x >= x1) x1 = x + 1
            if (y < y0) y0 = y
            if (y >= y1) y1 = y + 1
        }
        if (x1 < 0) continue
        val tr = IntRect(bx + x0, by + y0, bx + x1, by + y1)
        r = r?.union(tr) ?: tr
    }
    return r
}

/**
 * [src] multiplied per pixel by the mask coverage (or by `1 − m` with [inverse]); null when the result is
 * empty. Pixels outside the canvas count as unselected.
 */
fun maskedTile(src: Tile, mask: MaskGrid, key: TileKey, inverse: Boolean = false): Tile? {
    val mt = mask.get(key)
    val bx = key.tx shl Tile.SHIFT
    val by = key.ty shl Tile.SHIFT
    val def = mask.defaultValue.toInt() and 0xFF
    val out = Tile(src.precise)
    val p = FloatArray(4)
    var any = false
    for (y in 0 until Tile.SIZE) for (x in 0 until Tile.SIZE) {
        val inCanvas = bx + x < mask.width && by + y < mask.height
        val mv = if (!inCanvas) 0 else mt?.let { it.data[(y shl Tile.SHIFT) + x].toInt() and 0xFF } ?: def
        val m = (if (inverse) 255 - mv else mv) / 255f
        if (m <= 0f) continue
        val i = out.index(x, y)
        src.read(i, p)
        if (p[3] <= 0f) continue
        if (m < 1f) for (c in 0 until 4) p[c] *= m
        out.write(i, p)
        any = true
    }
    return if (any) out else null
}
