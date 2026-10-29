package app.painter.core.stroke

import app.painter.core.blend.BlendMath
import app.painter.core.blend.BlendMode
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
import kotlin.math.min
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
 * Effects ([EffectParams], spec/tools/m2-brush.md §1.7): glaze makes dabs saturate instead of accumulating in the
 * buffer; a blend mode other than normal composites the whole buffer with `BlendMath` and forces the buffered path.
 * Wet edges darken the rim on composite: `α' = α·(1 − w·(1 − e))` with `e` the buffer alpha against its 4 px box
 * blur, so a flat fill stays solid at the edge and thins out in the middle; `wetEdges` 0 composites unchanged.
 *
 * [StrokeOptions.clip] limits coverage: in buffered paths it is a ceiling of the buffer alpha (the buffer never
 * exceeds the selection value), in the direct path it multiplies dab coverage. [StrokeOptions.copies] repeats
 * every dab through each transform (mirrors flip the head); its domain drops dabs in all copies.
 *
 * Grain texture ([BrushParams.texture] with [BrushResources.texture], spec/tools/m2-brush.md §1.4) is applied by
 * [MaskKernel] to the dab alpha; with [TextureParams.lowerLimit] < 1 it also caps the alpha the stroke buffer
 * reaches in a pixel, so repeated dabs of one stroke stop at that cap (build-up strokes have no cap).
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

    /** Opacity cap the renderer must multiply [buffer] by when previewing; [previewTile] adds the wet rim. */
    val previewOpacity: Float = params.opacity.coerceIn(0f, 1f)

    // Stroke effects (spec/tools/m2-brush.md §1.7): glaze in the buffer, the brush blend mode on composite.
    /** Blend mode of the buffered composite (spec/tools/m2-brush.md §1.7); NORMAL/PASSTHROUGH mean the plain path. */
    private val strokeBlend: BlendMode = BlendMode.fromId(params.effects.blendMode)

    /** Wet edge strength (spec/tools/m2-brush.md §1.7) of the buffered composite; 0 leaves the plain path. */
    private val wetEdges: Float = params.effects.wetEdges.coerceIn(0f, 1f)

    /** Scratch of the wet edge blur (§1.7), allocated on the first composite that has wet edges. */
    private var wet: WetScratch? = null

    /** Share of [strokeBlend]: `out = lerp(normal, mode, strength)`. */
    private val blendStrength: Float = params.effects.blendStrength.coerceIn(0f, 1f)

    /** True when the stroke buffer is composited with [strokeBlend] instead of plain source-over. */
    private val blended: Boolean = blendStrength > 0f && strokeBlend != BlendMode.NORMAL && strokeBlend != BlendMode.PASSTHROUGH

    /** True when dabs are written directly into the layer (build-up mode); a blend mode forces the buffer path. */
    val directToLayer: Boolean = params.buildup && layer != null && !blended

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
        MaskKernel(
            directOpOf(mode, alphaLock), head, params.head.antialias, params.head.depth, previewOpacity, ceiling = false,
            tex = params.texture, texImage = res.texture, brushSize = params.size,
        )
    } else {
        MaskKernel(
            MaskOp.OVER, head, params.head.antialias, params.head.depth, 1f, ceiling = true,
            glaze = params.effects.glaze.coerceIn(0f, 1f),
            tex = params.texture, texImage = res.texture, brushSize = params.size,
        )
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
            // Brush blend mode (§1.7): the buffered stroke is blended into the layer instead of plain over.
            // Wet edges (§1.7): the rim factor scales the premultiplied pixel on composite; 0 skips the blur.
            // TODO: the live preview still composites [strokeBlend] with normal source-over.
            val f = if (wetEdges > 0f) wetFactors(key) else null
            if (blended && !erase && !alphaLock) {
                blendTile(s, dst, op, f)
                dst.markDirty()
                continue
            }
            val d = dst
            var i = 0
            var p = 0
            while (i < Tile.CHANNELS) {
                val k = if (f == null) op else op * f[p]
                val sa = s[i + 3] * k
                if (sa > 0f) {
                    val inv = 1f - sa
                    when {
                        erase -> {
                            d[i] *= inv; d[i + 1] *= inv; d[i + 2] *= inv; d[i + 3] *= inv
                        }
                        alphaLock -> {
                            val da = d[i + 3]
                            d[i] = s[i] * k * da + d[i] * inv
                            d[i + 1] = s[i + 1] * k * da + d[i + 1] * inv
                            d[i + 2] = s[i + 2] * k * da + d[i + 2] * inv
                        }
                        else -> {
                            d[i] = s[i] * k + d[i] * inv
                            d[i + 1] = s[i + 1] * k + d[i + 1] * inv
                            d[i + 2] = s[i + 2] * k + d[i + 2] * inv
                            d[i + 3] = sa + d[i + 3] * inv
                        }
                    }
                }
                i += 4
                p++
            }
            dst.markDirty()
        }
    }

    /**
     * Composites the stroke buffer tile [src] into [dst] with [strokeBlend]:
     * `out = lerp(normal, BlendMath.blendPixel(mode), blendStrength)`, opacity [op].
     * [f] carries the wet edge factors of the tile (spec §1.7) and scales the source opacity per pixel; null is
     * the plain uniform [op].
     */
    private fun blendTile(src: Tile, dst: Tile, op: Float, f: FloatArray?) {
        val mode = strokeBlend
        val strength = blendStrength
        val s = FloatArray(4)
        val d = FloatArray(4)
        val n = FloatArray(4)
        val m = FloatArray(4)
        var i = 0
        while (i < Tile.CHANNELS) {
            val o = if (f == null) op else op * f[i shr 2]
            val sa = src[i + 3] * o
            if (sa > 0f) {
                src.read(i, s)
                dst.read(i, d)
                val inv = 1f - sa
                n[0] = s[0] * o + d[0] * inv
                n[1] = s[1] * o + d[1] * inv
                n[2] = s[2] * o + d[2] * inv
                n[3] = sa + d[3] * inv
                d.copyInto(m)
                BlendMath.blendPixel(mode, m, 0, s, 0, o)
                dst.write(
                    i,
                    n[0] + (m[0] - n[0]) * strength,
                    n[1] + (m[1] - n[1]) * strength,
                    n[2] + (m[2] - n[2]) * strength,
                    n[3] + (m[3] - n[3]) * strength,
                )
            }
            i += 4
        }
    }

    /**
     * Wet edge factors of buffer tile [key] (spec/tools/m2-brush.md §1.7), one per pixel in reading order:
     * `f = 1 − w·(1 − e)`, `e = sat((α − box₄(α))·4 + 0.5)`. The box blur is separable (horizontal then vertical,
     * radius [WET_HALO]) over the tile alpha plus a halo read from the neighbouring buffer tiles.
     */
    private fun wetFactors(key: TileKey): FloatArray {
        val s = wet ?: WetScratch().also { wet = it }
        val h = WET_HALO
        val n = WET_SPAN
        val plane = s.plane
        java.util.Arrays.fill(plane, 0f)
        val baseX = (key.tx shl Tile.SHIFT) - h
        val baseY = (key.ty shl Tile.SHIFT) - h
        for (ty in key.ty - 1..key.ty + 1) {
            for (tx in key.tx - 1..key.tx + 1) {
                val t = buffer.get(tx, ty) ?: continue
                val tileX = tx shl Tile.SHIFT
                val tileY = ty shl Tile.SHIFT
                val x0 = max(baseX, tileX) - baseX
                val x1 = min(baseX + n, tileX + Tile.SIZE) - baseX
                val y0 = max(baseY, tileY) - baseY
                val y1 = min(baseY + n, tileY + Tile.SIZE) - baseY
                if (x0 >= x1 || y0 >= y1) continue
                val sx = (baseX + x0) and Tile.MASK
                val sy = (baseY + y0) and Tile.MASK
                for (y in y0 until y1) {
                    var di = y * n + x0
                    var si = t.index(sx, sy + y - y0) + 3
                    for (x in x0 until x1) {
                        plane[di++] = t[si]
                        si += 4
                    }
                }
            }
        }
        // First pass: a horizontal box of radius h per row; only the columns the vertical pass reads are kept.
        val rows = s.rows
        val inv = 1f / (2 * h + 1)
        for (y in 0 until n) {
            val row = y * n
            var sum = 0f
            for (x in 0..2 * h) sum += plane[row + x]
            rows[row + h] = sum * inv
            for (x in h + 1 until n - h) {
                sum += plane[row + x + h] - plane[row + x - h - 1]
                rows[row + x] = sum * inv
            }
        }
        // Second pass: a vertical box over the blurred rows, then the rim factor of every tile pixel.
        val w = wetEdges
        val f = s.factor
        for (x in h until n - h) {
            var sum = 0f
            for (y in 0..2 * h) sum += rows[y * n + x]
            for (y in h until n - h) {
                if (y > h) sum += rows[(y + h) * n + x] - rows[(y - h - 1) * n + x]
                val e = ((plane[y * n + x] - sum * inv) * 4f + 0.5f).coerceIn(0f, 1f)
                f[(y - h) * Tile.SIZE + (x - h)] = 1f - w * (1f - e)
            }
        }
        return f
    }

    /**
     * Live preview of buffer tile [key] (spec/tools/m2-brush.md §1.7): the buffer with the wet edge factors applied
     * to every channel, so the rim shows before the stroke is committed; null when the tile is absent. With
     * [EffectParams.wetEdges] 0 this is a plain copy of the buffer; the caller scales it by [previewOpacity].
     */
    fun previewTile(key: TileKey): Tile? {
        val src = buffer.get(key) ?: return null
        val out = src.copy()
        if (wetEdges <= 0f) return out
        val f = wetFactors(key)
        var i = 0
        var p = 0
        while (i < Tile.CHANNELS) {
            val k = f[p]
            out[i] = src[i] * k
            out[i + 1] = src[i + 1] * k
            out[i + 2] = src[i + 2] * k
            out[i + 3] = src[i + 3] * k
            i += 4
            p++
        }
        out.markDirty()
        return out
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

    /** Reusable buffers of the wet edge blur (§1.7): the tile alpha plus halo, the horizontal pass, the factors. */
    private class WetScratch {
        val plane = FloatArray(WET_SPAN * WET_SPAN)
        val rows = FloatArray(WET_SPAN * WET_SPAN)
        val factor = FloatArray(Tile.SIZE * Tile.SIZE)
    }

    private companion object {
        fun directOpOf(mode: StrokeMode, alphaLock: Boolean): MaskOp = when {
            mode == StrokeMode.ERASE -> MaskOp.ERASE
            alphaLock -> MaskOp.OVER_ALPHA_LOCK
            else -> MaskOp.OVER
        }

        const val MAX_PER_SAMPLE = 16

        /** Radius of the wet edge box blur (§1.7); a buffer tile is read with a halo of this many pixels. */
        const val WET_HALO = 4

        /** Side of the haloed alpha plane of one buffer tile: the tile plus two [WET_HALO] rims. */
        const val WET_SPAN = Tile.SIZE + 2 * WET_HALO
        const val PI_F = kotlin.math.PI.toFloat()
        const val ZERO: Byte = 0
    }
}
