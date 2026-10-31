package app.painter.render

import app.painter.core.adjust.AdjustMath
import app.painter.core.adjust.AdjustParams
import app.painter.core.blend.BlendMode
import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.Document
import app.painter.core.doc.FilterLayer
import app.painter.core.doc.GroupNode
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey

/** Immutable copy of a node's display-relevant properties. */
internal data class SnapNode(
    val id: String,
    val isGroup: Boolean,
    /** Live filter layer: replaces the root siblings below it with its cached filtered pixels. */
    val isFilter: Boolean,
    val visible: Boolean,
    val opacity: Float,
    val mode: BlendMode,
    val clipped: Boolean,
    val alphaLock: Boolean,
    val maskPresent: Boolean,
    val maskEnabled: Boolean,
    val maskDensity: Float,
    /** Raw coverage (0..255) of pixels whose mask tile is missing. */
    val maskDefault: Int,
    /** Prepared correction of an adjustment layer; null for every other node kind. */
    val adjust: SnapAdjust?,
    val children: List<SnapNode>,
)

/**
 * Main-thread copy of one adjustment layer's correction: the GPU kind index, the packed uniforms and the
 * LUT of [app.painter.core.adjust.AdjustMath.prepare]. Compared by content so that an unchanged layer keeps
 * the scene snapshot (and its GPU LUT texture) intact.
 */
internal class SnapAdjust(val kindIndex: Int, val uniforms: FloatArray, val lut: FloatArray?) {
    /** False when the correction cannot change a pixel (unknown kind). */
    val isActive: Boolean get() = kindIndex >= 0

    override fun equals(other: Any?): Boolean =
        other is SnapAdjust && other.kindIndex == kindIndex && other.uniforms.contentEquals(uniforms) &&
            (other.lut == null) == (lut == null) && (lut == null || other.lut!!.contentEquals(lut))

    override fun hashCode(): Int = 31 * (31 * kindIndex + uniforms.contentHashCode()) + (lut?.contentHashCode() ?: 0)
}

/** Immutable copy of everything except pixels that affects the composite. */
internal data class SceneSnapshot(
    val paperR: Float,
    val paperG: Float,
    val paperB: Float,
    val paperA: Float,
    val paperVisible: Boolean,
    /** Document toggle "gamma-correct": blend layers in linear light. */
    val gamma: Boolean,
    val root: SnapNode,
)

/** One tile's pixels copied off the main thread; [data] == null deletes the GL tile. */
internal class TileUpload(val layerId: String, val tx: Int, val ty: Int, val data: ShortArray?)

/** One mask tile's coverage copied off the main thread; [data] == null deletes the GL tile. */
internal class MaskUpload(val nodeId: String, val tx: Int, val ty: Int, val data: ByteArray?)

/** Changes collected on the main thread and applied on the GL thread. */
internal class RenderBatch(
    val scene: SceneSnapshot?,
    val uploads: List<TileUpload>,
    val maskUploads: List<MaskUpload>,
    /** Nodes whose complete mask texture set must be dropped. */
    val removedMasks: List<String>,
    val removedLayers: List<String>,
    val dirtyAll: Boolean,
    val dirtyKeys: LongArray,
)

/** Thread-safe pool of tile-sized binary16 arrays used as upload staging. */
internal class StagingPool(private val maxRetained: Int = 64) {
    private val free = ArrayDeque<ShortArray>()

    fun obtain(): ShortArray = synchronized(free) { free.removeLastOrNull() } ?: ShortArray(TILE_FLOATS)

    fun release(a: ShortArray) {
        synchronized(free) { if (free.size < maxRetained) free.addLast(a) }
    }

    companion object {
        const val TILE_FLOATS = Tile.SIZE * Tile.SIZE * 4
    }
}

/**
 * Main-thread side of the renderer: remembers which tile instances/versions were already sent
 * to the GL thread and produces [RenderBatch]es containing copies of changed tiles only.
 */
internal class DocumentSync(private val pool: StagingPool) {
    private class Stamp(var tile: Tile, var version: Long)

    private class MaskStamp(var tile: MaskTile, var version: Long)

