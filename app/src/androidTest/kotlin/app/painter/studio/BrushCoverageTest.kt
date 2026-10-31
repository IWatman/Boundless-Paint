package app.painter.studio

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.stroke.BrushResources
import app.painter.core.stroke.InputPoint
import app.painter.core.stroke.StrokeContext
import app.painter.core.stroke.StrokeEngine
import app.painter.core.stroke.StrokeMode
import app.painter.core.stroke.StrokeTarget
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/**
 * Smoke-renders every stock preset through the real engine and resources. Logs one line per brush:
 * `BrushCoverage|<category>|<name>|changed=<n>|error=<...>`. Not an assertion test: the report is the output.
 */
@RunWith(AndroidJUnit4::class)
class BrushCoverageTest {

    @Test
    fun renderEveryStockBrush() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = BrushLibrary(context.assets)
        library.load()
        for (category in library.categories) {
            if (category.id == "created" || category.id == "imported") continue
            for (brush in category.brushes) {
                val result = runCatching { draw(library, brush) }
                val changed = result.getOrNull() ?: -1
                val error = result.exceptionOrNull()?.let { it::class.simpleName + ": " + (it.message ?: "") } ?: ""
                android.util.Log.i(
                    "BrushCoverage",
                    "${category.id}|${brush.name.text}|changed=$changed|$error",
                )
            }
        }
    }

    private fun draw(library: BrushLibrary, brush: app.painter.core.stroke.Brush): Int {
        val size = 256
        val doc = Document.create(size, size)
        val layer = doc.activeRaster!!
        // A gradient with an 8 px checkerboard: a linear gradient alone is invariant under a symmetric blur, which
        // would make filter brushes look like no-ops. High-frequency detail gives every filter something to change.
        val px = FloatArray(4)
        for (y in 0 until size) for (x in 0 until size) {
            val checker = ((x / 8) + (y / 8)) and 1
            val base = if (checker == 0) 0.25f else 0.75f
            layer.pixels.setPixel(x, y, base, y / 255f, x / 255f, 1f)
        }
        val before = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            layer.pixels.getPixel(x, y, px)
            before[y * size + x] = Color.argb(
                (px[3] * 255).toInt(), (px[0] / px[3] * 255).toInt(),
                (px[1] / px[3] * 255).toInt(), (px[2] / px[3] * 255).toInt(),
            )
        }
        val resources = library.resources(brush)
        val engine = StrokeEngine(
            doc, StrokeTarget.Layer(layer.id), brush, resources, Rgba.BLACK, StrokeMode.PAINT,
            StrokeContext(threads = 1, seed = 7L),
        )
        val points = (0..50).map { i ->
            InputPoint(
                x = 40f + i * 3.5f,
                y = 128f + 36f * sin(i / 7f),
                pressure = 1f,
                timeMs = i * 8L,
                isStylus = true,
            )
        }
        engine.begin(points.first())
        for (chunk in points.drop(1).chunked(5)) engine.add(chunk)
        engine.end()
        var changed = 0
        for (y in 0 until size) for (x in 0 until size) {
            layer.pixels.getPixel(x, y, px)
            val a = (px[3] * 255).toInt()
            val argb = if (a == 0) 0 else Color.argb(
                a, (px[0] / px[3] * 255).toInt(),
                (px[1] / px[3] * 255).toInt(), (px[2] / px[3] * 255).toInt(),
            )
            if (argb != before[y * size + x]) changed++
        }
        return changed
    }
}
