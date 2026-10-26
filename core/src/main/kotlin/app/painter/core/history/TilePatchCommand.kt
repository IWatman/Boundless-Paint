package app.painter.core.history

import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch

/**
 * Pixel change on one raster layer. Tiles inside [patch] are owned snapshots: they are never put
 * into the layer directly, only copies are, so later painting cannot corrupt history.
 */
class TilePatchCommand(
    val patch: TilePatch,
    override val name: String = "Stroke",
) : Command {
    override fun apply(doc: Document) {
        writeTiles(layer(doc).pixels, patch.after)
        doc.touch()
    }

    override fun revert(doc: Document) {
        writeTiles(layer(doc).pixels, patch.before)
        doc.touch()
    }

    override val byteSize: Long get() = patch.byteSize

    private fun layer(doc: Document): RasterLayer =
        doc.findNode(patch.layerId) as? RasterLayer
            ?: error("raster layer ${patch.layerId} not found")
}

/** Copies the current tiles at [keys] (null where absent), for building a [TilePatch]. */
fun snapshotTiles(grid: TileGrid, keys: Collection<TileKey>): Map<TileKey, Tile?> {
    val out = LinkedHashMap<TileKey, Tile?>(keys.size * 2)
    for (k in keys) out[k] = grid.get(k)?.copy()
    return out
}

internal fun writeTiles(grid: TileGrid, tiles: Map<TileKey, Tile?>) {
    for ((k, t) in tiles) grid.put(k, t?.copy()?.also { it.markDirty() })
}

internal fun Document.touch() {
    modified = maxOf(System.currentTimeMillis(), modified + 1)
}

internal fun tileBytes(count: Int): Long = count.toLong() * Tile.SIZE * Tile.SIZE * 4 * 4
