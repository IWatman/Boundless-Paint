package app.painter.studio

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.painter.core.io.BrushIO
import app.painter.core.stroke.Brush
import app.painter.core.stroke.BrushResources
import app.painter.core.stroke.ImageData
import app.painter.core.stroke.ResourceResolver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Locale

/**
 * Built-in brush catalog read from the APK assets: `brushes/presets/index.json` lists the categories, the
 * mode favourites and the ids of the presets, which live as `brushes/presets/<category>/<name>.json`
 * (id `<category>.<name>`, see [BrushIO.decode]).
 *
 * [load] is blocking and must run off the main thread; afterwards the catalog is immutable and every
 * getter is safe to call from any thread. Head, texture and particle images are decoded on first use and
 * cached per brush id, so painting never touches the assets twice for the same preset.
 *
 * The user brushes of [userBrushes] are published as a first "Created" category (§4.2) once they exist and are
 * in the same [find] index; they keep `asset:` references, so [resources] decodes them like any preset.
 */
class BrushLibrary(private val assets: AssetManager, private val userBrushes: UserBrushes? = null) {

    /** One catalog category with its localized [name] and its presets in index order. */
    class Category(val id: String, val name: String, val brushes: List<Brush>)

    private val lock = Any()
    private var loaded = false
    /** Language of the last [load]; the "Created" category name follows it. */
    private var lang = "en"
    /** Built-in categories of the index, in index order; user brushes are published in front of them. */
    private var presetCategories: List<Category> = emptyList()
    /** The "Created" category: the user brushes of [userBrushes], empty when there are none. */
    private var createdCategory = Category(UserBrushCodec.CATEGORY, "Created", emptyList())
    /** The "Imported" category (spec/tools/brush-import.md), empty when nothing was imported. */
    private var importedCategory = Category(UserBrushCodec.CATEGORY_IMPORTED, "Imported", emptyList())
    /** Categories made from [userBrushes], in display order. */
    private val userCategories: List<Category>
        get() = listOfNotNull(
            createdCategory.takeIf { it.brushes.isNotEmpty() },
            importedCategory.takeIf { it.brushes.isNotEmpty() },
        )
    private val byId = HashMap<String, Brush>()
    private val favoriteIds = HashMap<String, List<String>>()
    private val resourceCache = HashMap<String, BrushResources>()
    private val editedCache = HashMap<String, BrushResources>()

    private val resolver = ResourceResolver { ref -> image(ref) }

    /** True once [load] has read the whole catalog. */
    val isLoaded: Boolean get() = loaded

    /** Categories of the catalog, localized for the locale the catalog was loaded with; user folders come first. */
    val categories: List<Category>
        get() = userCategories + presetCategories

    /** Ids of the favourites of brush [mode] (`PAINT`, `ERASE`), empty for a mode without favourites. */
    fun favorites(mode: String): List<String> = favoriteIds[mode].orEmpty()

    /** Preset [id] (`<category>.<name>`), null when the catalog has no such brush. */
    fun find(id: String): Brush? = byId[id]

    /**
     * Decoded [BrushResources] of the preset [brush]. Cached by [Brush.id]: presets are immutable, so the
     * image references (and their decoding) only depend on the id. A broken reference yields
     * [BrushResources.EMPTY] instead of failing the stroke.
     */
    fun resources(brush: Brush): BrushResources = synchronized(resourceCache) {
        resourceCache.getOrPut(brush.id) {
            runCatching { BrushResources.load(brush, resolver) }.getOrDefault(BrushResources.EMPTY)
        }
    }

    /**
     * Decoded [BrushResources] of an *edited* brush, for the brush editor preview: the head curve/invert and the
     * texture invert/softness are baked into the images when they are built (spec/tools/m2-brush.md §1.4), so the
     * per-id cache of [resources] would show stale grain. Cached by the references plus those parameters, which are
     * the only inputs of the build; a brush without references yields [BrushResources.EMPTY].
     */
    fun editedResources(brush: Brush): BrushResources {
        val refs = brush.resources
        if (refs.head == null && refs.texture == null && refs.particle == null) return BrushResources.EMPTY
        val head = brush.params.head
        val texture = brush.params.texture
        val key = buildString {
            append(brush.id).append('|').append(refs.head).append(head.invert).append(head.curve)
            append('|').append(refs.texture).append(texture?.invert).append(texture?.softness)
        }
        return synchronized(resourceCache) {
            editedCache[key] ?: runCatching { BrushResources.load(brush, resolver) }.getOrDefault(BrushResources.EMPTY)
                .also {
                    if (editedCache.size >= EDITED_CACHE_LIMIT) editedCache.clear()
                    editedCache[key] = it
                }
        }
    }

    /** Reads the index, every preset it lists and the user brushes; the second call is a no-op. */
    fun load() {
        synchronized(lock) {
            if (loaded) return
            lang = if (Locale.getDefault().language == "ru") "ru" else "en"
            presetCategories = runCatching { read(INDEX_PATH) }.getOrNull()?.let { parseIndex(it) } ?: emptyList()
            setCreated(userBrushes?.load().orEmpty())
            loaded = true
        }
    }

