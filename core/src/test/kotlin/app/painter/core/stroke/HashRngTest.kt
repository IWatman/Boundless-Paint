package app.painter.core.stroke

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HashRngTest {
    @Test
    fun uniformChiSquare() {
        val bins = IntArray(20)
        val n = 10_000
        for (i in 0 until n) {
            val r = HashRng.rnd(12345L, RngChannel.SIZE, i.toLong())
            assertTrue(r >= 0f && r < 1f)
            bins[(r * bins.size).toInt()]++
        }
        val e = n / bins.size.toDouble()
        val chi2 = bins.sumOf { (it - e) * (it - e) / e }
        // df = 19, p = 0.001 critical value 43.8.
        assertTrue(chi2 < 43.8, "chi2 = $chi2")
    }

    @Test
    fun channelsAreIndependent() {
        val n = 10_000
        var sxy = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val a = HashRng.rnd(7L, RngChannel.SCATTER_N, i.toLong()).toDouble()
            val b = HashRng.rnd(7L, RngChannel.SCATTER_T, i.toLong()).toDouble()
            sx += a; sy += b; sxy += a * b; sxx += a * a; syy += b * b
        }
        val cov = sxy / n - sx / n * sy / n
        val corr = cov / kotlin.math.sqrt((sxx / n - (sx / n) * (sx / n)) * (syy / n - (sy / n) * (sy / n)))
        assertTrue(kotlin.math.abs(corr) < 0.05, "corr $corr")
    }

    @Test
    fun valuesAreStable() {
        // Pure function of (seed, channel, index): repeatable and seed-sensitive.
        assertEquals(HashRng.rnd(1L, 2, 3L), HashRng.rnd(1L, 2, 3L))
        assertNotEquals(HashRng.rnd(1L, 2, 3L), HashRng.rnd(2L, 2, 3L))
        assertNotEquals(HashRng.rnd(1L, 2, 3L), HashRng.rnd(1L, 3, 3L))
        assertEquals(0L, HashRng.mix64(0L))
        assertEquals(HashRng.strokeSeed(5, "pencil.hb"), HashRng.strokeSeed(5, "pencil.hb"))
        assertNotEquals(HashRng.strokeSeed(5, "pencil.hb"), HashRng.strokeSeed(6, "pencil.hb"))
    }
}
