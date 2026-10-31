package app.painter.studio

import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.LayerMask
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.TileGrid

/**
 * Copy of [doc] with identical ids, safe to read on a background thread while the original keeps being edited.
 *
 * With [pixels] = false every grid stays empty, so the copy carries only the manifest side of the document
 * (node properties, paper, guides, masks' defaults, selection state). That is all [app.painter.core.io.ProjectIO.saveDelta]
 * needs next to the tile deltas, and it keeps the main thread from copying untouched pixels (debt 10).
 */
fun snapshotDocument(doc: Document, pixels: Boolean = true): Document {
    val copy = Document(doc.width, doc.height, doc.id, doc.name, doc.type, doc.dpi)
    copy.created = doc.created
    copy.modified = doc.modified
    copy.activeId = doc.activeId
    copy.activeTarget = doc.activeTarget
    copy.selection.active = doc.selection.active
    if (pixels) copy.selection.copyFrom(doc.selection)
    copy.guides = doc.guides
    copy.gammaCorrect = doc.gammaCorrect
    copy.paper.color = doc.paper.color
    copy.paper.visible = doc.paper.visible
    copy.paper.textureId = doc.paper.textureId
    copy.paper.textureDepth = doc.paper.textureDepth
    copy.paper.textureOpacity = doc.paper.textureOpacity
    copy.paper.textureScale = doc.paper.textureScale
    for (child in doc.root.children) copy.root.add(copyKeepingIds(child, pixels))
    return copy
}

private fun copyKeepingIds(node: Node, pixels: Boolean): Node = when (node) {
    is GroupNode -> {
        val g = GroupNode(node.id, node.name)
        copyBase(node, g, pixels)
        g.expanded = node.expanded
        for (c in node.children) g.add(copyKeepingIds(c, pixels))
        g
    }
    is RasterLayer -> RasterLayer(
        node.id,
        node.name,
        if (pixels) node.pixels.copy() else TileGrid(node.pixels.width, node.pixels.height),
    ).also {
        copyBase(node, it, pixels)
        it.alphaLock = node.alphaLock
    }
    is AdjustmentLayer -> AdjustmentLayer(node.id, node.name, node.params).also { copyBase(node, it, pixels) }
}.also { it.reference = node.reference }

private fun copyBase(from: Node, to: Node, pixels: Boolean) {
    to.visible = from.visible
    to.opacity = from.opacity
    to.blendMode = from.blendMode
    to.clipped = from.clipped
    to.hideInRecording = from.hideInRecording
    to.locked = from.locked
    to.mask = copyMask(from.mask, pixels)
    to.colorTag = from.colorTag
}

/** Mask metadata; with [pixels] = false the coverage stays empty (the delta writes it). */
private fun copyMask(mask: LayerMask?, pixels: Boolean): LayerMask? {
    if (mask == null) return null
    val grid = if (pixels) {
        mask.tiles.copy()
    } else {
        MaskGrid(mask.tiles.width, mask.tiles.height, mask.tiles.defaultValue)
    }
    return LayerMask(grid).also {
        it.enabled = mask.enabled
        it.linked = mask.linked
        it.density = mask.density
    }
}
