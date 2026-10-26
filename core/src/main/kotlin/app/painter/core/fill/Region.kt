package app.painter.core.fill

import app.painter.core.blend.Compositor
import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.BitPlane
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.raster.parallelFor
import app.painter.core.raster.parallelTiles
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** What decides whether a pixel belongs to the fill region. */
enum class Boundary { COLOR, ALPHA }

/** Pixels the region is computed from. */
sealed interface RefSource {
    /** All visible layers without the paper. */
    data object Canvas : RefSource

    /** The active layer. */
    data object Current : RefSource

    /** A chosen node (the reference layer); groups are composited without the paper. */
    data class Layer(val id: String) : RefSource
}

/**
 * Region settings shared by the bucket, the wand and "limit gradient to region" (spec §4.1).
 * [tolerance] 0..1, [gapPx] 0..20, [expandPx] −20..20 (negative contracts).
 */
data class RegionParams(
    val tolerance: Float = 0.15f,
    val boundary: Boundary = Boundary.COLOR,
    val contiguous: Boolean = true,
    val gapPx: Int = 0,
    val expandPx: Float = 0f,
    val antialias: Boolean = true,
)

/** Premultiplied tiles of a [RefSource]; null means transparent. Must be safe to call from several threads. */
fun interface PixelSource {
    fun tile(key: TileKey): Tile?
}

/** Region pipeline: fillable set, gap closing, 4-connected flood, expansion and antialiasing. */
object Region {

    fun source(doc: Document, ref: RefSource): PixelSource = when (ref) {
        RefSource.Canvas -> PixelSource { Compositor.flattenTile(doc, it, includePaper = false) }
        RefSource.Current -> nodeSource(doc, doc.activeNode)
        is RefSource.Layer -> nodeSource(doc, doc.findNode(ref.id))
    }

    private fun nodeSource(doc: Document, node: Node?): PixelSource = when (node) {
        null -> PixelSource { null }
        is RasterLayer -> PixelSource { node.pixels.get(it) }
        is GroupNode -> PixelSource { NodeFlatten.tile(node, it, doc.width, doc.height) }
    }

    /** Straight color of the pixel ([x], [y]) in [src]. */
    fun colorAt(src: PixelSource, x: Int, y: Int): Rgba {
        val t = src.tile(TileKey(x shr Tile.SHIFT, y shr Tile.SHIFT)) ?: return Rgba.TRANSPARENT
        val p = FloatArray(4)
        t.read(t.index(x and Tile.MASK, y and Tile.MASK), p)
        val a = p[3].coerceIn(0f, 1f)
        if (a <= 0f) return Rgba.TRANSPARENT
        return Rgba((p[0] / a).coerceIn(0f, 1f), (p[1] / a).coerceIn(0f, 1f), (p[2] / a).coerceIn(0f, 1f), a)
    }

    /**
     * Pixels whose un-premultiplied color is within tolerance of [seed]: Chebyshev distance over RGBA for
     * [Boundary.COLOR], alpha difference for [Boundary.ALPHA]. The color of a fully transparent pixel is
     * undefined, so it matches any color and only alpha counts.
     */
    fun fillable(src: PixelSource, w: Int, h: Int, seed: Rgba, p: RegionParams): BitPlane {
        val out = BitPlane(w, h)
        val tol = p.tolerance + 1e-5f
        val keys = IntRect(0, 0, w, h).tileKeys(w, h)
        parallelTiles(keys) { key ->
            val t = src.tile(key)
            val bx = key.tx shl Tile.SHIFT
            val by = key.ty shl Tile.SHIFT
            val px = FloatArray(4)
            val x1 = minOf(Tile.SIZE, w - bx)
            val y1 = minOf(Tile.SIZE, h - by)
            for (y in 0 until y1) for (x in 0 until x1) {
                if (t != null) t.read(t.index(x, y), px) else px.fill(0f)
                if (matches(px, seed, p.boundary, tol)) out[bx + x, by + y] = true
            }
        }
        return out
    }

    private fun matches(px: FloatArray, seed: Rgba, boundary: Boundary, tol: Float): Boolean {
        val a = px[3].coerceIn(0f, 1f)
        val da = abs(a - seed.a)
        if (boundary == Boundary.ALPHA || da > tol) return da <= tol
        if (a <= 0f || seed.a <= 0f) return true
        val dr = abs(px[0] / a - seed.r)
        val dg = abs(px[1] / a - seed.g)
        val db = abs(px[2] / a - seed.b)
        return max(dr, max(dg, db)) <= tol
    }

