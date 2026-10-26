package app.painter.render

import app.painter.core.blend.BlendMode
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey

/** Immutable copy of a node's display-relevant properties. */
internal data class SnapNode(
    val id: String,
    val isGroup: Boolean,
    val visible: Boolean,
    val opacity: Float,
    val mode: BlendMode,
    val clipped: Boolean,
    val alphaLock: Boolean,
    val children: List<SnapNode>,
)

/** Immutable copy of everything except pixels that affects the composite. */
internal data class SceneSnapshot(
    val paperR: Float,
    val paperG: Float,
    val paperB: Float,
    val paperA: Float,
    val paperVisible: Boolean,
    val root: SnapNode,
)

/** One tile's pixels copied off the main thread; [data] == null deletes the GL tile. */
internal class TileUpload(val layerId: String, val tx: Int, val ty: Int, val data: FloatArray?)

/** Changes collected on the main thread and applied on the GL thread. */
internal class RenderBatch(
    val scene: SceneSnapshot?,
    val uploads: List<TileUpload>,
    val removedLayers: List<String>,
    val dirtyAll: Boolean,
    val dirtyKeys: LongArray,
)

/** Thread-safe pool of tile-sized float arrays used as upload staging. */
internal class StagingPool(private val maxRetained: Int = 64) {
    private val free = ArrayDeque<FloatArray>()

    fun obtain(): FloatArray = synchronized(free) { free.removeLastOrNull() } ?: FloatArray(TILE_FLOATS)

    fun release(a: FloatArray) {
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

    var doc: Document? = null
        private set
    private val sent = HashMap<String, HashMap<TileKey, Stamp>>()
    private var lastScene: SceneSnapshot? = null

    /** Switches to [doc] and forgets everything that was sent. */
    fun reset(doc: Document?) {
        this.doc = doc
        sent.clear()
        lastScene = null
    }

    /** Forgets sent state so that the next [collect] re-sends all tiles (e.g. after GL context loss). */
    fun forgetSent() {
        sent.clear()
        lastScene = null
    }

    /** Collects changes of layer [layerId] (null = all) restricted to [keys] (null = all tiles). */
    fun collect(layerId: String?, keys: Collection<TileKey>?): RenderBatch? {
        val d = doc ?: return null
        val scene = snapshotScene(d)
        val sceneChanged = scene != lastScene
        lastScene = scene

        val layers = HashMap<String, RasterLayer>()
        d.root.walk { if (it is RasterLayer) layers[it.id] = it }

        val removed = ArrayList<String>()
        val it = sent.keys.iterator()
        while (it.hasNext()) {
            val id = it.next()
            if (id !in layers) { removed.add(id); it.remove() }
        }

        val uploads = ArrayList<TileUpload>()
        val dirty = HashSet<Long>()
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
        val dirtyAll = sceneChanged || removed.isNotEmpty() || (layerId == null && keys == null)
        return RenderBatch(
            scene = if (sceneChanged) scene else null,
            uploads = uploads,
            removedLayers = removed,
            dirtyAll = dirtyAll,
            dirtyKeys = if (dirtyAll) LongArray(0) else dirty.toLongArray(),
        )
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
    fun copyTile(t: Tile): FloatArray {
        val a = pool.obtain()
        t.data.copyInto(a)
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
                root = snapshot(d.root),
            )
        }

        private fun snapshot(n: Node): SnapNode = SnapNode(
            id = n.id,
            isGroup = n is GroupNode,
            visible = n.visible,
            opacity = n.opacity,
            mode = n.blendMode,
            clipped = n.clipped,
            alphaLock = (n as? RasterLayer)?.alphaLock ?: false,
            children = if (n is GroupNode) n.children.map(::snapshot) else emptyList(),
        )
    }
}
