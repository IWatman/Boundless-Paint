package app.painter.render

import app.painter.core.raster.Tile
import kotlin.math.roundToInt

/** Source extent and destination interval for one axis of an overview tile. */
internal data class OverviewTileBounds(val sourceEnd: Int, val destinationStart: Int, val destinationEnd: Int)

internal fun overviewTileBounds(tile: Int, documentSize: Int, overviewSize: Int): OverviewTileBounds {
    val start = tile * Tile.SIZE
    val end = minOf(start + Tile.SIZE, documentSize)
    fun map(position: Int) = (position.toDouble() * overviewSize / documentSize).roundToInt()
    return OverviewTileBounds(end - start, map(start), map(end))
}