    /** 4-connected scanline flood of [f] from ([x], [y]); empty when the seed is not in [f]. */
    fun flood(f: BitPlane, x: Int, y: Int): BitPlane {
        val w = f.width
        val out = BitPlane(w, f.height)
        if (!f[x, y]) return out
        var stack = IntArray(256)
        var sp = 0
        fun push(px: Int, py: Int) {
            if (sp + 2 > stack.size) stack = stack.copyOf(stack.size * 2)
            stack[sp++] = px; stack[sp++] = py
        }
        push(x, y)
        while (sp > 0) {
            val py = stack[--sp]
            val px = stack[--sp]
            if (!f[px, py] || out[px, py]) continue
            var l = px
            while (l > 0 && f[l - 1, py] && !out[l - 1, py]) l--
            var r = px
            while (r < w - 1 && f[r + 1, py] && !out[r + 1, py]) r++
            for (i in l..r) out[i, py] = true
            for (ny in intArrayOf(py - 1, py + 1)) {
                if (ny < 0 || ny >= f.height) continue
                var i = l
                while (i <= r) {
                    if (f[i, ny] && !out[i, ny]) {
                        push(i, ny)
                        while (i <= r && f[i, ny] && !out[i, ny]) i++
                    } else i++
                }
            }
        }
        return out
    }

    /** Pixels within Euclidean distance [r] of [x] (inside the plane). */
    fun dilate(x: BitPlane, r: Float): BitPlane {
        val out = x.copy()
        val b = x.bounds() ?: return out
        val m = ceil(r).toInt() + 1
        val area = IntRect(b.x0 - m, b.y0 - m, b.x1 + m, b.y1 + m).intersect(IntRect(0, 0, x.width, x.height))!!
        val d = Edt.distSq(area) { px, py -> x[px, py] }
        val r2 = r * r + 1e-4f
        val w = area.width
        parallelFor(area.height) { y ->
            for (i in 0 until w) if (d[y * w + i] <= r2) out[area.x0 + i, area.y0 + y] = true
        }
        return out
    }

    /**
     * Coverage of region [r] grown by [expandPx] (negative shrinks): with [aa] `clamp(e + 0.5 − s, 0, 1)`,
     * otherwise `s ≤ e`, where s is the signed distance of [Edt.signed].
     */
    fun coverage(r: BitPlane, expandPx: Float, aa: Boolean): MaskGrid {
        val w = r.width; val h = r.height
        val b = r.bounds() ?: return MaskGrid(w, h)
        val canvas = IntRect(0, 0, w, h)
        val e = expandPx
        if (e == 0f) return toMask(r, b)
        val m = if (e > 0) ceil(e).toInt() + 2 else 2
        val area = IntRect(b.x0 - m, b.y0 - m, b.x1 + m, b.y1 + m).intersect(canvas)!!
        val aw = area.width
        val cov = FloatArray(aw * area.height)
        if (e > 0) {
            val d = Edt.distSq(area) { x, y -> r[x, y] }
            parallelFor(area.height) { y ->
                for (i in 0 until aw) {
                    val k = y * aw + i
                    cov[k] = if (d[k] == 0f) 1f else rampOf(sqrt(d[k]) - 0.5f, e, aa)
                }
            }
        } else {
            val d = Edt.distSq(area) { x, y -> !r[x, y] }
            parallelFor(area.height) { y ->
                for (i in 0 until aw) {
                    val k = y * aw + i
                    cov[k] = if (d[k] == 0f) 0f else rampOf(-(sqrt(d[k]) - 0.5f), e, aa)
                }
            }
        }
        return fromArea(w, h, area, cov)
    }

    private fun rampOf(s: Float, e: Float, aa: Boolean): Float =
        if (aa) (e + 0.5f - s).coerceIn(0f, 1f) else if (s <= e) 1f else 0f

    /**
     * Full pipeline of §4.1 at seed ([x], [y]); null when the seed is outside the canvas or the region is
     * empty. Not multiplied by the selection: the tools that apply the region do that.
     */
    fun compute(doc: Document, ref: RefSource, x: Int, y: Int, p: RegionParams): MaskGrid? {
        val w = doc.width; val h = doc.height
        if (x !in 0 until w || y !in 0 until h) return null
        val src = cached(source(doc, ref))
        val f = fillable(src, w, h, colorAt(src, x, y), p)
        val r = when {
            !p.contiguous -> f
            p.gapPx > 0 -> closeGaps(f, x, y, p.gapPx)
            else -> flood(f, x, y)
        }
        if (r.bounds() == null) return null
        return coverage(r, p.expandPx, p.antialias)
    }

