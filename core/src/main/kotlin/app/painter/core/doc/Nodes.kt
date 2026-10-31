package app.painter.core.doc

import app.painter.core.adjust.AdjustParams
import app.painter.core.blend.BlendMode
import app.painter.core.color.Rgba
import app.painter.core.raster.TileGrid
import java.util.UUID

/** Base of every entry in the layer stack (spec/document-model.md). */
sealed class Node(
    val id: String,
    var name: String,
) {
    var visible: Boolean = true
    var opacity: Float = 1f
    var blendMode: BlendMode = BlendMode.NORMAL
    /** Clipped to the nearest non-clipped node below it. */
    var clipped: Boolean = false
    var hideInRecording: Boolean = false
    var locked: Boolean = false
    /** Optional raster mask (layers, groups and adjustment layers). */
    var mask: LayerMask? = null
    var colorTag: ColorTag = ColorTag.NONE
    /** Reference node for fill / wand (at most one per document). */
    var reference: Boolean = false
    /** Parent group; null only for the document root. */
    var parent: GroupNode? = null
        internal set

    abstract fun deepCopy(newId: String = newNodeId()): Node

    protected fun copyBaseTo(n: Node) {
        n.visible = visible; n.opacity = opacity; n.blendMode = blendMode
        n.clipped = clipped; n.hideInRecording = hideInRecording; n.locked = locked
        n.mask = mask?.deepCopy(); n.colorTag = colorTag
    }
}

class RasterLayer(
    id: String,
    name: String,
    /** Pixel grid; the canvas command replaces it with a resampled grid. */
    var pixels: TileGrid,
) : Node(id, name) {
    var alphaLock: Boolean = false

    override fun deepCopy(newId: String): RasterLayer =
        RasterLayer(newId, name, pixels.copy()).also { copyBaseTo(it); it.alphaLock = alphaLock }
}

/**
 * Non-destructive correction applied to everything below it within its parent (spec/tools/m2-layers-adjust.md L5).
 * Strength is the node opacity; the blend mode mixes `adj(below)` against `below`. Never a clip base.
 */
class AdjustmentLayer(
    id: String,
    name: String,
    var params: AdjustParams,
) : Node(id, name) {
    override fun deepCopy(newId: String): AdjustmentLayer =
        AdjustmentLayer(newId, name, params).also { copyBaseTo(it) }
}

class GroupNode(
    id: String,
    name: String,
) : Node(id, name) {
    private val _children = ArrayList<Node>()
    /** Bottom-to-top order: index 0 is the lowest layer. */
    val children: List<Node> get() = _children
    var expanded: Boolean = true

    init { blendMode = BlendMode.PASSTHROUGH }

    fun add(node: Node, index: Int = _children.size) {
        require(node.parent == null) { "node already has a parent" }
        _children.add(index, node)
        node.parent = this
    }

    fun remove(node: Node): Int {
        val i = _children.indexOf(node)
        require(i >= 0)
        _children.removeAt(i)
        node.parent = null
        return i
    }

    fun indexOf(node: Node) = _children.indexOf(node)

    override fun deepCopy(newId: String): GroupNode {
        val g = GroupNode(newId, name)
        copyBaseTo(g); g.expanded = expanded
        for (c in _children) g.add(c.deepCopy())
        return g
    }

    /** Depth-first walk, bottom-to-top, including nested groups. */
    fun walk(visit: (Node) -> Unit) {
        for (c in _children) { visit(c); if (c is GroupNode) c.walk(visit) }
    }
}

data class Paper(
    var color: Rgba = Rgba.WHITE,
    var visible: Boolean = true,
    var textureId: String? = null,
    var textureDepth: Float = 0.5f,
    var textureOpacity: Float = 0.5f,
    var textureScale: Float = 1f,
)

fun newNodeId(): String = UUID.randomUUID().toString()

/** True when this node or any ancestor is locked (spec L7: lock of a group applies to all descendants). */
val Node.effectivelyLocked: Boolean
    get() {
        var n: Node? = this
        while (n != null) { if (n.locked) return true; n = n.parent }
        return false
    }

/** True when this node, an ancestor or (for groups) a descendant is locked. */
val Node.touchesLocked: Boolean
    get() {
        if (effectivelyLocked) return true
        var found = false
        if (this is GroupNode) walk { if (it.locked) found = true }
        return found
    }
