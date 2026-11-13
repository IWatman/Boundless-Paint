package app.painter.core.io

import app.painter.core.doc.Document
import app.painter.core.doc.LayerMask
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import app.painter.core.stroke.LayerWorkingSet
import app.painter.core.stroke.TilePool
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveTrackingRegressionTest {
    private val root = createTempDirectory("painter-save-tracking").toFile()
    private val key = TileKey(0, 0)

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun mask(value: Int) = MaskTile().also { it.fill(value.toByte()) }

    @Test
    fun successiveWorkingSetCommitsWithEqualVersionsAreSaved() {
        val doc = Document.create(32, 32)
        val layer = doc.activeRaster!!
        fun stroke(green: Float) {
            val working = LayerWorkingSet(layer.pixels, TilePool())
            working.prepare(key, create = true)!!.write(0, 1f, green, 0f, 1f)
            assertNotNull(working.commit(layer.id))
        }
        stroke(0f)
        val firstVersion = layer.pixels.get(key)!!.version
        val dir = File(root, doc.id)
        ProjectIO.save(doc, dir, null)
        val tracker = SaveTracker().also { it.commitAll(doc) }

        stroke(1f)
        assertEquals(firstVersion, layer.pixels.get(key)!!.version)
        val delta = tracker.collect(doc)
        assertTrue(delta.layers.getValue(layer.id).changed.containsKey(key))
        ProjectIO.saveDelta(doc, dir, delta)
        tracker.commit(delta)
        assertEquals(1f, ProjectIO.load(dir).activeRaster!!.pixels.get(key)!![1])
        assertTrue(tracker.collect(doc).layers.isEmpty())
    }

    @Test
    fun replacementMaskAndSelectionWithEqualVersionsAreSaved() {
        val doc = Document.create(32, 32)
        val layer = doc.activeRaster!!
        layer.mask = LayerMask(MaskGrid(32, 32, (-1).toByte()))
        val firstMask = mask(40)
        val firstSelection = mask(80)
        layer.mask!!.tiles.put(key, firstMask)
        doc.selection.mask.put(key, firstSelection)
        val dir = File(root, doc.id)
        ProjectIO.save(doc, dir, null)
        val tracker = SaveTracker().also { it.commitAll(doc) }

        val nextMask = mask(120)
        val nextSelection = mask(160)
        assertEquals(firstMask.version, nextMask.version)
        assertEquals(firstSelection.version, nextSelection.version)
        layer.mask!!.tiles.put(key, nextMask)
        doc.selection.mask.put(key, nextSelection)
        val delta = tracker.collect(doc)
        assertTrue(delta.masks.getValue(layer.id).changed.containsKey(key))
        assertTrue(assertNotNull(delta.selection).changed.containsKey(key))
        ProjectIO.saveDelta(doc, dir, delta)
        tracker.commit(delta)
        val loaded = ProjectIO.load(dir)
        assertEquals(120, loaded.activeRaster!!.mask!!.tiles.byteAt(0, 0))
        assertEquals(160, loaded.selection.mask.byteAt(0, 0))
        val unchanged = tracker.collect(doc)
        assertTrue(unchanged.masks.isEmpty())
        assertNull(unchanged.selection)
    }

    @Test
    fun equalVersionEmptyReplacementsRemoveStoredTiles() {
        val doc = Document.create(32, 32)
        val layer = doc.activeRaster!!
        layer.pixels.setPixel(0, 0, 1f, 0f, 0f, 1f)
        layer.mask = LayerMask(MaskGrid(32, 32, (-1).toByte()))
        layer.mask!!.tiles.put(key, mask(40))
        doc.selection.mask.put(key, mask(80))
        val dir = File(root, doc.id)
        ProjectIO.save(doc, dir, null)
        val tracker = SaveTracker().also { it.commitAll(doc) }

        layer.pixels.put(key, Tile().also { it.markDirty() })
        layer.mask!!.tiles.put(key, mask(255))
        doc.selection.mask.put(key, mask(0))
        val delta = tracker.collect(doc)
        assertTrue(key in delta.layers.getValue(layer.id).removed)
        assertTrue(key in delta.masks.getValue(layer.id).removed)
        assertTrue(key in assertNotNull(delta.selection).removed)
        assertFalse(delta.selectionPresent)
        ProjectIO.saveDelta(doc, dir, delta)
        val loaded = ProjectIO.load(dir)
        assertNull(loaded.activeRaster!!.pixels.get(key))
        assertTrue(loaded.activeRaster!!.mask!!.tiles.keys().isEmpty())
        assertTrue(loaded.selection.isEmpty)
    }

    @Test
    fun capturedBaselineDoesNotMarkLaterEqualVersionReplacementsSaved() {
        val doc = Document.create(32, 32)
        val layer = doc.activeRaster!!
        layer.pixels.setPixel(0, 0, 1f, 0f, 0f, 1f)
        layer.mask = LayerMask(MaskGrid(32, 32, (-1).toByte()))
        layer.mask!!.tiles.put(key, mask(40))
        doc.selection.mask.put(key, mask(80))
        val tracker = SaveTracker()
        val captured = tracker.capture(doc)

        layer.pixels.put(key, Tile().also { it.write(0, 0f, 1f, 0f, 1f); it.markDirty() })
        layer.mask!!.tiles.put(key, mask(120))
        doc.selection.mask.put(key, mask(160))
        tracker.commitVersions(captured)
        val delta = tracker.collect(doc)
        assertTrue(key in delta.layers.getValue(layer.id).changed)
        assertTrue(key in delta.masks.getValue(layer.id).changed)
        assertTrue(key in assertNotNull(delta.selection).changed)
    }

    @Test
    fun metadataOnlyDeltaKeepsNewUnchangedAndInactiveSelections() {
        val doc = Document.create(32, 32)
        val dir = File(root, doc.id)
        ProjectIO.save(doc, dir, null)
        val tracker = SaveTracker().also { it.commitAll(doc) }
        doc.selection.mask.put(key, mask(180))
        for (active in listOf(true, false)) {
            doc.selection.active = active
            val metadata = Document(doc.width, doc.height, doc.id)
            metadata.selection.active = active
            val delta = tracker.collect(doc)
            assertTrue(metadata.selection.isEmpty)
            assertTrue(delta.selectionPresent)
            ProjectIO.saveDelta(metadata, dir, delta)
            tracker.commit(delta)
            val loaded = ProjectIO.load(dir)
            assertFalse(loaded.selection.isEmpty)
            assertEquals(active, loaded.selection.active)
            assertEquals(180, loaded.selection.mask.byteAt(0, 0))
        }
    }

    @Test
    fun deltaSelectionPresenceOverridesStaleNonemptyManifestSelection() {
        val doc = Document.create(32, 32)
        doc.selection.mask.put(key, mask(180))
        val dir = File(root, doc.id)
        ProjectIO.save(doc, dir, null)
        val tracker = SaveTracker().also { it.commitAll(doc) }
        val metadata = Document(doc.width, doc.height, doc.id)
        metadata.selection.copyFrom(doc.selection)
        doc.selection.mask.clear()
        val delta = tracker.collect(doc)
        assertFalse(delta.selectionPresent)
        ProjectIO.saveDelta(metadata, dir, delta)
        assertFalse(File(dir, "manifest.json").readText().contains("\"selection\""))
        assertTrue(ProjectIO.load(dir).selection.isEmpty)
    }
}
