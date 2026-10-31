package app.painter.core.filter

import app.painter.core.history.snapshotTiles
import app.painter.core.raster.IntRect
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch
import app.painter.core.stroke.StrokeContext
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
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
 * Destructive filters of spec/filters.md (M3 set). A filter runs over the painted area of one layer, tile by
 * tile: each tile is processed with a patch padded by [FilterParams.reach], so neighbouring tiles never show a
 * seam. Pixels outside [out] (the content expanded by the reach, clipped by the selection) are left alone.
 *
 * All arithmetic is on premultiplied RGBA floats, like the rest of the raster code.
 */
object Filters {

    private const val TILE = Tile.SIZE

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
        val canvas = IntRect(0, 0, grid.width, grid.height)
        val reach = params.reach.coerceAtLeast(0)
        val padded = IntRect(
            (content.x0 - reach).coerceAtLeast(0),
            (content.y0 - reach).coerceAtLeast(0),
            (content.x1 + reach).coerceAtMost(grid.width),
            (content.y1 + reach).coerceAtMost(grid.height),
        )
        val out = if (selection != null) {
            val sel = selection.bounds() ?: return TilePatch(layerId, emptyMap(), emptyMap())
            padded.intersect(sel) ?: return TilePatch(layerId, emptyMap(), emptyMap())
        } else {
            padded
        }
        val keys = out.tileKeys(grid.width, grid.height)
        if (keys.isEmpty()) return TilePatch(layerId, emptyMap(), emptyMap())

        val before = snapshotTiles(grid, keys)
        process(grid, params, out, keys, selection, alphaLock, Geometry(content), threads)
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
        val reach = params.reach.coerceAtLeast(0)
        val padded = IntRect(
            (content.x0 - reach).coerceAtLeast(0),
            (content.y0 - reach).coerceAtLeast(0),
            (content.x1 + reach).coerceAtMost(grid.width),
            (content.y1 + reach).coerceAtMost(grid.height),
        )
        val keys = padded.tileKeys(grid.width, grid.height)
        if (keys.isEmpty()) return
        process(grid, params, padded, keys, null, false, Geometry(content), threads)
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
    ) {
        val jobs = keys.map { key ->
            Callable { applyTile(grid, key, params, out, selection, alphaLock, geometry) }
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
        val src = readRect(grid, ox, oy, w, h)
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
            val tyi = y - tileY
            for (x in x0 until x1) {
                val i = tile.index(x - tileX, tyi)
                val j = ((y - oy) * w + (x - ox)) * 4
                var r = dst[j]
                var g = dst[j + 1]
                var b = dst[j + 2]
                var a = dst[j + 3]
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
                    selection == null -> 1f
                    maskTile != null -> (maskTile.data[tyi * TILE + (x - tileX)].toInt() and 0xFF) / 255f
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
        }
        tile.markDirty()
    }

    /** Copies the [ox],[oy]-based [w]×[h] rect of [grid] into a premultiplied float buffer. */
    private fun readRect(grid: TileGrid, ox: Int, oy: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h * 4)
        for (key in IntRect(ox, oy, ox + w, oy + h).tileKeys(grid.width, grid.height)) {
            val tile = grid.get(key) ?: continue
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
        is RadialLinesParams -> mix(src, radialLines(src, w, h, ox, oy, geometry, params), params.strength)
        is SpeedLinesParams -> mix(src, speedLines(src, w, h, ox, oy, geometry, params), params.strength)
        is HalftoneParams -> mix(src, halftone(src, w, h, ox, oy, params), params.strength)
        is LayerStrokeParams -> layerStroke(src, w, h, params)
        is DropShadowParams -> dropShadow(src, w, h, params)
        is LayerGlowParams -> layerGlow(src, w, h, params)
        is PanelGridParams -> panelGrid(src, w, h, params)
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
