package app.painter.core.stroke

import app.painter.core.color.ColorConv
import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Colour stamp (spec/tools/m2-brush.md §1.13): an RGBA head supplies the dab colour, shifted once per stroke from
 * `base_color` to the current brush colour. With the current colour equal to `base_color` the dab reproduces the
 * head bitmap; with a red head and a blue current colour the dab turns blue and gets no brighter than the head.
 */
class ColorStampTest {
    /** Solid RGBA head of one colour, so the mip pyramid keeps that colour at every level. */
    private fun solidHead(c: Rgba, size: Int = 8): ImageData {
        val px = ByteArray(size * size * 4)
        for (i in 0 until size * size) {
            px[i * 4] = byte(c.r)
            px[i * 4 + 1] = byte(c.g)
            px[i * 4 + 2] = byte(c.b)
            px[i * 4 + 3] = byte(c.a)
        }
        return ImageData(size, size, 4, px)
    }

    private fun byte(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    /** Brush whose dab colour comes from [head]; [base] is the `base_color` the shift starts from. */
    private fun stampBrush(base: Rgba): BrushParams = BrushParams(
        size = 40f,
        hardness = 1f,
        spacing = 0.25f,
        smoothing = 0f,
        opacity = 1f,
        flow = 1f,
        head = HeadParams(source = HeadSource.BITMAP, color = true, baseColor = ColorConv.toHex(base, withAlpha = false)),
        special = ColorStampParams(colorize = 1f),
    )

    /** Paints a short horizontal stroke in [color] through a head of [headColor] and returns the layer. */
    private fun stamp(headColor: Rgba, base: Rgba, color: Rgba): TileGrid {
        val doc = Document.create(200, 200)
        val res = BrushResources(head = HeadImage(solidHead(headColor)))
        val e = engine(doc, stampBrush(base), res = res, color = color)
        drawOpen(e, line(60f, 100f, 140f, 100f, 4f))
        e.endPatch()
        return doc.activeRaster!!.pixels
    }

    /** Straight colour of the painted pixel ([x], [y]); the layer stores premultiplied values. */
    private fun straight(grid: TileGrid, x: Int, y: Int): Rgba {
        val px = FloatArray(4)
        grid.getPixel(x, y, px)
        if (px[3] <= 0f) return Rgba.TRANSPARENT
        return Rgba(px[0] / px[3], px[1] / px[3], px[2] / px[3], px[3])
    }

    @Test
    fun baseColorReproducesTheHeadColors() {
        val head = Rgba(0.8f, 0.2f, 0.267f) // #CC3344
        val px = straight(stamp(head, base = head, color = head), 100, 100)
        assertTrue(px.a > 0.9f, "the stroke must paint, got alpha ${px.a}")
        val tol = 2f / 255f
        assertTrue(abs(px.r - head.r) <= tol, "red ${px.r} vs head ${head.r}")
        assertTrue(abs(px.g - head.g) <= tol, "green ${px.g} vs head ${head.g}")
        assertTrue(abs(px.b - head.b) <= tol, "blue ${px.b} vs head ${head.b}")
    }

    @Test
    fun redHeadWithBlueColorBecomesBlueAndNotBrighter() {
        val head = Rgba(1f, 0f, 0f)
        val blue = Rgba(0f, 0f, 1f)
        val px = straight(stamp(head, base = head, color = blue), 100, 100)
        assertTrue(px.a > 0.9f, "the stroke must paint, got alpha ${px.a}")
        assertTrue(px.b > 0.9f, "a blue current colour must give a blue dab, got ${px.b}")
        assertTrue(px.r < 0.1f, "the red of the head must go away, got ${px.r}")
        val bri = max(px.r, max(px.g, px.b))
        assertTrue(bri <= max(head.r, max(head.g, head.b)) + 2f / 255f, "the dab got brighter: $bri")
    }
}
