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
 */
class BrushLibrary(private val assets: AssetManager) {

    /** One catalog category with its localized [name] and its presets in index order. */
    class Category(val id: String, val name: String, val brushes: List<Brush>)

    private val lock = Any()
    private var loaded = false
    private var categoryList: List<Category> = emptyList()
    private val byId = HashMap<String, Brush>()
    private val favoriteIds = HashMap<String, List<String>>()
    private val resourceCache = HashMap<String, BrushResources>()

    private val resolver = ResourceResolver { ref -> image(ref) }

    /** True once [load] has read the whole catalog. */
    val isLoaded: Boolean get() = loaded

    /** Categories of the catalog, localized for the locale the catalog was loaded with. */
    val categories: List<Category> get() = categoryList

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

    /** Reads the index and every preset it lists; the second call is a no-op. */
    fun load() {
        synchronized(lock) {
            if (loaded) return
            val index = runCatching { read(INDEX_PATH) }.getOrNull()?.let { parseIndex(it) } ?: emptyList()
            categoryList = index
            loaded = true
        }
    }

    private fun parseIndex(text: String): List<Category> {
        val root = BrushIO.json.parseToJsonElement(text).jsonObject
        val lang = if (Locale.getDefault().language == "ru") "ru" else "en"
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

    /** Decodes an `asset:<path>` reference, which points into `brushes/<path>`. */
    private fun image(ref: String): ImageData {
        val path = ASSET_PATH + ref.removePrefix(BrushIO.ASSET_PREFIX)
        return assets.open(path).use { decode(it.readBytes()) }
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
    }
}
