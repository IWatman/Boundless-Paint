package app.painter.studio

import android.graphics.Bitmap
import app.painter.core.blend.Compositor
import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.Brush
import app.painter.core.stroke.BrushResources
import app.painter.core.stroke.StrokeContext
import app.painter.core.stroke.StrokeEngine
import app.painter.core.stroke.StrokeMode
import app.painter.core.stroke.StrokeOptions
import app.painter.core.stroke.StrokeTarget

/**
 * Renders the reference stroke of the brush editor preview pad with a real [StrokeEngine] (spec/tools/m2-brush.md
 * §4.2). The current brush carrying the edited parameters is painted onto a small off-screen [Document] on a
 * background thread by the view model and the result is published as the preview bitmap.
 */
object BrushPreviewRenderer {
    /** Preview size in pixels: wide enough to read the S-curve, small enough to re-render in a few milliseconds. */
    const val WIDTH = 480
    const val HEIGHT = 160

    /** Ink of the preview: dark and neutral, so the shape reads whatever the current color is. */
    private val INK = Rgba(0.11f, 0.12f, 0.16f)

    /**
     * Paints [brush] (already carrying the edited parameters) with [resources] over a white sheet of [width]×[height]
     * and returns it as an ARGB bitmap. Blocking; the caller runs it off the main thread.
     */
    fun render(
        brush: Brush,
        resources: BrushResources = BrushResources.EMPTY,
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ): Bitmap {
        val doc = Document.create(width, height)
        val engine = StrokeEngine(
            doc,
            StrokeTarget.Layer(doc.activeId!!),
            brush,
            resources,
            INK,
            StrokeMode.PAINT,
            StrokeContext(threads = 1),
            StrokeOptions(),
        )
        val points = PreviewStroke.points(width, height)
        engine.begin(points.first())
        engine.add(points.drop(1))
        engine.end()
        return toBitmap(Compositor.flatten(doc, includePaper = true), width, height)
    }

    /** Premultiplied float grid composed over white paper, as straight opaque ARGB pixels. */
    private fun toBitmap(grid: TileGrid, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        val px = FloatArray(4)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                grid.getPixel(x, y, px)
                val a = px[3].coerceIn(0f, 1f)
                val r = channel(px[0] + 1f - a)
                val g = channel(px[1] + 1f - a)
                val b = channel(px[2] + 1f - a)
                pixels[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun channel(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
}
