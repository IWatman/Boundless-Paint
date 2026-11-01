package app.painter.core.filter

import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The final M5 filters: determinism, tiling, selection, alpha lock, every control and the codec. */
class FiltersM5FinalTest {

    private fun grid(w: Int, h: Int) = TileGrid(w, h)

    private fun put(g: TileGrid, x: Int, y: Int, r: Float, gg: Float, b: Float, a: Float) {
        g.setPixel(x, y, r * a, gg * a, b * a, a)
    }

    private fun px(g: TileGrid, x: Int, y: Int): FloatArray =
        FloatArray(4).also { g.getPixel(x, y, it) }

    /** A smooth gradient with two bright spots, so every filter has colour and light to work with. */
    private fun dense(w: Int, h: Int, alpha: Float = 1f): TileGrid {
        val g = grid(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = x / (w - 1f)
            val v = y / (h - 1f)
            put(g, x, y, 0.2f + 0.6f * u, 0.25f + 0.4f * (u + v) * 0.5f, 0.2f + 0.6f * v, alpha)
        }
        for (y in h / 2 - 4 until h / 2 + 4) for (x in w / 4 - 4 until w / 4 + 4) {
            put(g, x, y, 1f, 1f, 1f, alpha)
        }
        for (y in h / 4 - 3 until h / 4 + 3) for (x in w * 3 / 4 - 3 until w * 3 / 4 + 3) {
            put(g, x, y, 1f, 0.9f, 0.7f, alpha)
        }
        return g
    }

    /** The gradient with punched transparent holes, so the filters see real shape edges. */
    private fun wash(w: Int, h: Int, alpha: Float = 1f): TileGrid {
        val g = dense(w, h, alpha)
        punch(g, w / 4, h / 2, (min(w, h) * 0.09f).coerceAtLeast(4f))
        punch(g, w * 3 / 4, h / 3, (min(w, h) * 0.07f).coerceAtLeast(3f))
        // The middle hole crosses the x = 255 tile edge of a 512-wide grid.
        punch(g, w / 2, h / 2, (min(w, h) * 0.1f).coerceAtLeast(4f))
        return g
    }

    private fun punch(g: TileGrid, cx: Int, cy: Int, radius: Float) {
        for (y in 0 until g.height) for (x in 0 until g.width) {
            if (hypot(x + 0.5f - cx, y + 0.5f - cy) <= radius) g.setPixel(x, y, 0f, 0f, 0f, 0f)
        }
    }

    private fun params(): List<FilterParams> = listOf(
        MangaParams(brightness = 10f, edge = 70f, toneSize = 6, strength = 1f),
        MangaBackgroundParams(contrast = 60f, edgeDarkness = 0.8f, toneSize = 6, strength = 1f),
        AnimeBackgroundParams(
            brightness = 10f, contrast = 30f, saturation = 40f, posterize = 6, strength = 1f,
        ),
        PencilSketchParams(lineLength = 12f, strength = 1f),
        OilPaintParams(radius = 4, levels = 8, strength = 1f),
        BlurFrameParams(width = 40f, blur = 8f, strength = 1f),
        TableFrameParams(width = 12f, color = "#FFFFFF", cornerRadius = 16f, strength = 1f),
        RainParams(density = 0.5f, angleDeg = 12f, length = 28f, opacity = 0.6f, seed = 7L, strength = 1f),
    )

    @Test
    fun everyFilterIsDeterministicAndChangesANonEmptyImage() {
        for (p in params()) {
            val a = wash(96, 96)
            val b = wash(96, 96)
            val before = FloatArray(a.width * a.height * 4)
            var n = 0
            for (y in 0 until a.height) for (x in 0 until a.width) {
                val v = px(a, x, y)
                for (c in 0..3) before[n++] = v[c]
            }
            Filters.apply("l", a, p)
            Filters.apply("l", b, p)
            var changed = 0.0
            n = 0
            for (y in 0 until a.height) for (x in 0 until a.width) {
                val pa = px(a, x, y)
                val pb = px(b, x, y)
                assertContentEquals(pa, pb, "${p.kind} is deterministic at $x,$y")
                for (c in 0..3) changed += abs(pa[c] - before[n++])
            }
            assertTrue(changed > 1.0, "${p.kind} visibly changes the image: $changed")
        }
    }

