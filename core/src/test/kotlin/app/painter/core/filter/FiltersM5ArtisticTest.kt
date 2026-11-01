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

/** The M5 artistic and generative filters: determinism, tiling, selection, alpha lock, controls and codec. */
class FiltersM5ArtisticTest {

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

    /** The gradient with punched transparent holes, so the dotted and painted filters see shape edges. */
    private fun wash(w: Int, h: Int, alpha: Float = 1f): TileGrid {
        val g = dense(w, h, alpha)
        punch(g, w / 4, h / 2, (min(w, h) * 0.09f).coerceAtLeast(4f))
        punch(g, w * 3 / 4, h / 3, (min(w, h) * 0.07f).coerceAtLeast(3f))
        punch(g, w / 2, h / 2, (min(w, h) * 0.1f).coerceAtLeast(4f))
        return g
    }

    private fun punch(g: TileGrid, cx: Int, cy: Int, radius: Float) {
        for (y in 0 until g.height) for (x in 0 until g.width) {
            if (hypot(x + 0.5f - cx, y + 0.5f - cy) <= radius) g.setPixel(x, y, 0f, 0f, 0f, 0f)
        }
    }

    private fun params(): List<FilterParams> = listOf(
        PaletteParams(colors = 6, strength = 1f),
        QrCodeParams(text = "https://boundless.paint/m5", size = 6, margin = 3, color = "#000000"),
        StainedGlassParams(cell = 18, borderWidth = 3f, borderColor = "#101010", strength = 1f),
        DotsParams(size = 6, spacing = 10, shape = "hex", color = "#FFFFFF", strength = 1f),
        StampParams(threshold = 0.5f, strength = 1f),
        GlitchParams(amount = 10, sliceHeight = 12, seed = 7L, strength = 1f),
        RetroGameParams(palette = "nes", pixelSize = 4, dither = 0.5f, strength = 1f),
        ChromeParams(smoothness = 4f, strength = 1f),
        SheerParams(strength = 1f),
        ImpressionistParams(strokeSize = 8, strength = 1f),
        SwirlPaintingParams(strength = 1f),
        PopArtParams(dotScale = 0.8f, saturation = 1.6f, strength = 1f),
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
    fun everyFilterIsThreadIndependent() {
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
        val probes = listOf(
            PaletteParams(colors = 6),
            StainedGlassParams(cell = 18, borderWidth = 3f),
            DotsParams(size = 6, spacing = 10, shape = "hex"),
            StampParams(threshold = 0.5f),
            GlitchParams(amount = 10, sliceHeight = 12, seed = 7L),
            RetroGameParams(palette = "nes", pixelSize = 4, dither = 0.5f),
            ChromeParams(smoothness = 4f),
            SheerParams(),
            ImpressionistParams(strokeSize = 8),
            SwirlPaintingParams(),
            PopArtParams(dotScale = 0.8f, saturation = 1.6f),
        )
        for (p in probes) {
            val g = wash(512, 256)
            Filters.apply("l", g, p)
            val boundary = columnDiff(g, 255)
            // A sharp filter has natural edges of the same magnitude elsewhere, so the seam must not exceed
            // the largest ordinary edge in the rest of the image.
            var normalEdge = 0.0
            for (x in 0 until 510) {
                if (x in 240..270) continue
                normalEdge = maxOf(normalEdge, columnDiff(g, x))
            }
            assertTrue(
                boundary <= normalEdge * 1.25 + 0.01,
                "${p.kind} has a tile seam: boundary=$boundary normalEdge=$normalEdge",
            )
        }
    }

    @Test
    fun qrCodeDrawsTheSameModulesAcrossTheTileBoundary() {
        val p = QrCodeParams(text = "https://boundless.paint/m5", size = 6, margin = 3, color = "#000000")
        val g = grid(512, 256)
        Filters.apply("l", g, p)
        val code = QrCode.encode(p.text)
        val total = code.size + p.margin * 2
        val module = min(p.size, min(512, 256) / total).coerceAtLeast(1)
        val side = total * module
        val left = (512 - side) / 2
        val top = (256 - side) / 2
        for (my in 0 until total) for (mx in 0 until total) {
            val dark = mx >= p.margin && my >= p.margin &&
                mx < p.margin + code.size && my < p.margin + code.size &&
                code.dark(mx - p.margin, my - p.margin)
            val expected = if (dark) 0f else 1f
            for (y in 0 until module) for (x in 0 until module) {
                val v = px(g, left + mx * module + x, top + my * module + y)
                assertEquals(1f, v[3], 1e-4f, "QR is not opaque at module $mx,$my")
                for (c in 0..2) {
                    assertEquals(expected, v[c], 1e-4f, "QR module $mx,$my differs at pixel $x,$y")
                }
            }
        }
        // Outside the paper square the generator must not paint.
        assertEquals(0f, px(g, left - 1, top - 1)[3], 1e-4f, "QR painted outside its quiet zone")
    }

    @Test
    fun paletteUsesAtMostTheRequestedColourCount() {
        val g = dense(96, 96)
        Filters.apply("l", g, PaletteParams(colors = 4))
        val colours = HashSet<Int>()
        for (y in 0 until 96) for (x in 0 until 96) {
            val v = px(g, x, y)
            colours.add(
                ((v[0] * 255).roundToInt() shl 16) or
                    ((v[1] * 255).roundToInt() shl 8) or
                    (v[2] * 255).roundToInt(),
            )
        }
        assertTrue(colours.size in 2..4, "palette left ${colours.size} colours instead of at most 4")
    }

    @Test
    fun stampIsOneBit() {
        val g = dense(64, 64)
        Filters.apply("l", g, StampParams(threshold = 0.5f))
        for (y in 0 until 64) for (x in 0 until 64) {
            val v = px(g, x, y)
            val lum = v[0]
            assertTrue(
                abs(lum) < 1e-4f || abs(lum - 1f) < 1e-4f,
                "stamp left a grey pixel at $x,$y: ${v.toList()}",
            )
        }
    }

    @Test
    fun retroGamePalettesLimitTheColourCount() {
        val cases = listOf("nes" to 16, "gb" to 4, "16" to 16)
        for ((name, limit) in cases) {
            val g = dense(96, 96)
            Filters.apply("l", g, RetroGameParams(palette = name, pixelSize = 4, dither = 0.5f))
            val colours = HashSet<Int>()
            for (y in 0 until 96) for (x in 0 until 96) {
                val v = px(g, x, y)
                colours.add(
                    ((v[0] * 255).roundToInt() shl 16) or
                        ((v[1] * 255).roundToInt() shl 8) or
                        (v[2] * 255).roundToInt(),
                )
            }
            assertTrue(colours.size <= limit, "retro palette $name used ${colours.size} colours")
        }
    }

    @Test
    fun dotsShowBothTheDotsAndTheGapColour() {
        val g = grid(64, 64)
        for (y in 0 until 64) for (x in 0 until 64) put(g, x, y, 1f, 0f, 0f, 1f)
        Filters.apply("l", g, DotsParams(size = 6, spacing = 12, shape = "square", color = "#FFFFFF"))
        var red = 0
        var white = 0
        for (y in 0 until 64) for (x in 0 until 64) {
            val v = px(g, x, y)
            if (v[0] > 0.9f && v[1] < 0.1f) red++
            if (v[0] > 0.9f && v[1] > 0.9f && v[2] > 0.9f) white++
        }
        assertTrue(red > 0, "the dots do not keep the source colour")
        assertTrue(white > 0, "the gap colour is not visible")
    }

    @Test
    fun everyNewControlChangesTheResult() {
        val pairs = listOf(
            "palette colours" to (PaletteParams(colors = 2) to PaletteParams(colors = 12)),
            "qr text" to (QrCodeParams(text = "one") to QrCodeParams(text = "two")),
            "qr module size" to (QrCodeParams(size = 2) to QrCodeParams(size = 6)),
            "qr margin" to (QrCodeParams(margin = 0) to QrCodeParams(margin = 6)),
            "qr colour" to (QrCodeParams(color = "#000000") to QrCodeParams(color = "#FF0000")),
            "stained glass cell" to (StainedGlassParams(cell = 10) to StainedGlassParams(cell = 30)),
            "stained glass border width" to (StainedGlassParams(borderWidth = 0f) to
                StainedGlassParams(borderWidth = 6f)),
            "stained glass border colour" to (StainedGlassParams(borderColor = "#000000") to
                StainedGlassParams(borderColor = "#FF0000")),
            "dots size" to (DotsParams(size = 3) to DotsParams(size = 12)),
            "dots spacing" to (DotsParams(spacing = 8) to DotsParams(spacing = 24)),
            "dots shape" to (DotsParams(shape = "hex") to DotsParams(shape = "square")),
            "dots colour" to (DotsParams(color = "#FFFFFF") to DotsParams(color = "#2040FF")),
            "stamp threshold" to (StampParams(threshold = 0.3f) to StampParams(threshold = 0.75f)),
            "glitch amount" to (GlitchParams(amount = 4) to GlitchParams(amount = 30)),
            "glitch slice height" to (GlitchParams(sliceHeight = 6) to GlitchParams(sliceHeight = 40)),
            "glitch seed" to (GlitchParams(seed = 1L) to GlitchParams(seed = 99L)),
            "retro palette" to (RetroGameParams(palette = "nes") to RetroGameParams(palette = "gb")),
            "retro pixel size" to (RetroGameParams(pixelSize = 2) to RetroGameParams(pixelSize = 10)),
            "retro dither" to (RetroGameParams(dither = 0f) to RetroGameParams(dither = 1f)),
            "chrome smoothness" to (ChromeParams(smoothness = 0f) to ChromeParams(smoothness = 12f)),
            "impressionist stroke size" to (ImpressionistParams(strokeSize = 3) to
                ImpressionistParams(strokeSize = 18)),
            "pop art dot scale" to (PopArtParams(dotScale = 0.3f) to PopArtParams(dotScale = 1.1f)),
            "pop art saturation" to (PopArtParams(saturation = 0f) to PopArtParams(saturation = 2f)),
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
                is PaletteParams -> p.copy(strength = 0.25f)
                is QrCodeParams -> p.copy(strength = 0.25f)
                is StainedGlassParams -> p.copy(strength = 0.25f)
                is DotsParams -> p.copy(strength = 0.25f)
                is StampParams -> p.copy(strength = 0.25f)
                is GlitchParams -> p.copy(strength = 0.25f)
                is RetroGameParams -> p.copy(strength = 0.25f)
                is ChromeParams -> p.copy(strength = 0.25f)
                is SheerParams -> p.copy(strength = 0.25f)
                is ImpressionistParams -> p.copy(strength = 0.25f)
                is SwirlPaintingParams -> p.copy(strength = 0.25f)
                is PopArtParams -> p.copy(strength = 0.25f)
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

    @Test
    fun allArtisticParamsRoundTripThroughTheCodec() {
        val formats = listOf(
            PaletteParams(colors = 17, strength = 0.8f),
            QrCodeParams(text = "Painter QR: тест 42", size = 11, margin = 2, color = "#123456", strength = 0.9f),
            StainedGlassParams(cell = 37, borderWidth = 7f, borderColor = "#402010", strength = 0.75f),
            DotsParams(size = 13, spacing = 27, shape = "square", color = "#F0E0C0", strength = 0.85f),
            StampParams(threshold = 0.37f, strength = 0.65f),
            GlitchParams(amount = 41, sliceHeight = 73, seed = 987654321L, strength = 0.8f),
            RetroGameParams(palette = "gb", pixelSize = 11, dither = 0.35f, strength = 0.7f),
            ChromeParams(smoothness = 13f, strength = 0.9f),
            SheerParams(strength = 0.55f),
            ImpressionistParams(strokeSize = 23, strength = 0.95f),
            SwirlPaintingParams(strength = 0.6f),
            PopArtParams(dotScale = 0.45f, saturation = 0.8f, strength = 0.8f),
        )
        for (p in formats) {
            val json = FilterCodec.toJson(p)
            assertEquals(p, FilterCodec.fromJson(json), "codec round trip of ${p.kind}")
        }
    }
}
