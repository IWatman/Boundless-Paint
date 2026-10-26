package app.painter.core.io

import app.painter.core.doc.Document
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectStoreTest {
    private val root: File = createTempDirectory("painter-store").toFile()
    private val store = ProjectStore(root)

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun create(name: String, modified: Long, thumb: Boolean = false): Document {
        val doc = Document.create(320, 240, name)
        doc.modified = modified
        doc.activeRaster!!.pixels.setPixel(10, 10, 0.5f, 0.5f, 0.5f, 1f)
        ProjectIO.save(doc, store.dirFor(doc.id), if (thumb) byteArrayOf(1) else null)
        return doc
    }

    @Test
    fun listSortedByModifiedDesc() {
        val a = create("A", 100)
        val b = create("B", 300, thumb = true)
        val c = create("C", 200)
        File(root, "garbage").mkdirs()
        val list = store.list()
        assertEquals(listOf(b.id, c.id, a.id), list.map { it.id })
        assertEquals("B", list[0].name)
        assertEquals(320, list[0].width)
        assertEquals(240, list[0].height)
        assertEquals(300, list[0].modified)
        assertNotNull(list[0].thumbFile)
        assertNull(list[1].thumbFile)
    }

    @Test
    fun renameKeepsOtherFieldsAndUnknownKeys() {
        val a = create("A", 100)
        val mf = File(store.dirFor(a.id), "manifest.json")
        mf.writeText(mf.readText().replaceFirst("{", "{\"futureField\": 42,"))
        store.rename(a.id, "Renamed")
        assertTrue(mf.readText().contains("futureField"))
        val loaded = ProjectIO.load(store.dirFor(a.id))
        assertEquals("Renamed", loaded.name)
        assertEquals(a.activeId, loaded.activeId)
        assertEquals("Renamed", store.list().single().name)
    }

    @Test
    fun duplicateRewritesIdAndName() {
        val a = create("A", 100)
        val copyId = store.duplicate(a.id)
        assertNotEquals(a.id, copyId)
        val copy = ProjectIO.load(store.dirFor(copyId))
        assertEquals(copyId, copy.id)
        assertEquals("A copy", copy.name)
        assertEquals(1, copy.activeRaster!!.pixels.keys().size)
        assertEquals(setOf(a.id, copyId), store.list().map { it.id }.toSet())
        assertEquals("A", ProjectIO.load(store.dirFor(a.id)).name)
    }

    @Test
    fun deleteRemovesProject() {
        val a = create("A", 100)
        val b = create("B", 200)
        FsUtil.tmpOf(store.dirFor(a.id)).mkdirs()
        store.delete(a.id)
        assertFalse(store.dirFor(a.id).exists())
        assertFalse(FsUtil.tmpOf(store.dirFor(a.id)).exists())
        assertEquals(listOf(b.id), store.list().map { it.id })
    }

    @Test
    fun recoverCleansLeftoverTmp() {
        val a = create("A", 100)
        val tmp = FsUtil.tmpOf(store.dirFor(a.id))
        File(tmp, "layers").mkdirs()
        File(tmp, "manifest.json").writeText("{\"id\":\"zzz\",\"name\":\"half-written\"}")
        val orphan = File(root, "orphan.tmp").also { it.mkdirs() }
        assertEquals(listOf(a.id), store.list().map { it.id })
        assertTrue(tmp.exists(), "list() must not touch in-progress saves")
        store.recoverInterrupted()
        assertEquals(listOf(a.id), store.list().map { it.id })
        assertFalse(tmp.exists())
        assertFalse(orphan.exists())
    }
}
