package app.painter.core.history

import app.painter.core.doc.Document
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskPatch
import app.painter.core.raster.MaskTile
import app.painter.core.raster.TileKey

/**
 * Change of the document selection mask ([MaskPatch.SELECTION]). Like [TilePatchCommand], the tiles in
 * [patch] are owned snapshots and only copies of them go into the document.
 */
class SelectionPatchCommand(
    val patch: MaskPatch,
    override val name: String = "Selection",
) : Command {
    init { require(patch.targetId == MaskPatch.SELECTION) { "not a selection patch: ${patch.targetId}" } }

    override fun apply(doc: Document) {
        writeSelectionTiles(doc.selection.mask, patch.after)
        doc.touch()
    }

    override fun revert(doc: Document) {
        writeSelectionTiles(doc.selection.mask, patch.before)
        doc.touch()
    }

    override val byteSize: Long get() = patch.byteSize + 64L

    companion object {
        /**
         * Command that turns the current selection mask into [newMask], or null when they are equal.
         * The document is not modified.
         */
        fun replacing(doc: Document, newMask: MaskGrid, name: String = "Selection"): SelectionPatchCommand? =
            diffSelectionMasks(doc.selection.mask, newMask)?.let { SelectionPatchCommand(it, name) }
    }
}

/** Turns the selection on or off without touching the mask. */
class SetSelectionActive(
    val on: Boolean,
    override val name: String = if (on) "Selection on" else "Selection off",
) : Command {
    private var prev: Boolean? = null

    override fun apply(doc: Document) {
        prev = doc.selection.active
        doc.selection.active = on
        doc.touch()
    }

    override fun revert(doc: Document) {
        prev?.let { doc.selection.active = it }
        doc.touch()
    }

    override val byteSize: Long get() = 64L
}

/**
 * Selection patch turning [old] into [new]: only tiles whose pixels differ are recorded, a tile equal to
 * zero everywhere is stored as null. Null when nothing differs.
 */
fun diffSelectionMasks(old: MaskGrid, new: MaskGrid): MaskPatch? {
    val keys = LinkedHashSet<TileKey>().apply { addAll(old.keys()); addAll(new.keys()) }
    val before = LinkedHashMap<TileKey, MaskTile?>()
    val after = LinkedHashMap<TileKey, MaskTile?>()
    for (k in keys.sortedBy { it.packed }) {
        val a = old.get(k)?.takeUnless { it.isEmpty() }
        val b = new.get(k)?.takeUnless { it.isEmpty() }
        val same = when {
            a == null && b == null -> true
            a == null || b == null -> false
            else -> a.contentEquals(b)
        }
        if (same) continue
        before[k] = a?.copy()
        after[k] = b?.copy()
    }
    if (before.isEmpty()) return null
    return MaskPatch(MaskPatch.SELECTION, before, after)
}

private fun writeSelectionTiles(grid: MaskGrid, tiles: Map<TileKey, MaskTile?>) {
    for ((k, t) in tiles) grid.put(k, t?.copy()?.also { it.markDirty() })
}
