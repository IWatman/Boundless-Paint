package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import java.io.File
import kotlin.test.Test

/**
 * Brush benchmark (spec/tools/m2-brush.md §6). Prints and writes `build/stroke-bench.txt`; never asserts
 * timings. Input: a 1000 px line at 2000 px/s, fed in 60 Hz frames (≈33 px per frame, a point every 4 px).
 * D benchmarks the smudge (S4) and E the watercolor simulation (S8).
 */
class StrokeBench {
    private class Result(val name: String, val meanMs: Double, val maxMs: Double, val frames: Int, val dabs: Int)

    private fun run(
        name: String,
        params: BrushParams,
        res: BrushResources,
        threads: Int,
        mode: StrokeMode = StrokeMode.PAINT,
    ): Result {
        val pts = (0..250).map { i -> InputPoint(100f + i * 4f, 600f + 40f * kotlin.math.sin(i / 30f), timeMs = i * 2L) }
        val frames = pts.drop(1).chunked(8)
        var best: Result? = null
        repeat(3) { round ->
            val doc = Document.create(1300, 1200)
            val e = StrokeEngine(doc, StrokeTarget.Layer(doc.activeId!!), Brush.of(params), res, Rgba(0.1f, 0.2f, 0.8f), mode,
                StrokeContext(threads = threads, seed = 1))
            val times = ArrayList<Double>()
            e.begin(pts[0])
            for (f in frames) {
                val t0 = System.nanoTime()
                e.add(f)
                times.add((System.nanoTime() - t0) / 1e6)
            }
            val t0 = System.nanoTime()
            e.end()
            times.add((System.nanoTime() - t0) / 1e6)
            val dabs = (1000f / (params.spacing * params.size)).toInt()
            val r = Result(name, times.average(), times.max(), times.size, dabs)
            if (round > 0 && (best == null || r.meanMs < best!!.meanMs)) best = r
        }
        return best!!
    }

    @Test
    fun bench() {
        val headPx = StrokeTestKit.gray(256, 256) { x, y ->
            val dx = x - 127.5f; val dy = y - 127.5f
            if (dx * dx + dy * dy < 120f * 120f) 0.6f + 0.4f * ((x * 7 + y * 13) % 5) / 4f else 0f
        }
        val bitmap = BrushResources(head = HeadImage(headPx))
        val lines = ArrayList<String>()
        lines.add("cores=${Runtime.getRuntime().availableProcessors()}")
        for (threads in listOf(1, StrokeContext.defaultThreads())) {
            val rs = listOf(
                run("A size 20 spacing 10%", BrushParams(size = 20f, spacing = 0.10f), BrushResources.EMPTY, threads),
                run("B size 300 soft spacing 5%", BrushParams(size = 300f, spacing = 0.05f, hardness = 0f), BrushResources.EMPTY, threads),
                run("C size 500 bitmap spacing 8% (texture: S2)", BrushParams(size = 500f, spacing = 0.08f,
                    head = HeadParams(source = HeadSource.BITMAP)), bitmap, threads),
                run("D smudge size 200", BrushParams(size = 200f, spacing = 0.10f,
                    paint = PaintParams(pull = 0.9f)), BrushResources.EMPTY, threads, StrokeMode.BLEND),
                run("E watercolor size 80", BrushParams(size = 80f, spacing = 0.05f, hardness = 0.7f,
                    special = WatercolorParams()), BrushResources.EMPTY, threads),
            )
            for (r in rs) lines.add(
                "threads=$threads ${r.name}: mean " + "%.2f".format(java.util.Locale.ROOT, r.meanMs) + " ms/frame, max " + "%.2f".format(java.util.Locale.ROOT, r.maxMs) + " ms, frames=${r.frames}, ~dabs=${r.dabs}",
            )
        }
        lines.forEach(::println)
        File("build").mkdirs()
        File("build/stroke-bench.txt").writeText(lines.joinToString("\n") + "\n")
    }
}
