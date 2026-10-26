package app.painter.core.history

import app.painter.core.blend.BlendMode
import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.LayerMask
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.doc.newNodeId
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey

/**
 * Merges node [layerId] (raster, group or adjustment) into the node directly below it. The lower node's
 * opacity and mask are baked into its pixels first; then the upper node is rendered over it with its own blend
 * mode, opacity, mask and clip (to the lower node) via [renderer]. An adjustment on top is baked into the
 * lower pixels. A lower group is flattened first into a new raster layer that keeps the group's name, mode
 * (passthrough becomes normal), clip and visibility. The result has opacity 100% and no mask; a lower raster
 * keeps its id. The lower node cannot be an adjustment layer; locked nodes cannot merge.
 */
class MergeDown private constructor(
    val layerId: String,
    private val renderer: NodeRenderer,
    private val legacy: Boolean,
    override val name: String,
) : Command {
    constructor(layerId: String, renderer: NodeRenderer, name: String = "Merge down") :
        this(layerId, renderer, false, name)

    /**
     * M1 form: [blender] composites one raster grid onto another. Only raster-on-raster merges are possible and
     * clipping is ignored.
     */
    constructor(
        layerId: String,
        blender: (dst: TileGrid, src: TileGrid, mode: BlendMode, opacity: Float) -> Unit,
        name: String = "Merge down",
    ) : this(layerId, LegacyRenderer(blender), true, name)

    private var upper: Node? = null
    private var lower: Node? = null
    private var result: RasterLayer? = null
    private var parentId: String? = null
    private var upperIndex = 0
    private var lowerBefore: Map<TileKey, Tile?>? = null
    private var lowerOpacity = 1f
    private var lowerMask: LayerMask? = null
    private var prevActive: String? = null

    /** Id of the layer that receives the merge, available after the first [apply]. */
    val targetId: String? get() = result?.id

    override fun apply(doc: Document) {
        val up = doc.node(layerId)
        if (legacy && up !is RasterLayer) error("raster layer $layerId not found")
        val parent = up.parent ?: error("layer has no parent")
        val idx = parent.indexOf(up)
        val low = parent.children.getOrNull(idx - 1) ?: error("no layer below $layerId")
        if (legacy && low !is RasterLayer) error("no raster layer below $layerId")
        if (low is AdjustmentLayer) error("cannot merge onto an adjustment layer")
        requireTreeUnlocked(up)
        requireTreeUnlocked(low)
        upper = up
        lower = low
        parentId = parent.id
        upperIndex = idx
        prevActive = doc.activeId

        var fresh = true
        val target: RasterLayer = when (low) {
            is RasterLayer -> {
                lowerOpacity = low.opacity
                lowerMask = low.mask
                if (lowerBefore == null) lowerBefore = snapshotTiles(low.pixels, low.pixels.keys().toList())
                low
            }
            is GroupNode -> result?.also { fresh = false } ?: flattenedCopy(low, doc, renderer)
            is AdjustmentLayer -> error("unreachable")
        }
        if (fresh) {
            val grid = target.pixels
            val mask = target.mask?.takeIf { it.enabled }
            val clipBase = if (up.clipped) masked(grid, mask).let { if (it === grid) grid.copy() else it } else null
            bake(grid, target.opacity, mask)
            renderer.renderInto(grid, listOf(up), ignoreVisibility = true, clipBase = clipBase)
            grid.compact()
        }
        target.opacity = 1f
        target.mask = null
        parent.remove(up)
        if (target !== low) {
            val i = parent.remove(low)
            parent.add(target, i)
        }
        result = target
        doc.activeId = target.id
        doc.touch()
    }

    override fun revert(doc: Document) {
        val up = upper ?: return
        val low = lower ?: return
        val parent = doc.group(parentId)
        val res = result!!
        if (res === low) {
            low as RasterLayer
            low.pixels.clear()
            lowerBefore?.let { writeTiles(low.pixels, it) }
            low.opacity = lowerOpacity
            low.mask = lowerMask
        } else {
            val i = parent.remove(res)
            parent.add(low, i)
        }
        parent.add(up, upperIndex)
        doc.activeId = prevActive
        doc.touch()
    }

    override val byteSize: Long
        get() = (upper?.let(::nodeBytes) ?: STRUCT_BYTES) + tileBytes(lowerBefore?.size ?: 0) +
            (result?.takeIf { it !== lower }?.let(::nodeBytes) ?: 0L) + maskBytes(lowerMask)

    private class LegacyRenderer(
        val blender: (dst: TileGrid, src: TileGrid, mode: BlendMode, opacity: Float) -> Unit,
    ) : NodeRenderer {
        override fun renderInto(dst: TileGrid, nodes: List<Node>, ignoreVisibility: Boolean, clipBase: TileGrid?) {
            for (n in nodes) {
                val r = n as? RasterLayer ?: error("legacy blender merges raster layers only")
                blender(dst, masked(r.pixels, r.mask), r.blendMode, r.opacity)
            }
        }
    }
}

