package app.painter.studio

import app.painter.core.doc.Document
import app.painter.core.doc.LayerMask
import app.painter.core.io.ProjectIO
import app.painter.core.io.SaveTracker
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.TileKey
import org.junit.Test
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*

class DocumentSaveTest {
    private fun write(prepared: DocumentSaveSnapshot, dir: java.io.File, tracker: SaveTracker) {
        val delta = prepared.delta
        if (delta != null) {
            ProjectIO.saveDelta(prepared.document, dir, delta)
            tracker.commit(delta)
        } else {
            ProjectIO.save(prepared.document, dir, null)
            tracker.commitVersions(prepared.versions!!)
        }
    }

    @Test
    fun queuedSaveAfterUndoRemovesPixelsAndMasksAddedByPendingWriter() {
        val doc = Document.create(32, 32)
        val layer = doc.activeRaster!!
        layer.mask = LayerMask(MaskGrid(32, 32, (-1).toByte()))
        val dir = createTempDirectory("painter-queued-save").toFile()
        try {
            ProjectIO.save(doc, dir, null)
            val tracker = SaveTracker().also { it.commitAll(doc) }
            layer.pixels.setPixel(5, 5, 1f, 0f, 0f, 1f)
            layer.mask!!.tiles.getOrCreate(0, 0).also { it.data[0] = 0; it.markDirty() }
            val first = prepareDocumentSave(doc, tracker, onDisk = true, savePending = false)
            // Undo while the first snapshot is being written, then queue the latest state.
            layer.pixels.put(TileKey(0, 0), null)
            layer.mask!!.tiles.clear()
            val second = prepareDocumentSave(doc, tracker, onDisk = true, savePending = true)
            write(first, dir, tracker)
            write(second, dir, tracker)
            val loaded = ProjectIO.load(dir).activeRaster!!
            assertNull(loaded.pixels.get(0, 0))
            assertEquals(255, loaded.mask!!.tiles.byteAt(0, 0))
            assertTrue(loaded.mask!!.tiles.keys().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun queuedReplacementAlsoWorksWhenPreviousWriterFails() {
        val doc = Document.create(32, 32)
        val layer = doc.activeRaster!!
        layer.pixels.setPixel(5, 5, 1f, 0f, 0f, 1f)
        val dir = createTempDirectory("painter-failed-save").toFile()
        try {
            ProjectIO.save(doc, dir, null)
            val tracker = SaveTracker().also { it.commitAll(doc) }
            layer.pixels.setPixel(6, 6, 0f, 1f, 0f, 1f)
            // The pending save never commits. Its successor must still preserve all current pixels.
            prepareDocumentSave(doc, tracker, onDisk = true, savePending = false)
            val queued = prepareDocumentSave(doc, tracker, onDisk = true, savePending = true)
            layer.pixels.clear() // further edits must not mutate the queued snapshot
            write(queued, dir, tracker)
            val loaded = ProjectIO.load(dir).activeRaster!!
            val pixel = FloatArray(4)
            loaded.pixels.getPixel(5, 5, pixel)
            assertEquals(1f, pixel[0], 0.001f)
            loaded.pixels.getPixel(6, 6, pixel)
            assertEquals(1f, pixel[1], 0.001f)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun ordinarySaveKeepsIncrementalSnapshotsWithoutCopyingUntouchedPixels() {
        val doc = Document.create(32, 32)
        doc.activeRaster!!.pixels.setPixel(5, 5, 1f, 0f, 0f, 1f)
        val tracker = SaveTracker().also { it.commitAll(doc) }
        val prepared = prepareDocumentSave(doc, tracker, onDisk = true, savePending = false)
        assertNotNull(prepared.delta)
        assertNull(prepared.versions)
        assertTrue(prepared.document.activeRaster!!.pixels.keys().isEmpty())
    }

    @Test
    fun incrementalMetadataSnapshotPreservesSelectionAcrossReloads() {
        val doc = Document.create(32, 32)
        val dir = createTempDirectory("painter-save-selection").toFile()
        try {
            ProjectIO.save(doc, dir, null)
            val tracker = SaveTracker().also { it.commitAll(doc) }
            doc.selection.mask.getOrCreate(0, 0).fill(180.toByte())
            for (active in listOf(true, false)) {
                doc.selection.active = active
                val prepared = prepareDocumentSave(doc, tracker, onDisk = true, savePending = false)
                assertNotNull(prepared.delta)
                assertTrue(prepared.document.selection.isEmpty)
                write(prepared, dir, tracker)
                val loaded = ProjectIO.load(dir)
                assertFalse(loaded.selection.isEmpty)
                assertEquals(active, loaded.selection.active)
                assertEquals(180, loaded.selection.mask.byteAt(0, 0))
            }
            doc.selection.mask.clear()
            write(prepareDocumentSave(doc, tracker, onDisk = true, savePending = false), dir, tracker)
            assertTrue(ProjectIO.load(dir).selection.isEmpty)
        } finally { dir.deleteRecursively() }
    }
}
