package app.painter.core.history

import app.painter.core.adjust.AdjustKind
import app.painter.core.adjust.AdjustParams
import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.Document
import app.painter.core.doc.newNodeId

/**
 * Inserts a new adjustment layer with [params] into group [parentId] (null = root) at [index] (null = top),
 * optionally [clipped], and makes it active. The default name is the kind's title.
 */
class AddAdjustmentLayer(
    val params: AdjustParams,
    val parentId: String? = null,
    val index: Int? = null,
    val clipped: Boolean = false,
    val layerName: String = AdjustKind.title(params.kind),
    val nodeId: String = newNodeId(),
    override val name: String = "Add adjustment layer",
) : Command {
    private val inner = AddLayer(AdjustmentLayer(nodeId, layerName, params).also { it.clipped = clipped }, parentId, index, name)

    override fun apply(doc: Document) = inner.apply(doc)
    override fun revert(doc: Document) = inner.revert(doc)
    override val byteSize: Long get() = inner.byteSize
}

/** Replaces the parameters of adjustment layer [nodeId]: one step per "Adjust…" session. */
class SetAdjustParams(
    val nodeId: String,
    val before: AdjustParams,
    val after: AdjustParams,
    override val name: String = "Adjustment",
) : Command {
    override fun apply(doc: Document) {
        val n = layer(doc)
        requireUnlocked(n)
        n.params = after
        doc.touch()
    }

    override fun revert(doc: Document) {
        layer(doc).params = before
        doc.touch()
    }

    override val byteSize: Long get() = STRUCT_BYTES

    private fun layer(doc: Document): AdjustmentLayer =
        doc.findNode(nodeId) as? AdjustmentLayer ?: error("adjustment layer $nodeId not found")
}
