package app.painter.core.video

/**
 * RGB → I420 conversion used by the video export (spec/VIDEO-MODE.md §6.3).
 *
 * Color is BT.709 limited range: luma 16–235 and chroma 16–240. The integer coefficients approximate the
 * 8-bit BT.709 matrix; all inputs are straight (non-premultiplied) sRGB bytes and the alpha-over-paper
 * compositing happens before this conversion. The timelapse encoder keeps its BT.601 conversion and is not
 * affected by this file.
 */
object Yuv {

    const val Y_MIN = 16
    const val Y_MAX = 235
    const val CHROMA_MIN = 16
    const val CHROMA_MAX = 240
    const val CHROMA_MID = 128

    /** BT.709 limited-range luma of straight sRGB bytes. */
    fun luma(r: Int, g: Int, b: Int): Int =
        (((47 * r + 157 * g + 16 * b + 128) shr 8) + Y_MIN).coerceIn(Y_MIN, Y_MAX)

    /** BT.709 limited-range Cb of straight sRGB bytes. */
    fun chromaU(r: Int, g: Int, b: Int): Int =
        (((-26 * r - 87 * g + 113 * b + 128) shr 8) + CHROMA_MID).coerceIn(CHROMA_MIN, CHROMA_MAX)

    /** BT.709 limited-range Cr of straight sRGB bytes. */
    fun chromaV(r: Int, g: Int, b: Int): Int =
        (((112 * r - 102 * g - 10 * b + 128) shr 8) + CHROMA_MID).coerceIn(CHROMA_MIN, CHROMA_MAX)

    /**
     * Reusable I420 (planar YUV 4:2:0) frame. Chroma planes hold `ceil(width / 2) × ceil(height / 2)` samples,
     * one per 2 × 2 block. [yRowStride] and [uvRowStride] may include padding; the padding bytes stay untouched
     * and [toI420] writes the compact planes used by a plain input buffer.
     */
    class I420Frame(
        val width: Int,
        val height: Int,
        val yRowStride: Int = width,
        val uvRowStride: Int = (width + 1) / 2,
    ) {
        val chromaWidth: Int = (width + 1) / 2
        val chromaHeight: Int = (height + 1) / 2

        init {
            require(width > 0 && height > 0) { "empty YUV frame" }
            require(yRowStride >= width) { "luma stride $yRowStride is shorter than $width" }
            require(uvRowStride >= chromaWidth) { "chroma stride $uvRowStride is shorter than $chromaWidth" }
        }

        val y = ByteArray(yRowStride * height)
        val u = ByteArray(uvRowStride * chromaHeight)
        val v = ByteArray(uvRowStride * chromaHeight)

        fun setLuma(x: Int, row: Int, value: Int) {
            y[row * yRowStride + x] = value.coerceIn(0, 255).toByte()
        }

        fun setChroma(cx: Int, cy: Int, uValue: Int, vValue: Int) {
            val index = cy * uvRowStride + cx
            u[index] = uValue.coerceIn(0, 255).toByte()
            v[index] = vValue.coerceIn(0, 255).toByte()
        }

        /** Writes one pixel and, on the even row/column of a block, its chroma from the same color. */
        fun setRgb(x: Int, row: Int, r: Int, g: Int, b: Int) {
            setLuma(x, row, Yuv.luma(r, g, b))
            if (x % 2 == 0 && row % 2 == 0) {
                setChroma(x / 2, row / 2, Yuv.chromaU(r, g, b), Yuv.chromaV(r, g, b))
            }
        }

        /** Compact I420 bytes: full luma followed by U and V without row padding. */
        fun toI420(): ByteArray {
            val out = ByteArray(width * height + 2 * chromaWidth * chromaHeight)
            var dst = 0
            for (row in 0 until height) {
                System.arraycopy(y, row * yRowStride, out, dst, width)
                dst += width
            }
            for (row in 0 until chromaHeight) {
                System.arraycopy(u, row * uvRowStride, out, dst, chromaWidth)
                dst += chromaWidth
            }
            for (row in 0 until chromaHeight) {
                System.arraycopy(v, row * uvRowStride, out, dst, chromaWidth)
                dst += chromaWidth
            }
            return out
        }
    }
}
