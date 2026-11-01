package app.painter.core.filter

import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The M5 stylization and material filters: tiling, determinism, selection, alpha lock and codec. */
class FiltersM5StyleTest {

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

    /** The dense pattern with punched transparent holes, so the material filters see real shape edges. */
    private fun wash(w: Int, h: Int, alpha: Float = 1f): TileGrid {
        val g = dense(w, h, alpha)
        punch(g, w / 4, h / 2, (min(w, h) * 0.09f).coerceAtLeast(4f))
        punch(g, w * 3 / 4, h / 3, (min(w, h) * 0.07f).coerceAtLeast(3f))
        // A hole in the middle crosses the x = 255 tile edge of a 512-wide grid and exercises the
        // canvas-anchored phase and the alpha support of every seam-free filter right at the boundary.
        punch(g, w / 2, h / 2, (min(w, h) * 0.1f).coerceAtLeast(4f))
        return g
    }

    private fun punch(g: TileGrid, cx: Int, cy: Int, radius: Float) {
        for (y in 0 until g.height) for (x in 0 until g.width) {
            if (hypot(x + 0.5f - cx, y + 0.5f - cy) <= radius) g.setPixel(x, y, 0f, 0f, 0f, 0f)
        }
    }

    private fun params(): List<FilterParams> = listOf(
        GloomParams(threshold = 0.55f, radius = 10f, strength = 1f),
        SketchParams(thickness = 3, amount = 0.7f, sensitivity = 0.6f, strength = 1f),
        TraceParams(strength = 0.8f),
        BevelParams(variant = "both", depth = 70f, size = 6, angleDeg = 135f, strength = 1f),
        EmbossParams(height = 60f, angleDeg = 135f, strength = 1f),
        ReliefParams(height = 60f, angleDeg = 135f, soften = 3f, strength = 1f),
        SatinParams(color = "#FFFFFF", angleDeg = 30f, distance = 16, size = 6, strength = 1f),
        WetEdgeParams(width = 8, strength = 1f),
        WaterdropParams(height = 60f, gloss = 0.7f, strength = 1f),
        ExtrudeParams(angleDeg = 45f, length = 20, color = "#808080", shading = 0.5f, strength = 1f),
        CloudsParams(scale = 32f, detail = 4, seed = 7L, colorA = "#FFFFFF", colorB = "#000000", strength = 1f),
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
    fun edgeFiltersReadAcrossTheTileBoundary() {
        val probes = listOf(
            GloomParams(radius = 10f),
            SketchParams(thickness = 3),
            TraceParams(),
            EmbossParams(height = 50f),
            ReliefParams(soften = 2f),
        )
        for (p in probes) {
            val base = dense(512, 64)
            val marked = base.copy()
            // The mark sits in the left tile; the spatial support of every probe reaches past x = 255.
            for (y in 28 until 36) for (x in 252 until 256) put(marked, x, y, 0f, 0f, 0f, 1f)
            Filters.apply("l", base, p)
            Filters.apply("l", marked, p)
            var diff = 0f
            for (y in 20 until 44) for (x in 256 until 272) {
                val a = px(base, x, y)
                val b = px(marked, x, y)
                for (c in 0..3) diff += abs(a[c] - b[c])
            }
            assertTrue(diff > 0.05f, "${p.kind} ignored the left tile: $diff")
        }
    }

    @Test
    fun alphaFiltersReadAlphaAcrossTheTileBoundary() {
        val probes = listOf(
            BevelParams(variant = "inner", size = 6),
            SatinParams(distance = 10, size = 4),
            WetEdgeParams(width = 8),
        )
        for (p in probes) {
            fun run(extra: Boolean): TileGrid {
                val g = grid(512, 64)
                for (y in 16 until 48) for (x in 0 until 256) put(g, x, y, 0.8f, 0.3f, 0.2f, 1f)
                if (extra) for (y in 16 until 48) for (x in 256 until 260) put(g, x, y, 0.8f, 0.3f, 0.2f, 1f)
                Filters.apply("l", g, p)
                return g
            }
            val cut = px(run(false), 255, 32)
            val joined = px(run(true), 255, 32)
            var diff = 0f
            for (c in 0..3) diff += abs(cut[c] - joined[c])
            assertTrue(diff > 0.01f, "${p.kind} ignored the alpha of the right tile: $diff")
        }
    }

    @Test
    fun extrudePaintsTheSideIntoTheNextTile() {
        val g = grid(512, 64)
        for (y in 24 until 40) for (x in 236 until 256) put(g, x, y, 1f, 0f, 0f, 1f)
        Filters.apply("l", g, ExtrudeParams(angleDeg = 0f, length = 20, color = "#808080", shading = 0.5f))
        var painted = 0
        for (y in 24 until 40) for (x in 256 until 276) if (px(g, x, y)[3] > 0.5f) painted++
        assertTrue(painted > 100, "the extruded side does not reach the next tile: $painted")
    }

    @Test
    fun wetEdgeDarkensTheRimAndLeavesTheMiddle() {
        val g = grid(64, 64)
        for (y in 0 until 64) for (x in 0 until 64) {
            if (hypot(x + 0.5f - 32f, y + 0.5f - 32f) <= 28f) put(g, x, y, 0.8f, 0.3f, 0.2f, 1f)
        }
        val middle = px(g, 32, 32)
        val rim = px(g, 32, 6)
        Filters.apply("l", g, WetEdgeParams(width = 8))
        assertContentEquals(middle, px(g, 32, 32), "the wet edge reached the middle of the wash")
        assertTrue(px(g, 32, 4)[0] < rim[0] - 0.05f, "the rim is not darker than before")
    }

    @Test
    fun outerBevelGrowsTheShapeAndInnerBevelDoesNot() {
        fun run(variant: String): TileGrid {
            val g = grid(96, 96)
            for (y in 36 until 60) for (x in 36 until 60) put(g, x, y, 0.8f, 0.3f, 0.2f, 1f)
            Filters.apply(
                "l", g,
                BevelParams(variant = variant, depth = 80f, size = 6, angleDeg = 135f),
            )
            return g
        }
        assertTrue(px(run("outer"), 32, 48)[3] > 0.01f, "the outer bevel does not paint outside the shape")
        assertEquals(0f, px(run("inner"), 32, 48)[3], 1e-6f, "the inner bevel grew the shape")
    }

    @Test
    fun cloudsFillAnEmptyLayerWithBothColours() {
        val g = grid(128, 128)
        Filters.apply(
            "l", g,
            CloudsParams(scale = 24f, detail = 4, seed = 3L, colorA = "#FFFFFF", colorB = "#101010"),
        )
        var opaque = 0
        var light = 0
        var dark = 0
        for (y in 0 until 128) for (x in 0 until 128) {
            val p = px(g, x, y)
            if (p[3] >= 0.999f) opaque++
            if (p[0] > 0.7f) light++
            if (p[0] < 0.3f) dark++
        }
        assertEquals(128 * 128, opaque, "the clouds generator left transparent gaps")
        assertTrue(light > 0, "the clouds have no light patches")
        assertTrue(dark > 0, "the clouds have no dark gaps")
    }

    @Test
    fun outputsAreContinuousAtTheTileBoundary() {
        for (p in params()) {
            val g = wash(512, 256)
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
    fun everyNewControlChangesTheResult() {
        val pairs = listOf(
            "gloom threshold" to (GloomParams(threshold = 0.3f) to GloomParams(threshold = 0.8f)),
            "gloom radius" to (GloomParams(radius = 3f) to GloomParams(radius = 14f)),
            "sketch thickness" to (SketchParams(thickness = 1) to SketchParams(thickness = 6)),
            "sketch amount" to (SketchParams(amount = 0.2f) to SketchParams(amount = 1f)),
            "sketch sensitivity" to (SketchParams(sensitivity = 0.1f) to SketchParams(sensitivity = 0.9f)),
            "trace strength" to (TraceParams(strength = 0.2f) to TraceParams(strength = 1f)),
            "bevel variant" to (BevelParams(variant = "inner") to BevelParams(variant = "outer")),
            "bevel depth" to (BevelParams(depth = 20f) to BevelParams(depth = 90f)),
            "bevel size" to (BevelParams(size = 3) to BevelParams(size = 12)),
            "bevel angle" to (BevelParams(angleDeg = 45f) to BevelParams(angleDeg = 225f)),
            "bevel highlight" to (BevelParams(highlight = "#FFFFFF") to BevelParams(highlight = "#FF8000")),
            "bevel shadow" to (BevelParams(shadow = "#000000") to BevelParams(shadow = "#204080")),
            "emboss height" to (EmbossParams(height = 10f) to EmbossParams(height = 90f)),
            "emboss angle" to (EmbossParams(angleDeg = 30f) to EmbossParams(angleDeg = 210f)),
            "relief height" to (ReliefParams(height = 10f) to ReliefParams(height = 90f)),
            "relief angle" to (ReliefParams(angleDeg = 30f) to ReliefParams(angleDeg = 210f)),
            "relief soften" to (ReliefParams(soften = 0f) to ReliefParams(soften = 9f)),
            "satin color" to (SatinParams(color = "#FFFFFF") to SatinParams(color = "#FF8000")),
            "satin angle" to (SatinParams(angleDeg = 20f) to SatinParams(angleDeg = 110f)),
            "satin distance" to (SatinParams(distance = 8) to SatinParams(distance = 28)),
            "satin size" to (SatinParams(size = 2) to SatinParams(size = 14)),
            "wet edge width" to (WetEdgeParams(width = 3) to WetEdgeParams(width = 16)),
            "waterdrop height" to (WaterdropParams(height = 10f) to WaterdropParams(height = 90f)),
            "waterdrop gloss" to (WaterdropParams(gloss = 0f) to WaterdropParams(gloss = 1f)),
            "extrude angle" to (ExtrudeParams(angleDeg = 20f) to ExtrudeParams(angleDeg = 120f)),
            "extrude length" to (ExtrudeParams(length = 8) to ExtrudeParams(length = 40)),
            "extrude color" to (ExtrudeParams(color = "#808080") to ExtrudeParams(color = "#FF8000")),
            "extrude shading" to (ExtrudeParams(shading = 0f) to ExtrudeParams(shading = 1f)),
            "clouds scale" to (CloudsParams(scale = 8f) to CloudsParams(scale = 96f)),
            "clouds detail" to (CloudsParams(detail = 1) to CloudsParams(detail = 7)),
            "clouds seed" to (CloudsParams(seed = 1L) to CloudsParams(seed = 99L)),
            "clouds first colour" to (CloudsParams(colorA = "#FFFFFF") to CloudsParams(colorA = "#FF8000")),
            "clouds second colour" to (CloudsParams(colorB = "#000000") to CloudsParams(colorB = "#204080")),
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
    fun allStylizationParamsRoundTripThroughTheCodec() {
        val formats = listOf(
            GloomParams(threshold = 0.35f, radius = 21f, strength = 0.8f),
            SketchParams(thickness = 5, amount = 0.4f, sensitivity = 0.75f, strength = 0.9f),
            TraceParams(strength = 0.55f),
            BevelParams(
                variant = "outer", depth = 42f, size = 17, angleDeg = 215f,
                highlight = "#FDE8C0", shadow = "#103050", strength = 0.85f,
            ),
            EmbossParams(height = 73f, angleDeg = 305f, strength = 0.7f),
            ReliefParams(height = 31f, angleDeg = 95f, soften = 7f, strength = 0.65f),
            SatinParams(color = "#C080FF", angleDeg = 140f, distance = 33, size = 11, strength = 0.75f),
            WetEdgeParams(width = 27, strength = 0.95f),
            WaterdropParams(height = 84f, gloss = 0.35f, strength = 0.8f),
            ExtrudeParams(angleDeg = 300f, length = 47, color = "#40A0C0", shading = 0.8f, strength = 0.9f),
            CloudsParams(scale = 91f, detail = 6, seed = 1234567L, colorA = "#F0F0FF", colorB = "#303040", strength = 0.7f),
        )
        for (p in formats) {
            val json = FilterCodec.toJson(p)
            assertEquals(p, FilterCodec.fromJson(json), "codec round trip of ${p.kind}")
        }
    }
}
