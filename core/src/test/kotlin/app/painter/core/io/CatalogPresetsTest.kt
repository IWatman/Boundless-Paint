package app.painter.core.io

import app.painter.core.stroke.ColorStampParams
import app.painter.core.stroke.DoubleParams
import app.painter.core.stroke.FilterBrushParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the generated catalog (tools/catalog → `app/src/main/assets/brushes/presets`): every preset
 * decodes with [BrushIO], its id matches `<category>.<file name>`, it has our en/ru names, it only
 * references generated assets, and `index.json` lists exactly the files on disk.
 */
class CatalogPresetsTest {
    /** `head.baseColor` of a colour stamp: `#rrggbb` only, so the hue shift has a defined origin. */
    private val hexColor = Regex("^#[0-9a-fA-F]{6}$")

    /**
     * The `brushes` assets folder. The :core test task runs with `user.dir` = the module directory, so
     * the app module is one level up; the second candidate covers an IDE run from the repository root.
     */
    private fun brushAssets(): File {
        val dir = File(System.getProperty("user.dir"))
        val candidates = listOf(dir, dir.parentFile ?: dir).map { it.resolve("app/src/main/assets/brushes") }
        val found = candidates.firstOrNull { it.resolve("assets.json").isFile }
        assertTrue(found != null, "brushes assets not found relative to ${dir.absolutePath}: $candidates")
        return found!!
    }

    /** Every `presets/<category>/<id>.json`, sorted; `index.json` is not a brush. */
    private fun presetFiles(presets: File): List<File> =
        presets.walkTopDown()
            .filter { it.isFile && it.extension == "json" && it.name != "index.json" }
            .sorted()
            .toList()

    private fun assetRefs(assetsJson: File): Set<String> =
        Json.parseToJsonElement(assetsJson.readText()).jsonObject.getValue("assets").jsonArray
            .map { it.jsonObject.getValue("ref").jsonPrimitive.content }
            .toSet()

    private fun index(presets: File) = Json.parseToJsonElement(presets.resolve("index.json").readText()).jsonObject

    @Test
    fun everyPresetDecodesAndMatchesItsPath() {
        val assets = brushAssets()
        val refs = assetRefs(assets.resolve("assets.json"))
        val files = presetFiles(assets.resolve("presets"))
        assertTrue(files.size >= 8, "too few presets: ${files.size}")
        for (f in files) {
            val brush = BrushIO.decode(f.readText())
            val category = f.parentFile!!.name
            assertEquals("$category.${f.nameWithoutExtension}", brush.id, f.path)
            assertEquals(category, brush.category, f.path)
            for (lang in listOf("en", "ru")) {
                assertTrue(brush.name.translations.containsKey(lang), "${f.path}: no $lang name")
                assertTrue(brush.name.get(lang).isNotBlank(), "${f.path}: empty $lang name")
            }
            for (ref in listOfNotNull(brush.resources.head, brush.resources.texture, brush.resources.particle)) {
                assertTrue(ref in refs, "${f.path}: unknown resource ref $ref")
            }
            // S6 specials: the off-stroke filter, the colour stamp and the double outline; nothing else.
            val special = brush.params.special
            assertTrue(
                special == null ||
                    (special is FilterBrushParams && !special.onStroke) ||
                    special is ColorStampParams ||
                    special is DoubleParams,
                "${f.path}: unexpected special engine $special",
            )
            // A colour stamp shifts the head hue from its base colour, so the head must carry both.
            if (special is ColorStampParams) {
                val head = brush.params.head
                assertTrue(head.color, "${f.path}: colour-stamp presets need head.color")
                val base = head.baseColor
                assertTrue(
                    base != null && hexColor.matches(base),
                    "${f.path}: colour-stamp presets need a #rrggbb baseColor: $base",
                )
            }
            // S2a paper texture: the grain params are meaningless without the texture image next to them.
            assertTrue(
                brush.params.texture == null || brush.resources.texture != null,
                "${f.path}: texture params need a texture image",
            )
        }
    }

    @Test
    fun everyEffectsPresetIsAnOffStrokeFilter() {
        val files = presetFiles(brushAssets().resolve("presets/effects"))
        assertTrue(files.size >= 5, "too few effects presets: ${files.size}")
        for (f in files) {
            // The `effects` category is the S6 filter engine: a kind plus a strength, run over the layer.
            val filter = BrushIO.decode(f.readText()).params.special as? FilterBrushParams
            assertTrue(filter != null, "${f.path}: effects presets must use FilterBrushParams")
            assertTrue(!filter!!.onStroke, "${f.path}: the filter must run off the stroke")
            assertTrue(filter.strength in 0f..1f, "${f.path}: filter strength out of 0..1: ${filter.strength}")
        }
    }

    @Test
    fun indexListsEveryPresetAndOnlyRealIds() {
        val presets = brushAssets().resolve("presets")
        val ids = presetFiles(presets).map { BrushIO.decode(it.readText()).id }.toSet()
        val root = index(presets)
        assertEquals(1, root.getValue("schema").jsonPrimitive.int)
        val listed = root.getValue("categories").jsonArray.flatMap { cat ->
            val o = cat.jsonObject
            for (lang in listOf("en", "ru")) {
                assertTrue(o.getValue("name").jsonObject.getValue(lang).jsonPrimitive.content.isNotBlank())
            }
            o.getValue("brushes").jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(ids, listed.toSet(), "index.json and the preset files must list the same ids")
        assertEquals(listed.size, listed.toSet().size, "duplicate id in index.json")

        val favorites = root.getValue("favorites").jsonObject
        val paint = favorites.getValue("PAINT").jsonArray.map { it.jsonPrimitive.content }
        val erase = favorites.getValue("ERASE").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(paint.size in 1..3, "PAINT holds up to 3 ids: $paint")
        assertEquals(1, erase.size, "ERASE holds exactly 1 id: $erase")
        for (id in paint + erase) assertTrue(id in ids, "favorite $id is not a preset")
    }
}
