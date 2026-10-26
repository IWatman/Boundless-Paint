package app.painter.core.stroke

import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HalfTileTest {
    @Test
    fun halfRoundTripError() {
        val t = Tile(precise = false)
        for (i in 0 until 4096) {
            val v = i / 4095f
            t[i] = v
            assertTrue(kotlin.math.abs(t[i] - v) <= 1e-3f, "$v -> ${t[i]}")
        }
    }

    @Test
    fun workingSetCommitsAndRollsBack() {
        val grid = TileGrid(600, 300)
        grid.setPixel(10, 10, 0.25f, 0.5f, 0.75f, 1f)
        val original = grid.get(TileKey(0, 0))!!
        val ws = LayerWorkingSet(grid, TilePool())
        val w = ws.prepare(TileKey(0, 0), create = true)!!
        assertTrue(w.precise)
        assertSame(w, grid.get(TileKey(0, 0)), "working copy is live in the grid")
        assertEquals(0.5f, w[w.index(10, 10) + 1])
        assertNull(ws.prepare(TileKey(1, 0), create = false))
        val created = ws.prepare(TileKey(1, 0), create = true)!!
        created.write(created.index(3, 3), 0.1f, 0.1f, 0.1f, 0.1f)
        w.write(w.index(10, 10), 0f, 0f, 0f, 0f)
        ws.rollback()
        assertSame(original, grid.get(TileKey(0, 0)))
        assertNull(grid.get(TileKey(1, 0)))

        val ws2 = LayerWorkingSet(grid, TilePool())
        val w2 = ws2.prepare(TileKey(0, 0), create = true)!!
        w2.write(w2.index(10, 10), 0f, 0f, 0f, 0f)
        val c = ws2.prepare(TileKey(1, 0), create = true)!!
        c.write(c.index(3, 3), 0.1f, 0.1f, 0.1f, 0.1f)
        val patch = assertNotNull(ws2.commit("L"))
        assertSame(original, patch.before[TileKey(0, 0)], "before keeps the untouched half tile")
        assertNull(patch.after[TileKey(0, 0)], "emptied tile is removed")
        assertNull(grid.get(TileKey(0, 0)))
        val after = grid.get(TileKey(1, 0))!!
        assertTrue(!after.precise)
        assertTrue(after.contentEquals(patch.after[TileKey(1, 0)]!!))
        assertEquals(0.1f, after[after.index(3, 3)], 1e-3f)
    }

    @Test
    fun poolRecyclesClearedTiles() {
        val pool = TilePool(limit = 2)
        val a = pool.obtain()
        a[0] = 1f
        pool.release(a)
        pool.release(Tile(true))
        pool.release(Tile(true))
        assertEquals(2, pool.retained)
        val b = pool.obtain()
        assertEquals(0f, b[0])
        pool.release(Tile(false))
        assertEquals(1, pool.retained)
    }
}
