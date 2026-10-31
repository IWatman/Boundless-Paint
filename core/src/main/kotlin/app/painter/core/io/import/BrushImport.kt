package app.painter.core.io.import

import app.painter.core.io.BrushIO
import app.painter.core.stroke.Brush
import app.painter.core.stroke.BrushName
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.Curve
import app.painter.core.stroke.TextureParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipInputStream

/**
 * Reads brush files from other painting apps (spec/tools/brush-import.md). Pure JVM so the formats are covered
 * by unit tests; the Android layer only saves the resulting files and rewrites the references.
 *
 * Every parser is defensive: archive and image limits, no unbounded reads, and a malformed file throws
 * [BrushFileException] without returning a partial brush. Behaviour parameters are mapped approximately.
 */
object BrushImport {

    /** One imported brush with the image files its references name. */
    class Imported(val brush: Brush, val files: Map<String, ByteArray>)

    /** Parsed file: its human-readable [format], the brushes and how many entries were skipped. */
    class Result(val format: String, val brushes: List<Imported>, val skipped: Int = 0)

    class BrushFileException(message: String) : IOException(message)

    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val MAX_PIXELS = 16_000_000L

    /** Parses [bytes] by [fileName]'s extension; [asTexture] puts an image into the texture slot instead of the head. */
    fun parse(fileName: String, bytes: ByteArray, asTexture: Boolean = false): Result {
        if (bytes.size > MAX_BYTES) throw BrushFileException("file is larger than 64 MB")
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pbrush" -> ownBrush(bytes)
            "pbrushes" -> ownPack(bytes)
            "myb" -> myPaint(fileName, bytes)
            "gbr" -> gimpBrush(fileName, bytes)
            "kpp" -> krita(fileName, bytes)
            "brushset", "brush" -> procreate(fileName, bytes)
            "png", "jpg", "jpeg", "webp" -> image(fileName, bytes, asTexture)
            else -> throw BrushFileException("unsupported brush file: .$ext")
        }
    }

    private fun ownBrush(bytes: ByteArray): Result {
        val (brush, files) = BrushIO.readPbrush(ByteArrayInputStream(bytes))
        return Result("Painter brush", listOf(Imported(brush, files)))
    }

    private fun ownPack(bytes: ByteArray): Result {
        val (name, entries) = BrushIO.readPack(ByteArrayInputStream(bytes))
        if (entries.isEmpty()) throw BrushFileException("the pack is empty")
        return Result("Painter pack \"$name\"", entries.map { Imported(it.brush, it.files) })
    }

    /** MyPaint `.myb`: open JSON with radius, hardness, opacity, spacing and pressure curves. */
    private fun myPaint(fileName: String, bytes: ByteArray): Result {
        val root = runCatching { BrushIO.json.parseToJsonElement(bytes.decodeToString()).jsonObject }
            .getOrElse { throw BrushFileException("not a MyPaint brush: ${it.message}") }
        val radius = root.float("radius", 1f).coerceIn(0.05f, 200f)
        val hardness = root.float("hardness", 0.8f).coerceIn(0f, 1f)
        val opacity = root.float("opacity", 1f).coerceIn(0f, 1f)
        val spacing = root.float("spacing", 0.1f).coerceIn(0.01f, 2f)
        val smudge = root.float("smudge", 0f).coerceIn(0f, 1f)
        val pressureSize = curve(root["pressure_size"] ?: root["pressure"])
        val params = BrushParams(
            size = (radius * 2f).coerceAtLeast(1f),
            opacity = opacity,
            flow = opacity,
            spacing = spacing,
            hardness = hardness,
            pressureToSize = pressureSize ?: BrushParams().pressureToSize,
            pressureToFlow = curve(root["pressure_opacity"]),
            paint = BrushParams().paint.copy(pull = smudge),
        )
        val name = root["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.')
        return Result("MyPaint brush", listOf(Imported(Brush(id = "imported.myb", name = BrushName(name), params = params), emptyMap())))
    }

    private fun JsonObject.float(key: String, fallback: Float): Float =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let { runCatching { it.float }.getOrNull() } ?: fallback

    /**
     * MyPaint curves are arrays of `[x, y1, ...]` samples or `{points: [[x,y],...]}`. The first two channels are
     * enough for our pressure -> size/opacity links; a malformed curve falls back to null.
     */
    private fun curve(value: kotlinx.serialization.json.JsonElement?): Curve? {
        val points = ArrayList<Pair<Float, Float>>()
        runCatching {
            val arr = value?.let { it as? kotlinx.serialization.json.JsonArray } ?: return null
            for (p in arr) {
                val a = p as? kotlinx.serialization.json.JsonArray ?: continue
                val x = a.getOrNull(0)?.jsonPrimitive?.float ?: continue
                val y = a.getOrNull(1)?.jsonPrimitive?.float ?: continue
                if (x in 0f..1f && y in 0f..1f) points.add(x to y)
            }
        }
        points.sortBy { it.first }
        val distinct = points.distinctBy { it.first }
        if (distinct.size < 2) return null
        return runCatching { Curve(distinct) }.getOrNull()
    }

    /** GIMP `.gbr`: header, raw gray/RGBA samples, name after the data. Converted to a PNG head. */
    private fun gimpBrush(fileName: String, bytes: ByteArray): Result {
        val magic = "GIMP Brush\u0000\u0000".encodeToByteArray()
        if (bytes.size < 20 || bytes.copyOfRange(0, magic.size).contentEquals(magic).not()) {
            throw BrushFileException("not a GIMP brush")
        }
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        var p = 12
        val version = buf.getInt(p); p += 4
        if (version != 1 && version != 2) throw BrushFileException("unsupported GIMP brush version $version")
        val w = buf.getInt(p); p += 4
        val h = buf.getInt(p); p += 4
        val bpp = buf.getInt(p); p += 4
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) throw BrushFileException("bad GIMP brush size")
        if (bpp != 1 && bpp != 4) throw BrushFileException("unsupported GIMP brush depth $bpp")
        val need = w.toLong() * h * bpp
        if (p + need > bytes.size) throw BrushFileException("truncated GIMP brush")
        val rgba = ByteArray(w * h * 4)
        var o = 0
        for (i in 0 until w * h) {
            if (bpp == 1) {
                val v = bytes[p + i].toInt() and 0xFF
                rgba[o] = v.toByte(); rgba[o + 1] = v.toByte(); rgba[o + 2] = v.toByte(); rgba[o + 3] = -1
            } else {
                rgba[o] = bytes[p + i * 4]; rgba[o + 1] = bytes[p + i * 4 + 1]
                rgba[o + 2] = bytes[p + i * 4 + 2]; rgba[o + 3] = bytes[p + i * 4 + 3]
            }
            o += 4
        }
        val name = readCString(bytes, p + need.toInt()).ifBlank { fileName.substringBeforeLast('.') }
        val png = PngWriter.rgba(w, h, rgba)
        val brush = Brush(
            id = "imported.gbr",
            name = BrushName(name),
            params = BrushParams(size = maxOf(w, h).toFloat(), spacing = 0.08f, hardness = 1f),
            resources = app.painter.core.stroke.ResourceRefs(head = "brush:head.png"),
        )
        return Result("GIMP brush", listOf(Imported(brush, mapOf("head.png" to png))))
    }

    /** Krita `.kpp`: a PNG with the preset XML in a `tEXt` chunk keyed `preset`. */
    private fun krita(fileName: String, bytes: ByteArray): Result {
        val text = pngText(bytes, "preset")
            ?: throw BrushFileException("Krita preset XML is missing")
        fun param(name: String): String? =
            Regex("<param\\s+name=\"${Regex.escape(name)}\"[^>]*>([^<]*)</param>").find(text)?.groupValues?.get(1)
        val radius = param("radius")?.toFloatOrNull() ?: param("size")?.toFloatOrNull()
        val spacing = param("spacing")?.toFloatOrNull()?.let { if (it > 2f) it / 100f else it }
        val opacity = param("opacity")?.toFloatOrNull()?.let { if (it > 1f) it / 255f else it }
        val name = Regex("<Preset[^>]*\\bname=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?: fileName.substringBeforeLast('.')
        val params = BrushParams(
            size = ((radius ?: 8f) * 2f).coerceIn(1f, 1000f),
            opacity = (opacity ?: 1f).coerceIn(0f, 1f),
            flow = (opacity ?: 1f).coerceIn(0f, 1f),
            spacing = (spacing ?: 0.1f).coerceIn(0.01f, 2f),
        )
        // .kpp has no embedded tip in the common case; the preview PNG is still a useful head.
        val preview = pngChunk(bytes, "IDAT")
        val files = HashMap<String, ByteArray>()
        val refs = if (preview != null) {
            files["head.png"] = bytes
            app.painter.core.stroke.ResourceRefs(head = "brush:head.png")
        } else app.painter.core.stroke.ResourceRefs()
        return Result("Krita preset", listOf(Imported(Brush(id = "imported.kpp", name = BrushName(name), params = params, resources = refs), files)))
    }

    /** Procreate `.brushset` / `.brush`: ZIP with shape/grain images and an XML/binary plist. */
    private fun procreate(fileName: String, bytes: ByteArray): Result {
        val files = readZip(bytes)
        val plist = files.entries.firstOrNull {
            it.key.endsWith(".plist", ignoreCase = true) || it.key.endsWith("Brush.archive", ignoreCase = true)
        }?.value
        val text = plist?.toString(Charsets.UTF_8).orEmpty()
        fun real(key: String): Float? =
            Regex("<key>${Regex.escape(key)}</key>\\s*<(?:real|integer)>([^<]+)</(?:real|integer)>")
                .find(text)?.groupValues?.get(1)?.toFloatOrNull()
        fun string(key: String): String? =
            Regex("<key>${Regex.escape(key)}</key>\\s*<string>([^<]*)</string>")
                .find(text)?.groupValues?.get(1)
        val shape = files.entries.firstOrNull {
            it.key.substringAfterLast('/').equals("Shape.png", ignoreCase = true)
        }
        val grain = files.entries.firstOrNull {
            it.key.substringAfterLast('/').equals("Grain.png", ignoreCase = true)
        }
        if (shape == null && grain == null && text.isEmpty()) throw BrushFileException("empty Procreate brush")
        val spacing = real("spacing")?.let { if (it > 2f) it / 100f else it }?.coerceIn(0.01f, 2f) ?: 0.1f
        val jitter = real("jitter")?.coerceIn(0f, 1f) ?: 0f
        val out = HashMap<String, ByteArray>()
        val head = shape?.let { out["head.png"] = it.value; "brush:head.png" }
        val texture = grain?.let { out["texture.png"] = it.value; "brush:texture.png" }
        val params = BrushParams(
            size = 30f,
            spacing = spacing,
            jitter = BrushParams().jitter.copy(scatter = jitter),
            texture = if (texture != null) TextureParams() else null,
        )
        val name = string("name") ?: shape?.key?.substringBeforeLast('/')?.substringAfterLast('/')
            ?: fileName.substringBeforeLast('.')
        return Result(
            "Procreate brush",
            listOf(Imported(Brush(
                id = "imported.procreate", name = BrushName(name), params = params,
                resources = app.painter.core.stroke.ResourceRefs(head = head, texture = texture),
            ), out)),
        )
    }

    /** PNG/JPG head or texture: the brush itself gets a reference to the file; the app stores it. */
    fun image(fileName: String, bytes: ByteArray, asTexture: Boolean): Result {
        if (!isImage(bytes)) throw BrushFileException("not a PNG or JPEG image")
        val safe = fileName.substringBeforeLast('.').ifBlank { "image" }
        val file = if (asTexture) "texture.png" else "head.png"
        val ref = "brush:$file"
        val params = BrushParams(
            size = 60f,
            spacing = 0.1f,
            hardness = if (asTexture) 1f else 0.8f,
            texture = if (asTexture) TextureParams() else null,
        )
        val refs = if (asTexture) {
            app.painter.core.stroke.ResourceRefs(texture = ref)
        } else {
            app.painter.core.stroke.ResourceRefs(head = ref)
        }
        return Result(
            if (asTexture) "Image texture" else "Image head",
            listOf(Imported(Brush(id = "imported.image", name = BrushName(safe), params = params, resources = refs), mapOf(file to bytes))),
        )
    }

    private fun isImage(bytes: ByteArray): Boolean =
        bytes.size > 8 && (
            (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()) ||
                (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
            )

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var n = 0
            while (true) {
                val e = z.nextEntry ?: break
                if (++n > 512) throw BrushFileException("archive has too many entries")
                if (e.isDirectory) continue
                val name = e.name
                if (name.contains("..") || name.startsWith("/") || name.contains('\\')) {
                    throw BrushFileException("bad archive entry")
                }
                val bo = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val r = z.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > MAX_BYTES) throw BrushFileException("archive is too large")
                    bo.write(buf, 0, r)
                }
                out[name] = bo.toByteArray()
            }
        }
        return out
    }

    /** Text of the first `tEXt`/`iTXt` PNG chunk with [keyword], or null. */
    private fun pngText(bytes: ByteArray, keyword: String): String? {
        var p = 8
        while (p + 12 <= bytes.size) {
            val len = readInt(bytes, p)
            if (len < 0 || p + 12 + len > bytes.size) return null
            val type = String(bytes, p + 4, 4, Charsets.US_ASCII)
            if (type == "tEXt") {
                val payload = bytes.copyOfRange(p + 8, p + 8 + len)
                val nul = payload.indexOf(0)
                if (nul > 0 && String(payload, 0, nul, Charsets.ISO_8859_1) == keyword) {
                    return String(payload, nul + 1, payload.size - nul - 1, Charsets.UTF_8)
                }
            } else if (type == "iTXt") {
                val payload = bytes.copyOfRange(p + 8, p + 8 + len)
                val nul = payload.indexOf(0)
                if (nul > 0 && String(payload, 0, nul, Charsets.ISO_8859_1) == keyword) {
                    // keyword\0 compressionFlag compressionMethod language\0 translated\0 text
                    var q = nul + 3
                    while (q < payload.size && payload[q] != 0.toByte()) q++
                    q++
                    while (q < payload.size && payload[q] != 0.toByte()) q++
                    q++
                    if (q <= payload.size) return String(payload, q, payload.size - q, Charsets.UTF_8)
                }
            }
            if (type == "IEND") return null
            p += 12 + len
        }
        return null
    }

    private fun pngChunk(bytes: ByteArray, wanted: String): ByteArray? {
        var p = 8
        while (p + 12 <= bytes.size) {
            val len = readInt(bytes, p)
            if (len < 0 || p + 12 + len > bytes.size) return null
            val type = String(bytes, p + 4, 4, Charsets.US_ASCII)
            if (type == wanted) return bytes.copyOfRange(p + 8, p + 8 + len)
            if (type == "IEND") return null
            p += 12 + len
        }
        return null
    }

    private fun readInt(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)

    private fun readCString(bytes: ByteArray, from: Int): String {
        var end = from
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, from, end - from, Charsets.UTF_8)
    }
}

/** Minimal PNG encoder for the raw brush tips of GIMP files (Android has no ImageIO). */
internal object PngWriter {
    fun rgba(w: Int, h: Int, rgba: ByteArray): ByteArray {
        require(rgba.size == w * h * 4)
        val raw = ByteArray((w * 4 + 1) * h)
        var o = 0
        for (y in 0 until h) {
            raw[o++] = 0 // filter: none
            System.arraycopy(rgba, y * w * 4, raw, o, w * 4)
            o += w * 4
        }
        return png(w, h, 6, raw)
    }

    private fun png(w: Int, h: Int, colorType: Int, raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream()
        writeInt(ihdr, w); writeInt(ihdr, h)
        ihdr.write(8); ihdr.write(colorType); ihdr.write(0); ihdr.write(0); ihdr.write(0)
        chunk(out, "IHDR", ihdr.toByteArray())
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(raw) }
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeInt(out, data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t); out.write(data)
        val crc = CRC32()
        crc.update(t); crc.update(data)
        writeInt(out, crc.value.toInt())
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }
}
