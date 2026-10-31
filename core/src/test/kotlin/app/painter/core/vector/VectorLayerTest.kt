package app.painter.core.vector

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.VectorLayer
import app.painter.core.history.ClearLayer
import app.painter.core.history.History
import app.painter.core.history.VectorStrokeCommand
import app.painter.core.io.ProjectIO
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.Curve
import app.painter.core.stroke.StrokeMode
import app.painter.core.symmetry.SymmetryConfig
import app.painter.core.symmetry.SymmetryType
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VectorLayerTest {
    private val tmpRoot: File = createTempDirectory("painter-vector").toFile()

    @AfterTest
    fun cleanup() {
        tmpRoot.deleteRecursively()
    }

    private fun stroke(
        dy: Float = 0f,
        mode: StrokeMode = StrokeMode.PAINT,
        symmetry: SymmetryConfig? = null,
    ) = VectorStroke(
        points = listOf(
            VectorPoint(20f, 20f + dy, 1f),
            VectorPoint(32f, 26f + dy, 0.8f),
            VectorPoint(44f, 32f + dy, 0.6f),
            VectorPoint(56f, 40f + dy, 0.4f),
        ),
        brush = BrushParams(
            size = 12f,
            smoothing = 0f,
            pressureToSize = Curve.LINEAR,
            afterSmoothing = 0f,
        ),
        color = Rgba.BLACK,
        mode = mode,
        seed = 42L,
        zoom = 1f,
        symmetry = symmetry,
    )

    private fun documentWithVector(): Pair<Document, VectorLayer> {
        val doc = Document(128, 128, name = "Vector test")
        val layer = VectorLayer("v1", "Vector 1", TileGrid(128, 128))
        doc.root.add(layer)
        doc.activeId = layer.id
        return doc to layer
    }

    @Test
    fun appendStrokeKeepsPathAndRendersCache() {
        val (doc, layer) = documentWithVector()
        val patch = VectorRenderService.appendStroke(doc, layer, stroke())

        assertEquals(1, layer.strokes.size)
        assertTrue(patch != null && patch.after.isNotEmpty())
        assertTrue(layer.pixels.keys().isNotEmpty())
        val pixel = FloatArray(4)
        layer.pixels.getPixel(26, 23, pixel)
        assertTrue(pixel[3] > 0f)
    }

    @Test
    fun appendAndUndoRestorePixelsAndPaths() {
        val (doc, layer) = documentWithVector()
        val history = History()
        val first = stroke()
        VectorRenderService.appendStroke(doc, layer, first)

        val beforeStrokes = layer.strokes.toList()
        val beforePixels = layer.pixels.copy()
        val second = stroke(dy = 40f)
        val patch = VectorRenderService.appendStroke(doc, layer, second)
        assertNotNull(patch)
        val afterStrokes = layer.strokes.toList()
        val afterPixels = layer.pixels.copy()
        history.push(VectorStrokeCommand(layer.id, beforeStrokes, afterStrokes, patch))

        assertEquals(2, layer.strokes.size)
        assertTrue(history.undo(doc))
        assertEquals(beforeStrokes, layer.strokes)
        assertEquals(tileHash(beforePixels), tileHash(layer.pixels))

        assertTrue(history.redo(doc))
        assertEquals(afterStrokes, layer.strokes)
        assertEquals(tileHash(afterPixels), tileHash(layer.pixels))
    }

    @Test
    fun clearLayerClearsPathsAndUndoRestoresThem() {
        val (doc, layer) = documentWithVector()
        val history = History()
        VectorRenderService.appendStroke(doc, layer, stroke())
        val cache = layer.pixels.copy()

        history.execute(doc, ClearLayer(layer.id))
        assertTrue(layer.strokes.isEmpty())
        assertTrue(layer.pixels.keys().isEmpty())

        assertTrue(history.undo(doc))
        assertEquals(1, layer.strokes.size)
        assertEquals(tileHash(cache), tileHash(layer.pixels))
    }

    @Test
    fun renderAllIsDeterministicForPlainBrushes() {
        val (doc, layer) = documentWithVector()
        VectorRenderService.appendStroke(doc, layer, stroke())
        val firstHash = tileHash(layer)

        val touched = VectorRenderService.renderAll(doc, layer)
        assertTrue(touched.isNotEmpty())
        assertEquals(firstHash, tileHash(layer))
    }

    @Test
    fun deepCopyPreservesPathsIndependently() {
        val (doc, layer) = documentWithVector()
        VectorRenderService.appendStroke(doc, layer, stroke())
        val copy: VectorLayer = layer.deepCopy("v2")

        assertEquals(layer.strokes, copy.strokes)
        copy.appendStroke(stroke(dy = 40f))
        assertEquals(1, layer.strokes.size)
        assertEquals(2, copy.strokes.size)
    }

    @Test
    fun projectRoundTripKeepsVectorPathsAndRebuildsCache() {
        val (doc, layer) = documentWithVector()
        val symmetric = SymmetryConfig(
            on = true,
            type = SymmetryType.MIRROR,
            center = app.painter.core.geom.Vec2(64f, 64f),
        )
        VectorRenderService.appendStroke(doc, layer, stroke(symmetry = symmetric))
        VectorRenderService.appendStroke(doc, layer, stroke(dy = 40f, mode = StrokeMode.ERASE))
        doc.activeId = layer.id
        // A nested vector layer also exercises the deferred cache replay in ProjectIO.load.
        doc.root.remove(layer)
        val group = GroupNode("g1", "Group")
        group.add(layer)
        doc.root.add(group)

        val dir = File(tmpRoot, "project")
        ProjectIO.save(doc, dir, thumbnailPng = null)
        val manifest = File(dir, "manifest.json").readText()
        assertTrue(manifest.contains("\"kind\": \"vector\""))
        assertTrue(manifest.contains("\"vector\""))

        val loaded = ProjectIO.load(dir)
        val node = loaded.findNode("v1")
        assertTrue(node is VectorLayer)
        assertEquals(layer.strokes, node.strokes)
        assertTrue(node.pixels.keys().isNotEmpty(), "the cache is re-rendered on load")
        assertFalse(node.strokes.any { it.points.isEmpty() })
    }

    private fun tileHash(layer: VectorLayer): String = tileHash(layer.pixels)

    private fun tileHash(grid: TileGrid): String =
        grid.entries().sortedBy { it.key.packed }
            .joinToString(";") { "${it.key}:${it.value.contentHash()}" }
}
