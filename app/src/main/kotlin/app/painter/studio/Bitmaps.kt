package app.painter.studio

import android.graphics.Bitmap
import app.painter.core.color.Rgba
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import kotlin.math.max
import kotlin.math.roundToInt

/** Converts a premultiplied float tile grid into an ARGB_8888 bitmap, optionally downscaled. */
fun gridToBitmap(grid: TileGrid, width: Int, height: Int, maxSide: Int = Int.MAX_VALUE): Bitmap {
    val scale = if (max(width, height) > maxSide) maxSide.toFloat() / max(width, height) else 1f
    val outW = (width * scale).roundToInt().coerceAtLeast(1)
    val outH = (height * scale).roundToInt().coerceAtLeast(1)
    val pixels = IntArray(outW * outH)
    val px = FloatArray(4)
    for (y in 0 until outH) {
        val sy = ((y + 0.5f) / scale).toInt().coerceIn(0, height - 1)
        for (x in 0 until outW) {
            val sx = ((x + 0.5f) / scale).toInt().coerceIn(0, width - 1)
            grid.getPixel(sx, sy, px)
            val a = px[3].coerceIn(0f, 1f)
            if (a <= 0f) continue
            val r = (px[0] / a).coerceIn(0f, 1f)
            val g = (px[1] / a).coerceIn(0f, 1f)
            val b = (px[2] / a).coerceIn(0f, 1f)
            pixels[y * outW + x] = ((a * 255 + .5f).toInt() shl 24) or
                ((r * 255 + .5f).toInt() shl 16) or ((g * 255 + .5f).toInt() shl 8) or (b * 255 + .5f).toInt()
        }
    }
    return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
}

/** Imports an ARGB bitmap into a new tile grid (premultiplied). */
fun bitmapToGrid(bmp: Bitmap): TileGrid {
    val grid = TileGrid(bmp.width, bmp.height)
    val row = IntArray(bmp.width)
    for (y in 0 until bmp.height) {
        bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
        for (x in 0 until bmp.width) {
            val c = row[x]
            val a = (c ushr 24) / 255f
            if (a == 0f) continue
            grid.setPixel(x, y, ((c shr 16) and 0xFF) / 255f * a, ((c shr 8) and 0xFF) / 255f * a, (c and 0xFF) / 255f * a, a)
        }
    }
    return grid
}

object DefaultPalette {
    val colors: List<Rgba> = listOf(
        0x000000, 0x404040, 0x808080, 0xC0C0C0, 0xFFFFFF, 0x7A1F1F,
        0xD32F2F, 0xF57C00, 0xFBC02D, 0x689F38, 0x2E7D32, 0x00897B,
        0x0288D1, 0x1A237E, 0x5E35B1, 0x8E24AA, 0xD81B60, 0x795548,
        0xF4D3B5, 0xE0AC8A, 0xB57A55, 0x8D5524, 0x5A3825, 0x3B2219,
    ).map { Rgba(((it shr 16) and 0xFF) / 255f, ((it shr 8) and 0xFF) / 255f, (it and 0xFF) / 255f) }
}
