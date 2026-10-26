package app.painter.core.raster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaskTypesTest {
    @Test
    fun maskGridDefaultValue() {
        val reveal = MaskGrid(600, 300, -1)
        assertEquals(255, reveal.byteAt(10, 10))
        assertEquals(0, reveal.byteAt(-1, 0))
        val t = reveal.getOrCreate(0, 0)
        assertTrue(t.isFull())
        reveal.compact()
        assertTrue(reveal.keys().isEmpty())
        assertTrue(reveal.isUniform)
    }

    @Test
    fun maskGridBounds() {
        val g = MaskGrid(1000, 800)
        assertNull(g.bounds())
        val t = g.getOrCreate(1, 2)
        t.data[(5 shl Tile.SHIFT) + 7] = 10
        assertEquals(IntRect(263, 517, 264, 518), g.bounds())
        assertEquals(10 / 255f, g.value(263, 517))
    }

    @Test
    fun intRectOps() {
        val a = IntRect(0, 0, 300, 300)
        val b = IntRect(200, 250, 600, 260)
        assertEquals(IntRect(200, 250, 300, 260), a.intersect(b))
        assertEquals(IntRect(0, 0, 600, 300), a.union(b))
        assertNull(a.intersect(IntRect(300, 0, 400, 10)))
        assertEquals(listOf(TileKey(0, 0), TileKey(1, 0)), IntRect(0, 0, 300, 10).tileKeys(1000, 1000))
        assertTrue(IntRect(0, 0, 10, 10).contains(9, 9))
        assertFalse(IntRect(0, 0, 10, 10).contains(10, 9))
    }

    @Test
    fun bitPlaneOps() {
        val p = BitPlane(130, 5)
        assertNull(p.bounds())
        p[0, 1] = true; p[129, 3] = true; p[64, 2] = true
        assertEquals(3L, p.count())
        assertEquals(IntRect(0, 1, 130, 4), p.bounds())
        val q = BitPlane(130, 5).also { it[64, 2] = true }
        val c = p.copy().also { it.andNot(q) }
        assertEquals(2L, c.count())
        c.and(q)
        assertEquals(0L, c.count())
        p[64, 2] = false
        assertFalse(p[64, 2])
    }
}
