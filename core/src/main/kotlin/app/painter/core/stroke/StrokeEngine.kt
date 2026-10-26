package app.painter.core.stroke

import app.painter.core.color.ColorConv
import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskPatch
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One brush stroke (spec/PLAN-M2.md §2): input → calibration → stabilizer → lazy rope → [StrokeOptions.stages]
 * → [PathWalker] (interpolation, closed, sub-strokes on `lift()`) → [DabGenerator] → symmetry copies →
 * [TileScheduler] + [MaskKernel] → stroke buffer → composite into the target on [end].
 *
 * Buffered path (default): dabs go to [buffer] (premultiplied float, full-canvas sparse grid) and the target is
 * only touched in [end], where the buffer is composited with the [previewOpacity] cap. Direct path
 * ([BrushParams.buildup] on a layer target, [directToLayer]): dabs go straight into the layer's float working
 * copies, opacity multiplies flow; [takeDirty] then reports layer tiles and [cancel] restores them.
 *
 * Targets: [StrokeTarget.Layer] commits [StrokeCommit.Pixels]. [StrokeTarget.Mask] and [StrokeTarget.Selection]
 * always use the buffered path and commit [StrokeCommit.Mask]: with buffer alpha `a` (× cap) and value
 * `L` = colour luminance (1 for the selection), PAINT writes `m + a·(L − m)`, ERASE writes `m·(1 − a)`.
 *
 * [StrokeOptions.clip] limits coverage: in buffered paths it is a ceiling of the buffer alpha (the buffer never
 * exceeds the selection value), in the direct path it multiplies dab coverage. [StrokeOptions.copies] repeats
 * every dab through each transform (mirrors flip the head); its domain drops dabs in all copies.
 * [StrokeOptions.fill] fills each closed sub-stroke (even-odd, AA) under the outline.
 *
 * Re-render on pen-up: the post-stage points are recorded; with [BrushParams.afterSmoothing] or a profile that
 * needs the final length, [end] clears the buffer (or resets the working copies) and redraws the final path.
 *
 * Modes [StrokeMode.BLEND] and [StrokeMode.CLONE] are part of the API but their kernels arrive in S4/S5:
 * constructing an engine with them throws [UnsupportedOperationException].
 *
 * Predicted input points are ignored. Non-stylus input is pressure 1. Erasing on an alpha-locked layer is a
 * no-op; a locked node is never modified.
 */
