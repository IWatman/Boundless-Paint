package app.painter.core.io

import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey

/** Pixels of the tiles of one layer that are new or changed since the last save. */
class LayerDelta internal constructor(
    /** Half-float pixels of every new or changed tile, copied off the document. */
    val changed: Map<TileKey, ShortArray>,
    /** Keys whose tile became empty: the stored tile must disappear. */
    val removed: Set<TileKey>,
    /** Versions of every tile of the layer after this delta (committed after a successful write). */
    internal val versions: Map<TileKey, Long>,
)

/** Coverage of the mask tiles that are new or changed since the last save. */
class MaskDelta internal constructor(
    val changed: Map<TileKey, ByteArray>,
    val removed: Set<TileKey>,
    internal val versions: Map<TileKey, Long>,
)

/**
 * Everything [ProjectIO.saveDelta] needs on top of the project already on disk (spec/file-formats.md):
 * the changed tiles of every grid. The manifest side of the document travels as an ordinary [Document]
 * whose grids are empty, so the delta never duplicates pixels of untouched tiles.
 */
class SaveDelta internal constructor(
    internal val layers: Map<String, LayerDelta>,
    internal val masks: Map<String, MaskDelta>,
    /** Selection tiles when the selection changed, null when it matches the stored one. */
    internal val selection: MaskDelta?,
    /** True when the document has a non-empty selection now (it may be unchanged). */
    val selectionPresent: Boolean,
    internal val versions: Versions,
)

/** Saved tile versions of every grid; captured on the main thread, committed after the write. */
class Versions internal constructor(
    internal val layers: Map<String, Map<TileKey, Long>>,
    internal val masks: Map<String, Map<TileKey, Long>>,
    internal val selection: Map<TileKey, Long>,
)

/**
 * Remembers the tile versions of the last successful save, so a save only has to copy the tiles that
 * changed since (debt 10: the main thread no longer deep-copies the whole document on every autosave).
 *
 * Threading: use it on the main thread only, like the document it tracks.
 */
class SaveTracker {

    private var layers: Map<String, Map<TileKey, Long>> = emptyMap()
    private var masks: Map<String, Map<TileKey, Long>> = emptyMap()
    private var selection: Map<TileKey, Long> = emptyMap()

    /** Forgets the saved state: the next [collect] copies every tile of every grid. */
    fun reset() {
        layers = emptyMap()
        masks = emptyMap()
        selection = emptyMap()
    }

    /** Records the current state of [doc] as saved (after it was loaded from disk or fully written). */
    fun commitAll(doc: Document) = commitVersions(capture(doc))

    /** Versions of every tile of [doc] without copying any pixels. */
    fun capture(doc: Document): Versions {
        val l = HashMap<String, Map<TileKey, Long>>()
        val m = HashMap<String, Map<TileKey, Long>>()
        doc.root.walk { node ->
            if (node is RasterLayer) l[node.id] = versionsOf(node.pixels)
            node.mask?.let { mask -> m[node.id] = versionsOfMask(mask.tiles) }
        }
        return Versions(l, m, versionsOfMask(doc.selection.mask))
    }

    /** Marks [versions] as saved; call only after the project was written successfully. */
    fun commitVersions(versions: Versions) {
        layers = versions.layers
        masks = versions.masks
        selection = versions.selection
    }

    /** Marks the tiles of [delta] as saved; call only after [ProjectIO.saveDelta] succeeded. */
    fun commit(delta: SaveDelta) = commitVersions(delta.versions)

    /**
     * Copies the tiles of [doc] that changed since the last commit. The result is what
     * [ProjectIO.saveDelta] needs; the caller writes it and then calls [commitVersions].
     */
    fun collect(doc: Document): SaveDelta {
        val layerVersions = HashMap<String, Map<TileKey, Long>>()
        val maskVersions = HashMap<String, Map<TileKey, Long>>()
        val layerDeltas = HashMap<String, LayerDelta>()
        val maskDeltas = HashMap<String, MaskDelta>()

        doc.root.walk { node ->
            if (node is RasterLayer) {
                val known = layers[node.id].orEmpty()
                val merged = HashMap<TileKey, Long>(known.size + 4)
                val changed = HashMap<TileKey, ShortArray>()
                val removed = HashSet<TileKey>()
                for ((key, tile) in node.pixels.entries()) {
                    val v = tile.version
                    if (known[key] == v) {
                        merged[key] = v
                        continue
                    }
                    // ProjectIO never stores empty tiles: an empty tile that used to exist is a removal.
                    if (tile.isEmpty()) {
                        if (known.containsKey(key)) removed.add(key)
                        continue
                    }
                    val halves = ShortArray(Tile.CHANNELS)
                    tile.copyHalvesTo(halves)
                    changed[key] = halves
                    merged[key] = v
                }
                for (key in known.keys) if (node.pixels.get(key) == null) removed.add(key)
                layerVersions[node.id] = merged
                if (changed.isNotEmpty() || removed.isNotEmpty()) {
                    layerDeltas[node.id] = LayerDelta(changed, removed, merged)
                }
            }
            node.mask?.let { mask ->
                val grid = mask.tiles
                val known = masks[node.id].orEmpty()
                val merged = HashMap<TileKey, Long>(known.size + 2)
                val changed = HashMap<TileKey, ByteArray>()
                val removed = HashSet<TileKey>()
                for ((key, tile) in grid.entries()) {
                    val v = tile.version
                    if (known[key] == v) {
                        merged[key] = v
                        continue
                    }
                    // Tiles equal to the grid default are not stored either.
                    if (tile.isUniform(grid.defaultValue)) {
                        if (known.containsKey(key)) removed.add(key)
                        continue
                    }
                    changed[key] = tile.data.copyOf()
                    merged[key] = v
                }
                for (key in known.keys) if (grid.get(key) == null) removed.add(key)
                maskVersions[node.id] = merged
                if (changed.isNotEmpty() || removed.isNotEmpty()) {
                    maskDeltas[node.id] = MaskDelta(changed, removed, merged)
                }
            }
        }

        val selGrid = doc.selection.mask
        val selMerged = HashMap<TileKey, Long>(selection.size + 2)
        val selChanged = HashMap<TileKey, ByteArray>()
        val selRemoved = HashSet<TileKey>()
        for ((key, tile) in selGrid.entries()) {
            val v = tile.version
            if (selection[key] == v) {
                selMerged[key] = v
                continue
            }
            if (tile.isUniform(selGrid.defaultValue)) {
                if (selection.containsKey(key)) selRemoved.add(key)
                continue
            }
            selChanged[key] = tile.data.copyOf()
            selMerged[key] = v
        }
        for (key in selection.keys) if (selGrid.get(key) == null) selRemoved.add(key)
        val selDelta = if (selChanged.isNotEmpty() || selRemoved.isNotEmpty()) {
            MaskDelta(selChanged, selRemoved, selMerged)
        } else {
            null
        }

        return SaveDelta(
            layers = layerDeltas,
            masks = maskDeltas,
            selection = selDelta,
            selectionPresent = !doc.selection.isEmpty,
            versions = Versions(layerVersions, maskVersions, selMerged),
        )
    }

    private fun versionsOf(grid: TileGrid): Map<TileKey, Long> {
        val out = HashMap<TileKey, Long>(grid.keys().size)
        for ((key, tile) in grid.entries()) out[key] = tile.version
        return out
    }

    private fun versionsOfMask(grid: MaskGrid): Map<TileKey, Long> {
        val out = HashMap<TileKey, Long>(grid.keys().size)
        for ((key, tile) in grid.entries()) out[key] = tile.version
        return out
    }
}
