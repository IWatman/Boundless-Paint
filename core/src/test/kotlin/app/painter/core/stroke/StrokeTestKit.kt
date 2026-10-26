package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.raster.TileGrid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Helpers shared by the M2 stroke tests. */
object StrokeTestKit {
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, step: Float, dtMs: Long = 4L): List<InputPoint> {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        val n = (len / step).toInt().coerceAtLeast(1)
        return (0..n).map { i -> InputPoint(x0 + dx * i / n, y0 + dy * i / n, timeMs = i * dtMs) }
    }

    fun circle(cx: Float, cy: Float, r: Float, n: Int): List<InputPoint> =
        (0..n).map { i ->
            val a = 2 * PI * i / n
            InputPoint(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), timeMs = i * 4L)
        }

    /** Gray ImageData with pixel values from [f] (0..1). */
    fun gray(w: Int, h: Int, f: (Int, Int) -> Float): ImageData {
        val px = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = (f(x, y).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        return ImageData(w, h, 1, px)
    }

    fun engine(
        doc: Document,
        params: BrushParams,
        res: BrushResources = BrushResources.EMPTY,
        ctx: StrokeContext = StrokeContext(threads = 1),
        options: StrokeOptions = StrokeOptions(),
        target: StrokeTarget = StrokeTarget.Layer(doc.activeId!!),
        mode: StrokeMode = StrokeMode.PAINT,
        color: Rgba = Rgba(1f, 0f, 0f),
    ) = StrokeEngine(doc, target, Brush.of(params), res, color, mode, ctx, options)

    /** Draws [points] (one batch) and returns the engine before [StrokeEngine.end]. */
    fun drawOpen(e: StrokeEngine, points: List<InputPoint>, perPoint: Boolean = false): StrokeEngine {
        e.begin(points.first())
        if (perPoint) for (p in points.drop(1)) e.add(listOf(p)) else e.add(points.drop(1))
        return e
    }

    fun alpha(grid: TileGrid, x: Int, y: Int): Float {
        val px = FloatArray(4)
        grid.getPixel(x, y, px)
        return px[3]
    }

    fun assertGridsEqual(a: TileGrid, b: TileGrid, msg: String = "") {
        assertEquals(a.keys().filter { !a.get(it)!!.isEmpty() }.toSet(), b.keys().filter { !b.get(it)!!.isEmpty() }.toSet(), msg)
        for (k in a.keys()) {
            val ta = a.get(k)!!
            val tb = b.get(k) ?: continue
            assertTrue(ta.contentEquals(tb), "$msg tile $k differs")
        }
    }
}
