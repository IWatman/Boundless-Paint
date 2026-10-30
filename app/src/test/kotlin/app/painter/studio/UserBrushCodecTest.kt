package app.painter.studio

import app.painter.core.stroke.Brush
import app.painter.core.stroke.BrushName
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.HeadParams
import app.painter.core.stroke.ResourceRefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserBrushCodecTest {

    private val params = BrushParams(size = 12f, opacity = 0.75f, flow = 0.5f, spacing = 0.25f)

    private val resources = ResourceRefs(head = "asset:heads/grain_disk_01.png", texture = "asset:textures/graphite_grain.png")

    @Test
    fun newIdsAreUserIds() {
        val first = UserBrushCodec.newId()
        val second = UserBrushCodec.newId()
        assertTrue(first.startsWith("user."))
        assertTrue(UserBrushCodec.isUser(first))
        assertFalse(UserBrushCodec.isUser("pencils.graphite"))
        assertFalse(UserBrushCodec.isUser(null))
        // The same millisecond must not produce the same id twice.
        assertTrue(first != second)
        assertEquals("$first.json", UserBrushCodec.fileName(first))
    }

    @Test
    fun fileNamesStayInsideTheDirectory() {
        assertEquals("user.abc-123.json", UserBrushCodec.fileName("user.abc-123"))
        // A preset id, a path separator and a parent directory are all rejected.
        assertNull(UserBrushCodec.fileName("pencils.graphite"))
        assertNull(UserBrushCodec.fileName("user.a/b"))
        assertNull(UserBrushCodec.fileName("user.."))
        assertNull(UserBrushCodec.fileName("user."))
        assertNull(UserBrushCodec.fileName(""))
    }

    @Test
    fun createdBrushCarriesParentAndResources() {
        val brush = UserBrushCodec.create(
            parentId = "pencils.grafit",
            name = "  Grafit soft  ",
            params = params,
            resources = resources,
            id = "user.1",
        )
        assertEquals("user.1", brush.id)
        assertEquals("pencils.grafit", brush.parentId)
        assertEquals(UserBrushCodec.CATEGORY, brush.category)
        assertEquals("Grafit soft", brush.name.text)
        assertEquals(params, brush.params)
        assertEquals(resources, brush.resources)
    }

    @Test
    fun encodedBrushIsAPresetFile() {
        val brush = UserBrushCodec.create("pencils.grafit", "Grafit copy", params, resources, id = "user.7")
        val json = UserBrushCodec.encode(brush)
        // The same schema, id and name form the built-in presets use (§3.2): a plain string name, no nulls.
        assertTrue(json.contains("\"schema\": 1"))
        assertTrue(json.contains("\"id\": \"user.7\""))
        assertTrue(json.contains("\"parentId\": \"pencils.grafit\""))
        assertTrue(json.contains("\"category\": \"created\""))
        assertTrue(json.contains("\"name\": \"Grafit copy\""))
        assertTrue(json.contains("asset:heads/grain_disk_01.png"))
        assertFalse(json.contains("null"))
    }

    @Test
    fun roundTripKeepsTheBrush() {
        val brush = UserBrushCodec.create("pencils.grafit", "Grafit copy", params.copy(head = HeadParams(color = true)), resources)
        val back = UserBrushCodec.decode(UserBrushCodec.encode(brush))
        assertEquals(brush, back)
        assertEquals(UserBrushCodec.CATEGORY, back?.category)
    }

    @Test
    fun decodesOnlyUserBrushes() {
        // A stock preset file is a valid brush but not a user one: the Created folder must not adopt it.
        val preset = Brush(id = "pencils.grafit", name = BrushName.of("en" to "Grafit"), params = params)
        assertNull(UserBrushCodec.decode(UserBrushCodec.encode(preset)))
        assertNull(UserBrushCodec.decode("not json"))
        assertNull(UserBrushCodec.decode("{}"))
        assertNotNull(UserBrushCodec.decode(UserBrushCodec.encode(preset.copy(id = "user.9"))))
    }

    @Test
    fun copyNameFollowsTheBrushName() {
        assertEquals("Grafit copy", UserBrushCodec.copyName("Grafit", "copy"))
        assertEquals("Grafit (копия)", UserBrushCodec.copyName("  Grafit ", "(копия)"))
        // A brush without a name (Blend) only gets the word.
        assertEquals("copy", UserBrushCodec.copyName("", "copy"))
        assertEquals("copy", UserBrushCodec.copyName("   ", "copy"))
    }

    @Test
    fun namesAreTrimmedAndCapped() {
        assertEquals("Ink", UserBrushCodec.cleanName("  Ink  ", "copy"))
        assertEquals("copy", UserBrushCodec.cleanName("", "copy"))
        assertEquals("copy", UserBrushCodec.cleanName("   ", "copy"))
        assertEquals(UserBrushCodec.MAX_NAME, UserBrushCodec.cleanName("x".repeat(200), "copy").length)
    }
}
