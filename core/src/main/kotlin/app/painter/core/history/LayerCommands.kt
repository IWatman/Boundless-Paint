package app.painter.core.history

import app.painter.core.blend.BlendMode
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey

private const val STRUCT_BYTES = 64L

private fun Document.group(id: String?): GroupNode =
    if (id == null || id == root.id) root
    else findNode(id) as? GroupNode ?: error("group $id not found")

private fun Document.node(id: String): Node = findNode(id) ?: error("node $id not found")

private fun Document.raster(id: String): RasterLayer =
    findNode(id) as? RasterLayer ?: error("raster layer $id not found")

private fun Node.contains(id: String): Boolean {
    if (this.id == id) return true
    var found = false
    if (this is GroupNode) walk { if (it.id == id) found = true }
    return found
}

private fun nodeBytes(node: Node): Long {
    var tiles = 0
    when (node) {
        is RasterLayer -> tiles = node.pixels.keys().size
        is GroupNode -> node.walk { if (it is RasterLayer) tiles += it.pixels.keys().size }
    }
    return STRUCT_BYTES + tileBytes(tiles)
}

/**
 * Inserts [node] into the group [parentId] (null = root) at [index] (null = top) and makes it
 * active.
 */
class AddLayer(
    val node: Node,
    val parentId: String? = null,
    val index: Int? = null,
    override val name: String = "Add layer",
) : Command {
    private var prevActive: String? = null

    override fun apply(doc: Document) {
        val parent = doc.group(parentId)
        prevActive = doc.activeId
        parent.add(node, (index ?: parent.children.size).coerceIn(0, parent.children.size))
        doc.activeId = node.id
        doc.touch()
    }

    override fun revert(doc: Document) {
        node.parent?.remove(node)
        doc.activeId = prevActive
        doc.touch()
    }

    override val byteSize: Long get() = nodeBytes(node)
}

/**
 * Removes node [nodeId] with its subtree. If the active node is removed, the neighbor below
 * (else above, else the parent group) becomes active.
 */
class DeleteNode(
    val nodeId: String,
    override val name: String = "Delete layer",
) : Command {
    private var node: Node? = null
    private var parentId: String? = null
    private var index = 0
    private var prevActive: String? = null

    override fun apply(doc: Document) {
        val n = doc.node(nodeId)
        val parent = n.parent ?: error("cannot delete root")
        node = n
        parentId = parent.id
        prevActive = doc.activeId
        index = parent.remove(n)
        val active = doc.activeId
        if (active == null || n.contains(active)) {
            doc.activeId = parent.children.getOrNull(index - 1)?.id
                ?: parent.children.getOrNull(index)?.id
                ?: parent.takeIf { it !== doc.root }?.id
                ?: topLayer(doc.root)?.id
        }
        doc.touch()
    }

    override fun revert(doc: Document) {
        val n = node ?: return
        doc.group(parentId).add(n, index)
        doc.activeId = prevActive
        doc.touch()
    }

    override val byteSize: Long get() = node?.let(::nodeBytes) ?: STRUCT_BYTES

    private fun topLayer(root: GroupNode): Node? {
        var last: Node? = null
        root.walk { if (it !is GroupNode) last = it }
        return last ?: root.children.lastOrNull()
    }
}

/**
 * Moves node [nodeId] into group [newParentId] (null = root) at [newIndex], where the index is
 * counted in the target's children after the node has been taken out.
 */
