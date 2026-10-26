package app.painter.core.raster

/**
 * Sparse grid of [MaskTile]s covering the canvas. A missing tile has every pixel equal to [defaultValue]:
 * 0 for selections and "hide all" layer masks, 255 (as byte -1) for "reveal all" layer masks.
 */
class MaskGrid(val width: Int, val height: Int, var defaultValue: Byte = 0) {
    private val tiles = HashMap<TileKey, MaskTile>()

    val tilesX: Int get() = (width + Tile.MASK) shr Tile.SHIFT
    val tilesY: Int get() = (height + Tile.MASK) shr Tile.SHIFT

    fun get(key: TileKey): MaskTile? = tiles[key]
    fun get(tx: Int, ty: Int): MaskTile? = tiles[TileKey(tx, ty)]

    /** Existing tile, or a new one filled with [defaultValue]. */
    fun getOrCreate(key: TileKey): MaskTile =
        tiles.getOrPut(key) { MaskTile().also { if (defaultValue != ZERO) it.data.fill(defaultValue) } }

    fun getOrCreate(tx: Int, ty: Int): MaskTile = getOrCreate(TileKey(tx, ty))

    fun put(key: TileKey, tile: MaskTile?) {
        if (tile == null) tiles.remove(key) else tiles[key] = tile
    }

    fun keys(): Set<TileKey> = tiles.keys
    fun entries(): Set<Map.Entry<TileKey, MaskTile>> = tiles.entries
    fun isInBounds(tx: Int, ty: Int) = tx in 0 until tilesX && ty in 0 until tilesY
    fun clear() = tiles.clear()

    /** True when every pixel equals [defaultValue]. */
    val isUniform: Boolean get() = tiles.values.all { it.isUniform(defaultValue) }

    /** Drops tiles equal to [defaultValue] everywhere. */
    fun compact() {
        val it = tiles.entries.iterator()
        while (it.hasNext()) if (it.next().value.isUniform(defaultValue)) it.remove()
    }

    fun copy(): MaskGrid {
        val g = MaskGrid(width, height, defaultValue)
        for ((k, t) in tiles) g.tiles[k] = t.copy()
        return g
    }

    /** Raw value 0..255 at pixel ([x], [y]); outside the canvas reads as 0. */
    fun byteAt(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        val t = tiles[TileKey(x shr Tile.SHIFT, y shr Tile.SHIFT)] ?: return defaultValue.toInt() and 0xFF
        return t.data[((y and Tile.MASK) shl Tile.SHIFT) + (x and Tile.MASK)].toInt() and 0xFF
    }

    /** Coverage 0..1 at pixel ([x], [y]); outside the canvas reads as 0. */
    fun value(x: Int, y: Int): Float = byteAt(x, y) / 255f

    /** Bounding rect of non-zero pixels, or null when there are none. */
    fun bounds(): IntRect? {
        val canvas = IntRect(0, 0, width, height)
        if (defaultValue != ZERO) return canvas
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        for ((k, t) in tiles) {
            val bx = k.tx shl Tile.SHIFT
            val by = k.ty shl Tile.SHIFT
            for (py in 0 until Tile.SIZE) {
                val row = py shl Tile.SHIFT
                for (px in 0 until Tile.SIZE) {
                    if (t.data[row + px] == ZERO) continue
                    val x = bx + px; val y = by + py
                    if (x < x0) x0 = x
                    if (x >= x1) x1 = x + 1
                    if (y < y0) y0 = y
                    if (y >= y1) y1 = y + 1
                }
            }
        }
        if (x0 == Int.MAX_VALUE) return null
        return IntRect(x0, y0, x1, y1).intersect(canvas)
    }

    /** Keys of tiles intersecting [r], clipped to the grid. */
    fun keysInRect(r: IntRect): List<TileKey> = r.tileKeys(width, height)

    private companion object {
        const val ZERO: Byte = 0
    }
}
