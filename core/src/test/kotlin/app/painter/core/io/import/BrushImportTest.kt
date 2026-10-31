package app.painter.core.io.import

import app.painter.core.io.BrushIO
import app.painter.core.stroke.Brush
import app.painter.core.stroke.BrushName
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.ResourceRefs
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Import of our own and foreign brush files (spec/tools/brush-import.md). */
class BrushImportTest {

    private fun pbrush(): ByteArray {
        val out = ByteArrayOutputStream()
        BrushIO.writePbrush(
            Brush(
                id = "user.test",
                name = BrushName("Round"),
                params = BrushParams(size = 42f),
                resources = ResourceRefs(head = "brush:head.png"),
            ),
            mapOf("head.png" to PngWriter.rgba(2, 2, ByteArray(16) { 0xFF.toByte() })),
            out,
        )
        return out.toByteArray()
    }

    @Test
    fun ownPbrushKeepsItsFiles() {
        val r = BrushImport.parse("round.pbrush", pbrush())
        assertEquals(1, r.brushes.size)
        assertEquals("Round", r.brushes[0].brush.name.text)
        assertTrue(r.brushes[0].files.containsKey("head.png"))
    }

    @Test
    fun ownPackKeepsTheManifestOrder() {
        val entries = listOf(
            BrushIO.PackEntry(Brush(id = "a", name = BrushName("A")), emptyMap()),
            BrushIO.PackEntry(Brush(id = "b", name = BrushName("B")), emptyMap()),
        )
        val out = ByteArrayOutputStream()
        BrushIO.writePack("Ink", entries, out)
        val r = BrushImport.parse("ink.pbrushes", out.toByteArray())
        assertEquals("Painter pack \"Ink\"", r.format)
        assertEquals(listOf("A", "B"), r.brushes.map { it.brush.name.text })
    }

    @Test
    fun myPaintMapsTheMainParameters() {
        val json = """{"name":"Pen","radius":12,"hardness":0.4,"opacity":0.8,"spacing":0.2}"""
        val r = BrushImport.parse("pen.myb", json.encodeToByteArray())
        val p = r.brushes.single().brush.params
        assertEquals(24f, p.size)
        assertEquals(0.4f, p.hardness)
        assertEquals(0.8f, p.opacity)
        assertEquals(0.2f, p.spacing)
        assertEquals("Pen", r.brushes.single().brush.name.text)
    }

    @Test
    fun gimpBrushBecomesAPngHead() {
        val out = ByteArrayOutputStream()
        out.write("GIMP Brush\u0000\u0000".encodeToByteArray())
        fun int(v: Int) {
            out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v)
        }
        int(2); int(2); int(2); int(1)
        out.write(byteArrayOf(0, 127, -1, 0))
        out.write("Dot\u0000".encodeToByteArray())
        val r = BrushImport.parse("dot.gbr", out.toByteArray())
        val imp = r.brushes.single()
        assertEquals("Dot", imp.brush.name.text)
        val png = imp.files.getValue("head.png")
        assertEquals(0x89.toByte(), png[0])
        assertEquals("PNG", String(png, 1, 3, Charsets.US_ASCII))
    }

    @Test
    fun aBadFileGivesAnError() {
        val e = runCatching { BrushImport.parse("broken.gbr", ByteArray(4)) }.exceptionOrNull()
        assertTrue(e is BrushImport.BrushFileException)
    }

    @Test
    fun procreateShapeAndGrainAreMapped() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("Brush.plist"))
            z.write(
                """<?xml version="1.0"?><plist><dict>
                   <key>name</key><string>Grainy</string>
                   <key>spacing</key><real>0.12</real>
                   <key>jitter</key><real>0.3</real>
                   </dict></plist>""".trimIndent().encodeToByteArray(),
            )
            z.closeEntry()
            z.putNextEntry(ZipEntry("Shape.png"))
            z.write(PngWriter.rgba(2, 2, ByteArray(16) { 0xFF.toByte() }))
            z.closeEntry()
            z.putNextEntry(ZipEntry("Grain.png"))
            z.write(PngWriter.rgba(2, 2, ByteArray(16) { 0xFF.toByte() }))
            z.closeEntry()
        }
        val r = BrushImport.parse("grainy.brushset", out.toByteArray())
        val imp = r.brushes.single()
        assertEquals("Grainy", imp.brush.name.text)
        assertEquals("brush:head.png", imp.brush.resources.head)
        assertEquals("brush:texture.png", imp.brush.resources.texture)
        assertEquals(0.12f, imp.brush.params.spacing)
        assertEquals(2, imp.files.size)
    }

    @Test
    fun imageCanBeAHeadOrATexture() {
        val png = PngWriter.rgba(1, 1, byteArrayOf(0, 0, 0, -1))
        val head = BrushImport.parse("stamp.png", png, asTexture = false).brushes.single().brush
        val texture = BrushImport.parse("paper.png", png, asTexture = true).brushes.single().brush
        assertEquals("brush:head.png", head.resources.head)
        assertEquals("brush:texture.png", texture.resources.texture)
        assertTrue(head.params.texture == null)
        assertTrue(texture.params.texture != null)
    }
}