    /**
     * Replaces the "Created" category with [brushes] and registers their ids for [find]; the built-in part of
     * the catalog is untouched. Called by the brush editor and the browser after a save, a rename or a delete.
     */
    fun setCreated(brushes: List<Brush>) {
        synchronized(lock) {
            createdCategory.brushes.forEach { byId.remove(it.id) }
            importedCategory.brushes.forEach { byId.remove(it.id) }
            val created = brushes.filter { !UserBrushCodec.isImported(it.id) }
            val imported = brushes.filter { UserBrushCodec.isImported(it.id) }
            createdCategory = Category(UserBrushCodec.CATEGORY, if (lang == "ru") "Созданные" else "Created", created)
            importedCategory = Category(
                UserBrushCodec.CATEGORY_IMPORTED,
                if (lang == "ru") "Импортированные" else "Imported",
                imported,
            )
            brushes.forEach { byId[it.id] = it }
        }
    }

    private fun parseIndex(text: String): List<Category> {
        val root = BrushIO.json.parseToJsonElement(text).jsonObject
        val out = ArrayList<Category>()
        for (element in root["categories"]?.jsonArray.orEmpty()) {
            val category = element.jsonObject
            val id = category["id"]?.jsonPrimitive?.content ?: continue
            val name = localized(category["name"] as? JsonObject, lang).ifEmpty { id }
            val brushes = ArrayList<Brush>()
            for (ref in category["brushes"]?.jsonArray.orEmpty()) {
                val brushId = ref.jsonPrimitive.content
                val brush = loadPreset(brushId) ?: continue
                byId[brushId] = brush
                brushes.add(brush)
            }
            out.add(Category(id, name, brushes))
        }
        val favorites = root["favorites"] as? JsonObject
        if (favorites != null) {
            for ((mode, refs) in favorites) {
                favoriteIds[mode] = refs.jsonArray.map { it.jsonPrimitive.content }
            }
        }
        return out
    }

    /** Name [lang] of a `{"en": …, "ru": …}` object, falling back to English and then to the first entry. */
    private fun localized(name: JsonObject?, lang: String): String {
        if (name == null) return ""
        val plain = name.mapValues { it.value.jsonPrimitive.content }
        return plain[lang] ?: plain["en"] ?: plain.values.firstOrNull() ?: ""
    }

    /** Reads preset [id] from `presets/<category>/<name>.json`; null when the file is missing or broken. */
    private fun loadPreset(id: String): Brush? = runCatching {
        BrushIO.decode(read(PRESET_PATH + id.replace('.', '/') + ".json"))
    }.getOrNull()

    private fun read(path: String): String = assets.open(path).use { it.readBytes().decodeToString() }

    /**
     * Decodes an `asset:<path>` reference: stock images point into `brushes/<path>` of the APK, imported ones
     * (`asset:user/<id>/<file>`) into the image directory of [UserBrushes].
     */
    private fun image(ref: String): ImageData {
        val path = ref.removePrefix(BrushIO.ASSET_PREFIX)
        val bytes = if (path.startsWith("user/")) {
            userBrushes?.assetBytes(path) ?: throw IOException("missing imported brush image")
        } else {
            assets.open(ASSET_PATH + path).use { it.readBytes() }
        }
        return decode(bytes)
    }

    /** Bytes behind an `asset:` reference, for exporting a brush or a pack. */
    fun assetBytes(ref: String?): ByteArray? {
        if (ref == null || !ref.startsWith(BrushIO.ASSET_PREFIX)) return null
        val path = ref.removePrefix(BrushIO.ASSET_PREFIX)
        if (path.startsWith("user/")) return userBrushes?.assetBytes(path)
        return runCatching { assets.open(ASSET_PATH + path).use { it.readBytes() } }.getOrNull()
    }

    /**
     * PNG bytes to [ImageData]: a gray image with no transparency becomes one channel (the gray value,
     * which equals the red one), anything else four channels of straight RGBA as stored in the file.
     */
    private fun decode(bytes: ByteArray): ImageData {
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("cannot decode brush image")
        try {
            val w = bitmap.width
            val h = bitmap.height
            val argb = IntArray(w * h)
            bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            var gray = true
            for (p in argb) {
                val r = (p shr 16) and 0xFF
                if ((p ushr 24) != 0xFF || r != ((p shr 8) and 0xFF) || r != (p and 0xFF)) {
                    gray = false
                    break
                }
            }
            if (gray) return ImageData(w, h, 1, ByteArray(w * h) { (argb[it] and 0xFF).toByte() })
            val px = ByteArray(w * h * 4)
            for (i in argb.indices) {
                val p = argb[i]
                px[i * 4] = ((p shr 16) and 0xFF).toByte()
                px[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
                px[i * 4 + 2] = (p and 0xFF).toByte()
                px[i * 4 + 3] = ((p ushr 24) and 0xFF).toByte()
            }
            return ImageData(w, h, 4, px)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val INDEX_PATH = "brushes/presets/index.json"
        const val PRESET_PATH = "brushes/presets/"
        const val ASSET_PATH = "brushes/"

        /** How many edited builds [editedResources] keeps: a softness drag only needs the last few. */
        const val EDITED_CACHE_LIMIT = 16
    }
}