    @Test
    fun everyFilterIsIndependentOfTheThreadCount() {
        for (p in params()) {
            val single = wash(512, 256)
            val parallel = wash(512, 256)
            Filters.apply("l", single, p, threads = 1)
            Filters.apply("l", parallel, p, threads = 4)
            for (y in 0 until 256) for (x in 0 until 512) {
                assertContentEquals(px(single, x, y), px(parallel, x, y), "${p.kind} differs at $x,$y")
            }
        }
    }

    @Test
    fun everyFilterIsThreadIndependentInLiveFilterLayers() {
        for (p in params()) {
            val single = wash(512, 256)
            val parallel = wash(512, 256)
            Filters.applyToGrid(single, p, threads = 1)
            Filters.applyToGrid(parallel, p, threads = 4)
            for (y in 0 until 256) for (x in 0 until 512) {
                assertContentEquals(px(single, x, y), px(parallel, x, y), "${p.kind} differs at $x,$y")
            }
        }
    }

    @Test
    fun everyFilterClipsToTheSelection() {
        for (p in params()) {
            val g = dense(128, 64)
            val sel = MaskGrid(128, 64, 0)
            val tile = sel.getOrCreate(0, 0)
            for (y in 0 until 64) for (x in 0 until 64) tile.data[y * Tile.SIZE + x] = 255.toByte()
            tile.markDirty()
            val untouched = px(g, 100, 20)
            Filters.apply("l", g, p, selection = sel)
            assertContentEquals(untouched, px(g, 100, 20), "${p.kind} wrote outside the selection")
        }
    }

    @Test
    fun everyFilterRespectsAlphaLock() {
        for (p in params()) {
            val g = wash(64, 64, alpha = 0.5f)
            val source = g.copy()
            Filters.apply("l", g, p, alphaLock = true)
            for (y in 0 until 64) for (x in 0 until 64) {
                assertEquals(
                    px(source, x, y)[3], px(g, x, y)[3], 1e-4f,
                    "${p.kind} changed alpha at $x,$y",
                )
            }
        }
    }

    @Test
    fun outputsStayPremultiplied() {
        for (p in params()) {
            val g = wash(96, 96, alpha = 0.6f)
            Filters.apply("l", g, p)
            for (y in 0 until 96) for (x in 0 until 96) {
                val v = px(g, x, y)
                assertTrue(v[3] in 0f..1.0001f, "${p.kind} alpha out of range at $x,$y: ${v[3]}")
                for (c in 0..2) {
                    assertTrue(
                        v[c] >= -1e-5f && v[c] <= v[3] + 1e-4f,
                        "${p.kind} broke premultiplication at $x,$y: ${v.toList()}",
                    )
                }
            }
        }
    }

    @Test
    fun outputsAreContinuousAtTheTileBoundary() {
        for (p in params()) {
            val horizontal = wash(512, 256)
            Filters.apply("l", horizontal, p)
            val boundary = columnDiff(horizontal, 255)
            // A sharp filter has natural edges of the same magnitude elsewhere, so the seam must not exceed
            // the largest ordinary edge in the rest of the image.
            var normalEdge = 0.0
            for (x in 0 until 510) {
                if (x in 240..270) continue
                normalEdge = maxOf(normalEdge, columnDiff(horizontal, x))
            }
            assertTrue(
                boundary <= normalEdge * 1.25 + 0.01,
                "${p.kind} has a vertical tile seam: boundary=$boundary normalEdge=$normalEdge",
            )

            val vertical = wash(256, 512)
            Filters.apply("l", vertical, p)
            val rowBoundary = rowDiff(vertical, 255)
            var normalRow = 0.0
            for (y in 0 until 510) {
                if (y in 240..270) continue
                normalRow = maxOf(normalRow, rowDiff(vertical, y))
            }
            assertTrue(
                rowBoundary <= normalRow * 1.25 + 0.01,
                "${p.kind} has a horizontal tile seam: boundary=$rowBoundary normalRow=$normalRow",
            )
        }
    }

