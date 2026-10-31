package app.painter.core.filter

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.history.TilePatchCommand
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/** Destructive filters of spec/filters.md: tiling, determinism, selection and alpha lock. */
class FiltersTest {

    private fun grid(w: Int, h: Int) = TileGrid(w, h)

    private fun put(g: TileGrid, x: Int, y: Int, r: Float, gg: Float, b: Float, a: Float) {
        g.setPixel(x, y, r * a, gg * a, b * a, a)
    }

    private fun px(g: TileGrid, x: Int, y: Int): FloatArray =
        FloatArray(4).also { g.getPixel(x, y, it) }

    @Test
    fun blurSpreadsInsideTheTile() {
        val g = grid(300, 20)
        put(g, 150, 10, 1f, 1f, 1f, 1f)
        Filters.apply("l", g, BlurParams(radius = 4f))
        assertTrue(px(g, 150, 10)[3] < 1f, "the source pixel gives its alpha away")
        assertTrue(px(g, 152, 10)[3] > 0f, "the blur reaches a neighbour")
        assertEquals(0f, px(g, 280, 10)[3], "far away nothing appears")
    }

    @Test
    fun blurHasNoSeamAtATileBoundary() {
        val g = grid(512, 40)
        // One dot just left of the boundary and one just right of it.
        put(g, 250, 20, 1f, 1f, 1f, 1f)
        put(g, 262, 20, 1f, 1f, 1f, 1f)
        Filters.apply("l", g, BlurParams(radius = 6f))
        assertTrue(px(g, 256, 20)[3] > 0f, "the right tile sees the dot of the left tile")
        assertTrue(px(g, 255, 20)[3] > 0f, "the left tile sees the dot of the right tile")
        // The profile is smooth across x = 256: the second difference stays small.
        val v255 = px(g, 255, 20)[3]
        val v256 = px(g, 256, 20)[3]
        val v257 = px(g, 257, 20)[3]
        assertTrue(abs(v255 - 2 * v256 + v257) < 0.02f, "no step at the tile edge")
    }

    @Test
    fun pixelateMakesUniformBlocks() {
        val g = grid(64, 64)
        for (y in 0 until 64) for (x in 0 until 64) put(g, x, y, x / 63f, y / 63f, 0.5f, 1f)
        Filters.apply("l", g, PixelateParams(cell = 8))
        for (by in 0 until 8) for (bx in 0 until 8) {
            val first = px(g, bx * 8, by * 8)
            for (y in by * 8 until by * 8 + 8) for (x in bx * 8 until bx * 8 + 8) {
                assertContentEquals(first, px(g, x, y), "block $bx,$by is uniform at $x,$y")
            }
        }
    }

    @Test
    fun sharpenBoostsAnEdge() {
        val g = grid(64, 8)
        for (y in 0 until 8) for (x in 0 until 64) {
            val v = if (x < 32) 0.35f else 0.65f
            put(g, x, y, v, v, v, 1f)
        }
        val before = abs(px(g, 32, 4)[0] - px(g, 31, 4)[0])
        Filters.apply("l", g, SharpenParams(amount = 1.5f, radius = 1f))
        val after = abs(px(g, 32, 4)[0] - px(g, 31, 4)[0])
        assertTrue(after > before, "the step across the edge grows: $before -> $after")
    }

    @Test
    fun noiseIsDeterministicAndKeepsTransparency() {
        val a = grid(32, 32)
        val b = grid(32, 32)
        for (g in listOf(a, b)) for (y in 8 until 24) for (x in 8 until 24) put(g, x, y, 0.5f, 0.5f, 0.5f, 1f)
        Filters.apply("l", a, NoiseParams(amount = 0.3f, seed = 7))
        Filters.apply("l", b, NoiseParams(amount = 0.3f, seed = 7))
        for (y in 0 until 32) for (x in 0 until 32) {
            assertContentEquals(px(a, x, y), px(b, x, y), "deterministic at $x,$y")
        }
        assertEquals(0f, px(a, 0, 0)[3], "a transparent pixel stays transparent")
        assertTrue(abs(px(a, 12, 12)[0] - 0.5f) > 0.001f, "noise changed the colour")
        for (y in 0 until 32) for (x in 0 until 32) {
            val p = px(a, x, y)
            assertTrue(p[0] in 0f..1f && p[3] in 0f..1f, "bounded at $x,$y")
        }
    }

