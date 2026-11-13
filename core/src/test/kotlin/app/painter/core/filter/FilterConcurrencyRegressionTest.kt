package app.painter.core.filter

import app.painter.core.raster.TileGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilterConcurrencyRegressionTest {
    @Test
    fun parallelGeneratorKeepsAllOutputTiles() {
        repeat(12) {
            val grid = TileGrid(1024, 1024)
            Filters.apply("layer", grid, QrCodeParams(text = "tile concurrency", size = 20), threads = 8)
            val pixel = FloatArray(4)
            // The QR paper spans both tile columns and rows.
            for (y in listOf(300, 500, 700)) for (x in listOf(300, 500, 700)) {
                grid.getPixel(x, y, pixel)
                assertEquals(1f, pixel[3], 0.0001f, "Missing output tile at $x,$y")
            }
        }
    }

    @Test
    fun liveBlurMatchesSnapshotBasedHistoryAcrossTileBoundary() {
        val input = TileGrid(512, 64)
        for (y in 0 until 64) for (x in 240 until 272) {
            input.setPixel(x, y, 1f, 0f, 0f, 1f)
        }
        val expected = input.copy()
        Filters.apply("layer", expected, BlurParams(radius = 8f), threads = 1)
        repeat(5) {
            val actual = input.copy()
            Filters.applyToGrid(actual, BlurParams(radius = 8f), threads = 4)
            for (key in expected.keys()) {
                assertTrue(expected.get(key)!!.contentEquals(actual.get(key)!!), "Blur differs at $key")
            }
        }
    }
}
