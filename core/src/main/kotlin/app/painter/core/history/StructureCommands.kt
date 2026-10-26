package app.painter.core.history

import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.LayerMask
import app.painter.core.doc.Node
import app.painter.core.doc.newNodeId
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import kotlin.math.roundToInt

/**
 * Removes the nodes [ids] with their subtrees (nodes inside a listed group go with it). Fails with
 * [LockedException] if any removed node, ancestor or descendant is locked. If the active node goes, the nearest
 * remaining sibling below (else above, else the parent group) becomes active.
 */
class DeleteNodes(
    val ids: List<String>,
    override val name: String = "Delete layers",
) : Command {
    private var slots: List<Slot> = emptyList()
    private var prevActive: String? = null

    override fun apply(doc: Document) {
        val nodes = doc.normalize(ids)
        require(nodes.isNotEmpty()) { "nothing to delete" }
        for (n in nodes) requireTreeUnlocked(n)
        prevActive = doc.activeId
        val nextActive = doc.activeAfterRemoval(nodes)
        slots = removeAll(nodes)
        doc.activeId = nextActive
        doc.touch()
    }

    override fun revert(doc: Document) {
        doc.restoreAll(slots)
        doc.activeId = prevActive
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES + slots.sumOf { nodeBytes(it.node) }
}

/**
 * Moves the nodes [ids] as one block into group [newParentId] (null = root) at [index], counted in the target's
 * children after the moved nodes have been taken out. Their relative stack order is kept. A group cannot move
 * into itself or a descendant.
 */
class MoveNodes(
    val ids: List<String>,
    val newParentId: String?,
    val index: Int,
    override val name: String = "Move layers",
) : Command {
    private var slots: List<Slot> = emptyList()
    private var moved: List<Node> = emptyList()

    override fun apply(doc: Document) {
        val nodes = doc.normalize(ids)
        require(nodes.isNotEmpty()) { "nothing to move" }
        val target = doc.group(newParentId)
        for (n in nodes) require(!n.contains(target.id)) { "cannot move a group into itself" }
        slots = removeAll(nodes)
        var at = index.coerceIn(0, target.children.size)
        for (n in nodes) target.add(n, at++)
        moved = nodes
        doc.touch()
    }

    override fun revert(doc: Document) {
        for (n in moved) n.parent?.remove(n)
        doc.restoreAll(slots)
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES
}

/** Deep-copies the nodes [ids]; each copy sits directly above its source. The top-most copy becomes active. */
class DuplicateNodes(
    val ids: List<String>,
    override val name: String = "Duplicate layers",
) : Command {
    private var copies: List<Node>? = null
    private var prevActive: String? = null

    /** Ids of the created copies in stack order, available after the first [apply]. */
    val copyIds: List<String> get() = copies?.map { it.id } ?: emptyList()

    override fun apply(doc: Document) {
        val nodes = doc.normalize(ids)
        require(nodes.isNotEmpty()) { "nothing to duplicate" }
        val cs = copies ?: nodes.map { src -> src.deepCopy().also { it.name = src.name + " copy" } }.also { copies = it }
        prevActive = doc.activeId
        for ((src, c) in nodes.zip(cs).asReversed()) {
            val parent = src.parent ?: error("cannot duplicate root")
            parent.add(c, parent.indexOf(src) + 1)
        }
        doc.activeId = cs.last().id
        doc.touch()
    }

    override fun revert(doc: Document) {
        copies?.forEach { it.parent?.remove(it) }
        doc.activeId = prevActive
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES + (copies?.sumOf(::nodeBytes) ?: 0L)
}

/**
 * Puts the nodes [ids] into a new passthrough group named [groupName]. The group takes the place of the top-most
 * node; the children keep their stack order. Nodes may come from different parents.
 */
class GroupNodes(
    val ids: List<String>,
    val groupName: String,
    val groupId: String = newNodeId(),
    override val name: String = "Group layers",
) : Command {
    private var group: GroupNode? = null
    private var slots: List<Slot> = emptyList()

    override fun apply(doc: Document) {
        val nodes = doc.normalize(ids)
        require(nodes.isNotEmpty()) { "nothing to group" }
        val top = nodes.last()
        val parent = top.parent ?: error("cannot group root")
        val at = remainingBelow(parent, parent.indexOf(top), nodes)
        val g = group ?: GroupNode(groupId, groupName).also { group = it }
        slots = removeAll(nodes)
        for (n in nodes) g.add(n)
        parent.add(g, at)
        doc.touch()
    }

    override fun revert(doc: Document) {
        val g = group ?: return
        g.parent?.remove(g)
        for (c in g.children.toList()) g.remove(c)
        doc.restoreAll(slots)
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES
}

/** What [Ungroup] does with the group's mask. */
enum class UngroupMaskPolicy {
    /** Children without a mask get a copy; children with a mask get the product of both. */
    APPLY_TO_EACH,

    /** The group mask is dropped. */
    DISCARD,
}

/**
 * Replaces group [groupId] by its children, in the same place and order. The group's opacity and blend mode are
 * lost; its mask is handled by [maskPolicy]. If the group was active, its top-most child becomes active.
 */
class Ungroup(
    val groupId: String,
    val maskPolicy: UngroupMaskPolicy = UngroupMaskPolicy.APPLY_TO_EACH,
    override val name: String = "Ungroup",
) : Command {
    private var group: GroupNode? = null
    private var parentId: String? = null
    private var index = 0
    private var children: List<Node> = emptyList()
    private var oldMasks: List<LayerMask?> = emptyList()
    private var newMasks: List<LayerMask?>? = null
    private var prevActive: String? = null

    override fun apply(doc: Document) {
        val g = doc.findNode(groupId) as? GroupNode ?: error("group $groupId not found")
        val parent = g.parent ?: error("cannot ungroup root")
        val gm = g.mask?.takeIf { it.enabled && maskPolicy == UngroupMaskPolicy.APPLY_TO_EACH }
        if (gm != null) for (c in g.children) requireTreeUnlocked(c)
        group = g
        parentId = parent.id
        prevActive = doc.activeId
        children = g.children.toList()
        oldMasks = children.map { it.mask }
        if (newMasks == null) newMasks = children.map { c -> if (gm == null) c.mask else combine(c.mask, gm) }
        index = parent.remove(g)
        for ((i, c) in children.withIndex()) {
            g.remove(c)
            c.mask = newMasks!![i]
            parent.add(c, index + i)
        }
        if (doc.activeId == g.id) doc.activeId = children.lastOrNull()?.id ?: doc.activeAfterRemoval(listOf(g))
        doc.touch()
    }

    override fun revert(doc: Document) {
        val g = group ?: return
        val parent = doc.group(parentId)
        for ((i, c) in children.withIndex()) {
            parent.remove(c)
            c.mask = oldMasks[i]
            g.add(c)
        }
        parent.add(g, index)
        doc.activeId = prevActive
        doc.touch()
    }

    override val byteSize: Long
        get() = STRUCT_BYTES + (newMasks?.sumOf(::maskBytes) ?: 0L)

    private fun combine(child: LayerMask?, gm: LayerMask): LayerMask {
        if (child == null || !child.enabled) return gm.deepCopy().also { it.enabled = true; it.linked = child?.linked ?: true }
        return LayerMask(intersect(child, gm)).also { it.linked = child.linked }
    }
}

/** Per-pixel product of the effective values of [a] and [b] (density baked in), as a density-1 mask grid. */
internal fun intersect(a: LayerMask, b: LayerMask): MaskGrid {
    fun def(m: LayerMask) = maskEffective(m.tiles.defaultValue.toInt() and 0xFF, m.density)
    val out = MaskGrid(a.tiles.width, a.tiles.height, ((def(a) * def(b)) * 255f).roundToInt().toByte())
    val keys = HashSet(a.tiles.keys()).apply { addAll(b.tiles.keys()) }
    val n = Tile.SIZE * Tile.SIZE
    for (k in keys) {
        val ta = a.tiles.get(k)
        val tb = b.tiles.get(k)
        val da = def(a)
        val db = def(b)
        val t = out.getOrCreate(k)
        for (p in 0 until n) {
            val va = if (ta != null) maskEffective(ta.data[p].toInt() and 0xFF, a.density) else da
            val vb = if (tb != null) maskEffective(tb.data[p].toInt() and 0xFF, b.density) else db
            t.data[p] = (va * vb * 255f).roundToInt().toByte()
        }
        t.markDirty()
    }
    out.compact()
    return out
}
