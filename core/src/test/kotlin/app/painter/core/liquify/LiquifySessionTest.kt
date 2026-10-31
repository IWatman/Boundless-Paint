package app.painter.core.liquify

import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Liquify push: pixels follow the drag, originals are kept for one undo step. */
class LiquifySessionTest {

    private fun square(g: TileGrid) {
        for (y in 120 until 136) for (x in 120 until 136) g.setPixel(x, y, 1f, 1f, 1f, 1f)
    }

    private fun alpha(g: TileGrid, x: Int, y: Int): Float =
        FloatArray(4).also { g.getPixel(x, y, it) }[3]

    @Test
    fun dragMovesThePixels() {
        val g = TileGrid(256, 256)
        square(g)
        val original = g.get(TileKey(0, 0))!!.copy()
        val s = LiquifySession(g, size = 80f, strength = 1f)
        s.begin(128f, 128f)
        s.move(148f, 128f)
        s.end()

        assertEquals(1f, alpha(g, 140, 128), 0.2f, "the square moved right")
        assertEquals(0f, alpha(g, 118, 128), "nothing moved into the empty left side")
        assertTrue(s.touched.contains(TileKey(0, 0)), "the tile is tracked")
        val before = s.beforeTiles.getValue(TileKey(0, 0))!!
        assertTrue(before.contentEquals(original), "the original tile is kept for undo")
    }

    @Test
    fun strengthScalesThePush() {
        val weak = TileGrid(256, 256)
        val strong = TileGrid(256, 256)
        square(weak)
        square(strong)
        for ((g, k) in listOf(weak to 0.2f, strong to 1f)) {
            val s = LiquifySession(g, size = 80f, strength = k)
            s.begin(128f, 128f)
            s.move(148f, 128f)
            s.end()
        }
        // A weak push keeps more of the square under its original spot than a strong one.
        assertTrue(alpha(weak, 122, 128) > alpha(strong, 122, 128), "strength changes the displacement")
    }

    @Test
    fun dirtyKeysAreReportedOnce() {
        val g = TileGrid(512, 256)
        square(g)
        val s = LiquifySession(g, size = 80f, strength = 0.8f)
        s.begin(128f, 128f)
        s.move(160f, 128f)
        val first = s.takeDirty()
        assertTrue(first.isNotEmpty(), "the dab reports its tile")
        assertTrue(s.takeDirty().isEmpty(), "a second call reports nothing new")
    }

    @Test
    fun tinyMovesDoNotCreateAHistory() {
        val g = TileGrid(256, 256)
        square(g)
        val s = LiquifySession(g, size = 40f, strength = 0.5f)
        s.begin(128f, 128f)
        s.move(128.001f, 128f)
        s.end()
        assertTrue(s.touched.isEmpty(), "a still press touches nothing")
    }
}
