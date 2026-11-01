package app.painter.core.filter

import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The M5 blur, light and texture filters: tiling, determinism, selection, alpha lock and codec. */
class FiltersM5Test {

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

    private fun params(cx: Float = 0.5f, cy: Float = 0.5f): List<FilterParams> = listOf(
        SmoothParams(radius = 3f, strength = 1f),
        LensBlurParams(radius = 6f, highlightBoost = 0.7f, bladeAngleDeg = 20f),
        HighPassParams(radius = 5f, strength = 1f),
        ScatterParams(distance = 6f, strength = 1f),
        GrainParams(variant = "mono", size = 2, strength = 1f),
        LocalContrastParams(radius = 5f, strength = 1f),
        BokehParams(size = 9f, strength = 1f),
        LensFlareParams(centerX = cx, centerY = cy, size = 18f, strength = 1f),
        GodRaysParams(centerX = cx, centerY = cy, length = 36f, threshold = 0.4f, strength = 1f),
        CrossFilterParams(threshold = 0.4f, length = 28f, angleDeg = 0f, rays = 4, strength = 1f),
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
    fun spatialFiltersReadAcrossTileBoundaries() {
        val probes = listOf(
            SmoothParams(radius = 10f),
            LensBlurParams(radius = 10f, highlightBoost = 0.5f),
            HighPassParams(radius = 10f),
            LocalContrastParams(radius = 10f),
            BokehParams(size = 10f),
            GodRaysParams(centerX = 0.5f, centerY = 0.5f, length = 40f, threshold = 0.3f),
            CrossFilterParams(threshold = 0.3f, length = 40f, rays = 4),
        )
        for (p in probes) {
            val g = grid(512, 64)
            for (y in 0 until 64) for (x in 0 until 512) put(g, x, y, 0.1f, 0.1f, 0.1f, 1f)
            put(g, 250, 32, 1f, 1f, 1f, 1f)
            put(g, 251, 32, 1f, 1f, 1f, 1f)
            val before = px(g, 260, 32)
            Filters.apply("l", g, p)
            val after = px(g, 260, 32)
            var delta = 0f
            for (c in 0..3) delta += abs(after[c] - before[c])
            assertTrue(delta > 1e-4f, "${p.kind} did not see the dot in the left tile: $delta")
        }
    }

    @Test
    fun everyNewControlChangesTheResult() {
        val pairs = listOf(
            "smooth radius" to (SmoothParams(radius = 2f) to SmoothParams(radius = 12f)),
            "lens blur radius" to (LensBlurParams(radius = 4f) to LensBlurParams(radius = 14f)),
            "lens blur highlight boost" to (LensBlurParams(radius = 8f, highlightBoost = 0f) to
                LensBlurParams(radius = 8f, highlightBoost = 1f)),
            "lens blur blade angle" to (LensBlurParams(radius = 8f, bladeAngleDeg = 0f) to
                LensBlurParams(radius = 8f, bladeAngleDeg = 30f)),
            "high-pass radius" to (HighPassParams(radius = 3f) to HighPassParams(radius = 12f)),
            "scatter distance" to (ScatterParams(distance = 3f) to ScatterParams(distance = 16f)),
            "grain size" to (GrainParams(size = 1) to GrainParams(size = 5)),
            "grain variant" to (GrainParams(variant = "mono") to GrainParams(variant = "rgb")),
            "local contrast radius" to (LocalContrastParams(radius = 3f) to LocalContrastParams(radius = 16f)),
            "bokeh size" to (BokehParams(size = 5f) to BokehParams(size = 24f)),
            "lens flare centre" to (LensFlareParams(centerX = 0.3f, size = 16f) to
                LensFlareParams(centerX = 0.7f, size = 16f)),
            "lens flare size" to (LensFlareParams(size = 12f) to LensFlareParams(size = 36f)),
            "god rays centre" to (GodRaysParams(centerX = 0.3f, length = 30f) to
                GodRaysParams(centerX = 0.7f, length = 30f)),
            "god rays length" to (GodRaysParams(length = 12f) to GodRaysParams(length = 64f)),
            "god rays threshold" to (GodRaysParams(threshold = 0.3f) to GodRaysParams(threshold = 0.85f)),
            "cross threshold" to (CrossFilterParams(threshold = 0.3f) to CrossFilterParams(threshold = 0.85f)),
            "cross length" to (CrossFilterParams(length = 10f) to CrossFilterParams(length = 50f)),
            "cross angle" to (CrossFilterParams(angleDeg = 0f) to CrossFilterParams(angleDeg = 45f)),
            "cross rays" to (CrossFilterParams(rays = 2) to CrossFilterParams(rays = 6)),
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
    fun grainVariantsTouchTheChannelsTheyName() {
        val source = pattern(64, 64, alpha = 0.8f)
        val mono = source.copy()
        val rgb = source.copy()
        val alpha = source.copy()
        Filters.apply("l", mono, GrainParams(variant = "mono", size = 2))
        Filters.apply("l", rgb, GrainParams(variant = "rgb", size = 2))
        Filters.apply("l", alpha, GrainParams(variant = "alpha", size = 2))
        var monoVsRgb = 0f
        var alphaChanged = 0f
        for (y in 0 until 64) for (x in 0 until 64) {
            val before = px(source, x, y)
            val m = px(mono, x, y)
            val r = px(rgb, x, y)
            val a = px(alpha, x, y)
            assertEquals(before[3], m[3], 1e-4f, "mono grain keeps alpha")
            monoVsRgb += abs(m[0] - r[0]) + abs(m[1] - r[1]) + abs(m[2] - r[2])
            alphaChanged += abs(a[3] - before[3])
        }
        assertTrue(monoVsRgb > 0.1f, "rgb grain uses separate channel noise")
        assertTrue(alphaChanged > 0.1f, "alpha grain changes the alpha channel")
    }

    @Test
    fun outputsAreContinuousAtTheTileBoundary() {
        for (p in params(cx = 0.35f, cy = 0.4f)) {
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
    fun allM5ParamsRoundTripThroughTheCodec() {
        val formats = listOf(
            SmoothParams(radius = 7f, strength = 0.8f),
            LensBlurParams(radius = 17f, highlightBoost = 0.6f, bladeAngleDeg = 35f, strength = 0.9f),
            HighPassParams(radius = 9f, strength = 0.7f),
            ScatterParams(distance = 21f, strength = 0.8f),
            GrainParams(variant = "rgb", size = 3, strength = 0.6f),
            LocalContrastParams(radius = 14f, strength = 0.75f),
            BokehParams(size = 23f, strength = 0.85f),
            LensFlareParams(centerX = 0.35f, centerY = 0.65f, size = 42f, strength = 0.9f),
            GodRaysParams(centerX = 0.4f, centerY = 0.55f, length = 55f, threshold = 0.45f, strength = 0.8f),
            CrossFilterParams(threshold = 0.5f, length = 31f, angleDeg = 30f, rays = 6, strength = 0.7f),
        )
        for (p in formats) {
            val json = FilterCodec.toJson(p)
            assertEquals(p, FilterCodec.fromJson(json), "codec round trip of ${p.kind}")
        }
    }
}
