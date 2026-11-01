package app.painter.video

import app.painter.core.color.Rgba
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.video.Yuv
import kotlin.math.floor
import kotlin.math.min

/**
 * Bilinear rasterizer from a flattened canvas grid to one reusable I420 frame (spec/VIDEO-MODE.md §6.3).
 * The grid is composited over the paper color (black when the paper is hidden) because video has no alpha,
 * and the conversion uses the BT.709 matrix from [Yuv]. Chroma samples average the 2 × 2 output block.
 */
internal class VideoRasterizer(
    canvasWidth: Int,
    canvasHeight: Int,
    private val width: Int,
    private val height: Int,
    paper: Rgba?,
) {
    init {
        require(canvasWidth > 0 && canvasHeight > 0) { "empty canvas" }
        require(width > 0 && height > 0) { "empty output" }
    }

    private val canvasWidth = canvasWidth
    private val canvasHeight = canvasHeight
    val frame = Yuv.I420Frame(width, height)

    private val paperR = paper?.r ?: 0f
    private val paperG = paper?.g ?: 0f
    private val paperB = paper?.b ?: 0f

    private val sx0 = IntArray(width)
    private val sx1 = IntArray(width)
    private val wx = FloatArray(width)
    private val rowA = FloatArray(width * 4)
    private val rowB = FloatArray(width * 4)
    private val rowBytes = ByteArray(width * 3)
    private val previousRowBytes = ByteArray(width * 3)

    init {
        for (x in 0 until width) {
            val source = (((x + 0.5f) * canvasWidth / width) - 0.5f)
                .coerceIn(0f, (canvasWidth - 1).toFloat())
            val x0 = floor(source).toInt()
            sx0[x] = x0
            sx1[x] = min(x0 + 1, canvasWidth - 1)
            wx[x] = source - x0
        }
    }

    /** Renders [grid] (canvas size) into [frame] and returns the same reusable frame. */
    fun render(grid: TileGrid): Yuv.I420Frame {
        require(grid.width == canvasWidth && grid.height == canvasHeight) {
            "grid is ${grid.width}x${grid.height}, expected ${canvasWidth}x$canvasHeight"
        }
        for (row in 0 until height) {
            val sourceY = (((row + 0.5f) * canvasHeight / height) - 0.5f)
                .coerceIn(0f, (canvasHeight - 1).toFloat())
            val y0 = floor(sourceY).toInt()
            val y1 = min(y0 + 1, canvasHeight - 1)
            val wy = if (y1 == y0) 0f else sourceY - y0
            sampleRow(grid, y0, rowA)
            val blendRows = wy > 0f
            if (blendRows) sampleRow(grid, y1, rowB)
            for (x in 0 until width) {
                val i = x * 4
                val ar = rowA[i]
                val ag = rowA[i + 1]
                val ab = rowA[i + 2]
                val aa = rowA[i + 3]
                val pr: Float
                val pg: Float
                val pb: Float
                val pa: Float
                if (blendRows) {
                    pr = ar + (rowB[i] - ar) * wy
                    pg = ag + (rowB[i + 1] - ag) * wy
                    pb = ab + (rowB[i + 2] - ab) * wy
                    pa = aa + (rowB[i + 3] - aa) * wy
                } else {
                    pr = ar
                    pg = ag
                    pb = ab
                    pa = aa
                }
                val inverse = 1f - pa.coerceIn(0f, 1f)
                val r = toByte(pr + paperR * inverse)
                val g = toByte(pg + paperG * inverse)
                val b = toByte(pb + paperB * inverse)
                val o = x * 3
                rowBytes[o] = r
                rowBytes[o + 1] = g
                rowBytes[o + 2] = b
                frame.setLuma(x, row, Yuv.luma(r.toInt() and 0xFF, g.toInt() and 0xFF, b.toInt() and 0xFF))
            }
            if (row % 2 == 1) {
                writeChroma(row / 2, previousRowBytes, rowBytes)
            } else {
                rowBytes.copyInto(previousRowBytes)
            }
        }
        if (height % 2 == 1) {
            writeChroma(height / 2, previousRowBytes, previousRowBytes)
        }
        return frame
    }

    /** Horizontally samples source row [sourceY] into [dst] as premultiplied RGBA floats. */
    private fun sampleRow(grid: TileGrid, sourceY: Int, dst: FloatArray) {
        val tileY = sourceY shr Tile.SHIFT
        val inTileY = sourceY and Tile.MASK
        var tile: Tile? = null
        var tileX = -1
        for (x in 0 until width) {
            val x0 = sx0[x]
            val tx = x0 shr Tile.SHIFT
            if (tx != tileX) {
                tile = grid.get(tx, tileY)
                tileX = tx
            }
            val x1 = sx1[x]
            val i = x * 4
            val first = tile
            if (first == null) {
                if (x1 == x0) {
                    dst.fill(0f, i, i + 4)
                } else {
                    val second = grid.get(x1 shr Tile.SHIFT, tileY)
                    if (second == null) {
                        dst.fill(0f, i, i + 4)
                    } else {
                        val index1 = second.index(x1 and Tile.MASK, inTileY)
                        val t = wx[x]
                        for (c in 0..3) dst[i + c] = second[index1 + c] * t
                    }
                }
                continue
            }
            val index0 = first.index(x0 and Tile.MASK, inTileY)
            if (x1 == x0) {
                dst[i] = first[index0]
                dst[i + 1] = first[index0 + 1]
                dst[i + 2] = first[index0 + 2]
                dst[i + 3] = first[index0 + 3]
            } else {
                val second = if ((x1 shr Tile.SHIFT) == tx) first else grid.get(x1 shr Tile.SHIFT, tileY)
                val index1 = second?.index(x1 and Tile.MASK, inTileY)
                val t = wx[x]
                for (c in 0..3) {
                    val a = first[index0 + c]
                    val b = second?.get(index1!! + c) ?: 0f
                    dst[i + c] = a + (b - a) * t
                }
            }
        }
    }

    private fun writeChroma(chromaY: Int, upper: ByteArray, lower: ByteArray) {
        for (cx in 0 until frame.chromaWidth) {
            val x = cx * 2
            val x2 = min(x + 1, width - 1)
            val i = x * 3
            val j = x2 * 3
            val r = (upper[i].toInt() and 0xFF) + (upper[j].toInt() and 0xFF) +
                (lower[i].toInt() and 0xFF) + (lower[j].toInt() and 0xFF)
            val g = (upper[i + 1].toInt() and 0xFF) + (upper[j + 1].toInt() and 0xFF) +
                (lower[i + 1].toInt() and 0xFF) + (lower[j + 1].toInt() and 0xFF)
            val b = (upper[i + 2].toInt() and 0xFF) + (upper[j + 2].toInt() and 0xFF) +
                (lower[i + 2].toInt() and 0xFF) + (lower[j + 2].toInt() and 0xFF)
            frame.setChroma(
                cx,
                chromaY,
                Yuv.chromaU((r + 2) / 4, (g + 2) / 4, (b + 2) / 4),
                Yuv.chromaV((r + 2) / 4, (g + 2) / 4, (b + 2) / 4),
            )
        }
    }

    private fun toByte(value: Float): Byte =
        (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
}
