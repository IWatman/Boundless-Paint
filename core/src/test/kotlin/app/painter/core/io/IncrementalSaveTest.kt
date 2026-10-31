package app.painter.core.io

import app.painter.core.doc.Document
import app.painter.core.doc.LayerMask
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Incremental save (debt 10): the tracker copies only the tiles that changed, and the writer reuses the
 * stored files and compressed tiles of everything else.
 */
class IncrementalSaveTest {

    private val root: File = createTempDirectory("painter-delta").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun doc(): Document {
        val d = Document(512, 256, name = "Incremental")
        val a = RasterLayer("a", "A", TileGrid(512, 256))
        fill(a.pixels, TileKey(0, 0), 0.1f)
        fill(a.pixels, TileKey(1, 0), 0.2f)
        val b = RasterLayer("b", "B", TileGrid(512, 256))
        fill(b.pixels, TileKey(0, 0), 0.3f)
        d.root.add(a)
        d.root.add(b)
        d.activeId = a.id
        return d
    }

    private fun fill(grid: TileGrid, key: TileKey, value: Float) {
        val t = grid.getOrCreate(key.tx, key.ty)
        for (i in 0 until Tile.CHANNELS) t[i] = value
        t.markDirty()
    }

    private fun indexOf(dir: File): Map<TileKey, LongArray> {
        val f = File(dir, "tiles.idx")
        if (!f.isFile) return emptyMap()
        val buf = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val out = HashMap<TileKey, LongArray>()
        while (buf.remaining() >= 12) {
            val tx = buf.short.toInt() and 0xFFFF
            val ty = buf.short.toInt() and 0xFFFF
            val offset = buf.int.toLong() and 0xFFFFFFFFL
            val length = buf.int.toLong() and 0xFFFFFFFFL
            out[TileKey(tx, ty)] = longArrayOf(offset, length)
        }
        return out
    }

    private fun bytesOf(dir: File, key: TileKey): ByteArray {
        val range = indexOf(dir).getValue(key)
        RandomAccessFile(File(dir, "tiles.bin"), "r").use { f ->
            val b = ByteArray(range[1].toInt())
            f.seek(range[0])
            f.readFully(b)
            return b
        }
    }

    private fun bytes(file: File): ByteArray = file.readBytes()

    @Test
    fun trackerCopiesOnlyChangedTiles() {
        val d = doc()
        val dir = File(root, d.id)
        ProjectIO.save(d, dir, null)
        val tracker = SaveTracker()
        tracker.commitAll(d)

        val nothing = tracker.collect(d)
        assertTrue(nothing.layers.isEmpty(), "no layer changed")
        assertNull(nothing.selection)

        val a = d.findNode("a") as RasterLayer
        fill(a.pixels, TileKey(0, 0), 0.9f)
        val delta = tracker.collect(d)
        assertEquals(setOf("a"), delta.layers.keys)
        val layerDelta = delta.layers.getValue("a")
        assertEquals(setOf(TileKey(0, 0)), layerDelta.changed.keys)
        assertTrue(layerDelta.removed.isEmpty())
        assertEquals(Tile.CHANNELS, layerDelta.changed.getValue(TileKey(0, 0)).size)
    }

    @Test
    fun unchangedLayerFileIsCopiedVerbatim() {
        val d = doc()
        val dir = File(root, d.id)
        ProjectIO.save(d, dir, null)
        val tracker = SaveTracker()
        tracker.commitAll(d)

        val bBefore = bytes(File(dir, "layers/b/tiles.bin"))
        val aBefore = bytesOf(File(dir, "layers/a"), TileKey(1, 0))

        (d.findNode("a") as RasterLayer).let { fill(it.pixels, TileKey(0, 0), 0.9f) }
        val delta = tracker.collect(d)
        ProjectIO.saveDelta(d, dir, delta, null)

        assertContentEquals(bBefore, bytes(File(dir, "layers/b/tiles.bin")), "untouched layer is copied")
        assertContentEquals(aBefore, bytesOf(File(dir, "layers/a"), TileKey(1, 0)), "untouched tile keeps its bytes")

        val loaded = ProjectIO.load(dir)
        val a = loaded.findNode("a") as RasterLayer
        assertEquals(0.9f, a.pixels.get(TileKey(0, 0))!![0], 1e-2f)
        assertEquals(0.2f, a.pixels.get(TileKey(1, 0))!![0], 1e-2f)
        assertEquals(0.3f, (loaded.findNode("b") as RasterLayer).pixels.get(TileKey(0, 0))!![0], 1e-2f)
    }

