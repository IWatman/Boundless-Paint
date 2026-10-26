package app.painter.core.io

import app.painter.core.stroke.Brush
import app.painter.core.stroke.ResourceRefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Brush codec (spec/tools/m2-brush.md §3.1–§3.2, spec/file-formats.md «Кисть»).
 *
 * - `brush.json` schema 1: unknown fields are ignored, missing ones take defaults; a newer schema is read
 *   best-effort and stamped with the current schema ([migrate]).
 * - `.pbrush`: ZIP with `brush.json` and the image files its `brush:` references name.
 * - `.pbrushes`: ZIP with `manifest.json {name, brushes: [file]}` and nested `.pbrush` entries.
 */
object BrushIO {
    const val BRUSH_JSON = "brush.json"
    const val PACK_MANIFEST = "manifest.json"
    const val ASSET_PREFIX = "asset:"
    const val BRUSH_PREFIX = "brush:"
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = true
        coerceInputValues = true
    }

    fun decode(text: String): Brush {
        val obj = json.parseToJsonElement(text).jsonObject
        return migrate(obj)
    }

    fun encode(brush: Brush): String = json.encodeToString(Brush.serializer(), brush)

    private fun migrate(obj: JsonObject): Brush {
        val schema = obj["schema"]?.jsonPrimitive?.int ?: Brush.SCHEMA
        val b = json.decodeFromJsonElement(Brush.serializer(), obj)
        return if (schema != Brush.SCHEMA) b.copy(schema = Brush.SCHEMA) else b
    }

    /** Reads a `.pbrush` archive: the brush and every other file by name. */
    fun readPbrush(zip: InputStream): Pair<Brush, Map<String, ByteArray>> {
        val files = readZip(zip)
        val text = files.remove(BRUSH_JSON) ?: throw IOException("no $BRUSH_JSON in .pbrush")
        return decode(text.decodeToString()) to files
    }

    /**
     * Writes a `.pbrush`. `brush:` references are taken from [files]; `asset:` references are loaded with
     * [assets], stored inside the archive and rewritten to `brush:` names.
     */
    fun writePbrush(
        brush: Brush,
        files: Map<String, ByteArray>,
        out: OutputStream,
        assets: (String) -> ByteArray? = { null },
    ) {
        val (b, payload) = embed(brush, files, assets)
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(BRUSH_JSON))
            z.write(encode(b).encodeToByteArray())
            z.closeEntry()
            for ((name, bytes) in payload) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
    }

    /** One brush of a pack with its files. */
    class PackEntry(val brush: Brush, val files: Map<String, ByteArray>)

    @Serializable
    private data class PackManifest(val name: String = "", val brushes: List<String> = emptyList())

    /** Reads a `.pbrushes` pack: its name and brushes in manifest order. */
    fun readPack(zip: InputStream): Pair<String, List<PackEntry>> {
        val files = readZip(zip)
        val mf = files[PACK_MANIFEST]?.let { json.decodeFromString(PackManifest.serializer(), it.decodeToString()) }
            ?: throw IOException("no $PACK_MANIFEST in .pbrushes")
        val list = mf.brushes.map { name ->
            val bytes = files[name] ?: throw IOException("missing $name in pack")
            val (b, f) = readPbrush(ByteArrayInputStream(bytes))
            PackEntry(b, f)
        }
        return mf.name to list
    }

    /** Writes a `.pbrushes` pack; `asset:` references are embedded as in [writePbrush]. */
    fun writePack(name: String, brushes: List<PackEntry>, out: OutputStream, assets: (String) -> ByteArray? = { null }) {
        val used = HashSet<String>()
        val names = brushes.map { e ->
            val base = e.brush.id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "brush" }
            var n = "$base.pbrush"
            var k = 2
            while (!used.add(n)) n = "${base}_${k++}.pbrush"
            n
        }
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(PACK_MANIFEST))
            z.write(json.encodeToString(PackManifest.serializer(), PackManifest(name, names)).encodeToByteArray())
            z.closeEntry()
            for ((i, e) in brushes.withIndex()) {
                val bo = ByteArrayOutputStream()
                writePbrush(e.brush, e.files, bo, assets)
                z.putNextEntry(ZipEntry(names[i]))
                z.write(bo.toByteArray())
                z.closeEntry()
            }
        }
    }

    private fun embed(
        brush: Brush,
        files: Map<String, ByteArray>,
        assets: (String) -> ByteArray?,
    ): Pair<Brush, Map<String, ByteArray>> {
        val payload = LinkedHashMap<String, ByteArray>()
        fun conv(ref: String?, slot: String): String? {
            if (ref == null) return null
            return when {
                ref.startsWith(BRUSH_PREFIX) -> {
                    val n = ref.removePrefix(BRUSH_PREFIX)
                    payload[n] = files[n] ?: throw IOException("missing brush file $n")
                    ref
                }
                ref.startsWith(ASSET_PREFIX) -> {
                    val bytes = assets(ref.removePrefix(ASSET_PREFIX)) ?: throw IOException("cannot load $ref")
                    val ext = ref.substringAfterLast('.', "png")
                    var n = "$slot.$ext"
                    var k = 2
                    while (payload.containsKey(n)) n = "${slot}_${k++}.$ext"
                    payload[n] = bytes
                    BRUSH_PREFIX + n
                }
                else -> ref
            }
        }
        val r = brush.resources
        val refs = ResourceRefs(conv(r.head, "head"), conv(r.texture, "texture"), conv(r.particle, "particle"))
        return brush.copy(resources = refs) to payload
    }

    private fun readZip(input: InputStream): MutableMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (name.contains("..") || name.startsWith("/") || name.contains('\\')) throw IOException("bad entry $name")
                val bo = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ENTRY_BYTES) throw IOException("entry $name too large")
                    bo.write(buf, 0, n)
                }
                out[name] = bo.toByteArray()
            }
        }
        return out
    }
}