class StrokeEngine(
    private val doc: Document,
    val target: StrokeTarget,
    val brush: Brush,
    private val res: BrushResources,
    private val color: Rgba,
    val mode: StrokeMode,
    private val ctx: StrokeContext = StrokeContext(),
    private val options: StrokeOptions = StrokeOptions(),
) {
    /** M1 convenience: paint [params] on raster layer [layerId] with default context and resources. */
    constructor(
        doc: Document,
        layerId: String,
        params: BrushParams,
        color: Rgba,
        mode: StrokeMode,
        options: StrokeOptions = StrokeOptions(),
    ) : this(doc, StrokeTarget.Layer(layerId), Brush.of(params), BrushResources.EMPTY, color, mode, StrokeContext(), options)

    private val params = brush.params

    init {
        if (mode == StrokeMode.BLEND || mode == StrokeMode.CLONE) {
            throw UnsupportedOperationException("StrokeMode.$mode kernels arrive in m2-brush S4/S5")
        }
    }

    private val layer: RasterLayer? = (target as? StrokeTarget.Layer)?.let {
        requireNotNull(doc.findNode(it.id) as? RasterLayer) { "no raster layer ${it.id}" }
    }

    private val mask: MaskGrid? = when (target) {
        is StrokeTarget.Layer -> null
        is StrokeTarget.Mask -> requireNotNull(requireNotNull(doc.findNode(target.nodeId)) { "no node ${target.nodeId}" }.mask) {
            "node ${target.nodeId} has no mask"
        }.tiles
        StrokeTarget.Selection -> doc.selection.mask
    }

    private val locked: Boolean = when (target) {
        is StrokeTarget.Layer -> layer!!.locked
        is StrokeTarget.Mask -> doc.findNode(target.nodeId)!!.locked
        StrokeTarget.Selection -> false
    }

    /** Premultiplied stroke buffer, alpha uncapped; the preview applies [previewOpacity]. */
    val buffer: TileGrid = TileGrid(doc.width, doc.height, precise = true)

    /** Opacity cap the renderer must multiply [buffer] by when previewing. */
    val previewOpacity: Float = params.opacity.coerceIn(0f, 1f)

    /** True when dabs are written directly into the layer (build-up mode). */
    val directToLayer: Boolean = params.buildup && layer != null

    private val alphaLock = layer?.alphaLock ?: false
    private val inert = locked || (mode == StrokeMode.ERASE && alphaLock)
    private val pool = TilePool.shared
    private val dirty = HashSet<TileKey>()
    private val touch: (TileKey) -> Unit = { k -> synchronized(dirty) { dirty.add(k) } }
    private val bufferAccess = BufferAccess(buffer, pool, touch)
    private val working = layer?.let { LayerWorkingSet(it.pixels, pool, touch) }
    private val scheduler = TileScheduler.shared(ctx.threads)
    private val head = if (params.head.source == HeadSource.BITMAP) res.head else null

    private val kernel: MaskKernel = if (directToLayer) {
        MaskKernel(directOpOf(mode, alphaLock), head, params.head.antialias, params.head.depth, previewOpacity, ceiling = false)
    } else {
        MaskKernel(MaskOp.OVER, head, params.head.antialias, params.head.depth, 1f, ceiling = true)
    }
    private val directOp = directOpOf(mode, alphaLock)
    private val access: TileAccess = if (directToLayer) working!! else bufferAccess

    private val stabilizer = Stabilizer(params.smoothing, params.smoothingSpeed)
    private val stages: List<PointStage> = buildList {
        if (params.lazyRadius > 0f) add(BrushLazyRope(params.lazyRadius, params.lazyElastic, ctx.zoom))
        addAll(options.stages)
    }
    private val stageStarted = BooleanArray(stages.size)
    private val stageSinks: List<PointSink> = List(stages.size) { i -> StageSink(i + 1) }
    private val finalSink = FinalSink()

    private val batch = DabBatch()
    private val fanned = DabBatch()
    private var generator = newGenerator()
    private var walker = newWalker(generator, replay = false)
    private val recorded = ArrayList<MutableList<InputPoint>>()
    private var rerender = false
    private var active = false
    private var peakRaw = 0f
    private var stylus = false

    /** Starts the stroke at [p]. */
    fun begin(p: InputPoint) {
        if (inert) return
        active = true
        val n = normalize(p)
        feed(if (options.stabilize) stabilizer.begin(n) else n)
        flush()
    }

    /** Feeds new input points; predicted points are skipped. */
    fun add(points: List<InputPoint>) {
        if (!active) return
        for (p in points) {
            if (p.predicted) continue
            val n = normalize(p)
            feed(if (options.stabilize) stabilizer.add(n) else n)
        }
        flush()
    }

    /**
     * Finishes the stroke, applies it to the target and returns what changed, or null if nothing did.
     */
    fun end(): StrokeCommit? {
        if (!active) return null
        active = false
        if (options.stabilize) for (p in stabilizer.end()) feed(p)
        endStages(0)
        finalSink.lift()
        flush()
        if (rerender || params.afterSmoothing > 0f) rerender()
        if (options.fill != null) fill()
        if (stylus) ctx.calibration?.observeStrokePeak(peakRaw)
        val commit: StrokeCommit? = when {
            mask != null -> commitMask(mask)
            directToLayer -> working!!.commit(layer!!.id)?.let { StrokeCommit.Pixels(it) }
            else -> {
                compositeBuffer()
                working!!.commit(layer!!.id)?.let { StrokeCommit.Pixels(it) }
            }
        }
        touchBuffer()
        bufferAccess.releaseAll()
        return commit
    }

    /** M1 convenience: [end] for a layer target, returning the pixel patch. */
    fun endPatch(): TilePatch? = (end() as? StrokeCommit.Pixels)?.patch

    /** Aborts the stroke without changing the target. */
    fun cancel() {
        for (s in stages) s.cancel()
        working?.rollback()
        touchBuffer()
        bufferAccess.releaseAll()
        recorded.clear()
        active = false
    }

    /** Returns and clears the keys of tiles changed since the previous call. */
    fun takeDirty(): Set<TileKey> = synchronized(dirty) {
        val out = HashSet(dirty)
        dirty.clear()
        out
    }

    private fun touchBuffer() {
        synchronized(dirty) { dirty.addAll(buffer.keys()) }
    }

    private fun normalize(p: InputPoint): InputPoint {
        if (!p.isStylus) return if (p.pressure == 1f) p else p.copy(pressure = 1f)
        stylus = true
        peakRaw = max(peakRaw, p.pressure)
        val cal = ctx.calibration ?: return p
        return p.copy(pressure = cal.pressure(p.pressure))
    }

    private fun feed(p: InputPoint) {
        if (stages.isEmpty()) finalSink.point(p) else stagePoint(0, p)
    }

    private fun stagePoint(i: Int, p: InputPoint) {
        val out = stageSinks[i]
        if (!stageStarted[i]) {
            stageStarted[i] = true
            stages[i].begin(p, out)
        } else {
            stages[i].add(p, out)
        }
    }

    private fun endStages(i: Int) {
        if (i >= stages.size) return
        if (stageStarted[i]) {
            stageStarted[i] = false
            stages[i].end(stageSinks[i])
        }
        endStages(i + 1)
    }

    /** Output of stage `next − 1`. */
    private inner class StageSink(private val next: Int) : PointSink {
        override fun point(p: InputPoint) {
            if (next < stages.size) stagePoint(next, p) else finalSink.point(p)
        }

        override fun lift() {
            if (next < stages.size) {
                if (stageStarted[next]) {
                    stageStarted[next] = false
                    stages[next].end(stageSinks[next])
                }
            } else {
                finalSink.lift()
            }
        }
    }

    /** Records the post-stage path and drives the walker; `lift()` ends a sub-stroke. */
    private inner class FinalSink : PointSink {
        private var started = false

        override fun point(p: InputPoint) {
            if (!started) {
                started = true
                recorded.add(ArrayList<InputPoint>().also { it.add(p) })
                walker.begin(p)
            } else {
                recorded.last().add(p)
                walker.add(p)
            }
        }

        override fun lift() {
            if (!started) return
            started = false
            walker.end()
        }
    }

    private fun newGenerator() = DabGenerator(brush, res, ctx, color)

    private fun newWalker(gen: DabGenerator, replay: Boolean): PathWalker = PathWalker(
        params,
        PathSink { s ->
            if (batch.size >= batch.capacity - MAX_PER_SAMPLE) flush()
            if (s.ending && !replay && gen.profileNeedsRerender(s.total)) rerender = true
            gen.emit(s, batch)
        },
        options.interpolation,
        options.closed,
        ctx.zoom,
        holdBack = if (replay) Float.MAX_VALUE else -1f,
        deferForDirection = gen.needsDirection,
    )

    private fun rerender() {
        if (directToLayer) working!!.reset() else {
            touchBuffer()
            bufferAccess.releaseAll()
        }
        generator = newGenerator()
        walker = newWalker(generator, replay = true)
        for (sub in recorded) {
            val pts = AfterSmoothing.smooth(sub, params.afterSmoothing)
            walker.begin(pts[0])
            for (k in 1 until pts.size) walker.add(pts[k])
            walker.end()
        }
        flush()
    }

    /** Applies symmetry copies to [batch] and renders it. */
    private fun flush() {
        if (batch.size == 0) return
        val copies = options.copies
        val out = if (copies.transforms.isEmpty() && copies.domain == null) {
            batch
        } else {
            fanned.clear()
            for (i in 0 until batch.size) {
                val src = batch[i]
                if (copies.domain?.invoke(src.x, src.y) == false) continue
                fanned.add().set(src)
                for (t in copies.transforms) {
                    val c = fanned.add()
                    c.set(src)
                    c.x = t.a * src.x + t.c * src.y + t.tx
                    c.y = t.b * src.x + t.d * src.y + t.ty
                    val det = t.det
                    c.d = src.d * sqrt(abs(det))
                    if (det < 0f) {
                        // T = R(2·axis)·flipY; with the head flipped along its own x the angle gains π.
                        c.angle = t.rotationRad - src.angle + PI_F
                        c.flipX = !src.flipX
                    } else {
                        c.angle = src.angle + t.rotationRad
                    }
                }
            }
            fanned
        }
        scheduler.run(out, access, kernel, options.clip)
        batch.clear()
    }

    private fun fill() {
        val spec = options.fill ?: return
        val alpha = spec.opacity.coerceIn(0f, 1f) * color.a
        if (alpha <= 0f) return
        val transforms = listOf(app.painter.core.geom.Affine2.ID) + options.copies.transforms
        val clip = options.clip
        for (sub in recorded) {
            if (sub.size < 3) continue
            for (t in transforms) {
                val xs = FloatArray(sub.size) { t.a * sub[it].x + t.c * sub[it].y + t.tx }
                val ys = FloatArray(sub.size) { t.b * sub[it].x + t.d * sub[it].y + t.ty }
                var cachedKey = Long.MIN_VALUE
                var tile: Tile? = null
                PolygonFill.rasterize(xs, ys, doc.width, doc.height) { x, y, cov ->
                    val key = TileKey(x shr Tile.SHIFT, y shr Tile.SHIFT)
                    if (key.packed != cachedKey) {
                        cachedKey = key.packed
                        tile = access.prepare(key, kernel.createsTiles)
                    }
                    val d = tile ?: return@rasterize
                    val m = clip?.value(x, y) ?: 1f
                    val sa = cov * alpha * m
                    if (sa <= 0f) return@rasterize
                    val i = d.index(x and Tile.MASK, y and Tile.MASK)
                    if (directToLayer) {
                        val k = sa * previewOpacity
                        val inv = 1f - k
                        when (directOp) {
                            MaskOp.ERASE -> { d[i] *= inv; d[i + 1] *= inv; d[i + 2] *= inv; d[i + 3] *= inv }
                            MaskOp.OVER_ALPHA_LOCK -> {
                                val da = d[i + 3]
                                d[i] = color.r * k * da + d[i] * inv
                                d[i + 1] = color.g * k * da + d[i + 1] * inv
                                d[i + 2] = color.b * k * da + d[i + 2] * inv
                            }
                            MaskOp.OVER -> {
                                d[i] = color.r * k + d[i] * inv
                                d[i + 1] = color.g * k + d[i + 1] * inv
                                d[i + 2] = color.b * k + d[i + 2] * inv
                                d[i + 3] = k + d[i + 3] * inv
                            }
                        }
                    } else {
                        val under = 1f - d[i + 3]
                        if (under <= 0f) return@rasterize
                        val k = sa * under
                        d[i] += color.r * k
                        d[i + 1] += color.g * k
                        d[i + 2] += color.b * k
                        d[i + 3] += k
                    }
                    d.markDirty()
                }
            }
        }
    }

    private fun compositeBuffer() {
        val op = previewOpacity
        if (op <= 0f) return
        val ws = working!!
        val erase = mode == StrokeMode.ERASE
        for ((key, src) in buffer.entries()) {
            if (src.isEmpty()) continue
            val dst = ws.prepare(key, create = !(alphaLock || erase)) ?: continue
            val s = src
            val d = dst
            var i = 0
            while (i < Tile.CHANNELS) {
                val sa = s[i + 3] * op
                if (sa > 0f) {
                    val inv = 1f - sa
                    when {
                        erase -> {
                            d[i] *= inv; d[i + 1] *= inv; d[i + 2] *= inv; d[i + 3] *= inv
                        }
                        alphaLock -> {
                            val da = d[i + 3]
                            d[i] = s[i] * op * da + d[i] * inv
                            d[i + 1] = s[i + 1] * op * da + d[i + 1] * inv
                            d[i + 2] = s[i + 2] * op * da + d[i + 2] * inv
                        }
                        else -> {
                            d[i] = s[i] * op + d[i] * inv
                            d[i + 1] = s[i + 1] * op + d[i + 1] * inv
                            d[i + 2] = s[i + 2] * op + d[i + 2] * inv
                            d[i + 3] = sa + d[i + 3] * inv
                        }
                    }
                }
                i += 4
            }
            dst.markDirty()
        }
    }

    private fun commitMask(grid: MaskGrid): StrokeCommit? {
        val op = previewOpacity
        val lum = if (target is StrokeTarget.Selection) 1f else ColorConv.luminance(color).coerceIn(0f, 1f)
        val erase = mode == StrokeMode.ERASE
        val def = grid.defaultValue
        val before = LinkedHashMap<TileKey, MaskTile?>()
        val after = LinkedHashMap<TileKey, MaskTile?>()
        if (op > 0f) {
            for ((key, src) in buffer.entries()) {
                if (src.isEmpty()) continue
                val existing = grid.get(key)
                if (existing == null && erase && def == ZERO) continue
                val old = existing?.copy()
                val t = existing ?: grid.getOrCreate(key)
                val data = t.data
                for (p in 0 until Tile.SIZE * Tile.SIZE) {
                    val a = src[p * 4 + 3] * op
                    if (a <= 0f) continue
                    val m = (data[p].toInt() and 0xFF) / 255f
                    val v = if (erase) m * (1f - a) else m + a * (lum - m)
                    data[p] = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                }
                val changed = if (old != null) !old.contentEquals(t) else !t.isUniform(def)
                if (!changed) {
                    if (existing == null) grid.put(key, null)
                    continue
                }
                t.markDirty()
                before[key] = old
                if (t.isUniform(def)) {
                    grid.put(key, null)
                    after[key] = null
                } else {
                    after[key] = t.copy()
                }
                touch(key)
            }
        }
        if (before.isEmpty()) return null
        val id = when (target) {
            is StrokeTarget.Mask -> target.nodeId
            else -> MaskPatch.SELECTION
        }
        return StrokeCommit.Mask(MaskPatch(id, before, after))
    }

    private companion object {
        fun directOpOf(mode: StrokeMode, alphaLock: Boolean): MaskOp = when {
            mode == StrokeMode.ERASE -> MaskOp.ERASE
            alphaLock -> MaskOp.OVER_ALPHA_LOCK
            else -> MaskOp.OVER
        }

        const val MAX_PER_SAMPLE = 16
        const val PI_F = kotlin.math.PI.toFloat()
        const val ZERO: Byte = 0
    }
}
