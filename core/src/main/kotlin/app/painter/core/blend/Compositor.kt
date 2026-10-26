package app.painter.core.blend

import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey

/** Tile-level compositing and whole-document flattening on the CPU. */
object Compositor {
    private const val PIXELS = Tile.SIZE * Tile.SIZE

    /**
     * Blends [src] onto [dst] in place with [mode] and [opacity]. Only non-empty source tiles are visited:
     * a transparent source never changes the destination in any mode (MASK/LINE_ART treat it as white).
     * When [clipAlpha] is given, the source alpha is multiplied per pixel by the clip grid's alpha;
     * tiles missing from [clipAlpha] are fully clipped.
     */
    fun compositeInto(dst: TileGrid, src: TileGrid, mode: BlendMode, opacity: Float, clipAlpha: TileGrid? = null) {
        if (opacity <= 0f) return
        val needsDst = mode == BlendMode.ERASE || mode == BlendMode.MASK || mode == BlendMode.INVERT
        for ((key, sTile) in src.entries()) {
            val clipTile = if (clipAlpha != null) clipAlpha.get(key) ?: continue else null
            if (sTile.isEmpty()) continue
            val dTile = if (needsDst) dst.get(key) ?: continue else dst.get(key) ?: Tile().also { dst.put(key, it) }
            val sp = FloatArray(4)
            val dp = FloatArray(4)
            var i = 0
            for (p in 0 until PIXELS) {
                if (sTile[i + 3] > 0f) {
                    val op = if (clipTile != null) opacity * clipTile[i + 3] else opacity
                    if (op > 0f) {
                        sTile.read(i, sp)
                        dTile.read(i, dp)
                        BlendMath.blendPixel(mode, dp, 0, sp, 0, op)
                        dTile.write(i, dp)
                    }
                }
                i += 4
            }
            dTile.markDirty()
        }
    }

    /**
     * Renders [doc] bottom-to-top into a new grid. Hidden nodes are skipped; clipped nodes use the alpha of
     * the nearest non-clipped sibling below (and are hidden with it). PASSTHROUGH groups draw their children
     * straight into the parent accumulator; other groups are rendered offscreen and blended as one layer.
     * With [includePaper] and a visible paper, rendering starts from the opaque paper color.
     */
    fun flatten(doc: Document, includePaper: Boolean): TileGrid {
        val acc = TileGrid(doc.width, doc.height)
        if (includePaper && doc.paper.visible) {
            val p = doc.paper.color
            for (ty in 0 until acc.tilesY) for (tx in 0 until acc.tilesX) {
                val d = acc.getOrCreate(tx, ty)
                var i = 0
                while (i < Tile.CHANNELS) {
                    d.write(i, p.r, p.g, p.b, 1f)
                    i += 4
                }
            }
        }
        renderStack(doc.root.children, acc, null)
        return acc
    }

    /**
     * Composites only the tile at [key] (same rules as [flatten]); null when the result is transparent there
     * and there is no paper. Cheap enough for per-pixel sampling on large canvases.
     */
    fun flattenTile(doc: Document, key: TileKey, includePaper: Boolean): Tile? {
        val acc = TileGrid(doc.width, doc.height)
        if (includePaper && doc.paper.visible) {
            val p = doc.paper.color
            val d = acc.getOrCreate(key.tx, key.ty)
            var i = 0
            while (i < Tile.CHANNELS) { d.write(i, p.r, p.g, p.b, 1f); i += 4 }
        }
        renderStack(doc.root.children, acc, key)
        return acc.get(key)
    }

    /** [grid] limited to the tile at [only] (sharing the tile), or [grid] itself when [only] is null. */
    private fun view(grid: TileGrid, only: TileKey?): TileGrid {
        if (only == null) return grid
        val v = TileGrid(grid.width, grid.height, grid.precise)
        grid.get(only)?.let { v.put(only, it) }
        return v
    }

    private fun renderStack(children: List<Node>, acc: TileGrid, only: TileKey?) {
        var baseAlpha: TileGrid? = null
        for ((index, node) in children.withIndex()) {
            if (node.clipped) {
                val base = baseAlpha ?: continue
                if (!node.visible) continue
                compositeInto(acc, contentOf(node, acc, only), node.blendMode, node.opacity, base)
                continue
            }
            baseAlpha = null
            if (!node.visible) continue
            val hasClipped = children.getOrNull(index + 1)?.clipped == true
            when (node) {
                is RasterLayer -> {
                    val px = view(node.pixels, only)
                    compositeInto(acc, px, node.blendMode, node.opacity)
                    baseAlpha = px
                }
                is GroupNode -> if (node.blendMode == BlendMode.PASSTHROUGH) {
                    if (hasClipped) baseAlpha = renderIsolated(node, acc, only)
                    renderPassthrough(node, acc, only)
                } else {
                    val off = renderIsolated(node, acc, only)
                    compositeInto(acc, off, node.blendMode, node.opacity)
                    baseAlpha = off
                }
            }
        }
    }

    private fun contentOf(node: Node, like: TileGrid, only: TileKey?): TileGrid = when (node) {
        is RasterLayer -> view(node.pixels, only)
        is GroupNode -> renderIsolated(node, like, only)
    }

    private fun renderIsolated(group: GroupNode, like: TileGrid, only: TileKey?): TileGrid {
        val off = TileGrid(like.width, like.height)
        renderStack(group.children, off, only)
        return off
    }

    private fun renderPassthrough(group: GroupNode, acc: TileGrid, only: TileKey?) {
        if (group.opacity >= 1f) {
            renderStack(group.children, acc, only)
            return
        }
        if (group.opacity <= 0f) return
        val result = acc.copy()
        renderStack(group.children, result, only)
        val t = group.opacity
        val keys = HashSet(acc.keys()).apply { addAll(result.keys()) }
        for (key in keys) {
            val r = result.get(key)
            val a = acc.get(key)
            if (a == null && r == null) continue
            val tile = a ?: Tile(acc.precise).also { acc.put(key, it) }
            if (r == null) {
                tile.scale(1f - t)
            } else {
                for (i in 0 until Tile.CHANNELS) tile[i] = tile[i] + (r[i] - tile[i]) * t
            }
            tile.markDirty()
        }
    }
}
