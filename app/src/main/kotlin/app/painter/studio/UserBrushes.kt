package app.painter.studio

import app.painter.core.io.BrushIO
import app.painter.core.io.import.BrushImport
import app.painter.core.stroke.Brush
import app.painter.core.stroke.BrushName
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.ResourceRefs
import java.io.File
import java.util.UUID

/**
 * User brushes: the "Created" folder of the brush browser and the target of "Save as"
 * (spec/tools/m2-brush.md §4.2).
 *
 * One brush per file, `<dir>/<id>.json`, written with [BrushIO] — the same `brush.json` schema 1 the built-in
 * presets use (§3.2), so a user brush differs from a preset only by its id (`user.<uuid>`) and by
 * [Brush.parentId], which points at the brush it was derived from. The resources keep their `asset:`
 * references into the stock catalog, so [BrushLibrary.resources] decodes them without copying an image.
 *
 * All methods are synchronized: [load] runs off the main thread while saves and deletes come from the UI.
 */
class UserBrushes(private val dir: File) {

    private val lock = Any()
    private var cached: List<Brush> = emptyList()

    /** Directory that holds the `*.json` files of the brushes, for [load]. */
    val directory: File get() = dir

    /** Brushes last read by [load], kept in sync by [save] and [remove]. */
    val brushes: List<Brush> get() = synchronized(lock) { cached }

    /** Reads every brush file of the directory; a file that does not decode is skipped. */
    fun load(): List<Brush> = synchronized(lock) {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
        cached = files.sortedBy { it.name }
            .mapNotNull { file -> runCatching { file.readText() }.getOrNull()?.let(UserBrushCodec::decode) }
        cached
    }

    /** Stored brush [id], null when there is none. */
    fun find(id: String): Brush? = synchronized(lock) { cached.firstOrNull { it.id == id } }

    /** Writes [brush] over its file; false when its id cannot be a file name or the write fails. */
    fun save(brush: Brush): Boolean = synchronized(lock) {
        val name = UserBrushCodec.fileName(brush.id) ?: return false
        val written = runCatching {
            dir.mkdirs()
            File(dir, name).writeText(UserBrushCodec.encode(brush))
        }.isSuccess
        if (written) cached = (cached.filter { it.id != brush.id } + brush).sortedBy { it.id }
        written
    }

    /** Deletes the file of [id] and the images of an imported brush; false when it cannot be removed. */
    fun remove(id: String): Boolean = synchronized(lock) {
        val name = UserBrushCodec.fileName(id) ?: return false
        val file = File(dir, name)
        val removed = !file.exists() || runCatching { file.delete() }.getOrDefault(false)
        if (removed) {
            cached = cached.filter { it.id != id }
            runCatching { assetDir(id).deleteRecursively() }
        }
        removed
    }

    /**
     * Stores [imported] as a new user brush in the "Imported" folder: its images go to `assets/<id>/`,
     * the refs to them become `asset:user/<id>/<file>` and the brush gets a fresh `imported.<uuid>` id.
     * Returns the stored brush, or null when nothing could be written.
     */
    fun saveImported(
        fileName: String,
        imported: BrushImport.Imported,
        fallbackName: String = "Imported brush",
    ): Brush? = synchronized(lock) {
        val id = UserBrushCodec.newImportedId()
        val assetFiles = LinkedHashMap<String, String>()
        for ((source, bytes) in imported.files) {
            val safe = UserBrushCodec.assetName(source) ?: continue
            var target = safe
            var k = 2
            while (assetFiles.containsValue(target)) {
                val base = safe.substringBeforeLast('.')
                val ext = safe.substringAfterLast('.', "")
                target = if (ext.isEmpty()) "$base-$k" else "$base-$k.$ext"
                k++
            }
            val file = File(assetDir(id), target)
            if (runCatching { file.parentFile?.mkdirs(); file.writeBytes(bytes) }.isFailure) continue
            assetFiles[source] = target
        }
        fun rewrite(ref: String?): String? {
            if (ref == null) return null
            val name = ref.removePrefix(BrushIO.BRUSH_PREFIX)
            val target = assetFiles[name] ?: return ref
            return BrushIO.ASSET_PREFIX + "user/" + id + "/" + target
        }
        val base = imported.brush
        val name = base.name.text.trim().take(UserBrushCodec.MAX_NAME)
            .ifBlank { fileName.substringBeforeLast('.').ifBlank { fallbackName } }
        val brush = base.copy(
            id = id,
            parentId = base.parentId,
            name = BrushName(name),
            category = UserBrushCodec.CATEGORY_IMPORTED,
            resources = ResourceRefs(
                head = rewrite(base.resources.head),
                texture = rewrite(base.resources.texture),
                particle = rewrite(base.resources.particle),
            ),
        )
        val written = runCatching {
            dir.mkdirs()
            File(dir, UserBrushCodec.fileName(id)!!).writeText(UserBrushCodec.encode(brush))
        }.isSuccess
        if (!written) return null
        cached = (cached.filter { it.id != id } + brush).sortedBy { it.id }
        brush
    }

