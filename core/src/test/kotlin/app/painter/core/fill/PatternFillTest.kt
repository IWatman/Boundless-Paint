package app.painter.core.fill

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.history.History
import app.painter.core.history.TilePatchCommand
import app.painter.core.history.fillRect
import app.painter.core.history.pixel
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

    @Test
    fun coverage_wrapsAtTileEdgesForEveryPattern() {
        val samples = floatArrayOf(-0.31f, 0f, 0.03f, 0.25f, 0.49f, 0.5f, 0.77f, 0.999f)
        for (kind in kinds) {
            for (u in samples) for (v in samples) {
                val base = PatternGenerator.coverage(kind, u, v, 0.01f)
                assertEquals(base, PatternGenerator.coverage(kind, u + 1f, v, 0.01f), 2e-4f, "$kind wraps u at $u,$v")
                assertEquals(base, PatternGenerator.coverage(kind, u, v + 1f, 0.01f), 2e-4f, "$kind wraps v at $u,$v")
                assertEquals(base, PatternGenerator.coverage(kind, u - 1f, v - 1f, 0.01f), 2e-4f, "$kind wraps back at $u,$v")
            }
        }
    }

    @Test
    fun sampling_isDeterministic() {
        for (kind in kinds) {
            val a = mask(kind, tile = 48f, rotation = 0f)
            val b = mask(kind, tile = 48f, rotation = 0f)
            for (i in a.indices) assertEquals(a[i], b[i], 0f, "$kind at $i")
        }
    }

    @Test
    fun rotationAndScaleChangeTheMask() {
        for (kind in kinds) {
            val base = mask(kind, tile = 48f, rotation = 0f)
            val scaled = mask(kind, tile = 24f, rotation = 0f)
            val rotated = mask(kind, tile = 48f, rotation = 37f)
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
        for (kind in kinds) {
            val sampler = PatternSampler(PatternSpec(kind, tile, rotation))
            for ((x, y) in points) {
                val base = sampler.at(x, y)
                assertEquals(base, sampler.at(x + dx, y + dy), 1e-5f, "$kind does not wrap along u at $x,$y")
                assertEquals(base, sampler.at(x + ex, y + ey), 1e-5f, "$kind does not wrap along v at $x,$y")
            }
        }
    }

    @Test
    fun selectionClipsTheFill() {
        val doc = Document.create(300, 300)
        val layer = doc.activeRaster!!
        val mask = doc.selection.mask
        for (y in 0 until 50) for (x in 0 until 50) mask.getOrCreate(0, 0).data[(y shl 8) + x] = -1
        assertNotNull(PatternFill.apply(doc, layer.id, PatternSpec(PatternKind.CHECKS, 32f), Rgba(1f, 0f, 0f), 1f))
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
        assertNotNull(
            PatternFill.apply(doc, layer.id, PatternSpec(PatternKind.CHECKS, 16f), Rgba(1f, 0f, 0f), 1f),
        )
        val inside = pixel(layer.pixels, 5, 5)
        assertEquals(0.5f, inside[3], 1e-3f)
        assertTrue(inside[0] > 0.1f, "the alpha-locked pixel did not take the pattern color")
        assertEquals(0f, pixel(layer.pixels, 40, 40)[3], 1e-6f)
    }

    @Test
    fun lockedLayerIsLeftAlone() {
        val doc = Document.create(64, 64)
        val layer = doc.activeRaster!!
        layer.locked = true
        assertNull(PatternFill.apply(doc, layer.id, PatternSpec(PatternKind.DOTS, 32f), Rgba.BLACK, 1f))
        assertTrue(layer.pixels.keys().isEmpty())
    }

    @Test
    fun fullLayerFillHonoursOpacity() {
        val doc = Document.create(300, 300)
        val layer = doc.activeRaster!!
        val patch = PatternFill.apply(
            doc,
            layer.id,
            PatternSpec(PatternKind.CHECKS, 32f),
            Rgba(1f, 0f, 0f),
            0.5f,
        )
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
        val patch = PatternFill.apply(
            doc,
            layer.id,
            PatternSpec(PatternKind.BRICK, 32f, 12f),
            Rgba(1f, 0.2f, 0f),
            1f,
        )
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

    private fun mask(kind: PatternKind, tile: Float, rotation: Float): FloatArray {
        val sampler = PatternSampler(PatternSpec(kind, tile, rotation))
        return FloatArray(40 * 40) { i -> sampler.at((i % 40) + 0.5f, (i / 40) + 0.5f) }
    }

    private fun changed(a: FloatArray, b: FloatArray): Int =
        a.indices.count { abs(a[it] - b[it]) > 0.02f }

    private fun same(a: FloatArray, b: FloatArray): Boolean =
        a.indices.all { abs(a[it] - b[it]) <= 1e-5f }
}
