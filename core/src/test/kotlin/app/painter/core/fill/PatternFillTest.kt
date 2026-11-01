package app.painter.core.fill

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.history.History
import app.painter.core.history.TilePatchCommand
import app.painter.core.history.fillRect
import app.painter.core.history.gridHash
import app.painter.core.history.pixel
import app.painter.core.raster.TileGrid
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternFillTest {
    private val kinds = PatternKind.entries
    private val newKinds = listOf(
        PatternKind.ZIGZAG,
        PatternKind.WAVES,
        PatternKind.TRIANGLES,
        PatternKind.STARS,
        PatternKind.HEXAGONS,
        PatternKind.WOOD_GRAIN,
    )
    private val background = Rgba(1f, 0f, 0f)
    private val ink = Rgba(0f, 0f, 1f)
    private val clear = Rgba.TRANSPARENT

    @Test
    fun coverage_wrapsAtTileEdgesForEveryPattern() {
        val samples = floatArrayOf(-0.31f, 0f, 0.03f, 0.25f, 0.49f, 0.5f, 0.77f, 0.999f)
        for (kind in kinds) {
            for (u in samples) for (v in samples) {
                val base = PatternGenerator.coverage(kind, u, v, 0.01f)
                assertEquals(base, PatternGenerator.coverage(kind, u + 1f, v, 0.01f), 2e-4f, "$kind wraps u at $u,$v")
                assertEquals(base, PatternGenerator.coverage(kind, u, v + 1f, 0.01f), 2e-4f, "$kind wraps v at $u,$v")
                assertEquals(
                    base,
                    PatternGenerator.coverage(kind, u - 1f, v - 1f, 0.01f),
                    2e-4f,
                    "$kind wraps back at $u,$v",
                )
            }
        }
    }

    @Test
    fun sampling_isDeterministicWithEveryControl() {
        for (kind in kinds) {
            val spec = PatternSpec(kind, 48f, 17f, 37.5f, -81.25f, 0.65f, invert = true)
            assertContentEquals(mask(spec), mask(spec), "$kind is not deterministic")
        }
    }

    @Test
    fun fillIsDeterministicWithEveryControl() {
        val spec = PatternSpec(PatternKind.DIAGONAL_STRIPES, 48f, 23f, 37.5f, -81.25f, 0.7f, invert = true)
        assertEquals(gridHash(fill(spec, background, ink, 0.8f)), gridHash(fill(spec, background, ink, 0.8f)))
    }

    @Test
    fun samplingClampsOffsetsAndContrast() {
        val sampler = PatternSampler(PatternSpec(PatternKind.CHECKS, 32f, 0f, 900f, -900f, 4f, invert = true))
        assertEquals(PatternSpec.MAX_OFFSET, sampler.offsetX)
        assertEquals(-PatternSpec.MAX_OFFSET, sampler.offsetY)
        assertEquals(1f, sampler.contrast)
        assertTrue(sampler.invert)
        val bounded = PatternSampler(PatternSpec(PatternKind.CHECKS, 32f, 0f, 256f, -256f, 1f, invert = true))
        assertEquals(bounded.at(10f, 20f), sampler.at(10f, 20f), 0f)
    }

    @Test
    fun rotationAndScaleChangeTheMask() {
        for (kind in kinds) {
            val base = mask(PatternSpec(kind, 48f, 0f))
            val scaled = mask(PatternSpec(kind, 24f, 0f))
            val rotated = mask(PatternSpec(kind, 48f, 37f))
            assertTrue(changed(base, scaled) > 10, "$kind ignores the tile size")
            assertTrue(changed(base, rotated) > 10, "$kind ignores the rotation")
        }
    }

    @Test
    fun rotatedSamplingRepeatsAlongTheRotatedTile() {
        val tile = 40f
        val rotation = 33f
        val rad = rotation * PI.toFloat() / 180f
        val dx = tile * cos(rad)
        val dy = tile * sin(rad)
        val ex = -tile * sin(rad)
        val ey = tile * cos(rad)
        val points = listOf(3.2f to 1.7f, 17.5f to 9.25f, 30.1f to 33.3f, 41.3f to 8.9f)
        val phases = listOf(0f to 0f, 37.5f to -81.25f, -PatternSpec.MAX_OFFSET to PatternSpec.MAX_OFFSET)
        for (kind in kinds) {
            for ((offsetX, offsetY) in phases) {
                val sampler = PatternSampler(PatternSpec(kind, tile, rotation, offsetX, offsetY, contrast = 0.35f))
                for ((x, y) in points) {
                    val base = sampler.at(x, y)
                    assertEquals(base, sampler.at(x + dx, y + dy), 1e-4f, "$kind does not wrap along u at $x,$y")
                    assertEquals(base, sampler.at(x + ex, y + ey), 1e-4f, "$kind does not wrap along v at $x,$y")
                }
            }
        }
    }

    @Test
    fun offsetShiftsThePatternInDocumentSpace() {
        val offsetX = 37.5f
        val offsetY = -81.25f
        val points = listOf(3.2f to 1.7f, 17.5f to 9.25f, 30.1f to 33.3f, 41.3f to 8.9f)
        for (kind in kinds) {
            val base = PatternSampler(PatternSpec(kind, 40f, 33f))
            val shifted = PatternSampler(PatternSpec(kind, 40f, 33f, offsetX, offsetY))
            for ((x, y) in points) {
                assertEquals(
                    base.at(x, y),
                    shifted.at(x + offsetX, y + offsetY),
                    2e-4f,
                    "$kind ignores the phase offset at $x,$y",
                )
            }
        }
    }

    @Test
    fun contrastPushesCoverageAwayFromMidpoint() {
        assertEquals(0.2f, PatternGenerator.contrastCurve(0.2f, 0f), 1e-6f)
        assertEquals(0.5f, PatternGenerator.contrastCurve(0.5f, 1f), 1e-6f)
        assertTrue(PatternGenerator.contrastCurve(0.2f, 0.5f) < 0.2f)
        assertTrue(PatternGenerator.contrastCurve(0.8f, 0.5f) > 0.8f)
        assertEquals(0f, PatternGenerator.contrastCurve(0.4f, 1f), 1e-6f)
        assertEquals(1f, PatternGenerator.contrastCurve(0.6f, 1f), 1e-6f)
    }

    @Test
    fun contrastAndInvertChangeTheSampledCoverage() {
        val points = 40
        for (kind in kinds) {
            val base = PatternSampler(PatternSpec(kind, 64f, 11f))
            val contrasted = PatternSampler(PatternSpec(kind, 64f, 11f, contrast = 0.65f))
            val inverted = PatternSampler(PatternSpec(kind, 64f, 11f, invert = true))
            var changedByContrast = 0
            for (y in 0 until points) for (x in 0 until points) {
                val px = x + 0.5f
                val py = y + 0.5f
                val c = base.at(px, py)
                assertEquals(1f - c, inverted.at(px, py), 1e-6f, "$kind does not invert at $x,$y")
                val d = contrasted.at(px, py)
                if (abs(d - c) > 0.02f) changedByContrast++
                if (c > 0.6f) assertTrue(d >= c - 1e-6f, "$kind lowers high coverage at $x,$y")
                if (c < 0.4f) assertTrue(d <= c + 1e-6f, "$kind raises low coverage at $x,$y")
            }
            assertTrue(changedByContrast > 10, "$kind ignores the contrast")
        }
    }

    @Test
    fun contrastMovesPartialCoverageTowardsTheNearerColour() {
        val sampler = PatternSampler(PatternSpec(PatternKind.DOTS, 32f))
        val low = firstPoint(sampler) { it in 0.15f..0.45f }
        val high = firstPoint(sampler) { it in 0.55f..0.85f }
        val natural = fill(PatternSpec(PatternKind.DOTS, 32f), background, ink, 1f)
        val contrasted = fill(PatternSpec(PatternKind.DOTS, 32f, contrast = 0.8f), background, ink, 1f)

        val lowNatural = pixel(natural, low.first, low.second)
        val lowContrasted = pixel(contrasted, low.first, low.second)
        assertTrue(lowContrasted[0] > lowNatural[0] + 0.1f, "contrast did not push low coverage to the background")
        assertTrue(lowContrasted[2] < lowNatural[2] - 0.1f, "contrast did not push low coverage away from the ink")

        val highNatural = pixel(natural, high.first, high.second)
        val highContrasted = pixel(contrasted, high.first, high.second)
        assertTrue(highContrasted[2] > highNatural[2] + 0.1f, "contrast did not push high coverage to the ink")
        assertTrue(
            highContrasted[0] < highNatural[0] - 0.1f,
            "contrast did not push high coverage away from the background",
        )
    }

    @Test
    fun patternBlendsBothColoursAndInvertSwapsThem() {
        val natural = fill(PatternSpec(PatternKind.CHECKS, 32f), background, ink, 1f)
        val inverted = fill(PatternSpec(PatternKind.CHECKS, 32f, invert = true), background, ink, 1f)
        assertColor(ink, pixel(natural, 8, 8), "natural ink cell")
        assertColor(background, pixel(natural, 24, 8), "natural background cell")
        assertColor(background, pixel(inverted, 8, 8), "inverted ink cell")
        assertColor(ink, pixel(inverted, 24, 8), "inverted background cell")
    }

    @Test
    fun opacityAppliesToTheBackgroundColourToo() {
        val grid = fill(PatternSpec(PatternKind.CHECKS, 32f), background, ink, 0.5f)
        val gap = pixel(grid, 24, 8)
        assertEquals(0.5f, gap[0], 1e-3f, "background red at half opacity")
        assertEquals(0f, gap[1], 1e-3f)
        assertEquals(0f, gap[2], 1e-3f)
        assertEquals(0.5f, gap[3], 1e-3f)
        val dot = pixel(grid, 8, 8)
        assertEquals(0f, dot[0], 1e-3f)
        assertEquals(0.5f, dot[2], 1e-3f, "ink blue at half opacity")
        assertEquals(0.5f, dot[3], 1e-3f)
    }

    @Test
    fun selectionClipsTheFillWithEveryControl() {
        val doc = Document.create(300, 300)
        val layer = doc.activeRaster!!
        val mask = doc.selection.mask
        for (y in 0 until 50) for (x in 0 until 50) mask.getOrCreate(0, 0).data[(y shl 8) + x] = -1
        val spec = PatternSpec(PatternKind.CHECKS, 32f, 11f, 17f, -9f, 0.3f, invert = true)
        assertNotNull(PatternFill.apply(doc, layer.id, spec, background, ink, 1f))
        var inside = false
        for (y in 0 until 100) for (x in 0 until 100) {
            val a = pixel(layer.pixels, x, y)[3]
            if (x >= 50 || y >= 50) assertEquals(0f, a, 1e-6f, "outside the selection at $x,$y")
            else if (a > 0f) inside = true
        }
        assertTrue(inside)
        assertEquals(0f, pixel(layer.pixels, 60, 60)[3], 1e-6f)
        assertEquals(0f, pixel(layer.pixels, 260, 10)[3], 1e-6f)
    }

    @Test
    fun alphaLockKeepsTheOriginalAlpha() {
        val doc = Document.create(64, 64)
        val layer = doc.activeRaster!!
        fillRect(layer.pixels, 0, 0, 20, 20, 0f, 0f, 1f, 0.5f)
        layer.alphaLock = true
        val spec = PatternSpec(PatternKind.CHECKS, 16f, 7f, 13f, -5f, 0.4f, invert = true)
        assertNotNull(PatternFill.apply(doc, layer.id, spec, background, ink, 1f))
        val inside = pixel(layer.pixels, 5, 5)
        assertEquals(0.5f, inside[3], 1e-3f)
        assertTrue(inside[0] + inside[2] > 0.1f, "the alpha-locked pixel did not take the pattern colours")
        assertEquals(0f, pixel(layer.pixels, 40, 40)[3], 1e-6f)
    }

    @Test
    fun lockedLayerIsLeftAlone() {
        val doc = Document.create(64, 64)
        val layer = doc.activeRaster!!
        layer.locked = true
        val spec = PatternSpec(PatternKind.DOTS, 32f, 0f, 21f, -3f, 0.5f, invert = true)
        assertNull(PatternFill.apply(doc, layer.id, spec, background, ink, 1f))
        assertTrue(layer.pixels.keys().isEmpty())
    }

    @Test
    fun fullLayerFillHonoursOpacity() {
        val doc = Document.create(300, 300)
        val layer = doc.activeRaster!!
        val spec = PatternSpec(PatternKind.CHECKS, 32f, 0f, 9f, -7f, 0.2f, invert = true)
        val patch = PatternFill.apply(doc, layer.id, spec, clear, ink, 0.5f)
        assertNotNull(patch)
        assertEquals(4, patch.after.size)
        assertEquals(0.5f, pixel(layer.pixels, 8, 8)[3], 1e-3f)
        assertEquals(0f, pixel(layer.pixels, 24, 8)[3], 1e-6f)
    }

    @Test
    fun undoRedoRestoresThePreviousPixels() {
        val doc = Document.create(320, 320)
        val layer = doc.activeRaster!!
        fillRect(layer.pixels, 10, 10, 40, 40, 0.2f, 0.4f, 0.6f, 1f)
        val before = pixel(layer.pixels, 20, 20)
        val spec = PatternSpec(PatternKind.BRICK, 32f, 12f, 7f, -5f, 0.2f)
        val patch = PatternFill.apply(doc, layer.id, spec, background, ink, 1f)
        assertNotNull(patch)
        val after = pixel(layer.pixels, 20, 20)
        assertFalse(same(before, after), "the pattern did not cover the painted square")

        val history = History()
        history.push(TilePatchCommand(patch, "Pattern fill"))
        assertEquals(1, history.undoSize)
        assertTrue(history.undo(doc))
        assertTrue(same(before, pixel(layer.pixels, 20, 20)))
        assertEquals(0f, pixel(layer.pixels, 200, 200)[3], 1e-6f)

        assertTrue(history.redo(doc))
        assertTrue(same(after, pixel(layer.pixels, 20, 20)))
        assertEquals(1, history.undoSize)
    }

    @Test
    fun newPatternsWrapAtEveryTileLatticePoint() {
        val samples = floatArrayOf(
            -2.75f, -1.4f, -0.5f, -0.001f, 0f, 0.001f, 0.25f, 0.5f, 0.75f, 0.999f, 1f, 1.5f, 2.25f,
        )
        val shifts = listOf(1 to 0, 0 to 1, -1 to -1, 2 to -3)
        for (kind in newKinds) {
            for (u in samples) for (v in samples) {
                val base = PatternGenerator.coverage(kind, u, v, 0.01f)
                for ((shiftU, shiftV) in shifts) {
                    assertEquals(
                        base,
                        PatternGenerator.coverage(kind, u + shiftU, v + shiftV, 0.01f),
                        2e-4f,
                        "$kind does not wrap by $shiftU,$shiftV at $u,$v",
                    )
                }
            }
        }
    }

    @Test
    fun newPatternsAreDeterministicWithEveryControl() {
        for (kind in newKinds) {
            val spec = PatternSpec(kind, 37f, 61f, 123.5f, -44.25f, 0.55f, invert = true)
            assertContentEquals(mask(spec), mask(spec), "$kind is not deterministic")
            assertEquals(gridHash(fill(spec, background, ink, 0.8f)), gridHash(fill(spec, background, ink, 0.8f)))
        }
    }

    @Test
    fun newPatternsWrapAlongTheRotatedAndShiftedTile() {
        val tile = 40f
        val points = listOf(3.2f to 1.7f, 17.5f to 9.25f, 30.1f to 33.3f, 41.3f to 8.9f)
        val rotations = floatArrayOf(0f, 17f, 45f, 90f, 137f, 270f)
        val phases = listOf(0f to 0f, 37.5f to -81.25f, -PatternSpec.MAX_OFFSET to PatternSpec.MAX_OFFSET)
        for (kind in newKinds) {
            for (rotation in rotations) {
                val rad = rotation * PI.toFloat() / 180f
                val dx = tile * cos(rad)
                val dy = tile * sin(rad)
                val ex = -tile * sin(rad)
                val ey = tile * cos(rad)
                for ((offsetX, offsetY) in phases) {
                    val sampler = PatternSampler(PatternSpec(kind, tile, rotation, offsetX, offsetY, contrast = 0.35f))
                    for ((x, y) in points) {
                        val base = sampler.at(x, y)
                        assertEquals(base, sampler.at(x + dx, y + dy), 1e-3f, "$kind does not wrap u at $rotation°")
                        assertEquals(base, sampler.at(x + ex, y + ey), 1e-3f, "$kind does not wrap v at $rotation°")
                    }
                }
            }
        }
    }

    @Test
    fun newPatternsBlendBothColoursAndInvertExactly() {
        for (kind in newKinds) {
            val spec = PatternSpec(kind, 64f, 23f, 11f, -7f)
            val natural = fill(spec, background, ink, 1f)
            val inverted = fill(spec.copy(invert = true), background, ink, 1f)
            var partial = 0
            for (y in 0 until 64) for (x in 0 until 64) {
                val a = pixel(natural, x, y)
                val b = pixel(inverted, x, y)
                assertEquals(background.r + ink.r, a[0] + b[0], 1e-3f, "$kind red is not complementary at $x,$y")
                assertEquals(background.g + ink.g, a[1] + b[1], 1e-3f, "$kind green is not complementary at $x,$y")
                assertEquals(background.b + ink.b, a[2] + b[2], 1e-3f, "$kind blue is not complementary at $x,$y")
                assertEquals(background.a + ink.a, a[3] + b[3], 1e-3f, "$kind alpha is not complementary at $x,$y")
                if (a[0] > 0.05f && a[0] < 0.95f) partial++
            }
            assertTrue(partial > 20, "$kind has no partially covered pixels")
        }
    }

    @Test
    fun newPatternsDrawDistinctVisibleShapes() {
        val allMasks = kinds.associateWith { tileMask(it) }
        for (kind in newKinds) {
            val mask = allMasks.getValue(kind)
            assertTrue(mask.count { it > 0.85f } > 100, "$kind has no ink")
            assertTrue(mask.count { it < 0.4f } > 100, "$kind has no background")
        }
        for (first in kinds) for (second in kinds) if (first < second) {
            assertTrue(
                changed(allMasks.getValue(first), allMasks.getValue(second)) > 50,
                "$first and $second look the same",
            )
        }

        val zigzag = allMasks.getValue(PatternKind.ZIGZAG)
        for (x in 0 until 64) {
            assertTrue((0 until 64).any { y -> zigzag[y * 64 + x] > 0.85f }, "zigzag misses column $x")
        }
        assertTrue(abs(peakRow(zigzag, 2) - peakRow(zigzag, 32)) > 15, "zigzag does not fold")

        val waves = allMasks.getValue(PatternKind.WAVES)
        for (x in 0 until 64) {
            assertTrue((0 until 64).any { y -> waves[y * 64 + x] > 0.85f }, "waves miss column $x")
            assertEquals(2, bandCount(waves, x, column = true), "waves has the wrong bands in column $x")
        }

        val triangles = allMasks.getValue(PatternKind.TRIANGLES)
        for (cellY in 0..1) for (cellX in 0..1) {
            val x0 = cellX * 32
            val y0 = cellY * 32
            val top = regionCount(triangles, x0, y0, x0 + 31, y0 + 15)
            val bottom = regionCount(triangles, x0, y0 + 16, x0 + 31, y0 + 31)
            assertTrue(top > 0 && bottom > 0, "triangles leaves cell $cellX,$cellY empty")
            if (((cellX + cellY) and 1) == 0) {
                assertTrue(bottom > top + 20, "up triangle $cellX,$cellY has no wide base")
            } else {
                assertTrue(top > bottom + 20, "down triangle $cellX,$cellY has no wide base")
            }
        }

        val starSampler = PatternSampler(PatternSpec(PatternKind.STARS, 64f))
        assertTrue(starSampler.at(32f, 32f) > 0.95f, "star centre is empty")
        for (tip in 0 until 5) {
            val angle = -PI.toFloat() / 2f + tip * 2f * PI.toFloat() / 5f
            val tipX = 32f + 0.36f * 64f * cos(angle)
            val tipY = 32f + 0.36f * 64f * sin(angle)
            assertTrue(starSampler.at(tipX, tipY) > 0.9f, "star tip $tip is missing")
            val valley = angle + PI.toFloat() / 5f
            val valleyX = 32f + 0.28f * 64f * cos(valley)
            val valleyY = 32f + 0.28f * 64f * sin(valley)
            assertTrue(starSampler.at(valleyX, valleyY) < 0.1f, "star valley $tip is filled")
        }

        val hexSampler = PatternSampler(PatternSpec(PatternKind.HEXAGONS, 64f))
        for (row in -1..2) {
            val shift = if ((row and 1) == 0) 0f else 0.25f
            for (column in -1..2) {
                val x = (column * 0.5f + shift) * 64f
                val y = row * 0.5f * 64f
                val nextX = ((column + 1) * 0.5f + shift) * 64f
                assertTrue(hexSampler.at(x, y) > 0.9f, "hexagon centre $column,$row is empty")
                assertTrue(hexSampler.at((x + nextX) / 2f, y) < 0.1f, "hexagon gap $column,$row is filled")
            }
        }

        val wood = allMasks.getValue(PatternKind.WOOD_GRAIN)
        for (y in 0 until 64) {
            assertTrue((0 until 64).any { x -> wood[y * 64 + x] > 0.85f }, "wood has no grain line in row $y")
            assertTrue(bandCount(wood, y, column = false) >= 4, "wood has too few grain lines in row $y")
        }
    }

    private fun tileMask(kind: PatternKind): FloatArray {
        val sampler = PatternSampler(PatternSpec(kind, 64f))
        return FloatArray(64 * 64) { i -> sampler.at((i % 64) + 0.5f, (i / 64) + 0.5f) }
    }

    /** Row of the strongest coverage in one column of a 64 × 64 tile mask. */
    private fun peakRow(mask: FloatArray, x: Int): Int {
        var best = 0
        for (y in 1 until 64) if (mask[y * 64 + x] > mask[best * 64 + x]) best = y
        return best
    }

    /** Number of separate high-coverage runs along one column or row of a 64 × 64 tile mask. */
    private fun bandCount(mask: FloatArray, index: Int, column: Boolean, threshold: Float = 0.6f): Int {
        var count = 0
        var inside = false
        for (i in 0 until 64) {
            val value = if (column) mask[i * 64 + index] else mask[index * 64 + i]
            if (value > threshold) {
                if (!inside) count++
                inside = true
            } else {
                inside = false
            }
        }
        return count
    }

    /** Pixels above [threshold] inside the inclusive tile rectangle. */
    private fun regionCount(mask: FloatArray, x0: Int, y0: Int, x1: Int, y1: Int, threshold: Float = 0.8f): Int {
        var count = 0
        for (y in y0..y1) for (x in x0..x1) if (mask[y * 64 + x] > threshold) count++
        return count
    }

    private fun mask(spec: PatternSpec): FloatArray {
        val sampler = PatternSampler(spec)
        return FloatArray(40 * 40) { i -> sampler.at((i % 40) + 0.5f, (i / 40) + 0.5f) }
    }

    private fun fill(spec: PatternSpec, bg: Rgba, fg: Rgba, opacity: Float): TileGrid {
        val doc = Document.create(64, 64)
        val layer: RasterLayer = doc.activeRaster!!
        assertNotNull(PatternFill.apply(doc, layer.id, spec, bg, fg, opacity))
        return layer.pixels
    }

    /** First integer pixel whose sampled coverage satisfies [predicate]. */
    private fun firstPoint(sampler: PatternSampler, predicate: (Float) -> Boolean): Pair<Int, Int> {
        for (y in 0 until 64) for (x in 0 until 64) {
            if (predicate(sampler.at(x + 0.5f, y + 0.5f))) return x to y
        }
        error("no pixel matches the coverage predicate")
    }

    private fun assertColor(expected: Rgba, actual: FloatArray, message: String) {
        assertEquals(expected.r * expected.a, actual[0], 1e-3f, "$message r")
        assertEquals(expected.g * expected.a, actual[1], 1e-3f, "$message g")
        assertEquals(expected.b * expected.a, actual[2], 1e-3f, "$message b")
        assertEquals(expected.a, actual[3], 1e-3f, "$message a")
    }

    private fun changed(a: FloatArray, b: FloatArray): Int =
        a.indices.count { abs(a[it] - b[it]) > 0.02f }

    private fun same(a: FloatArray, b: FloatArray): Boolean =
        a.indices.all { abs(a[it] - b[it]) <= 1e-5f }

    private fun assertContentEquals(a: FloatArray, b: FloatArray, message: String) {
        assertEquals(a.size, b.size, message)
        for (i in a.indices) assertEquals(a[i], b[i], 0f, "$message at $i")
    }
}