/**
 * A new raster layer (new id) holding the isolated render of [group]'s children, carrying the group's name,
 * opacity, mode (passthrough → normal), clip, visibility, tag and a copy of its mask.
 */
private fun flattenedCopy(group: GroupNode, doc: Document, renderer: NodeRenderer): RasterLayer {
    val grid = TileGrid(doc.width, doc.height)
    renderer.renderInto(grid, group.children)
    grid.compact()
    return RasterLayer(newNodeId(), group.name, grid).also {
        it.opacity = group.opacity
        it.blendMode = if (group.blendMode == BlendMode.PASSTHROUGH) BlendMode.NORMAL else group.blendMode
        it.clipped = group.clipped
        it.visible = group.visible
        it.hideInRecording = group.hideInRecording
        it.colorTag = group.colorTag
        it.mask = group.mask?.deepCopy()
    }
}

/**
 * Renders the nodes [ids] (stack order; each with its mode, opacity and mask; clipped to its base only when the
 * base is merged too; adjustments act only on merged nodes below them) onto transparent, into a new raster layer
 * at the place and with the name of the top-most one (normal, 100%). The merged nodes are removed; the new layer
 * becomes active. Hidden listed nodes are rendered as visible.
 */
class MergeNodes(
    val ids: List<String>,
    private val renderer: NodeRenderer,
    override val name: String = "Merge layers",
) : Command {
    private var result: RasterLayer? = null
    private var slots: List<Slot> = emptyList()
    private var prevActive: String? = null

    /** Id of the merged layer, available after the first [apply]. */
    val resultId: String? get() = result?.id

    override fun apply(doc: Document) {
        val nodes = doc.normalize(ids)
        require(nodes.size >= 2) { "merge needs at least two nodes" }
        for (n in nodes) requireTreeUnlocked(n)
        val top = nodes.last()
        val parent = top.parent ?: error("cannot merge root")
        val at = remainingBelow(parent, parent.indexOf(top), nodes)
        val res = result ?: RasterLayer(newNodeId(), top.name, render(doc, nodes)).also { result = it }
        prevActive = doc.activeId
        slots = removeAll(nodes)
        parent.add(res, at)
        doc.activeId = res.id
        doc.touch()
    }

    override fun revert(doc: Document) {
        val res = result ?: return
        res.parent?.remove(res)
        doc.restoreAll(slots)
        doc.activeId = prevActive
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES + (result?.let(::nodeBytes) ?: 0L) + slots.sumOf { nodeBytes(it.node) }

    private fun render(doc: Document, nodes: List<Node>): TileGrid {
        val unclip = ArrayList<Node>()
        for ((i, n) in nodes.withIndex()) {
            if (!n.clipped) continue
            val listBase = (i - 1 downTo 0).map { nodes[it] }.firstOrNull { !it.clipped }
            if (listBase == null || listBase !== realBase(n)) unclip.add(n)
        }
        val grid = TileGrid(doc.width, doc.height)
        try {
            for (n in unclip) n.clipped = false
            renderer.renderInto(grid, nodes, ignoreVisibility = true)
        } finally {
            for (n in unclip) n.clipped = true
        }
        grid.compact()
        return grid
    }

    private fun realBase(n: Node): Node? {
        val p = n.parent ?: return null
        for (j in p.indexOf(n) - 1 downTo 0) if (!p.children[j].clipped) return p.children[j]
        return null
    }
}

/**
 * Replaces group [groupId] by one raster layer holding the isolated render of its children. Blend mode
 * (passthrough → normal), opacity, clip and mask move to the new layer. [mayChangeLook] tells the UI to warn.
 */
class FlattenGroup(
    val groupId: String,
    private val renderer: NodeRenderer,
    override val name: String = "Flatten group",
) : Command {
    private var group: GroupNode? = null
    private var result: RasterLayer? = null
    private var prevActive: String? = null

    /** Id of the new layer, available after the first [apply]. */
    val resultId: String? get() = result?.id

    /**
     * True when the group was passthrough with adjustment layers or masking/erasing modes inside: the isolated
     * result may then look different from before.
     */
    var mayChangeLook: Boolean = false
        private set

    override fun apply(doc: Document) {
        val g = doc.findNode(groupId) as? GroupNode ?: error("group $groupId not found")
        val parent = g.parent ?: error("cannot flatten root")
        requireTreeUnlocked(g)
        if (g.blendMode == BlendMode.PASSTHROUGH) {
            g.walk {
                if (it is AdjustmentLayer || it.blendMode.group == BlendMode.Group.MASKING) mayChangeLook = true
            }
        }
        group = g
        prevActive = doc.activeId
        val res = result ?: flattenedCopy(g, doc, renderer).also { result = it }
        val i = parent.remove(g)
        parent.add(res, i)
        val active = doc.activeId
        if (active != null && g.contains(active)) doc.activeId = res.id
        doc.touch()
    }

    override fun revert(doc: Document) {
        val res = result ?: return
        val parent = res.parent ?: return
        val i = parent.remove(res)
        parent.add(group!!, i)
        doc.activeId = prevActive
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES + (group?.let(::nodeBytes) ?: 0L) + (result?.let(::nodeBytes) ?: 0L)
}

/**
 * Replaces the whole layer stack by one raster layer named [layerName] holding the visible composite (without
 * paper). Hidden nodes are dropped. Fails if any node is locked.
 */
class FlattenDocument(
    private val renderer: NodeRenderer,
    val layerName: String = "Layer 1",
    override val name: String = "Flatten image",
) : Command {
    private var old: List<Node> = emptyList()
    private var result: RasterLayer? = null
    private var prevActive: String? = null

    /** Id of the new layer, available after the first [apply]. */
    val resultId: String? get() = result?.id

    override fun apply(doc: Document) {
        for (c in doc.root.children) requireTreeUnlocked(c)
        val res = result ?: run {
            val grid = TileGrid(doc.width, doc.height)
            renderer.renderInto(grid, doc.root.children)
            grid.compact()
            RasterLayer(newNodeId(), layerName, grid)
        }.also { result = it }
        prevActive = doc.activeId
        old = doc.root.children.toList()
        for (c in old) doc.root.remove(c)
        doc.root.add(res)
        doc.activeId = res.id
        doc.touch()
    }

    override fun revert(doc: Document) {
        val res = result ?: return
        doc.root.remove(res)
        for (c in old) doc.root.add(c)
        doc.activeId = prevActive
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES + old.sumOf(::nodeBytes) + (result?.let(::nodeBytes) ?: 0L)
}

/**
 * Adds a raster layer directly above group [groupId] holding the isolated composite of its children (normal,
 * 100%); the group is unchanged. The new layer becomes active.
 */
class CopyMerged(
    val groupId: String,
    private val renderer: NodeRenderer,
    val layerName: String? = null,
    override val name: String = "Copy merged",
) : Command {
    private var result: RasterLayer? = null
    private var prevActive: String? = null

    /** Id of the new layer, available after the first [apply]. */
    val resultId: String? get() = result?.id

    override fun apply(doc: Document) {
        val g = doc.findNode(groupId) as? GroupNode ?: error("group $groupId not found")
        val parent = g.parent ?: error("cannot copy root")
        val res = result ?: run {
            val grid = TileGrid(doc.width, doc.height)
            renderer.renderInto(grid, g.children)
            grid.compact()
            RasterLayer(newNodeId(), layerName ?: "${g.name} merged", grid)
        }.also { result = it }
        prevActive = doc.activeId
        parent.add(res, parent.indexOf(g) + 1)
        doc.activeId = res.id
        doc.touch()
    }

    override fun revert(doc: Document) {
        val res = result ?: return
        res.parent?.remove(res)
        doc.activeId = prevActive
        doc.touch()
    }

    override val byteSize: Long get() = result?.let(::nodeBytes) ?: STRUCT_BYTES
}
