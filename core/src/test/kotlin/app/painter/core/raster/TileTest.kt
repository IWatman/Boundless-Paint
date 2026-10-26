package app.painter.core.raster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileTest {
    @Test
    fun halfStorageRoundsToBinary16() {
        val t = Tile()
        t[0] = 0.1f
        assertEquals(Half.toFloat(Half.fromFloat(0.1f)), t[0])
        assertEquals(Tile.CHANNELS * 2L, t.byteSize)
    }

    @Test
    fun preciseStorageKeepsFloat() {
        val t = Tile(precise = true)
        t[5] = 0.1f
        assertEquals(0.1f, t[5])
        assertEquals(Tile.CHANNELS * 4L, t.byteSize)
    }

    @Test
    fun lowFlowAccumulationNeedsPrecision() {
        // 2000 dabs at 0.1% coverage: half storage stalls, float keeps building up.
        val half = Tile()
        val precise = Tile(precise = true)
        repeat(2000) {
            for (t in listOf(half, precise)) t[3] = 0.001f + t[3] * 0.999f
        }
        assertTrue(precise[3] > 0.8f)
        assertTrue(half[3] < precise[3])
    }

    @Test
    fun negativeZeroAlphaIsEmpty() {
        val t = Tile()
        t[3] = -0f
        assertTrue(t.isEmpty())
        t[7] = 0.5f
        assertFalse(t.isEmpty())
    }

    @Test
    fun readWriteAndConversions() {
        val t = Tile()
        val i = t.index(10, 20)
        t.write(i, 0.25f, 0.5f, 0.75f, 1f)
        val out = FloatArray(4)
        t.read(i, out)
        assertTrue(out.contentEquals(floatArrayOf(0.25f, 0.5f, 0.75f, 1f)))
        val p = t.copy(precise = true)
        assertTrue(p.precise)
        assertTrue(p.contentEquals(t))
        val halves = ShortArray(Tile.CHANNELS)
        p.copyHalvesTo(halves)
        assertTrue(Tile.ofHalves(halves).contentEquals(t))
    }

    @Test
    fun halfTableMatchesAllValues() {
        for (bits in 0 until 65536) {
            val h = bits.toShort()
            val f = Half.toFloat(h)
            if (f.isNaN()) continue
            assertEquals(h, Half.fromFloat(f), "bits $bits")
        }
    }
}
