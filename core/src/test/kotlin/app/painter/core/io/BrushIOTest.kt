package app.painter.core.io

import app.painter.core.stroke.Brush
import app.painter.core.stroke.BrushName
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.Curve
import app.painter.core.stroke.DynSource
import app.painter.core.stroke.DynTarget
import app.painter.core.stroke.DynamicsLink
import app.painter.core.stroke.HeadParams
import app.painter.core.stroke.HeadSource
import app.painter.core.stroke.JitterParams
import app.painter.core.stroke.Profiles
import app.painter.core.stroke.ResourceRefs
import app.painter.core.stroke.RotationMode
import app.painter.core.stroke.TextureMode
import app.painter.core.stroke.TextureParams
import app.painter.core.stroke.WatercolorParams
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BrushIOTest {
    private val specExample = """
        { "schema": 1, "id": "pencil.graphite_hb", "parentId": null, "name": {"ru": "Графит HB", "en": "Graphite HB"},
          "category": "pencils", "tags": ["графит", "graphite", "sketch"],
          "params": { "size": 6, "sizeMax": 60, "opacity": 0.9, "flow": 0.55, "spacing": 0.08, "hardness": 0.9,
            "head": {"source": "bitmap", "aspect": 0, "rotationMode": "stylus-tilt", "depth": 0.2},
            "jitter": {"size": 0.05, "angle": 1.0},
            "texture": {"mode": "canvas-fixed", "depth": 0.7, "scale": 0.5, "lowerLimit": 0.4},
            "dynamics": [ {"source": "PRESSURE", "target": "FLOW", "curve": {"points": [[0,0],[1,1]], "min": 0.1}},
                          {"source": "TILT", "target": "SIZE", "curve": {"points": [[0,0],[1,1]], "min": 0.5}} ] },
          "resources": { "head": "asset:heads/grain_disk_01.png", "texture": "asset:textures/graphite_grain.png" },
          "futureField": {"x": 1} }
    """.trimIndent()

    /** ZIP with [entries] chunks of 22 MiB: three of them exceed the 64 MiB archive cap. */
    private fun bigZip(entries: Int): ByteArray {
        val chunk = ByteArray(22 * 1024 * 1024)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for (i in 0 until entries) {
                z.putNextEntry(ZipEntry("part$i.bin"))
                z.write(chunk)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Renames the second entry to the first name; ZipOutputStream itself refuses duplicate names. */
    private fun duplicateNameZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("dup1.bin"))
            z.write(byteArrayOf(1))
            z.closeEntry()
            z.putNextEntry(ZipEntry("dup2.bin"))
            z.write(byteArrayOf(2))
            z.closeEntry()
        }
        val text = String(out.toByteArray(), Charsets.ISO_8859_1)
        return text.replace("dup2.bin", "dup1.bin").toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun rejectsArchiveAboveTotalDecompressedCap() {
        val e = assertFailsWith<IOException> { BrushIO.readPbrush(ByteArrayInputStream(bigZip(3))) }
        assertTrue(e.message!!.contains("too large"), e.message!!)
    }

    @Test
    fun rejectsPackWhoseNestedBrushesExceedTotalCap() {
        val chunk = ByteArray(22 * 1024 * 1024)
        val brush = full().copy(resources = ResourceRefs(head = "brush:big.bin"))
        val out = ByteArrayOutputStream()
        BrushIO.writePack("Big", List(3) { BrushIO.PackEntry(brush, mapOf("big.bin" to chunk)) }, out)
        val e = assertFailsWith<IOException> { BrushIO.readPack(ByteArrayInputStream(out.toByteArray())) }
        assertTrue(e.message!!.contains("too large"), e.message!!)
    }

    @Test
    fun rejectsArchiveWithTooManyEntries() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for (i in 0..512) {
                z.putNextEntry(ZipEntry("e$i.bin"))
                z.write(byteArrayOf(1))
                z.closeEntry()
            }
        }
        val e = assertFailsWith<IOException> { BrushIO.readPbrush(ByteArrayInputStream(out.toByteArray())) }
        assertTrue(e.message!!.contains("more than 512 entries"), e.message!!)
    }

    @Test
    fun rejectsDuplicateEntryNames() {
        val e = assertFailsWith<IOException> { BrushIO.readPbrush(ByteArrayInputStream(duplicateNameZip())) }
        assertTrue(e.message!!.contains("duplicate entry"), e.message!!)
    }

    private fun full() = Brush(
        id = "u-1", parentId = "pencil.hb", name = BrushName.of("ru" to "Мой", "en" to "Mine"), category = "pencils",
        tags = listOf("a"),
        params = BrushParams(
            size = 33f, pressureToFlow = Curve(listOf(0f to 0f, 0.5f to 0.7f, 1f to 1f), 0.1f), afterSmoothing = 20f,
            head = HeadParams(source = HeadSource.BITMAP, rotationMode = RotationMode.TANGENT, aspect = -0.3f),
            jitter = JitterParams(scatter = 0.4f, count = 3),
            texture = TextureParams(mode = TextureMode.MOVING),
            special = WatercolorParams(bleed = 0.7f),
            dynamics = listOf(DynamicsLink(DynSource.VELOCITY, DynTarget.SIZE, Curve.linear(0.3f))),
            profiles = Profiles(size = Curve.LINEAR),
        ),
        resources = ResourceRefs(head = "brush:head.png"),
    )

    @Test
    fun parsesSpecExampleIgnoringUnknownFields() {
        val b = BrushIO.decode(specExample)
        assertEquals("pencil.graphite_hb", b.id)
        assertEquals("Graphite HB", b.name.text)
        assertEquals("Графит HB", b.name.get("ru"))
        assertEquals(6f, b.params.size)
        assertEquals(HeadSource.BITMAP, b.params.head.source)
        assertEquals(RotationMode.STYLUS_TILT, b.params.head.rotationMode)
        assertEquals(0.4f, b.params.texture!!.lowerLimit)
        assertEquals(2, b.params.dynamics.size)
        assertEquals(0.1f, b.params.dynamics[0].curve.min)
        assertEquals(BrushParams().smoothing, b.params.smoothing, "missing = default")
    }

    @Test
    fun jsonRoundTrip() {
        val b = full()
        val text = BrushIO.encode(b)
        assertTrue(text.contains("\"schema\": 1"))
        assertTrue(text.contains("[\n") || text.contains("[0.0"), text)
        assertEquals(b, BrushIO.decode(text))
        val plain = b.copy(name = BrushName("User brush"))
        assertTrue(BrushIO.encode(plain).contains("\"name\": \"User brush\""))
        assertEquals(plain, BrushIO.decode(BrushIO.encode(plain)))
    }

    @Test
    fun readsM1CurveForm() {
        val text = """{"id":"x","params":{"pressureToSize":{"points":[{"first":0.0,"second":0.0},{"first":1.0,"second":1.0}],"min":0.3}}}"""
        assertEquals(0.3f, BrushIO.decode(text).params.pressureToSize.min)
    }

    @Test
    fun pbrushRoundTripEmbedsAssets() {
        val b = full().copy(resources = ResourceRefs(head = "brush:head.png", texture = "asset:textures/paper.png"))
        val head = byteArrayOf(1, 2, 3)
        val tex = byteArrayOf(9, 8)
        val out = ByteArrayOutputStream()
        BrushIO.writePbrush(b, mapOf("head.png" to head), out) { if (it == "textures/paper.png") tex else null }
        val (back, files) = BrushIO.readPbrush(ByteArrayInputStream(out.toByteArray()))
        assertEquals("brush:texture.png", back.resources.texture)
        assertEquals("brush:head.png", back.resources.head)
        assertContentEquals(head, files["head.png"])
        assertContentEquals(tex, files["texture.png"])
        assertEquals(b.copy(resources = back.resources), back)
    }

    @Test
    fun packRoundTrip() {
        val a = full()
        val b = full().copy(id = "u-1", resources = ResourceRefs())
        val out = ByteArrayOutputStream()
        BrushIO.writePack("My pack", listOf(BrushIO.PackEntry(a, mapOf("head.png" to byteArrayOf(5))), BrushIO.PackEntry(b, emptyMap())), out)
        val (name, list) = BrushIO.readPack(ByteArrayInputStream(out.toByteArray()))
        assertEquals("My pack", name)
        assertEquals(listOf(a, b), list.map { it.brush })
        assertContentEquals(byteArrayOf(5), list[0].files["head.png"])
    }
}
