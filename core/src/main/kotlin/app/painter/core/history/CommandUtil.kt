package app.painter.core.history

import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.LayerMask
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.doc.effectivelyLocked
import app.painter.core.doc.touchesLocked
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey

/** Thrown by commands that would change pixels, masks or existence of a locked node (spec L7). */
class LockedException(val nodeId: String) : IllegalStateException("node $nodeId is locked")

internal const val STRUCT_BYTES = 64L
internal const val MASK_TILE_BYTES = Tile.SIZE.toLong() * Tile.SIZE

internal fun Document.group(id: String?): GroupNode =
    if (id == null || id == root.id) root
    else findNode(id) as? GroupNode ?: error("group $id not found")

internal fun Document.node(id: String): Node = findNode(id) ?: error("node $id not found")

internal fun Document.raster(id: String): RasterLayer =
    findNode(id) as? RasterLayer ?: error("raster layer $id not found")

internal fun Node.contains(id: String): Boolean {
    if (this.id == id) return true
    var found = false
    if (this is GroupNode) walk { if (it.id == id) found = true }
    return found
}

/** Throws [LockedException] when [n] or an ancestor is locked. */
internal fun requireUnlocked(n: Node) {
    if (n.effectivelyLocked) throw LockedException(n.id)
}

/** Throws [LockedException] when [n], an ancestor or a descendant is locked. */
internal fun requireTreeUnlocked(n: Node) {
    if (n.touchesLocked) throw LockedException(n.id)
}

internal fun maskBytes(m: LayerMask?): Long = (m?.tiles?.keys()?.size ?: 0) * MASK_TILE_BYTES

internal fun nodeBytes(node: Node): Long {
    var bytes = STRUCT_BYTES
    fun add(n: Node) {
        bytes += maskBytes(n.mask)
        if (n is RasterLayer) bytes += tileBytes(n.pixels.keys().size)
    }
    add(node)
    if (node is GroupNode) node.walk(::add)
    return bytes
}

/** Where a node sits: its parent and index there. */
internal class Slot(val node: Node, val parentId: String, val index: Int)

/**
 * The nodes of [ids] that exist (root excluded), with nodes inside another listed group dropped (the group
 * absorbs them), in stack order: depth-first, bottom-to-top.
 */
internal fun Document.normalize(ids: Collection<String>): List<Node> {
    val set = ids.toSet()
    val out = ArrayList<Node>()
    root.walk { n ->
        if (n.id !in set) return@walk
        var p = n.parent
        while (p != null && p !== root) { if (p.id in set) return@walk; p = p.parent }
        out.add(n)
    }
    return out
}

/** Removes [nodes] from their parents, top-most first; returns the slots for [restoreAll]. */
internal fun removeAll(nodes: List<Node>): List<Slot> {
    val slots = ArrayList<Slot>(nodes.size)
    for (n in nodes.asReversed()) {
        val p = n.parent ?: error("cannot remove root")
        slots.add(Slot(n, p.id, p.remove(n)))
    }
    return slots
}

/** Puts back nodes removed by [removeAll] (each must currently have no parent). */
internal fun Document.restoreAll(slots: List<Slot>) {
    for (s in slots.asReversed()) group(s.parentId).add(s.node, s.index)
}

/**
 * The node that should become active when [removed] disappear and the active node was among them: nearest
 * remaining sibling below the first removed node that held the active one, else above, else its parent group,
 * else the top-most non-group node of the document.
 */
internal fun Document.activeAfterRemoval(removed: List<Node>): String? {
    val active = activeId
    if (active != null && removed.none { it.contains(active) }) return active
    val holder = removed.firstOrNull { active != null && it.contains(active) } ?: removed.firstOrNull()
    val parent = holder?.parent ?: return topNode(root)?.id
    val siblings = parent.children
    val i = siblings.indexOf(holder)
    val gone = removed.toSet()
    for (j in i - 1 downTo 0) if (siblings[j] !in gone) return siblings[j].id
    for (j in i + 1 until siblings.size) if (siblings[j] !in gone) return siblings[j].id
    if (parent !== root) return parent.id
    return topNode(root, gone)?.id
}

private fun topNode(root: GroupNode, skip: Set<Node> = emptySet()): Node? {
    var last: Node? = null
    fun visit(g: GroupNode) {
        for (c in g.children) {
            if (c in skip) continue
            if (c !is GroupNode) last = c else visit(c)
        }
    }
    visit(root)
    return last ?: root.children.lastOrNull { it !in skip }
}

/** Number of [parent]'s children that are not in [removed] and sit below index [index]. */
internal fun remainingBelow(parent: GroupNode, index: Int, removed: Collection<Node>): Int {
    var n = 0
    for (j in 0 until index) if (parent.children[j] !in removed) n++
    return n
}

/** Effective mask factor 0..1 of raw byte [m] at [density]. */
internal fun maskEffective(m: Int, density: Float): Float = 1f - density * (1f - m / 255f)

/** Multiplies [grid] in place by [opacity] and the effective values of [mask] (ignoring `enabled`). */
internal fun bake(grid: TileGrid, opacity: Float, mask: LayerMask?) {
    val pixels = Tile.SIZE * Tile.SIZE
    val keys = grid.keys().toList()
    for (key in keys) {
        val t = grid.get(key) ?: continue
        val m = mask?.tiles?.get(key)
        if (m == null) {
            val k = opacity * (mask?.let { maskEffective(it.tiles.defaultValue.toInt() and 0xFF, it.density) } ?: 1f)
            if (k <= 0f) { grid.put(key, null); continue }
            if (k < 1f) t.scale(k)
        } else {
            var i = 0
            for (p in 0 until pixels) {
                val k = opacity * maskEffective(m.data[p].toInt() and 0xFF, mask.density)
                if (k < 1f) for (c in 0..3) t[i + c] = t[i + c] * k
                i += 4
            }
        }
        t.markDirty()
    }
    grid.compact()
}

/** A copy of [grid] multiplied by [mask] when it is enabled ([grid] itself otherwise). */
internal fun masked(grid: TileGrid, mask: LayerMask?): TileGrid {
    if (mask == null || !mask.enabled) return grid
    val out = grid.copy()
    bake(out, 1f, mask)
    return out
}

/** Copies the current mask tiles at [keys] (null where absent). */
fun snapshotMaskTiles(grid: MaskGrid, keys: Collection<TileKey>): Map<TileKey, MaskTile?> {
    val out = LinkedHashMap<TileKey, MaskTile?>(keys.size * 2)
    for (k in keys) out[k] = grid.get(k)?.copy()
    return out
}

internal fun writeMaskTiles(grid: MaskGrid, tiles: Map<TileKey, MaskTile?>) {
    for ((k, t) in tiles) grid.put(k, t?.copy()?.also { it.markDirty() })
}

/** Rebuilds [grid] from [tiles] and [default]. */
internal fun restoreMask(grid: MaskGrid, default: Byte, tiles: Map<TileKey, MaskTile?>) {
    grid.clear()
    grid.defaultValue = default
    writeMaskTiles(grid, tiles)
}

/** Whether [n] is a raster layer or group (the kinds that may be a reference). */
internal fun canBeReference(n: Node): Boolean = n !is AdjustmentLayer
