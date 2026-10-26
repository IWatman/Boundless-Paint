package app.painter.core.stroke

import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Tile-parallel dab rendering (spec/tools/m2-brush.md §0). A batch is binned by tile; tiles are prepared on the
 * calling thread, then each tile renders its dabs strictly in index order on a worker. Tiles are independent,
 * so the result is bit-identical for any thread count. [DabKernel.orderDependent] kernels run one dab at a time
 * (its tiles still in parallel).
 */
class TileScheduler(val threads: Int) : AutoCloseable {
    private val executor: ExecutorService? = if (threads > 1) {
        Executors.newFixedThreadPool(threads) { r -> Thread(r, "painter-dabs").apply { isDaemon = true } }
    } else {
        null
    }

    private class Job(val tile: Tile, val tx: Int, val ty: Int, val clip: MaskTile?, val clipValue: Float) {
        var dabs = IntArray(8)
        var n = 0
        fun add(i: Int) {
            if (n == dabs.size) dabs = dabs.copyOf(n * 2)
            dabs[n++] = i
        }
    }

    /** Renders [batch] into [target] with [kernel], multiplying coverage by [clip] when set. */
    fun run(batch: DabBatch, target: TileAccess, kernel: DabKernel, clip: MaskGrid? = null) {
        if (batch.size == 0) return
        if (kernel.orderDependent) {
            for (i in 0 until batch.size) runRange(batch, i, i + 1, target, kernel, clip)
        } else {
            runRange(batch, 0, batch.size, target, kernel, clip)
        }
    }

    private fun runRange(batch: DabBatch, from: Int, to: Int, target: TileAccess, kernel: DabKernel, clip: MaskGrid?) {
        val w = target.width
        val h = target.height
        val bins = HashMap<Long, MutableList<Int>>()
        val order = ArrayList<Long>()
        for (i in from until to) {
            val dab = batch[i]
            val e = kernel.extent(dab)
            val x0 = max(0, floor(dab.x - e).toInt())
            val y0 = max(0, floor(dab.y - e).toInt())
            val x1 = min(w - 1, ceil(dab.x + e).toInt())
            val y1 = min(h - 1, ceil(dab.y + e).toInt())
            if (x0 > x1 || y0 > y1) continue
            for (ty in (y0 shr Tile.SHIFT)..(y1 shr Tile.SHIFT)) {
                for (tx in (x0 shr Tile.SHIFT)..(x1 shr Tile.SHIFT)) {
                    val k = TileKey(tx, ty).packed
                    bins.getOrPut(k) { order.add(k); ArrayList() }.add(i)
                }
            }
        }
        val jobs = ArrayList<Job>(order.size)
        for (k in order) {
            val key = TileKey(k)
            var ct: MaskTile? = null
            var cv = 1f
            if (clip != null) {
                ct = clip.get(key)
                if (ct == null) {
                    cv = (clip.defaultValue.toInt() and 0xFF) / 255f
                    if (cv <= 0f) continue
                } else if (ct.isEmpty()) {
                    continue
                }
            }
            val tile = target.prepare(key, kernel.createsTiles) ?: continue
            val job = Job(tile, key.tx, key.ty, ct, cv)
            for (i in bins.getValue(k)) job.add(i)
            jobs.add(job)
        }
        val ex = executor
        if (ex == null || jobs.size < 2) {
            for (j in jobs) render(j, batch, kernel, w, h)
        } else {
            val futures = ex.invokeAll(jobs.map { j -> Callable { render(j, batch, kernel, w, h) } })
            for (f in futures) f.get()
        }
    }

    private fun render(j: Job, batch: DabBatch, kernel: DabKernel, w: Int, h: Int) {
        for (n in 0 until j.n) kernel.renderTile(j.tile, j.tx, j.ty, w, h, batch[j.dabs[n]], j.clip, j.clipValue)
    }

    override fun close() {
        executor?.shutdown()
    }

    companion object {
        private val sharedByThreads = HashMap<Int, TileScheduler>()

        /** Process-wide scheduler with [threads] workers (never closed). */
        fun shared(threads: Int): TileScheduler = synchronized(sharedByThreads) {
            sharedByThreads.getOrPut(threads.coerceAtLeast(1)) { TileScheduler(threads.coerceAtLeast(1)) }
        }
    }
}
