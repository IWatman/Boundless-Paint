package app.painter.render

import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewTileBoundsTest {
    @Test
    fun partialTilesCopyOnlyDocumentPixelsAtNativeResolution() {
        assertEquals(OverviewTileBounds(256, 768, 1024), overviewTileBounds(3, 1078, 1078))
        assertEquals(OverviewTileBounds(54, 1024, 1078), overviewTileBounds(4, 1078, 1078))
        assertEquals(OverviewTileBounds(63, 1792, 1855), overviewTileBounds(7, 1855, 1855))
    }

    @Test
    fun scaledOverviewUsesEachAxisAndAdjacentTilesMeet() {
        for ((documentSize, overviewSize) in listOf(4097 to 2560, 3001 to 1875, 127 to 79, 512 to 320)) {
            var previousEnd = 0
            var copied = 0
            val tileCount = (documentSize + 255) / 256
            for (tile in 0 until tileCount) {
                val bounds = overviewTileBounds(tile, documentSize, overviewSize)
                assertEquals(previousEnd, bounds.destinationStart)
                previousEnd = bounds.destinationEnd
                copied += bounds.sourceEnd
            }
            assertEquals(documentSize, copied)
            assertEquals(overviewSize, previousEnd)
        }
    }
}
