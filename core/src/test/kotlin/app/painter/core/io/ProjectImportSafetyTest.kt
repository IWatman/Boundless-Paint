package app.painter.core.io

import app.painter.core.doc.Document
import app.painter.core.doc.VectorLayer
import app.painter.core.raster.TileGrid
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectImportSafetyTest {
    private val root = createTempDirectory("painter-import-safety").toFile()

    @AfterTest
    fun cleanup() { root.deleteRecursively() }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun files(dir: File): Map<String, List<Byte>> = dir.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes().toList() }

    @Test
    fun extractionLimitsPreserveExistingProjectAndRemoveStaging() {
        val target = File(root, "project")
        ProjectIO.save(Document(16, 16, name = "Existing"), target, byteArrayOf(1, 2))
        val before = files(target)
        val cases = listOf(
            archive("manifest.json" to ByteArray(5)) to ProjectIO.ArchiveLimits(10, 4, 100),
            archive("manifest.json" to ByteArray(3), "other" to ByteArray(3)) to ProjectIO.ArchiveLimits(10, 4, 5),
            archive("manifest.json" to ByteArray(1), "other" to ByteArray(1)) to ProjectIO.ArchiveLimits(1, 10, 10),
            archive("manifest.json" to ByteArray(1), "../escape" to ByteArray(1)) to ProjectIO.ArchiveLimits(10, 10, 100),
            archive("other" to ByteArray(1)) to ProjectIO.ArchiveLimits(10, 10, 100),
        )
        for ((bytes, limits) in cases) {
            assertFailsWith<IOException> { ProjectIO.importPnt(ByteArrayInputStream(bytes), target, limits) }
            assertEquals(before, files(target))
            assertFalse(FsUtil.tmpOf(target).exists())
        }
        assertFalse(File(root, "escape").exists())
    }

    @Test
    fun exactLimitsSucceedAndInputRemainsOpen() {
        val bytes = archive("manifest.json" to ByteArray(3), "other" to ByteArray(2))
        val input = object : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val target = File(root, "exact")
        ProjectIO.importPnt(input, target, ProjectIO.ArchiveLimits(2, 3, 5))
        assertFalse(input.closed)
        assertEquals(3L, File(target, "manifest.json").length())
        assertEquals(2L, File(target, "other").length())
        assertFalse(FsUtil.tmpOf(target).exists())
    }

    @Test
    fun inputFailureRemovesStagingWithoutTouchingExistingProject() {
        val target = File(root, "project")
        ProjectIO.save(Document(16, 16), target, null)
        val before = files(target)
        val bytes = archive("manifest.json" to ByteArray(100_000) { (it % 251).toByte() })
        val input = object : InputStream() {
            var pos = 0
            override fun read(): Int {
                if (pos >= bytes.size / 2) throw IOException("injected read failure")
                return bytes[pos++].toInt() and 0xff
            }
        }
        assertFailsWith<IOException> { ProjectIO.importPnt(input, target) }
        assertEquals(before, files(target))
        assertFalse(FsUtil.tmpOf(target).exists())
    }

    @Test
    fun malformedVectorDataFailsLoadWithoutRewritingManifestOrCache() {
        val doc = Document(16, 16)
        val layer = VectorLayer("vector", "Vector", TileGrid(16, 16), emptyList())
        layer.pixels.setPixel(2, 2, 1f, 0f, 0f, 1f)
        doc.root.add(layer)
        val target = File(root, "vector-project")
        ProjectIO.save(doc, target, null)
        val manifest = File(target, "manifest.json")
        val original = manifest.readText()
        val invalid = original.replace(Regex("\"strokes\"\\s*:\\s*\\["), "\"strokes\": \"invalid\", \"ignored\": [")
        assertTrue(invalid != original)
        manifest.writeText(invalid)
        val before = files(target)
        val cache = File(target, "layers/vector/tiles.bin").readBytes()
        assertTrue(cache.isNotEmpty())
        assertFailsWith<IOException> { ProjectIO.load(target) }
        assertEquals(before, files(target))
        assertContentEquals(cache, File(target, "layers/vector/tiles.bin").readBytes())
    }
}
