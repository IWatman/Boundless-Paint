package app.painter.core.stroke

import app.painter.core.blend.Compositor
import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.io.BrushIO
import app.painter.core.raster.TileGrid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/**
 * Renders every catalog preset into one PNG preview sheet (`-Dpainter.sheet=<path>`), so the brushes can be
 * eyeballed side by side. Off by default: without the property this test is a no-op, keeping `:core:test`
 * cheap. `-Dpainter.sheet.filter=<ids>` keeps only the given category ids (comma separated).
 *
 * Each cell is a 520×176 white-paper swatch: the en name and id on top, then three hand-like strokes painted
 * with the preset's own size (clamped to 4..60) in dark blue — an S-curve with a rising/falling pressure
 * envelope, a self-crossing figure-eight and a build-up scribble. A failing preset becomes a red cell with its
 * exception message instead of failing the test.
 */
class BrushSheet {
    private companion object {
        const val CELL_W = 520
        const val CELL_H = 176
        const val HEADER = 26
        const val COLS = 3
        const val GAP = 2

        /** #1e3a8a, so colour stamps and mixing show against the paper. */
        val STROKE = Rgba(0x1e / 255f, 0x3a / 255f, 0x8a / 255f)
        val HEADER_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }

    @Test
    fun renderSheet() {
        val out = System.getProperty("painter.sheet")?.takeIf { it.isNotBlank() } ?: return
        val assets = brushAssets()
        val presets = assets.resolve("presets")
        val files = presetFiles(presets)
        val filter = System.getProperty("painter.sheet.filter")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        val cells = ArrayList<BufferedImage>()
        var rendered = 0
        for ((category, id) in indexOrder(presets)) {
            if (filter.isNotEmpty() && category !in filter) continue
            val file = files[id] ?: continue
            cells += try {
                renderPreset(file, assets, rendered).also { rendered++ }
            } catch (e: Exception) {
                println("BrushSheet: ${id} failed: ${e.message}")
                errorCell(id, e)
            }
            if (category == "harmony") cells += try {
                renderPreset(file, assets, rendered, wave = true)
            } catch (e: Exception) {
                errorCell("$id (wave)", e)
            }
        }
        if (cells.isEmpty()) {
            println("BrushSheet: no presets to render")
            return
        }

        val rows = (cells.size + COLS - 1) / COLS
        val sheet = BufferedImage(
            COLS * CELL_W + (COLS - 1) * GAP,
            rows * CELL_H + (rows - 1) * GAP,
            BufferedImage.TYPE_INT_RGB,
        )
        val g = sheet.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, sheet.width, sheet.height)
        for ((i, cell) in cells.withIndex()) {
            val x = (i % COLS) * (CELL_W + GAP)
            val y = (i / COLS) * (CELL_H + GAP)
            g.drawImage(cell, x, y, null)
        }
        g.dispose()

