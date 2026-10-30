package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.assertGridsEqual
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Paint with colour pickup ([StrokeMode.PAINT] with `mix_in`/`dilution`, spec/tools/m2-brush.md §1.8, stage S4b):
 * the dab mixes the colours it samples into the deposited paint. So a blue brush with a high `mix_in` dragged from a
 * red area into white leaves a purple-ish colour there, `dilution` 1 without `mix_in` deposits only the picked-up
 * colours, and `mix_in` 0 with `dilution` 0 leaves the plain stroke bit-identical.
 */
class PaintMixTest {
    /** Full flow, so `m·flow·opacity` reaches 1 in the middle of the dab and the deposit is the paint itself. */
    private val mixing = BrushParams(size = 40f, hardness = 1f, spacing = 0.25f, smoothing = 0f, flow = 1f, opacity = 1f)

    private val blue = Rgba(0f, 0f, 1f)

    /** 400×120 canvas, opaque red left of the middle column and opaque white right of it. */
    private fun boundaryDoc(): Document {
        val doc = Document.create(400, 120)
        val px = doc.activeRaster!!.pixels
        for (y in 0 until 120) {
            for (x in 0 until 400) {
                if (x < 300) px.setPixel(x, y, 1f, 0f, 0f, 1f) else px.setPixel(x, y, 1f, 1f, 1f, 1f)
            }
        }
        return doc
    }

    private fun pixel(px: TileGrid, x: Int, y: Int): FloatArray {
        val out = FloatArray(4)
        px.getPixel(x, y, out)
        return out
    }

    /** Paints a horizontal line ([x0] → [x1]) at y = 60 with [paint] and returns the layer. */
    private fun paint(paint: PaintParams, x0: Float, x1: Float, doc: Document = boundaryDoc()): TileGrid {
        val e = engine(doc, mixing.copy(paint = paint), color = blue)
        drawOpen(e, line(x0, 60.5f, x1, 60.5f, 4f))
        e.endPatch()
        return doc.activeRaster!!.pixels
    }

    @Test
    fun zeroMixInAndDilutionKeepThePlainStroke() {
        // Colour pickup off: the paint params must not change a single pixel of the stroke.
        val plain = boundaryDoc()
        drawOpen(engine(plain, mixing, color = blue), line(240f, 60.5f, 370f, 60.5f, 4f)).endPatch()
        val off = boundaryDoc()
        val params = PaintParams(mixIn = 0f, dilution = 0f, wet = true, pull = 0.9f, blur = 0.5f)
        drawOpen(engine(off, mixing.copy(paint = params), color = blue), line(240f, 60.5f, 370f, 60.5f, 4f)).endPatch()
        assertGridsEqual(plain.activeRaster!!.pixels, off.activeRaster!!.pixels, "mix_in 0 with dilution 0")
    }

    @Test
    fun mixInBlendsThePickedUpRedIntoTheWhite() {
        // The paint runs from blue towards the red it samples, so right after the boundary it is neither: purple.
        val px = paint(PaintParams(mixIn = 0.8f), 240f, 370f)
        val p = pixel(px, 310, 60)
        assertTrue(p[0] > 0.2f, "the picked-up red must show, got ${p.toList()}")
        assertTrue(p[2] > 0.2f, "the brush blue must still show, got ${p.toList()}")
        val start = pixel(px, 245, 60)
        assertTrue(start[0] > 0.5f, "the paint follows the red it samples, got ${start.toList()}")
    }

    @Test
    fun dilutionWithoutMixInAddsNoBrushColour() {
        // dilution 1 deposits the reservoir only, which starts from the layer colour: the blue never reaches paint.
        // Checked in the red half only: there the layer has no blue and no sampled dab reaches the white.
        val px = paint(PaintParams(mixIn = 0f, dilution = 1f), 240f, 370f)
        for (x in 240 until 260) {
            val p = pixel(px, x, 60)
            assertTrue(p[2] < 2f / 255f, "no brush blue at $x, got ${p.toList()}")
        }
        // Past the boundary the picked-up white lightens the deposited red, so the stroke did reach the layer.
        val white = pixel(px, 320, 60)
        assertTrue(white[1] < 0.9f && white[2] < 0.9f, "the reservoir carries the white along, got ${white.toList()}")
    }
}
