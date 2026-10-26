package app.painter.core.doc

import app.painter.core.raster.TileGrid
import java.util.UUID

enum class ProjectType { REGULAR, PATTERN, CHALLENGE, ANIMATION, MANUSCRIPT }

/**
 * In-memory project. Mutations must go through [app.painter.core.history.History]
 * commands so that undo works; direct field access is for reading and for commands.
 */
class Document(
    val width: Int,
    val height: Int,
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Untitled",
    val type: ProjectType = ProjectType.REGULAR,
    var dpi: Int = 300,
) {
    val root = GroupNode("root", "root")
    val paper = Paper()
    var created: Long = System.currentTimeMillis()
    var modified: Long = created

    /** The layer that receives paint. */
    var activeId: String? = null

    fun newRasterLayer(name: String): RasterLayer = RasterLayer(newNodeId(), name, TileGrid(width, height))

    fun findNode(id: String): Node? {
        var found: Node? = null
        root.walk { if (it.id == id) found = it }
        return found
    }

    val activeNode: Node? get() = activeId?.let(::findNode)
    val activeRaster: RasterLayer? get() = activeNode as? RasterLayer

    fun layerCount(): Int { var n = 0; root.walk { if (it !is GroupNode) n++ }; return n }

    companion object {
        /** Creates a document with one empty raster layer, set active. */
        fun create(width: Int, height: Int, name: String = "Untitled"): Document {
            val d = Document(width, height, name = name)
            val l = d.newRasterLayer("Layer 1")
            d.root.add(l)
            d.activeId = l.id
            return d
        }
    }
}
