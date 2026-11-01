package app.painter.core.filter

import app.painter.core.history.snapshotTiles
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch
import app.painter.core.stroke.StrokeContext
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Destructive filters of spec/filters.md (M3 and the M5 batches). A filter runs over the painted area of one
 * layer, tile by tile: each tile is processed with a patch padded by [FilterParams.reach], so neighbouring tiles
 * never show a seam. Warps that can read any pixel of the content set [FilterParams.needsFullSource]; they are
 * sampled from one immutable snapshot of the painted area instead of a padded tile patch, which keeps the same
 * guarantee without a padding the size of the canvas. Pixels outside [out] are left alone.
 *
 * All arithmetic is on premultiplied RGBA floats, like the rest of the raster code.
 */
object Filters {

    private const val TILE = Tile.SIZE
    private val TWO_PI = (Math.PI * 2).toFloat()
    private val PI_F = Math.PI.toFloat()
    private val SQRT3 = sqrt(3f)
    private val SQRT3_2 = SQRT3 / 2f

    /**
     * Applies [params] to [grid] in place and returns the before/after patch for the history.
     * [selection] limits the write to its coverage; [alphaLock] keeps the original alpha per pixel.
     */
    fun apply(
        layerId: String,
        grid: TileGrid,
        params: FilterParams,
        selection: MaskGrid? = null,
        alphaLock: Boolean = false,
        threads: Int = StrokeContext.defaultThreads(),
    ): TilePatch {
        val content = if (params.coversCanvas) {
            IntRect(0, 0, grid.width, grid.height)
        } else {
            grid.bounds() ?: return TilePatch(layerId, emptyMap(), emptyMap())
        }
        val writeReach = params.writeReach.coerceAtLeast(0)
        val padded = if (params.needsFullSource) {
            content
        } else {
            IntRect(
                (content.x0 - writeReach).coerceAtLeast(0),
                (content.y0 - writeReach).coerceAtLeast(0),
                (content.x1 + writeReach).coerceAtMost(grid.width),
                (content.y1 + writeReach).coerceAtMost(grid.height),
            )
        }
        val out = if (selection != null) {
            val sel = selection.bounds() ?: return TilePatch(layerId, emptyMap(), emptyMap())
            padded.intersect(sel) ?: return TilePatch(layerId, emptyMap(), emptyMap())
        } else {
            padded
        }
        val keys = out.tileKeys(grid.width, grid.height)
        if (keys.isEmpty()) return TilePatch(layerId, emptyMap(), emptyMap())

        val before = snapshotTiles(grid, keys)
        process(grid, params, out, keys, selection, alphaLock, Geometry(content), threads, before)
        val after = snapshotTiles(grid, keys)
        return TilePatch(layerId, before, after)
    }

    /**
     * Applies [params] to [grid] in place without building a history patch; used by live filter layers, whose
     * cache is derived state and is not undoable by itself. This avoids the before/after copies that would double
     * the heap while a filter layer is recomputed after every stroke.
     */
    fun applyToGrid(grid: TileGrid, params: FilterParams, threads: Int = StrokeContext.defaultThreads()) {
        val content = if (params.coversCanvas) {
            IntRect(0, 0, grid.width, grid.height)
        } else {
            grid.bounds() ?: return
        }
        val writeReach = params.writeReach.coerceAtLeast(0)
        val padded = if (params.needsFullSource) {
            content
        } else {
            IntRect(
                (content.x0 - writeReach).coerceAtLeast(0),
                (content.y0 - writeReach).coerceAtLeast(0),
                (content.x1 + writeReach).coerceAtMost(grid.width),
                (content.y1 + writeReach).coerceAtMost(grid.height),
            )
        }
        val keys = padded.tileKeys(grid.width, grid.height)
        if (keys.isEmpty()) return
        // Cell filters need the same pre-filter view as the history path; other filters keep the zero-copy
        // path that avoids doubling the heap while a live filter layer is recomputed after every stroke.
        val source = if (params.needsStableSource) snapshotTiles(grid, keys) else null
        process(grid, params, padded, keys, null, false, Geometry(content), threads, source)
    }

    /** Runs the tile jobs of one filter pass. */
    private fun process(
        grid: TileGrid,
        params: FilterParams,
        out: IntRect,
        keys: List<TileKey>,
        selection: MaskGrid?,
        alphaLock: Boolean,
        geometry: Geometry,
        threads: Int,
        source: Map<TileKey, Tile?>?,
    ) {
        if (params.needsFullSource) {
            processFullSource(grid, params, out, keys, selection, alphaLock, geometry, threads)
            return
        }
        val jobs = keys.map { key ->
            Callable { applyTile(grid, key, params, out, selection, alphaLock, geometry, source) }
        }
        runParallel(jobs, threads)
    }

    /**
     * Runs a warp that may read any pixel of the content. The content is copied once into an immutable
     * snapshot; every worker samples it with canvas coordinates, so the result is independent of the tile
     * grid and of the thread count.
     */
    private fun processFullSource(
        grid: TileGrid,
        params: FilterParams,
        out: IntRect,
        keys: List<TileKey>,
        selection: MaskGrid?,
        alphaLock: Boolean,
        geometry: Geometry,
        threads: Int,
    ) {
        val content = geometry.content
        val src = readRect(grid, content.x0, content.y0, content.width, content.height)
        // A palette is a property of the whole content, so it is extracted once before the tiles run.
        val palette = if (params is PaletteParams) buildPaletteLut(src, params.colors) else null
        val jobs = keys.map { key ->
            Callable { applyFullSourceTile(grid, key, params, out, selection, alphaLock, geometry, src, palette) }
        }
        runParallel(jobs, threads)
    }

    /** Centre and radius of the painted area, for the radial filters. */
    private class Geometry(val content: IntRect) {
        val cx = content.x0 + content.width / 2f
        val cy = content.y0 + content.height / 2f
        val radius = max(content.width, content.height) / 2f
    }

