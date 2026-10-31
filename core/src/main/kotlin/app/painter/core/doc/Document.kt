package app.painter.core.doc

import app.painter.core.raster.TileGrid
import kotlinx.serialization.json.JsonObject
import java.util.UUID

enum class ProjectType { REGULAR, PATTERN, CHALLENGE, ANIMATION, MANUSCRIPT }

/**
 * In-memory project. Mutations must go through [app.painter.core.history.History]
 * commands so that undo works; direct field access is for reading and for commands.
 */
class Document(
    /** Canvas width in pixels; changed by the crop/resize/straighten command. */
    var width: Int,
    /** Canvas height in pixels; changed by the crop/resize/straighten command. */
    var height: Int,
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Untitled",
    val type: ProjectType = ProjectType.REGULAR,
    var dpi: Int = 300,
) {
    val root = GroupNode("root", "root")
    val paper = Paper()

    /**
     * Blend layers and dabs in linear light (spec/blend-and-color.md): sRGB values are decoded before a blend
     * and encoded back. Off by default; stored in the project file.
     */
    var gammaCorrect: Boolean = false

    var created: Long = System.currentTimeMillis()
    var modified: Long = created

    /** The layer that receives paint. */
    var activeId: String? = null

    /** Whether painting edits the active node's pixels or its mask. Not part of history. */
    var activeTarget: EditTarget = EditTarget.LAYER

    val selection = Selection(width, height)

    /** Guides block of the manifest, kept opaque and round-tripped as is (schema owned by core/guide). */
    var guides: JsonObject? = null

    /** The node flagged as reference for fill / wand, if any. */
    val referenceNode: Node? get() { var r: Node? = null; root.walk { if (it.reference) r = it }; return r }

    fun newRasterLayer(name: String): RasterLayer = RasterLayer(newNodeId(), name, TileGrid(width, height))

    /** Empty vector layer with a transparent raster cache of the canvas size. */
    fun newVectorLayer(name: String): VectorLayer = VectorLayer(newNodeId(), name, TileGrid(width, height))

    fun findNode(id: String): Node? {
        var found: Node? = null
        root.walk { if (it.id == id) found = it }
        return found
    }

    val activeNode: Node? get() = activeId?.let(::findNode)
    val activeRaster: RasterLayer? get() = activeNode as? RasterLayer

    /** Number of raster layers (groups and adjustment layers do not count). */
    fun layerCount(): Int { var n = 0; root.walk { if (it is RasterLayer) n++ }; return n }

    companion object {
        /** Creates a document with one empty raster layer named [layerName] and set active. */
        fun create(width: Int, height: Int, name: String = "Untitled", layerName: String = "Layer 1"): Document {
            val d = Document(width, height, name = name)
            val l = d.newRasterLayer(layerName)
            d.root.add(l)
            d.activeId = l.id
            return d
        }
    }
}
