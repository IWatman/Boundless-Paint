package app.painter.core.canvas

import app.painter.core.doc.Document
import app.painter.core.doc.LayerMask
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.doc.Selection
import app.painter.core.history.CanvasCommand
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.TileGrid
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Canvas crop, canvas-size and straighten (spec/ROADMAP.md M3, spec/FEATURE-MAP.md "Crop (non-destructive),
 * resize (content, canvas, anchor), straighten").
 *
 * [Crop] keeps a document-space rectangle, [CanvasSize] grows/shrinks the canvas around the content and
 * [Straighten] rotates every pixel about the canvas centre. All layers, their masks and the selection go through
 * the same mapping; [command] wraps the result in one undoable step.
 */
sealed class CanvasOp {
    /** New canvas size after [this] op is applied. */
    abstract val width: Int
    abstract val height: Int

    data class Crop(val rect: IntRect) : CanvasOp() {
        override val width: Int get() = rect.width
        override val height: Int get() = rect.height
    }

    /**
     * New canvas [width] x [height]. With [scaleContent] the artwork is scaled to the new canvas; otherwise it
     * keeps its size and is positioned by the anchor ([anchorX]/[anchorY] 0..1, 0.5 = centre).
     */
    data class CanvasSize(
        override val width: Int,
        override val height: Int,
        val scaleContent: Boolean = false,
        val anchorX: Float = 0.5f,
        val anchorY: Float = 0.5f,
    ) : CanvasOp()

    /** Rotates the artwork by [angleDeg] (clockwise) inside the same canvas. */
    data class Straighten(val angleDeg: Float) : CanvasOp() {
        override val width: Int = 0
        override val height: Int = 0
    }

    companion object {
        /** New canvas size of [op] for a document of [docW] x [docH]. */
        fun sizeOf(op: CanvasOp, docW: Int, docH: Int): Pair<Int, Int> = when (op) {
            is Crop -> op.width to op.height
            is CanvasSize -> op.width to op.height
            is Straighten -> docW to docH
        }
    }
}

/** Mapping from a destination canvas pixel to the source pixel of the old canvas. */
private class Mapping(
    val srcW: Int,
    val srcH: Int,
    val dstW: Int,
    val dstH: Int,
    val exact: Boolean,
    val mapX: (Float, Float) -> Float,
    val mapY: (Float, Float) -> Float,
)

/** Builds the full undoable canvas command for [op] against [doc]. */
fun canvasCommand(doc: Document, op: CanvasOp, name: String = "Canvas"): CanvasCommand {
    val (dstW, dstH) = CanvasOp.sizeOf(op, doc.width, doc.height)
    require(dstW > 0 && dstH > 0) { "canvas must not be empty" }
    val mapping = mappingOf(op, doc.width, doc.height, dstW, dstH)
    val beforeLayers = LinkedHashMap<String, TileGrid>()
    val afterLayers = LinkedHashMap<String, TileGrid>()
    val beforeMasks = LinkedHashMap<String, MaskGrid>()
    val afterMasks = LinkedHashMap<String, MaskGrid>()
    forEachNode(doc.root) { node ->
        if (node is RasterLayer) {
            beforeLayers[node.id] = node.pixels
            afterLayers[node.id] = resample(node.pixels, mapping)
        }
        node.mask?.let { m ->
            beforeMasks[node.id] = m.tiles
            afterMasks[node.id] = resampleMask(m.tiles, mapping)
        }
    }
    val beforeSelection = doc.selection.mask
    val afterSelection = resampleMask(beforeSelection, mapping, doc.selection)
    return CanvasCommand(
        name = name,
        oldWidth = doc.width,
        oldHeight = doc.height,
        newWidth = dstW,
        newHeight = dstH,
        beforeLayers = beforeLayers,
        afterLayers = afterLayers,
        beforeMasks = beforeMasks,
        afterMasks = afterMasks,
        beforeSelection = beforeSelection,
        afterSelection = afterSelection,
    )
}

private fun forEachNode(group: app.painter.core.doc.GroupNode, visit: (Node) -> Unit) {
    for (c in group.children) {
        visit(c)
        if (c is app.painter.core.doc.GroupNode) forEachNode(c, visit)
    }
}

private fun mappingOf(op: CanvasOp, srcW: Int, srcH: Int, dstW: Int, dstH: Int): Mapping = when (op) {
    is CanvasOp.Crop -> {
        val ox = op.rect.x0.toFloat()
        val oy = op.rect.y0.toFloat()
        Mapping(srcW, srcH, dstW, dstH, exact = true, mapX = { x, _ -> x + ox }, mapY = { _, y -> y + oy })
    }
    is CanvasOp.CanvasSize -> {
        if (op.scaleContent) {
            val sx = srcW.toFloat() / dstW
            val sy = srcH.toFloat() / dstH
            Mapping(srcW, srcH, dstW, dstH, exact = false, mapX = { x, _ -> (x + 0.5f) * sx - 0.5f }, mapY = { _, y -> (y + 0.5f) * sy - 0.5f })
        } else {
            val ox = (dstW - srcW) * op.anchorX.coerceIn(0f, 1f)
            val oy = (dstH - srcH) * op.anchorY.coerceIn(0f, 1f)
            Mapping(srcW, srcH, dstW, dstH, exact = true, mapX = { x, _ -> x - ox }, mapY = { _, y -> y - oy })
        }
    }
    is CanvasOp.Straighten -> {
        val a = Math.toRadians(op.angleDeg.toDouble()).toFloat()
        val ca = cos(a)
        val sa = sin(a)
        val scx = srcW / 2f
        val scy = srcH / 2f
        val dcx = dstW / 2f
        val dcy = dstH / 2f
        // Destination -> source: undo the clockwise rotation.
        Mapping(
            srcW, srcH, dstW, dstH, exact = false,
            mapX = { x, y -> scx + (x - dcx) * ca + (y - dcy) * sa },
            mapY = { x, y -> scy - (x - dcx) * sa + (y - dcy) * ca },
        )
    }
}

/** Resamples a premultiplied RGBA grid; the target is fully transparent where the source has no pixels. */
private fun resample(src: TileGrid, m: Mapping): TileGrid {
    val out = TileGrid(m.dstW, m.dstH, src.precise)
    val px = FloatArray(4)
    if (m.exact) {
        for (y in 0 until m.dstH) {
            val syf = m.mapY(0f, y.toFloat())
            val sy = syf.roundToInt()
            if (sy < 0 || sy >= src.height) continue
            for (x in 0 until m.dstW) {
                val sx = m.mapX(x.toFloat(), y.toFloat()).roundToInt()
                if (sx < 0 || sx >= src.width) continue
                src.getPixel(sx, sy, px)
                if (px[3] != 0f || px[0] != 0f || px[1] != 0f || px[2] != 0f) {
                    out.setPixel(x, y, px[0], px[1], px[2], px[3])
                }
            }
        }
        return out
    }
    val a = FloatArray(4)
    val b = FloatArray(4)
    for (y in 0 until m.dstH) {
        for (x in 0 until m.dstW) {
            val sx = m.mapX(x + 0.5f, y + 0.5f)
            val sy = m.mapY(x + 0.5f, y + 0.5f)
            if (sx < -1f || sy < -1f || sx > src.width.toFloat() || sy > src.height.toFloat()) continue
            bilinear(src, sx, sy, a, b)
            if (a[3] > 0.0001f) out.setPixel(x, y, a[0], a[1], a[2], a[3])
        }
    }
    return out
}

private fun bilinear(grid: TileGrid, x: Float, y: Float, out: FloatArray, tmp: FloatArray) {
    val fx = x.coerceIn(0f, (grid.width - 1).toFloat())
    val fy = y.coerceIn(0f, (grid.height - 1).toFloat())
    val x0 = floor(fx).toInt()
    val y0 = floor(fy).toInt()
    val x1 = min(x0 + 1, grid.width - 1)
    val y1 = min(y0 + 1, grid.height - 1)
    val tx = fx - x0
    val ty = fy - y0
    grid.getPixel(x0, y0, tmp)
    val p00 = tmp.copyOf()
    grid.getPixel(x1, y0, tmp); val p10 = tmp.copyOf()
    grid.getPixel(x0, y1, tmp); val p01 = tmp.copyOf()
    grid.getPixel(x1, y1, tmp); val p11 = tmp.copyOf()
    for (c in 0..3) {
        val top = p00[c] + (p10[c] - p00[c]) * tx
        val bottom = p01[c] + (p11[c] - p01[c]) * tx
        out[c] = top + (bottom - top) * ty
    }
}

/** Resamples a mask with nearest sampling, preserving its default value. */
private fun resampleMask(src: MaskGrid, m: Mapping, selection: Selection? = null): MaskGrid {
    val out = MaskGrid(m.dstW, m.dstH, src.defaultValue)
    for (y in 0 until m.dstH) {
        for (x in 0 until m.dstW) {
            val sx = if (m.exact) m.mapX(x.toFloat(), y.toFloat()).roundToInt()
            else m.mapX(x + 0.5f, y + 0.5f).roundToInt()
            val sy = if (m.exact) m.mapY(x.toFloat(), y.toFloat()).roundToInt()
            else m.mapY(x + 0.5f, y + 0.5f).roundToInt()
            if (sx < 0 || sx >= src.width || sy < 0 || sy >= src.height) continue
            val v = src.byteAt(sx, sy)
            if (v != (src.defaultValue.toInt() and 0xFF)) {
                val t = out.getOrCreate(x shr app.painter.core.raster.Tile.SHIFT, y shr app.painter.core.raster.Tile.SHIFT)
                t.set(x and app.painter.core.raster.Tile.MASK, y and app.painter.core.raster.Tile.MASK, v / 255f)
            }
        }
    }
    return out
}

/** Suggested crop rectangle: the selection bounds when there is one, else a centred 4:3 area. */
fun suggestedCrop(doc: Document): IntRect {
    val b = doc.selection.coverageOrNull()?.bounds()
    if (b != null) return b
    val w = max(1, (doc.width * 0.9f).toInt())
    val h = max(1, (doc.height * 0.9f).toInt())
    return IntRect((doc.width - w) / 2, (doc.height - h) / 2, (doc.width + w) / 2, (doc.height + h) / 2)
}

/** Trims transparent margins of the whole document when that is safe (at least one non-empty pixel). */
fun contentBounds(doc: Document): IntRect? {
    var r: IntRect? = null
    forEachNode(doc.root) { node ->
        if (node is RasterLayer && node.visible) {
            val b = node.pixels.bounds() ?: return@forEachNode
            r = r?.union(b) ?: b
        }
    }
    return r
}

/**
 * A crop rectangle clamped to the canvas; null when it is degenerate. The UI uses this before building
 * [CanvasOp.Crop] so a typo cannot destroy the document.
 */
fun clampCrop(doc: Document, r: IntRect): IntRect? {
    val x0 = r.x0.coerceIn(0, doc.width - 1)
    val y0 = r.y0.coerceIn(0, doc.height - 1)
    val x1 = r.x1.coerceIn(x0 + 1, doc.width)
    val y1 = r.y1.coerceIn(y0 + 1, doc.height)
    return IntRect(x0, y0, x1, y1)
}
