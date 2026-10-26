package app.painter.core.history

import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.Document
import app.painter.core.doc.EditTarget
import app.painter.core.doc.GroupNode
import app.painter.core.doc.LayerMask
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskPatch
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey

/** How [AddMask] initializes a new mask (spec L3 «Создание»). */
enum class MaskInit {
    /** Default 255, no tiles. */
    REVEAL_ALL,

    /** Default 0, no tiles. */
    HIDE_ALL,

    /** Copy of the document selection mask. */
    FROM_SELECTION,

    /** m = alpha of the layer (of the isolated render for groups). Not for adjustment layers. */
    FROM_ALPHA,
}

private fun Document.masked(id: String): Pair<Node, LayerMask> {
    val n = node(id)
    return n to (n.mask ?: error("node $id has no mask"))
}

/**
 * Adds a mask initialized by [init] to node [nodeId] (which must have none) and makes the mask the active
 * edit target. [FROM_ALPHA][MaskInit.FROM_ALPHA] on a group needs [renderer].
 */
class AddMask(
    val nodeId: String,
    val init: MaskInit = MaskInit.REVEAL_ALL,
    private val renderer: NodeRenderer? = null,
    override val name: String = "Add mask",
) : Command {
    private var mask: LayerMask? = null
    private var prevTarget = EditTarget.LAYER

    override fun apply(doc: Document) {
        val n = doc.node(nodeId)
        require(n.mask == null) { "node $nodeId already has a mask" }
        requireUnlocked(n)
        val m = mask ?: LayerMask(build(doc, n)).also { mask = it }
        n.mask = m
        prevTarget = doc.activeTarget
        doc.activeTarget = EditTarget.MASK
        doc.touch()
    }

    override fun revert(doc: Document) {
        doc.node(nodeId).mask = null
        doc.activeTarget = prevTarget
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES + maskBytes(mask)

    private fun build(doc: Document, n: Node): MaskGrid = when (init) {
        MaskInit.REVEAL_ALL -> MaskGrid(doc.width, doc.height, -1)
        MaskInit.HIDE_ALL -> MaskGrid(doc.width, doc.height, 0)
        MaskInit.FROM_SELECTION -> doc.selection.mask.copy().also { it.defaultValue = 0; it.compact() }
        MaskInit.FROM_ALPHA -> {
            val src = when (n) {
                is RasterLayer -> n.pixels
                is GroupNode -> TileGrid(doc.width, doc.height).also {
                    requireNotNull(renderer) { "group alpha needs a renderer" }.renderInto(it, n.children)
                }
                is AdjustmentLayer -> error("an adjustment layer has no alpha")
            }
            alphaMask(src)
        }
    }

    private fun alphaMask(src: TileGrid): MaskGrid {
        val out = MaskGrid(src.width, src.height, 0)
        for ((k, t) in src.entries()) {
            if (t.isEmpty()) continue
            val m = MaskTile()
            var i = 3
            for (p in 0 until Tile.SIZE * Tile.SIZE) {
                m.data[p] = (t[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                i += 4
            }
            out.put(k, m)
        }
        return out
    }
}

/** Removes the mask of node [nodeId] without applying it. The edit target falls back to the layer. */
class DeleteMask(
    val nodeId: String,
    override val name: String = "Delete mask",
) : Command {
    private var mask: LayerMask? = null
    private var prevTarget = EditTarget.LAYER

    override fun apply(doc: Document) {
        val (n, m) = doc.masked(nodeId)
        requireUnlocked(n)
        mask = m
        n.mask = null
        prevTarget = doc.activeTarget
        if (doc.activeId == nodeId) doc.activeTarget = EditTarget.LAYER
        doc.touch()
    }

    override fun revert(doc: Document) {
        doc.node(nodeId).mask = mask
        doc.activeTarget = prevTarget
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES + maskBytes(mask)
}

/**
 * Bakes the mask of node [nodeId] into pixels and removes it: raster `rgba *= m_eff`; group: into every child,
 * which must all be raster layers ([isAvailable]). Not available for adjustment layers.
 */
class ApplyMask(
    val nodeId: String,
    override val name: String = "Apply mask",
) : Command {
    private var mask: LayerMask? = null
    private var before: Map<String, Map<TileKey, Tile?>> = emptyMap()
    private var prevTarget = EditTarget.LAYER

    override fun apply(doc: Document) {
        val (n, m) = doc.masked(nodeId)
        require(isAvailable(n)) { "mask of $nodeId cannot be applied" }
        requireTreeUnlocked(n)
        val layers = if (n is GroupNode) n.children.map { it as RasterLayer } else listOf(n as RasterLayer)
        before = layers.associate { it.id to snapshotTiles(it.pixels, it.pixels.keys().toList()) }
        for (l in layers) bake(l.pixels, 1f, m)
        mask = m
        n.mask = null
        prevTarget = doc.activeTarget
        if (doc.activeId == nodeId) doc.activeTarget = EditTarget.LAYER
        doc.touch()
    }

    override fun revert(doc: Document) {
        val n = doc.node(nodeId)
        for ((id, tiles) in before) {
            val grid = doc.raster(id).pixels
            grid.clear()
            writeTiles(grid, tiles)
        }
        n.mask = mask
        doc.activeTarget = prevTarget
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES + maskBytes(mask) + tileBytes(before.values.sumOf { it.size })

    companion object {
        /** Whether [n]'s mask can be applied: raster, or a group of raster layers only. */
        fun isAvailable(n: Node): Boolean = n.mask != null && when (n) {
            is RasterLayer -> true
            is GroupNode -> n.children.all { it is RasterLayer }
            is AdjustmentLayer -> false
        }
    }
}

/** Tile change on the mask of node `patch.targetId` (stroke, fill, destructive correction on the mask). */
class MaskPatchCommand(
    val patch: MaskPatch,
    override val name: String = "Edit mask",
) : Command {
    init {
        require(patch.targetId != MaskPatch.SELECTION) { "selection patches belong to the selection commands" }
    }

    override fun apply(doc: Document) {
        val (n, m) = doc.masked(patch.targetId)
        requireUnlocked(n)
        writeMaskTiles(m.tiles, patch.after)
        doc.touch()
    }

    override fun revert(doc: Document) {
        writeMaskTiles(doc.masked(patch.targetId).second.tiles, patch.before)
        doc.touch()
    }

    override val byteSize: Long get() = patch.byteSize
}

/** Changes mask properties of node [nodeId]; null arguments are left untouched. */
class SetMaskProps(
    val nodeId: String,
    val enabled: Boolean? = null,
    val linked: Boolean? = null,
    val density: Float? = null,
    override val name: String = "Mask properties",
) : Command {
    private var old: Triple<Boolean, Boolean, Float>? = null

    override fun apply(doc: Document) {
        val (n, m) = doc.masked(nodeId)
        requireUnlocked(n)
        old = Triple(m.enabled, m.linked, m.density)
        enabled?.let { m.enabled = it }
        linked?.let { m.linked = it }
        density?.let { m.density = it.coerceIn(0f, 1f) }
        doc.touch()
    }

    override fun revert(doc: Document) {
        val o = old ?: return
        val m = doc.masked(nodeId).second
        m.enabled = o.first; m.linked = o.second; m.density = o.third
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES
}

/** Inverts the mask of node [nodeId] (tiles and default value). Its own inverse. */
class InvertMask(
    val nodeId: String,
    override val name: String = "Invert mask",
) : Command {
    override fun apply(doc: Document) {
        val (n, m) = doc.masked(nodeId)
        requireUnlocked(n)
        invert(m.tiles)
        doc.touch()
    }

    override fun revert(doc: Document) {
        invert(doc.masked(nodeId).second.tiles)
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES

    private fun invert(g: MaskGrid) {
        g.defaultValue = (255 - (g.defaultValue.toInt() and 0xFF)).toByte()
        for ((_, t) in g.entries()) {
            for (i in t.data.indices) t.data[i] = (255 - (t.data[i].toInt() and 0xFF)).toByte()
            t.markDirty()
        }
    }
}

/** Fills the whole mask of node [nodeId]: [reveal] = white (clear), else black (hide all). */
class FillMask(
    val nodeId: String,
    val reveal: Boolean,
    override val name: String = if (reveal) "Clear mask" else "Fill mask",
) : Command {
    private var oldDefault: Byte = 0
    private var oldTiles: Map<TileKey, MaskTile?> = emptyMap()

    override fun apply(doc: Document) {
        val (n, m) = doc.masked(nodeId)
        requireUnlocked(n)
        oldDefault = m.tiles.defaultValue
        oldTiles = snapshotMaskTiles(m.tiles, m.tiles.keys().toList())
        m.tiles.clear()
        m.tiles.defaultValue = if (reveal) -1 else 0
        doc.touch()
    }

    override fun revert(doc: Document) {
        restoreMask(doc.masked(nodeId).second.tiles, oldDefault, oldTiles)
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES + oldTiles.size * MASK_TILE_BYTES
}
