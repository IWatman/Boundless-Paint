package app.painter.core.io

import app.painter.core.blend.BlendMode
import app.painter.core.blend.Compositor
import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.TileGrid
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal Photoshop PSD reader/writer (spec/file-formats.md "PSD (M3)", Adobe's public specification).
 *
 * Export: 8-bit RGB, one layer per raster layer (bottom-to-top), opacity, blend mode, clipping, visibility and
 * the flattened composite. Groups and adjustment layers are flattened away; text and masks are not written yet.
 * Import: raster layers, names, opacity, blend mode, clipping and the composite, with the same clean-room subset.
 * RLE (PackBits) is used for every channel.
 */
object PsdIO {

    private const val SIG = "8BPS"
    private const val VERSION = 1
    private const val CHANNELS = 4
    private const val DEPTH = 8
    private const val COLOR_MODE_RGB = 3

    /** Writes [doc] as a PSD; [composite] is normally [Compositor.flatten]. */
    fun write(doc: Document, out: OutputStream, composite: TileGrid = Compositor.flatten(doc, includePaper = true)) {
        val buf = BeWriter(out)
        buf.ascii(SIG)
        buf.short(VERSION)
        buf.zeros(6)
        buf.short(CHANNELS)
        buf.int(doc.height)
        buf.int(doc.width)
        buf.short(DEPTH)
        buf.short(COLOR_MODE_RGB)
        buf.int(0) // colour mode data
        buf.int(0) // image resources

        val layers = ArrayList<RasterLayer>()
        doc.root.walk { if (it is RasterLayer) layers.add(it) }
        val info = ByteArrayOutputStream()
        val iw = BeWriter(info)
        iw.short(layers.size)
        val channelData = ArrayList<ByteArray>()
        for (layer in layers) {
            writeLayerRecord(iw, layer, doc.width, doc.height, channelData)
        }
        for (data in channelData) iw.bytes(data)
        val infoBytes = info.toByteArray()
        val flat = ByteArrayOutputStream()
        val fw = BeWriter(flat)
        fw.int(infoBytes.size)
        fw.bytes(infoBytes)
        fw.int(0) // global layer mask info
        val layerSection = flat.toByteArray()
        buf.int(layerSection.size)
        buf.bytes(layerSection)

        // Composite image: 4 channels, RLE. Each channel is a row-count table and then the packed rows.
        buf.short(1)
        for (ch in intArrayOf(0, 1, 2, 3)) {
            val bytes = rleRows(composite, ch, doc.width, doc.height)
            for (y in 0 until doc.height) {
                val count = ((bytes[y * 2].toInt() and 0xFF) shl 8) or (bytes[y * 2 + 1].toInt() and 0xFF)
                buf.short(count)
            }
            buf.bytes(bytes.copyOfRange(2 * doc.height, bytes.size))
        }
    }

    private fun writeLayerRecord(
        w: BeWriter,
        layer: RasterLayer,
        width: Int,
        height: Int,
        channelData: MutableList<ByteArray>,
    ) {
        w.int(0); w.int(0); w.int(height); w.int(width)
        w.short(CHANNELS)
        val ids = intArrayOf(-1, 0, 1, 2)
        val channels = ids.map { id -> id to channelBytes(layer.pixels, channelIndex(id), width, height) }
        println("write ${layer.name} a4=${FloatArray(4).also { layer.pixels.getPixel(4, 5, it) }.toList()} alpha=${channels[0].second.take(60).toList()}")
        for ((id, bytes) in channels) {
            w.short(id)
            w.int(bytes.size + 2)
        }
        w.ascii("8BIM")
        w.ascii(psdKey(layer.blendMode))
        w.byte((layer.opacity.coerceIn(0f, 1f) * 255).toInt())
        w.byte(if (layer.clipped) 1 else 0)
        var flags = 0
        if (layer.visible) flags = flags or 0x02
        w.byte(flags)
        w.byte(0)
        val extra = ByteArrayOutputStream()
        val ew = BeWriter(extra)
        ew.int(0) // mask data
        ew.int(0) // blending ranges
        ew.pascal(layer.name)
        val unicode = ByteArrayOutputStream()
        val uw = BeWriter(unicode)
        uw.int(layer.name.length)
        for (ch in layer.name) { uw.short(ch.code); uw.short(0) }
        ew.ascii("8BIM"); ew.ascii("luni"); ew.int(unicode.size()); ew.bytes(unicode.toByteArray())
        w.int(extra.size())
        w.bytes(extra.toByteArray())
        for ((_, bytes) in channels) channelData.add(byteArrayOf(0, 1) + bytes)
    }

    private fun channelIndex(id: Int): Int = when (id) {
        -1 -> 3
        0 -> 0
        1 -> 1
        else -> 2
    }

    private fun channelBytes(grid: TileGrid, channel: Int, width: Int, height: Int): ByteArray =
        rleRows(grid, channel, width, height)

    /** PackBits RLE: 2-byte row counts followed by each packed row. */
    private fun rleRows(grid: TileGrid, channel: Int, width: Int, height: Int): ByteArray {
        val rowData = ArrayList<ByteArray>(height)
        val px = FloatArray(4)
        for (y in 0 until height) {
            val raw = ByteArray(width)
            for (x in 0 until width) {
                grid.getPixel(x, y, px)
                val a = px[3].coerceIn(0f, 1f)
                val v = if (channel == 3) a else {
                    val straight = if (a > 0f) (px[channel] / a).coerceIn(0f, 1f) else 0f
                    straight
                }
                raw[x] = (v * 255f + 0.5f).toInt().toByte()
            }
            rowData.add(packBits(raw))
        }
        val out = ByteArrayOutputStream()
        for (row in rowData) {
            out.write((row.size ushr 8) and 0xFF); out.write(row.size and 0xFF)
        }
        for (row in rowData) out.write(row)
        return out.toByteArray()
    }

    /** PackBits: literal runs of up to 128 bytes and repeated runs of 3..128. */
    private fun packBits(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < raw.size) {
            var run = 1
            while (i + run < raw.size && run < 128 && raw[i + run] == raw[i]) run++
            if (run >= 3) {
                out.write(257 - run)
                out.write(raw[i].toInt())
                i += run
            } else {
                val start = i
                i++
                while (i < raw.size && i - start < 128) {
                    var r = 1
                    while (i + r < raw.size && r < 3 && raw[i + r] == raw[i]) r++
                    if (r >= 3) break
                    i++
                }
                val n = i - start
                out.write(n - 1)
                out.write(raw, start, n)
            }
        }
        return out.toByteArray()
    }

    /** Reads a PSD written by [write] or by Photoshop's raster subset. */
    fun read(input: InputStream): Document {
        val data = input.readBytes()
        val r = BeReader(data)
        if (String(r.bytes(4), Charsets.US_ASCII) != SIG) throw IOException("not a PSD")
        val version = r.short()
        if (version != 1 && version != 2) throw IOException("unsupported PSD version $version")
        r.zeros(6)
        val channels = r.short()
        val height = r.int()
        val width = r.int()
        val depth = r.short()
        val mode = r.short()
        if (width <= 0 || height <= 0 || width.toLong() * height > 64_000_000L) throw IOException("bad PSD size")
        if (depth != 8 || mode != COLOR_MODE_RGB) throw IOException("only 8-bit RGB PSD is supported")
        val colorLen = r.int(); r.skip(colorLen)
        val resourceLen = r.int(); r.skip(resourceLen)
        val doc = Document(width, height)
        doc.paper.visible = false
        val layerMaskLen = r.int()
        val layerEnd = r.pos + layerMaskLen
        if (layerMaskLen > 0) {
            val infoLen = r.int()
            val infoEnd = r.pos + infoLen
            val count = kotlin.math.abs(r.short())
            val records = ArrayList<Record>(count)
            repeat(count) { records.add(readRecord(r)) }
            for (rec in records) {
                val grid = TileGrid(width, height)
                for (ch in rec.channels) {
                    val compression = r.short()
                    val bytes = readChannel(r, compression, rec.rectW, rec.rectH)
                    if (ch.id == -1 || ch.id == 0 || ch.id == 1 || ch.id == 2) {
                        val target = when (ch.id) { -1 -> 3; 0 -> 0; 1 -> 1; else -> 2 }
                        for (y in 0 until rec.rectH) for (x in 0 until rec.rectW) {
                            val dx = rec.left + x
                            val dy = rec.top + y
                            if (dx !in 0 until width || dy !in 0 until height) continue
                            val v = (bytes[y * rec.rectW + x].toInt() and 0xFF) / 255f
                            val px = FloatArray(4).also { grid.getPixel(dx, dy, it) }
                            px[target] = v
                            grid.setPixel(dx, dy, px[0], px[1], px[2], px[3])
                        }
                    }
                }
                // Premultiply colour channels by alpha (they were stored straight).
                for (y in 0 until height) for (x in 0 until width) {
                    val px = FloatArray(4).also { grid.getPixel(x, y, it) }
                    if (px[3] != 1f) grid.setPixel(x, y, px[0] * px[3], px[1] * px[3], px[2] * px[3], px[3])
                }
                val layer = RasterLayer(app.painter.core.doc.newNodeId(), rec.name, grid)
                layer.opacity = rec.opacity
                layer.blendMode = rec.blend
                layer.clipped = rec.clipped
                layer.visible = rec.visible
                doc.root.add(layer)
                if (doc.activeId == null) doc.activeId = layer.id
            }
            r.pos = infoEnd
        }
        r.pos = layerEnd
        if (doc.root.children.isEmpty()) {
            // No layer records: use the composite as the only layer.
            r.short()
            val rows = IntArray(height * CHANNELS)
            for (i in rows.indices) rows[i] = r.short()
            val planes = Array(CHANNELS) { ByteArray(width * height) }
            for (ch in 0 until CHANNELS) for (y in 0 until height) {
                val n = rows[ch * height + y]
                val packed = r.bytes(n)
                unpackInto(packed, planes[ch], y * width, width)
            }
            val grid = TileGrid(width, height)
            for (y in 0 until height) for (x in 0 until width) {
                val a = (planes[3][y * width + x].toInt() and 0xFF) / 255f
                grid.setPixel(
                    x, y,
                    (planes[0][y * width + x].toInt() and 0xFF) / 255f * a,
                    (planes[1][y * width + x].toInt() and 0xFF) / 255f * a,
                    (planes[2][y * width + x].toInt() and 0xFF) / 255f * a,
                    a,
                )
            }
            val layer = RasterLayer(app.painter.core.doc.newNodeId(), "Layer 1", grid)
            doc.root.add(layer)
            doc.activeId = layer.id
        }
        return doc
    }

    private class Channel(val id: Int)
    private class Record(
        val top: Int, val left: Int, val bottom: Int, val right: Int,
        val channels: List<Channel>, val blend: BlendMode, val opacity: Float,
        val clipped: Boolean, val visible: Boolean, val name: String,
    ) {
        val rectW get() = (right - left).coerceAtLeast(1)
        val rectH get() = (bottom - top).coerceAtLeast(1)
    }

    private fun readRecord(r: BeReader): Record {
        val top = r.int(); val left = r.int(); val bottom = r.int(); val right = r.int()
        val n = r.short().coerceIn(0, 64)
        val channels = ArrayList<Channel>(n)
        repeat(n) {
            val raw = r.short()
            channels.add(Channel(if (raw > 32767) raw - 65536 else raw))
            r.int()
        }
        val sig = String(r.bytes(4), Charsets.US_ASCII)
        val key = String(r.bytes(4), Charsets.US_ASCII)
        if (sig != "8BIM") throw IOException("bad layer signature")
        val opacity = (r.byte() and 0xFF) / 255f
        val clipped = r.byte() != 0
        val flags = r.byte()
        r.byte()
        val extraLen = r.int()
        val end = r.pos + extraLen
        val maskLen = r.int(); r.skip(maskLen)
        val rangesLen = r.int(); r.skip(rangesLen)
        var name = r.pascal()
        while (r.pos + 12 <= end) {
            val s = String(r.bytes(4), Charsets.US_ASCII)
            if (s != "8BIM") break
            val k = String(r.bytes(4), Charsets.US_ASCII)
            val len = r.int()
            val e = r.pos + len
            if (k == "luni" && len >= 4) {
                val nChars = r.int()
                val sb = StringBuilder()
                repeat(nChars.coerceAtMost(256)) {
                    val code = r.short()
                    r.short()
                    if (code > 0) sb.append(code.toChar())
                }
                if (sb.isNotEmpty()) name = sb.toString()
            }
            r.pos = e
        }
        r.pos = end
        return Record(
            top, left, bottom, right, channels, psdBlend(key), opacity, clipped,
            flags and 0x02 != 0, name.ifBlank { "Layer" },
        )
    }

    private fun readChannel(r: BeReader, compression: Int, width: Int, height: Int): ByteArray {
        return when (compression) {
            0 -> r.bytes(width * height)
            1 -> {
                val rows = IntArray(height) { r.short() }
                val out = ByteArray(width * height)
                for (y in 0 until height) {
                    val packed = r.bytes(rows[y])
                    unpackInto(packed, out, y * width, width)
                }
                out
            }
            else -> throw IOException("unsupported PSD compression $compression")
        }
    }

    private fun unpackInto(packed: ByteArray, out: ByteArray, offset: Int, width: Int) {
        var i = 0
        var o = offset
        val end = offset + width
        while (i < packed.size && o < end) {
            val n = packed[i++].toInt()
            if (n >= 0) {
                val count = n + 1
                for (k in 0 until count) if (i < packed.size && o < end) out[o++] = packed[i++]
            } else if (n != -128) {
                val count = 1 - n
                if (i < packed.size) {
                    val b = packed[i++]
                    for (k in 0 until count) if (o < end) out[o++] = b
                }
            }
        }
    }

    private fun psdKey(mode: BlendMode): String = when (mode) {
        BlendMode.NORMAL, BlendMode.PASSTHROUGH -> "norm"
        BlendMode.DARKEN -> "dark"
        BlendMode.MULTIPLY -> "mul "
        BlendMode.COLOR_BURN -> "idiv"
        BlendMode.LINEAR_BURN -> "lbrn"
        BlendMode.DARKER_COLOR -> "dkCl"
        BlendMode.LIGHTEN -> "lite"
        BlendMode.SCREEN -> "scrn"
        BlendMode.COLOR_DODGE -> "div "
        BlendMode.LINEAR_DODGE, BlendMode.ADD -> "lddg"
        BlendMode.LIGHTER_COLOR -> "lgCl"
        BlendMode.OVERLAY -> "over"
        BlendMode.SOFT_LIGHT -> "sLit"
        BlendMode.HARD_LIGHT -> "hLit"
        BlendMode.VIVID_LIGHT -> "vLit"
        BlendMode.LINEAR_LIGHT -> "lLit"
        BlendMode.PIN_LIGHT -> "pLit"
        BlendMode.HARD_MIX -> "hMix"
        BlendMode.DIFFERENCE -> "diff"
        BlendMode.EXCLUSION -> "smud"
        BlendMode.SUBTRACT -> "fsub"
        BlendMode.DIVIDE -> "fdiv"
        BlendMode.HUE -> "hue "
        BlendMode.SATURATION -> "sat "
        BlendMode.COLOR -> "colr"
        BlendMode.LUMINOSITY -> "lum "
        BlendMode.NEGATIVE, BlendMode.INVERT, BlendMode.ERASE, BlendMode.MASK, BlendMode.LINE_ART -> "norm"
    }

    private fun psdBlend(key: String): BlendMode = when (key) {
        "dark" -> BlendMode.DARKEN
        "mul " -> BlendMode.MULTIPLY
        "idiv" -> BlendMode.COLOR_BURN
        "lbrn" -> BlendMode.LINEAR_BURN
        "dkCl" -> BlendMode.DARKER_COLOR
        "lite" -> BlendMode.LIGHTEN
        "scrn" -> BlendMode.SCREEN
        "div " -> BlendMode.COLOR_DODGE
        "lddg" -> BlendMode.LINEAR_DODGE
        "lgCl" -> BlendMode.LIGHTER_COLOR
        "over" -> BlendMode.OVERLAY
        "sLit" -> BlendMode.SOFT_LIGHT
        "hLit" -> BlendMode.HARD_LIGHT
        "vLit" -> BlendMode.VIVID_LIGHT
        "lLit" -> BlendMode.LINEAR_LIGHT
        "pLit" -> BlendMode.PIN_LIGHT
        "hMix" -> BlendMode.HARD_MIX
        "diff" -> BlendMode.DIFFERENCE
        "smud" -> BlendMode.EXCLUSION
        "fsub" -> BlendMode.SUBTRACT
        "fdiv" -> BlendMode.DIVIDE
        "hue " -> BlendMode.HUE
        "sat " -> BlendMode.SATURATION
        "colr" -> BlendMode.COLOR
        "lum " -> BlendMode.LUMINOSITY
        else -> BlendMode.NORMAL
    }

    private class BeWriter(private val out: OutputStream) {
        fun byte(v: Int) = out.write(v and 0xFF)
        fun short(v: Int) { byte(v ushr 8); byte(v) }
        fun int(v: Int) { byte(v ushr 24); byte(v ushr 16); byte(v ushr 8); byte(v) }
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun bytes(b: ByteArray) = out.write(b)
        fun zeros(n: Int) = repeat(n) { out.write(0) }
        fun pascal(s: String) {
            val bytes = s.toByteArray(Charsets.US_ASCII).take(255).toByteArray()
            byte(bytes.size)
            out.write(bytes)
            var pad = 1 + bytes.size
            while (pad % 4 != 0) { out.write(0); pad++ }
        }
    }

    private class BeReader(private val data: ByteArray) {
        var pos = 0
        fun byte(): Int = data[pos++].toInt() and 0xFF
        fun short(): Int = (byte() shl 8) or byte()
        fun int(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > data.size) throw IOException("truncated PSD")
            val b = data.copyOfRange(pos, pos + n); pos += n; return b
        }
        fun skip(n: Int) { if (n < 0 || pos + n > data.size) throw IOException("truncated PSD"); pos += n }
        fun zeros(n: Int) = skip(n)
        fun pascal(): String {
            val n = byte()
            val b = bytes(n)
            var pad = 1 + n
            while (pad % 4 != 0) { skip(1); pad++ }
            return String(b, Charsets.US_ASCII)
        }
    }
}
