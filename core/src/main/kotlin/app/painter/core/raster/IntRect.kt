package app.painter.core.raster

/** Half-open pixel rectangle [x0, x1) x [y0, y1) in document coordinates. */
data class IntRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
    val isEmpty: Boolean get() = x1 <= x0 || y1 <= y0

    fun intersect(o: IntRect): IntRect? {
        val r = IntRect(maxOf(x0, o.x0), maxOf(y0, o.y0), minOf(x1, o.x1), minOf(y1, o.y1))
        return if (r.isEmpty) null else r
    }

    fun union(o: IntRect): IntRect = when {
        isEmpty -> o
        o.isEmpty -> this
        else -> IntRect(minOf(x0, o.x0), minOf(y0, o.y0), maxOf(x1, o.x1), maxOf(y1, o.y1))
    }

    fun contains(x: Int, y: Int): Boolean = x in x0 until x1 && y in y0 until y1

    /** Keys of tiles intersecting this rect, clipped to a grid of [gridW] x [gridH] pixels. */
    fun tileKeys(gridW: Int, gridH: Int): List<TileKey> {
        if (isEmpty) return emptyList()
        val tx0 = x0.coerceAtLeast(0) shr Tile.SHIFT
        val ty0 = y0.coerceAtLeast(0) shr Tile.SHIFT
        val tx1 = (x1 - 1).coerceAtMost(gridW - 1) shr Tile.SHIFT
        val ty1 = (y1 - 1).coerceAtMost(gridH - 1) shr Tile.SHIFT
        if (tx1 < tx0 || ty1 < ty0) return emptyList()
        val out = ArrayList<TileKey>((tx1 - tx0 + 1) * (ty1 - ty0 + 1))
        for (ty in ty0..ty1) for (tx in tx0..tx1) out.add(TileKey(tx, ty))
        return out
    }
}
