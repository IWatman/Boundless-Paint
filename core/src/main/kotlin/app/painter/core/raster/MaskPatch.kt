package app.painter.core.raster

/**
 * Before/after snapshots of the mask tiles one operation changed. [targetId] is a node id (layer mask)
 * or [SELECTION] for the document selection. A null value means the tile was absent (default value).
 */
class MaskPatch(
    val targetId: String,
    val before: Map<TileKey, MaskTile?>,
    val after: Map<TileKey, MaskTile?>,
) {
    val byteSize: Long
        get() = (before.values.count { it != null } + after.values.count { it != null }).toLong() *
            Tile.SIZE * Tile.SIZE

    companion object {
        const val SELECTION = "selection"
    }
}