    @Test
    fun removedTileDisappearsAndNewLayerAppears() {
        val d = doc()
        val dir = File(root, d.id)
        ProjectIO.save(d, dir, null)
        val tracker = SaveTracker()
        tracker.commitAll(d)

        val a = d.findNode("a") as RasterLayer
        a.pixels.put(TileKey(1, 0), null)
        val c = RasterLayer("c", "C", TileGrid(512, 256))
        fill(c.pixels, TileKey(1, 0), 0.6f)
        d.root.add(c)

        val delta = tracker.collect(d)
        assertTrue(delta.layers.getValue("a").removed.contains(TileKey(1, 0)))
        assertEquals(setOf(TileKey(1, 0)), delta.layers.getValue("c").changed.keys)
        ProjectIO.saveDelta(d, dir, delta, null)

        val loaded = ProjectIO.load(dir)
        assertNull((loaded.findNode("a") as RasterLayer).pixels.get(TileKey(1, 0)), "removed tile is gone")
        assertNotNull(loaded.findNode("c"))
        assertEquals(0.6f, (loaded.findNode("c") as RasterLayer).pixels.get(TileKey(1, 0))!![0], 1e-2f)
    }

    @Test
    fun maskAndSelectionAreSavedIncrementally() {
        val d = doc()
        val dir = File(root, d.id)
        ProjectIO.save(d, dir, null)
        val tracker = SaveTracker()
        tracker.commitAll(d)

        // A mask on layer B and a selection covering one tile.
        val b = d.findNode("b") as RasterLayer
        val maskGrid = MaskGrid(d.width, d.height, 255.toByte())
        val maskTile = maskGrid.getOrCreate(0, 0)
        for (i in maskTile.data.indices) maskTile.data[i] = (i % 251).toByte()
        maskTile.markDirty()
        b.mask = LayerMask(maskGrid)
        val sel = d.selection.mask.getOrCreate(0, 0)
        sel.data.fill(200.toByte())
        sel.markDirty()

        val delta = tracker.collect(d)
        assertNotNull(delta.masks["b"])
        assertNotNull(delta.selection)
        assertTrue(delta.selectionPresent)
        ProjectIO.saveDelta(d, dir, delta, null)

        val loaded = ProjectIO.load(dir)
        val loadedMask = (loaded.findNode("b") as RasterLayer).mask
        assertNotNull(loadedMask)
        assertContentEquals(maskTile.data, loadedMask.tiles.get(0, 0)!!.data)
        assertContentEquals(sel.data, loaded.selection.mask.get(0, 0)!!.data)

        // Removing the selection clears it on disk as well.
        d.selection.mask.clear()
        val cleared = tracker.collect(d)
        assertFalse(cleared.selectionPresent)
        ProjectIO.saveDelta(d, dir, cleared, null)
        assertTrue(ProjectIO.load(dir).selection.isEmpty)
    }

    @Test
    fun failedSaveKeepsTilesDirty() {
        val d = doc()
        val dir = File(root, d.id)
        ProjectIO.save(d, dir, null)
        val tracker = SaveTracker()
        tracker.commitAll(d)
        fill((d.findNode("a") as RasterLayer).pixels, TileKey(0, 0), 0.7f)
        val first = tracker.collect(d)
        assertEquals(setOf(TileKey(0, 0)), first.layers.getValue("a").changed.keys)
        // The caller never commits: the next collect offers the same tiles again.
        val second = tracker.collect(d)
        assertEquals(setOf(TileKey(0, 0)), second.layers.getValue("a").changed.keys)
    }

    @Test
    fun unchangedSelectionIsCopiedOnLaterSaves() {
        val d = doc()
        val dir = File(root, d.id)
        val tile = d.selection.mask.getOrCreate(0, 0)
        tile.data.fill(150.toByte())
        tile.markDirty()
        ProjectIO.save(d, dir, null)
        val tracker = SaveTracker()
        tracker.commitAll(d)

        // Only a layer changes; the selection files must survive the swap.
        fill((d.findNode("b") as RasterLayer).pixels, TileKey(1, 0), 0.4f)
        val delta = tracker.collect(d)
        assertNull(delta.selection)
        assertTrue(delta.selectionPresent)
        ProjectIO.saveDelta(d, dir, delta, null)

        val loaded = ProjectIO.load(dir)
        assertFalse(loaded.selection.isEmpty)
        assertContentEquals(tile.data, loaded.selection.mask.get(0, 0)!!.data)
    }

    @Test
    fun metadataChangesAreWritten() {
        val d = doc()
        val dir = File(root, d.id)
        ProjectIO.save(d, dir, null)
        val tracker = SaveTracker()
        tracker.commitAll(d)

        d.name = "Renamed"
        (d.findNode("b") as RasterLayer).opacity = 0.25f
        val delta = tracker.collect(d)
        assertTrue(delta.layers.isEmpty())
        ProjectIO.saveDelta(d, dir, delta, null)

        val loaded = ProjectIO.load(dir)
        assertEquals("Renamed", loaded.name)
        assertEquals(0.25f, loaded.findNode("b")!!.opacity)
    }

    @Test
    fun thumbnailProviderSeesTheWrittenDocument() {        val d = doc()
        val dir = File(root, d.id)
        ProjectIO.save(d, dir, null)
        val tracker = SaveTracker()
        tracker.commitAll(d)
        fill((d.findNode("a") as RasterLayer).pixels, TileKey(0, 0), 0.5f)

        var seen: Document? = null
        var pixels = 0f
        ProjectIO.saveDelta(d, dir, tracker.collect(d)) { written ->
            seen = written
            pixels = (written.findNode("a") as RasterLayer).pixels.get(TileKey(0, 0))!![0]
            byteArrayOf(9, 8, 7)
        }
        assertNotNull(seen)
        assertEquals(0.5f, pixels, 1e-2f)
        assertContentEquals(byteArrayOf(9, 8, 7), bytes(File(dir, "thumb.png")))
    }

