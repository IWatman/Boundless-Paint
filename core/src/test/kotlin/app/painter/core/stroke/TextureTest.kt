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
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Grain texture (spec/tools/m2-brush.md §1.4, stage S2a): depth 0 is the untextured stroke, the UV modes place
 * the grain, canvas-fixed UVs are shared by crossing strokes, the 512 px period tiles without a seam, and the
 * lower limit caps the alpha a stroke reaches; softness softens the tile without a seam (S2b), gradation fades the
 * depth along the dab axis, the texture jitter is drawn once per stroke from the seed, and warp-along-stroke runs
 * the grain along the path at a large stretch.
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

    /** High-frequency gray grain, periodic by construction (a hash-like step over both axes). */
    private fun noise() = gray(512, 512) { x, y -> ((x * 7919 + y * 104729) % 251) / 251f }

    /**
     * Alpha row [y] of one 400 px textured dab (hardness 1, depth 1) centred in a 256² tile: the dab covers the
     * whole tile, so every sample is the grain factor of that pixel. [seed] drives the per-stroke texture jitter.
     */
    private fun dabRow(tp: TextureParams, tex: TextureImage, seed: Long = 0L, y: Int = 128): FloatArray {
        val kernel = MaskKernel(
            MaskOp.OVER, null, antialias = true, depth = 0f, alphaScale = 1f, ceiling = true,
            tex = tp, texImage = tex, brushSize = 400f, seed = seed,
        )
        val dab = Dab()
        dab.x = 128f
        dab.y = 128f
        dab.d = 400f
        dab.hardness = 1f
        dab.flow = 1f
        dab.a = 1f
        dab.opacity = 1f
        dab.texDepth = 1f
        val tile = Tile(precise = true)
        kernel.renderTile(tile, 0, 0, 256, 256, dab, null, 1f)
        return FloatArray(256) { x -> tile[tile.index(x, y) + 3] }
    }

    /** Mean absolute difference of neighbouring samples of [row] between [from] and [to]. */
    private fun roughness(row: FloatArray, from: Int = 0, to: Int = row.size): Float {
        var sum = 0f
        for (x in from + 1 until to) sum += abs(row[x] - row[x - 1])
        return sum / (to - from - 1)
    }

    @Test
    fun softnessSmoothsTheGrainAndKeepsTheTileSeamless() {
        val src = noise()
        val textured = TextureParams(TextureMode.CANVAS_FIXED, depth = 1f)
        val sharp = roughness(dabRow(textured, TextureImage(src)))
        val soft = roughness(dabRow(textured, TextureImage(src, softness = 1f)))
        assertTrue(sharp > 0.05f, "the test needs a rough grain, got $sharp")
        assertTrue(soft < sharp * 0.25f, "softness 1 must flatten the grain: $soft vs $sharp")
        // The blur wraps around the tile edge, so the texels across the 512 px seam stay neighbours.
        val tile = TextureImage(src, softness = 1f)
        for (i in 0 until 4) {
            val k = 0.11f + 0.23f * i
            val right = tile.sample(511.5f / 512f, k)
            val left = tile.sample(512.5f / 512f, k)
            val bottom = tile.sample(k, 511.5f / 512f)
            val top = tile.sample(k, 512.5f / 512f)
            assertTrue(abs(right - left) <= 2f / 255f, "horizontal seam at v=$k: $right vs $left")
            assertTrue(abs(bottom - top) <= 2f / 255f, "vertical seam at u=$k: $bottom vs $top")
        }
    }

    @Test
    fun gradationFadesTheDepthAlongTheDabAxis() {
        // A flat mid-tone grain makes the dab alpha equal to the grain factor: f = 1 − depth·0.5.
        val flat = TextureImage(gray(64, 64) { _, _ -> 0.5f })
        val plain = TextureParams(TextureMode.CANVAS_FIXED, depth = 1f)
        // The 400 px dab fills the tile: x = 40 lies at τ ≈ 0.22 of its x axis, x = 216 at τ ≈ 0.72.
        val row = dabRow(plain, flat)
        assertEquals(0.502f, row[40], 5e-3f, "without gradation the grain depth is constant")
        assertEquals(row[40], row[216], 1e-4f, "without gradation both ends are equal")
        val graded = dabRow(plain.copy(gradation = 1f), flat)
        // Gradation only takes depth away, so the graded dab is lighter everywhere and lightest at the tail.
        assertTrue(graded[40] > row[40], "gradation lowers the depth: ${graded[40]} vs ${row[40]}")
        assertTrue(graded[216] > graded[40] + 0.02f, "the depth fades toward τ = 1: ${graded[216]} vs ${graded[40]}")
        // With a varying grain the τ = 0 end is the rougher one as well.
        val w = dabRow(plain.copy(gradation = 1f), TextureImage(wrap()))
        val tip = roughness(w, 32, 100)
        val tail = roughness(w, 156, 224)
        assertTrue(tip > 1.5f * tail, "the grain fades toward the tail: tip=$tip tail=$tail")
    }

    @Test
    fun textureJitterFollowsTheStrokeSeed() {
        val tex = TextureImage(wrap())
        val jittered = TextureParams(TextureMode.CANVAS_FIXED, depth = 1f, jitterPos = true)
        val a = dabRow(jittered, tex, seed = 1L)
        assertTrue(a.contentEquals(dabRow(jittered, tex, seed = 1L)), "the same seed must keep the pattern")
        val b = dabRow(jittered, tex, seed = 2L)
        var maxDiff = 0f
        for (i in a.indices) maxDiff = max(maxDiff, abs(a[i] - b[i]))
        assertTrue(maxDiff > 0.05f, "another seed must move the grain: max Δ = $maxDiff")
        // The scale jitter is drawn per stroke from the same seed.
        val scaled = TextureParams(TextureMode.CANVAS_FIXED, depth = 1f, jitterScale = true)
        assertTrue(!dabRow(scaled, tex, seed = 1L).contentEquals(dabRow(scaled, tex, seed = 2L)), "jitterScale uses the seed")
        // Without jitter the seed changes nothing.
        val off = TextureParams(TextureMode.CANVAS_FIXED, depth = 1f)
        assertTrue(dabRow(off, tex, seed = 1L).contentEquals(dabRow(off, tex, seed = 2L)), "no jitter, no seed effect")
    }

    @Test
    fun warpAlongStrokeRunsTheGrainAlongTheCurve() {
        // A full circle with tangent dabs: at θ = π/2 the path runs along x, so samples 1 px apart in row 230
        // follow the stroke while the ones down column 150 cross it.
        val doc = Document.create(300, 300)
        // A thin, dense brush: the dabs of a curve meet at sub-pixel offsets of v, as on a fibre brush.
        val params = hard.copy(
            size = 20f,
            spacing = 0.05f,
            texture = TextureParams(TextureMode.WARP_ALONG_STROKE, depth = 1f, stretch = 10f),
            head = HeadParams(rotationMode = RotationMode.TANGENT),
        )
        // A slightly softened grain (σ ≈ 0.8 px): neighbouring dabs of a curve meet with sub-pixel offsets of v, and
        // raw per-texel noise would decorrelate there although every dab runs its grain along the path.
        val e = engine(doc, params, BrushResources(texture = TextureImage(noise(), softness = 0.02f)))
        drawOpen(e, StrokeTestKit.circle(150.5f, 150.5f, 80f, 240))
        val buf = e.buffer
        var along = 0f
        for (x in 141 until 159) along += abs(alpha(buf, x + 1, 230) - alpha(buf, x, 230))
        var across = 0f
        for (y in 222 until 238) across += abs(alpha(buf, 150, y + 1) - alpha(buf, 150, y))
        assertTrue(across > 0.01f, "the grain still varies across the stroke: $across")
        assertTrue(across > 2f * along, "stretch 10 must run the grain along the curve: along=$along across=$across")
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
