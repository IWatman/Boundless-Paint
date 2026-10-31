package app.painter.core.stroke

import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch

/**
 * Recycles float32 tiles (1 MB each) for stroke buffers and working copies. At most [limit] free tiles are
 * kept (spec/tools/m2-brush.md §0: 96). Thread-safe.
 */
class TilePool(val limit: Int = DEFAULT_LIMIT) {
    private val free = ArrayDeque<Tile>()

    /** Number of free tiles currently retained. */
    val retained: Int get() = synchronized(free) { free.size }

    /** A cleared precise tile. */
    fun obtain(): Tile {
        val t = synchronized(free) { free.removeLastOrNull() } ?: return Tile(precise = true)
        t.clear()
        return t
    }

    /** Returns [t] to the pool; half tiles and overflow are dropped. */
    fun release(t: Tile) {
        if (!t.precise) return
        synchronized(free) { if (free.size < limit) free.addLast(t) }
    }

    /** Drops the retained free tiles, e.g. just before asking the heap for a large stroke buffer. */
    fun clearFree() {
        synchronized(free) { free.clear() }
    }

    companion object {
        /**
         * Free float tiles kept for reuse. One tile is 1 MB; 96 of them (the old value) plus the 512 MB history
         * budget could exceed the whole Android heap. 32 keeps reuse useful while leaving room for a stroke.
         */
        const val DEFAULT_LIMIT = 32

        /** Process-wide pool used by [StrokeEngine]. */
        val shared = TilePool()
    }
}

/** Tiles a [DabKernel] writes to. [prepare] runs on one thread before the parallel render pass. */
interface TileAccess {
    val width: Int
    val height: Int

    /** The tile at [key] ready for writing, or null to skip it. Missing tiles are created only when [create]. */
    fun prepare(key: TileKey, create: Boolean): Tile?
}

/** The stroke buffer: a sparse float grid filled from [pool]; prepared keys are reported to [onTouch]. */
class BufferAccess(
    val grid: TileGrid,
    private val pool: TilePool,
    private val onTouch: (TileKey) -> Unit,
) : TileAccess {
    override val width: Int get() = grid.width
    override val height: Int get() = grid.height

    override fun prepare(key: TileKey, create: Boolean): Tile? {
        val t = grid.get(key) ?: if (create) pool.obtain().also { grid.put(key, it) } else return null
        onTouch(key)
        return t
    }

    /** Empties the grid and returns its tiles to the pool. */
    fun releaseAll() {
        for ((_, t) in grid.entries()) pool.release(t)
        grid.clear()
    }
}

/**
 * Float working copies of the touched tiles of a half-float layer (spec/tools/m2-brush.md §0).
 *
 * On first touch the layer tile is decoded into a pooled float tile which replaces it in [grid] (so previews see
 * live pixels); the original half tile is kept untouched as the before-snapshot. [commit] encodes the working
 * tiles back to half and returns the patch; [rollback] puts the originals back.
 */
class LayerWorkingSet(
    val grid: TileGrid,
    private val pool: TilePool = TilePool.shared,
    private val onTouch: (TileKey) -> Unit = {},
) : TileAccess {
    private val before = LinkedHashMap<TileKey, Tile?>()
    private val work = HashMap<TileKey, Tile>()
    private val halves = ShortArray(Tile.CHANNELS)

    override val width: Int get() = grid.width
    override val height: Int get() = grid.height

    /** Keys touched so far. */
    val keys: Set<TileKey> get() = before.keys

    val isEmpty: Boolean get() = before.isEmpty()

    override fun prepare(key: TileKey, create: Boolean): Tile? {
        work[key]?.let { onTouch(key); return it }
        val original = grid.get(key)
        if (original == null && !create) return null
        val w = pool.obtain()
        if (original != null) decode(original, w)
        before[key] = original
        work[key] = w
        grid.put(key, w)
        onTouch(key)
        return w
    }

    /** Restores every working tile to its before-state (for re-rendering), keeping the set. */
    fun reset() {
        for ((key, w) in work) {
            val o = before[key]
            if (o != null) decode(o, w) else w.clear()
            w.markDirty()
            onTouch(key)
        }
    }

    /** Encodes the working tiles into the layer and returns the patch, or null if nothing was touched. */
    fun commit(layerId: String): TilePatch? {
        if (before.isEmpty()) return null
        val after = HashMap<TileKey, Tile?>(before.size * 2)
        for ((key, w) in work) {
            if (w.isEmpty()) {
                grid.put(key, null)
                after[key] = null
            } else {
                val hs = ShortArray(Tile.CHANNELS)
                w.copyHalvesTo(hs)
                val t = Tile.ofHalves(hs)
                t.markDirty()
                grid.put(key, t)
                after[key] = t.copy()
            }
            onTouch(key)
            pool.release(w)
        }
        val patch = TilePatch(layerId, LinkedHashMap(before), after)
        before.clear()
        work.clear()
        return patch
    }

    /** Puts the original tiles back and drops the working copies. */
    fun rollback() {
        for ((key, o) in before) {
            grid.put(key, o)
            o?.markDirty()
            onTouch(key)
        }
        for (w in work.values) pool.release(w)
        before.clear()
        work.clear()
    }

    private fun decode(src: Tile, dst: Tile) {
        src.copyHalvesTo(halves)
        dst.setHalves(halves)
    }
}