    @Test
    fun mangaAndPencilProduceGreyPaperAndInk() {
        for (p in listOf(
            MangaParams(brightness = 0f, edge = 80f, toneSize = 6),
            MangaBackgroundParams(contrast = 50f, edgeDarkness = 0.9f, toneSize = 6),
            PencilSketchParams(lineLength = 10f),
        )) {
            val g = wash(96, 96)
            Filters.apply("l", g, p)
            var ink = 0
            var paper = 0
            for (y in 0 until 96) for (x in 0 until 96) {
                val v = px(g, x, y)
                if (v[3] <= 0.01f) continue
                assertEquals(v[0], v[1], 1e-5f, "${p.kind} is not grey at $x,$y")
                assertEquals(v[1], v[2], 1e-5f, "${p.kind} is not grey at $x,$y")
                val value = v[0] / v[3]
                if (value < 0.35f) ink++
                if (value > 0.85f) paper++
            }
            assertTrue(ink > 0, "${p.kind} has no dark ink")
            assertTrue(paper > 0, "${p.kind} has no paper")
        }
    }

    @Test
    fun animeBackgroundLimitsEachChannelToThePosterizeLevels() {
        for (levels in listOf(3, 5, 12)) {
            val g = dense(96, 96)
            Filters.apply("l", g, AnimeBackgroundParams(posterize = levels))
            val seen = arrayOf(HashSet<Float>(), HashSet<Float>(), HashSet<Float>())
            for (y in 0 until 96) for (x in 0 until 96) {
                val v = px(g, x, y)
                for (c in 0..2) seen[c].add(v[c])
            }
            for (c in 0..2) {
                assertTrue(
                    seen[c].size <= levels,
                    "posterize $levels left ${seen[c].size} values in channel $c",
                )
            }
        }
    }

    @Test
    fun oilPaintFlattensTheDetailIntoFewColours() {
        val g = dense(96, 96)
        Filters.apply("l", g, OilPaintParams(radius = 4, levels = 4))
        val colours = HashSet<Int>()
        for (y in 0 until 96) for (x in 0 until 96) {
            val v = px(g, x, y)
            if (v[3] <= 0.01f) continue
            colours.add(
                ((v[0] * 255).roundToInt() shl 16) or
                    ((v[1] * 255).roundToInt() shl 8) or
                    (v[2] * 255).roundToInt(),
            )
        }
        assertTrue(colours.size in 2..64, "oil paint left ${colours.size} colours at 4 levels")
    }

    @Test
    fun blurFrameBlursTheBorderAndLeavesTheMiddleAlone() {
        val g = grid(128, 128)
        for (y in 0 until 128) for (x in 0 until 128) {
            val light = ((x / 8) + (y / 8)) and 1 == 0
            put(g, x, y, if (light) 1f else 0f, if (light) 1f else 0f, if (light) 1f else 0f, 1f)
        }
        val source = g.copy()
        fun detail(at: TileGrid, y: Int, x0: Int, x1: Int): Double {
            var sum = 0.0
            for (x in x0 until x1) {
                val a = px(at, x, y)
                val b = px(at, x + 1, y)
                for (c in 0..2) sum += abs(a[c] - b[c])
            }
            return sum / ((x1 - x0) * 3)
        }
        Filters.apply("l", g, BlurFrameParams(width = 24f, blur = 8f))
        val borderBefore = detail(source, 64, 3, 12)
        val borderAfter = detail(g, 64, 3, 12)
        val middleBefore = detail(source, 64, 60, 68)
        val middleAfter = detail(g, 64, 60, 68)
        assertTrue(borderAfter < borderBefore * 0.5, "the blur frame did not soften the border")
        assertEquals(middleBefore, middleAfter, 1e-6, "the blur frame reached the middle")
    }

