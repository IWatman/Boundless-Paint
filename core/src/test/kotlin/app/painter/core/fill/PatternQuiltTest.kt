package app.painter.core.fill

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.history.History
import app.painter.core.history.TilePatchCommand
import app.painter.core.history.fillRect
import app.painter.core.history.gridHash
import app.painter.core.history.pixel
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternQuiltTest {
    private val background = Rgba(1f, 0f, 0f)
    private val ink = Rgba(0f, 0f, 1f)
    private val clear = Rgba.TRANSPARENT

    @Test
    fun layoutUsesRowsColumnsAndEvenGaps() {
        val layout = PatternQuilt.layout(IntRect(10, 20, 210, 120), rows = 3, columns = 2, gap = 10f)

        assertEquals(3, layout.rows)
        assertEquals(2, layout.columns)
        assertEquals(6, layout.cells.size)
        assertEquals(10f, layout.gapX, 1e-4f)
        assertEquals(10f, layout.gapY, 1e-4f)
        assertEquals(95f, layout.cellWidth, 1e-4f)
        assertEquals(80f / 3f, layout.cellHeight, 1e-4f)
        for (cell in layout.cells) {
            assertEquals(10f + cell.column * 105f, cell.x0, 1e-4f, "cell ${cell.row},${cell.column} x")
            assertEquals(20f + cell.row * (80f / 3f + 10f), cell.y0, 1e-4f, "cell ${cell.row},${cell.column} y")
            assertEquals(95f, cell.width, 1e-4f)
            assertEquals(80f / 3f, cell.height, 1e-4f)
        }
        assertEquals(210f, layout.cells.last().x1, 1e-4f)
        assertEquals(120f, layout.cells.last().y1, 1e-4f)
    }

    @Test
    fun layoutClampsCountsAndShrinksAnOversizedGapEvenly() {
        val layout = PatternQuilt.layout(IntRect(0, 0, 100, 60), rows = 99, columns = 0, gap = 999f)

        assertEquals(20, layout.rows)
        assertEquals(1, layout.columns)
        assertEquals(20, layout.cells.size)
        assertEquals(0f, layout.gapX, 0f)
        assertEquals((60f - 20f) / 19f, layout.gapY, 1e-4f)
        assertEquals(100f, layout.cellWidth, 1e-4f)
        assertEquals(1f, layout.cellHeight, 1e-4f)
        for (cell in layout.cells) {
            assertTrue(cell.width >= 1f)
            assertTrue(cell.height >= 1f)
        }
    }

    @Test
    fun oneCellCoversTheWholeBounds() {
        val layout = PatternQuilt.layout(IntRect(4, 5, 68, 37), rows = 1, columns = 1, gap = 64f)

        assertEquals(1, layout.cells.size)
        val cell = layout.cells.single()
        assertEquals(0f, layout.gapX, 0f)
        assertEquals(0f, layout.gapY, 0f)
        assertEquals(4f, cell.x0, 0f)
        assertEquals(5f, cell.y0, 0f)
        assertEquals(68f, cell.x1, 1e-4f)
        assertEquals(37f, cell.y1, 1e-4f)
    }

    @Test
    fun emptyBoundsProduceNoCells() {
        val layout = PatternQuilt.layout(IntRect(5, 5, 5, 5), rows = 2, columns = 2, gap = 4f)

        assertTrue(layout.cells.isEmpty())
    }

    @Test
    fun eachCellRestartsThePattern() {
        val doc = Document.create(128, 64)
        val layer = doc.activeRaster!!
        val spec = QuiltSpec(PatternSpec(PatternKind.DOTS, 16f), rows = 1, columns = 2, gap = 16f)
        assertNotNull(PatternQuilt.apply(doc, layer.id, spec, background, ink, 1f))

        // Cells are 56 px wide, so the second cell starts at x = 72 and mirrors the first one exactly.
        for (y in 0 until 64) for (x in 0 until 56) {
            assertColor(pixel(layer.pixels, x, y), pixel(layer.pixels, x + 72, y), "cell pixel $x,$y")
        }
    }

    @Test
    fun gapsStayUnpaintedInBothAxes() {
        val doc = Document.create(128, 128)
        val layer = doc.activeRaster!!
        val spec = QuiltSpec(PatternSpec(PatternKind.CHECKS, 16f), rows = 2, columns = 2, gap = 8f)
        assertNotNull(PatternQuilt.apply(doc, layer.id, spec, background, ink, 1f))

        assertTrue(pixel(layer.pixels, 30, 30)[3] > 0f)
        assertEquals(0f, pixel(layer.pixels, 64, 30)[3], 1e-6f, "horizontal gap")
        assertEquals(0f, pixel(layer.pixels, 30, 64)[3], 1e-6f, "vertical gap")
        assertEquals(0f, pixel(layer.pixels, 64, 64)[3], 1e-6f, "crossing of the gaps")
    }

    @Test
    fun selectionBoundsPlaceTheGridAndTheMaskClipsIt() {
        val doc = Document.create(300, 300)
        val layer = doc.activeRaster!!
        val mask = doc.selection.mask
        // An L-shaped selection: its tight bounds are 30..100 × 20..120, but the lower right stays unpainted.
        for (y in 20 until 120) for (x in 30 until 50) mask.getOrCreate(0, 0).data[(y shl 8) + x] = -1
        for (y in 20 until 40) for (x in 30 until 100) mask.getOrCreate(0, 0).data[(y shl 8) + x] = -1
        val spec = QuiltSpec(PatternSpec(PatternKind.CHECKS, 24f), rows = 1, columns = 1)
        assertNotNull(PatternQuilt.apply(doc, layer.id, spec, background, ink, 1f))

        assertTrue(pixel(layer.pixels, 40, 60)[3] > 0f, "inside the vertical leg")
        assertTrue(pixel(layer.pixels, 80, 30)[3] > 0f, "inside the horizontal leg")
        assertEquals(0f, pixel(layer.pixels, 80, 80)[3], 1e-6f, "inside the bounds but outside the mask")
        assertEquals(0f, pixel(layer.pixels, 20, 60)[3], 1e-6f, "left of the selection")
        assertEquals(0f, pixel(layer.pixels, 110, 60)[3], 1e-6f, "right of the selection")
    }

    @Test
    fun scaleAndRotationChangeTheCells() {
        val base = quiltHash(PatternSpec(PatternKind.DIAGONAL_STRIPES, 16f), 1, 1, 0f)
        val scaled = quiltHash(PatternSpec(PatternKind.DIAGONAL_STRIPES, 32f), 1, 1, 0f)
        val rotated = quiltHash(PatternSpec(PatternKind.DIAGONAL_STRIPES, 16f, 31f), 1, 1, 0f)

        assertNotEquals(base, scaled, "the tile size does not scale the cell")
        assertNotEquals(base, rotated, "the rotation does not turn the cell")
    }

    @Test
    fun quiltIsDeterministicWithEveryControl() {
        val spec = QuiltSpec(
            PatternSpec(PatternKind.STARS, 24f, 23f, 17f, -9f, 0.4f, invert = true),
            rows = 3,
            columns = 2,
            gap = 7f,
        )
        assertEquals(quiltHash(spec), quiltHash(spec))
    }

    @Test
    fun opacityAppliesToBothPatternColours() {
        val doc = Document.create(64, 64)
        val layer = doc.activeRaster!!
        val spec = QuiltSpec(PatternSpec(PatternKind.CHECKS, 32f), rows = 1, columns = 1)
        assertNotNull(PatternQuilt.apply(doc, layer.id, spec, background, ink, 0.5f))

        val dot = pixel(layer.pixels, 8, 8)
        assertEquals(0f, dot[0], 1e-3f)
        assertEquals(0.5f, dot[2], 1e-3f, "ink blue at half opacity")
        assertEquals(0.5f, dot[3], 1e-3f)
        val gap = pixel(layer.pixels, 24, 8)
        assertEquals(0.5f, gap[0], 1e-3f, "background red at half opacity")
        assertEquals(0f, gap[2], 1e-3f)
        assertEquals(0.5f, gap[3], 1e-3f)
    }

    @Test
    fun alphaLockKeepsTheOriginalAlpha() {
        val doc = Document.create(128, 128)
        val layer = doc.activeRaster!!
        fillRect(layer.pixels, 0, 0, 40, 40, 0f, 0f, 1f, 0.5f)
        layer.alphaLock = true
        val spec = QuiltSpec(PatternSpec(PatternKind.CHECKS, 16f, 7f, invert = true), rows = 2, columns = 2, gap = 4f)
        assertNotNull(PatternQuilt.apply(doc, layer.id, spec, background, ink, 1f))

        val inside = pixel(layer.pixels, 5, 5)
        assertEquals(0.5f, inside[3], 1e-3f)
        assertTrue(inside[0] + inside[2] > 0.1f, "the alpha-locked pixel did not take the pattern colours")
        assertEquals(0f, pixel(layer.pixels, 80, 80)[3], 1e-6f)
    }

    @Test
    fun lockedLayerIsLeftAlone() {
        val doc = Document.create(64, 64)
        val layer = doc.activeRaster!!
        layer.locked = true
        val spec = QuiltSpec(PatternSpec(PatternKind.DOTS, 16f), rows = 2, columns = 2, gap = 4f)
        assertNull(PatternQuilt.apply(doc, layer.id, spec, background, ink, 1f))
        assertTrue(layer.pixels.keys().isEmpty())
    }

    @Test
    fun emptySelectionAndTransparentColoursProduceNoPatch() {
        val doc = Document.create(64, 64)
        val layer = doc.activeRaster!!
        val spec = QuiltSpec(PatternSpec(PatternKind.CHECKS, 16f), rows = 2, columns = 2)
        assertNull(PatternQuilt.patch(layer, MaskGrid(64, 64), spec, background, ink, 1f))
        assertNull(PatternQuilt.patch(layer, null, spec, clear, clear, 1f))
        assertNull(PatternQuilt.patch(layer, null, spec, background, ink, 0f))
    }

    @Test
    fun undoRedoRestoresThePreviousPixels() {
        val doc = Document.create(128, 128)
        val layer = doc.activeRaster!!
        fillRect(layer.pixels, 10, 10, 40, 40, 0.2f, 0.4f, 0.6f, 1f)
        val before = pixel(layer.pixels, 20, 20)
        val spec = QuiltSpec(PatternSpec(PatternKind.BRICK, 24f, 12f), rows = 2, columns = 2, gap = 6f)
        val patch = PatternQuilt.apply(doc, layer.id, spec, background, ink, 1f)
        assertNotNull(patch)
        val after = pixel(layer.pixels, 20, 20)
        assertFalse(same(before, after), "the quilt did not cover the painted square")

        val history = History()
        history.push(TilePatchCommand(patch, "Pattern quilt"))
        assertEquals(1, history.undoSize)
        assertTrue(history.undo(doc))
        assertTrue(same(before, pixel(layer.pixels, 20, 20)))
        assertEquals(0f, pixel(layer.pixels, 100, 100)[3], 1e-6f)

        assertTrue(history.redo(doc))
        assertTrue(same(after, pixel(layer.pixels, 20, 20)))
        assertEquals(1, history.undoSize)
    }

    private fun quiltHash(spec: QuiltSpec): String {
        val doc = Document.create(96, 96)
        val layer: RasterLayer = doc.activeRaster!!
        assertNotNull(
            PatternQuilt.apply(doc, layer.id, spec, background, ink, 1f),
            "the quilt produced no patch for $spec",
        )
        return gridHash(layer.pixels)
    }

    private fun quiltHash(pattern: PatternSpec, rows: Int, columns: Int, gap: Float): String =
        quiltHash(QuiltSpec(pattern, rows, columns, gap))

    private fun assertColor(expected: FloatArray, actual: FloatArray, message: String) {
        for (channel in 0 until 4) assertEquals(expected[channel], actual[channel], 1e-6f, "$message channel $channel")
    }

    private fun same(a: FloatArray, b: FloatArray): Boolean =
        a.indices.all { kotlin.math.abs(a[it] - b[it]) <= 1e-5f }
}