    /** Gap closing of §4.1 step 3; falls back to a plain flood when the seed lies in the dilated border. */
    fun closeGaps(f: BitPlane, x: Int, y: Int, g: Int): BitPlane {
        val border = f.copy().also { it.invert() }
        val closed = dilate(border, g.toFloat())
        if (closed[x, y]) return flood(f, x, y)
        closed.invert()
        val core = flood(closed, x, y)
        val grown = dilate(core, g + 1f)
        grown.and(f)
        return grown
    }

    private fun BitPlane.invert() {
        val all = BitPlane(width, height)
        for (yy in 0 until height) for (xx in 0 until width) all[xx, yy] = true
        all.andNot(this)
        clear(); or(all)
    }

    /** Memoizes tiles of [src] (thread-safe), so the seed lookup and the fillable pass share composites. */
    internal fun cached(src: PixelSource): PixelSource {
        val map = java.util.concurrent.ConcurrentHashMap<TileKey, Any>()
        val none = Any()
        return PixelSource { k -> map.getOrPut(k) { src.tile(k) ?: none }.let { if (it === none) null else it as Tile } }
    }

    internal fun toMask(r: BitPlane, b: IntRect): MaskGrid {
        val g = MaskGrid(r.width, r.height)
        val keys = b.tileKeys(r.width, r.height)
        val tiles = parallelTiles(keys) { key ->
            val t = MaskTile()
            val bx = key.tx shl Tile.SHIFT; val by = key.ty shl Tile.SHIFT
            var any = false
            for (y in 0 until Tile.SIZE) for (x in 0 until Tile.SIZE) {
                if (r[bx + x, by + y]) { t.data[(y shl Tile.SHIFT) + x] = -1; any = true }
            }
            if (any) t else null
        }
        for ((i, k) in keys.withIndex()) tiles[i]?.let { g.put(k, it) }
        return g
    }

    /** Mask of [width]×[height] with values 0..1 of [v] (row-major over [area]); zero outside the area. */
    internal fun fromArea(width: Int, height: Int, area: IntRect, v: FloatArray): MaskGrid {
        val g = MaskGrid(width, height)
        val keys = area.tileKeys(width, height)
        val aw = area.width
        val tiles = parallelTiles(keys) { key ->
            val t = MaskTile()
            val bx = key.tx shl Tile.SHIFT; val by = key.ty shl Tile.SHIFT
            var any = false
            val y0 = maxOf(by, area.y0); val y1 = minOf(by + Tile.SIZE, area.y1)
            val x0 = maxOf(bx, area.x0); val x1 = minOf(bx + Tile.SIZE, area.x1)
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = v[(y - area.y0) * aw + (x - area.x0)]
                if (c <= 0f) continue
                val b = (c.coerceAtMost(1f) * 255f + 0.5f).toInt()
                if (b == 0) continue
                t.data[((y - by) shl Tile.SHIFT) + (x - bx)] = b.toByte()
                any = true
            }
            if (any) t else null
        }
        for ((i, k) in keys.withIndex()) tiles[i]?.let { g.put(k, it) }
        return g
    }
}

/** Composites a node's own subtree for one tile, without the paper (groups used as fill references). */
internal object NodeFlatten {
    fun tile(node: Node, key: TileKey, w: Int, h: Int): Tile? = when (node) {
        is RasterLayer -> node.pixels.get(key)
        is GroupNode -> TileGrid(w, h).also { render(node.children, it, key) }.get(key)
    }

    private fun view(grid: TileGrid, key: TileKey): TileGrid =
        TileGrid(grid.width, grid.height).also { v -> grid.get(key)?.let { v.put(key, it) } }

    private fun content(n: Node, acc: TileGrid, key: TileKey): TileGrid = when (n) {
        is RasterLayer -> view(n.pixels, key)
        is GroupNode -> TileGrid(acc.width, acc.height).also { render(n.children, it, key) }
    }

    private fun render(children: List<Node>, acc: TileGrid, key: TileKey) {
        var base: TileGrid? = null
        for (n in children) {
            if (n.clipped) {
                val b = base ?: continue
                if (n.visible) Compositor.compositeInto(acc, content(n, acc, key), n.blendMode, n.opacity, b)
                continue
            }
            base = null
            if (!n.visible) continue
            val c = content(n, acc, key)
            Compositor.compositeInto(acc, c, n.blendMode, n.opacity)
            base = c
        }
    }
}
