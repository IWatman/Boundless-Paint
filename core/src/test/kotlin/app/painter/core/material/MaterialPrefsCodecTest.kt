package app.painter.core.material

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterialPrefsCodecTest {

    @Test
    fun decodesKindNamesCaseInsensitively() {
        assertEquals(MaterialKind.WOOD, MaterialPrefsCodec.decodeKind("WOOD"))
        assertEquals(MaterialKind.WOOD, MaterialPrefsCodec.decodeKind(" wood "))
        assertEquals(MaterialKind.SPARKLE, MaterialPrefsCodec.decodeKind("Sparkle"))
        assertNull(MaterialPrefsCodec.decodeKind(null))
        assertNull(MaterialPrefsCodec.decodeKind(""))
        assertNull(MaterialPrefsCodec.decodeKind("GLASS"))
    }

    @Test
    fun decodesKindListsInOrderWithoutDuplicates() {
        val decoded = MaterialPrefsCodec.decodeKinds("STONE, WOOD,stone,BROKEN,METAL", 4)
        assertEquals(listOf(MaterialKind.STONE, MaterialKind.WOOD, MaterialKind.METAL), decoded)
    }

    @Test
    fun kindListsRespectTheLimitAndEmptyInput() {
        assertEquals(
            listOf(MaterialKind.PAPER, MaterialKind.FABRIC),
            MaterialPrefsCodec.decodeKinds("PAPER,FABRIC,WOOD", 2),
        )
        assertTrue(MaterialPrefsCodec.decodeKinds(null, 4).isEmpty())
        assertTrue(MaterialPrefsCodec.decodeKinds("WOOD", 0).isEmpty())
    }

    @Test
    fun kindListsRoundTrip() {
        val kinds = listOf(MaterialKind.FOLIAGE, MaterialKind.METAL, MaterialKind.BALLOON)
        val text = MaterialPrefsCodec.encodeKinds(kinds)
        assertEquals("FOLIAGE,METAL,BALLOON", text)
        assertEquals(kinds, MaterialPrefsCodec.decodeKinds(text, kinds.size))
    }

    @Test
    fun favouritesToggleOnAndOff() {
        val one = MaterialPrefsCodec.toggleFavourite(emptySet(), MaterialKind.STONE)
        assertEquals(setOf(MaterialKind.STONE), one)
        val two = MaterialPrefsCodec.toggleFavourite(one, MaterialKind.WOOD)
        assertEquals(setOf(MaterialKind.STONE, MaterialKind.WOOD), two)
        assertEquals(setOf(MaterialKind.WOOD), MaterialPrefsCodec.toggleFavourite(two, MaterialKind.STONE))
    }

    @Test
    fun recentMovesTheKindToTheFrontWithoutDuplicates() {
        val recent = MaterialPrefsCodec.addRecent(
            listOf(MaterialKind.WOOD, MaterialKind.STONE, MaterialKind.PAPER),
            MaterialKind.STONE,
        )
        assertEquals(listOf(MaterialKind.STONE, MaterialKind.WOOD, MaterialKind.PAPER), recent)
    }

    @Test
    fun recentIsCappedAndStartsWithTheNewKind() {
        val recent = MaterialPrefsCodec.addRecent(
            listOf(MaterialKind.WOOD, MaterialKind.STONE, MaterialKind.PAPER, MaterialKind.METAL),
            MaterialKind.FABRIC,
        )
        assertEquals(4, recent.size)
        assertEquals(MaterialKind.FABRIC, recent.first())
        assertEquals(listOf(MaterialKind.FABRIC, MaterialKind.WOOD, MaterialKind.STONE, MaterialKind.PAPER), recent)
        assertTrue(MaterialPrefsCodec.addRecent(emptyList(), MaterialKind.WOOD, limit = 0).isEmpty())
    }

    @Test
    fun specDefaultsWhenFieldsAreMissing() {
        val spec = MaterialPrefsCodec.decodeSpec("WOOD")
        assertEquals(MaterialSpec(MaterialKind.WOOD), spec)
    }

    @Test
    fun specIsClampedToTheToolLimits() {
        val spec = MaterialPrefsCodec.decodeSpec(
            kind = "STONE",
            size = 9000f,
            rotationDeg = -450f,
            opacity = 4f,
            spacing = 0.01f,
        )!!
        assertEquals(MaterialKind.STONE, spec.kind)
        assertEquals(MaterialSpec.MAX_SIZE, spec.size)
        assertEquals(270f, spec.rotationDeg, 1e-4f)
        assertEquals(1f, spec.opacity)
        assertEquals(MaterialSpec.MIN_SPACING, spec.spacing)

        val small = MaterialPrefsCodec.decodeSpec("STONE", 1f, 720f, -2f, 9f)!!
        assertEquals(MaterialSpec.MIN_SIZE, small.size)
        assertEquals(0f, small.rotationDeg)
        assertEquals(0f, small.opacity)
        assertEquals(MaterialSpec.MAX_SPACING, small.spacing)
    }

    @Test
    fun specFallsBackForNonFiniteFields() {
        val spec = MaterialPrefsCodec.decodeSpec(
            kind = "PAPER",
            size = Float.NaN,
            rotationDeg = Float.POSITIVE_INFINITY,
            opacity = Float.NEGATIVE_INFINITY,
            spacing = Float.NaN,
        )!!
        assertEquals(MaterialSpec.DEFAULT_SIZE, spec.size)
        assertEquals(MaterialSpec.DEFAULT_ROTATION, spec.rotationDeg)
        assertEquals(MaterialSpec.DEFAULT_OPACITY, spec.opacity)
        assertEquals(MaterialSpec.DEFAULT_SPACING, spec.spacing)
    }

    @Test
    fun specNeedsAKnownKind() {
        assertNull(MaterialPrefsCodec.decodeSpec(null, 512f, 0f, 1f, 0.25f))
        assertNull(MaterialPrefsCodec.decodeSpec("GLASS", 512f, 0f, 1f, 0.25f))
    }

    @Test
    fun clampNormalisesAnInMemorySpec() {
        val clamped = MaterialPrefsCodec.clamp(MaterialSpec(MaterialKind.METAL, -4f, 1080f, 0.5f, 0.5f))
        assertEquals(MaterialSpec.MIN_SIZE, clamped.size)
        assertEquals(0f, clamped.rotationDeg)
        assertEquals(0.5f, clamped.opacity)
        assertEquals(0.5f, clamped.spacing)
    }

    @Test
    fun modeRoundTrips() {
        assertEquals("STAMP", MaterialPrefsCodec.encodeMode(MaterialMode.STAMP))
        assertEquals(MaterialMode.STAMP, MaterialPrefsCodec.decodeMode("stamp"))
        assertEquals(MaterialMode.PAINT, MaterialPrefsCodec.decodeMode(" PAINT "))
        assertNull(MaterialPrefsCodec.decodeMode(null))
        assertNull(MaterialPrefsCodec.decodeMode("SPRAY"))
    }
}
