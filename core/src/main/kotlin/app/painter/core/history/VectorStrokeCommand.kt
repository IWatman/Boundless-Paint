package app.painter.core.history

import app.painter.core.doc.Document
import app.painter.core.doc.VectorLayer
import app.painter.core.raster.TilePatch
import app.painter.core.vector.VectorStroke

/**
 * One brush stroke on a [VectorLayer]: the vector paths and the raster cache are restored together.
 * [patch] holds the pixels the interactive stroke engine changed; the paths are the before/after snapshots.
 * Applying and reverting never re-renders, so the exact pixels of the session are kept.
 */
class VectorStrokeCommand(
    val layerId: String,
    private val beforeStrokes: List<VectorStroke>,
    private val afterStrokes: List<VectorStroke>,
    private val patch: TilePatch? = null,
    override val name: String = "Vector stroke",
) : Command {
    override fun apply(doc: Document) {
        val layer = layer(doc)
        requireUnlocked(layer)
        layer.setStrokes(afterStrokes)
        patch?.let { writeTiles(layer.pixels, it.after) }
        doc.touch()
    }

    override fun revert(doc: Document) {
        val layer = layer(doc)
        layer.setStrokes(beforeStrokes)
        patch?.let { writeTiles(layer.pixels, it.before) }
        doc.touch()
    }

    override val byteSize: Long
        get() = (patch?.byteSize ?: 0L) + strokesBytes(beforeStrokes) + strokesBytes(afterStrokes)

    private fun layer(doc: Document): VectorLayer =
        doc.findNode(layerId) as? VectorLayer ?: error("vector layer $layerId not found")

    /** Coarse retained size: a point is 7 floats/ints, a brush is approximated by its default JSON size. */
    private fun strokesBytes(strokes: List<VectorStroke>): Long =
        strokes.sumOf { STRUCT_BYTES + it.points.size * 32L }
}