    @Test
    fun selectionClipsTheWrite() {
        val g = grid(128, 64)
        for (y in 0 until 64) for (x in 0 until 128) put(g, x, y, 1f, 0f, 0f, 1f)
        val sel = MaskGrid(128, 64, 0)
        for (y in 0 until 64) for (x in 0 until 64) sel.getOrCreate(0, 0).data[y * Tile.SIZE + x] = 255.toByte()
        sel.getOrCreate(0, 0).markDirty()
        Filters.apply("l", g, PixelateParams(cell = 32), selection = sel)
        // Left half cell 0 is uniform (0..31); the right half was not touched: still the original red.
        assertEquals(1f, px(g, 100, 10)[0])
        assertEquals(1f, px(g, 100, 10)[3])
    }

    @Test
    fun alphaLockKeepsTheOriginalAlpha() {
        val g = grid(64, 64)
        for (y in 0 until 64) for (x in 0 until 64) put(g, x, y, 1f, 0f, 0f, 0.5f)
        val before = px(g, 32, 32)[3]
        Filters.apply("l", g, BlurParams(radius = 6f), alphaLock = true)
        for (y in 0 until 64) for (x in 0 until 64) {
            assertEquals(before, px(g, x, y)[3], "alpha locked at $x,$y")
        }
    }

    @Test
    fun glowAddsAHalo() {
        val g = grid(128, 64)
        put(g, 64, 32, 1f, 1f, 1f, 1f)
        Filters.apply("l", g, GlowParams(threshold = 0.5f, radius = 8f, strength = 1f))
        assertTrue(px(g, 64, 32)[0] > 0.9f, "the core stays bright")
        assertTrue(px(g, 70, 32)[3] > 0f, "the halo gains alpha")
    }

    @Test
    fun edgesTraceTheBorder() {
        val g = grid(64, 64)
        for (y in 16 until 48) for (x in 16 until 48) put(g, x, y, 1f, 1f, 1f, 1f)
        Filters.apply("l", g, EdgesParams(amount = 1f))
        assertTrue(px(g, 16, 32)[0] > 0.2f, "an edge on the left border")
        assertTrue(px(g, 32, 32)[0] < 0.05f, "flat interior stays dark")
        assertTrue(px(g, 8, 8)[3] == 0f, "outside the shape stays transparent")
    }

    @Test
    fun chromaticShiftsRedAndBlue() {
        val g = grid(64, 8)
        for (y in 0 until 8) for (x in 0 until 64) put(g, x, y, 1f, 1f, 1f, 1f)
        // A single dark dot: red samples to its left, blue to its right, green stays put.
        put(g, 32, 4, 0f, 0f, 0f, 1f)
        Filters.apply("l", g, ChromaticParams(distance = 3f, angleDeg = 0f))
        assertTrue(px(g, 29, 4)[0] < px(g, 32, 4)[0], "red is taken from +x")
        assertTrue(px(g, 35, 4)[2] < px(g, 32, 4)[2], "blue is taken from -x")
        assertEquals(px(g, 32, 4)[1], px(g, 32, 4)[1], "green stays")
    }

    @Test
    fun emptyLayerIsANoOp() {
        val g = grid(64, 64)
        val patch = Filters.apply("l", g, BlurParams())
        assertTrue(patch.before.isEmpty() && patch.after.isEmpty())
    }

    @Test
    fun patchUndoesTheFilter() {
        val doc = Document.create(64, 64)
        val layer = doc.activeRaster!!
        for (y in 0 until 64) for (x in 0 until 64) layer.pixels.setPixel(x, y, 0.2f, 0.4f, 0.6f, 1f)
        val original = FloatArray(4).also { layer.pixels.getPixel(10, 10, it) }
        val patch = Filters.apply(layer.id, layer.pixels, PixelateParams(cell = 16))
        val cmd = TilePatchCommand(patch, "Filter")
        cmd.revert(doc)
        assertContentEquals(original, FloatArray(4).also { layer.pixels.getPixel(10, 10, it) })
        cmd.apply(doc)
        assertTrue(!patch.after.isEmpty(), "the command re-applies the filtered tiles")
    }

