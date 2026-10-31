package app.painter.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import app.painter.core.color.Rgba
import app.painter.core.raster.TileGrid
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Rasterizes [text] for the text tool (M3): one bitmap per stamp, anti-aliased, with the current colour, then
 * converted to a premultiplied tile grid. Lines are split on `\n`; the size is the em size in pixels.
 */
fun renderTextStamp(text: String, sizePx: Float, color: Rgba): TileGrid? {
    val lines = text.split('\n').filter { it.isNotEmpty() }
    if (lines.isEmpty() || sizePx <= 0f) return null
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.argb(
            (color.a.coerceIn(0f, 1f) * 255).roundToInt(),
            (color.r.coerceIn(0f, 1f) * 255).roundToInt(),
            (color.g.coerceIn(0f, 1f) * 255).roundToInt(),
            (color.b.coerceIn(0f, 1f) * 255).roundToInt(),
        )
        textSize = sizePx
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    val fm = paint.fontMetrics
    val lineHeight = (fm.descent - fm.ascent + fm.leading).coerceAtLeast(1f)
    val width = ceil(lines.maxOf { paint.measureText(it) }).toInt().coerceAtLeast(1)
    val height = ceil(lineHeight * lines.size).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    var baseline = -fm.ascent
    for (line in lines) {
        canvas.drawText(line, 0f, baseline, paint)
        baseline += lineHeight
    }
    val grid = bitmapToGrid(bmp)
    bmp.recycle()
    return grid
}
