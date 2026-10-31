package app.painter.core.io

import app.painter.core.raster.TileGrid
import java.io.ByteArrayOutputStream

/**
 * Minimal CMYK TIFF writer for the M4 CMYK export: 8-bit, four inks, one strip, uncompressed and little-endian.
 * TIFF is used because Android's Bitmap codecs cannot write CMYK; the file opens in Photoshop/GIMP/Krita.
 */
object TiffCmyk {
    fun encode(grid: TileGrid, width: Int, height: Int): ByteArray {
        val pixels = ByteArray(width * height * 4)
        val px = FloatArray(4)
        var o = 0
        for (y in 0 until height) for (x in 0 until width) {
            grid.getPixel(x, y, px)
            val a = px[3].coerceIn(0f, 1f)
            val r = if (a > 0f) (px[0] / a).coerceIn(0f, 1f) else 0f
            val g = if (a > 0f) (px[1] / a).coerceIn(0f, 1f) else 0f
            val b = if (a > 0f) (px[2] / a).coerceIn(0f, 1f) else 0f
            // Composite a transparent pixel onto white paper; CMYK has no alpha channel.
            val rr = r * a + (1f - a)
            val gg = g * a + (1f - a)
            val bb = b * a + (1f - a)
            val k = 1f - maxOf(rr, gg, bb)
            val inv = 1f - k
            val c = if (inv > 1e-5f) (1f - rr - k) / inv else 0f
            val m = if (inv > 1e-5f) (1f - gg - k) / inv else 0f
            val yy = if (inv > 1e-5f) (1f - bb - k) / inv else 0f
            pixels[o++] = toByte(c)
            pixels[o++] = toByte(m)
            pixels[o++] = toByte(yy)
            pixels[o++] = toByte(k)
        }

        // IFD: 9 entries, then the BitsPerSample array, then the strip.
        val ifdOffset = 8
        val entryCount = 9
        val ifdSize = 2 + entryCount * 12 + 4
        val bitsOffset = ifdOffset + ifdSize
        val bitsSize = 8
        val stripOffset = bitsOffset + bitsSize
        val out = ByteArrayOutputStream()
        fun short(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun int(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF) }
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            short(tag); short(type); int(count)
            if (type == 3 && count == 1) { short(value); short(0) } else int(value)
        }
        out.write('I'.code); out.write('I'.code)
        short(42)
        int(ifdOffset)
        short(entryCount)
        entry(256, 4, 1, width)           // ImageWidth
        entry(257, 4, 1, height)          // ImageLength
        entry(258, 3, 4, bitsOffset)      // BitsPerSample
        entry(259, 3, 1, 1)               // Compression: none
        entry(262, 3, 1, 5)               // Photometric: separated
        entry(273, 4, 1, stripOffset)     // StripOffsets
        entry(277, 3, 1, 4)               // SamplesPerPixel
        entry(278, 4, 1, height)          // RowsPerStrip
        entry(279, 4, 1, pixels.size)     // StripByteCounts
        int(0)                             // no next IFD
        // BitsPerSample values are 8,8,8,8; PlanarConfiguration defaults to 1, so the tag is optional.
        repeat(4) { short(8) }
        out.write(pixels)
        return out.toByteArray()
    }

    private fun toByte(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
}