    @Test
    fun tableFramePaintsOnlyTheBorder() {
        val g = grid(96, 96)
        for (y in 0 until 96) for (x in 0 until 96) put(g, x, y, 0.8f, 0.1f, 0.1f, 1f)
        val middle = px(g, 48, 48)
        val corner = px(g, 0, 0)
        Filters.apply("l", g, TableFrameParams(width = 12f, color = "#FFFFFF", cornerRadius = 8f))
        assertContentEquals(middle, px(g, 48, 48), "the table frame reached the middle")
        assertContentEquals(corner, px(g, 0, 0), "the rounded corner was not cut")
        val border = px(g, 2, 48)
        assertEquals(1f, border[0], 1e-4f, "the border is not white")
        assertEquals(1f, border[1], 1e-4f, "the border is not white")
        assertEquals(1f, border[2], 1e-4f, "the border is not white")
        assertEquals(1f, border[3], 1e-4f, "the border is not opaque")
    }

    @Test
    fun rainDrawsSparseWhiteStreaksOnAnEmptyLayer() {
        val g = grid(128, 128)
        Filters.apply(
            "l", g,
            RainParams(density = 1f, angleDeg = 90f, length = 40f, opacity = 1f, seed = 3L),
        )
        var painted = 0
        var clear = 0
        for (y in 0 until 128) for (x in 0 until 128) {
            val v = px(g, x, y)
            if (v[3] <= 0.001f) {
                clear++
            } else {
                painted++
                assertEquals(v[0], v[3], 1e-4f, "the rain is not white at $x,$y")
                assertEquals(v[1], v[3], 1e-4f, "the rain is not white at $x,$y")
                assertEquals(v[2], v[3], 1e-4f, "the rain is not white at $x,$y")
            }
        }
        assertTrue(painted > 100, "the rain painted too few pixels: $painted")
        assertTrue(clear > 100, "the rain flooded the canvas: painted=$painted")
    }

    @Test
    fun everyNewControlChangesTheResult() {
        val pairs = listOf(
            "manga brightness" to (MangaParams(brightness = -60f) to MangaParams(brightness = 60f)),
            "manga edge" to (MangaParams(edge = 0f) to MangaParams(edge = 100f)),
            "manga tone size" to (MangaParams(toneSize = 3) to MangaParams(toneSize = 20)),
            "manga background contrast" to (MangaBackgroundParams(contrast = 0f) to
                MangaBackgroundParams(contrast = 100f)),
            "manga background edge darkness" to (MangaBackgroundParams(edgeDarkness = 0f) to
                MangaBackgroundParams(edgeDarkness = 1f)),
            "manga background tone size" to (MangaBackgroundParams(toneSize = 3) to
                MangaBackgroundParams(toneSize = 20)),
            "anime brightness" to (AnimeBackgroundParams(brightness = -60f) to
                AnimeBackgroundParams(brightness = 60f)),
            "anime contrast" to (AnimeBackgroundParams(contrast = -60f) to
                AnimeBackgroundParams(contrast = 60f)),
            "anime saturation" to (AnimeBackgroundParams(saturation = -100f) to
                AnimeBackgroundParams(saturation = 100f)),
            "anime posterize" to (AnimeBackgroundParams(posterize = 2) to
                AnimeBackgroundParams(posterize = 12)),
            "pencil line length" to (PencilSketchParams(lineLength = 4f) to
                PencilSketchParams(lineLength = 32f)),
            "oil paint radius" to (OilPaintParams(radius = 1) to OilPaintParams(radius = 8)),
            "oil paint levels" to (OilPaintParams(levels = 3) to OilPaintParams(levels = 20)),
            "blur frame width" to (BlurFrameParams(width = 8f) to BlurFrameParams(width = 80f)),
            "blur frame blur" to (BlurFrameParams(blur = 2f) to BlurFrameParams(blur = 24f)),
            "table frame width" to (TableFrameParams(width = 4f) to TableFrameParams(width = 40f)),
            "table frame colour" to (TableFrameParams(color = "#FFFFFF") to
                TableFrameParams(color = "#FF0000")),
            "table frame corner radius" to (TableFrameParams(cornerRadius = 0f) to
                TableFrameParams(cornerRadius = 40f)),
            "rain density" to (RainParams(density = 0.2f) to RainParams(density = 1f)),
            "rain angle" to (RainParams(angleDeg = 0f) to RainParams(angleDeg = 90f)),
            "rain length" to (RainParams(length = 8f) to RainParams(length = 100f)),
            "rain opacity" to (RainParams(opacity = 0.2f) to RainParams(opacity = 1f)),
            "rain seed" to (RainParams(seed = 1L) to RainParams(seed = 99L)),
        )
        for ((name, pair) in pairs) {
            val a = wash(96, 96)
            val b = wash(96, 96)
            Filters.apply("l", a, pair.first)
            Filters.apply("l", b, pair.second)
            var delta = 0f
            for (y in 0 until 96) for (x in 0 until 96) {
                val pa = px(a, x, y)
                val pb = px(b, x, y)
                for (c in 0..3) delta += abs(pa[c] - pb[c])
            }
            assertTrue(delta > 0.01f, "$name does not change the result: $delta")
        }
    }

