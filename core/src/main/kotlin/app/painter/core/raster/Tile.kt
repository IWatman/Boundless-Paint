package app.painter.core.raster

/**
 * A square block of premultiplied RGBA pixels, stored as floats on the CPU.
 * Layout: row-major, 4 floats per pixel (r, g, b, a), values nominally 0..1.
 */
class Tile(val data: FloatArray = FloatArray(SIZE * SIZE * 4)) {
    init { require(data.size == SIZE * SIZE * 4) }

    /** Bumped on every mutation; renderers compare it to know when to re-upload. */
    var version: Long = 0
        private set

    fun markDirty() { version++ }

    fun copy(): Tile = Tile(data.copyOf()).also { it.version = version }

    fun isEmpty(): Boolean {
        var i = 3
        while (i < data.size) { if (data[i] != 0f) return false; i += 4 }
        return true
    }

    fun clear() { data.fill(0f); markDirty() }

    fun index(x: Int, y: Int): Int = (y * SIZE + x) * 4

    companion object {
        const val SIZE = 256
        const val SHIFT = 8
        const val MASK = SIZE - 1
    }
}

/** Single-channel 8-bit tile used by masks and selections. */
class MaskTile(val data: ByteArray = ByteArray(Tile.SIZE * Tile.SIZE)) {
    var version: Long = 0
        private set
    fun markDirty() { version++ }
    fun copy(): MaskTile = MaskTile(data.copyOf()).also { it.version = version }
    fun get(x: Int, y: Int): Float = (data[y * Tile.SIZE + x].toInt() and 0xFF) / 255f
    fun set(x: Int, y: Int, v: Float) {
        data[y * Tile.SIZE + x] = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
    }
}

/** Immutable integer key of a tile within its grid. */
@JvmInline
value class TileKey(val packed: Long) {
    constructor(tx: Int, ty: Int) : this((tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL))
    val tx: Int get() = (packed shr 32).toInt()
    val ty: Int get() = packed.toInt()
    override fun toString() = "TileKey($tx,$ty)"
}