    @Test
    fun oldThumbnailSurvivesASaveWithoutANewOne() {
        val d = doc()
        val dir = File(root, d.id)
        ProjectIO.save(d, dir, byteArrayOf(1, 2, 3))
        val tracker = SaveTracker()
        tracker.commitAll(d)
        fill((d.findNode("a") as RasterLayer).pixels, TileKey(0, 0), 0.5f)
        ProjectIO.saveDelta(d, dir, tracker.collect(d), null)
        assertContentEquals(byteArrayOf(1, 2, 3), bytes(File(dir, "thumb.png")), "autosave keeps the preview")
    }

    @Test
    fun deltaRequiresTheProjectOnDisk() {
        val d = doc()
        val dir = File(root, "missing")
        val tracker = SaveTracker()
        tracker.commitAll(d)
        val failure = runCatching { ProjectIO.saveDelta(d, dir, tracker.collect(d), null) }
        assertTrue(failure.isFailure, "no project directory to update")
    }

    @Test
    fun maskTileCopiesAreReportedOnce() {
        val d = doc()
        val tracker = SaveTracker()
        tracker.commitAll(d)

        val grid = MaskGrid(d.width, d.height, 0)
        val t = grid.getOrCreate(2, 1)
        t.data.fill(42.toByte())
        t.markDirty()
        d.root.children[0].mask = LayerMask(grid)

        val first = tracker.collect(d)
        assertEquals(setOf(TileKey(2, 1)), first.masks.getValue("a").changed.keys)
        tracker.commit(first)
        assertTrue(tracker.collect(d).masks.isEmpty())
    }

    @Test
    fun gridDefaultMasksAreNotStored() {
        val d = doc()
        val dir = File(root, d.id)
        val grid = MaskGrid(d.width, d.height, 255.toByte())
        val t = grid.getOrCreate(0, 0)
        t.data.fill(255.toByte()) // equal to the default: nothing to store
        t.markDirty()
        d.root.children[0].mask = LayerMask(grid)
        ProjectIO.save(d, dir, null)
        val tracker = SaveTracker()
        tracker.commitAll(d)
        val delta = tracker.collect(d)
        assertTrue(delta.masks["a"] == null)
        ProjectIO.saveDelta(d, dir, delta, null)
        assertTrue(ProjectIO.load(dir).root.children[0].mask!!.tiles.keys().isEmpty())
    }

    @Test
    fun tileCopyIsInStoredHalfPrecision() {
        val d = doc()
        val dir = File(root, d.id)
        ProjectIO.save(d, dir, null)
        val tracker = SaveTracker()
        tracker.commitAll(d)
        val a = d.findNode("a") as RasterLayer
        a.pixels.setPixel(300, 40, 0.123456f, 0.5f, 0.25f, 1f)
        val delta = tracker.collect(d)
        val halves = delta.layers.getValue("a").changed.getValue(TileKey(1, 0))
        assertEquals(Tile.CHANNELS, halves.size)
        ProjectIO.saveDelta(d, dir, delta, null)
        val loaded = ProjectIO.load(dir).findNode("a") as RasterLayer
        val px = FloatArray(4)
        loaded.pixels.getPixel(300, 40, px)
        assertEquals(0.5f, px[1], 1e-2f)
        assertEquals(0.25f, px[2], 1e-2f)
    }

    @Test
    fun trackerForgetsDeletedLayers() {
        val d = doc()
        val tracker = SaveTracker()
        tracker.commitAll(d)
        d.root.remove(d.findNode("b")!!)
        val delta = tracker.collect(d)
        tracker.commit(delta)
        // The deleted layer is no longer tracked and the next capture only knows the survivors.
        assertEquals(setOf("a"), tracker.capture(d).layers.keys)
        assertTrue(tracker.collect(d).layers.isEmpty())
    }

    @Test
    fun thumbnailOnlyUpdateLeavesTilesAlone() {
        val d = doc()
        val dir = File(root, d.id)
        ProjectIO.save(d, dir, byteArrayOf(1))
        val before = bytes(File(dir, "layers/a/tiles.bin"))
        var seen: Document? = null
        ProjectIO.updateThumbnail(dir) { written ->
            seen = written
            byteArrayOf(4, 5, 6)
        }
        assertNotNull(seen)
        assertEquals(0.1f, (seen!!.findNode("a") as RasterLayer).pixels.get(TileKey(0, 0))!![0], 1e-2f)
        assertContentEquals(byteArrayOf(4, 5, 6), bytes(File(dir, "thumb.png")))
        assertContentEquals(before, bytes(File(dir, "layers/a/tiles.bin")))
        assertFalse(File(dir, "thumb.png.tmp").exists())
    }
}
