package app.painter.studio

import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.Node

/**
 * Deep copy of [doc] with identical ids, safe to read on a background thread while the
 * original keeps being edited on the main thread.
 */
fun snapshotDocument(doc: Document): Document {
    val copy = Document(doc.width, doc.height, doc.id, doc.name, doc.type, doc.dpi)
    copy.created = doc.created
    copy.modified = doc.modified
    copy.activeId = doc.activeId
    copy.activeTarget = doc.activeTarget
    copy.selection.copyFrom(doc.selection)
    copy.paper.color = doc.paper.color
    copy.paper.visible = doc.paper.visible
    copy.paper.textureId = doc.paper.textureId
    copy.paper.textureDepth = doc.paper.textureDepth
    copy.paper.textureOpacity = doc.paper.textureOpacity
    copy.paper.textureScale = doc.paper.textureScale
    for (child in doc.root.children) copy.root.add(copyKeepingIds(child))
    return copy
}

private fun copyKeepingIds(node: Node): Node = when (node) {
    is GroupNode -> {
        val g = GroupNode(node.id, node.name)
        g.visible = node.visible; g.opacity = node.opacity; g.blendMode = node.blendMode
        g.clipped = node.clipped; g.hideInRecording = node.hideInRecording; g.locked = node.locked
        g.expanded = node.expanded
        g.mask = node.mask?.deepCopy(); g.colorTag = node.colorTag
        for (c in node.children) g.add(copyKeepingIds(c))
        g
    }
    else -> node.deepCopy(node.id)
}.also { it.reference = node.reference }