    @Test
    fun fisheyeBulgesAroundTheContentCentre() {
        val g = grid(128, 128)
        // A dot 24 px above the centre is sampled further out by a bulge, so it moves.
        put(g, 64, 40, 1f, 1f, 1f, 1f)
        var before = 0f
        for (y in 0 until 128) for (x in 0 until 128) before += px(g, x, y)[3]
        Filters.apply("l", g, FisheyeParams(amount = 0.8f, scale = 1f))
        assertTrue(px(g, 64, 40)[3] < 0.5f, "the dot no longer sits where it was")
        var after = 0f
        for (y in 0 until 128) for (x in 0 until 128) after += px(g, x, y)[3]
        assertTrue(abs(after - before) < before * 0.25f, "the bulge keeps the content: $before -> $after")
    }

    @Test
    fun dilateGrowsAndErodeShrinksTheShape() {
        val g = grid(64, 64)
        for (y in 28 until 36) for (x in 28 until 36) put(g, x, y, 1f, 1f, 1f, 1f)
        val dilated = g.copy()
        Filters.apply("l", dilated, DilateParams(radius = 4))
        assertTrue(px(dilated, 25, 32)[3] > 0f, "dilate grows the shape")
        Filters.apply("l", g, ErodeParams(radius = 4))
        assertEquals(0f, px(g, 32, 32)[3], "erode takes the whole 8 px square away")
    }

    @Test
    fun zoomBlurSmearsAwayFromTheCentre() {
        val g = grid(128, 128)
        // A dot off-centre: a radial blur pulls the samples toward the centre, so the smear lies outward.
        put(g, 96, 64, 1f, 1f, 1f, 1f)
        Filters.apply("l", g, ZoomBlurParams(amount = 0.6f))
        assertTrue(px(g, 96, 64)[3] < 1f, "the dot fades at its own place")
        assertTrue(px(g, 104, 64)[3] > 0f, "content appears farther from the centre")
    }

    @Test
    fun spinBlurSmearsAroundTheCentre() {
        val g = grid(128, 128)
        put(g, 96, 64, 1f, 1f, 1f, 1f)
        Filters.apply("l", g, SpinBlurParams(angleDeg = 60f))
        assertTrue(px(g, 96, 64)[3] < 1f, "the dot is averaged with its arc")
        // 32 px from the centre, +-30 degrees: (64 + 32·cos30, 64 + 32·sin30) ≈ (92, 80).
        assertTrue(px(g, 92, 80)[3] > 0f, "the arc leaves a smear")
    }

    @Test
    fun selectionAndAlphaLockTogether() {
        val doc = Document.create(96, 32)
        val layer = doc.activeRaster!!
        for (y in 0 until 32) for (x in 0 until 96) layer.pixels.setPixel(x, y, 0.5f, 0.5f, 0.5f, 0.5f)
        val sel = MaskGrid(96, 32, 0)
        sel.getOrCreate(0, 0).data.fill(255.toByte())
        sel.getOrCreate(0, 0).markDirty()
        Filters.apply(layer.id, layer.pixels, BlurParams(radius = 4f), selection = sel, alphaLock = true)
        val p = FloatArray(4).also { layer.pixels.getPixel(5, 5, it) }
        assertEquals(0.5f, p[3], 1e-4f)
    }

    @Test
    fun paramsRoundTrip() {
        val formats = listOf(
            BlurParams(radius = 12f, strength = 0.8f),
            MotionBlurParams(distance = 30f, angleDeg = 45f),
            PixelateParams(cell = 9),
            SharpenParams(amount = 1.2f, radius = 2f),
            GlowParams(threshold = 0.4f, radius = 12f, strength = 0.7f),
            NoiseParams(amount = 0.2f, mono = false, seed = 5),
            EdgesParams(amount = 1.5f),
            ChromaticParams(distance = 6f, angleDeg = 90f),
            FisheyeParams(amount = -0.4f, scale = 0.8f),
        )
        val json = kotlinx.serialization.json.Json { classDiscriminator = "kind"; encodeDefaults = true }
        for (p in formats) {
            val text = json.encodeToString(FilterParams.serializer(), p)
            val back = json.decodeFromString(FilterParams.serializer(), text)
            assertEquals(p, back, "round trip of ${p.kind}")
        }
    }
}