    /** Bytes of an imported asset reference `user/<id>/<file>`, null when it is missing. */
    fun assetBytes(path: String): ByteArray? {
        if (!path.startsWith("user/")) return null
        return runCatching { File(dir, "assets/$path").readBytes() }.getOrNull()
    }

    /** Directory of the images of imported brush [id]. */
    private fun assetDir(id: String): File = File(dir, "assets/$id")
}

/**
 * File format, ids and names of [UserBrushes]. Free of Android APIs, so the JVM tests can cover it.
 *
 * A user brush is a preset JSON: unknown fields are ignored on read and [BrushIO.json] drops the defaulted
 * ones on write, which keeps the files of two runs identical.
 */
internal object UserBrushCodec {

    /** Prefix of a user brush id: `user.<uuid>`. */
    const val PREFIX = "user."

    /** Prefix of an imported brush id: `imported.<uuid>`. */
    const val PREFIX_IMPORTED = "imported."

    /** Category id of the "Created" folder; the display name is localized by [BrushLibrary]. */
    const val CATEGORY = "created"

    /** Category id of the "Imported" folder (spec/tools/brush-import.md). */
    const val CATEGORY_IMPORTED = "imported"

    /** Longest kept brush name, in characters. */
    const val MAX_NAME = 64

    /** Characters [fileName] accepts: an id goes into a path, so nothing outside a slug may reach it. */
    private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,120}")

    /** True when [id] is the id of a user or an imported brush. */
    fun isUser(id: String?): Boolean =
        id != null && (id.startsWith(PREFIX) || id.startsWith(PREFIX_IMPORTED))

    /** True when [id] was produced by a file import. */
    fun isImported(id: String?): Boolean = id != null && id.startsWith(PREFIX_IMPORTED)

    /** A fresh id; the uuid keeps two brushes saved in the same millisecond apart. */
    fun newId(): String = PREFIX + UUID.randomUUID()

    /** A fresh id for a brush read from [app.painter.core.io.import.BrushImport]. */
    fun newImportedId(): String = PREFIX_IMPORTED + UUID.randomUUID()

    /** File name of [id] (`<id>.json`), null when [id] is not a user id or holds characters a path cannot. */
    fun fileName(id: String): String? =
        if (isUser(id) && id.length > PREFIX.length && !id.contains("..") && SAFE_ID.matches(id)) "$id.json" else null

    /** A safe file name for an imported image; null when nothing usable is left. */
    fun assetName(source: String): String? {
        val base = source.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return clean.take(80).ifBlank { null }
    }

    /** [brush] as preset-format JSON, the serializer the built-in catalog files use. */
    fun encode(brush: Brush): String = BrushIO.encode(brush)

    /** Brush of [encode] output; null when [text] is not a brush or holds an id of a built-in preset. */
    fun decode(text: String): Brush? = runCatching { BrushIO.decode(text) }.getOrNull()?.takeIf { isUser(it.id) }

    /**
     * The brush "Save as" stores: the edited [params] and the [resources] of the brush they were edited on,
     * [parentId] of that brush, a user id and [name] as the user typed it.
     */
    fun create(
        parentId: String?,
        name: String,
        params: BrushParams,
        resources: ResourceRefs,
        id: String = newId(),
        category: String = CATEGORY,
    ): Brush = Brush(
        id = id,
        parentId = parentId,
        name = BrushName(cleanName(name, category)),
        category = category,
        params = params,
        resources = resources,
    )

    /** [name] trimmed and cut to [MAX_NAME] characters; a blank name falls back to [fallback]. */
    fun cleanName(name: String, fallback: String): String =
        name.trim().take(MAX_NAME).ifBlank { fallback.trim().take(MAX_NAME) }

    /** Default name of a copy of [name], where [copy] is the localized word for "copy". */
    fun copyName(name: String, copy: String): String {
        val base = name.trim()
        return if (base.isEmpty()) copy else "$base $copy"
    }
}