        File(out).absoluteFile.parentFile?.mkdirs()
        ImageIO.write(sheet, "png", File(out))
        println("BrushSheet: wrote $out — $rendered presets rendered, ${cells.size - rendered} failed, ${cells.size} cells")
    }

    /** The `brushes` assets folder: the module dir is the cwd, the parent covers an IDE run from the repo root. */
    private fun brushAssets(): File {
        val dir = File(System.getProperty("user.dir"))
        val candidates = listOf(dir, dir.parentFile ?: dir).map { it.resolve("app/src/main/assets/brushes") }
        return candidates.firstOrNull { it.resolve("assets.json").isFile }
            ?: error("brushes assets not found relative to ${dir.absolutePath}: $candidates")
    }

    /** `id → file` for every preset, derived from `<category>/<name>.json` without decoding. */
    private fun presetFiles(presets: File): Map<String, File> =
        presets.walkTopDown()
            .filter { it.isFile && it.extension == "json" && it.name != "index.json" }
            .associateBy { "${it.parentFile.name}.${it.nameWithoutExtension}" }

    /** `(category id, brush id)` in `index.json` order. */
    private fun indexOrder(presets: File): List<Pair<String, String>> {
        val root = Json.parseToJsonElement(presets.resolve("index.json").readText()).jsonObject
        return root.getValue("categories").jsonArray.flatMap { cat ->
            val o = cat.jsonObject
            val catId = o.getValue("id").jsonPrimitive.content
            o.getValue("brushes").jsonArray.map { catId to it.jsonPrimitive.content }
        }
    }

    /** One swatch: the three strokes on a fresh white-paper document, flattened with the paper. */
    private fun renderPreset(file: File, assets: File, index: Int, wave: Boolean = false): BufferedImage {
        val brush = BrushIO.decode(file.readText())
        val params = brush.params.copy(size = brush.params.size.coerceIn(4f, 60f))
        val res = BrushResources.load(brush.copy(params = params), ResourceResolver { ref -> loadImage(assets, ref) })
        val doc = Document.create(CELL_W, CELL_H - HEADER)
        val rnd = Random(7919L + index)
        val strokes = if (wave) listOf(wave(rnd)) else listOf(sCurve(rnd), figureEight(rnd), scribble(rnd))
        for (points in strokes) {
            val e = StrokeTestKit.engine(doc, params, res, color = STROKE)
            StrokeTestKit.drawOpen(e, points)
            e.end()
        }
        val label = "${brush.name.get("en")}  ·  ${brush.id}" + if (wave) "  (wave there and back)" else ""
        return cell(label, Compositor.flatten(doc, includePaper = true))
    }

    /** S-curve left→right, pressure 0.15 → 1 → 0.15 (sin envelope), uneven 4–14 ms steps, ±0.8 px hand jitter. */
    private fun sCurve(rnd: Random): List<InputPoint> {
        val points = ArrayList<InputPoint>()
        val n = 140
        var time = 0L
        for (i in 0..n) {
            val f = i.toFloat() / n
            val x = 40f + 440f * f + jitter(rnd)
            val y = 75f + 42f * sin(2.0 * PI * f).toFloat() + jitter(rnd)
            val pressure = (0.15f + 0.85f * sin(PI * f).toFloat()).coerceIn(0.02f, 1f)
            points += InputPoint(x, y, pressure = pressure, timeMs = time, isStylus = true)
            time += (4 + i % 11).toLong()
        }
        return points
    }

    /** A small self-crossing figure-eight on the right, pressure 0.6–0.9. */
    private fun figureEight(rnd: Random): List<InputPoint> {
        val points = ArrayList<InputPoint>()
        val n = 120
        var time = 0L
        for (i in 0..n) {
            val t = 2.0 * PI * i / n
            val x = 400f + 34f * cos(t).toFloat() + jitter(rnd)
            val y = 75f + 30f * (sin(t) * cos(t)).toFloat() + jitter(rnd)
            val pressure = (0.75f + 0.15f * sin(2 * t).toFloat()).coerceIn(0.6f, 0.9f)
            points += InputPoint(x, y, pressure = pressure, timeMs = time, isStylus = true)
            time += (5 + i % 10).toLong()
        }
        return points
    }

    /** Bottom-left scribble: 3 returns over ~80 px with a slight vertical drift, for harmony / build-up. */
    private fun scribble(rnd: Random): List<InputPoint> {
        val points = ArrayList<InputPoint>()
        var time = 0L
        for (r in 0..3) {
            val fromX = if (r % 2 == 0) 60f else 140f
            val toX = if (r % 2 == 0) 140f else 60f
            val baseY = 128f + r * 1.5f
            for (i in 0..20) {
                val f = i / 20f
                val x = fromX + (toX - fromX) * f + jitter(rnd)
                points += InputPoint(x, baseY + jitter(rnd), pressure = 0.9f, timeMs = time, isStylus = true)
                time += 6
            }
        }
        return points
    }

    /**
     * The stroke from the emulator report: a sine wave left→right and back with a phase and vertical offset, so the
     * two passes run close and almost parallel (this is what exposed harmony's flat bands).
     */
    private fun wave(rnd: Random): List<InputPoint> {
        val points = ArrayList<InputPoint>()
        var time = 0L
        for (i in 0..120) {
            val x = 40f + i * 3.6f + jitter(rnd)
            points += InputPoint(x, 75f + 40f * sin(i / 6.0).toFloat() + jitter(rnd), pressure = 0.8f, timeMs = time, isStylus = true)
            time += 8
        }
        for (i in 120 downTo 0) {
            val x = 40f + i * 3.6f + jitter(rnd)
            points += InputPoint(x, 87f + 40f * sin(i / 6.0 + 1.0).toFloat() + jitter(rnd), pressure = 0.8f, timeMs = time, isStylus = true)
            time += 8
        }
        return points
    }

    private fun jitter(rnd: Random): Float = (rnd.nextFloat() * 2f - 1f) * 0.8f

    /** White cell with the header text and the flattened (premultiplied) tiles painted below it. */
    private fun cell(label: String, grid: TileGrid): BufferedImage {
        val img = BufferedImage(CELL_W, CELL_H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, CELL_W, CELL_H)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = HEADER_FONT
        g.color = Color(0x14, 0x14, 0x14)
        g.drawString(label, 6, 17)
        g.dispose()
        val px = FloatArray(4)
        val row = IntArray(grid.width)
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                grid.getPixel(x, y, px)
                row[x] = argb(px)
            }
            img.setRGB(0, HEADER + y, grid.width, 1, row, 0, grid.width)
        }
        return img
    }

    /** Premultiplied pixel composed over white paper, as a straight opaque ARGB int. */
    private fun argb(px: FloatArray): Int {
        val a = px[3].coerceIn(0f, 1f)
        val r = channel(px[0] + 1f - a)
        val g = channel(px[1] + 1f - a)
        val b = channel(px[2] + 1f - a)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun channel(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    /** Red cell carrying the failing preset id and exception message. */
    private fun errorCell(id: String, e: Exception): BufferedImage {
        val img = BufferedImage(CELL_W, CELL_H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(0xC6, 0x28, 0x28)
        g.fillRect(0, 0, CELL_W, CELL_H)
        g.font = HEADER_FONT
        g.color = Color.WHITE
        g.drawString(id, 6, 17)
        g.drawString(e.message ?: e.javaClass.simpleName, 6, 34)
        g.dispose()
        return img
    }

    /** Resolves `asset:<path>` from the brushes assets root; gray PNGs keep 1 channel, the rest straight RGBA. */
    private fun loadImage(assets: File, ref: String): ImageData {
        val file = File(assets, ref.removePrefix(BrushIO.ASSET_PREFIX))
        val img = ImageIO.read(file) ?: error("cannot read image $ref")
        val w = img.width
        val h = img.height
        if (img.colorModel.numColorComponents == 1) {
            val pixels = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                pixels[y * w + x] = img.raster.getSample(x, y, 0).toByte()
            }
            return ImageData(w, h, 1, pixels)
        }
        val pixels = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val argb = img.getRGB(x, y)
            val i = (y * w + x) * 4
            pixels[i] = ((argb shr 16) and 0xFF).toByte()
            pixels[i + 1] = ((argb shr 8) and 0xFF).toByte()
            pixels[i + 2] = (argb and 0xFF).toByte()
            pixels[i + 3] = ((argb ushr 24) and 0xFF).toByte()
        }
        return ImageData(w, h, 4, pixels)
    }
}
