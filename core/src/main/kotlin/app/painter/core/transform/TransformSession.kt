package app.painter.core.transform

import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.RasterLayer
import app.painter.core.doc.newNodeId
import app.painter.core.history.AddLayer
import app.painter.core.history.Command
import app.painter.core.history.CompoundCommand
import app.painter.core.history.SelectionPatchCommand
import app.painter.core.history.TilePatchCommand
import app.painter.core.history.touch
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch
import app.painter.core.raster.alphaBounds
import app.painter.core.raster.applyTiles
import app.painter.core.raster.maskedTile
import app.painter.core.raster.parallelTiles

/**
 * One transform session (spec §3.1). On start the selected part of every layer is lifted into [floating]
 * and the layer keeps only the rest (written straight into the document, outside history, for the preview).
 * Every edit inside the session is one [Mapping] from the **original** floating pixels, so nothing degrades
 * until [commit]. [cancel] puts the layers back without a history entry.
 *
 * Groups in [layerIds] stand for all their raster descendants; locked layers are skipped ([skipped]).
 */
class TransformSession(
    val doc: Document,
    layerIds: List<String>,
    val target: Target = Target.PIXELS,
) {
    /** What is transformed: layer pixels (with the selection following them) or the selection mask alone. */
    enum class Target { PIXELS, SELECTION_MASK }

    /** Raster layers in the session, bottom-to-top as given. */
    val layers: List<String>

    /** Layers left out because they are locked. */
    val skipped: List<String>

    /** Lifted pixels per layer id (canvas-sized grids). */
    val floating: Map<String, TileGrid>

    /** Selection mask moving with the content (or the object itself for [Target.SELECTION_MASK]). */
    val floatingMask: MaskGrid?

    /** Tight axis-aligned bounds of everything lifted; null when there is nothing to transform. */
    val cage: IntRect?

    /** True until [commit] or [cancel]. */
    var isOpen: Boolean = true
        private set

    private val originals = HashMap<String, Map<TileKey, Tile?>>()
    private val rest = HashMap<String, TileGrid>()
    private val maskGrid: TileGrid?
    private val mips = HashMap<String, MipChain>()
    private var stampLayer: RasterLayer? = null

    init {
        val ids = ArrayList<String>(); val skip = ArrayList<String>()
        if (target == Target.PIXELS) {
            for (id in layerIds) {
                val raster = when (val n = doc.findNode(id)) {
                    is RasterLayer -> listOf(n)
                    is GroupNode -> ArrayList<RasterLayer>().also { l -> n.walk { if (it is RasterLayer) l.add(it) } }
                    is AdjustmentLayer, null -> emptyList()
                }
                for (l in raster) {
                    if (l.id in ids || l.id in skip) continue
                    if (l.locked || generateSequence(l.parent) { it.parent }.any { it.locked }) skip.add(l.id) else ids.add(l.id)
                }
            }
        }
        layers = ids
        skipped = skip
        val sel = doc.selection.coverageOrNull()
        val fl = LinkedHashMap<String, TileGrid>()
        var bounds: IntRect? = null
        for (id in ids) {
            val layer = doc.findNode(id) as RasterLayer
            val px = layer.pixels
            originals[id] = px.entries().associate { it.key to it.value.copy() }
            val f = TileGrid(doc.width, doc.height)
            val r = TileGrid(doc.width, doc.height)
            for ((k, t) in px.entries()) {
                if (sel == null) {
                    f.put(k, t.copy(false))
                } else {
                    maskedTile(t, sel, k)?.let { f.put(k, it) }
                    maskedTile(t, sel, k, inverse = true)?.let { r.put(k, it) }
                }
            }
            f.alphaBounds()?.let { bounds = bounds?.union(it) ?: it }
            fl[id] = f
            rest[id] = r
            px.clear()
            applyTiles(px, r.entries().associate { it.key to it.value })
        }
        floating = fl
        floatingMask = when (target) {
            Target.SELECTION_MASK -> doc.selection.mask.copy()
            Target.PIXELS -> sel?.copy()
        }
        maskGrid = floatingMask?.let(::maskToGrid)
        if (target == Target.SELECTION_MASK) bounds = floatingMask?.bounds()
        cage = bounds
        if (ids.isNotEmpty()) doc.touch()
    }

    /** Mip pyramid of a layer's floating pixels (for previews that sample on the CPU). */
    fun mips(layerId: String): MipChain = synchronized(mips) { mips.getOrPut(layerId) { MipChain(floating.getValue(layerId)) } }

    /**
     * Resamples [src] through [mapping] into canvas tiles (dst bounds ∩ canvas, pixels outside the canvas
     * stay transparent). Parallel per tile.
     */
    fun render(src: TileGrid, mapping: Mapping, mode: Resample, mip: MipChain? = null): Map<TileKey, Tile> {
        val srcBounds = src.alphaBounds() ?: return emptyMap()
        val canvas = IntRect(0, 0, doc.width, doc.height)
        val dst = mapping.dstBounds(srcBounds).intersect(canvas) ?: return emptyMap()
        val chain = mip ?: MipChain(src)
        val keys = dst.tileKeys(doc.width, doc.height)
        val tiles = parallelTiles(keys) { k -> renderTile(src, chain, srcBounds, mapping, mode, k) }
        val out = LinkedHashMap<TileKey, Tile>()
        for ((i, k) in keys.withIndex()) tiles[i]?.let { out[k] = it }
        return out
    }

    private fun renderTile(src: TileGrid, chain: MipChain, sb: IntRect, m: Mapping, mode: Resample, k: TileKey): Tile? {
        val bx = k.tx shl Tile.SHIFT; val by = k.ty shl Tile.SHIFT
        val scale = Resampler.forwardScale(m, bx + Tile.SIZE / 2f, by + Tile.SIZE / 2f)
        val level = Resampler.levelFor(scale)
        val reader = GridReader(if (level == 0) src else chain.level(level))
        val div = (1 shl level).toFloat()
        val uv = FloatArray(2); val px = FloatArray(4); val tmp = FloatArray(16)
        val margin = 3f * div
        var out: Tile? = null
        for (y in 0 until minOf(Tile.SIZE, doc.height - by)) for (x in 0 until minOf(Tile.SIZE, doc.width - bx)) {
            if (!m.inverse(bx + x + 0.5f, by + y + 0.5f, uv)) continue
            if (uv[0] < sb.x0 - margin || uv[1] < sb.y0 - margin || uv[0] > sb.x1 + margin || uv[1] > sb.y1 + margin) continue
            Resampler.sampleAt(reader, uv[0] / div, uv[1] / div, mode, px, tmp)
            if (px[3] <= 0f) continue
            val t = out ?: Tile().also { out = it }
            t.write(t.index(x, y), px)
        }
        return out
    }

    /**
     * Applies the session: each layer becomes rest OVER resampled floating, the selection follows the
     * content. Returns one already applied command ("Transform"); the session closes.
     */
    fun commit(mapping: Mapping, mode: Resample): Command {
        check(isOpen) { "session closed" }
        val cmds = ArrayList<Command>()
        for (id in layers) {
            val res = render(floating.getValue(id), mapping, mode, mips(id))
            val r = rest.getValue(id)
            val orig = originals.getValue(id)
            val keys = LinkedHashSet<TileKey>().apply { addAll(orig.keys); addAll(r.keys()); addAll(res.keys) }
            val before = LinkedHashMap<TileKey, Tile?>(); val after = LinkedHashMap<TileKey, Tile?>()
            for (k in keys) {
                val fin = over(r.get(k), res[k])
                val o = orig[k]
                val same = if (o == null || fin == null) o == fin else o.contentEquals(fin)
                if (same) continue
                before[k] = o?.copy(); after[k] = fin
            }
            val layer = doc.findNode(id) as RasterLayer
            // The layer currently holds the rest: put the originals back, then apply the patch on top.
            layer.pixels.clear()
            applyTiles(layer.pixels, orig)
            if (before.isNotEmpty()) {
                val patch = TilePatch(id, before, after)
                applyTiles(layer.pixels, patch.after)
                cmds.add(TilePatchCommand(patch, "Transform"))
            }
        }
        transformedMask(mapping, mode)?.let { m ->
            SelectionPatchCommand.replacing(doc, m, "Transform selection")?.let { it.apply(doc); cmds.add(it) }
        }
        isOpen = false
        doc.touch()
        return CompoundCommand("Transform", cmds)
    }

    /**
     * Copies the current result onto the layer "<name> stamps" (created on the first stamp, above the first
     * layer of the session) and keeps the session open. Returns the already applied command.
     */
    fun stamp(mapping: Mapping, mode: Resample): Command {
        check(isOpen) { "session closed" }
        val acc = TileGrid(doc.width, doc.height)
        for (id in layers) for ((k, t) in render(floating.getValue(id), mapping, mode, mips(id))) {
            acc.put(k, over(acc.get(k), t))
        }
        val existing = stampLayer
        if (existing == null || doc.findNode(existing.id) == null) {
            val first = doc.findNode(layers.firstOrNull() ?: return CompoundCommand("Stamp", emptyList())) as RasterLayer
            val layer = RasterLayer(newNodeId(), first.name + " stamps", acc)
            stampLayer = layer
            val parent = first.parent ?: doc.root
            val active = doc.activeId
            val add = AddLayer(layer, parent.id, parent.indexOf(first) + 1, "Stamp")
            val keep = KeepActive(active)
            val cmd = CompoundCommand("Stamp", listOf(add, keep))
            cmd.apply(doc)
            return cmd
        }
        val g = existing.pixels
        val before = LinkedHashMap<TileKey, Tile?>(); val after = LinkedHashMap<TileKey, Tile?>()
        for ((k, t) in acc.entries()) {
            before[k] = g.get(k)?.copy()
            after[k] = over(g.get(k), t)
        }
        val cmd = TilePatchCommand(TilePatch(existing.id, before, after), "Stamp")
        cmd.apply(doc)
        return cmd
    }

    /** Restores the layers as they were; nothing goes into history. */
    fun cancel() {
        if (!isOpen) return
        for (id in layers) {
            val layer = doc.findNode(id) as? RasterLayer ?: continue
            layer.pixels.clear()
            applyTiles(layer.pixels, originals.getValue(id))
        }
        isOpen = false
        doc.touch()
    }

    /** The floating selection mask after [mapping], or null when there is no mask in the session. */
    fun transformedMask(mapping: Mapping, mode: Resample): MaskGrid? {
        val g = maskGrid ?: return null
        val res = render(g, mapping, if (mode == Resample.NEAREST) Resample.NEAREST else Resample.BILINEAR)
        val out = MaskGrid(doc.width, doc.height)
        for ((k, t) in res) {
            val m = MaskTile()
            var any = false
            for (i in 0 until Tile.SIZE * Tile.SIZE) {
                val v = (t[i * 4 + 3].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                if (v != 0) { m.data[i] = v.toByte(); any = true }
            }
            if (any) out.put(k, m)
        }
        return out
    }

    private fun maskToGrid(m: MaskGrid): TileGrid {
        val g = TileGrid(m.width, m.height)
        for ((k, t) in m.entries()) {
            if (t.isEmpty()) continue
            val o = Tile()
            for (i in 0 until Tile.SIZE * Tile.SIZE) {
                val v = (t.data[i].toInt() and 0xFF) / 255f
                if (v > 0f) o.write(i * 4, v, v, v, v)
            }
            g.put(k, o)
        }
        return g
    }

    private fun over(dst: Tile?, src: Tile?): Tile? {
        if (src == null) return dst?.copy()
        if (dst == null) return src.copy()
        val out = dst.copy()
        val s = FloatArray(4); val d = FloatArray(4)
        var i = 0
        while (i < Tile.CHANNELS) {
            src.read(i, s)
            if (s[3] > 0f) {
                dst.read(i, d)
                val k = 1f - s[3]
                out.write(i, s[0] + d[0] * k, s[1] + d[1] * k, s[2] + d[2] * k, s[3] + d[3] * k)
            }
            i += 4
        }
        return out
    }

    /** Restores the active node after [AddLayer] made the stamps layer active. */
    private class KeepActive(val id: String?) : Command {
        override val name = "Stamp"
        override fun apply(doc: Document) { if (id != null && doc.findNode(id) != null) doc.activeId = id }
        override fun revert(doc: Document) {}
        override val byteSize: Long get() = 16L
    }
}
