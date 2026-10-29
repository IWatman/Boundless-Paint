package app.painter.core.stroke

import app.painter.core.doc.Document
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.stroke.StrokeTestKit.alpha
import app.painter.core.stroke.StrokeTestKit.drawOpen
import app.painter.core.stroke.StrokeTestKit.engine
import app.painter.core.stroke.StrokeTestKit.gray
import app.painter.core.stroke.StrokeTestKit.line
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Grain texture (spec/tools/m2-brush.md §1.4, stage S2a): depth 0 is the untextured stroke, the UV modes place
 * the grain, canvas-fixed UVs are shared by crossing strokes, the 512 px period tiles without a seam, and the
 * lower limit caps the alpha a stroke reaches.
 */
class TextureTest {
    private val hard = BrushParams(size = 40f, hardness = 1f, spacing = 0.25f, smoothing = 0f)

    /** Smooth 512² grain, periodic in both axes (so a seam shows as a jump and not as a clip). */
    private fun wrap() = gray(512, 512) { x, y ->
        0.5f + 0.25f * sin(2 * PI.toFloat() * x / 512f) + 0.2f * cos(2 * PI.toFloat() * y / 512f)
    }

    @Test
    fun depthZeroIsTheUntexturedStroke() {
        fun draw(params: BrushParams, res: BrushResources): TileGrid {
            val doc = Document.create(200, 120)
            val e = engine(doc, params, res)
            drawOpen(e, line(20f, 60.5f, 180f, 60.5f, 3f))
            e.endPatch()
            return doc.activeRaster!!.pixels
        }
        val tex = TextureImage(wrap())
        val plain = draw(hard, BrushResources.EMPTY)
        val zero = draw(hard.copy(texture = TextureParams(depth = 0f)), BrushResources(texture = tex))
        StrokeTestKit.assertGridsEqual(plain, zero, "depth 0")
        // No texture image at all: the texture params alone change nothing either.
        val noImage = draw(hard.copy(texture = TextureParams(depth = 1f)), BrushResources.EMPTY)
        StrokeTestKit.assertGridsEqual(plain, noImage, "no texture image")
    }

    @Test
    fun canvasFixedSharesOnePatternBetweenCrossingStrokes() {
        val tex = TextureImage(wrap())
        val px = 100
        val py = 100

        /** Alpha of the stroke buffer at ([px], [py]) for a horizontal or vertical stroke through that pixel. */
        fun buffer(horiz: Boolean, textured: Boolean): Float {
            val doc = Document.create(200, 200)
            val params = hard.copy(texture = if (textured) TextureParams(TextureMode.CANVAS_FIXED, depth = 1f) else null)
            val res = if (textured) BrushResources(texture = tex) else BrushResources.EMPTY
            val e = engine(doc, params, res)
            drawOpen(e, if (horiz) line(20f, 100.5f, 180f, 100.5f, 4f) else line(100.5f, 20f, 100.5f, 180f, 4f))
            return alpha(e.buffer, px, py)
        }
        val ratioH = buffer(horiz = true, textured = true) / buffer(horiz = true, textured = false)
        val ratioV = buffer(horiz = false, textured = true) / buffer(horiz = false, textured = false)
        val expected = tex.sample((px + 0.5f) / 512f, (py + 0.5f) / 512f)
        assertTrue(expected > 0.1f && expected < 0.95f, "test needs a mid-tone grain, got $expected")
        assertEquals(ratioH, ratioV, 1e-3f, "canvas-fixed: same texture factor on both strokes")
        assertEquals(expected, ratioH, 2e-3f, "texture factor at the pixel")
    }

    /** One dab of [mode] into a fresh tile; the pixel alpha equals that mode's grain factor. */
    private fun dabAlpha(mode: TextureMode, x: Int, y: Int, dabX: Float, dabY: Float, angle: Float = 0f): Float {
        val kernel = MaskKernel(
            MaskOp.OVER, null, antialias = true, depth = 0f, alphaScale = 1f, ceiling = true,
            tex = TextureParams(mode = mode, depth = 1f), texImage = TextureImage(wrap()), brushSize = 200f,
        )
        val dab = Dab()
        dab.x = dabX
        dab.y = dabY
        dab.d = 200f
        dab.hardness = 1f
        dab.flow = 1f
        dab.a = 1f
        dab.opacity = 1f
        dab.texDepth = 1f
        dab.angle = angle
        val tile = Tile(precise = true)
        kernel.renderTile(tile, 0, 0, 256, 256, dab, null, 1f)
        return tile[tile.index(x, y) + 3]
    }

