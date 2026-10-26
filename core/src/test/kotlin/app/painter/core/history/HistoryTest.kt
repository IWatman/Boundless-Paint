package app.painter.core.history

import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {
    private class Counter(override val name: String, override val byteSize: Long = 1) : Command {
        var value = 0
        override fun apply(doc: Document) { value++ }
        override fun revert(doc: Document) { value-- }
    }

    private fun stroke(doc: Document, layerId: String, x: Int, y: Int, r: Float): TilePatch {
        val grid = doc.findNode(layerId).let { (it as RasterLayer).pixels }
        val keys = grid.keysInRect(x, y, x + 10, y + 10)
        val before = snapshotTiles(grid, keys)
        fillRect(grid, x, y, x + 10, y + 10, r, 0.5f, 0.2f, 1f)
        return TilePatch(layerId, before, snapshotTiles(grid, keys))
    }

    @Test
    fun undoRedoStrokesRestoreExactState() {
        val doc = Document.create(600, 400)
        val id = doc.activeId!!
        val h = History()
        val states = mutableListOf(fingerprint(doc))
        h.push(TilePatchCommand(stroke(doc, id, 10, 10, 1f)))
        states += fingerprint(doc)
        h.push(TilePatchCommand(stroke(doc, id, 250, 250, 0.3f)))
        states += fingerprint(doc)
        h.push(TilePatchCommand(stroke(doc, id, 15, 15, 0f)))
        states += fingerprint(doc)
        assertEquals(4, states.distinct().size)

        for (i in 2 downTo 0) {
            assertTrue(h.undo(doc))
            assertEquals(states[i], fingerprint(doc))
        }
        assertFalse(h.undo(doc))
        assertTrue(doc.findNode(id).let { (it as RasterLayer).pixels.keys().isEmpty() })
        for (i in 1..3) {
            assertTrue(h.redo(doc))
            assertEquals(states[i], fingerprint(doc))
        }
        assertFalse(h.redo(doc))
    }

    @Test
    fun historyIsImmuneToLaterLayerMutation() {
        val doc = Document.create(300, 300)
        val id = doc.activeId!!
        val grid = doc.activeRaster!!.pixels
        val h = History()
        h.push(TilePatchCommand(stroke(doc, id, 0, 0, 1f)))
        val afterFirst = fingerprint(doc)
        h.undo(doc)
        h.redo(doc)
        // Mutate the tile now in the grid without recording; history snapshots must stay intact.
        val before = snapshotTiles(grid, listOf(TileKey(0, 0)))
        fillRect(grid, 0, 0, 5, 5, 0f, 0f, 1f, 1f)
        h.push(TilePatchCommand(TilePatch(id, before, snapshotTiles(grid, listOf(TileKey(0, 0))))))
        h.undo(doc)
        assertEquals(afterFirst, fingerprint(doc))
        h.redo(doc)
        h.undo(doc)
        assertEquals(afterFirst, fingerprint(doc))
    }

    @Test
    fun applyPutsCopiesAndMarksDirty() {
        val doc = Document.create(256, 256)
        val t = Tile()
        t[3] = 1f
        val cmd = TilePatchCommand(TilePatch(doc.activeId!!, mapOf(TileKey(0, 0) to null), mapOf(TileKey(0, 0) to t)))
        cmd.apply(doc)
        val inGrid = doc.activeRaster!!.pixels.get(0, 0)!!
        assertTrue(inGrid !== t)
        assertTrue(inGrid.version > t.version)
        inGrid[3] = 0f
        assertEquals(1f, t[3])
        cmd.revert(doc)
        assertNull(doc.activeRaster!!.pixels.get(0, 0))
    }

    @Test
    fun pushClearsRedo() {
        val doc = Document.create(10, 10)
        val h = History()
        val a = Counter("a"); val b = Counter("b"); val c = Counter("c")
        h.execute(doc, a); h.execute(doc, b)
        h.undo(doc)
        assertTrue(h.canRedo)
        assertEquals("b", h.redoName)
        h.execute(doc, c)
        assertFalse(h.canRedo)
        assertNull(h.redoName)
        assertEquals("c", h.undoName)
        assertEquals(0, b.value)
        assertEquals(2L, h.totalBytes)
    }

    @Test
    fun evictsByStepCount() {
        val doc = Document.create(10, 10)
        val h = History(maxSteps = 3)
        val cmds = (0 until 5).map { Counter("c$it") }
        cmds.forEach { h.execute(doc, it) }
        assertEquals(3, h.undoSize)
        repeat(3) { assertTrue(h.undo(doc)) }
        assertFalse(h.undo(doc))
        assertEquals(listOf(1, 1, 0, 0, 0), cmds.map { it.value })
    }

    @Test
    fun evictsByBytes() {
        val doc = Document.create(10, 10)
        val h = History(maxBytes = 250)
        h.execute(doc, Counter("a", 100))
        h.execute(doc, Counter("b", 100))
        assertEquals(2, h.undoSize)
        h.execute(doc, Counter("c", 100))
        assertEquals(2, h.undoSize)
        assertEquals(200L, h.totalBytes)
        h.execute(doc, Counter("huge", 1000))
        assertEquals(1, h.undoSize)
        assertEquals("huge", h.undoName)
    }

    @Test
    fun onChangedAndModified() {
        val doc = Document.create(10, 10)
        var calls = 0
        val h = History()
        h.onChanged = { calls++ }
        val m0 = doc.modified
        h.execute(doc, ClearLayer(doc.activeId!!))
        assertTrue(doc.modified > m0)
        val m1 = doc.modified
        h.undo(doc)
        assertTrue(doc.modified > m1)
        h.redo(doc)
        h.clear()
        assertEquals(4, calls)
        assertFalse(h.canUndo || h.canRedo)
    }

    @Test
    fun compoundRevertsInReverse() {
        val doc = Document.create(10, 10)
        val first = doc.activeId!!
        val l2 = doc.newRasterLayer("L2")
        val cmd = CompoundCommand("Add+rename", listOf(AddLayer(l2), SetNodeProps(l2.id, newName = "Renamed")))
        val before = fingerprint(doc)
        val h = History()
        h.execute(doc, cmd)
        assertEquals("Renamed", doc.findNode(l2.id)!!.name)
        h.undo(doc)
        assertEquals(before, fingerprint(doc))
        assertEquals(first, doc.activeId)
        h.redo(doc)
        assertEquals("Renamed", doc.findNode(l2.id)!!.name)
        assertNotEquals(before, fingerprint(doc))
    }
}