    /** Sent mask tiles of one node; [grid] identifies the mask whose tiles were copied. */
    private class MaskStamps(var grid: MaskGrid) {
        val tiles = HashMap<TileKey, MaskStamp>()
    }

    var doc: Document? = null
        private set
    private val sent = HashMap<String, HashMap<TileKey, Stamp>>()
    private val maskSent = HashMap<String, MaskStamps>()
    private var lastScene: SceneSnapshot? = null

    /** Switches to [doc] and forgets everything that was sent. */
    fun reset(doc: Document?) {
        this.doc = doc
        sent.clear()
        maskSent.clear()
        lastScene = null
    }

    /** Forgets sent state so that the next [collect] re-sends all tiles (e.g. after GL context loss). */
    fun forgetSent() {
        sent.clear()
        maskSent.clear()
        lastScene = null
    }

    /** Collects changes of layer [layerId] (null = all) restricted to [keys] (null = all tiles). */
    fun collect(layerId: String?, keys: Collection<TileKey>?): RenderBatch? {
        val d = doc ?: return null
        val scene = snapshotScene(d)
        val sceneChanged = scene != lastScene
        lastScene = scene

        val layers = HashMap<String, RasterLayer>()
        val nodes = HashMap<String, Node>()
        d.root.walk { nodes[it.id] = it; if (it is RasterLayer) layers[it.id] = it }

        val removed = ArrayList<String>()
        val it = sent.keys.iterator()
        while (it.hasNext()) {
            val id = it.next()
            if (id !in layers) { removed.add(id); it.remove() }
        }

        val uploads = ArrayList<TileUpload>()
        val maskUploads = ArrayList<MaskUpload>()
        val removedMasks = ArrayList<String>()
        val dirty = HashSet<Long>()
        syncMasks(nodes, maskUploads, removedMasks, dirty)
        val targets: Collection<RasterLayer> =
            if (layerId == null) layers.values else listOfNotNull(layers[layerId])
        for (layer in targets) {
            val stamps = sent.getOrPut(layer.id) { HashMap() }
            val grid = layer.pixels
            if (keys == null) {
                for ((k, t) in grid.entries()) {
                    if (syncTile(layer.id, grid, k, t, stamps, uploads)) dirty.add(k.packed)
                }
                val si = stamps.keys.iterator()
                while (si.hasNext()) {
                    val k = si.next()
                    if (grid.get(k) == null) {
                        si.remove()
                        if (grid.isInBounds(k.tx, k.ty)) {
                            uploads.add(TileUpload(layer.id, k.tx, k.ty, null))
                            dirty.add(k.packed)
                        }
                    }
                }
            } else {
                for (k in keys) {
                    val t = grid.get(k)
                    if (t == null) {
                        if (stamps.remove(k) != null && grid.isInBounds(k.tx, k.ty)) {
                            uploads.add(TileUpload(layer.id, k.tx, k.ty, null))
                        }
                    } else {
                        syncTile(layer.id, grid, k, t, stamps, uploads)
                    }
                    dirty.add(k.packed)
                }
            }
        }
        val dirtyAll = sceneChanged || removed.isNotEmpty() || removedMasks.isNotEmpty() ||
            (layerId == null && keys == null)
        return RenderBatch(
            scene = if (sceneChanged) scene else null,
            uploads = uploads,
            maskUploads = maskUploads,
            removedMasks = removedMasks,
            removedLayers = removed,
            dirtyAll = dirtyAll,
            dirtyKeys = if (dirtyAll) LongArray(0) else dirty.toLongArray(),
        )
    }

