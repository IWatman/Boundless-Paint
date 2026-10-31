package app.painter.core.history

import app.painter.core.blend.BlendMode
import app.painter.core.doc.ColorTag
import app.painter.core.doc.Document
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.doc.VectorLayer
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import app.painter.core.vector.VectorStroke

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
 * Removes node [nodeId] with its subtree (single-node [DeleteNodes]). If the active node is removed, the
 * neighbor below (else above, else the parent group) becomes active.
 */
class DeleteNode(
    val nodeId: String,
    override val name: String = "Delete layer",
) : Command by DeleteNodes(listOf(nodeId), name)

/**
 * Moves node [nodeId] into group [newParentId] (null = root) at [newIndex], where the index is
 * counted in the target's children after the node has been taken out (single-node [MoveNodes]).
 */
class MoveNode(
    val nodeId: String,
    val newParentId: String?,
    val newIndex: Int,
    override val name: String = "Move layer",
) : Command by MoveNodes(listOf(nodeId), newParentId, newIndex, name)

/** Deep-copies node [nodeId], places the copy directly above it and makes it active. */
class DuplicateNode(
    val nodeId: String,
    override val name: String = "Duplicate layer",
) : Command {
    private val inner = DuplicateNodes(listOf(nodeId), name)

    /** Id of the created copy, available after the first [apply]. */
    val copyId: String? get() = inner.copyIds.firstOrNull()

    override fun apply(doc: Document) = inner.apply(doc)
    override fun revert(doc: Document) = inner.revert(doc)
    override val byteSize: Long get() = inner.byteSize
}

/**
 * Changes node properties; null arguments are left untouched. [alphaLock] applies to raster layers only.
 * Setting [reference] to true clears the flag on the previous reference node in the same step; adjustment
 * layers cannot be a reference. Properties are allowed on locked nodes (spec L7).
 */
class SetNodeProps(
    val nodeId: String,
    val newName: String? = null,
    val visible: Boolean? = null,
    val opacity: Float? = null,
    val blendMode: BlendMode? = null,
    val clipped: Boolean? = null,
    val alphaLock: Boolean? = null,
    val colorTag: ColorTag? = null,
    val reference: Boolean? = null,
    val locked: Boolean? = null,
    val hideInRecording: Boolean? = null,
    override val name: String = "Layer properties",
) : Command {
    private var old: Props? = null
    private var oldReferenceId: String? = null

    private data class Props(
        val name: String,
        val visible: Boolean,
        val opacity: Float,
        val blendMode: BlendMode,
        val clipped: Boolean,
        val alphaLock: Boolean?,
        val colorTag: ColorTag,
        val reference: Boolean,
        val locked: Boolean,
        val hideInRecording: Boolean,
    )

    override fun apply(doc: Document) {
        val n = doc.node(nodeId)
        require(reference != true || canBeReference(n)) { "an adjustment layer cannot be a reference" }
        old = Props(
            n.name, n.visible, n.opacity, n.blendMode, n.clipped, (n as? RasterLayer)?.alphaLock,
            n.colorTag, n.reference, n.locked, n.hideInRecording,
        )
        oldReferenceId = null
        if (reference == true) {
            val prev = doc.referenceNode
            if (prev != null && prev !== n) { prev.reference = false; oldReferenceId = prev.id }
        }
        newName?.let { n.name = it }
        visible?.let { n.visible = it }
        opacity?.let { n.opacity = it.coerceIn(0f, 1f) }
        blendMode?.let { n.blendMode = it }
        clipped?.let { n.clipped = it }
        if (n is RasterLayer) alphaLock?.let { n.alphaLock = it }
        colorTag?.let { n.colorTag = it }
        reference?.let { n.reference = it }
        locked?.let { n.locked = it }
        hideInRecording?.let { n.hideInRecording = it }
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
        n.colorTag = p.colorTag
        n.reference = p.reference
        n.locked = p.locked
        n.hideInRecording = p.hideInRecording
        oldReferenceId?.let { doc.findNode(it)?.reference = true }
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES
}

/** Removes all pixels, and the vector paths of a [VectorLayer], of raster layer [layerId]. */
class ClearLayer(
    val layerId: String,
    override val name: String = "Clear layer",
) : Command {
    private var before: Map<TileKey, Tile?>? = null
    private var beforeStrokes: List<VectorStroke>? = null

    override fun apply(doc: Document) {
        val layer = doc.raster(layerId)
        requireUnlocked(layer)
        val grid = layer.pixels
        if (before == null) before = snapshotTiles(grid, grid.keys().toList())
        if (layer is VectorLayer && beforeStrokes == null) beforeStrokes = layer.strokes.toList()
        grid.clear()
        (layer as? VectorLayer)?.setStrokes(emptyList())
        doc.touch()
    }

    override fun revert(doc: Document) {
        val layer = doc.raster(layerId)
        val grid = layer.pixels
        grid.clear()
        before?.let { writeTiles(grid, it) }
        if (layer is VectorLayer) beforeStrokes?.let(layer::setStrokes)
        doc.touch()
    }

    override val byteSize: Long
        get() = STRUCT_BYTES + tileBytes(before?.size ?: 0) +
            (beforeStrokes?.sumOf { it.points.size * 32L } ?: 0L)
}
