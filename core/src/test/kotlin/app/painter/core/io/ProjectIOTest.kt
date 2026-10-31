package app.painter.core.io

import app.painter.core.adjust.AdjustCodec
import app.painter.core.adjust.UnknownAdjust
import app.painter.core.blend.BlendMode
import app.painter.core.color.Rgba
import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.ColorTag
import app.painter.core.doc.Document
import app.painter.core.doc.EditTarget
import app.painter.core.doc.GroupNode
import app.painter.core.doc.LayerMask
import app.painter.core.doc.Node
import app.painter.core.doc.ProjectType
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.Half
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.vectors.Vectors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectIOTest {
    private val tmpRoot: File = createTempDirectory("painter-io").toFile()

    @AfterTest
    fun cleanup() {
        tmpRoot.deleteRecursively()
    }

    private fun randomTile(rnd: Random): Tile {
        val t = Tile()
        for (i in 0 until Tile.CHANNELS) t[i] = rnd.nextFloat()
        return t
    }

    private fun sampleDoc(): Document {
        val rnd = Random(42)
        val doc = Document(600, 300, name = "Sample", type = ProjectType.PATTERN, dpi = 144)
        doc.created = 1_000L
        doc.modified = 2_000L
        doc.paper.color = Rgba(0.2f, 0.4f, 0.6f, 1f)
        doc.paper.visible = false
        doc.gammaCorrect = true

        val bottom = RasterLayer("l-bottom", "Bottom", TileGrid(600, 300))
        bottom.pixels.put(TileKey(0, 0), randomTile(rnd))
        bottom.pixels.put(TileKey(2, 1), randomTile(rnd))
        bottom.pixels.put(TileKey(1, 0), Tile())
        bottom.alphaLock = true
        bottom.opacity = 0.5f
        bottom.blendMode = BlendMode.MULTIPLY

        val outer = GroupNode("g-outer", "Outer")
        outer.expanded = false
        outer.opacity = 0.75f
        outer.blendMode = BlendMode.NORMAL
        val inner = GroupNode("g-inner", "Inner")
        inner.visible = false
        val clippedLayer = RasterLayer("l-clip", "Clip", TileGrid(600, 300))
        clippedLayer.clipped = true
        clippedLayer.hideInRecording = true
        clippedLayer.locked = true
        clippedLayer.blendMode = BlendMode.SCREEN
        clippedLayer.pixels.put(TileKey(1, 1), randomTile(rnd))
        val empty = RasterLayer("l-empty", "Empty", TileGrid(600, 300))
        inner.add(empty)
        inner.add(clippedLayer)
        outer.add(inner)

        doc.root.add(bottom)
        doc.root.add(outer)
        doc.activeId = clippedLayer.id
        return doc
    }

    private fun quantized(t: Tile) = FloatArray(Tile.CHANNELS) { Half.toFloat(Half.fromFloat(t[it])) }

    private fun assertSameNode(a: Node, b: Node) {
        assertEquals(a.id, b.id)
        assertEquals(a.name, b.name)
        assertEquals(a::class, b::class)
        assertEquals(a.visible, b.visible)
        assertEquals(a.opacity, b.opacity)
        assertEquals(a.blendMode, b.blendMode)
        assertEquals(a.clipped, b.clipped)
        assertEquals(a.hideInRecording, b.hideInRecording)
        assertEquals(a.locked, b.locked)
        assertEquals(a.colorTag, b.colorTag)
        assertEquals(a.reference, b.reference)
        assertEquals(a.mask == null, b.mask == null)
        a.mask?.let { m ->
            val n = b.mask!!
            assertEquals(m.enabled, n.enabled); assertEquals(m.linked, n.linked); assertEquals(m.density, n.density)
            assertEquals(m.tiles.defaultValue, n.tiles.defaultValue)
            val kept = m.tiles.entries().filter { !it.value.isUniform(m.tiles.defaultValue) }.map { it.key }.toSet()
            assertEquals(kept, n.tiles.keys())
            for (k in kept) assertContentEquals(m.tiles.get(k)!!.data, n.tiles.get(k)!!.data)
        }
        when (a) {
            is AdjustmentLayer -> assertEquals(a.params, (b as AdjustmentLayer).params)
            is GroupNode -> {
                b as GroupNode
                assertEquals(a.expanded, b.expanded)
                assertEquals(a.children.size, b.children.size)
                a.children.zip(b.children).forEach { (x, y) -> assertSameNode(x, y) }
            }
            is RasterLayer -> {
                b as RasterLayer
                assertEquals(a.alphaLock, b.alphaLock)
                val nonEmpty = a.pixels.entries().filter { !it.value.isEmpty() }.map { it.key }.toSet()
                assertEquals(nonEmpty, b.pixels.keys())
                for (k in nonEmpty) assertContentEquals(quantized(a.pixels.get(k)!!), b.pixels.get(k)!!.toFloats())
            }
        }
    }

    private fun assertSameDoc(a: Document, b: Document) {
        assertEquals(a.id, b.id)
        assertEquals(a.name, b.name)
        assertEquals(a.type, b.type)
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        assertEquals(a.dpi, b.dpi)
        assertEquals(a.created, b.created)
        assertEquals(a.modified, b.modified)
        assertEquals(a.activeId, b.activeId)
        assertEquals(a.activeTarget, b.activeTarget)
        assertEquals(a.gammaCorrect, b.gammaCorrect)
        assertEquals(a.guides, b.guides)
        assertEquals(a.selection.active, b.selection.active)
        assertEquals(a.selection.mask.keys().filter { !a.selection.mask.get(it)!!.isEmpty() }.toSet(), b.selection.mask.keys())
        for (k in b.selection.mask.keys()) assertContentEquals(a.selection.mask.get(k)!!.data, b.selection.mask.get(k)!!.data)
        assertEquals(a.paper.visible, b.paper.visible)
        assertEquals(Math.round(a.paper.color.b * 255), Math.round(b.paper.color.b * 255))
        assertEquals(a.animation, b.animation)
        assertEquals(a.root.children.size, b.root.children.size)
        a.root.children.zip(b.root.children).forEach { (x, y) -> assertSameNode(x, y) }
    }

    @Test
    fun saveLoadRoundTrip() {
        val doc = sampleDoc()
        val dir = File(tmpRoot, doc.id)
        val thumb = byteArrayOf(1, 2, 3)
        ProjectIO.save(doc, dir, thumb)
        assertContentEquals(thumb, File(dir, "thumb.png").readBytes())
        assertFalse(FsUtil.tmpOf(dir).exists())
        assertFalse(FsUtil.oldOf(dir).exists())
        assertFalse(File(dir, FsUtil.COMPLETE_MARKER).exists())
        assertSameDoc(doc, ProjectIO.load(dir))

        doc.name = "Renamed"
        ProjectIO.save(doc, dir, null)
        val again = ProjectIO.load(dir)
        assertSameDoc(doc, again)
        assertFalse(File(dir, "thumb.png").exists())
    }

    @Test
    fun emptyTilesAreSkipped() {
        val doc = sampleDoc()
        val dir = File(tmpRoot, "p")
        ProjectIO.save(doc, dir, null)
        val idx = File(dir, "layers/l-bottom/tiles.idx").readBytes()
        assertEquals(2 * 12, idx.size)
        val bb = ByteBuffer.wrap(idx).order(ByteOrder.LITTLE_ENDIAN)
        val keys = (0 until 2).map {
            val tx = bb.short.toInt(); val ty = bb.short.toInt(); bb.int; bb.int
            tx to ty
        }.toSet()
        assertEquals(setOf(0 to 0, 2 to 1), keys)
        assertEquals(0, File(dir, "layers/l-empty/tiles.idx").length())
        assertEquals(0, File(dir, "layers/l-empty/tiles.bin").length())
        val loaded = ProjectIO.load(dir)
        assertTrue((loaded.findNode("l-empty") as RasterLayer).pixels.keys().isEmpty())
    }

    @Test
    fun unknownManifestFieldsAreIgnored() {
        val doc = sampleDoc()
        val dir = File(tmpRoot, "p")
        ProjectIO.save(doc, dir, null)
        val mf = File(dir, "manifest.json")
        val text = mf.readText().replaceFirst("{", "{\n  \"futureField\": {\"x\": [1, 2]},\n  \"guides\": null,")
            .replace("\"kind\": \"group\"", "\"kind\": \"group\", \"futureNodeField\": {\"enabled\": true}")
        mf.writeText(text)
        assertSameDoc(doc, ProjectIO.load(dir))
    }

    @Test
    fun pntExportImportRoundTrip() {
        val doc = sampleDoc()
        val dir = File(tmpRoot, "src")
        ProjectIO.save(doc, dir, byteArrayOf(9, 9))
        val bytes = ByteArrayOutputStream().also { ProjectIO.exportPnt(dir, it) }.toByteArray()

        val methods = HashMap<String, Int>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                methods[e.name] = e.method
            }
        }
        assertEquals(ZipEntry.STORED, methods["layers/l-bottom/tiles.bin"])
        assertEquals(ZipEntry.DEFLATED, methods["manifest.json"])
        assertEquals(ZipEntry.DEFLATED, methods["thumb.png"])

        val target = File(tmpRoot, "imported")
        ProjectIO.importPnt(ByteArrayInputStream(bytes), target)
        assertSameDoc(doc, ProjectIO.load(target))
        assertContentEquals(byteArrayOf(9, 9), File(target, "thumb.png").readBytes())
    }

    @Test
    fun leftoverIncompleteTmpIsIgnoredAndCleaned() {
        val doc = sampleDoc()
        val dir = File(tmpRoot, "p")
        ProjectIO.save(doc, dir, null)
        val tmp = FsUtil.tmpOf(dir)
        File(tmp, "layers/x").mkdirs()
        File(tmp, "manifest.json").writeText("{ broken")
        assertSameDoc(doc, ProjectIO.load(dir))
        assertFalse(tmp.exists())

        File(tmp, "manifest.json").also { it.parentFile.mkdirs() }.writeText("{ broken")
        ProjectIO.save(doc, dir, null)
        assertFalse(tmp.exists())
        assertSameDoc(doc, ProjectIO.load(dir))
    }

    @Test
    fun crashBetweenRenamesRestoresOldOrPromotesCompleteTmp() {
        val doc = sampleDoc()
        val dir = File(tmpRoot, "p")
        ProjectIO.save(doc, dir, null)

        // Crash after moving dir aside, before the new tmp was complete: old must be restored.
        dir.renameTo(FsUtil.oldOf(dir))
        File(FsUtil.tmpOf(dir), "manifest.json").also { it.parentFile.mkdirs() }.writeText("{")
        assertSameDoc(doc, ProjectIO.load(dir))
        assertFalse(FsUtil.oldOf(dir).exists())
        assertFalse(FsUtil.tmpOf(dir).exists())

        // Crash after moving dir aside with a complete tmp: the new version wins.
        val newer = sampleDoc().also { it.name = "Newer" }
        val staging = File(tmpRoot, "staging")
        ProjectIO.save(newer, staging, null)
        File(dir, "refs").mkdirs()
        File(dir, "refs/r.png").writeBytes(byteArrayOf(5))
        dir.renameTo(FsUtil.oldOf(dir))
        staging.renameTo(FsUtil.tmpOf(dir))
        File(FsUtil.tmpOf(dir), FsUtil.COMPLETE_MARKER).writeBytes(ByteArray(0))
        val loaded = ProjectIO.load(dir)
        assertEquals("Newer", loaded.name)
        assertContentEquals(byteArrayOf(5), File(dir, "refs/r.png").readBytes())
        assertFalse(FsUtil.oldOf(dir).exists())
        assertFalse(File(dir, FsUtil.COMPLETE_MARKER).exists())
    }

    @Test
    fun foreignEntriesSurviveSave() {
        val doc = sampleDoc()
        val dir = File(tmpRoot, "p")
        ProjectIO.save(doc, dir, null)
        File(dir, "history/1").mkdirs()
        File(dir, "history/1/a.bin").writeBytes(byteArrayOf(7))
        ProjectIO.save(doc, dir, null)
        assertContentEquals(byteArrayOf(7), File(dir, "history/1/a.bin").readBytes())
    }

    @Test
    fun activeIdOfMissingNodeIsDropped() {
        val doc = sampleDoc()
        doc.activeId = "nope"
        val dir = File(tmpRoot, "p")
        ProjectIO.save(doc, dir, null)
        assertNull(ProjectIO.load(dir).activeId)
        assertNotNull(ProjectIO.load(dir).findNode("l-clip"))
    }

    private fun maskTile(seed: Int): MaskTile = MaskTile().also { t ->
        val rnd = Random(seed)
        for (i in t.data.indices) t.data[i] = rnd.nextInt(256).toByte()
    }

    private fun schema2Doc(): Document {
        val doc = sampleDoc()
        val bottom = doc.findNode("l-bottom")!!
        bottom.colorTag = ColorTag.VIOLET
        bottom.reference = true
        bottom.mask = LayerMask(MaskGrid(600, 300, -1).also {
            it.put(TileKey(0, 0), maskTile(1))
            it.put(TileKey(1, 0), MaskTile().also { t -> t.fill(-1) })
        }).also { it.density = 0.6f; it.linked = false }
        val outer = doc.findNode("g-outer") as GroupNode
        outer.mask = LayerMask(MaskGrid(600, 300, 0).also { it.put(TileKey(2, 1), maskTile(2)) }).also { it.enabled = false }
        for ((i, p) in Vectors.adjustSets().withIndex()) {
            val a = AdjustmentLayer("adj-$i", "A$i", p)
            a.opacity = 0.5f
            a.blendMode = BlendMode.SOFT_LIGHT
            a.clipped = i % 2 == 1
            if (i == 0) a.mask = LayerMask(MaskGrid(600, 300, 0))
            outer.add(a)
        }
        outer.add(AdjustmentLayer("adj-unknown", "U", AdjustCodec.decode("""{"kind":"zz_future","k":[1,{"x":2}]}""")))
        doc.activeId = "l-bottom"
        doc.activeTarget = EditTarget.MASK
        doc.selection.mask.put(TileKey(1, 1), maskTile(3))
        doc.selection.active = false
        doc.guides = AdjustCodec.toJson(UnknownAdjust(JsonObject(mapOf("kind" to JsonPrimitive("lines"), "on" to JsonPrimitive(true)))))
        return doc
    }

    @Test
    fun schema2RoundTrip() {
        val doc = schema2Doc()
        val dir = File(tmpRoot, "p2")
        ProjectIO.save(doc, dir, null)
        val text = File(dir, "manifest.json").readText()
        assertTrue(text.contains("\"schema\": 2"))
        assertTrue(text.contains("\"kind\": \"adjustment\""))
        assertTrue(File(dir, "masks/l-bottom/tiles.idx").isFile)
        assertEquals(12, File(dir, "masks/l-bottom/tiles.idx").length(), "full-default tile skipped")
        assertFalse(File(dir, "masks/adj-0").exists(), "uniform mask writes no tiles")
        assertTrue(File(dir, "selection/tiles.bin").isFile)
        val loaded = ProjectIO.load(dir)
        assertSameDoc(doc, loaded)
        assertEquals("l-bottom", loaded.referenceNode?.id)
        ProjectIO.save(loaded, dir, null)
        assertSameDoc(doc, ProjectIO.load(dir))
    }

    @Test
    fun emptySelectionIsNotWritten() {
        val doc = sampleDoc()
        val dir = File(tmpRoot, "p")
        ProjectIO.save(doc, dir, null)
        assertFalse(File(dir, "manifest.json").readText().contains("\"selection\""))
        assertFalse(File(dir, "selection").exists())
        assertTrue(ProjectIO.load(dir).selection.isEmpty)
    }

    @Test
    fun schema1ReadsAsSchema2() {
        val dir = File(tmpRoot, "old")
        dir.mkdirs()
        File(dir, "manifest.json").writeText(
            """{"schema":1,"app":"painter","id":"x","name":"Old","width":64,"height":64,
               "nodes":[{"id":"a","name":"A","kind":"raster"},{"id":"g","name":"G","kind":"group","blendMode":"passthrough",
               "children":[{"id":"b","name":"B","kind":"raster","opacity":0.5}]}],"activeId":"b"}""",
        )
        val doc = ProjectIO.load(dir)
        assertEquals(EditTarget.LAYER, doc.activeTarget)
        assertEquals("b", doc.activeId)
        val b = doc.findNode("b")!!
        assertEquals(ColorTag.NONE, b.colorTag)
        assertNull(b.mask)
        assertFalse(b.reference)
        assertNull(doc.guides)
        assertTrue(doc.selection.isEmpty)
        ProjectIO.save(doc, dir, null)
        assertTrue(File(dir, "manifest.json").readText().contains("\"schema\": 2"))
    }

    @Test
    fun unknownNodeKindIsSkipped() {
        val dir = File(tmpRoot, "unk")
        dir.mkdirs()
        File(dir, "manifest.json").writeText(
            """{"schema":2,"id":"x","name":"N","width":64,"height":64,
               "nodes":[{"id":"a","name":"A","kind":"raster"},{"id":"v","name":"V","kind":"vector3d"},
               {"id":"g","name":"G","kind":"group","children":[{"id":"t","name":"T","kind":"text"}]}]}""",
        )
        val warnings = ArrayList<String>()
        val prev = ProjectIO.warn
        ProjectIO.warn = { warnings += it }
        try {
            val doc = ProjectIO.load(dir)
            assertEquals(listOf("a", "g"), doc.root.children.map { it.id })
            assertTrue((doc.findNode("g") as GroupNode).children.isEmpty())
            assertEquals(2, warnings.size)
        } finally {
            ProjectIO.warn = prev
        }
    }
}
