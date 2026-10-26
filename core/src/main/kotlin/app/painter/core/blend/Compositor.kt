package app.painter.core.blend

import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid

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
            val s = sTile.data
            val d = dTile.data
            val c = clipTile?.data
            var i = 0
            for (p in 0 until PIXELS) {
                if (s[i + 3] > 0f) {
                    val op = if (c != null) opacity * c[i + 3] else opacity
                    if (op > 0f) BlendMath.blendPixel(mode, d, i, s, i, op)
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
                val d = acc.getOrCreate(tx, ty).data
                var i = 0
                while (i < d.size) {
                    d[i] = p.r; d[i + 1] = p.g; d[i + 2] = p.b; d[i + 3] = 1f
                    i += 4
                }
            }
        }
        renderStack(doc.root.children, acc)
        return acc
    }

    private fun renderStack(children: List<Node>, acc: TileGrid) {
        var baseAlpha: TileGrid? = null
        for ((index, node) in children.withIndex()) {
            if (node.clipped) {
                val base = baseAlpha ?: continue
                if (!node.visible) continue
                compositeInto(acc, contentOf(node, acc), node.blendMode, node.opacity, base)
                continue
            }
            baseAlpha = null
            if (!node.visible) continue
            val hasClipped = children.getOrNull(index + 1)?.clipped == true
            when (node) {
                is RasterLayer -> {
                    compositeInto(acc, node.pixels, node.blendMode, node.opacity)
                    baseAlpha = node.pixels
                }
                is GroupNode -> if (node.blendMode == BlendMode.PASSTHROUGH) {
                    if (hasClipped) baseAlpha = renderIsolated(node, acc)
                    renderPassthrough(node, acc)
                } else {
                    val off = renderIsolated(node, acc)
                    compositeInto(acc, off, node.blendMode, node.opacity)
                    baseAlpha = off
                }
            }
        }
    }

    private fun contentOf(node: Node, like: TileGrid): TileGrid = when (node) {
        is RasterLayer -> node.pixels
        is GroupNode -> renderIsolated(node, like)
    }

    private fun renderIsolated(group: GroupNode, like: TileGrid): TileGrid {
        val off = TileGrid(like.width, like.height)
        renderStack(group.children, off)
        return off
    }

    private fun renderPassthrough(group: GroupNode, acc: TileGrid) {
        if (group.opacity >= 1f) {
            renderStack(group.children, acc)
            return
        }
        if (group.opacity <= 0f) return
        val result = acc.copy()
        renderStack(group.children, result)
        val t = group.opacity
        val keys = HashSet(acc.keys()).apply { addAll(result.keys()) }
        for (key in keys) {
            val r = result.get(key)?.data
            val a = acc.get(key)
            if (a == null && r == null) continue
            val tile = a ?: Tile().also { acc.put(key, it) }
            val d = tile.data
            if (r == null) {
                for (i in d.indices) d[i] *= 1f - t
            } else {
                for (i in d.indices) d[i] += (r[i] - d[i]) * t
            }
            tile.markDirty()
        }
    }
}
