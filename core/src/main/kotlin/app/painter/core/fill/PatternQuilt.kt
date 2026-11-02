package app.painter.core.fill

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.doc.effectivelyLocked
import app.painter.core.history.touch
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch
import app.painter.core.raster.applyTiles
import app.painter.core.raster.maskedPatch
import app.painter.core.raster.parallelTiles
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pattern quilt settings: one [pattern] of the twelve built-in kinds, repeated in a [rows] × [columns]
 * grid with a [gap] in document pixels between the cells. Rows and columns are clamped to
 * [MIN_CELLS]..[MAX_CELLS] and the gap to 0..[MAX_GAP] by [PatternQuilt.layout], so an out-of-range
 * value cannot create unbounded work. Every cell restarts the pattern at its top-left, so all cells of
 * a quilt show the same motif at the chosen tile size and rotation.
 */
data class QuiltSpec(
    val pattern: PatternSpec = PatternSpec(PatternKind.CHECKS),
    val rows: Int = DEFAULT_ROWS,
    val columns: Int = DEFAULT_COLUMNS,
    val gap: Float = DEFAULT_GAP,
) {
    companion object {
        const val MIN_CELLS = 1
        const val MAX_CELLS = 20

        const val DEFAULT_ROWS = 4
        const val DEFAULT_COLUMNS = 4

        /** Default gap between two cells in document pixels. */
        const val DEFAULT_GAP = 8f
        const val MAX_GAP = 64f
    }
}

/** One cell of a quilt: its row and column and its half-open document rectangle. */
data class QuiltCell(
    val row: Int,
    val column: Int,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
}

/**
 * Geometry of one quilt: the [bounds] the grid fills, the effective [gapX]/[gapY] after the fit clamp,
 * the size of one cell and the row-major [cells]. Adjacent cells are separated by exactly the requested
 * gap, or by a smaller even gap when the requested one would make the cells disappear.
 */
data class QuiltLayout(
    val bounds: IntRect,
    val rows: Int,
    val columns: Int,
    val gapX: Float,
    val gapY: Float,
    val cellWidth: Float,
    val cellHeight: Float,
    val cells: List<QuiltCell>,
)

/**
 * Pattern quilt tool (spec §21): fills the active layer, or the selection when one is in effect, with a
 * grid of pattern cells. The grid covers the canvas content rectangle, or the selection bounds when a
 * selection exists, and every cell shows [QuiltSpec.pattern] clipped to its own rectangle. Cells are
 * composited between [background] (coverage 0) and [ink] (coverage 1) at the requested opacity; the
 * selection is honoured per pixel, alpha lock keeps the original alpha and a locked layer is left alone,
 * like [PatternFill].
 *
 * The result is one [TilePatch], so the caller records a single undo step. The generator is pure and
 * deterministic, and the tiles of one patch are independent, so the tool can run on a worker thread.
 */
object PatternQuilt {
    /**
     * Fills [layerId] with the quilt [spec] between [background] and [ink] at [opacity], clipped by the
     * selection. Returns the patch already applied to the layer, or null when nothing changed.
     */
    fun apply(
        doc: Document,
        layerId: String,
        spec: QuiltSpec,
        background: Rgba,
        ink: Rgba,
        opacity: Float,
    ): TilePatch? {
        val layer = doc.findNode(layerId) as? RasterLayer ?: return null
        if (layer.effectivelyLocked || opacity <= 0f || (background.a <= 0f && ink.a <= 0f)) return null
        val patch = patch(layer, doc.selection.coverageOrNull(), spec, background, ink, opacity) ?: return null
        applyTiles(layer.pixels, patch.after)
        doc.touch()
        return patch
    }

    /**
     * The patch [apply] would produce, without touching [layer]. A null [selection] uses the whole canvas;
     * [layer] may be a private copy when the quilt runs on a worker thread.
     */
    fun patch(
        layer: RasterLayer,
        selection: MaskGrid?,
        spec: QuiltSpec,
        background: Rgba,
        ink: Rgba,
        opacity: Float,
    ): TilePatch? {
        if (layer.effectivelyLocked || opacity <= 0f || (background.a <= 0f && ink.a <= 0f)) return null
        val grid = layer.pixels
        val canvas = IntRect(0, 0, grid.width, grid.height)
        val bounds = ((selection?.bounds() ?: canvas).intersect(canvas)) ?: return null
        val layout = layout(bounds, spec.rows, spec.columns, spec.gap)
        if (layout.cells.isEmpty()) return null
        val keys = when {
            selection == null -> bounds.tileKeys(grid.width, grid.height)
            else -> selection.keys()
                .filter { selection.isInBounds(it.tx, it.ty) && !(selection.get(it)?.isEmpty() ?: true) }
                .sortedBy { it.packed }
        }
        if (keys.isEmpty()) return null
        val sampler = PatternSampler(spec.pattern)
        val o = opacity.coerceIn(0f, 1f)
        val tiles = parallelTiles(keys) { key ->
            renderTile(sampler, grid.get(key), key, layout, background, ink, o)
        }
        val result = HashMap<TileKey, Tile?>(keys.size)
        for ((i, key) in keys.withIndex()) result[key] = tiles[i]
        return maskedPatch(layer, result, selection, preserveAlpha = layer.alphaLock)
    }

