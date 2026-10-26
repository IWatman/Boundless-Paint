package app.painter.core.stroke

import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch

/** Whether a stroke deposits paint or removes it (destination-out). */
enum class StrokeMode { PAINT, ERASE }

/**
 * One brush stroke on one raster layer: stabilizer → resampler → dab renderer → compositor.
 *
 * Normally dabs go to [buffer] (premultiplied, full-canvas sparse grid) and the layer is only
 * touched in [end], where the buffer is composited with the [previewOpacity] cap. With
 * [BrushParams.buildup] dabs are applied straight to the layer ([directToLayer]); [takeDirty]
 * then reports layer tiles and [cancel] restores them.
 *
 * Predicted input points are ignored (never rendered, never committed). Non-stylus input is
 * treated as pressure 1. Erasing on an alpha-locked layer is a no-op. A locked layer is never
 * modified.
 */
class StrokeEngine(
    doc: Document,
    private val layerId: String,
    private val params: BrushParams,
    private val color: Rgba,
    private val mode: StrokeMode,
) {
    private val layer: RasterLayer = requireNotNull(doc.findNode(layerId) as? RasterLayer) {
        "no raster layer $layerId"
    }

    /** Premultiplied stroke buffer, alpha uncapped; the preview applies [previewOpacity]. */
    val buffer: TileGrid = TileGrid(doc.width, doc.height)

    /** Opacity cap the renderer must multiply [buffer] by when previewing. */
    val previewOpacity: Float = params.opacity.coerceIn(0f, 1f)

    /** True when dabs are written directly into the layer (build-up mode). */
    val directToLayer: Boolean = params.buildup

    private val inert = layer.locked || (mode == StrokeMode.ERASE && layer.alphaLock)
    private val renderer = DabRenderer()
    private val stabilizer = Stabilizer(params.smoothing, params.smoothingSpeed)
    private val before = HashMap<TileKey, Tile?>()
    private val dirty = HashSet<TileKey>()
    private var active = false

    private val bufferListener = TileTouchListener { key, _ -> dirty.add(key) }
    private val layerListener = TileTouchListener { key, tile ->
        if (!before.containsKey(key)) before[key] = tile?.copy()
        dirty.add(key)
    }

    private val directOp = when {
        mode == StrokeMode.ERASE -> DabOp.ERASE
        layer.alphaLock -> DabOp.OVER_ALPHA_LOCK
        else -> DabOp.OVER
    }

    private val sink = DabSink { x, y, size, flow, _, _ ->
        if (directToLayer) {
            renderer.render(
                layer.pixels, x, y, size, params.hardness, color.r, color.g, color.b,
                flow * color.a * previewOpacity, directOp, layerListener,
            )
        } else {
            renderer.render(
                buffer, x, y, size, params.hardness, color.r, color.g, color.b,
                flow * color.a, DabOp.OVER, bufferListener,
            )
        }
    }

    private val resampler = StrokeResampler(params, sink)

    /** Starts the stroke at [p]. */
    fun begin(p: InputPoint) {
        if (inert) return
        active = true
        resampler.begin(stabilizer.begin(normalize(p)))
    }

    /** Feeds new input points; predicted points are skipped. */
    fun add(points: List<InputPoint>) {
        if (!active) return
        for (p in points) {
            if (p.predicted) continue
            resampler.add(stabilizer.add(normalize(p)))
        }
    }

    /**
     * Finishes the stroke, applies it to the layer and returns the before/after patch,
     * or null if no pixel of the layer changed.
     */
    fun end(): TilePatch? {
        if (!active) return null
        active = false
        for (p in stabilizer.end()) resampler.add(p)
        resampler.end()
        if (!directToLayer) compositeBuffer()
        buffer.clear()
        if (before.isEmpty()) return null
        val after = HashMap<TileKey, Tile?>(before.size * 2)
        for (key in before.keys) {
            val t = layer.pixels.get(key)
            if (t != null && t.isEmpty()) {
                layer.pixels.put(key, null)
                after[key] = null
            } else {
                after[key] = t?.copy()
            }
        }
        dirty.addAll(before.keys)
        val patch = TilePatch(layerId, HashMap(before), after)
        before.clear()
        return patch
    }

    /** Aborts the stroke without changing the layer. */
    fun cancel() {
        if (directToLayer) {
            for ((key, tile) in before) {
                layer.pixels.put(key, tile)
                tile?.markDirty()
                dirty.add(key)
            }
        }
        dirty.addAll(buffer.keys())
        buffer.clear()
        before.clear()
        active = false
    }

    /** Returns and clears the keys of tiles changed since the previous call. */
    fun takeDirty(): Set<TileKey> {
        val out = HashSet(dirty)
        dirty.clear()
        return out
    }

    private fun normalize(p: InputPoint): InputPoint =
        if (p.isStylus || p.pressure == 1f) p else p.copy(pressure = 1f)

    private fun compositeBuffer() {
        val op = previewOpacity
        if (op <= 0f) return
        val lock = layer.alphaLock
        for ((key, src) in buffer.entries()) {
            val existing = layer.pixels.get(key)
            if (existing == null && (lock || mode == StrokeMode.ERASE)) continue
            if (src.isEmpty()) continue
            before[key] = existing?.copy()
            val dst = existing ?: layer.pixels.getOrCreate(key.tx, key.ty)
            val s = src.data
            val d = dst.data
            var i = 0
            while (i < s.size) {
                val sa = s[i + 3] * op
                if (sa > 0f) {
                    val inv = 1f - sa
                    when {
                        mode == StrokeMode.ERASE -> {
                            d[i] *= inv; d[i + 1] *= inv; d[i + 2] *= inv; d[i + 3] *= inv
                        }
                        lock -> {
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
}