    private fun applyTile(
        grid: TileGrid,
        key: TileKey,
        params: FilterParams,
        out: IntRect,
        selection: MaskGrid?,
        alphaLock: Boolean,
        geometry: Geometry,
        source: Map<TileKey, Tile?>?,
    ) {
        val tileX = key.tx * TILE
        val tileY = key.ty * TILE
        val x0 = max(out.x0, tileX)
        val y0 = max(out.y0, tileY)
        val x1 = min(out.x1, tileX + TILE)
        val y1 = min(out.y1, tileY + TILE)
        if (x1 <= x0 || y1 <= y0) return

        val reach = params.reach
        val ox = (tileX - reach).coerceAtLeast(0)
        val oy = (tileY - reach).coerceAtLeast(0)
        val ex = (tileX + TILE + reach).coerceAtMost(grid.width)
        val ey = (tileY + TILE + reach).coerceAtMost(grid.height)
        val w = ex - ox
        val h = ey - oy
        val src = readRect(grid, ox, oy, w, h, source)
        val dst = filter(params, src, w, h, ox, oy, geometry)

        val tile = grid.getOrCreate(key.tx, key.ty)
        val maskTile = selection?.get(key)
        val maskConst = if (selection != null && maskTile == null) {
            (selection.defaultValue.toInt() and 0xFF) / 255f
        } else {
            1f
        }
        val px = FloatArray(4)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val j = ((y - oy) * w + (x - ox)) * 4
                storePixel(
                    tile, tileX, tileY, selection != null, maskTile, maskConst, x, y,
                    dst[j], dst[j + 1], dst[j + 2], dst[j + 3], alphaLock, px,
                )
            }
        }
        tile.markDirty()
    }

    /** One tile of a full-source warp; [src] is the immutable content snapshot of [geometry]. */
    private fun applyFullSourceTile(
        grid: TileGrid,
        key: TileKey,
        params: FilterParams,
        out: IntRect,
        selection: MaskGrid?,
        alphaLock: Boolean,
        geometry: Geometry,
        src: FloatArray,
        palette: IntArray?,
    ) {
        val tileX = key.tx * TILE
        val tileY = key.ty * TILE
        val x0 = max(out.x0, tileX)
        val y0 = max(out.y0, tileY)
        val x1 = min(out.x1, tileX + TILE)
        val y1 = min(out.y1, tileY + TILE)
        if (x1 <= x0 || y1 <= y0) return

        val content = geometry.content
        val w = content.width
        val h = content.height
        val strength = strengthOf(params).coerceIn(0f, 1f)
        val tile = grid.getOrCreate(key.tx, key.ty)
        val maskTile = selection?.get(key)
        val maskConst = if (selection != null && maskTile == null) {
            (selection.defaultValue.toInt() and 0xFF) / 255f
        } else {
            1f
        }
        val px = FloatArray(4)
        val warped = FloatArray(4)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val i = ((y - content.y0) * w + (x - content.x0)) * 4
                globalPixel(params, src, w, h, content.x0, content.y0, geometry, x, y, palette, warped)
                val r = src[i] + (warped[0] - src[i]) * strength
                val g = src[i + 1] + (warped[1] - src[i + 1]) * strength
                val b = src[i + 2] + (warped[2] - src[i + 2]) * strength
                val a = src[i + 3] + (warped[3] - src[i + 3]) * strength
                storePixel(
                    tile, tileX, tileY, selection != null, maskTile, maskConst, x, y,
                    r, g, b, a, alphaLock, px,
                )
            }
        }
        tile.markDirty()
    }

    /** Applies the selection coverage and alpha lock to one filtered pixel and writes it into [tile]. */
    private fun storePixel(
        tile: Tile,
        tileX: Int,
        tileY: Int,
        hasSelection: Boolean,
        maskTile: MaskTile?,
        maskConst: Float,
        x: Int,
        y: Int,
        rIn: Float,
        gIn: Float,
        bIn: Float,
        aIn: Float,
        alphaLock: Boolean,
        px: FloatArray,
    ) {
        val i = tile.index(x - tileX, y - tileY)
        var r = rIn
        var g = gIn
        var b = bIn
        var a = aIn
        if (alphaLock) {
            tile.read(i, px)
            val alpha = px[3]
            if (alpha > 0f) {
                val k = if (a > 0f) alpha / a else 0f
                r *= k; g *= k; b *= k; a = alpha
            } else {
                r = 0f; g = 0f; b = 0f; a = 0f
            }
        }
        val cov = when {
            !hasSelection -> 1f
            maskTile != null -> (maskTile.data[(y - tileY) * TILE + (x - tileX)].toInt() and 0xFF) / 255f
            else -> maskConst
        }
        if (cov < 1f) {
            tile.read(i, px)
            r = px[0] + (r - px[0]) * cov
            g = px[1] + (g - px[1]) * cov
            b = px[2] + (b - px[2]) * cov
            a = px[3] + (a - px[3]) * cov
        }
        tile.write(i, r, g, b, a)
    }

    /**
     * Copies the [ox],[oy]-based [w]×[h] rect of [grid] into a premultiplied float buffer. Tiles present in
     * [source] are read from that pre-filter snapshot, so a worker never sees a neighbour that another worker
     * has already rewritten.
     */
    private fun readRect(
        grid: TileGrid,
        ox: Int,
        oy: Int,
        w: Int,
        h: Int,
        source: Map<TileKey, Tile?>? = null,
    ): FloatArray {
        val out = FloatArray(w * h * 4)
        for (key in IntRect(ox, oy, ox + w, oy + h).tileKeys(grid.width, grid.height)) {
            val tile = if (source != null && source.containsKey(key)) source[key] else grid.get(key)
            if (tile == null) continue
            val tx0 = max(ox, key.tx * TILE)
            val ty0 = max(oy, key.ty * TILE)
            val tx1 = min(ox + w, key.tx * TILE + TILE)
            val ty1 = min(oy + h, key.ty * TILE + TILE)
            for (y in ty0 until ty1) {
                val tyi = y - key.ty * TILE
                for (x in tx0 until tx1) {
                    val i = tile.index(x - key.tx * TILE, tyi)
                    val j = ((y - oy) * w + (x - ox)) * 4
                    out[j] = tile[i]
                    out[j + 1] = tile[i + 1]
                    out[j + 2] = tile[i + 2]
                    out[j + 3] = tile[i + 3]
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- the filters

    private fun filter(
        params: FilterParams,
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        geometry: Geometry,
    ): FloatArray = when (params) {
        is BlurParams -> mix(src, gaussian(src, w, h, params.radius.roundToInt()), params.strength)
        is MotionBlurParams -> mix(src, motionBlur(src, w, h, params), params.strength)
        is PixelateParams -> mix(src, pixelate(src, w, h, ox, oy, params.cell), params.strength)
        is SharpenParams -> mix(src, sharpen(src, w, h, params), params.strength)
        is GlowParams -> mix(src, glow(src, w, h, params), params.strength)
        is NoiseParams -> mix(src, noise(src, w, h, ox, oy, params), params.strength)
        is EdgesParams -> mix(src, edges(src, w, h, params.amount), params.strength)
        is ChromaticParams -> mix(src, chromatic(src, w, h, params), params.strength)
        is FisheyeParams -> mix(src, fisheye(src, w, h, ox, oy, geometry, params), params.strength)
        is DilateParams -> mix(src, morph(src, w, h, params.radius, dilate = true), params.strength)
        is ErodeParams -> mix(src, morph(src, w, h, params.radius, dilate = false), params.strength)
        is ZoomBlurParams -> mix(src, zoomBlur(src, w, h, ox, oy, geometry, params), params.strength)
        is SpinBlurParams -> mix(src, spinBlur(src, w, h, ox, oy, geometry, params), params.strength)
        is SmoothParams -> mix(src, smooth(src, w, h, params), params.strength)
        is LensBlurParams -> mix(src, lensBlur(src, w, h, params), params.strength)
        is HighPassParams -> mix(src, highPass(src, w, h, params), params.strength)
        is ScatterParams -> mix(src, scatter(src, w, h, ox, oy, params), params.strength)
        is GrainParams -> mix(src, grain(src, w, h, ox, oy, params), params.strength)
        is LocalContrastParams -> mix(src, localContrast(src, w, h, params), params.strength)
        is BokehParams -> mix(src, bokeh(src, w, h, params), params.strength)
        is LensFlareParams -> mix(src, lensFlare(src, w, h, ox, oy, geometry, params), params.strength)
        is GodRaysParams -> mix(src, godRays(src, w, h, ox, oy, geometry, params), params.strength)
        is CrossFilterParams -> mix(src, crossFilter(src, w, h, params), params.strength)
        is RadialLinesParams -> mix(src, radialLines(src, w, h, ox, oy, geometry, params), params.strength)
        is SpeedLinesParams -> mix(src, speedLines(src, w, h, ox, oy, geometry, params), params.strength)
        is HalftoneParams -> mix(src, halftone(src, w, h, ox, oy, params), params.strength)
        is LayerStrokeParams -> layerStroke(src, w, h, params)
        is DropShadowParams -> dropShadow(src, w, h, params)
        is LayerGlowParams -> layerGlow(src, w, h, params)
        is PanelGridParams -> panelGrid(src, w, h, params)
        is ScreentoneParams -> screentone(src, w, h, params)
        is CrystallizeParams -> mix(src, crystallize(src, w, h, ox, oy, params), params.strength)
        is HexPixelateParams -> mix(src, hexPixelate(src, w, h, ox, oy, params), params.strength)
        is TrianglePixelateParams -> mix(src, trianglePixelate(src, w, h, ox, oy, params), params.strength)
        is PointillizeParams -> mix(src, pointillize(src, w, h, ox, oy, params), params.strength)
        is GloomParams -> mix(src, gloom(src, w, h, params), params.strength)
        is SketchParams -> mix(src, sketch(src, w, h, params), params.strength)
        is TraceParams -> mix(src, traceEdges(src, w, h), params.strength)
        is BevelParams -> mix(src, bevel(src, w, h, params), params.strength)
        is EmbossParams -> mix(src, emboss(src, w, h, params), params.strength)
        is ReliefParams -> mix(src, relief(src, w, h, params), params.strength)
        is SatinParams -> mix(src, satin(src, w, h, ox, oy, params), params.strength)
        is WetEdgeParams -> mix(src, wetEdge(src, w, h, params), params.strength)
        is ExtrudeParams -> mix(src, extrude(src, w, h, params), params.strength)
        is CloudsParams -> mix(src, clouds(src, w, h, ox, oy, params), params.strength)
        is QrCodeParams -> mix(src, qrCode(src, w, h, ox, oy, geometry, params), params.strength)
        is StainedGlassParams -> mix(src, stainedGlass(src, w, h, ox, oy, params), params.strength)
        is DotsParams -> mix(src, dots(src, w, h, ox, oy, params), params.strength)
        is StampParams -> mix(src, stamp(src, params), params.strength)
        is GlitchParams -> mix(src, glitch(src, w, h, ox, oy, params), params.strength)
        is RetroGameParams -> mix(src, retroGame(src, w, h, ox, oy, params), params.strength)
        is ChromeParams -> mix(src, chrome(src, w, h, params), params.strength)
        is SheerParams -> mix(src, sheer(src, w, h, ox, oy), params.strength)
        is ImpressionistParams -> mix(src, impressionist(src, w, h, ox, oy, params), params.strength)
        is SwirlPaintingParams -> mix(src, swirlPainting(src, w, h, ox, oy), params.strength)
        is PopArtParams -> mix(src, popArt(src, w, h, ox, oy, params), params.strength)
        is MangaParams -> mix(
            src,
            mangaTone(
                src, w, h, ox, oy, params.toneSize,
                brightness = params.brightness.coerceIn(-100f, 100f) / 100f,
                contrast = 0f,
                edge = params.edge / 100f,
                background = false,
            ),
            params.strength,
        )
        is MangaBackgroundParams -> mix(
            src,
            mangaTone(
                src, w, h, ox, oy, params.toneSize,
                brightness = 0f,
                contrast = params.contrast / 100f,
                edge = params.edgeDarkness.coerceIn(0f, 1f),
                background = true,
            ),
            params.strength,
        )
        is AnimeBackgroundParams -> mix(src, animeBackground(src, params), params.strength)
        is PencilSketchParams -> mix(src, pencilSketch(src, w, h, ox, oy, params), params.strength)
        is OilPaintParams -> mix(src, oilPaint(src, w, h, params), params.strength)
        is BlurFrameParams -> mix(src, blurFrame(src, w, h, ox, oy, geometry, params), params.strength)
        is TableFrameParams -> mix(src, tableFrame(src, w, h, ox, oy, geometry, params), params.strength)
        is RainParams -> mix(src, rain(src, w, h, ox, oy, params), params.strength)
        is SphereLensParams, is WaveParams, is RippleParams, is TwirlParams, is PolarCoordinatesParams,
        is ExpansionParams, is KaleidoscopeParams, is WaterdropParams, is PaletteParams ->
            throw IllegalArgumentException("${params.kind} is processed from the full content snapshot")
    }

    /** Common strength of a full-source warp. */
    private fun strengthOf(params: FilterParams): Float = when (params) {
        is SphereLensParams -> params.strength
        is WaveParams -> params.strength
        is RippleParams -> params.strength
        is TwirlParams -> params.strength
        is PolarCoordinatesParams -> params.strength
        is ExpansionParams -> params.strength
        is KaleidoscopeParams -> params.strength
        is WaterdropParams -> params.strength
        is PaletteParams -> params.strength
        else -> 1f
    }

    /**
     * One pixel of a full-source warp: [src] is the content snapshot with its top-left at [ox],[oy].
     * Coordinates are canvas pixels, so the mapping never depends on the tile that owns the pixel.
     */
    private fun globalPixel(
        params: FilterParams,
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        geometry: Geometry,
        x: Int,
        y: Int,
        palette: IntArray?,
        out: FloatArray,
    ) {
        val gx = x.toFloat()
        val gy = y.toFloat()
        when (params) {
            is PaletteParams -> {
                val i = (((y - oy) * w + (x - ox)) * 4).coerceIn(0, src.size - 4)
                val a = src[i + 3]
                if (a <= 0f || palette == null || palette.isEmpty()) {
                    out.fill(0f)
                } else {
                    val r = ((src[i] / a).coerceIn(0f, 1f) * 31f).roundToInt()
                    val g = ((src[i + 1] / a).coerceIn(0f, 1f) * 31f).roundToInt()
                    val b = ((src[i + 2] / a).coerceIn(0f, 1f) * 31f).roundToInt()
                    val rgb = palette[(r shl 10) or (g shl 5) or b]
                    out[0] = ((rgb shr 16) and 0xFF) / 255f * a
                    out[1] = ((rgb shr 8) and 0xFF) / 255f * a
                    out[2] = (rgb and 0xFF) / 255f * a
                    out[3] = a
                }
            }
            is SphereLensParams -> {
                val cx = geometry.content.x0 + geometry.content.width * params.centerX.coerceIn(0f, 1f)
                val cy = geometry.content.y0 + geometry.content.height * params.centerY.coerceIn(0f, 1f)
                val radius = params.radius.coerceIn(1f, 2000f)
                val dx = gx - cx
                val dy = gy - cy
                val r = hypot(dx, dy)
                if (r < 1e-4f || r >= radius) {
                    sample(src, w, h, gx - ox, gy - oy, out)
                } else {
                    val t = r / radius
                    // Strong magnification in the middle, identity at the rim.
                    val f = 1f - 0.9f * (1f - t * t)
                    sample(src, w, h, cx + dx * f - ox, cy + dy * f - oy, out)
                }
            }
            is WaveParams -> {
                val amplitude = params.amplitude.coerceIn(0f, 200f)
                val wavelength = params.wavelength.coerceIn(4f, 1000f)
                val a = Math.toRadians(params.angleDeg.toDouble()).toFloat()
                val dx = cos(a)
                val dy = sin(a)
                val phase = (gx * dx + gy * dy) / wavelength * TWO_PI
                val offset = amplitude * sin(phase)
                sample(src, w, h, gx - dy * offset - ox, gy + dx * offset - oy, out)
            }
            is RippleParams -> {
                val cx = geometry.content.x0 + geometry.content.width * params.centerX.coerceIn(0f, 1f)
                val cy = geometry.content.y0 + geometry.content.height * params.centerY.coerceIn(0f, 1f)
                val dx = gx - cx
                val dy = gy - cy
                val r = hypot(dx, dy)
                val amplitude = params.amplitude.coerceIn(0f, 100f)
                val frequency = params.frequency.coerceIn(0.1f, 40f)
                if (r < 1e-4f) {
                    sample(src, w, h, gx - ox, gy - oy, out)
                } else {
                    val phase = r / 100f * frequency * TWO_PI
                    val radius = (r + amplitude * sin(phase)).coerceAtLeast(0f)
                    sample(src, w, h, cx + dx / r * radius - ox, cy + dy / r * radius - oy, out)
                }
            }
            is TwirlParams -> {
                val cx = geometry.content.x0 + geometry.content.width * params.centerX.coerceIn(0f, 1f)
                val cy = geometry.content.y0 + geometry.content.height * params.centerY.coerceIn(0f, 1f)
                val radius = params.radius.coerceIn(1f, 2000f)
                val dx = gx - cx
                val dy = gy - cy
                val r = hypot(dx, dy)
                if (r >= radius) {
                    sample(src, w, h, gx - ox, gy - oy, out)
                } else {
                    val t = r / radius
                    val angle = Math.toRadians(params.angleDeg.toDouble().coerceIn(-720.0, 720.0))
                        .toFloat() * (1f - t) * (1f - t)
                    val ca = cos(-angle)
                    val sa = sin(-angle)
                    sample(src, w, h, cx + dx * ca - dy * sa - ox, cy + dx * sa + dy * ca - oy, out)
                }
            }
            is PolarCoordinatesParams -> polarPixel(params, src, w, h, ox, oy, geometry, gx, gy, out)
            is ExpansionParams -> {
                val cx = geometry.cx
                val cy = geometry.cy
                val halfW = (geometry.content.width / 2f).coerceAtLeast(1f)
                val halfH = (geometry.content.height / 2f).coerceAtLeast(1f)
                val nx = ((gx - cx) / halfW).coerceIn(-1f, 1f)
                val ny = ((gy - cy) / halfH).coerceIn(-1f, 1f)
                val window = (1f - nx * nx) * (1f - ny * ny)
                val k = 0.6f * window
                sample(src, w, h, cx + (gx - cx) * (1f - k) - ox, cy + (gy - cy) * (1f - k) - oy, out)
            }
            is KaleidoscopeParams -> {
                val cx = geometry.content.x0 + geometry.content.width * params.centerX.coerceIn(0f, 1f)
                val cy = geometry.content.y0 + geometry.content.height * params.centerY.coerceIn(0f, 1f)
                val dx = gx - cx
                val dy = gy - cy
                val r = hypot(dx, dy)
                if (r < 1e-4f) {
                    sample(src, w, h, gx - ox, gy - oy, out)
                } else {
                    val segments = params.segments.coerceIn(1, 12)
                    val wedge = TWO_PI / segments
                    val angle = atan2(dy, dx)
                    var folded = angle - floor(angle / wedge) * wedge
                    val half = wedge / 2f
                    if (folded > half) folded = wedge - folded
                    sample(src, w, h, cx + cos(folded) * r - ox, cy + sin(folded) * r - oy, out)
                }
            }
            is WaterdropParams -> waterdropPixel(params, src, w, h, ox, oy, geometry, gx, gy, out)
            else -> sample(src, w, h, gx - ox, gy - oy, out)
        }
    }

    /**
     * One pixel of the water drop: the disc magnifies its middle and pinches its rim like a plano-convex
     * lens, a dark ring at the rim sells the refraction and a small specular spot sells the water.
     */
    private fun waterdropPixel(
        params: WaterdropParams,
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        geometry: Geometry,
        gx: Float,
        gy: Float,
        out: FloatArray,
    ) {
        val cx = geometry.cx
        val cy = geometry.cy
        val radius = (min(geometry.content.width, geometry.content.height) * 0.38f).coerceAtLeast(8f)
        val height = params.height.coerceIn(0f, 100f) / 100f
        val gloss = params.gloss.coerceIn(0f, 1f)
        val dx = gx - cx
        val dy = gy - cy
        val r = hypot(dx, dy)
        if (r >= radius) {
            sample(src, w, h, gx - ox, gy - oy, out)
            return
        }
        val t = r / radius
        // A plano-convex drop is thickest in the middle and flat at the rim.
        val lens = (0.12f + 0.48f * height) * (1f - t * t)
        sample(src, w, h, cx + dx * (1f - lens) - ox, cy + dy * (1f - lens) - oy, out)
        val rim = (1f - abs(t - 0.9f) / 0.1f).coerceIn(0f, 1f)
        if (rim > 0f) {
            val shade = 1f - rim * (0.2f + 0.25f * height)
            out[0] *= shade
            out[1] *= shade
            out[2] *= shade
        }
        val spotX = cx - radius * 0.34f
        val spotY = cy - radius * 0.4f
        val spotRadius = radius * 0.14f
        val spot = hypot(gx - spotX, gy - spotY)
        if (gloss > 0f && spot < spotRadius) {
            val k = (1f - spot / spotRadius).let { it * it } * gloss
            out[0] = (out[0] + k).coerceAtMost(1f)
            out[1] = (out[1] + k).coerceAtMost(1f)
            out[2] = (out[2] + k).coerceAtMost(1f)
            // The highlight is additive white light, so it lifts the alpha with the colour.
            out[3] = max(out[3], max(out[0], max(out[1], out[2])))
        }
    }

    /** Rectangular-to-polar and back; the disc uses the short side of the content. */
    private fun polarPixel(
        params: PolarCoordinatesParams,
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        geometry: Geometry,
        gx: Float,
        gy: Float,
        out: FloatArray,
    ) {
        val content = geometry.content
        val cx = geometry.cx
        val cy = geometry.cy
        val radius = (min(content.width, content.height) / 2f).coerceAtLeast(1f)
        if (params.variant.lowercase() == "polar-to-rect") {
            val u = ((gx - content.x0) / content.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            val v = ((gy - content.y0) / content.height.coerceAtLeast(1)).coerceIn(0f, 1f)
            val angle = u * TWO_PI - PI_F
            val r = v * radius
            sample(src, w, h, cx + cos(angle) * r - ox, cy + sin(angle) * r - oy, out)
        } else {
            val dx = gx - cx
            val dy = gy - cy
            val r = hypot(dx, dy)
            if (r > radius) {
                sample(src, w, h, gx - ox, gy - oy, out)
            } else {
                val angle = atan2(dy, dx)
                val u = (angle + PI_F) / TWO_PI
                val v = r / radius
                sample(src, w, h, content.x0 + u * content.width - ox, content.y0 + v * content.height - oy, out)
            }
        }
    }

    /** `a·(1-t) + b·t` per channel, clamped to a non-negative premultiplied pixel. */
    private fun mix(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        val k = t.coerceIn(0f, 1f)
        if (k >= 1f) return b
        if (k <= 0f) return a
        val out = FloatArray(a.size)
        for (i in a.indices) out[i] = a[i] + (b[i] - a[i]) * k
        return out
    }

    /** Three box passes with a sliding window; [radius] is the box radius in px. */
    private fun gaussian(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val r = radius.coerceIn(0, 250)
        if (r == 0) return src.copyOf()
        val a = src.copyOf()
        val temp = FloatArray(src.size)
        repeat(3) {
            boxH(a, temp, w, h, r)
            boxV(temp, a, w, h, r)
        }
        return a
    }

    /**
     * Two box passes. Unlike the three-pass [gaussian], its support is exactly `2r`, which lets the M5
     * filters keep a reach that covers every pixel they read.
     */
    private fun localBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val r = radius.coerceIn(0, 32)
        if (r == 0) return src.copyOf()
        val a = src.copyOf()
        val temp = FloatArray(src.size)
        repeat(2) {
            boxH(a, temp, w, h, r)
            boxV(temp, a, w, h, r)
        }
        return a
    }

    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w * 4
            var sr = 0f; var sg = 0f; var sb = 0f; var sa = 0f
            for (k in -r..r) {
                val x = k.coerceIn(0, w - 1)
                val i = row + x * 4
                sr += src[i]; sg += src[i + 1]; sb += src[i + 2]; sa += src[i + 3]
            }
            for (x in 0 until w) {
                val i = row + x * 4
                dst[i] = sr / n; dst[i + 1] = sg / n; dst[i + 2] = sb / n; dst[i + 3] = sa / n
                val outX = (x - r).coerceIn(0, w - 1)
                val inX = (x + r + 1).coerceIn(0, w - 1)
                val o = row + outX * 4
                val q = row + inX * 4
                sr += src[q] - src[o]
                sg += src[q + 1] - src[o + 1]
                sb += src[q + 2] - src[o + 2]
                sa += src[q + 3] - src[o + 3]
            }
        }
    }

    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        val stride = w * 4
        for (x in 0 until w) {
            val col = x * 4
            var sr = 0f; var sg = 0f; var sb = 0f; var sa = 0f
            for (k in -r..r) {
                val y = k.coerceIn(0, h - 1)
                val i = col + y * stride
                sr += src[i]; sg += src[i + 1]; sb += src[i + 2]; sa += src[i + 3]
            }
            for (y in 0 until h) {
                val i = col + y * stride
                dst[i] = sr / n; dst[i + 1] = sg / n; dst[i + 2] = sb / n; dst[i + 3] = sa / n
                val outY = (y - r).coerceIn(0, h - 1)
                val inY = (y + r + 1).coerceIn(0, h - 1)
                val o = col + outY * stride
                val q = col + inY * stride
                sr += src[q] - src[o]
                sg += src[q + 1] - src[o + 1]
                sb += src[q + 2] - src[o + 2]
                sa += src[q + 3] - src[o + 3]
            }
        }
    }

    private fun motionBlur(src: FloatArray, w: Int, h: Int, p: MotionBlurParams): FloatArray {
        val dist = p.distance.coerceIn(0f, 500f)
        if (dist <= 0.5f) return src.copyOf()
        val rad = Math.toRadians(p.angleDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val samples = dist.roundToInt().coerceIn(2, 64)
        val out = FloatArray(src.size)
        val s = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f; var a = 0f
            for (n in 0 until samples) {
                val t = (n / (samples - 1f) - 0.5f) * dist
                sample(src, w, h, x + dx * t, y + dy * t, s)
                r += s[0]; g += s[1]; b += s[2]; a += s[3]
            }
            val i = (y * w + x) * 4
            out[i] = r / samples; out[i + 1] = g / samples; out[i + 2] = b / samples; out[i + 3] = a / samples
        }
        return out
    }

    private fun pixelate(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int, cellPx: Int): FloatArray {
        val cell = cellPx.coerceIn(2, 200)
        val out = src.copyOf()
        val gx0 = (ox / cell) * cell
        val gy0 = (oy / cell) * cell
        for (cy in gy0 until oy + h step cell) {
            for (cx in gx0 until ox + w step cell) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                var n = 0
                val xa = max(cx, ox); val xb = min(cx + cell, ox + w)
                val ya = max(cy, oy); val yb = min(cy + cell, oy + h)
                if (xb <= xa || yb <= ya) continue
                for (y in ya until yb) for (x in xa until xb) {
                    val i = ((y - oy) * w + (x - ox)) * 4
                    r += src[i]; g += src[i + 1]; b += src[i + 2]; a += src[i + 3]
                    n++
                }
                if (n == 0) continue
                r /= n; g /= n; b /= n; a /= n
                for (y in ya until yb) for (x in xa until xb) {
                    val i = ((y - oy) * w + (x - ox)) * 4
                    out[i] = r; out[i + 1] = g; out[i + 2] = b; out[i + 3] = a
                }
            }
        }
        return out
    }

    private fun sharpen(src: FloatArray, w: Int, h: Int, p: SharpenParams): FloatArray {
        val blurred = gaussian(src, w, h, ceil(p.radius.coerceIn(0.1f, 100f)).toInt())
        val amount = p.amount.coerceIn(0f, 5f)
        val out = FloatArray(src.size)
        for (i in src.indices) {
            out[i] = (src[i] + (src[i] - blurred[i]) * amount).coerceIn(0f, 1f)
        }
        // Unsharp keeps the source alpha: a sharpened edge must not grow the layer.
        for (i in src.indices step 4) out[i + 3] = src[i + 3]
        return out
    }

    private fun glow(src: FloatArray, w: Int, h: Int, p: GlowParams): FloatArray {
        val threshold = p.threshold.coerceIn(0f, 1f)
        val bright = FloatArray(src.size)
        for (i in src.indices step 4) {
            val a = src[i + 3]
            if (a <= 0f) continue
            val l = (0.3f * src[i] + 0.59f * src[i + 1] + 0.11f * src[i + 2]) / a
            if (l >= threshold) {
                val k = (l - threshold) / max(1f - threshold, 1e-4f)
                bright[i] = src[i] * k; bright[i + 1] = src[i + 1] * k; bright[i + 2] = src[i + 2] * k
                bright[i + 3] = a * k
            }
        }
        val halo = gaussian(bright, w, h, ceil(p.radius.coerceIn(0f, 250f)).toInt())
        val strength = p.strength.coerceIn(0f, 2f)
        val out = src.copyOf()
        for (i in src.indices step 4) {
            // Additive bloom on the premultiplied colour, alpha only grows.
            out[i] = (src[i] + halo[i] * strength).coerceIn(0f, 1f)
            out[i + 1] = (src[i + 1] + halo[i + 1] * strength).coerceIn(0f, 1f)
            out[i + 2] = (src[i + 2] + halo[i + 2] * strength).coerceIn(0f, 1f)
            out[i + 3] = max(src[i + 3], (halo[i + 3] * strength).coerceIn(0f, 1f))
        }
        return out
    }

    private fun noise(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int, p: NoiseParams): FloatArray {
        val amount = p.amount.coerceIn(0f, 1f)
        val out = src.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            val a = src[i + 3]
            if (a <= 0f) continue
            val nr = hash(x + ox, y + oy, p.seed)
            val ng = if (p.mono) nr else hash(x + ox, y + oy, p.seed + 1)
            val nb = if (p.mono) nr else hash(x + ox, y + oy, p.seed + 2)
            out[i] = (src[i] + nr * amount * a).coerceIn(0f, 1f)
            out[i + 1] = (src[i + 1] + ng * amount * a).coerceIn(0f, 1f)
            out[i + 2] = (src[i + 2] + nb * amount * a).coerceIn(0f, 1f)
        }
        return out
    }

    private fun edges(src: FloatArray, w: Int, h: Int, amount: Float): FloatArray {
        val out = FloatArray(src.size)
        val a = amount.coerceIn(0f, 3f)
        fun lum(x: Int, y: Int): Float {
            val i = (y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)) * 4
            val alpha = src[i + 3]
            if (alpha <= 0f) return 0f
            return (0.3f * src[i] + 0.59f * src[i + 1] + 0.11f * src[i + 2]) / alpha * alpha
        }
        for (y in 0 until h) for (x in 0 until w) {
            val gx = lum(x - 1, y - 1) + 2f * lum(x - 1, y) + lum(x - 1, y + 1) -
                lum(x + 1, y - 1) - 2f * lum(x + 1, y) - lum(x + 1, y + 1)
            val gy = lum(x - 1, y - 1) + 2f * lum(x, y - 1) + lum(x + 1, y - 1) -
                lum(x - 1, y + 1) - 2f * lum(x, y + 1) - lum(x + 1, y + 1)
            val e = (sqrt(gx * gx + gy * gy) * a).coerceIn(0f, 1f)
            val i = (y * w + x) * 4
            val alpha = src[i + 3]
            out[i] = e * alpha
            out[i + 1] = e * alpha
            out[i + 2] = e * alpha
            out[i + 3] = alpha
        }
        return out
    }

    private fun chromatic(src: FloatArray, w: Int, h: Int, p: ChromaticParams): FloatArray {
        val d = p.distance.coerceIn(0f, 200f)
        if (d <= 0.01f) return src.copyOf()
        val rad = Math.toRadians(p.angleDeg.toDouble())
        val dx = cos(rad).toFloat() * d
        val dy = sin(rad).toFloat() * d
        val out = src.copyOf()
        val s = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            sample(src, w, h, x + dx, y + dy, s)
            out[i] = s[0]
            sample(src, w, h, x - dx, y - dy, s)
            out[i + 2] = s[2]
        }
        return out
    }

    private fun fisheye(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        geometry: Geometry,
        p: FisheyeParams,
    ): FloatArray {
        val amount = p.amount.coerceIn(-1f, 1f)
        if (abs(amount) < 0.001f) return src.copyOf()
        val radius = (geometry.radius * p.scale.coerceIn(0.1f, 2f)).coerceAtLeast(1f)
        val out = FloatArray(src.size)
        val s = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x + ox) - geometry.cx
            val dy = (y + oy) - geometry.cy
            val r = sqrt(dx * dx + dy * dy)
            val i = (y * w + x) * 4
            if (r >= radius || r < 1e-4f) {
                out[i] = src[i]; out[i + 1] = src[i + 1]; out[i + 2] = src[i + 2]; out[i + 3] = src[i + 3]
                continue
            }
            val t = r / radius
            val f = 1f + amount * (1f - t) * (1f - t)
            val sx = geometry.cx + dx * f
            val sy = geometry.cy + dy * f
            sample(src, w, h, sx - ox, sy - oy, s)
            out[i] = s[0]; out[i + 1] = s[1]; out[i + 2] = s[2]; out[i + 3] = s[3]
        }
        return out
    }

    // ---------------------------------------------------------------- morphology and radial blur

    /** Separable max (dilate) or min (erode) over a square window, per premultiplied channel. */
    private fun morph(src: FloatArray, w: Int, h: Int, radius: Int, dilate: Boolean): FloatArray {
        val r = radius.coerceIn(1, 20)
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r0 = if (dilate) 0f else 1f
                var r1 = r0; var r2 = r0; var r3 = r0
                for (k in -r..r) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    val i = (y * w + xx) * 4
                    if (dilate) {
                        if (src[i] > r0) r0 = src[i]
                        if (src[i + 1] > r1) r1 = src[i + 1]
                        if (src[i + 2] > r2) r2 = src[i + 2]
                        if (src[i + 3] > r3) r3 = src[i + 3]
                    } else {
                        if (src[i] < r0) r0 = src[i]
                        if (src[i + 1] < r1) r1 = src[i + 1]
                        if (src[i + 2] < r2) r2 = src[i + 2]
                        if (src[i + 3] < r3) r3 = src[i + 3]
                    }
                }
                val i = (y * w + x) * 4
                tmp[i] = r0; tmp[i + 1] = r1; tmp[i + 2] = r2; tmp[i + 3] = r3
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r0 = if (dilate) 0f else 1f
                var r1 = r0; var r2 = r0; var r3 = r0
                for (k in -r..r) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    val i = (yy * w + x) * 4
                    if (dilate) {
                        if (tmp[i] > r0) r0 = tmp[i]
                        if (tmp[i + 1] > r1) r1 = tmp[i + 1]
                        if (tmp[i + 2] > r2) r2 = tmp[i + 2]
                        if (tmp[i + 3] > r3) r3 = tmp[i + 3]
                    } else {
                        if (tmp[i] < r0) r0 = tmp[i]
                        if (tmp[i + 1] < r1) r1 = tmp[i + 1]
                        if (tmp[i + 2] < r2) r2 = tmp[i + 2]
                        if (tmp[i + 3] < r3) r3 = tmp[i + 3]
                    }
                }
                val i = (y * w + x) * 4
                out[i] = r0; out[i + 1] = r1; out[i + 2] = r2; out[i + 3] = r3
            }
        }
        return out
    }

    /** Radial blur toward the content centre: samples shrink toward [Geometry.cx],[Geometry.cy]. */
    private fun zoomBlur(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        geometry: Geometry,
        p: ZoomBlurParams,
    ): FloatArray {
        val amount = p.amount.coerceIn(0f, 0.9f)
        if (amount <= 0.001f) return src.copyOf()
        val samples = 12
        val out = FloatArray(src.size)
        val s = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val px = x + ox - geometry.cx
            val py = y + oy - geometry.cy
            var r = 0f; var g = 0f; var b = 0f; var a = 0f
            for (n in 0 until samples) {
                val f = 1f - amount * (n / (samples - 1f))
                sample(src, w, h, geometry.cx + px * f - ox, geometry.cy + py * f - oy, s)
                r += s[0]; g += s[1]; b += s[2]; a += s[3]
            }
            val i = (y * w + x) * 4
            out[i] = r / samples; out[i + 1] = g / samples; out[i + 2] = b / samples; out[i + 3] = a / samples
        }
        return out
    }

    /** Rotational blur around the content centre over [SpinBlurParams.angleDeg]. */
    private fun spinBlur(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        geometry: Geometry,
        p: SpinBlurParams,
    ): FloatArray {
        val angle = Math.toRadians(p.angleDeg.toDouble().coerceIn(-180.0, 180.0)).toFloat()
        if (abs(angle) < 0.001f) return src.copyOf()
        val samples = 12
        val out = FloatArray(src.size)
        val s = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val px = x + ox - geometry.cx
            val py = y + oy - geometry.cy
            var r = 0f; var g = 0f; var b = 0f; var a = 0f
            for (n in 0 until samples) {
                val t = angle * (n / (samples - 1f) - 0.5f)
                val ca = cos(t)
                val sa = sin(t)
                sample(src, w, h, geometry.cx + px * ca - py * sa - ox, geometry.cy + px * sa + py * ca - oy, s)
                r += s[0]; g += s[1]; b += s[2]; a += s[3]
            }
            val i = (y * w + x) * 4
            out[i] = r / samples; out[i + 1] = g / samples; out[i + 2] = b / samples; out[i + 3] = a / samples
        }
        return out
    }

    // ---------------------------------------------------------------- M5 blur, light and texture

    /**
     * Edge-preserving smoothing: blend toward a Gaussian only where the pixel is close to its blurred
     * neighbourhood, so flat areas and noise are smoothed while outlines stay crisp.
     */
    private fun smooth(src: FloatArray, w: Int, h: Int, p: SmoothParams): FloatArray {
        val radius = ceil(p.radius.coerceIn(1f, 32f)).toInt()
        val blurred = localBlur(src, w, h, radius)
        val out = FloatArray(src.size)
        for (i in src.indices step 4) {
            val a = src[i + 3]
            if (a <= 0f) continue
            var d = abs(src[i] - blurred[i])
            d = max(d, abs(src[i + 1] - blurred[i + 1]))
            d = max(d, abs(src[i + 2] - blurred[i + 2]))
            // A large difference is an outline (or a hole), so it keeps the sharper source pixel.
            val keep = ((d / a) / 0.16f).coerceIn(0f, 1f)
            val k = 1f - keep
            out[i] = src[i] + (blurred[i] - src[i]) * k
            out[i + 1] = src[i + 1] + (blurred[i + 1] - src[i + 1]) * k
            out[i + 2] = src[i + 2] + (blurred[i + 2] - src[i + 2]) * k
            out[i + 3] = a
        }
        return out
    }

    /** Aperture blur with a hexagonal opening; [LensBlurParams.highlightBoost] favours bright samples. */
    private fun lensBlur(src: FloatArray, w: Int, h: Int, p: LensBlurParams): FloatArray = apertureBlur(
        src, w, h,
        radius = p.radius.coerceIn(0f, 100f),
        angle = Math.toRadians(p.bladeAngleDeg.toDouble()).toFloat(),
        highlightBoost = p.highlightBoost.coerceIn(0f, 1f),
        polygon = true,
        directions = 24,
    )

    /**
     * Shared disc/hexagon gather. Every sample is taken from the padded patch, so the result is identical
     * on both sides of a tile boundary.
     */
    private fun apertureBlur(
        src: FloatArray,
        w: Int,
        h: Int,
        radius: Float,
        angle: Float,
        highlightBoost: Float,
        polygon: Boolean,
        directions: Int,
    ): FloatArray {
        if (radius < 0.75f) return src.copyOf()
        val rings = 3
        val count = directions * rings
        val sx = FloatArray(count)
        val sy = FloatArray(count)
        val sector = (Math.PI / 3.0).toFloat()
        val cosHalf = cos(sector / 2f)
        var n = 0
        for (d in 0 until directions) {
            val theta = (Math.PI * 2.0 * d / directions).toFloat() + angle
            val extent = if (polygon) {
                var local = (theta - angle) % sector
                if (local < 0f) local += sector
                radius * cosHalf / cos(local - sector / 2f).coerceAtLeast(0.5f)
            } else {
                radius
            }
            for (ring in 1..rings) {
                val f = ring / rings.toFloat() * extent
                sx[n] = cos(theta) * f
                sy[n] = sin(theta) * f
                n++
            }
        }
        val out = FloatArray(src.size)
        val s = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0f
            var g = 0f
            var b = 0f
            var a = 0f
            var total = 0f
            for (k in 0 until count) {
                sample(src, w, h, x + sx[k], y + sy[k], s)
                var weight = 1f
                if (highlightBoost > 0f && s[3] > 0f) {
                    val lum = (0.3f * s[0] + 0.59f * s[1] + 0.11f * s[2]) / s[3]
                    weight += highlightBoost * 3f * lum * lum
                }
                r += s[0] * weight
                g += s[1] * weight
                b += s[2] * weight
                a += s[3] * weight
                total += weight
            }
            val i = (y * w + x) * 4
            out[i] = r / total
            out[i + 1] = g / total
            out[i + 2] = b / total
            out[i + 3] = a / total
        }
        return out
    }

    /** Grey high-pass image: `0.5 + (source - blur)` per pixel, over the source alpha. */
    private fun highPass(src: FloatArray, w: Int, h: Int, p: HighPassParams): FloatArray {
        val radius = ceil(p.radius.coerceIn(0.5f, 32f)).toInt()
        val blurred = localBlur(src, w, h, radius)
        val out = FloatArray(src.size)
        for (i in src.indices step 4) {
            val a = src[i + 3]
            if (a <= 0f) continue
            val sourceLum = (0.3f * src[i] + 0.59f * src[i + 1] + 0.11f * src[i + 2]) / a
            val ba = blurred[i + 3]
            val blurLum = if (ba > 0f) {
                (0.3f * blurred[i] + 0.59f * blurred[i + 1] + 0.11f * blurred[i + 2]) / ba
            } else {
                sourceLum
            }
            val v = (0.5f + (sourceLum - blurLum) * 2f).coerceIn(0f, 1f)
            out[i] = v * a
            out[i + 1] = v * a
            out[i + 2] = v * a
            out[i + 3] = a
        }
        return out
    }

    /** Frosted glass: smooth, deterministic noise displaces the source by up to [distance] px. */
    private fun scatter(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int, p: ScatterParams): FloatArray {
        val distance = p.distance.coerceIn(1f, 50f)
        val out = FloatArray(src.size)
        val s = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = x + ox
            val gy = y + oy
            val dx = valueNoise(gx, gy, 3f, 0x51ED2701L) * distance
            val dy = valueNoise(gx, gy, 3f, 0x7F4A7C15L) * distance
            sample(src, w, h, x + dx, y + dy, s)
            val i = (y * w + x) * 4
            out[i] = s[0]
            out[i + 1] = s[1]
            out[i + 2] = s[2]
            out[i + 3] = s[3]
        }
        return out
    }

    /** Smooth value noise on a [cell]-px lattice, in -1..1, aligned to the canvas. */
    private fun valueNoise(x: Int, y: Int, cell: Float, seed: Long): Float {
        val fx = x / cell
        val fy = y / cell
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fade(fx - x0)
        val ty = fade(fy - y0)
        val n00 = hash(x0, y0, seed)
        val n10 = hash(x0 + 1, y0, seed)
        val n01 = hash(x0, y0 + 1, seed)
        val n11 = hash(x0 + 1, y0 + 1, seed)
        val top = n00 + (n10 - n00) * tx
        val bottom = n01 + (n11 - n01) * tx
        return top + (bottom - top) * ty
    }

    private fun fade(t: Float): Float = t * t * (3f - 2f * t)

    /** Deterministic grain on a [GrainParams.size]-px grid; alpha grain keeps the straight colour. */
    private fun grain(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int, p: GrainParams): FloatArray {
        val size = p.size.coerceIn(1, 16)
        val variant = p.variant.lowercase()
        val out = src.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            val a = src[i + 3]
            if (a <= 0f) continue
            val gx = (x + ox) / size
            val gy = (y + oy) / size
            if (variant == "alpha") {
                val na = (a + hash(gx, gy, 0x2C1B3C6DL) * 0.45f).coerceIn(0f, 1f)
                val k = na / a
                out[i] = (src[i] * k).coerceIn(0f, na)
                out[i + 1] = (src[i + 1] * k).coerceIn(0f, na)
                out[i + 2] = (src[i + 2] * k).coerceIn(0f, na)
                out[i + 3] = na
                continue
            }
            val nr = hash(gx, gy, 0x1B873593L) * 0.3f
            val ng = if (variant == "rgb") hash(gx, gy, 0x1B873594L) * 0.3f else nr
            val nb = if (variant == "rgb") hash(gx, gy, 0x1B873595L) * 0.3f else nr
            val sr = (src[i] / a).coerceIn(0f, 1f) + nr
            val sg = (src[i + 1] / a).coerceIn(0f, 1f) + ng
            val sb = (src[i + 2] / a).coerceIn(0f, 1f) + nb
            out[i] = sr.coerceIn(0f, 1f) * a
            out[i + 1] = sg.coerceIn(0f, 1f) * a
            out[i + 2] = sb.coerceIn(0f, 1f) * a
        }
        return out
    }

    /** Local contrast: the luminance difference from the blurred source added back to every channel. */
    private fun localContrast(src: FloatArray, w: Int, h: Int, p: LocalContrastParams): FloatArray {
        val radius = ceil(p.radius.coerceIn(1f, 32f)).toInt()
        val blurred = localBlur(src, w, h, radius)
        val out = FloatArray(src.size)
        for (i in src.indices step 4) {
            val a = src[i + 3]
            if (a <= 0f) continue
            val lum = (0.3f * src[i] + 0.59f * src[i + 1] + 0.11f * src[i + 2]) / a
            val ba = blurred[i + 3]
            val blurLum = if (ba > 0f) {
                (0.3f * blurred[i] + 0.59f * blurred[i + 1] + 0.11f * blurred[i + 2]) / ba
            } else {
                lum
            }
            val add = (lum - blurLum) * a * 2f
            out[i] = (src[i] + add).coerceIn(0f, a)
            out[i + 1] = (src[i + 1] + add).coerceIn(0f, a)
            out[i + 2] = (src[i + 2] + add).coerceIn(0f, a)
            out[i + 3] = a
        }
        return out
    }

    /** Bokeh: bright pixels are blurred through a wide disc and screened over the source. */
    private fun bokeh(src: FloatArray, w: Int, h: Int, p: BokehParams): FloatArray {
        val size = p.size.coerceIn(1f, 64f)
        val bright = FloatArray(src.size)
        for (i in src.indices step 4) {
            val a = src[i + 3]
            if (a <= 0f) continue
            val lum = (0.3f * src[i] + 0.59f * src[i + 1] + 0.11f * src[i + 2]) / a
            if (lum >= 0.5f) {
                val k = ((lum - 0.5f) / 0.5f).coerceIn(0f, 1f)
                bright[i] = src[i] * k
                bright[i + 1] = src[i + 1] * k
                bright[i + 2] = src[i + 2] * k
                bright[i + 3] = a * k
            }
        }
        val halo = apertureBlur(bright, w, h, size, 0f, 0f, polygon = false, directions = 16)
        val out = src.copyOf()
        for (i in src.indices step 4) {
            val a = src[i + 3]
            val ha = (halo[i + 3] * 2.5f).coerceIn(0f, 1f)
            val oa = max(a, ha)
            out[i] = (src[i] + halo[i] * 2.5f).coerceIn(0f, oa)
            out[i + 1] = (src[i + 1] + halo[i + 1] * 2.5f).coerceIn(0f, oa)
            out[i + 2] = (src[i + 2] + halo[i + 2] * 2.5f).coerceIn(0f, oa)
            out[i + 3] = oa
        }
        return out
    }

    /** Additive lens flare: a warm core, an aperture ring and a horizontal anamorphic streak. */
    private fun lensFlare(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        geometry: Geometry,
        p: LensFlareParams,
    ): FloatArray {
        val size = p.size.coerceIn(1f, 80f)
        val cx = geometry.content.x0 + geometry.content.width * p.centerX.coerceIn(0f, 1f)
        val cy = geometry.content.y0 + geometry.content.height * p.centerY.coerceIn(0f, 1f)
        val out = src.copyOf()
        val limit = size * 2.5f
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x + ox - cx
            val dy = y + oy - cy
            val r = hypot(dx, dy)
            if (r > limit) continue
            val t = r / size
            val core = 1f / (1f + 16f * t * t)
            val ringOffset = (r - size * 0.82f) / (size * 0.09f)
            val ring = 1f / (1f + ringOffset * ringOffset) * 0.55f
            val halo = 1f / (1f + 3f * t * t) * 0.3f
            val axis = if (r > 0.01f) min(abs(dx), abs(dy)) / r else 0f
            var star = 1f - axis
            repeat(7) { star *= (1f - axis) }
            star *= 1f / (1f + t * 0.8f) * 0.8f
            val sy2 = (abs(dy) / (size * 0.05f)).let { it * it }
            val sx2 = (abs(dx) / (size * 1.8f)).let { it * it }
            val streak = 1f / (1f + sy2) * 1f / (1f + sx2) * 0.6f
            val light = (core + ring + halo + star + streak).coerceIn(0f, 1f)
            val i = (y * w + x) * 4
            out[i] = (out[i] + light).coerceIn(0f, 1f)
            out[i + 1] = (out[i + 1] + light * 0.96f).coerceIn(0f, 1f)
            out[i + 2] = (out[i + 2] + light * 0.86f).coerceIn(0f, 1f)
            out[i + 3] = (out[i + 3] + light).coerceIn(0f, 1f)
        }
        return out
    }

    /** God rays: bright samples marched toward the centre become visible shafts. */
    private fun godRays(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        geometry: Geometry,
        p: GodRaysParams,
    ): FloatArray {
        val length = p.length.coerceIn(1f, 160f)
        val threshold = p.threshold.coerceIn(0f, 1f)
        val cx = geometry.content.x0 + geometry.content.width * p.centerX.coerceIn(0f, 1f)
        val cy = geometry.content.y0 + geometry.content.height * p.centerY.coerceIn(0f, 1f)
        val samples = 20
        val step = length / samples
        val range = max(1f - threshold, 1e-4f)
        val out = src.copyOf()
        val s = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = x + ox
            val gy = y + oy
            val tx = cx - gx
            val ty = cy - gy
            val distance = hypot(tx, ty)
            if (distance < 0.01f) continue
            val ux = tx / distance
            val uy = ty / distance
            // A narrow fan makes a shaft out of the exact centre line instead of a one-pixel ray.
            val spreadX = -uy * 0.02f
            val spreadY = ux * 0.02f
            var light = 0f
            for (fan in -1..1) {
                val dx = ux + spreadX * fan
                val dy = uy + spreadY * fan
                for (n in 1..samples) {
                    val d = step * n
                    if (d > length) break
                    sample(src, w, h, gx + dx * d - ox, gy + dy * d - oy, s)
                    val a = s[3]
                    if (a <= 0f) continue
                    val lum = (0.3f * s[0] + 0.59f * s[1] + 0.11f * s[2]) / a
                    if (lum < threshold) continue
                    val k = ((lum - threshold) / range).coerceIn(0f, 1f)
                    val falloff = 1f - (n - 1) / samples.toFloat()
                    light = max(light, k * falloff)
                }
            }
            if (light <= 0.002f) continue
            val v = (light * 0.9f).coerceIn(0f, 1f)
            val i = (y * w + x) * 4
            out[i] = (out[i] + v).coerceIn(0f, 1f)
            out[i + 1] = (out[i + 1] + v * 0.95f).coerceIn(0f, 1f)
            out[i + 2] = (out[i + 2] + v * 0.8f).coerceIn(0f, 1f)
            out[i + 3] = (out[i + 3] + v).coerceIn(0f, 1f)
        }
        return out
    }

    /** Cross filter: bright pixels leave [CrossFilterParams.rays] star spikes of [length] px. */
    private fun crossFilter(src: FloatArray, w: Int, h: Int, p: CrossFilterParams): FloatArray {
        val length = p.length.coerceIn(1f, 80f)
        val threshold = p.threshold.coerceIn(0f, 1f)
        val rays = p.rays.coerceIn(2, 8)
        val base = Math.toRadians(p.angleDeg.toDouble()).toFloat()
        val samples = 20
        val step = length / samples
        val range = max(1f - threshold, 1e-4f)
        val out = src.copyOf()
        val s = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            var br = 0f
            var bg = 0f
            var bb = 0f
            var ba = 0f
            for (ray in 0 until rays) {
                val angle = base + (Math.PI * 2.0 * ray / rays).toFloat()
                val dx = cos(angle)
                val dy = sin(angle)
                for (n in 1..samples) {
                    val d = step * n
                    sample(src, w, h, x + dx * d, y + dy * d, s)
                    val a = s[3]
                    if (a <= 0f) continue
                    val lum = (0.3f * s[0] + 0.59f * s[1] + 0.11f * s[2]) / a
                    if (lum < threshold) continue
                    val k = ((lum - threshold) / range).coerceIn(0f, 1f)
                    val falloff = 1f - (n - 1) / samples.toFloat()
                    val v = k * falloff * falloff
                    val sr = s[0] * v
                    val sg = s[1] * v
                    val sb = s[2] * v
                    if (sr + sg + sb > br + bg + bb) {
                        br = sr
                        bg = sg
                        bb = sb
                        ba = a * v
                    }
                }
            }
            if (ba <= 0.002f) continue
            val i = (y * w + x) * 4
            val a = src[i + 3]
            val oa = max(a, (a + ba * 0.8f).coerceAtMost(1f))
            out[i] = (out[i] + br * 0.9f).coerceIn(0f, oa)
            out[i + 1] = (out[i + 1] + bg * 0.9f).coerceIn(0f, oa)
            out[i + 2] = (out[i + 2] + bb * 0.9f).coerceIn(0f, oa)
            out[i + 3] = oa
        }
        return out
    }

    // ---------------------------------------------------------------- M5 shape pixelation and pointillism

    /** Accumulates premultiplied colour sums per cell, keyed by a canvas-aligned cell id. */
    private class CellSums {
        val cells = HashMap<Long, FloatArray>()

        fun add(key: Long, src: FloatArray, i: Int) {
            val v = cells.getOrPut(key) { FloatArray(5) }
            v[0] += src[i]
            v[1] += src[i + 1]
            v[2] += src[i + 2]
            v[3] += src[i + 3]
            v[4] += 1f
        }

        /** Writes the average colour of [key] into [out], or returns false when the cell is absent. */
        fun average(key: Long, out: FloatArray): Boolean {
            val v = cells[key] ?: return false
            val n = v[4]
            if (n <= 0f) return false
            out[0] = v[0] / n
            out[1] = v[1] / n
            out[2] = v[2] / n
            out[3] = v[3] / n
            return true
        }
    }

    private fun pack2(a: Int, b: Int): Long =
        (a.toLong() shl 32) xor (b.toLong() and 0xFFFFFFFFL)

    private fun pack3(a: Int, b: Int, c: Int): Long =
        (a.toLong() shl 33) xor ((b.toLong() and 0xFFFFFFFFL) shl 1) xor (c.toLong() and 1L)

    /** Pointy-top axial hexagon of a canvas point; [size] is the circumradius in px. */
    internal fun hexKey(gx: Float, gy: Float, size: Float): Long {
        val qf = (SQRT3 / 3f * gx - gy / 3f) / size
        val rf = (2f * gy / 3f) / size
        var q = qf.roundToInt()
        var r = rf.roundToInt()
        var s = (-qf - rf).roundToInt()
        val dq = abs(q - qf)
        val dr = abs(r - rf)
        val ds = abs(s + qf + rf)
        if (dq > dr && dq > ds) {
            q = -r - s
        } else if (dr > ds) {
            r = -q - s
        } else {
            s = -q - r
        }
        return pack2(q, r)
    }

    /** Triangle of an equilateral stride-[size] lattice, keyed by row, column and up/down. */
    internal fun triangleKey(gx: Float, gy: Float, size: Float): Long {
        val rowHeight = size * SQRT3_2
        val row = floor(gy / rowHeight).toInt()
        val offset = if (row and 1 == 1) size / 2f else 0f
        val u = gx - offset
        val col = floor(u / size).toInt()
        val u0 = u - col * size
        val v = ((gy - row * rowHeight) / rowHeight).coerceIn(0f, 1f)
        val left = size / 2f * (1f - v)
        val right = size / 2f * (1f + v)
        return when {
            u0 < left -> pack3(row, col - 1, 1)
            u0 > right -> pack3(row, col, 1)
            else -> pack3(row, col, 0)
        }
    }

    /** Nearest jittered seed of a square lattice; [size] is the lattice pitch in px. */
    internal fun crystalKey(gx: Float, gy: Float, size: Float): Long {
        val bx = floor(gx / size).toInt()
        val by = floor(gy / size).toInt()
        var bestKey = pack2(bx, by)
        var bestDistance = Float.MAX_VALUE
        for (dy in -1..1) {
            for (dx in -1..1) {
                val cx = bx + dx
                val cy = by + dy
                val jx = hash(cx, cy, 0x51ED2701L) * 0.3f
                val jy = hash(cx, cy, 0x7F4A7C15L) * 0.3f
                val sx = (cx + 0.5f + jx) * size
                val sy = (cy + 0.5f + jy) * size
                val d = (gx - sx) * (gx - sx) + (gy - sy) * (gy - sy)
                if (d < bestDistance - 1e-6f) {
                    bestDistance = d
                    bestKey = pack2(cx, cy)
                }
            }
        }
        return bestKey
    }

    /** Pointy-top hexagonal pixelation: one average colour per hexagon. */
    private fun hexPixelate(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        p: HexPixelateParams,
    ): FloatArray {
        val size = p.size.coerceIn(2, 200).toFloat()
        val sums = CellSums()
        for (y in 0 until h) for (x in 0 until w) {
            sums.add(hexKey(x + ox + 0.5f, y + oy + 0.5f, size), src, (y * w + x) * 4)
        }
        val out = src.copyOf()
        val avg = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            if (!sums.average(hexKey(x + ox + 0.5f, y + oy + 0.5f, size), avg)) continue
            val i = (y * w + x) * 4
            out[i] = avg[0]
            out[i + 1] = avg[1]
            out[i + 2] = avg[2]
            out[i + 3] = avg[3]
        }
        return out
    }

    /** Triangular pixelation: one average colour per equilateral triangle. */
    private fun trianglePixelate(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        p: TrianglePixelateParams,
    ): FloatArray {
        val size = p.size.coerceIn(2, 200).toFloat()
        val sums = CellSums()
        for (y in 0 until h) for (x in 0 until w) {
            sums.add(triangleKey(x + ox + 0.5f, y + oy + 0.5f, size), src, (y * w + x) * 4)
        }
        val out = src.copyOf()
        val avg = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            if (!sums.average(triangleKey(x + ox + 0.5f, y + oy + 0.5f, size), avg)) continue
            val i = (y * w + x) * 4
            out[i] = avg[0]
            out[i + 1] = avg[1]
            out[i + 2] = avg[2]
            out[i + 3] = avg[3]
        }
        return out
    }

    /** Crystallize: jittered Voronoi cells of the source, one flat colour each. */
    private fun crystallize(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        p: CrystallizeParams,
    ): FloatArray {
        val size = p.size.coerceIn(2, 100).toFloat()
        val sums = CellSums()
        for (y in 0 until h) for (x in 0 until w) {
            sums.add(crystalKey(x + ox + 0.5f, y + oy + 0.5f, size), src, (y * w + x) * 4)
        }
        val out = src.copyOf()
        val avg = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            if (!sums.average(crystalKey(x + ox + 0.5f, y + oy + 0.5f, size), avg)) continue
            val i = (y * w + x) * 4
            out[i] = avg[0]
            out[i + 1] = avg[1]
            out[i + 2] = avg[2]
            out[i + 3] = avg[3]
        }
        return out
    }

    /** Pointillize: the source colour on a jittered dot grid over a flat background. */
    private fun pointillize(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        p: PointillizeParams,
    ): FloatArray {
        val spacing = p.size.coerceIn(2, 64).toFloat()
        val radius = spacing * 0.5f
        val bg = ink(p.background)
        val out = FloatArray(src.size)
        for (i in out.indices step 4) {
            out[i] = bg[0]
            out[i + 1] = bg[1]
            out[i + 2] = bg[2]
            out[i + 3] = 1f
        }
        val minCx = floor((ox - radius) / spacing).toInt() - 1
        val maxCx = floor((ox + w + radius) / spacing).toInt() + 1
        val minCy = floor((oy - radius) / spacing).toInt() - 1
        val maxCy = floor((oy + h + radius) / spacing).toInt() + 1
        val s = FloatArray(4)
        for (cy in minCy..maxCy) {
            for (cx in minCx..maxCx) {
                val jx = hash(cx, cy, 0x2C1B3C6DL) * 0.18f * spacing
                val jy = hash(cx, cy, 0x1B873593L) * 0.18f * spacing
                val px = (cx + 0.5f) * spacing + jx
                val py = (cy + 0.5f) * spacing + jy
                sample(src, w, h, px - ox, py - oy, s)
                if (s[3] <= 0.002f) continue
                val xa = max((px - radius).toInt(), ox)
                val xb = min((px + radius).toInt() + 1, ox + w)
                val ya = max((py - radius).toInt(), oy)
                val yb = min((py + radius).toInt() + 1, oy + h)
                if (xb <= xa || yb <= ya) continue
                for (y in ya until yb) for (x in xa until xb) {
                    val d = hypot(x + 0.5f - px, y + 0.5f - py)
                    val cov = (radius + 0.5f - d).coerceIn(0f, 1f)
                    if (cov <= 0f) continue
                    val i = ((y - oy) * w + (x - ox)) * 4
                    out[i] = s[0] * cov + out[i] * (1f - cov)
                    out[i + 1] = s[1] * cov + out[i + 1] * (1f - cov)
                    out[i + 2] = s[2] * cov + out[i + 2] * (1f - cov)
                    out[i + 3] = s[3] * cov + out[i + 3] * (1f - cov)
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- M5 stylization and material

    /** Premultiplied luminance of a patch; transparent pixels read as zero. */
    private fun luminance(src: FloatArray): FloatArray {
        val lum = FloatArray(src.size / 4)
        for (i in lum.indices) {
            val j = i * 4
            lum[i] = 0.3f * src[j] + 0.59f * src[j + 1] + 0.11f * src[j + 2]
        }
        return lum
    }

    /** Sobel magnitude of the luminance, normalised to roughly 0..1.4 (4 is the kernel weight sum). */
    private fun sobel(lum: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val xm = (x - 1).coerceIn(0, w - 1)
        val xp = (x + 1).coerceIn(0, w - 1)
        val ym = (y - 1).coerceIn(0, h - 1)
        val yp = (y + 1).coerceIn(0, h - 1)
        val gx = lum[ym * w + xm] + 2f * lum[y * w + xm] + lum[yp * w + xm] -
            lum[ym * w + xp] - 2f * lum[y * w + xp] - lum[yp * w + xp]
        val gy = lum[ym * w + xm] + 2f * lum[ym * w + x] + lum[ym * w + xp] -
            lum[yp * w + xm] - 2f * lum[yp * w + x] - lum[yp * w + xp]
        return sqrt(gx * gx + gy * gy) * 0.25f
    }

    /** Bilinear sample of a single-channel buffer, clamped to its edges. */
    private fun sampleGray(buf: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        if (w <= 0 || h <= 0) return 0f
        val fx = x.coerceIn(0f, (w - 1).toFloat())
        val fy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val top = buf[y0 * w + x0] + (buf[y0 * w + x1] - buf[y0 * w + x0]) * tx
        val bottom = buf[y1 * w + x0] + (buf[y1 * w + x1] - buf[y1 * w + x0]) * tx
        return top + (bottom - top) * ty
    }

    /**
     * Gloom: the shadows deeper than [GloomParams.threshold] are darkened and the darkening spreads by
     * [GloomParams.radius] px, so shaded areas gain weight while the highlights stay untouched.
     */
    private fun gloom(src: FloatArray, w: Int, h: Int, p: GloomParams): FloatArray {
        val threshold = p.threshold.coerceIn(0.02f, 1f)
        val radius = ceil(p.radius.coerceIn(1f, 32f)).toInt()
        val dark = FloatArray(src.size)
        for (i in src.indices step 4) {
            val a = src[i + 3]
            if (a <= 0f) continue
            val lum = (0.3f * src[i] + 0.59f * src[i + 1] + 0.11f * src[i + 2]) / a
            val shade = ((threshold - lum) / threshold).coerceIn(0f, 1f)
            dark[i + 3] = shade * a
        }
        val spread = localBlur(dark, w, h, radius)
        val out = src.copyOf()
        for (i in src.indices step 4) {
            val a = src[i + 3]
            if (a <= 0f) continue
            val k = (spread[i + 3] / a).coerceIn(0f, 1f)
            val f = 1f - 0.75f * k
            out[i] = src[i] * f
            out[i + 1] = src[i + 1] * f
            out[i + 2] = src[i + 2] * f
            // Gloom is a tone change: the layer alpha stays as it is.
        }
        return out
    }

    /** Pencil sketch: thresholded Sobel edges of [SketchParams.thickness] px drawn on paper. */
    private fun sketch(src: FloatArray, w: Int, h: Int, p: SketchParams): FloatArray {
        val thickness = p.thickness.coerceIn(1, 8)
        val amount = p.amount.coerceIn(0f, 1f)
        val sensitivity = p.sensitivity.coerceIn(0f, 1f)
        val lum = luminance(src)
        val line = FloatArray(w * h)
        // A sensitive setting keeps weak gradients; the amount decides how much of them stay visible.
        val threshold = 0.32f - 0.24f * sensitivity
        for (y in 0 until h) for (x in 0 until w) {
            val e = sobel(lum, w, h, x, y)
            line[y * w + x] = ((e - threshold) / 0.3f).coerceIn(0f, 1f)
        }
        val ink = if (thickness > 1) maxFilter(line, w, h, thickness - 1) else line
        val gain = 0.25f + 0.75f * amount
        val out = FloatArray(src.size)
        for (i in 0 until w * h) {
            val j = i * 4
            val a = src[j + 3]
            if (a <= 0f) continue
            val paper = 1f - (ink[i] * gain).coerceIn(0f, 1f)
            out[j] = paper * a
            out[j + 1] = paper * a
            out[j + 2] = paper * a
            out[j + 3] = a
        }
        return out
    }

    /** Trace: the edges of the source are darkened in place, negative-style, over the source colours. */
    private fun traceEdges(src: FloatArray, w: Int, h: Int): FloatArray {
        val lum = luminance(src)
        val out = src.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val e = sobel(lum, w, h, x, y)
            val ink = ((e - 0.05f) / 0.45f).coerceIn(0f, 1f) * 0.9f
            if (ink <= 0f) continue
            val j = (y * w + x) * 4
            val f = 1f - ink
            out[j] = src[j] * f
            out[j + 1] = src[j + 1] * f
            out[j + 2] = src[j + 2] * f
            // Edges darken the colour only; the shape and its alpha stay the same.
        }
        return out
    }

    /**
     * Bevel: a blurred alpha height map lit from [BevelParams.angleDeg] gives a highlight on the lit slope
     * and a shade on the opposite one, inside, outside or on both sides of the painted shape.
     */
    private fun bevel(src: FloatArray, w: Int, h: Int, p: BevelParams): FloatArray {
        val width = p.size.coerceIn(1, 64)
        val variant = p.variant.lowercase()
        val inner = variant != "outer"
        val outer = variant == "outer" || variant == "both"
        val alpha = FloatArray(w * h) { src[it * 4 + 3] }
        val grown = if (outer) maxFilter(alpha, w, h, width) else null
        val eroded = if (inner) minFilter(alpha, w, h, width) else null
        val soft = max(1, width / 2)
        val heightIn = if (inner) boxBlurAlpha(alpha, w, h, soft) else null
        val heightOut = if (outer) boxBlurAlpha(grown!!, w, h, soft) else null
        val hi = ink(p.highlight)
        val sh = ink(p.shadow)
        val depth = p.depth.coerceIn(0f, 100f) / 100f
        val rad = Math.toRadians(p.angleDeg.toDouble()).toFloat()
        val lx = cos(rad)
        val ly = sin(rad)
        val out = src.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = alpha[i]
            val covOut = if (grown != null) (grown[i] - a).coerceAtLeast(0f) else 0f
            val covIn = if (eroded != null) (a - eroded[i]).coerceAtLeast(0f) else 0f
            val cov = max(covIn, covOut)
            if (cov <= 0.002f) continue
            val height = if (a > 0.002f) heightIn else heightOut
            if (height == null) continue
            val xm = (x - 1).coerceAtLeast(0)
            val xp = (x + 1).coerceAtMost(w - 1)
            val ym = (y - 1).coerceAtLeast(0)
            val yp = (y + 1).coerceAtMost(h - 1)
            val gx = (height[y * w + xp] - height[y * w + xm]) * 0.5f
            val gy = (height[yp * w + x] - height[ym * w + x]) * 0.5f
            // The surface normal is (-gx, -gy, 1), so its dot with the light direction picks the side.
            val lit = (-(gx * lx + gy * ly) * width * 2f).coerceIn(-1f, 1f) * depth
            val k = (cov * abs(lit)).coerceIn(0f, 1f)
            if (k <= 0.002f) continue
            val colour = if (lit >= 0f) hi else sh
            val j = i * 4
            if (a <= cov) {
                // Outside the shape: the bevel itself supplies the coverage.
                out[j] = colour[0] * k
                out[j + 1] = colour[1] * k
                out[j + 2] = colour[2] * k
                out[j + 3] = max(a, k)
            } else {
                out[j] = (src[j] * (1f - k) + colour[0] * k).coerceAtMost(a)
                out[j + 1] = (src[j + 1] * (1f - k) + colour[1] * k).coerceAtMost(a)
                out[j + 2] = (src[j + 2] * (1f - k) + colour[2] * k).coerceAtMost(a)
            }
        }
        return out
    }

    /** Emboss: a grey relief of the directional luminance difference, keeping the source alpha. */
    private fun emboss(src: FloatArray, w: Int, h: Int, p: EmbossParams): FloatArray {
        val height = p.height.coerceIn(0f, 100f) / 100f
        val d = 1 + (height * 4f).roundToInt()
        val rad = Math.toRadians(p.angleDeg.toDouble()).toFloat()
        val dx = cos(rad) * d
        val dy = sin(rad) * d
        val gain = 1.2f + 1.8f * height
        val out = FloatArray(src.size)
        val hi = FloatArray(4)
        val lo = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val j = (y * w + x) * 4
            val a = src[j + 3]
            if (a <= 0f) continue
            sample(src, w, h, x + dx, y + dy, hi)
            sample(src, w, h, x - dx, y - dy, lo)
            val diff = (0.3f * hi[0] + 0.59f * hi[1] + 0.11f * hi[2]) -
                (0.3f * lo[0] + 0.59f * lo[1] + 0.11f * lo[2])
            val v = (0.5f + diff * gain).coerceIn(0f, 1f)
            out[j] = v * a
            out[j + 1] = v * a
            out[j + 2] = v * a
            out[j + 3] = a
        }
        return out
    }

    /** Relief: the softened luminance slope along the light direction shades the source colours in place. */
    private fun relief(src: FloatArray, w: Int, h: Int, p: ReliefParams): FloatArray {
        val height = p.height.coerceIn(0f, 100f) / 100f
        val soften = ceil(p.soften.coerceIn(0f, 16f)).toInt()
        val lum = luminance(src)
        val smoothed = if (soften > 0) boxBlurAlpha(lum, w, h, soften) else lum
        val rad = Math.toRadians(p.angleDeg.toDouble()).toFloat()
        val dx = cos(rad)
        val dy = sin(rad)
        val gain = 0.6f + 2.4f * height
        val out = src.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val j = (y * w + x) * 4
            val a = src[j + 3]
            if (a <= 0f) continue
            val hi = sampleGray(smoothed, w, h, x + dx, y + dy)
            val lo = sampleGray(smoothed, w, h, x - dx, y - dy)
            val shade = (hi - lo) * gain
            val f = (1f + shade).coerceAtLeast(0f)
            out[j] = (src[j] * f).coerceAtMost(a)
            out[j + 1] = (src[j + 1] * f).coerceAtMost(a)
            out[j + 2] = (src[j + 2] * f).coerceAtMost(a)
            // The relief lights the pigments, it does not change the painted shape.
        }
        return out
    }

    /** Satin: a soft sheen in the last [SatinParams.distance] px of the shape, folded across the angle. */
    private fun satin(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int, p: SatinParams): FloatArray {
        val distance = p.distance.coerceIn(1, 100)
        val size = p.size.coerceIn(1, 32)
        val alpha = FloatArray(w * h) { src[it * 4 + 3] }
        val eroded = minFilter(alpha, w, h, distance)
        val band = FloatArray(w * h) { (alpha[it] - eroded[it]).coerceAtLeast(0f) }
        val sheen = boxBlurAlpha(band, w, h, size)
        val colour = ink(p.color)
        val rad = Math.toRadians(p.angleDeg.toDouble()).toFloat()
        val ux = cos(rad)
        val uy = sin(rad)
        val period = max(4f, distance.toFloat())
        val out = src.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = alpha[i]
            if (a <= 0f) continue
            // The folds follow the canvas grid, so neighbouring tiles agree on their phase.
            val wave = 0.5f + 0.5f * cos(((x + ox) * ux + (y + oy) * uy) * PI_F / period)
            val k = (sheen[i] * wave * 0.9f).coerceIn(0f, 1f)
            if (k <= 0.002f) continue
            val j = i * 4
            out[j] = (src[j] * (1f - k) + colour[0] * k).coerceAtMost(a)
            out[j + 1] = (src[j + 1] * (1f - k) + colour[1] * k).coerceAtMost(a)
            out[j + 2] = (src[j + 2] * (1f - k) + colour[2] * k).coerceAtMost(a)
            // The sheen lives inside the shape, so alpha stays untouched.
        }
        return out
    }

    /** Wet edge: the last [WetEdgeParams.width] px inside the painted area darken like drying pigment. */
    private fun wetEdge(src: FloatArray, w: Int, h: Int, p: WetEdgeParams): FloatArray {
        val width = p.width.coerceIn(1, 64)
        val alpha = FloatArray(w * h) { src[it * 4 + 3] }
        val inner = minFilter(alpha, w, h, width)
        val band = FloatArray(w * h) { (alpha[it] - inner[it]).coerceAtLeast(0f) }
        val rim = boxBlurAlpha(band, w, h, max(1, width / 3))
        val out = src.copyOf()
        for (i in 0 until w * h) {
            val a = alpha[i]
            if (a <= 0f) continue
            val k = (rim[i] / a).coerceIn(0f, 1f)
            if (k <= 0.002f) continue
            val f = 1f - 0.55f * k
            val j = i * 4
            out[j] = src[j] * f
            out[j + 1] = src[j + 1] * f
            out[j + 2] = src[j + 2] * f
        }
        return out
    }

    /**
     * Extrude: every transparent pixel in front of the shape is filled with the solid side face, coloured by
     * [ExtrudeParams.color] and darkened toward the far end. The march only looks [ExtrudeParams.length] px
     * back along the light, so a padded patch is enough and no full-content snapshot is needed.
     */
    private fun extrude(src: FloatArray, w: Int, h: Int, p: ExtrudeParams): FloatArray {
        val length = p.length.coerceIn(1, 100)
        val shading = p.shading.coerceIn(0f, 1f)
        val rad = Math.toRadians(p.angleDeg.toDouble()).toFloat()
        val dx = cos(rad)
        val dy = sin(rad)
        val colour = ink(p.color)
        val out = src.copyOf()
        val s = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val j = (y * w + x) * 4
            if (src[j + 3] > 0.002f) continue
            var found = 0
            for (n in 1..length) {
                sample(src, w, h, x - dx * n, y - dy * n, s)
                if (s[3] > 0.02f) {
                    found = n
                    break
                }
            }
            if (found == 0) continue
            val f = 1f - shading * (found / length.toFloat())
            // The side face is solid; taking the rim coverage per march would stripe it along the light.
            out[j] = colour[0] * f
            out[j + 1] = colour[1] * f
            out[j + 2] = colour[2] * f
            out[j + 3] = 1f
        }
        return out
    }

    /**
     * Clouds: fractal value noise between two colours. It is evaluated at canvas coordinates, so every tile
     * computes the same clouds without reading a neighbour or a snapshot.
     */
    private fun clouds(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int, p: CloudsParams): FloatArray {
        val scale = p.scale.coerceIn(1f, 256f)
        val detail = p.detail.coerceIn(1, 8)
        val a = ink(p.colorA)
        val b = ink(p.colorB)
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            var norm = 0f
            var amp = 1f
            var cell = scale
            for (o in 0 until detail) {
                sum += amp * valueNoise(x + ox, y + oy, cell, p.seed + o * 0x9E3779B9L)
                norm += amp
                amp *= 0.55f
                cell *= 0.5f
            }
            val noise = (sum / norm).coerceIn(-1f, 1f) * 0.5f + 0.5f
            val v = noise * noise * (3f - 2f * noise)
            val j = (y * w + x) * 4
            out[j] = b[0] + (a[0] - b[0]) * v
            out[j + 1] = b[1] + (a[1] - b[1]) * v
            out[j + 2] = b[2] + (a[2] - b[2]) * v
            out[j + 3] = 1f
        }
        return out
    }

    // ---------------------------------------------------------------- M5 artistic and generative

    /**
     * Palette posterization. A deterministic median cut over a 32x32x32 colour histogram of the whole content
     * yields at most [requested] entries; every 5-bit source bin is then mapped to its nearest entry once, so
     * the per-tile write is a table lookup that cannot depend on tile order.
     */
    private fun buildPaletteLut(src: FloatArray, requested: Int): IntArray {
        val colors = requested.coerceIn(2, 48)
        val counts = IntArray(32768)
        for (i in src.indices step 4) {
            val a = src[i + 3]
            if (a <= 0.004f) continue
            val r = ((src[i] / a).coerceIn(0f, 1f) * 31f).roundToInt()
            val g = ((src[i + 1] / a).coerceIn(0f, 1f) * 31f).roundToInt()
            val b = ((src[i + 2] / a).coerceIn(0f, 1f) * 31f).roundToInt()
            counts[(r shl 10) or (g shl 5) or b]++
        }
        val bins = ArrayList<Int>()
        for (bin in counts.indices) if (counts[bin] > 0) bins.add(bin)
        if (bins.isEmpty()) {
            val lut = IntArray(32768) { 0 }
            return lut
        }
        var boxes = listOf(PaletteBox(bins, counts))
        while (boxes.size < colors) {
            var best = -1
            var bestScore = 0L
            for (i in boxes.indices) {
                val score = boxes[i].splitScore()
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
            if (best < 0) break
            val parts = boxes[best].split(counts) ?: break
            val next = ArrayList<PaletteBox>(boxes.size + 1)
            next.addAll(boxes)
            next.removeAt(best)
            next.add(parts.first)
            next.add(parts.second)
            boxes = next
        }
        val palette = IntArray(boxes.size) { boxes[it].averageColor() }
        val lut = IntArray(32768)
        for (bin in lut.indices) {
            val r = ((bin shr 10) and 31) / 31f
            val g = ((bin shr 5) and 31) / 31f
            val b = (bin and 31) / 31f
            lut[bin] = nearestPaletteColor(r, g, b, palette)
        }
        return lut
    }

    /** One weighted RGB box of the median-cut palette. */
    private class PaletteBox(val bins: MutableList<Int>, private val counts: IntArray) {
        var weight = 0L
        private val sum = LongArray(3)
        private val low = IntArray(3) { 32 }
        private val high = IntArray(3) { -1 }

        init {
            for (bin in bins) {
                val n = counts[bin].toLong()
                weight += n
                val c = intArrayOf((bin shr 10) and 31, (bin shr 5) and 31, bin and 31)
                for (k in 0..2) {
                    sum[k] += n * c[k]
                    if (c[k] < low[k]) low[k] = c[k]
                    if (c[k] > high[k]) high[k] = c[k]
                }
            }
        }

        /** Larger and more varied boxes split first; a box with one bin cannot split. */
        fun splitScore(): Long {
            if (bins.size < 2) return 0L
            var range = 0
            for (k in 0..2) range = max(range, high[k] - low[k])
            return if (range == 0) 0L else weight * (range + 1L)
        }

        fun split(counts: IntArray): Pair<PaletteBox, PaletteBox>? {
            if (bins.size < 2) return null
            var axis = 0
            for (k in 1..2) if (high[k] - low[k] > high[axis] - low[axis]) axis = k
            val shift = when (axis) {
                0 -> 10
                1 -> 5
                else -> 0
            }
            val sorted = bins.sortedBy { (it shr shift) and 31 }
            val half = weight / 2
            var acc = 0L
            var cut = 0
            for (i in sorted.indices) {
                acc += counts[sorted[i]]
                if (acc >= half && i < sorted.size - 1) {
                    cut = i + 1
                    break
                }
            }
            if (cut <= 0 || cut >= sorted.size) return null
            return PaletteBox(ArrayList(sorted.subList(0, cut)), counts) to
                PaletteBox(ArrayList(sorted.subList(cut, sorted.size)), counts)
        }

        fun averageColor(): Int {
            if (weight <= 0L) return 0
            val r = (sum[0] * 255L / (weight * 31L)).toInt().coerceIn(0, 255)
            val g = (sum[1] * 255L / (weight * 31L)).toInt().coerceIn(0, 255)
            val b = (sum[2] * 255L / (weight * 31L)).toInt().coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or b
        }
    }

    /** Nearest palette entry to straight [r],[g],[b], packed as 0xRRGGBB. */
    private fun nearestPaletteColor(r: Float, g: Float, b: Float, palette: IntArray): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (entry in palette) {
            val dr = r - (((entry shr 16) and 0xFF) / 255f)
            val dg = g - (((entry shr 8) and 0xFF) / 255f)
            val db = b - ((entry and 0xFF) / 255f)
            val d = dr * dr + dg * dg + db * db
            if (d < bestDistance) {
                bestDistance = d
                best = entry
            }
        }
        return best
    }

    /**
     * QR generator. The symbol is generated once per tile from the canvas geometry, so every tile draws the
     * same modules at the same canvas coordinates; the white quiet zone and paper make it scannable even on an
     * empty layer. A selection clips it like any other generator.
     */
    private fun qrCode(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        g: Geometry,
        p: QrCodeParams,
    ): FloatArray {
        val code = QrCode.encode(p.text)
        val margin = p.margin.coerceIn(0, 8)
        val total = code.size + margin * 2
        val maxSide = min(g.content.width, g.content.height).coerceAtLeast(1)
        val module = min(p.size.coerceIn(1, 32), maxSide / total).coerceAtLeast(1)
        val side = total * module
        val left = g.content.x0 + (g.content.width - side) / 2
        val top = g.content.y0 + (g.content.height - side) / 2
        val out = FloatArray(src.size)
        val paper = floatArrayOf(1f, 1f, 1f)
        fillRect(out, w, h, left - ox, top - oy, left + side - ox, top + side - oy, paper)
        val ink = ink(p.color)
        for (my in 0 until code.size) for (mx in 0 until code.size) {
            if (!code.dark(mx, my)) continue
            val x0 = left + (margin + mx) * module - ox
            val y0 = top + (margin + my) * module - oy
            fillRect(out, w, h, x0, y0, x0 + module, y0 + module, ink)
        }
        return out
    }

    /** Stained glass: one average colour per Voronoi pane and a solid lead border along the cell edges. */
    private fun stainedGlass(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        p: StainedGlassParams,
    ): FloatArray {
        val size = p.cell.coerceIn(4, 100).toFloat()
        val sums = CellSums()
        for (y in 0 until h) for (x in 0 until w) {
            sums.add(crystalKey(x + ox + 0.5f, y + oy + 0.5f, size), src, (y * w + x) * 4)
        }
        val border = p.borderWidth.coerceIn(0f, 20f)
        val ink = ink(p.borderColor)
        val out = src.copyOf()
        val avg = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = x + ox + 0.5f
            val gy = y + oy + 0.5f
            if (!sums.average(crystalKey(gx, gy, size), avg)) continue
            val a = avg[3]
            if (a <= 0.002f) continue
            val cov = if (border > 0.01f) {
                val edge = voronoiEdge(gx, gy, size)
                (border + 0.5f - edge).coerceIn(0f, 1f)
            } else {
                0f
            }
            val i = (y * w + x) * 4
            out[i] = avg[0] * (1f - cov) + ink[0] * a * cov
            out[i + 1] = avg[1] * (1f - cov) + ink[1] * a * cov
            out[i + 2] = avg[2] * (1f - cov) + ink[2] * a * cov
            out[i + 3] = a
        }
        return out
    }

    /** Distance from a canvas point to the nearest Voronoi cell boundary of the crystal lattice. */
    private fun voronoiEdge(gx: Float, gy: Float, size: Float): Float {
        val bx = floor(gx / size).toInt()
        val by = floor(gy / size).toInt()
        var d1 = Float.MAX_VALUE
        var d2 = Float.MAX_VALUE
        for (dy in -1..1) for (dx in -1..1) {
            val cx = bx + dx
            val cy = by + dy
            val sx = (cx + 0.5f + hash(cx, cy, 0x51ED2701L) * 0.3f) * size
            val sy = (cy + 0.5f + hash(cx, cy, 0x7F4A7C15L) * 0.3f) * size
            val d = (gx - sx) * (gx - sx) + (gy - sy) * (gy - sy)
            if (d < d1) {
                d2 = d1
                d1 = d
            } else if (d < d2) {
                d2 = d
            }
        }
        if (d2 == Float.MAX_VALUE) return size / 2f
        return 0.5f * (sqrt(d2) - sqrt(d1))
    }

    /** Dots mosaic: source-colour dots on a canvas-aligned hex or square lattice over [DotsParams.color]. */
    private fun dots(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int, p: DotsParams): FloatArray {
        val spacing = p.spacing.coerceIn(2, 128).toFloat()
        val radius = (p.size.coerceIn(1, 64) * 0.5f).coerceAtMost(spacing * 0.5f)
        val hex = p.shape.equals("hex", true)
        val rowHeight = if (hex) spacing * SQRT3_2 else spacing
        val bg = ink(p.color)
        val out = FloatArray(src.size)
        // Keep the painted silhouette: only pixels with source coverage receive the gap colour.
        for (i in src.indices step 4) {
            if (src[i + 3] <= 0.004f) continue
            out[i] = bg[0]
            out[i + 1] = bg[1]
            out[i + 2] = bg[2]
            out[i + 3] = 1f
        }
        val minRow = floor((oy - radius) / rowHeight).toInt() - 1
        val maxRow = floor((oy + h + radius) / rowHeight).toInt() + 1
        val s = FloatArray(4)
        for (row in minRow..maxRow) {
            val offset = if (hex && row and 1 == 1) spacing * 0.5f else 0f
            val cy = (row + 0.5f) * rowHeight
            val minCol = floor((ox - offset - radius) / spacing).toInt() - 1
            val maxCol = floor((ox + w - offset + radius) / spacing).toInt() + 1
            for (col in minCol..maxCol) {
                val cx = (col + 0.5f) * spacing + offset
                sample(src, w, h, cx - ox, cy - oy, s)
                val a = s[3]
                if (a <= 0.01f) continue
                val r = s[0] / a
                val g = s[1] / a
                val b = s[2] / a
                val xa = max((cx - radius - ox).toInt(), 0)
                val xb = min((cx + radius - ox).toInt() + 1, w)
                val ya = max((cy - radius - oy).toInt(), 0)
                val yb = min((cy + radius - oy).toInt() + 1, h)
                if (xb <= xa || yb <= ya) continue
                for (y in ya until yb) for (x in xa until xb) {
                    val d = hypot(x + ox + 0.5f - cx, y + oy + 0.5f - cy)
                    val cov = (radius + 0.5f - d).coerceIn(0f, 1f)
                    if (cov <= 0f) continue
                    val i = (y * w + x) * 4
                    out[i] = r * cov + out[i] * (1f - cov)
                    out[i + 1] = g * cov + out[i + 1] * (1f - cov)
                    out[i + 2] = b * cov + out[i + 2] * (1f - cov)
                    out[i + 3] = cov + out[i + 3] * (1f - cov)
                }
            }
        }
        return out
    }

    /** One-bit stamp: luminance is thresholded, the painted alpha is kept. */
    private fun stamp(src: FloatArray, p: StampParams): FloatArray {
        val threshold = p.threshold.coerceIn(0f, 1f)
        val out = FloatArray(src.size)
        for (i in src.indices step 4) {
            val a = src[i + 3]
            if (a <= 0f) continue
            val lum = (0.3f * src[i] + 0.59f * src[i + 1] + 0.11f * src[i + 2]) / a
            val v = if (lum >= threshold) 1f else 0f
            out[i] = v * a
            out[i + 1] = v * a
            out[i + 2] = v * a
            out[i + 3] = a
        }
        return out
    }

    /** Glitch: per-slice horizontal shifts plus a channel split, all keyed by [GlitchParams.seed]. */
    private fun glitch(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int, p: GlitchParams): FloatArray {
        val amount = p.amount.coerceIn(0, 64).toFloat()
        val slice = p.sliceHeight.coerceIn(2, 128)
        val shift = if (amount < 1f) 0f else max(1, (amount / 6f).roundToInt()).toFloat()
        val out = FloatArray(src.size)
        val sr = FloatArray(4)
        val sg = FloatArray(4)
        val sb = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            if (src[i + 3] <= 0f) continue
            val sliceIndex = floor((y + oy).toFloat() / slice).toInt()
            val offset = hash(sliceIndex, 0, p.seed) * amount
            val dir = if (hash(sliceIndex, 1, p.seed) < -0.2f) -1f else 1f
            sample(src, w, h, x + offset + shift * dir, y.toFloat(), sr)
            sample(src, w, h, x + offset, y.toFloat(), sg)
            sample(src, w, h, x + offset - shift * dir, y.toFloat(), sb)
            val a = max(sr[3], max(sg[3], sb[3]))
            if (a <= 0.002f) continue
            var r = if (sr[3] > 0.002f) sr[0] / sr[3] * a else 0f
            var g = if (sg[3] > 0.002f) sg[1] / sg[3] * a else 0f
            var b = if (sb[3] > 0.002f) sb[2] / sb[3] * a else 0f
            if (hash(sliceIndex, 2, p.seed) > 0.75f) {
                r = a - r
                g = a - g
                b = a - b
            }
            out[i] = r.coerceIn(0f, a)
            out[i + 1] = g.coerceIn(0f, a)
            out[i + 2] = b.coerceIn(0f, a)
            out[i + 3] = a
        }
        return out
    }

    /** Retro game: canvas-aligned pixel blocks quantized to a small palette with ordered dither. */
    private fun retroGame(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int, p: RetroGameParams): FloatArray {
        val palette = when (p.palette.lowercase()) {
            "gb" -> RETRO_GB
            "16" -> RETRO_16
            else -> RETRO_NES
        }
        val cell = p.pixelSize.coerceIn(1, 32)
        val dither = p.dither.coerceIn(0f, 1f)
        val out = FloatArray(src.size)
        val gx0 = (ox / cell) * cell
        val gy0 = (oy / cell) * cell
        for (cy in gy0 until oy + h step cell) {
            for (cx in gx0 until ox + w step cell) {
                val xa = max(cx, ox)
                val xb = min(cx + cell, ox + w)
                val ya = max(cy, oy)
                val yb = min(cy + cell, oy + h)
                if (xb <= xa || yb <= ya) continue
                var sr = 0f
                var sg = 0f
                var sb = 0f
                var sa = 0f
                var n = 0
                for (y in ya until yb) for (x in xa until xb) {
                    val i = ((y - oy) * w + (x - ox)) * 4
                    sr += src[i]
                    sg += src[i + 1]
                    sb += src[i + 2]
                    sa += src[i + 3]
                    n++
                }
                if (n == 0 || sa <= 0.002f) continue
                val meanAlpha = sa / n
                var r = (sr / sa).coerceIn(0f, 1f)
                var g = (sg / sa).coerceIn(0f, 1f)
                var b = (sb / sa).coerceIn(0f, 1f)
                if (dither > 0f) {
                    val bx = (cx / cell) and 3
                    val by = (cy / cell) and 3
                    val offset = (BAYER[by * 4 + bx] - 0.5f) * dither * 0.22f
                    r = (r + offset).coerceIn(0f, 1f)
                    g = (g + offset).coerceIn(0f, 1f)
                    b = (b + offset).coerceIn(0f, 1f)
                }
                val rgb = nearestPaletteColor(r, g, b, palette)
                val cr = ((rgb shr 16) and 0xFF) / 255f * meanAlpha
                val cg = ((rgb shr 8) and 0xFF) / 255f * meanAlpha
                val cb = (rgb and 0xFF) / 255f * meanAlpha
                for (y in ya until yb) for (x in xa until xb) {
                    val i = ((y - oy) * w + (x - ox)) * 4
                    out[i] = cr
                    out[i + 1] = cg
                    out[i + 2] = cb
                    out[i + 3] = meanAlpha
                }
            }
        }
        return out
    }

    /** Chrome: luminance detail drives a dark-to-white-to-dark metal ramp. */
    private fun chrome(src: FloatArray, w: Int, h: Int, p: ChromeParams): FloatArray {
        val radius = ceil(p.smoothness.coerceIn(0f, 16f)).toInt()
        val lum = luminance(src)
        val soft = if (radius > 0) boxBlurAlpha(lum, w, h, radius) else lum
        val gain = 7f + radius * 1.3f
        val out = src.copyOf()
        for (i in src.indices step 4) {
            val a = src[i + 3]
            if (a <= 0f) continue
            val detail = lum[i / 4] - soft[i / 4]
            val u = (0.5f + detail * gain).coerceIn(0f, 1f)
            val t = u * u * (3f - 2f * u)
            var r: Float
            var g: Float
            var b: Float
            when {
                t < 0.42f -> {
                    val k = t / 0.42f
                    r = 0.07f + (0.55f - 0.07f) * k
                    g = 0.09f + (0.62f - 0.09f) * k
                    b = 0.13f + (0.76f - 0.13f) * k
                }
                t < 0.78f -> {
                    val k = (t - 0.42f) / 0.36f
                    r = 0.55f + (0.99f - 0.55f) * k
                    g = 0.62f + (0.99f - 0.62f) * k
                    b = 0.76f + (1f - 0.76f) * k
                }
                else -> {
                    val k = (t - 0.78f) / 0.22f
                    r = 0.99f + (0.28f - 0.99f) * k
                    g = 0.99f + (0.30f - 0.99f) * k
                    b = 1f + (0.36f - 1f) * k
                }
            }
            out[i] = r * a
            out[i + 1] = g * a
            out[i + 2] = b * a
        }
        return out
    }

    /** Sheer: a broad diagonal sheen added to the pigments, keeping the painted shape. */
    private fun sheer(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int): FloatArray {
        val out = src.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            val a = src[i + 3]
            if (a <= 0f) continue
            val gx = x + ox
            val gy = y + oy
            val band = 0.5f + 0.5f * cos(gx * 0.045f - gy * 0.031f + (gx + gy) * 0.002f)
            val lum = (0.3f * src[i] + 0.59f * src[i + 1] + 0.11f * src[i + 2]) / a
            val k = 0.10f + 0.34f * band * (0.35f + 0.65f * lum)
            out[i] = src[i] + (a - src[i]) * k
            out[i + 1] = src[i + 1] + (a - src[i + 1]) * k
            out[i + 2] = src[i + 2] + (a - src[i + 2]) * k
        }
        return out
    }

    /** Impressionist: short dabs of the local colour, oriented across the local luminance gradient. */
    private fun impressionist(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        p: ImpressionistParams,
    ): FloatArray {
        val pitch = p.strokeSize.coerceIn(2, 32).toFloat()
        val length = pitch * 2.4f
        val halfWidth = (pitch * 0.35f).coerceAtLeast(1f)
        val out = src.copyOf()
        val s = FloatArray(4)
        val probe = FloatArray(4)
        fun lumAt(px: Float, py: Float): Float {
            sample(src, w, h, px - ox, py - oy, probe)
            val a = probe[3]
            if (a <= 0.002f) return 0f
            return (0.3f * probe[0] + 0.59f * probe[1] + 0.11f * probe[2]) / a
        }
        val minRow = floor((oy - length - halfWidth) / pitch).toInt() - 1
        val maxRow = floor((oy + h + length + halfWidth) / pitch).toInt() + 1
        for (row in minRow..maxRow) {
            val minCol = floor((ox - length - halfWidth) / pitch).toInt() - 1
            val maxCol = floor((ox + w + length + halfWidth) / pitch).toInt() + 1
            for (col in minCol..maxCol) {
                val jx = hash(col, row, 0x2C1B3C6DL)
                val jy = hash(col, row, 0x1B873593L)
                val cx = (col + 0.5f) * pitch + jx * pitch * 0.35f
                val cy = (row + 0.5f) * pitch + jy * pitch * 0.35f
                sample(src, w, h, cx - ox, cy - oy, s)
                if (s[3] <= 0.01f) continue
                val probeDistance = pitch * 0.6f
                val gradX = lumAt(cx + probeDistance, cy) - lumAt(cx - probeDistance, cy)
                val gradY = lumAt(cx, cy + probeDistance) - lumAt(cx, cy - probeDistance)
                var angle = if (gradX * gradX + gradY * gradY > 1e-5f) {
                    atan2(gradY, gradX) + PI_F / 2f
                } else {
                    hash(col, row, 0x7F4A7C15L) * TWO_PI
                }
                angle += jx * 0.45f
                val dx = cos(angle) * length * 0.5f
                val dy = sin(angle) * length * 0.5f
                val x0 = cx - dx
                val y0 = cy - dy
                val x1 = cx + dx
                val y1 = cy + dy
                val vx = x1 - x0
                val vy = y1 - y0
                val vv = vx * vx + vy * vy
                if (vv < 1e-4f) continue
                val xa = max(floor(min(x0, x1) - halfWidth - ox).toInt(), 0)
                val xb = min(ceil(max(x0, x1) + halfWidth - ox).toInt() + 1, w)
                val ya = max(floor(min(y0, y1) - halfWidth - oy).toInt(), 0)
                val yb = min(ceil(max(y0, y1) + halfWidth - oy).toInt() + 1, h)
                if (xb <= xa || yb <= ya) continue
                for (y in ya until yb) for (x in xa until xb) {
                    val px = x + ox + 0.5f
                    val py = y + oy + 0.5f
                    val t = (((px - x0) * vx + (py - y0) * vy) / vv).coerceIn(0f, 1f)
                    val d = hypot(px - (x0 + vx * t), py - (y0 + vy * t))
                    val cov = (halfWidth + 0.5f - d).coerceIn(0f, 1f)
                    if (cov <= 0f) continue
                    val k = cov * 0.85f
                    val i = (y * w + x) * 4
                    out[i] = s[0] * k + out[i] * (1f - k)
                    out[i + 1] = s[1] * k + out[i + 1] * (1f - k)
                    out[i + 2] = s[2] * k + out[i + 2] * (1f - k)
                    out[i + 3] = s[3] * k + out[i + 3] * (1f - k)
                }
            }
        }
        return out
    }

    /**
     * Swirl painting: a procedural flow field drags the pixels along curved strokes, then three samples along
     * the flow are blended into a short painted smear. The field is evaluated at canvas coordinates.
     */
    private fun swirlPainting(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int): FloatArray {
        val out = FloatArray(src.size)
        val base = FloatArray(4)
        val sideA = FloatArray(4)
        val sideB = FloatArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = (x + ox).toFloat()
            val gy = (y + oy).toFloat()
            val n1 = valueNoise(x + ox, y + oy, 110f, 0x51ED2701L)
            val n2 = valueNoise(x + ox, y + oy, 54f, 0x7F4A7C15L)
            val angle = n1 * PI_F * 1.5f + (gx + gy) * 0.008f
            val reach = 30f * (0.65f + 0.35f * n2)
            val dx = cos(angle) * reach
            val dy = sin(angle) * reach
            sample(src, w, h, x + dx, y + dy, base)
            sample(src, w, h, x + dx + cos(angle + 0.5f) * 8f, y + dy + sin(angle + 0.5f) * 8f, sideA)
            sample(src, w, h, x + dx - cos(angle - 0.4f) * 8f, y + dy - sin(angle - 0.4f) * 8f, sideB)
            val i = (y * w + x) * 4
            out[i] = base[0] * 0.6f + sideA[0] * 0.2f + sideB[0] * 0.2f
            out[i + 1] = base[1] * 0.6f + sideA[1] * 0.2f + sideB[1] * 0.2f
            out[i + 2] = base[2] * 0.6f + sideA[2] * 0.2f + sideB[2] * 0.2f
            out[i + 3] = base[3] * 0.6f + sideA[3] * 0.2f + sideB[3] * 0.2f
        }
        return out
    }

    /** Pop art: a white sheet with saturated halftone dots sampled from the content. */
    private fun popArt(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int, p: PopArtParams): FloatArray {
        val cell = 12
        val radius = cell * 0.5f * p.dotScale.coerceIn(0.2f, 1.2f)
        val saturation = p.saturation.coerceIn(0f, 2f)
        val out = FloatArray(src.size)
        for (i in out.indices step 4) {
            out[i] = 1f
            out[i + 1] = 1f
            out[i + 2] = 1f
            out[i + 3] = 1f
        }
        val gx0 = (ox / cell) * cell
        val gy0 = (oy / cell) * cell
        val s = FloatArray(4)
        for (cy in gy0 until oy + h step cell) {
            for (cx in gx0 until ox + w step cell) {
                val centerX = cx + cell / 2f
                val centerY = cy + cell / 2f
                sample(src, w, h, centerX - ox, centerY - oy, s)
                val a = s[3]
                if (a <= 0.01f) continue
                var r = (s[0] / a).coerceIn(0f, 1f)
                var g = (s[1] / a).coerceIn(0f, 1f)
                var b = (s[2] / a).coerceIn(0f, 1f)
                val lum = 0.3f * r + 0.59f * g + 0.11f * b
                r = (lum + (r - lum) * saturation).coerceIn(0f, 1f)
                g = (lum + (g - lum) * saturation).coerceIn(0f, 1f)
                b = (lum + (b - lum) * saturation).coerceIn(0f, 1f)
                val xa = max((centerX - radius - ox).toInt(), 0)
                val xb = min((centerX + radius - ox).toInt() + 1, w)
                val ya = max((centerY - radius - oy).toInt(), 0)
                val yb = min((centerY + radius - oy).toInt() + 1, h)
                if (xb <= xa || yb <= ya) continue
                for (y in ya until yb) for (x in xa until xb) {
                    val d = hypot(x + ox + 0.5f - centerX, y + oy + 0.5f - centerY)
                    val cov = (radius + 0.5f - d).coerceIn(0f, 1f)
                    if (cov <= 0f) continue
                    val i = (y * w + x) * 4
                    out[i] = r * cov + out[i] * (1f - cov)
                    out[i + 1] = g * cov + out[i + 1] * (1f - cov)
                    out[i + 2] = b * cov + out[i + 2] * (1f - cov)
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- final M5 batch

    /**
     * A manga tone: the alpha-weighted luminance of each [cellPx] cell is flattened to four levels and painted
     * as a dot screen, with the luminance edges over it as ink. The lattice and the edges are evaluated at
     * canvas coordinates, so tiles agree on every dot and outline that they share.
     */
    private fun mangaTone(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        cellPx: Int,
        brightness: Float,
        contrast: Float,
        edge: Float,
        background: Boolean,
    ): FloatArray {
        val cell = cellPx.coerceIn(2, 32)
        val lum = luminance(src)
        val out = FloatArray(src.size)
        val tone = FloatArray(w * h)
        val startX = floor(ox.toFloat() / cell).toInt() * cell
        val startY = floor(oy.toFloat() / cell).toInt() * cell
        for (cy in startY until oy + h step cell) {
            for (cx in startX until ox + w step cell) {
                val xa = max(cx, ox)
                val xb = min(cx + cell, ox + w)
                val ya = max(cy, oy)
                val yb = min(cy + cell, oy + h)
                if (xb <= xa || yb <= ya) continue
                var sum = 0f
                var weight = 0f
                for (y in ya until yb) for (x in xa until xb) {
                    val i = (y - oy) * w + (x - ox)
                    val a = src[i * 4 + 3]
                    if (a <= 0.002f) continue
                    sum += lum[i]
                    weight += a
                }
                if (weight <= 0.002f) continue
                var value = (sum / weight).coerceIn(0f, 1f)
                value = if (background) {
                    ((value - 0.5f) * (1f + contrast * 2f) + 0.5f).coerceIn(0f, 1f)
                } else {
                    (value + brightness).coerceIn(0f, 1f)
                }
                // Four flat steps keep the dot size readable at small cell sizes.
                val flat = (value * 3f).roundToInt() / 3f
                for (y in ya until yb) for (x in xa until xb) tone[(y - oy) * w + (x - ox)] = flat
            }
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val j = i * 4
            val a = src[j + 3]
            if (a <= 0.002f) continue
            val gx = x + ox
            val gy = y + oy
            val ccx = floor(gx.toFloat() / cell).toInt() * cell + cell * 0.5f
            val ccy = floor(gy.toFloat() / cell).toInt() * cell + cell * 0.5f
            val radius = cell * 0.72f * sqrt((1f - tone[i]).coerceIn(0f, 1f))
            val d = hypot(gx + 0.5f - ccx, gy + 0.5f - ccy)
            val paper = 1f - (radius + 0.5f - d).coerceIn(0f, 1f)
            val e = sobel(lum, w, h, x, y)
            val ink = ((e - 0.04f) / 0.32f).coerceIn(0f, 1f) * edge.coerceIn(0f, 1f)
            val value = min(paper, 1f - ink)
            out[j] = value * a
            out[j + 1] = value * a
            out[j + 2] = value * a
            out[j + 3] = a
        }
        return out
    }

    /** Anime background: bright saturated colours posterized to [AnimeBackgroundParams.posterize] levels. */
    private fun animeBackground(src: FloatArray, p: AnimeBackgroundParams): FloatArray {
        val brightness = p.brightness.coerceIn(-100f, 100f) / 100f * 0.5f
        val contrast = 1f + p.contrast.coerceIn(-100f, 100f) / 100f
        val saturation = (1f + p.saturation.coerceIn(-100f, 100f) / 100f).coerceIn(0f, 2f)
        val step = (p.posterize.coerceIn(2, 16) - 1).toFloat()
        val out = FloatArray(src.size)
        for (i in src.indices step 4) {
            val a = src[i + 3]
            if (a <= 0f) continue
            var r = (src[i] / a).coerceIn(0f, 1f)
            var g = (src[i + 1] / a).coerceIn(0f, 1f)
            var b = (src[i + 2] / a).coerceIn(0f, 1f)
            val lum = 0.3f * r + 0.59f * g + 0.11f * b
            r = lum + (r - lum) * saturation
            g = lum + (g - lum) * saturation
            b = lum + (b - lum) * saturation
            r = ((r - 0.5f) * contrast + 0.5f + brightness).coerceIn(0f, 1f)
            g = ((g - 0.5f) * contrast + 0.5f + brightness).coerceIn(0f, 1f)
            b = ((b - 0.5f) * contrast + 0.5f + brightness).coerceIn(0f, 1f)
            // Flat posterized bands are what makes a painted anime background.
            r = (r * step).roundToInt() / step
            g = (g * step).roundToInt() / step
            b = (b * step).roundToInt() / step
            out[i] = r * a
            out[i + 1] = g * a
            out[i + 2] = b * a
            out[i + 3] = a
        }
        return out
    }

    /** Pencil sketch: graphite hatching of [PencilSketchParams.lineLength] px plus darker contours on paper. */
    private fun pencilSketch(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        p: PencilSketchParams,
    ): FloatArray {
        val length = p.lineLength.coerceIn(2f, 48f)
        val spacing = (length * 0.65f).coerceAtLeast(2f)
        val period = (length * 1.6f).coerceAtLeast(3f)
        val lum = luminance(src)
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val j = i * 4
            val a = src[j + 3]
            if (a <= 0.002f) continue
            val gx = x + ox + 0.5f
            val gy = y + oy + 0.5f
            val shade = (1f - lum[i] / a).coerceIn(0f, 1f)
            var ink = hatchInk(gx, gy, 1f, spacing, period, length, (shade - 0.05f) / 0.75f)
            if (shade > 0.5f) {
                ink = max(ink, hatchInk(gx, gy, -1f, spacing, period, length, (shade - 0.5f) / 0.5f))
            }
            val e = sobel(lum, w, h, x, y)
            ink = max(ink, ((e - 0.04f) / 0.32f).coerceIn(0f, 1f) * (0.35f + 0.65f * shade))
            // Sparse graphite grain breaks up the stroke edges like paper tooth.
            val grain = (hash(x + ox, y + oy, 0x2C1B3C6DL) + 1f) * 0.5f
            val value = (1f - ink * 0.9f - grain * 0.06f * shade).coerceIn(0f, 1f)
            out[j] = value * a
            out[j + 1] = value * a
            out[j + 2] = value * a
            out[j + 3] = a
        }
        return out
    }

    /**
     * One family of pencil dashes: rows of [spacing] px across the stroke direction, each row a run of [length]
     * px repeated every [period] px. [amount] is the maximum ink of the family, 0 when the tone is too light.
     */
    private fun hatchInk(
        gx: Float,
        gy: Float,
        dir: Float,
        spacing: Float,
        period: Float,
        length: Float,
        amount: Float,
    ): Float {
        if (amount <= 0f) return 0f
        val s = 0.70710677f
        val along = (gx * dir + gy) * s
        val across = (gy - gx * dir) * s
        val row = floor(across / spacing).toInt()
        val jitter = hash(row, dir.toInt(), 0x51ED2701L) * spacing * 0.25f
        val centre = (row + 0.5f) * spacing + jitter
        val acrossCov = (1f - abs(across - centre)).coerceIn(0f, 1f)
        if (acrossCov <= 0f) return 0f
        val offset = hash(row, dir.toInt() + 11, 0x7F4A7C15L) * period
        var phase = (along + offset) % period
        if (phase < 0f) phase += period
        val alongCov = (min(phase, length - phase) * 2f).coerceIn(0f, 1f)
        return min(acrossCov, alongCov) * amount.coerceIn(0f, 1f)
    }

    /**
     * Oil paint: the classic Kuwahara filter. Of the four [OilPaintParams.radius] quadrants around a pixel the
     * flattest one supplies its mean colour, which keeps edges sharp while flattening the paint into
     * [OilPaintParams.levels] bands. Summed-area tables make each quadrant O(1), so a large radius stays cheap.
     */
    private fun oilPaint(src: FloatArray, w: Int, h: Int, p: OilPaintParams): FloatArray {
        val radius = p.radius.coerceIn(1, 12)
        val step = (p.levels.coerceIn(2, 32) - 1).toFloat()
        val stride = w + 1
        val size = stride * (h + 1)
        val sa = FloatArray(size)
        val sr = FloatArray(size)
        val sg = FloatArray(size)
        val sb = FloatArray(size)
        val sl = FloatArray(size)
        val sl2 = FloatArray(size)
        for (y in 0 until h) {
            var ra = 0f
            var rr = 0f
            var rg = 0f
            var rb = 0f
            var rl = 0f
            var rl2 = 0f
            val row = (y + 1) * stride
            val prev = y * stride
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                val a = src[i + 3]
                val r = src[i]
                val g = src[i + 1]
                val b = src[i + 2]
                ra += a
                rr += r
                rg += g
                rb += b
                if (a > 0.002f) {
                    val l = (0.3f * r + 0.59f * g + 0.11f * b) / a
                    rl += l * a
                    rl2 += l * l * a
                }
                sa[row + x + 1] = sa[prev + x + 1] + ra
                sr[row + x + 1] = sr[prev + x + 1] + rr
                sg[row + x + 1] = sg[prev + x + 1] + rg
                sb[row + x + 1] = sb[prev + x + 1] + rb
                sl[row + x + 1] = sl[prev + x + 1] + rl
                sl2[row + x + 1] = sl2[prev + x + 1] + rl2
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            val j = (y * w + x) * 4
            val a = src[j + 3]
            if (a <= 0.002f) continue
            var best = Float.MAX_VALUE
            var mr = 0f
            var mg = 0f
            var mb = 0f
            var found = false
            for (qy in 0..1) for (qx in 0..1) {
                val x0 = if (qx == 0) max(0, x - radius) else x
                val x1 = if (qx == 0) x + 1 else min(w, x + radius + 1)
                val y0 = if (qy == 0) max(0, y - radius) else y
                val y1 = if (qy == 0) y + 1 else min(h, y + radius + 1)
                val bottom = y1 * stride
                val top = y0 * stride
                val alpha = sa[bottom + x1] - sa[top + x1] - sa[bottom + x0] + sa[top + x0]
                if (alpha <= 0.002f) continue
                val lSum = sl[bottom + x1] - sl[top + x1] - sl[bottom + x0] + sl[top + x0]
                val l2Sum = sl2[bottom + x1] - sl2[top + x1] - sl2[bottom + x0] + sl2[top + x0]
                val mean = lSum / alpha
                val variance = (l2Sum / alpha - mean * mean).coerceAtLeast(0f)
                if (!found || variance < best) {
                    found = true
                    best = variance
                    mr = (sr[bottom + x1] - sr[top + x1] - sr[bottom + x0] + sr[top + x0]) / alpha
                    mg = (sg[bottom + x1] - sg[top + x1] - sg[bottom + x0] + sg[top + x0]) / alpha
                    mb = (sb[bottom + x1] - sb[top + x1] - sb[bottom + x0] + sb[top + x0]) / alpha
                }
            }
            if (!found) continue
            val r = (mr.coerceIn(0f, 1f) * step).roundToInt() / step
            val g = (mg.coerceIn(0f, 1f) * step).roundToInt() / step
            val b = (mb.coerceIn(0f, 1f) * step).roundToInt() / step
            out[j] = r * a
            out[j + 1] = g * a
            out[j + 2] = b * a
            out[j + 3] = a
        }
        return out
    }

    /**
     * Blur frame: the last [BlurFrameParams.width] px inside the content edge are replaced by a blur of
     * [BlurFrameParams.blur] px with a smooth falloff, so the artwork fades into an unfocused vignette.
     */
    private fun blurFrame(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        g: Geometry,
        p: BlurFrameParams,
    ): FloatArray {
        val width = p.width.coerceIn(1f, 400f)
        val radius = ceil(p.blur.coerceIn(1f, 32f)).toInt()
        val blurred = localBlur(src, w, h, radius)
        val out = src.copyOf()
        val content = g.content
        for (y in 0 until h) for (x in 0 until w) {
            val gx = x + ox
            val gy = y + oy
            val d = minOf(
                gx - content.x0,
                content.x1 - 1 - gx,
                gy - content.y0,
                content.y1 - 1 - gy,
            ).toFloat()
            if (d >= width) continue
            val t = (d / width).coerceIn(0f, 1f)
            val k = 1f - t * t * (3f - 2f * t)
            val i = (y * w + x) * 4
            out[i] = src[i] + (blurred[i] - src[i]) * k
            out[i + 1] = src[i + 1] + (blurred[i + 1] - src[i + 1]) * k
            out[i + 2] = src[i + 2] + (blurred[i + 2] - src[i + 2]) * k
            out[i + 3] = src[i + 3] + (blurred[i + 3] - src[i + 3]) * k
        }
        return out
    }

    /** Table frame: a [TableFrameParams.width] px border in [TableFrameParams.color] around the content. */
    private fun tableFrame(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        g: Geometry,
        p: TableFrameParams,
    ): FloatArray {
        val width = p.width.coerceIn(1f, 400f)
        val ink = ink(p.color)
        val out = src.copyOf()
        val content = g.content
        val halfW = content.width / 2f
        val halfH = content.height / 2f
        if (halfW <= 0f || halfH <= 0f) return out
        val cx = content.x0 + halfW
        val cy = content.y0 + halfH
        val radius = p.cornerRadius.coerceIn(0f, 400f)
        val innerW = halfW - width
        val innerH = halfH - width
        for (y in 0 until h) for (x in 0 until w) {
            val px = x + ox + 0.5f - cx
            val py = y + oy + 0.5f - cy
            val outer = roundRectDistance(px, py, halfW, halfH, radius)
            if (outer >= 0.5f) continue
            var cov = (0.5f - outer).coerceIn(0f, 1f)
            if (innerW > 0.5f && innerH > 0.5f) {
                val inner = roundRectDistance(px, py, innerW, innerH, (radius - width).coerceAtLeast(0f))
                cov *= 1f - (0.5f - inner).coerceIn(0f, 1f)
            }
            if (cov > 0.002f) overInk(out, (y * w + x) * 4, ink, cov)
        }
        return out
    }

    /** Signed distance to the rounded rectangle of half extents [halfW],[halfH]; negative inside. */
    private fun roundRectDistance(px: Float, py: Float, halfW: Float, halfH: Float, radius: Float): Float {
        val r = radius.coerceIn(0f, min(halfW, halfH))
        val qx = abs(px) - halfW + r
        val qy = abs(py) - halfH + r
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - r
    }

    /**
     * Rain: one white streak per occupied cell of a 14 px canvas-anchored lattice. The cell hash also supplies
     * the drop position, its length and its presence at [RainParams.density], so no tile needs a neighbour.
     */
    private fun rain(src: FloatArray, w: Int, h: Int, ox: Int, oy: Int, p: RainParams): FloatArray {
        val out = src.copyOf()
        val density = p.density.coerceIn(0f, 1f)
        val opacity = p.opacity.coerceIn(0f, 1f)
        if (density <= 0.001f || opacity <= 0.001f) return out
        val pitch = 14f
        val length = p.length.coerceIn(4f, 200f)
        val angle = Math.toRadians(p.angleDeg.toDouble()).toFloat()
        val dx = cos(angle)
        val dy = sin(angle)
        val margin = length + 4f
        val minRow = floor((oy - margin) / pitch).toInt() - 2
        val maxRow = floor((oy + h + margin) / pitch).toInt() + 2
        val minCol = floor((ox - margin) / pitch).toInt() - 2
        val maxCol = floor((ox + w + margin) / pitch).toInt() + 2
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                val present = (hash(col, row, p.seed) + 1f) * 0.5f
                if (present >= density) continue
                val jx = hash(col, row, p.seed + 1)
                val jy = hash(col, row, p.seed + 2)
                val cx = (col + 0.5f + jx * 0.4f) * pitch
                val cy = (row + 0.5f + jy * 0.4f) * pitch
                val len = length * (0.55f + 0.45f * abs(hash(col, row, p.seed + 3)))
                drawRainStreak(
                    out, w, h, ox, oy,
                    cx - dx * len, cy - dy * len, cx, cy, opacity,
                )
            }
        }
        return out
    }

    /** One anti-aliased rain streak, brighter toward its leading end, painted white over the patch. */
    private fun drawRainStreak(
        out: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        opacity: Float,
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 0.01f) return
        val steps = ceil(len / 0.5f).toInt().coerceIn(1, 2048)
        val radius = 0.7f
        for (s in 0..steps) {
            val t = s / steps.toFloat()
            val px = x0 + dx * t
            val py = y0 + dy * t
            val fade = opacity * (0.35f + 0.65f * t)
            val xa = max((px - radius).toInt(), ox)
            val xb = min((px + radius).toInt() + 1, ox + w)
            val ya = max((py - radius).toInt(), oy)
            val yb = min((py + radius).toInt() + 1, oy + h)
            if (xb <= xa || yb <= ya) continue
            for (y in ya until yb) for (x in xa until xb) {
                val d = hypot(x + 0.5f - px, y + 0.5f - py)
                val cov = (radius + 0.5f - d).coerceIn(0f, 1f) * fade
                if (cov <= 0f) continue
                val i = ((y - oy) * w + (x - ox)) * 4
                out[i] = cov + out[i] * (1f - cov)
                out[i + 1] = cov + out[i + 1] * (1f - cov)
                out[i + 2] = cov + out[i + 2] * (1f - cov)
                out[i + 3] = cov + out[i + 3] * (1f - cov)
            }
        }
    }

    private val BAYER = floatArrayOf(
        0f, 8f, 2f, 10f,
        12f, 4f, 14f, 6f,
        3f, 11f, 1f, 9f,
        15f, 7f, 13f, 5f,
    ).map { it / 16f }.toFloatArray()

    private val RETRO_NES = intArrayOf(
        0x000000, 0xFCFCFC, 0xBCBCBC, 0x7C7C7C,
        0x0078F8, 0x00B8F8, 0x58D854, 0x00A800,
        0xF8B800, 0xF87858, 0xF83800, 0xD82800,
        0xA800A8, 0x6844FC, 0xF878F8, 0xFCE4A0,
    )

    private val RETRO_GB = intArrayOf(0x0F380F, 0x306230, 0x8BAC0F, 0x9BBC0F)

    private val RETRO_16 = intArrayOf(
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
        0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
        0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    )

    // ---------------------------------------------------------------- generators (draw)

    /** Radial strokes from the content centre: [RadialLinesParams]. */
    private fun radialLines(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        g: Geometry,
        p: RadialLinesParams,
    ): FloatArray {
        val out = src.copyOf()
        val ink = ink(p.color)
        val count = p.count.coerceIn(2, 500)
        val inner = g.radius * p.inner.coerceIn(0f, 0.8f)
        val random = p.random.coerceIn(0f, 1f)
        val thickness = p.thickness.coerceIn(0.5f, 40f)
        val twoPi = (Math.PI * 2).toFloat()
        for (i in 0 until count) {
            val jitter = (hash(i, 0, p.seed) + 1f) * 0.5f
            val a = twoPi * i / count + twoPi / count * 0.5f * jitter
            val len = g.radius * (1f - random * ((hash(i, 1, p.seed) + 1f) * 0.5f))
            drawLine(
                out, w, h, ox, oy,
                g.cx + cos(a) * inner, g.cy + sin(a) * inner,
                g.cx + cos(a) * len, g.cy + sin(a) * len,
                thickness / 2f, ink,
            )
        }
        return out
    }

    /** Parallel lines across the canvas at [SpeedLinesParams.angleDeg]. */
    private fun speedLines(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        g: Geometry,
        p: SpeedLinesParams,
    ): FloatArray {
        val out = src.copyOf()
        val ink = ink(p.color)
        val count = p.count.coerceIn(2, 400)
        val thickness = p.thickness.coerceIn(0.5f, 40f)
        val a = Math.toRadians(p.angleDeg.toDouble()).toFloat()
        val dx = cos(a)
        val dy = sin(a)
        val nx = -dy
        val ny = dx
        val half = g.radius * 1.5f
        val spacing = 2f * half / count
        val len = half * p.length.coerceIn(0.2f, 1f)
        for (i in 0 until count) {
            val off = -half + (i + 0.5f) * spacing + hash(i, 0, p.seed) * spacing * 0.25f
            val cx = g.cx + nx * off
            val cy = g.cy + ny * off
            drawLine(out, w, h, ox, oy, cx - dx * len, cy - dy * len, cx + dx * len, cy + dy * len, thickness / 2f, ink)
        }
        return out
    }

    /** Luminance halftone: a dot per [HalftoneParams.cell] px, darker cells get bigger dots. */
    private fun halftone(
        src: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        p: HalftoneParams,
    ): FloatArray {
        val cell = p.cell.coerceIn(2, 64)
        val ink = ink(p.color)
        val out = FloatArray(src.size)
        val gx0 = (ox / cell) * cell
        val gy0 = (oy / cell) * cell
        for (cy in gy0 until oy + h step cell) {
            for (cx in gx0 until ox + w step cell) {
                val xa = max(cx, ox)
                val xb = min(cx + cell, ox + w)
                val ya = max(cy, oy)
                val yb = min(cy + cell, oy + h)
                if (xb <= xa || yb <= ya) continue
                var lum = 0f
                var alpha = 0f
                var n = 0
                for (y in ya until yb) for (x in xa until xb) {
                    val i = ((y - oy) * w + (x - ox)) * 4
                    val a = src[i + 3]
                    if (a > 0f) {
                        lum += (0.3f * src[i] + 0.59f * src[i + 1] + 0.11f * src[i + 2]) / a
                        alpha += a
                    }
                    n++
                }
                if (n == 0 || alpha <= 0f) continue
                lum = (lum / (alpha.coerceAtLeast(1e-4f))).coerceIn(0f, 1f)
                val meanAlpha = (alpha / n).coerceIn(0f, 1f)
                // Darker cell -> bigger dot (a dot of radius cell/2 covers everything at black).
                val radius = cell / 2f * sqrt(1f - lum)
                if (radius <= 0.05f) continue
                val ccx = cx + cell / 2f
                val ccy = cy + cell / 2f
                for (y in ya until yb) for (x in xa until xb) {
                    val dx = x + 0.5f - ccx
                    val dy = y + 0.5f - ccy
                    val cov = (radius + 0.5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
                    if (cov <= 0f) continue
                    val i = ((y - oy) * w + (x - ox)) * 4
                    val a = cov * meanAlpha
                    out[i] = ink[0] * a
                    out[i + 1] = ink[1] * a
                    out[i + 2] = ink[2] * a
                    out[i + 3] = a
                }
            }
        }
        return out
    }

    /** "#RRGGBB" to straight (not premultiplied) rgb in 0..1; malformed input is black. */
    private fun ink(hex: String): FloatArray {
        val h = hex.removePrefix("#")
        val v = if (h.length == 6) h.toLongOrNull(16) ?: 0L else 0L
        return floatArrayOf(((v shr 16) and 0xFF) / 255f, ((v shr 8) and 0xFF) / 255f, (v and 0xFF) / 255f)
    }

    /** Anti-aliased round-cap segment stamped into a premultiplied patch buffer, clipped to the patch. */
    private fun drawLine(
        out: FloatArray,
        w: Int,
        h: Int,
        ox: Int,
        oy: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        radius: Float,
        ink: FloatArray,
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 0.01f || radius <= 0f) return
        val steps = ceil(len / (radius.coerceAtLeast(0.5f) * 0.5f)).toInt().coerceIn(1, 4096)
        for (s in 0..steps) {
            val t = s / steps.toFloat()
            val px = x0 + dx * t
            val py = y0 + dy * t
            val xa = max((px - radius).toInt(), ox)
            val xb = min((px + radius).toInt() + 1, ox + w)
            val ya = max((py - radius).toInt(), oy)
            val yb = min((py + radius).toInt() + 1, oy + h)
            if (xb <= xa || yb <= ya) continue
            for (y in ya until yb) for (x in xa until xb) {
                val d = hypot(x + 0.5f - px, y + 0.5f - py)
                val cov = (radius + 0.5f - d).coerceIn(0f, 1f)
                if (cov <= 0f) continue
                val i = ((y - oy) * w + (x - ox)) * 4
                out[i] = ink[0] * cov + out[i] * (1f - cov)
                out[i + 1] = ink[1] * cov + out[i + 1] * (1f - cov)
                out[i + 2] = ink[2] * cov + out[i + 2] * (1f - cov)
                out[i + 3] = cov + out[i + 3] * (1f - cov)
            }
        }
    }

    // ---------------------------------------------------------------- raster layer styles

    /** Outline around the alpha of the layer: outer, inner or both, with a hard width. */
    private fun layerStroke(src: FloatArray, w: Int, h: Int, p: LayerStrokeParams): FloatArray {
        val width = p.width.coerceIn(1, 100)
        val alpha = FloatArray(w * h) { src[it * 4 + 3] }
        val outer = if (p.outer) maxFilter(alpha, w, h, width) else null
        val inner = if (p.inner) minFilter(alpha, w, h, width) else null
        val ink = ink(p.color)
        val out = src.copyOf()
        val scale = p.opacity.coerceIn(0f, 1f)
        for (i in 0 until w * h) {
            var a = 0f
            if (outer != null) a = max(a, outer[i] - alpha[i])
            if (inner != null) a = max(a, alpha[i] - inner[i])
            a *= scale
            if (a > 0.001f) overInk(out, i * 4, ink, a)
        }
        return mix(src, out, p.strength)
    }

    /** Drop shadow: the alpha shifted by angle/distance, blurred, coloured and put under the layer. */
    private fun dropShadow(src: FloatArray, w: Int, h: Int, p: DropShadowParams): FloatArray {
        val dist = p.distance.coerceIn(0, 200)
        val blur = p.blur.coerceIn(0, 100)
        val a = Math.toRadians(p.angleDeg.toDouble())
        val dx = (cos(a) * dist).toInt()
        val dy = (sin(a) * dist).toInt()
        val shifted = FloatArray(w * h)
        for (y in 0 until h) {
            val sy = y - dy
            if (sy !in 0 until h) continue
            for (x in 0 until w) {
                val sx = x - dx
                if (sx !in 0 until w) continue
                shifted[y * w + x] = src[(sy * w + sx) * 4 + 3]
            }
        }
        val shadow = if (blur > 0) boxBlurAlpha(shifted, w, h, blur) else shifted
        val ink = ink(p.color)
        val scale = p.opacity.coerceIn(0f, 1f)
        // result = source + shadow * (1 - sourceAlpha).
        val result = src.copyOf()
        for (i in 0 until w * h) {
            val j = i * 4
            val keep = 1f - src[j + 3]
            val sa = (shadow[i] * scale).coerceIn(0f, 1f)
            result[j] = result[j] + ink[0] * sa * keep
            result[j + 1] = result[j + 1] + ink[1] * sa * keep
            result[j + 2] = result[j + 2] + ink[2] * sa * keep
            result[j + 3] = result[j + 3] + sa * keep
        }
        return mix(src, result, p.strength)
    }

    /** Soft outer glow behind the layer. */
    private fun layerGlow(src: FloatArray, w: Int, h: Int, p: LayerGlowParams): FloatArray {
        val radius = p.radius.coerceIn(1, 100)
        val a = FloatArray(w * h) { src[it * 4 + 3] }
        val glow = boxBlurAlpha(a, w, h, radius)
        val ink = ink(p.color)
        val res = src.copyOf()
        val scale = p.opacity.coerceIn(0f, 1f)
        for (i in 0 until w * h) {
            val j = i * 4
            val keep = 1f - src[j + 3]
            val ga = (glow[i] * scale).coerceIn(0f, 1f) * keep
            res[j] += ink[0] * ga
            res[j + 1] += ink[1] * ga
            res[j + 2] += ink[2] * ga
            res[j + 3] += ga
        }
        return mix(src, res, p.strength)
    }

    /** Divides the page into panels with a margin, gutters and an outer frame. */
    private fun panelGrid(src: FloatArray, w: Int, h: Int, p: PanelGridParams): FloatArray {
        val out = src.copyOf()
        val rows = p.rows.coerceIn(1, 12)
        val cols = p.cols.coerceIn(1, 12)
        val margin = p.margin.coerceIn(0, min(w, h) / 2)
        val gutter = p.gutter.coerceIn(0, min(w, h) / 2)
        val thickness = p.thickness.coerceIn(1, 100)
        val ix0 = margin
        val iy0 = margin
        val ix1 = w - margin
        val iy1 = h - margin
        if (ix1 <= ix0 || iy1 <= iy0) return out
        val ink = ink(p.color)
        val panelW = (ix1 - ix0 - gutter * (cols - 1)) / cols.toFloat()
        val panelH = (iy1 - iy0 - gutter * (rows - 1)) / rows.toFloat()
        // Gutters.
        for (c in 1 until cols) {
            val x0 = (ix0 + panelW * c + gutter * (c - 1)).toInt()
            fillRect(out, w, h, x0, iy0, x0 + gutter, iy1, ink)
        }
        for (r in 1 until rows) {
            val y0 = (iy0 + panelH * r + gutter * (r - 1)).toInt()
            fillRect(out, w, h, ix0, y0, ix1, y0 + gutter, ink)
        }
        // Outer frame and the panel borders (margin included).
        fillRect(out, w, h, ix0, iy0, ix1, iy0 + thickness, ink)
        fillRect(out, w, h, ix0, iy1 - thickness, ix1, iy1, ink)
        fillRect(out, w, h, ix0, iy0, ix0 + thickness, iy1, ink)
        fillRect(out, w, h, ix1 - thickness, iy0, ix1, iy1, ink)
        return mix(src, out, p.strength)
    }

    /** Screentone: a dot/line/cross pattern on a [ScreentoneParams.cell] grid, optionally rotated. */
    private fun screentone(src: FloatArray, w: Int, h: Int, p: ScreentoneParams): FloatArray {
        val cell = p.cell.coerceIn(2, 64)
        val ink = ink(p.color)
        val angle = Math.toRadians(p.angleDeg.toDouble())
        val ca = cos(angle)
        val sa = sin(angle)
        val out = src.copyOf()
        val cx = w / 2f
        val cy = h / 2f
        val radius = cell * 0.32f
        for (y in 0 until h) for (x in 0 until w) {
            // Rotate the pixel into the pattern frame around the canvas centre.
            val dx = (x + 0.5f - cx)
            val dy = (y + 0.5f - cy)
            val u = dx * ca + dy * sa
            val v = -dx * sa + dy * ca
            val gx = u - cell * floor(u / cell)
            val gy = v - cell * floor(v / cell)
            val cx0 = cell / 2f
            val dot = gx - cx0
            val doy = gy - cx0
            val hit = when (p.shape.lowercase()) {
                "line" -> abs(dot) <= 0.75f
                "cross" -> abs(dot) <= 0.75f || abs(doy) <= 0.75f
                else -> hypot(dot, doy) <= radius
            }
            if (hit) overInk(out, (y * w + x) * 4, ink, 1f)
        }
        return mix(src, out, p.strength)
    }

    private fun fillRect(out: FloatArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, ink: FloatArray) {
        val xa = x0.coerceIn(0, w)
        val xb = x1.coerceIn(0, w)
        val ya = y0.coerceIn(0, h)
        val yb = y1.coerceIn(0, h)
        for (y in ya until yb) for (x in xa until xb) overInk(out, (y * w + x) * 4, ink, 1f)
    }

    /** Premultiplied "ink over dest" at coverage [a]. */
    private fun overInk(out: FloatArray, j: Int, ink: FloatArray, a: Float) {
        val k = a.coerceIn(0f, 1f)
        out[j] = ink[0] * k + out[j] * (1f - k)
        out[j + 1] = ink[1] * k + out[j + 1] * (1f - k)
        out[j + 2] = ink[2] * k + out[j + 2] * (1f - k)
        out[j + 3] = k + out[j + 3] * (1f - k)
    }

    /** Separable sliding-window maximum of [a]. */
    private fun maxFilter(a: FloatArray, w: Int, h: Int, r: Int): FloatArray =
        windowFilter(a, w, h, r, maximum = true)

    /** Separable sliding-window minimum of [a]. */
    private fun minFilter(a: FloatArray, w: Int, h: Int, r: Int): FloatArray =
        windowFilter(a, w, h, r, maximum = false)

    private fun windowFilter(a: FloatArray, w: Int, h: Int, r: Int, maximum: Boolean): FloatArray {
        val tmp = FloatArray(a.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var best = if (maximum) 0f else 1f
                for (k in -r..r) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    val v = a[y * w + xx]
                    if (maximum) { if (v > best) best = v } else if (v < best) best = v
                }
                tmp[y * w + x] = best
            }
        }
        val out = FloatArray(a.size)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var best = if (maximum) 0f else 1f
                for (k in -r..r) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    val v = tmp[yy * w + x]
                    if (maximum) { if (v > best) best = v } else if (v < best) best = v
                }
                out[y * w + x] = best
            }
        }
        return out
    }

    /** Separable box blur of a single alpha channel. */
    private fun boxBlurAlpha(a: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val r = radius.coerceIn(1, 100)
        val tmp = FloatArray(a.size)
        val out = FloatArray(a.size)
        for (y in 0 until h) {
            var sum = 0f
            for (x in -r..r) sum += a[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = sum / (r * 2 + 1)
                sum += a[y * w + (x + r + 1).coerceIn(0, w - 1)]
                sum -= a[y * w + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / (r * 2 + 1)
                sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sum -= tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    /** Bilinear sample of a premultiplied patch, clamped to its edges. */
    private fun sample(buf: FloatArray, w: Int, h: Int, x: Float, y: Float, out: FloatArray) {        if (w <= 0 || h <= 0) { out.fill(0f); return }
        val fx = x.coerceIn(0f, (w - 1).toFloat())
        val fy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val i00 = (y0 * w + x0) * 4
        val i10 = (y0 * w + x1) * 4
        val i01 = (y1 * w + x0) * 4
        val i11 = (y1 * w + x1) * 4
        for (c in 0..3) {
            val top = buf[i00 + c] + (buf[i10 + c] - buf[i00 + c]) * tx
            val bottom = buf[i01 + c] + (buf[i11 + c] - buf[i01 + c]) * tx
            out[c] = top + (bottom - top) * ty
        }
    }

    /** Deterministic noise in -1..1 from canvas pixel and seed. */
    private fun hash(x: Int, y: Int, seed: Long): Float {
        var z = seed * -0x61c8864680b583ebL + x * -0x7a1435883d4d519L + y * 0x2545f4914f6cdd1dL
        z = (z xor (z ushr 33)) * -0xae502812aa7333L
        z = (z xor (z ushr 29)) * -0x3b314601e57a13adL
        z = z xor (z ushr 32)
        return ((z ushr 40).toFloat() / 8388608f) - 1f
    }

    private fun runParallel(jobs: List<Callable<Unit>>, threads: Int) {
        if (jobs.isEmpty()) return
        val workers = min(threads.coerceAtLeast(1), jobs.size)
        if (workers <= 1) {
            jobs.forEach { it.call() }
            return
        }
        val pool = Executors.newFixedThreadPool(workers) { r -> Thread(r, "painter-filter").apply { isDaemon = true } }
        try {
            pool.invokeAll(jobs).forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
}
