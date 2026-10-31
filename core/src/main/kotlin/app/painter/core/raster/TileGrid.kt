package app.painter.core.raster

/**
 * Sparse grid of tiles covering a layer. Missing tiles are fully transparent.
 * Width/height are the canvas size in pixels; tiles may extend past the edge.
 * New tiles use float32 storage when [precise], half floats otherwise (see [Tile]).
 */
class TileGrid(val width: Int, val height: Int, val precise: Boolean = false) {
    private val tiles = HashMap<TileKey, Tile>()

    val tilesX: Int get() = (width + Tile.MASK) shr Tile.SHIFT
    val tilesY: Int get() = (height + Tile.MASK) shr Tile.SHIFT

    fun get(tx: Int, ty: Int): Tile? = tiles[TileKey(tx, ty)]
    fun get(key: TileKey): Tile? = tiles[key]

    fun getOrCreate(tx: Int, ty: Int): Tile =
        tiles.getOrPut(TileKey(tx, ty)) { Tile(precise) }

    fun put(key: TileKey, tile: Tile?) {
        if (tile == null) tiles.remove(key) else tiles[key] = tile
    }

    fun keys(): Set<TileKey> = tiles.keys

    /** Bytes of pixel storage held by this grid. */
    val byteSize: Long get() = tiles.values.sumOf { it.byteSize }

    fun entries(): Set<Map.Entry<TileKey, Tile>> = tiles.entries

    fun isInBounds(tx: Int, ty: Int) = tx in 0 until tilesX && ty in 0 until tilesY

    /** Drops tiles whose alpha is zero everywhere. */
    fun compact() {
        val it = tiles.entries.iterator()
        while (it.hasNext()) if (it.next().value.isEmpty()) it.remove()
    }

    fun clear() = tiles.clear()

    fun copy(): TileGrid {
        val g = TileGrid(width, height, precise)
        for ((k, t) in tiles) g.tiles[k] = t.copy()
        return g
    }

    /** Reads one premultiplied pixel; out-of-range or missing returns transparent. */
    fun getPixel(x: Int, y: Int, out: FloatArray) {
        if (x < 0 || y < 0 || x >= width || y >= height) { out.fill(0f, 0, 4); return }
        val t = get(x shr Tile.SHIFT, y shr Tile.SHIFT)
        if (t == null) { out.fill(0f, 0, 4); return }
        val i = t.index(x and Tile.MASK, y and Tile.MASK)
        t.read(i, out)
    }

    fun setPixel(x: Int, y: Int, r: Float, g: Float, b: Float, a: Float) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val t = getOrCreate(x shr Tile.SHIFT, y shr Tile.SHIFT)
        val i = t.index(x and Tile.MASK, y and Tile.MASK)
        t.write(i, r, g, b, a)
        t.markDirty()
    }

    /** Keys of tiles intersecting the pixel rect [x0,x1)×[y0,y1), clipped to the grid. */
    fun keysInRect(x0: Int, y0: Int, x1: Int, y1: Int): List<TileKey> {
        val tx0 = (x0.coerceAtLeast(0)) shr Tile.SHIFT
        val ty0 = (y0.coerceAtLeast(0)) shr Tile.SHIFT
        val tx1 = ((x1 - 1).coerceAtMost(width - 1)) shr Tile.SHIFT
        val ty1 = ((y1 - 1).coerceAtMost(height - 1)) shr Tile.SHIFT
        if (tx1 < tx0 || ty1 < ty0) return emptyList()
        val out = ArrayList<TileKey>((tx1 - tx0 + 1) * (ty1 - ty0 + 1))
        for (ty in ty0..ty1) for (tx in tx0..tx1) out.add(TileKey(tx, ty))
        return out
    }

    /**
     * Coarse bounding box of the painted area (tile granularity, clipped to the canvas), or null when the
     * layer is empty. Used to limit filters and generates to the content instead of the whole canvas.
     */
    fun bounds(): IntRect? {
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y1 = Int.MIN_VALUE
        for ((key, tile) in tiles) {
            if (tile.isEmpty()) continue
            val bx = key.tx shl Tile.SHIFT
            val by = key.ty shl Tile.SHIFT
            if (bx < x0) x0 = bx
            if (by < y0) y0 = by
            if (bx + Tile.SIZE > x1) x1 = bx + Tile.SIZE
            if (by + Tile.SIZE > y1) y1 = by + Tile.SIZE
        }
        if (x0 == Int.MAX_VALUE) return null
        return IntRect(x0, y0, x1, y1).intersect(IntRect(0, 0, width, height))
    }
}
