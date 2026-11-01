package app.painter.core.filter

import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The M5 geometric, shape-pixelation and pointillism filters: tiling, determinism, codec. */
class FiltersM5GeometryTest {

    private fun grid(w: Int, h: Int) = TileGrid(w, h)

    private fun put(g: TileGrid, x: Int, y: Int, r: Float, gg: Float, b: Float, a: Float) {
        g.setPixel(x, y, r * a, gg * a, b * a, a)
    }

    private fun px(g: TileGrid, x: Int, y: Int): FloatArray =
        FloatArray(4).also { g.getPixel(x, y, it) }

    /** A smooth gradient with two bright spots, so every kind of filter has something to change. */
    private fun pattern(w: Int, h: Int, alpha: Float = 1f): TileGrid {
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

    private fun params(cx: Float = 0.45f, cy: Float = 0.55f): List<FilterParams> = listOf(
        SphereLensParams(radius = 40f, centerX = cx, centerY = cy, strength = 1f),
        WaveParams(amplitude = 10f, wavelength = 40f, angleDeg = 20f, strength = 1f),
        RippleParams(amplitude = 8f, frequency = 3f, centerX = cx, centerY = cy, strength = 1f),
        TwirlParams(angleDeg = 180f, radius = 40f, centerX = cx, centerY = cy, strength = 1f),
        PolarCoordinatesParams(variant = "rect-to-polar", strength = 1f),
        PolarCoordinatesParams(variant = "polar-to-rect", strength = 1f),
        ExpansionParams(strength = 1f),
        KaleidoscopeParams(segments = 6, centerX = cx, centerY = cy, strength = 1f),
        CrystallizeParams(size = 17, strength = 1f),
        HexPixelateParams(size = 13, strength = 1f),
        TrianglePixelateParams(size = 15, strength = 1f),
        PointillizeParams(size = 9, background = "#FFFFFF", strength = 1f),
    )

    @Test
    fun everyFilterIsDeterministicAndChangesANonEmptyImage() {
        for (p in params()) {
            val a = pattern(96, 96)
            val b = pattern(96, 96)
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
            val single = pattern(512, 256)
            val parallel = pattern(512, 256)
            Filters.apply("l", single, p, threads = 1)
            Filters.apply("l", parallel, p, threads = 4)
            for (y in 0 until 256) for (x in 0 until 512) {
                assertContentEquals(px(single, x, y), px(parallel, x, y), "${p.kind} differs at $x,$y")
            }
        }
    }

    @Test
    fun cellFiltersAreThreadIndependentInLiveFilterLayers() {
        val probes = listOf(
            CrystallizeParams(size = 17),
            HexPixelateParams(size = 13),
            TrianglePixelateParams(size = 15),
            PointillizeParams(size = 10),
        )
        for (p in probes) {
            val single = pattern(512, 256)
            val parallel = pattern(512, 256)
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
            val g = pattern(128, 64)
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
            val g = pattern(64, 64, alpha = 0.5f)
            Filters.apply("l", g, p, alphaLock = true)
            for (y in 0 until 64) for (x in 0 until 64) {
                assertEquals(0.5f, px(g, x, y)[3], 1e-4f, "${p.kind} changed alpha at $x,$y")
            }
        }
    }

    @Test
    fun fullSourceWarpsAreContinuousAtTheTileBoundary() {
        val probes = listOf(
            SphereLensParams(radius = 60f, centerX = 0.35f, centerY = 0.4f),
            WaveParams(amplitude = 14f, wavelength = 44f, angleDeg = 25f),
            RippleParams(amplitude = 10f, frequency = 3f, centerX = 0.35f, centerY = 0.4f),
            TwirlParams(angleDeg = 240f, radius = 70f, centerX = 0.35f, centerY = 0.4f),
            PolarCoordinatesParams(variant = "rect-to-polar"),
            PolarCoordinatesParams(variant = "polar-to-rect"),
            ExpansionParams(),
            KaleidoscopeParams(segments = 6, centerX = 0.35f, centerY = 0.4f),
        )
        for (p in probes) {
            val g = pattern(512, 256)
            Filters.apply("l", g, p)
            val boundary = columnDiff(g, 255)
            val diffs = ArrayList<Double>(510)
            for (x in 0 until 510) {
                if (x in 240..270) continue
                diffs.add(columnDiff(g, x))
            }
            diffs.sort()
            val baseline = diffs[diffs.size / 2]
            assertTrue(
                boundary <= baseline * 2.5 + 0.01,
                "${p.kind} has a tile seam: boundary=$boundary baseline=$baseline",
            )
        }
    }

    @Test
    fun shapeFiltersReadAcrossTileBoundaries() {
        val probes = listOf(
            CrystallizeParams(size = 17),
            HexPixelateParams(size = 13),
            TrianglePixelateParams(size = 15),
            PointillizeParams(size = 10),
        )
        for (p in probes) {
            val base = pattern(512, 128)
            val marked = pattern(512, 128)
            // A strong block that ends just left of the tile edge; its cell or dot must reach the right tile.
            for (y in 20 until 108) for (x in 220 until 256) put(marked, x, y, 1f, 0f, 0f, 1f)
            Filters.apply("l", base, p)
            Filters.apply("l", marked, p)
            var diff = 0f
            for (y in 0 until 128) for (x in 256 until 330) {
                val a = px(base, x, y)
                val b = px(marked, x, y)
                for (c in 0..3) diff += abs(a[c] - b[c])
            }
            assertTrue(diff > 0.5f, "${p.kind} ignored the content of the left tile: $diff")
        }
    }

    @Test
    fun cellShapesAreIdenticalOnBothSidesOfTheTileBoundary() {
        val probes: List<Pair<FilterParams, (Float, Float) -> Long>> = listOf(
            HexPixelateParams(size = 13) to { x, y -> Filters.hexKey(x, y, 13f) },
            TrianglePixelateParams(size = 15) to { x, y -> Filters.triangleKey(x, y, 15f) },
            CrystallizeParams(size = 17) to { x, y -> Filters.crystalKey(x, y, 17f) },
        )
        for ((p, keyOf) in probes) {
            val g = pattern(512, 256)
            Filters.apply("l", g, p)
            var sameCellRows = 0
            for (y in 0 until 256) {
                val left = keyOf(255.5f, y + 0.5f)
                val right = keyOf(256.5f, y + 0.5f)
                if (left != right) continue
                sameCellRows++
                assertContentEquals(
                    px(g, 255, y), px(g, 256, y),
                    "${p.kind} split one cell at the tile edge, row $y",
                )
            }
            assertTrue(sameCellRows > 0, "${p.kind} never crosses the tile edge in this setup")
        }
    }

    @Test
    fun everyNewControlChangesTheResult() {
        val pairs = listOf(
            "sphere radius" to (SphereLensParams(radius = 30f) to SphereLensParams(radius = 70f)),
            "sphere centre x" to (SphereLensParams(centerX = 0.3f) to SphereLensParams(centerX = 0.7f)),
            "sphere centre y" to (SphereLensParams(centerY = 0.3f) to SphereLensParams(centerY = 0.7f)),
            "wave amplitude" to (WaveParams(amplitude = 4f) to WaveParams(amplitude = 22f)),
            "wave wavelength" to (WaveParams(wavelength = 20f) to WaveParams(wavelength = 90f)),
            "wave angle" to (WaveParams(angleDeg = 0f) to WaveParams(angleDeg = 55f)),
            "ripple amplitude" to (RippleParams(amplitude = 3f) to RippleParams(amplitude = 18f)),
            "ripple frequency" to (RippleParams(frequency = 1f) to RippleParams(frequency = 7f)),
            "ripple centre x" to (RippleParams(centerX = 0.3f) to RippleParams(centerX = 0.7f)),
            "twirl angle" to (TwirlParams(angleDeg = 90f) to TwirlParams(angleDeg = 360f)),
            "twirl radius" to (TwirlParams(radius = 30f) to TwirlParams(radius = 90f)),
            "twirl centre y" to (TwirlParams(centerY = 0.3f) to TwirlParams(centerY = 0.7f)),
            "polar variant" to (PolarCoordinatesParams(variant = "rect-to-polar") to
                PolarCoordinatesParams(variant = "polar-to-rect")),
            "expansion strength" to (ExpansionParams(strength = 0.25f) to ExpansionParams(strength = 1f)),
            "kaleidoscope segments" to (KaleidoscopeParams(segments = 3) to KaleidoscopeParams(segments = 8)),
            "kaleidoscope centre x" to (KaleidoscopeParams(centerX = 0.3f) to KaleidoscopeParams(centerX = 0.7f)),
            "crystallize size" to (CrystallizeParams(size = 9) to CrystallizeParams(size = 31)),
            "hex pixelate size" to (HexPixelateParams(size = 7) to HexPixelateParams(size = 27)),
            "triangle pixelate size" to (TrianglePixelateParams(size = 7) to TrianglePixelateParams(size = 27)),
            "pointillize size" to (PointillizeParams(size = 4) to PointillizeParams(size = 16)),
            "pointillize background" to (PointillizeParams(size = 9, background = "#FFFFFF") to
                PointillizeParams(size = 9, background = "#101010")),
        )
        for ((name, pair) in pairs) {
            val a = pattern(96, 96)
            val b = pattern(96, 96)
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
    fun pointillizeLeavesAFlatBackgroundBetweenDots() {
        val g = grid(64, 64)
        for (y in 0 until 64) for (x in 0 until 64) put(g, x, y, 1f, 0f, 0f, 1f)
        Filters.apply("l", g, PointillizeParams(size = 12, background = "#0000FF", strength = 1f))
        var blue = 0
        var red = 0
        for (y in 0 until 64) for (x in 0 until 64) {
            val p = px(g, x, y)
            if (p[2] > 0.5f && p[0] < 0.5f) blue++
            if (p[0] > 0.5f && p[2] < 0.5f) red++
        }
        assertTrue(blue > 0, "the background colour is visible between the dots")
        assertTrue(red > 0, "the dots keep the source colour")
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

    @Test
    fun allGeometricParamsRoundTripThroughTheCodec() {
        val formats = listOf(
            SphereLensParams(radius = 77f, centerX = 0.35f, centerY = 0.65f, strength = 0.8f),
            WaveParams(amplitude = 23f, wavelength = 51f, angleDeg = 35f, strength = 0.7f),
            RippleParams(amplitude = 13f, frequency = 4.5f, centerX = 0.4f, centerY = 0.6f, strength = 0.9f),
            TwirlParams(angleDeg = -330f, radius = 123f, centerX = 0.45f, centerY = 0.55f, strength = 0.75f),
            PolarCoordinatesParams(variant = "polar-to-rect", strength = 0.6f),
            ExpansionParams(strength = 0.65f),
            KaleidoscopeParams(segments = 7, centerX = 0.3f, centerY = 0.7f, strength = 0.85f),
            CrystallizeParams(size = 37, strength = 0.9f),
            HexPixelateParams(size = 43, strength = 0.8f),
            TrianglePixelateParams(size = 39, strength = 0.95f),
            PointillizeParams(size = 19, background = "#334455", strength = 0.7f),
        )
        for (p in formats) {
            val json = FilterCodec.toJson(p)
            assertEquals(p, FilterCodec.fromJson(json), "codec round trip of ${p.kind}")
        }
    }
}
