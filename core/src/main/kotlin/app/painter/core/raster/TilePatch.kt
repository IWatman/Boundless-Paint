package app.painter.core.raster

/**
 * Before/after snapshots of the tiles one operation changed on one layer.
 * A null value means the tile did not exist (fully transparent).
 */
class TilePatch(
    val layerId: String,
    val before: Map<TileKey, Tile?>,
    val after: Map<TileKey, Tile?>,
) {
    val byteSize: Long
        get() = before.values.sumOf { it?.byteSize ?: 0L } + after.values.sumOf { it?.byteSize ?: 0L }
}
