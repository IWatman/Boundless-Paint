package app.painter.core.raster

/** Dense 1-bit-per-pixel plane for fill regions and wand results (4096² = 2 MiB). */
class BitPlane(val width: Int, val height: Int) {
    private val stride = (width + 63) ushr 6
    private val bits = LongArray(stride * height)

    operator fun get(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        return bits[y * stride + (x ushr 6)] and (1L shl (x and 63)) != 0L
    }

    operator fun set(x: Int, y: Int, v: Boolean) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val i = y * stride + (x ushr 6)
        val m = 1L shl (x and 63)
        bits[i] = if (v) bits[i] or m else bits[i] and m.inv()
    }

    fun clear() = bits.fill(0L)

    fun or(o: BitPlane) { check(o); for (i in bits.indices) bits[i] = bits[i] or o.bits[i] }
    fun and(o: BitPlane) { check(o); for (i in bits.indices) bits[i] = bits[i] and o.bits[i] }
    fun andNot(o: BitPlane) { check(o); for (i in bits.indices) bits[i] = bits[i] and o.bits[i].inv() }

    fun count(): Long = bits.sumOf { java.lang.Long.bitCount(it).toLong() }

    /** Bounding rect of set bits, or null when none are set. */
    fun bounds(): IntRect? {
        var x0 = Int.MAX_VALUE; var y0 = -1; var x1 = -1; var y1 = -1
        for (y in 0 until height) {
            val row = y * stride
            for (w in 0 until stride) {
                val v = bits[row + w]
                if (v == 0L) continue
                if (y0 < 0) y0 = y
                y1 = y + 1
                val lo = (w shl 6) + java.lang.Long.numberOfTrailingZeros(v)
                val hi = (w shl 6) + 63 - java.lang.Long.numberOfLeadingZeros(v)
                if (lo < x0) x0 = lo
                if (hi + 1 > x1) x1 = hi + 1
            }
        }
        return if (y0 < 0) null else IntRect(x0, y0, minOf(x1, width), y1)
    }

    fun copy(): BitPlane = BitPlane(width, height).also { bits.copyInto(it.bits) }

    private fun check(o: BitPlane) = require(o.width == width && o.height == height)
}