    @Test
    fun theCommonStrengthChangesEveryFilter() {
        for (p in params()) {
            val weak = when (p) {
                is MangaParams -> p.copy(strength = 0.25f)
                is MangaBackgroundParams -> p.copy(strength = 0.25f)
                is AnimeBackgroundParams -> p.copy(strength = 0.25f)
                is PencilSketchParams -> p.copy(strength = 0.25f)
                is OilPaintParams -> p.copy(strength = 0.25f)
                is BlurFrameParams -> p.copy(strength = 0.25f)
                is TableFrameParams -> p.copy(strength = 0.25f)
                is RainParams -> p.copy(strength = 0.25f)
                else -> error("unexpected ${p.kind}")
            }
            val a = wash(96, 96)
            val b = wash(96, 96)
            Filters.apply("l", a, weak)
            Filters.apply("l", b, p)
            var delta = 0f
            for (y in 0 until 96) for (x in 0 until 96) {
                val pa = px(a, x, y)
                val pb = px(b, x, y)
                for (c in 0..3) delta += abs(pa[c] - pb[c])
            }
            assertTrue(delta > 0.01f, "the strength of ${p.kind} does not change the result: $delta")
        }
    }

    /** Mean absolute difference between columns [x] and [x]+1 over all rows and channels. */
    private fun columnDiff(g: TileGrid, x: Int): Double {
        var sum = 0.0
        for (y in 0 until g.height) {
            val a = px(g, x, y)
            val b = px(g, x + 1, y)
            for (c in 0..3) sum += abs(a[c] - b[c])
        }
        return sum / (g.height * 4)
    }

    /** Mean absolute difference between rows [y] and [y]+1 over all columns and channels. */
    private fun rowDiff(g: TileGrid, y: Int): Double {
        var sum = 0.0
        for (x in 0 until g.width) {
            val a = px(g, x, y)
            val b = px(g, x, y + 1)
            for (c in 0..3) sum += abs(a[c] - b[c])
        }
        return sum / (g.width * 4)
    }

    @Test
    fun allFinalParamsRoundTripThroughTheCodec() {
        val formats = listOf(
            MangaParams(brightness = -37f, edge = 83f, toneSize = 19, strength = 0.8f),
            MangaBackgroundParams(contrast = 77f, edgeDarkness = 0.45f, toneSize = 11, strength = 0.9f),
            AnimeBackgroundParams(
                brightness = 21f, contrast = -45f, saturation = 68f, posterize = 13, strength = 0.7f,
            ),
            PencilSketchParams(lineLength = 33f, strength = 0.85f),
            OilPaintParams(radius = 9, levels = 21, strength = 0.75f),
            BlurFrameParams(width = 137f, blur = 27f, strength = 0.65f),
            TableFrameParams(width = 43f, color = "#40A0C0", cornerRadius = 71f, strength = 0.95f),
            RainParams(
                density = 0.83f, angleDeg = 217f, length = 143f, opacity = 0.41f,
                seed = 987654321L, strength = 0.6f,
            ),
        )
        for (p in formats) {
            val json = FilterCodec.toJson(p)
            assertEquals(p, FilterCodec.fromJson(json), "codec round trip of ${p.kind}")
        }
    }
}