    /**
     * Grid geometry of a quilt over [bounds]. Counts are clamped to [QuiltSpec.MIN_CELLS]..[QuiltSpec.MAX_CELLS]
     * and the gap to 0..[QuiltSpec.MAX_GAP]; a gap that would leave a cell smaller than one pixel is reduced
     * evenly in both axes. Returns an empty cell list for an empty rectangle.
     */
    fun layout(bounds: IntRect, rows: Int, columns: Int, gap: Float): QuiltLayout {
        val r = rows.coerceIn(QuiltSpec.MIN_CELLS, QuiltSpec.MAX_CELLS)
        val c = columns.coerceIn(QuiltSpec.MIN_CELLS, QuiltSpec.MAX_CELLS)
        if (bounds.isEmpty) return QuiltLayout(bounds, r, c, 0f, 0f, 0f, 0f, emptyList())
        val width = bounds.width.toFloat()
        val height = bounds.height.toFloat()
        val requested = gap.coerceIn(0f, QuiltSpec.MAX_GAP)
        val gapX = if (c == 1) 0f else requested.coerceAtMost(max(0f, (width - c) / (c - 1)))
        val gapY = if (r == 1) 0f else requested.coerceAtMost(max(0f, (height - r) / (r - 1)))
        val cellWidth = (width - (c - 1) * gapX) / c
        val cellHeight = (height - (r - 1) * gapY) / r
        if (cellWidth <= 0f || cellHeight <= 0f) {
            return QuiltLayout(bounds, r, c, gapX, gapY, cellWidth, cellHeight, emptyList())
        }
        val cells = ArrayList<QuiltCell>(r * c)
        for (row in 0 until r) {
            val y0 = bounds.y0 + row * (cellHeight + gapY)
            for (column in 0 until c) {
                val x0 = bounds.x0 + column * (cellWidth + gapX)
                cells.add(QuiltCell(row, column, x0, y0, x0 + cellWidth, y0 + cellHeight))
            }
        }
        return QuiltLayout(bounds, r, c, gapX, gapY, cellWidth, cellHeight, cells)
    }

    /**
     * One tile with every cell cut by the pattern and composited over [before]. Pixels outside the cells,
     * in a gap or outside the grid bounds, keep their previous value.
     */
    private fun renderTile(
        sampler: PatternSampler,
        before: Tile?,
        key: TileKey,
        layout: QuiltLayout,
        background: Rgba,
        ink: Rgba,
        opacity: Float,
    ): Tile {
        val out = before?.copy(false) ?: Tile()
        val bx = key.tx shl Tile.SHIFT
        val by = key.ty shl Tile.SHIFT
        val p = FloatArray(4)
        for (cell in layout.cells) {
            val x0 = max(bx, ceil(cell.x0 - 0.5f).toInt())
            val y0 = max(by, ceil(cell.y0 - 0.5f).toInt())
            val x1 = min(bx + Tile.SIZE, ceil(cell.x1 - 0.5f).toInt())
            val y1 = min(by + Tile.SIZE, ceil(cell.y1 - 0.5f).toInt())
            if (x1 <= x0 || y1 <= y0) continue
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val coverage = sampler.at(x + 0.5f - cell.x0, y + 0.5f - cell.y0)
                    val invCoverage = 1f - coverage
                    // Premultiplied colour of the two straight-alpha pattern colours at this coverage.
                    val srcA = ((background.a * invCoverage + ink.a * coverage) * opacity).coerceIn(0f, 1f)
                    if (srcA <= 0f) continue
                    val i = out.index(x - bx, y - by)
                    out.read(i, p)
                    val inv = 1f - srcA
                    out.write(
                        i,
                        (background.r * background.a * invCoverage + ink.r * ink.a * coverage) * opacity + p[0] * inv,
                        (background.g * background.a * invCoverage + ink.g * ink.a * coverage) * opacity + p[1] * inv,
                        (background.b * background.a * invCoverage + ink.b * ink.a * coverage) * opacity + p[2] * inv,
                        srcA + p[3] * inv,
                    )
                }
            }
        }
        return out
    }
}
