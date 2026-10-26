package app.painter.core.raster

/**
 * A square block of premultiplied RGBA pixels. Layout: row-major, 4 channels per pixel (r, g, b, a),
 * values nominally 0..1, addressed by channel index ([index] + 0..3).
 *
 * Storage is half floats (8 B/px) by default; [precise] tiles keep float32 (16 B/px) and are meant for
 * short-lived accumulation buffers such as the stroke buffer, where tiny low-flow increments would be
 * lost to half rounding.
 */
class Tile private constructor(private val h: ShortArray?, private val f: FloatArray?) {

    constructor(precise: Boolean = false) : this(
        if (precise) null else ShortArray(CHANNELS),
        if (precise) FloatArray(CHANNELS) else null,
    )

    val precise: Boolean get() = f != null

    /** Bumped on every mutation; renderers compare it to know when to re-upload. */
    var version: Long = 0
        private set

    fun markDirty() { version++ }

    operator fun get(i: Int): Float = f?.get(i) ?: Half.toFloat(h!![i])

    operator fun set(i: Int, v: Float) {
        if (f != null) f[i] = v else h!![i] = Half.fromFloat(v)
    }

    /** Reads pixel channels at [i] (an [index]) into [out] at [off]. */
    fun read(i: Int, out: FloatArray, off: Int = 0) {
        if (f != null) {
            System.arraycopy(f, i, out, off, 4)
        } else {
            val s = h!!
            out[off] = Half.toFloat(s[i]); out[off + 1] = Half.toFloat(s[i + 1])
            out[off + 2] = Half.toFloat(s[i + 2]); out[off + 3] = Half.toFloat(s[i + 3])
        }
    }

    /** Writes pixel channels at [i] (an [index]) from [src] at [off]. */
    fun write(i: Int, src: FloatArray, off: Int = 0) = write(i, src[off], src[off + 1], src[off + 2], src[off + 3])

    fun write(i: Int, r: Float, g: Float, b: Float, a: Float) {
        if (f != null) {
            f[i] = r; f[i + 1] = g; f[i + 2] = b; f[i + 3] = a
        } else {
            val s = h!!
            s[i] = Half.fromFloat(r); s[i + 1] = Half.fromFloat(g)
            s[i + 2] = Half.fromFloat(b); s[i + 3] = Half.fromFloat(a)
        }
    }

    /** Multiplies every channel by [k]. */
    fun scale(k: Float) {
        if (f != null) for (i in f.indices) f[i] *= k
        else { val s = h!!; for (i in s.indices) s[i] = Half.fromFloat(Half.toFloat(s[i]) * k) }
    }

    fun copy(): Tile = Tile(h?.copyOf(), f?.copyOf()).also { it.version = version }

    /** A copy with the requested storage precision. */
    fun copy(precise: Boolean): Tile {
        if (precise == this.precise) return copy()
        val t = Tile(precise)
        for (i in 0 until CHANNELS) t[i] = get(i)
        t.version = version
        return t
    }

    fun isEmpty(): Boolean {
        var i = 3
        if (f != null) {
            while (i < CHANNELS) { if (f[i] != 0f) return false; i += 4 }
        } else {
            val s = h!!
            // ±0 in binary16 is 0x0000 / 0x8000.
            while (i < CHANNELS) { if (s[i].toInt() and 0x7FFF != 0) return false; i += 4 }
        }
        return true
    }

    fun clear() {
        f?.fill(0f)
        h?.fill(0)
        markDirty()
    }

    /** Copies all channels as binary16 into [out] (size [CHANNELS]). */
    fun copyHalvesTo(out: ShortArray) {
        if (h != null) h.copyInto(out) else for (i in 0 until CHANNELS) out[i] = Half.fromFloat(f!![i])
    }

    /** Overwrites all channels from binary16 [src] (size [CHANNELS]). */
    fun setHalves(src: ShortArray) {
        if (h != null) src.copyInto(h) else for (i in 0 until CHANNELS) f!![i] = Half.toFloat(src[i])
        markDirty()
    }

    /** All channels as float32 (a copy). */
    fun toFloats(): FloatArray = f?.copyOf() ?: FloatArray(CHANNELS) { Half.toFloat(h!![it]) }

    /** Same pixel values, regardless of version. Tiles of different precision compare by float value. */
    fun contentEquals(o: Tile): Boolean = when {
        h != null && o.h != null -> h.contentEquals(o.h)
        f != null && o.f != null -> f.contentEquals(o.f)
        else -> toFloats().contentEquals(o.toFloats())
    }

    fun contentHash(): Int = h?.contentHashCode() ?: f!!.contentHashCode()

    /** Bytes of pixel storage. */
    val byteSize: Long get() = if (f != null) CHANNELS * 4L else CHANNELS * 2L

    fun index(x: Int, y: Int): Int = (y * SIZE + x) * 4

    companion object {
        const val SIZE = 256
        const val SHIFT = 8
        const val MASK = SIZE - 1
        const val CHANNELS = SIZE * SIZE * 4

        /** A half-storage tile holding [halves] (taken over, not copied). */
        fun ofHalves(halves: ShortArray): Tile {
            require(halves.size == CHANNELS)
            return Tile(halves, null)
        }
    }
}

/** Single-channel 8-bit tile used by masks and selections (0 = hidden/unselected, 255 = full). */
class MaskTile(val data: ByteArray = ByteArray(Tile.SIZE * Tile.SIZE)) {
    init { require(data.size == Tile.SIZE * Tile.SIZE) }
    var version: Long = 0
        private set
    fun markDirty() { version++ }
    fun copy(): MaskTile = MaskTile(data.copyOf()).also { it.version = version }
    /** Every pixel equals [v]. */
    fun isUniform(v: Byte): Boolean { for (b in data) if (b != v) return false; return true }
    fun isEmpty(): Boolean = isUniform(0)
    fun isFull(): Boolean = isUniform(-1)
    fun fill(v: Byte) { data.fill(v); markDirty() }
    fun contentEquals(o: MaskTile): Boolean = data.contentEquals(o.data)
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
