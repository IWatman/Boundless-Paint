package app.painter.core.history

import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid

/**
 * One undoable crop / canvas-size / straighten step (spec/ROADMAP.md M3). Holds the pixel grids of every raster
 * layer, the mask grids of every node and the selection mask both before and after the operation; [apply] and
 * [revert] swap them and update [Document.width]/[Document.height].
 *
 * The UI must call `CanvasView.setDocument` after an undo or redo of this command, because the canvas texture
 * allocation changes size.
 */
class CanvasCommand(
    override val name: String,
    private val oldWidth: Int,
    private val oldHeight: Int,
    private val newWidth: Int,
    private val newHeight: Int,
    private val beforeLayers: Map<String, TileGrid>,
    private val afterLayers: Map<String, TileGrid>,
    private val beforeMasks: Map<String, MaskGrid>,
    private val afterMasks: Map<String, MaskGrid>,
    private val beforeSelection: MaskGrid,
    private val afterSelection: MaskGrid,
) : Command {

    override fun apply(doc: Document) {
        doc.width = newWidth
        doc.height = newHeight
        for ((id, grid) in afterLayers) (doc.findNode(id) as? RasterLayer)?.pixels = grid
        for ((id, grid) in afterMasks) doc.findNode(id)?.mask?.tiles = grid
        doc.selection.mask = afterSelection
        doc.touch()
    }

    override fun revert(doc: Document) {
        doc.width = oldWidth
        doc.height = oldHeight
        for ((id, grid) in beforeLayers) (doc.findNode(id) as? RasterLayer)?.pixels = grid
        for ((id, grid) in beforeMasks) doc.findNode(id)?.mask?.tiles = grid
        doc.selection.mask = beforeSelection
        doc.touch()
    }

    override val byteSize: Long
        get() {
            var n = 0L
            for (g in beforeLayers.values + afterLayers.values) n += g.keys().size
            for (g in beforeMasks.values + afterMasks.values) n += g.keys().size
            n += beforeSelection.keys().size + afterSelection.keys().size
            return tileBytes(n.toInt())
        }
}
