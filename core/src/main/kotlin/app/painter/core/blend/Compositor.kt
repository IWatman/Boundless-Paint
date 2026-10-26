package app.painter.core.blend

import app.painter.core.adjust.AdjustMath
import app.painter.core.adjust.AdjustParams
import app.painter.core.adjust.PreparedAdjust
import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.LayerMask
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.history.NodeRenderer
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey

/** Tile-level compositing and whole-document flattening on the CPU. */
object Compositor : NodeRenderer {
    private const val PIXELS = Tile.SIZE * Tile.SIZE

    /**
     * Blends [src] onto [dst] in place with [mode] and [opacity]. Only non-empty source tiles are visited:
     * a transparent source never changes the destination in any mode (MASK/LINE_ART treat it as white).
     * When [clipAlpha] is given, the source alpha is multiplied per pixel by the clip grid's alpha;
     * tiles missing from [clipAlpha] are fully clipped. A [mask] multiplies the contribution per pixel by
     * `1 - maskDensity·(1 - m)`; missing mask tiles read as the mask's default value.
     */
    fun compositeInto(
        dst: TileGrid,
        src: TileGrid,
        mode: BlendMode,
        opacity: Float,
        clipAlpha: TileGrid? = null,
        mask: MaskGrid? = null,
        maskDensity: Float = 1f,
    ) {
        if (opacity <= 0f) return
        val needsDst = mode == BlendMode.ERASE || mode == BlendMode.MASK || mode == BlendMode.INVERT
        val sp = FloatArray(4)
        val dp = FloatArray(4)
        for ((key, sTile) in src.entries()) {
            val clipTile = if (clipAlpha != null) clipAlpha.get(key) ?: continue else null
            val mTile = mask?.get(key)
            val mConst = if (mask != null && mTile == null) effective(mask.defaultValue.toInt() and 0xFF, maskDensity) else 1f
            if (mConst <= 0f) continue
            if (sTile.isEmpty()) continue
            val dTile = if (needsDst) dst.get(key) ?: continue else dst.get(key) ?: Tile().also { dst.put(key, it) }
            var i = 0
            for (p in 0 until PIXELS) {
                if (sTile[i + 3] > 0f) {
                    var op = opacity * mConst
                    if (mTile != null) op *= effective(mTile.data[p].toInt() and 0xFF, maskDensity)
                    if (clipTile != null) op *= clipTile[i + 3]
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
     * Applies the correction [params] to [acc] in place as an adjustment layer would:
     * `acc = blend(mode, acc, adj(acc), f)` with `f = opacity · mask · clipAlpha`, evaluated on an opaque backdrop
     * and re-multiplied by the pixel alpha, so alpha never changes (except in ERASE/MASK-like modes) and
     * transparent pixels stay transparent. For NORMAL this is `lerp(acc, adj(acc), f)`.
     */
    fun adjustInto(
        acc: TileGrid,
        params: AdjustParams,
        mode: BlendMode,
        opacity: Float,
        clipAlpha: TileGrid? = null,
        mask: MaskGrid? = null,
        maskDensity: Float = 1f,
    ) = adjustInto(acc, AdjustMath.prepare(params), mode, opacity, clipAlpha, mask, maskDensity)

    /** [adjustInto] with prepared parameters. */
    fun adjustInto(
        acc: TileGrid,
        prep: PreparedAdjust,
        mode: BlendMode,
        opacity: Float,
        clipAlpha: TileGrid? = null,
        mask: MaskGrid? = null,
        maskDensity: Float = 1f,
    ) {
        if (opacity <= 0f || prep.isNoOp) return
        val d = FloatArray(4)
        val s = FloatArray(4)
        for ((key, tile) in acc.entries()) {
            val clipTile = if (clipAlpha != null) clipAlpha.get(key) ?: continue else null
            val mTile = mask?.get(key)
            val mConst = if (mask != null && mTile == null) effective(mask.defaultValue.toInt() and 0xFF, maskDensity) else 1f
            if (mConst <= 0f) continue
            if (tile.isEmpty()) continue
            var i = 0
            for (p in 0 until PIXELS) {
                val a = tile[i + 3]
                if (a > 0f) {
                    var f = opacity * mConst
                    if (mTile != null) f *= effective(mTile.data[p].toInt() and 0xFF, maskDensity)
                    if (clipTile != null) f *= clipTile[i + 3]
                    if (f > 0f) {
                        tile.read(i, d)
                        val inv = 1f / a
                        for (c in 0..2) { d[c] = (d[c] * inv).coerceIn(0f, 1f); s[c] = d[c] }
                        d[3] = 1f
                        AdjustMath.applyStraight(prep, s)
                        val sa = s[3]
                        s[0] *= sa; s[1] *= sa; s[2] *= sa
                        BlendMath.blendPixel(mode, d, 0, s, 0, f)
                        tile.write(i, d[0] * a, d[1] * a, d[2] * a, d[3] * a)
                    }
                }
                i += 4
            }
            tile.markDirty()
        }
    }

    /**
     * Renders [doc] bottom-to-top into a new grid. Hidden nodes are skipped; clipped nodes use the alpha of
     * the nearest non-clipped sibling below (times its mask; hidden with it; adjustment layers are never a
     * base). PASSTHROUGH groups draw their children straight into the parent accumulator (blended back by
     * `opacity · mask` when either is partial); other groups are rendered offscreen and blended as one layer.
     * Adjustment layers act on the accumulator of their parent. With [includePaper] and a visible paper,
     * rendering starts from the opaque paper color.
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
        Stack(null).render(doc.root.children, acc, false, null)
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
        Stack(key).render(doc.root.children, acc, false, null)
        return acc.get(key)
    }

    override fun renderInto(dst: TileGrid, nodes: List<Node>, ignoreVisibility: Boolean, clipBase: TileGrid?) =
        Stack(null).render(nodes, dst, ignoreVisibility, clipBase)

    /** Isolated render of [group]'s visible children onto transparent (the group's own props are not applied). */
    fun renderGroup(group: GroupNode, width: Int, height: Int): TileGrid =
        TileGrid(width, height).also { Stack(null).render(group.children, it, false, null) }

    /** [renderGroup] limited to the tile at [key]. */
    fun renderGroupTile(group: GroupNode, key: TileKey, width: Int, height: Int): Tile? =
        TileGrid(width, height).also { Stack(key).render(group.children, it, false, null) }.get(key)

    /** Copy of [grid] with every pixel multiplied by the effective value of [mask] (null = [grid] itself). */
    fun applyMask(grid: TileGrid, mask: LayerMask?): TileGrid {
        if (mask == null || !mask.enabled) return grid
        val out = TileGrid(grid.width, grid.height, grid.precise)
        for ((key, t) in grid.entries()) {
            val m = mask.tiles.get(key)
            val c = if (m == null) effective(mask.tiles.defaultValue.toInt() and 0xFF, mask.density) else 1f
            if (c <= 0f) continue
            val copy = t.copy()
            if (m == null) {
                if (c < 1f) copy.scale(c)
            } else {
                scaleByMask(copy, m, mask.density)
            }
            copy.markDirty()
            out.put(key, copy)
        }
        return out
    }

    /** Multiplies every pixel of [t] by `1 - density·(1 - m)`. */
    fun scaleByMask(t: Tile, m: MaskTile, density: Float) {
        var i = 0
        for (p in 0 until PIXELS) {
            val k = effective(m.data[p].toInt() and 0xFF, density)
            if (k < 1f) for (c in 0..3) t[i + c] = t[i + c] * k
            i += 4
        }
    }

    /** Effective mask factor of raw byte [m] at [density]. */
    fun effective(m: Int, density: Float): Float = 1f - density * (1f - m / 255f)

    private fun Node.activeMask(): LayerMask? = mask?.takeIf { it.enabled }

    /** One render pass; [only] limits every grid to that tile (for [flattenTile]). */
    private class Stack(val only: TileKey?) {
        private val prepared = HashMap<AdjustParams, PreparedAdjust>()

        fun render(children: List<Node>, acc: TileGrid, ignoreVisibility: Boolean, clipBase: TileGrid?) {
            var baseAlpha: TileGrid? = clipBase
            for ((index, node) in children.withIndex()) {
                val visible = ignoreVisibility || node.visible
                val mask = node.activeMask()
                if (node.clipped) {
                    val base = baseAlpha ?: continue
                    if (!visible) continue
                    if (node is AdjustmentLayer) {
                        adjust(acc, node, base)
                    } else {
                        compositeInto(
                            acc, contentOf(node, acc), node.blendMode, node.opacity, base,
                            mask?.tiles, mask?.density ?: 1f,
                        )
                    }
                    continue
                }
                baseAlpha = null
                if (!visible) continue
                val hasClipped = children.getOrNull(index + 1)?.clipped == true
                when (node) {
                    is RasterLayer -> {
                        val px = view(node.pixels)
                        compositeInto(acc, px, node.blendMode, node.opacity, null, mask?.tiles, mask?.density ?: 1f)
                        if (hasClipped) baseAlpha = applyMask(px, mask)
                    }
                    is GroupNode -> if (node.blendMode == BlendMode.PASSTHROUGH) {
                        if (hasClipped) baseAlpha = applyMask(isolated(node, acc), mask)
                        passthrough(node, acc, mask)
                    } else {
                        val off = isolated(node, acc)
                        compositeInto(acc, off, node.blendMode, node.opacity, null, mask?.tiles, mask?.density ?: 1f)
                        if (hasClipped) baseAlpha = applyMask(off, mask)
                    }
                    is AdjustmentLayer -> adjust(acc, node, null)
                }
            }
        }

        private fun adjust(acc: TileGrid, node: AdjustmentLayer, clip: TileGrid?) {
            val mask = node.activeMask()
            val prep = prepared.getOrPut(node.params) { AdjustMath.prepare(node.params) }
            adjustInto(acc, prep, node.blendMode, node.opacity, clip, mask?.tiles, mask?.density ?: 1f)
        }

        /** [grid] limited to the tile at [only] (sharing the tile), or [grid] itself when [only] is null. */
        private fun view(grid: TileGrid): TileGrid {
            if (only == null) return grid
            val v = TileGrid(grid.width, grid.height, grid.precise)
            grid.get(only)?.let { v.put(only, it) }
            return v
        }

        private fun contentOf(node: Node, like: TileGrid): TileGrid = when (node) {
            is RasterLayer -> view(node.pixels)
            is GroupNode -> isolated(node, like)
            is AdjustmentLayer -> TileGrid(like.width, like.height)
        }

        private fun isolated(group: GroupNode, like: TileGrid): TileGrid {
            val off = TileGrid(like.width, like.height)
            render(group.children, off, false, null)
            return off
        }

        private fun passthrough(group: GroupNode, acc: TileGrid, mask: LayerMask?) {
            if (group.opacity >= 1f && mask == null) {
                render(group.children, acc, false, null)
                return
            }
            if (group.opacity <= 0f) return
            val result = acc.copy()
            render(group.children, result, false, null)
            val t = group.opacity
            val keys = HashSet(acc.keys()).apply { addAll(result.keys()) }
            for (key in keys) {
                val r = result.get(key)
                val a = acc.get(key)
                if (a == null && r == null) continue
                val mTile = mask?.tiles?.get(key)
                val mConst = if (mask != null && mTile == null) effective(mask.tiles.defaultValue.toInt() and 0xFF, mask.density) else 1f
                if (mTile == null && t * mConst <= 0f) continue
                val tile = a ?: Tile(acc.precise).also { acc.put(key, it) }
                if (mTile == null) {
                    val k = t * mConst
                    if (r == null) tile.scale(1f - k)
                    else for (i in 0 until Tile.CHANNELS) tile[i] = tile[i] + (r[i] - tile[i]) * k
                } else {
                    var i = 0
                    for (p in 0 until PIXELS) {
                        val k = t * effective(mTile.data[p].toInt() and 0xFF, mask.density)
                        if (k > 0f) for (c in 0..3) {
                            val rv = r?.get(i + c) ?: 0f
                            tile[i + c] = tile[i + c] + (rv - tile[i + c]) * k
                        }
                        i += 4
                    }
                }
                tile.markDirty()
            }
        }
    }
}