    @Test
    fun uvModesPlaceTheGrainDifferently() {
        val tex = TextureImage(wrap())
        val px = 120.5f
        val py = 100.5f
        // canvas-fixed: the UV is the document position.
        assertEquals(tex.sample(px / 512f, py / 512f), dabAlpha(TextureMode.CANVAS_FIXED, 120, 100, 60f, 60f), 2e-3f)
        // moving: the UV is the offset from the dab centre, so the grain travels with the dab.
        assertEquals(
            tex.sample((px - 60f) / 512f, (py - 60f) / 512f),
            dabAlpha(TextureMode.MOVING, 120, 100, 60f, 60f),
            2e-3f,
        )
        // rotate-with-stroke: the document UV is rotated by the dab angle around the dab centre.
        val a = PI.toFloat() / 6
        val dx = px - 60f
        val dy = py - 60f
        assertEquals(
            tex.sample((dx * cos(a) + dy * sin(a)) / 512f, (-dx * sin(a) + dy * cos(a)) / 512f),
            dabAlpha(TextureMode.ROTATE_WITH_STROKE, 120, 100, 60f, 60f, a),
            2e-3f,
        )
    }

    @Test
    fun tilingHasNoSeamAtThePeriodBoundary() {
        val tex = TextureImage(wrap())
        val doc = Document.create(1100, 100)
        val e = engine(doc, hard.copy(texture = TextureParams(TextureMode.CANVAS_FIXED, depth = 1f)), BrushResources(texture = tex))
        drawOpen(e, line(10f, 50.5f, 1090f, 50.5f, 8f))
        e.endPatch()
        val px = doc.activeRaster!!.pixels
        for (x in intArrayOf(511, 1023)) {
            val a = alpha(px, x, 50)
            val b = alpha(px, x + 1, 50)
            assertTrue(a > 0.1f, "no paint at $x")
            assertTrue(abs(a - b) <= 2f / 255f, "seam at $x: $a vs $b")
        }
    }

    @Test
    fun lowerLimitCapsTheStrokeAlpha() {
        val black = TextureImage(gray(64, 64) { _, _ -> 0f })
        // Five passes over the same line, each a sub-stroke of one stroke (the pen is lifted on reversal).
        val passes = (0 until 5).flatMap { _ -> listOf(20f, 140f) }
            .mapIndexed { i, x -> InputPoint(x, 80.5f, timeMs = i * 8L) }

        fun alphaAfter(lowerLimit: Float): Float {
            val doc = Document.create(200, 160)
            val params = hard.copy(texture = TextureParams(TextureMode.CANVAS_FIXED, depth = 1f, lowerLimit = lowerLimit))
            val e = engine(doc, params, BrushResources(texture = black), options = StrokeOptions(stabilize = false, stages = listOf(LiftOnReverse())))
            drawOpen(e, passes)
            e.endPatch()
            return alpha(doc.activeRaster!!.pixels, 80, 80)
        }
        // A black grain with depth 1 would paint nothing; the lower limit holds the alpha at L.
        assertEquals(0f, alphaAfter(1f), 1e-3f, "no ceiling: the dark grain hides the paint")
        assertEquals(0.3f, alphaAfter(0.3f), 0.01f, "repeated passes stop at the lower limit")
    }

    /** Lifts the pen whenever x runs backwards, so a back-and-forth path becomes several sub-strokes. */
    private class LiftOnReverse : PointStage {
        private var lastX = Float.NaN

        override fun begin(p: InputPoint, out: PointSink) {
            lastX = p.x
            out.point(p)
        }

        override fun add(p: InputPoint, out: PointSink) {
            if (p.x < lastX) out.lift()
            lastX = p.x
            out.point(p)
        }

        override fun end(out: PointSink) {}
    }
}