    /** Copies dirty mask tiles of every node into [out]; vanished masks go to [removed]. */
    private fun syncMasks(
        nodes: Map<String, Node>,
        out: MutableList<MaskUpload>,
        removed: MutableList<String>,
        dirty: MutableSet<Long>,
    ) {
        val it = maskSent.keys.iterator()
        while (it.hasNext()) {
            val id = it.next()
            if (nodes[id]?.mask == null) { removed.add(id); it.remove() }
        }
        for ((id, node) in nodes) {
            val mask = node.mask ?: continue
            val grid = mask.tiles
            val stamps = maskStamps(id, grid, out)
            for ((k, t) in grid.entries()) {
                if (!syncMaskTile(id, grid, k, t, stamps.tiles, out)) continue
                if (mask.enabled) dirty.add(k.packed)
            }
            val stale = stamps.tiles.keys.iterator()
            while (stale.hasNext()) {
                val k = stale.next()
                if (grid.get(k) != null) continue
                stale.remove()
                if (!grid.isInBounds(k.tx, k.ty)) continue
                out.add(MaskUpload(id, k.tx, k.ty, null))
                if (mask.enabled) dirty.add(k.packed)
            }
        }
    }

    /** Sent-tile bookkeeping of [id]'s mask, restarted (dropping GL tiles) when the mask grid changed. */
    private fun maskStamps(id: String, grid: MaskGrid, out: MutableList<MaskUpload>): MaskStamps {
        val st = maskSent[id]
        if (st == null) return MaskStamps(grid).also { maskSent[id] = it }
        if (st.grid === grid) return st
        for (k in st.tiles.keys) out.add(MaskUpload(id, k.tx, k.ty, null))
        st.grid = grid
        st.tiles.clear()
        return st
    }

    private fun syncMaskTile(
        nodeId: String,
        grid: MaskGrid,
        k: TileKey,
        t: MaskTile,
        stamps: HashMap<TileKey, MaskStamp>,
        out: MutableList<MaskUpload>,
    ): Boolean {
        if (!grid.isInBounds(k.tx, k.ty)) return false
        val s = stamps[k]
        if (s != null && s.tile === t && s.version == t.version) return false
        if (s == null) stamps[k] = MaskStamp(t, t.version) else { s.tile = t; s.version = t.version }
        out.add(MaskUpload(nodeId, k.tx, k.ty, t.data.copyOf()))
        return true
    }

    private fun syncTile(
        layerId: String,
        grid: TileGrid,
        k: TileKey,
        t: Tile,
        stamps: HashMap<TileKey, Stamp>,
        out: MutableList<TileUpload>,
    ): Boolean {
        if (!grid.isInBounds(k.tx, k.ty)) return false
        val s = stamps[k]
        if (s != null && s.tile === t && s.version == t.version) return false
        if (s == null) stamps[k] = Stamp(t, t.version) else { s.tile = t; s.version = t.version }
        out.add(TileUpload(layerId, k.tx, k.ty, copyTile(t)))
        return true
    }

    /** Copies [t]'s pixels into a pooled staging array. */
    fun copyTile(t: Tile): ShortArray {
        val a = pool.obtain()
        t.copyHalvesTo(a)
        return a
    }

    companion object {
        fun snapshotScene(d: Document): SceneSnapshot {
            val c = d.paper.color
            return SceneSnapshot(
                paperR = c.r * c.a,
                paperG = c.g * c.a,
                paperB = c.b * c.a,
                paperA = c.a,
                paperVisible = d.paper.visible,
                gamma = d.gammaCorrect,
                root = snapshot(d.root),
            )
        }

        private fun snapshot(n: Node): SnapNode {
            val m = n.mask
            return SnapNode(
                id = n.id,
                isGroup = n is GroupNode,
                isFilter = n is FilterLayer,
                visible = n.visible,
                opacity = n.opacity,
                mode = n.blendMode,
                clipped = n.clipped,
                alphaLock = (n as? RasterLayer)?.alphaLock ?: false,
                maskPresent = m != null,
                maskEnabled = m?.enabled ?: false,
                maskDensity = m?.density ?: 1f,
                maskDefault = m?.tiles?.defaultValue?.toInt()?.and(0xFF) ?: 255,
                adjust = (n as? AdjustmentLayer)?.let { prep(it.params) },
                children = if (n is GroupNode) n.children.map(::snapshot) else emptyList(),
            )
        }

        /** [AdjustMath.prepare] of [p], or null when the correction is a no-op. */
        private fun prep(p: AdjustParams): SnapAdjust? =
            AdjustMath.prepare(p).let { if (it.isNoOp) null else SnapAdjust(it.kindIndex, it.uniforms, it.lut) }
    }
}