class MoveNode(
    val nodeId: String,
    val newParentId: String?,
    val newIndex: Int,
    override val name: String = "Move layer",
) : Command {
    private var oldParentId: String? = null
    private var oldIndex = 0

    override fun apply(doc: Document) {
        val n = doc.node(nodeId)
        val target = doc.group(newParentId)
        require(!n.contains(target.id)) { "cannot move a group into itself" }
        val old = n.parent ?: error("cannot move root")
        oldParentId = old.id
        oldIndex = old.remove(n)
        target.add(n, newIndex.coerceIn(0, target.children.size))
        doc.touch()
    }

    override fun revert(doc: Document) {
        val n = doc.node(nodeId)
        n.parent?.remove(n)
        doc.group(oldParentId).add(n, oldIndex)
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES
}

/** Deep-copies node [nodeId], places the copy directly above it and makes it active. */
class DuplicateNode(
    val nodeId: String,
    override val name: String = "Duplicate layer",
) : Command {
    private var copy: Node? = null
    private var prevActive: String? = null

    /** Id of the created copy, available after the first [apply]. */
    val copyId: String? get() = copy?.id

    override fun apply(doc: Document) {
        val src = doc.node(nodeId)
        val parent = src.parent ?: error("cannot duplicate root")
        val c = copy ?: src.deepCopy().also { it.name = src.name + " copy"; copy = it }
        prevActive = doc.activeId
        parent.add(c, parent.indexOf(src) + 1)
        doc.activeId = c.id
        doc.touch()
    }

    override fun revert(doc: Document) {
        val c = copy ?: return
        c.parent?.remove(c)
        doc.activeId = prevActive
        doc.touch()
    }

    override val byteSize: Long get() = copy?.let(::nodeBytes) ?: STRUCT_BYTES
}

/**
 * Changes node properties; null arguments are left untouched. [alphaLock] applies to raster
 * layers only.
 */
class SetNodeProps(
    val nodeId: String,
    val newName: String? = null,
    val visible: Boolean? = null,
    val opacity: Float? = null,
    val blendMode: BlendMode? = null,
    val clipped: Boolean? = null,
    val alphaLock: Boolean? = null,
    override val name: String = "Layer properties",
) : Command {
    private var old: Props? = null

    private data class Props(
        val name: String,
        val visible: Boolean,
        val opacity: Float,
        val blendMode: BlendMode,
        val clipped: Boolean,
        val alphaLock: Boolean?,
    )

    override fun apply(doc: Document) {
        val n = doc.node(nodeId)
        old = Props(n.name, n.visible, n.opacity, n.blendMode, n.clipped, (n as? RasterLayer)?.alphaLock)
        newName?.let { n.name = it }
        visible?.let { n.visible = it }
        opacity?.let { n.opacity = it.coerceIn(0f, 1f) }
        blendMode?.let { n.blendMode = it }
        clipped?.let { n.clipped = it }
        if (n is RasterLayer) alphaLock?.let { n.alphaLock = it }
        doc.touch()
    }

    override fun revert(doc: Document) {
        val p = old ?: return
        val n = doc.node(nodeId)
        n.name = p.name
        n.visible = p.visible
        n.opacity = p.opacity
        n.blendMode = p.blendMode
        n.clipped = p.clipped
        if (n is RasterLayer && p.alphaLock != null) n.alphaLock = p.alphaLock
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES
}

/** Removes all pixels of raster layer [layerId]. */
class ClearLayer(
    val layerId: String,
    override val name: String = "Clear layer",
) : Command {
    private var before: Map<TileKey, Tile?>? = null

    override fun apply(doc: Document) {
        val grid = doc.raster(layerId).pixels
        if (before == null) before = snapshotTiles(grid, grid.keys().toList())
        grid.clear()
        doc.touch()
    }

    override fun revert(doc: Document) {
        val grid = doc.raster(layerId).pixels
        grid.clear()
        before?.let { writeTiles(grid, it) }
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES + tileBytes(before?.size ?: 0)
}

/**
 * Merges raster layer [layerId] into the raster sibling directly below it. The result keeps the
 * lower layer's id, name and blend mode with opacity 100%: the lower layer's opacity is baked
 * into its pixels, then the upper layer is composited over it via [blender] with its own blend
 * mode and opacity. Visibility and clipping are not taken into account.
 */
class MergeDown(
    val layerId: String,
    val blender: (dst: TileGrid, src: TileGrid, mode: BlendMode, opacity: Float) -> Unit,
    override val name: String = "Merge down",
) : Command {
    private var upper: RasterLayer? = null
    private var lowerId: String? = null
    private var parentId: String? = null
    private var upperIndex = 0
    private var lowerBefore: Map<TileKey, Tile?>? = null
    private var lowerOpacity = 1f
    private var prevActive: String? = null

    /** Id of the layer that receives the merge, available after the first [apply]. */
    val targetId: String? get() = lowerId

    override fun apply(doc: Document) {
        val up = doc.raster(layerId)
        val parent = up.parent ?: error("layer has no parent")
        val idx = parent.indexOf(up)
        val low = parent.children.getOrNull(idx - 1) as? RasterLayer
            ?: error("no raster layer below $layerId")
        upper = up
        lowerId = low.id
        parentId = parent.id
        upperIndex = idx
        lowerOpacity = low.opacity
        prevActive = doc.activeId
        if (lowerBefore == null) lowerBefore = snapshotTiles(low.pixels, low.pixels.keys().toList())

        val grid = low.pixels
        if (lowerOpacity != 1f) {
            for ((_, t) in grid.entries()) {
                val d = t.data
                for (i in d.indices) d[i] *= lowerOpacity
                t.markDirty()
            }
        }
        blender(grid, up.pixels, up.blendMode, up.opacity)
        grid.compact()
        low.opacity = 1f
        parent.remove(up)
        doc.activeId = low.id
        doc.touch()
    }

    override fun revert(doc: Document) {
        val up = upper ?: return
        val low = doc.raster(lowerId!!)
        low.pixels.clear()
        lowerBefore?.let { writeTiles(low.pixels, it) }
        low.opacity = lowerOpacity
        doc.group(parentId).add(up, upperIndex)
        doc.activeId = prevActive
        doc.touch()
    }

    override val byteSize: Long
        get() = (upper?.let(::nodeBytes) ?: STRUCT_BYTES) + tileBytes(lowerBefore?.size ?: 0)
}
