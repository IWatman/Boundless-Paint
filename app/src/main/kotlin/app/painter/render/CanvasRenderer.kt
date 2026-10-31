package app.painter.render

import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import app.painter.core.blend.BlendMode
import app.painter.core.raster.Tile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * GL-thread side of [CanvasView]. Keeps per-layer tile textures, composites the layer stack
 * tile-by-tile into a bounded LRU cache of composite tiles (only visible ones), and draws them with the
 * view transform. Zoomed out it draws a single mipmapped overview instead (debt 6).
 * All public methods except [post]/[setView] must be called on the GL thread.
 */
internal class CanvasRenderer(
    private val pool: StagingPool,
    private val callbacks: Callbacks,
) : GLSurfaceView.Renderer {

    interface Callbacks {
        /** A new EGL context replaced a previous one; every texture must be re-sent. */
        fun onContextLost()
        fun requestRender()
    }

    private class Target(val tex: Int, val fbo: Int)

    /** Mask of one node for one tile: R8 texture (0 = use [default]), default value 0..1 and density. */
    private class MaskRef(val tex: Int, val default: Float, val density: Float)

    private class GNode(
        val snap: SnapNode,
        val tex: IntArray?,
        /** LUT texture of an adjustment node; 0 for every other kind. */
        val lut: Int,
        val children: Array<GNode>,
    )

    /** LUT of one adjustment node: the uploaded contents and their texture. */
    private class LutRef(val lut: FloatArray, val tex: Int)

    // ---- cross-thread ----
    private val opsLock = Any()
    private val ops = ArrayList<(CanvasRenderer) -> Unit>()
    private val opsDrain = ArrayList<(CanvasRenderer) -> Unit>()
    private val viewLock = Any()
    private val pendingView = FloatArray(4)
    private var viewDirty = false

    // ---- GL state ----
    private var contextCount = 0
    private var viewW = 1
    private var viewH = 1
    private val view = ViewTransform()
    private val mvp = FloatArray(16)
    private val bounds = FloatArray(4)

    /** Studio backdrop around the canvas: light #D0D0D0 when true, dark #2B2B2B otherwise (spec T1). */
    var lightBackground = false

    private var blendProg = 0
    private var uDst = 0; private var uSrc = 0; private var uBuf = 0; private var uClip = 0
    private var uMode = 0; private var uOpacity = 0; private var uPreview = 0
    private var uPreviewOpacity = 0; private var uStrokeMode = 0; private var uStrokeStrength = 0
    private var uAlphaLock = 0; private var uUseClip = 0
    private var uMask = 0; private var uClipMask = 0
    private var uUseMask = 0; private var uMaskDefault = 0; private var uMaskDensity = 0
    private var uUseClipMask = 0; private var uClipMaskDefault = 0; private var uClipMaskDensity = 0
    private var uGamma = 0
    private var displayProg = 0
    private val adjustShader = AdjustShader(::link)
    private var uMvp = 0; private var uOrigin = 0; private var uSize = 0; private var uTex = 0
    private var uUvScale = 0
    private var vao = 0
    private var samplerNearest = 0
    private var samplerLinear = 0
    private var samplerMip = 0
    private var emptyTex = 0
    private var targetInternal = GL.GL_RGBA16F
    private val freeTargets = ArrayList<Target>()
    private val ints = IntArray(1)
    private var upload: ShortBuffer? = null
    private var maskUpload: ByteBuffer? = null

    // ---- document state ----
    private var docW = 0
    private var docH = 0
    private var tilesX = 0
    private var tilesY = 0
    private var scene: SceneSnapshot? = null
    private var root: GNode? = null
    private val layerTex = HashMap<String, IntArray>()
    private val maskTex = HashMap<String, IntArray>()
    private val adjustLut = HashMap<String, LutRef>()
    private val lutSeen = HashSet<String>()

    /**
     * Composite tiles on the GPU, least recently used first (debt 6). Only visible tiles are cached, and the
     * cache stays under [compositeLimit] tiles, so a large canvas no longer allocates a mipmapped texture per
     * document tile. Tiles are recomposited when they are panned back into view.
     */
    private val compositeCache = object : LinkedHashMap<Int, Target>(64, 0.75f, true) {}
    /** Cache limit from the memory budget; [compositeLimit] grows to the visible tile count when that is larger. */
    private var baseCompositeLimit = 64
    private var compositeLimit = 64

    /** Downscaled, mipmapped copy of the whole document, drawn at zoom < 1 instead of every tile. */
    private var overviewTex = 0
    private var overviewFbo = 0
    private var overviewW = 0
    private var overviewH = 0
    private var overviewScale = 1f
    private var overviewValid = false
    /** Rebuild every overview tile on the next draw (scene change, first draw, new canvas size). */
    private var overviewFull = true
    /** Tiles that changed since the overview was last updated. */
    private val overviewTiles = LinkedHashSet<Int>()
    /** False after an update: the mip chain is regenerated only when a far zoom needs it. */
    private var overviewMipsValid = false
    private var lastMipGenMs = 0L

    private var dirty = BooleanArray(0)
    private var dirtyCount = 0

    private var previewLayerId: String? = null
    private var previewErase = false
    private var previewOpacity = 1f
    private var previewStrokeMode = 0
    private var previewStrokeStrength = 0f
    private var previewTex = IntArray(0)

    /** Last draw mode logged, so a zoom crossing 1.0 leaves one diagnostic line. */
    private var loggedOverview = false

    /** Queues [op] to run on the GL thread before the next frame. Thread-safe. */
    fun post(op: (CanvasRenderer) -> Unit) {
        synchronized(opsLock) { ops.add(op) }
    }

    /** Publishes the latest view transform. Thread-safe. */
    fun setView(t: ViewTransform) {
        synchronized(viewLock) {
            pendingView[0] = t.zoom; pendingView[1] = t.rotationDeg
            pendingView[2] = t.tx; pendingView[3] = t.ty
            viewDirty = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        contextCount++
        freeTargets.clear()
        layerTex.clear()
        maskTex.clear()
        adjustLut.clear()
        compositeCache.values.forEach(::deleteTarget)
        compositeCache.clear()
        // GL objects died with the context: sizes stay, contents do not.
        overviewTex = 0
        overviewFbo = 0
        overviewValid = false
        overviewFull = true
        overviewTiles.clear()
        previewTex.fill(0)
        root = null
        scene = null
        initGl()
        if (contextCount > 1) {
            synchronized(opsLock) { ops.clear() }
            callbacks.onContextLost()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width.coerceAtLeast(1)
        viewH = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(opsLock) {
            opsDrain.addAll(ops)
            ops.clear()
        }
        for (i in opsDrain.indices) opsDrain[i](this)
        opsDrain.clear()
        synchronized(viewLock) {
            if (viewDirty) {
                view.set(pendingView[0], pendingView[1], pendingView[2], pendingView[3])
                viewDirty = false
            }
        }

        GL.glDisable(GL.GL_BLEND)
        GL.glDisable(GL.GL_DEPTH_TEST)
        GL.glDisable(GL.GL_SCISSOR_TEST)
        val r = root
        if (r == null || tilesX == 0) {
            clearScreen()
            return
        }

        view.visibleDocBounds(viewW, viewH, bounds)
        val tx0 = floor(bounds[0] / Tile.SIZE).toInt().coerceIn(0, tilesX - 1)
        val ty0 = floor(bounds[1] / Tile.SIZE).toInt().coerceIn(0, tilesY - 1)
        val tx1 = (ceil(bounds[2] / Tile.SIZE).toInt() - 1).coerceIn(0, tilesX - 1)
        val ty1 = (ceil(bounds[3] / Tile.SIZE).toInt() - 1).coerceIn(0, tilesY - 1)
        val visible = bounds[2] > 0 && bounds[3] > 0 && bounds[0] < docW && bounds[1] < docH

        // Zoomed out the whole canvas is on screen: one mipmapped overview costs a fraction of the per-tile
        // textures and keeps the tile cache for the zoomed-in case (debt 6).
        val useOverview = visible && view.zoom < 1f
        if (useOverview != loggedOverview) {
            loggedOverview = useOverview
            Log.d(
                TAG,
                "draw mode=${if (useOverview) "overview" else "tiles"} zoom=${view.zoom} doc=${docW}x$docH " +
                    "overview=${overviewW}x$overviewH scale=$overviewScale uvWindow=${viewW}x$viewH",
            )
        }
        // Compose before clearing: every pass below leaves a tile-sized FBO and viewport behind, and
        // clearScreen puts the framebuffer and the viewport back for the display pass.
        if (visible) {
            if (useOverview) updateOverview(r) else compositeVisibleTiles(r, tx0, ty0, tx1, ty1)
        }

        clearScreen()
        if (!visible) return
        if (useOverview) drawOverview() else drawTiles(tx0, ty0, tx1, ty1)
    }

    /** Composites the visible tiles that are missing from the cache or changed since the last frame. */
    private fun compositeVisibleTiles(r: GNode, tx0: Int, ty0: Int, tx1: Int, ty1: Int) {
        // Never thrash on the tiles that are on screen: allow the visible set even when it is over the budget.
        val visible = (tx1 - tx0 + 1) * (ty1 - ty0 + 1)
        compositeLimit = maxOf(baseCompositeLimit, visible + 8).coerceAtMost(dirty.size)
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val i = ty * tilesX + tx
            if (compositeCache[i] == null || dirty[i]) tileTarget(i, r)
        }
    }

    private fun clearScreen() {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, viewW, viewH)
        val bg = if (lightBackground) BG_LIGHT else BG_DARK
        GL.glClearColor(bg, bg, bg, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
    }

    private fun drawTiles(tx0: Int, ty0: Int, tx1: Int, ty1: Int) {
        val zoom = view.zoom
        val sampler = if (zoom >= 4f) samplerNearest else samplerLinear
        GL.glViewport(0, 0, viewW, viewH)
        view.toNdcMatrix(viewW, viewH, mvp)
        GL.glUseProgram(displayProg)
        GL.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GL.glUniform1i(uTex, 0)
        GL.glUniform2f(uUvScale, 1f / Tile.SIZE, 1f / Tile.SIZE)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindSampler(0, sampler)
        GL.glBindVertexArray(vao)
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val t = compositeCache[ty * tilesX + tx] ?: continue
            GL.glBindTexture(GL.GL_TEXTURE_2D, t.tex)
            val ox = tx * Tile.SIZE
            val oy = ty * Tile.SIZE
            GL.glUniform2f(uOrigin, ox.toFloat(), oy.toFloat())
            GL.glUniform2f(uSize, minOf(Tile.SIZE, docW - ox).toFloat(), minOf(Tile.SIZE, docH - oy).toFloat())
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        }
        GL.glBindSampler(0, 0)
        GL.glBindVertexArray(0)
    }

    /** Cached composite of tile [i], composited on the spot when it is missing or dirty. */
    private fun tileTarget(i: Int, r: GNode): Target {
        val cached = compositeCache[i]
        if (cached != null && !dirty[i]) return cached
        val target = cached ?: createTarget(1).also {
            compositeCache[i] = it
            trimCompositeCache()
        }
        compositeTileInto(target, r, i % tilesX, i / tilesX)
        return target
    }

    /** Evicts the least recently used composite tiles until the cache fits its budget. */
    private fun trimCompositeCache() {
        while (compositeCache.size > compositeLimit) {
            val it = compositeCache.entries.iterator()
            if (!it.hasNext()) return
            val eldest = it.next()
            it.remove()
            deleteTarget(eldest.value)
        }
    }

    // ---------------------------------------------------------------- overview (zoom < 1)

    /**
     * Creates the overview texture: the document at up to [OVERVIEW_MAX] on the long side, RGBA16F like the
     * tile composites, with a full mip chain. Mips exist only here, never per tile (debt 6).
     */
    private fun ensureOverview() {
        if (overviewTex != 0) return
        val maxSide = maxOf(docW, docH)
        val scale = if (maxSide <= OVERVIEW_MAX) 1f else OVERVIEW_MAX.toFloat() / maxSide
        val w = (docW * scale).roundToInt().coerceAtLeast(1)
        val h = (docH * scale).roundToInt().coerceAtLeast(1)
        overviewW = w
        overviewH = h
        overviewScale = w.toFloat() / docW
        GL.glGenTextures(1, ints, 0)
        overviewTex = ints[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, overviewTex)
        // Mutable storage with level 0 only: glGenerateMipmap allocates the chain the first time it is needed.
        GL.glTexImage2D(
            GL.GL_TEXTURE_2D, 0, targetInternal, w, h, 0,
            GL.GL_RGBA, if (targetInternal == GL.GL_RGBA16F) GL.GL_HALF_FLOAT else GL.GL_UNSIGNED_BYTE, null,
        )
        setNearest()
        GL.glGenFramebuffers(1, ints, 0)
        overviewFbo = ints[0]
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, overviewFbo)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, overviewTex, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        overviewValid = false
        overviewFull = true
        overviewMipsValid = false
        overviewTiles.clear()
    }

    private fun deleteOverview() {
        if (overviewFbo != 0) {
            ints[0] = overviewFbo
            GL.glDeleteFramebuffers(1, ints, 0)
            overviewFbo = 0
        }
        if (overviewTex != 0) {
            deleteTexture(overviewTex)
            overviewTex = 0
        }
        overviewW = 0
        overviewH = 0
    }

    /** Draws the whole document from the overview (zoom < 1): mips only when the canvas is really small on screen. */
    private fun drawOverview() {
        if (overviewTex == 0) return
        // The overview update above composited tiles and left the viewport at 256x256.
        GL.glViewport(0, 0, viewW, viewH)
        val wantMips = view.zoom < MIP_ZOOM
        if (wantMips && !overviewMipsValid && mipBudgetAllows()) {
            GL.glBindTexture(GL.GL_TEXTURE_2D, overviewTex)
            GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
            overviewMipsValid = true
            lastMipGenMs = SystemClock.uptimeMillis()
        }
        view.toNdcMatrix(viewW, viewH, mvp)
        GL.glUseProgram(displayProg)
        GL.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GL.glUniform1i(uTex, 0)
        GL.glUniform2f(uOrigin, 0f, 0f)
        GL.glUniform2f(uSize, docW.toFloat(), docH.toFloat())
        GL.glUniform2f(uUvScale, 1f / overviewW, 1f / overviewH)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, overviewTex)
        GL.glBindSampler(0, if (wantMips) samplerMip else samplerLinear)
        GL.glBindVertexArray(vao)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        GL.glBindSampler(0, 0)
        GL.glBindVertexArray(0)
    }

    /** Regenerating the mip chain is throttled: a stroke redraws the overview many times per second. */
    private fun mipBudgetAllows(): Boolean =
        SystemClock.uptimeMillis() - lastMipGenMs >= MIP_MIN_INTERVAL_MS
    /** Brings the overview up to date: a full rebuild, or only the tiles changed since the last frame. */
    private fun updateOverview(r: GNode) {
        ensureOverview()
        if (overviewValid && !overviewFull && overviewTiles.isEmpty()) return
        val full = overviewFull || !overviewValid
        // The tile cache is not drawn while the overview is on screen, and its entries would be stale here.
        compositeCache.values.forEach(::deleteTarget)
        compositeCache.clear()
        val scratch = acquire()
        try {
            if (full) {
                for (i in 0 until tilesX * tilesY) {
                    compositeTileInto(scratch, r, i % tilesX, i / tilesX, clearDirty = false)
                    blitIntoOverview(scratch, i % tilesX, i / tilesX)
                }
            } else {
                for (i in overviewTiles) {
                    compositeTileInto(scratch, r, i % tilesX, i / tilesX, clearDirty = false)
                    blitIntoOverview(scratch, i % tilesX, i / tilesX)
                }
            }
        } finally {
            release(scratch)
        }
        overviewMipsValid = false
        // What was composed is on screen; the tile path recomposites a tile when it is drawn again.
        if (full) {
            dirty.fill(false)
            dirtyCount = 0
        } else {
            for (i in overviewTiles) if (dirty[i]) { dirty[i] = false; dirtyCount-- }
        }
        overviewTiles.clear()
        overviewFull = false
        overviewValid = true
    }

    /** Copies one 256ВІ scratch tile into its place in the scaled overview. */
    private fun blitIntoOverview(scratch: Target, tx: Int, ty: Int) {
        GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER, scratch.fbo)
        GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER, overviewFbo)
        val s = overviewScale
        val x0 = (tx * Tile.SIZE * s).roundToInt().coerceIn(0, overviewW)
        val y0 = (ty * Tile.SIZE * s).roundToInt().coerceIn(0, overviewH)
        val x1 = ((tx + 1) * Tile.SIZE * s).roundToInt().coerceIn(0, overviewW)
        val y1 = ((ty + 1) * Tile.SIZE * s).roundToInt().coerceIn(0, overviewH)
        if (x1 > x0 && y1 > y0) {
            GL.glBlitFramebuffer(
                0, 0, Tile.SIZE, Tile.SIZE, x0, y0, x1, y1,
                GL.GL_COLOR_BUFFER_BIT, GL.GL_LINEAR,
            )
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
    }

    // ---------------------------------------------------------------- ops (GL thread)

    /** Re-initializes all document state for a [w]Г—[h] document (or none if 0). */
    fun resetDocument(w: Int, h: Int) {
        deleteAllLayerTextures()
        deleteAllMaskTextures()
        deleteAllLuts()
        compositeCache.values.forEach(::deleteTarget)
        compositeCache.clear()
        deleteOverview()
        clearPreviewTextures()
        previewLayerId = null
        docW = w; docH = h
        tilesX = (w + Tile.MASK) shr Tile.SHIFT
        tilesY = (h + Tile.MASK) shr Tile.SHIFT
        val n = tilesX * tilesY
        // A tile composite is 256ВІ RGBA16F; keep the cache within the budget and never above the tile count.
        baseCompositeLimit = (COMPOSITE_BUDGET / TILE_BYTES).toInt().coerceIn(8, maxOf(8, n))
        compositeLimit = baseCompositeLimit
        dirty = BooleanArray(n)
        dirtyCount = 0
        overviewFull = true
        overviewTiles.clear()
        previewTex = IntArray(n)
        scene = null
        root = null
    }

    /** Applies a batch produced by [DocumentSync]. */
    fun applyBatch(b: RenderBatch) {
        for (id in b.removedLayers) {
            layerTex.remove(id)?.let(::deleteTextures)
        }
        for (id in b.removedMasks) {
            maskTex.remove(id)?.let(::deleteTextures)
        }
        for (u in b.uploads) {
            val arr = layerTex.getOrPut(u.layerId) { IntArray(tilesX * tilesY) }
            uploadTile(arr, u)
        }
        for (u in b.maskUploads) {
            if (u.tx !in 0 until tilesX || u.ty !in 0 until tilesY) continue
            val arr = maskTex.getOrPut(u.nodeId) { IntArray(tilesX * tilesY) }
            uploadMaskTile(arr, u)
        }
        if (b.scene != null) {
            scene = b.scene
            lutSeen.clear()
            root = resolve(b.scene.root)
            pruneLuts()
        }
        if (b.dirtyAll) markAllDirty() else for (k in b.dirtyKeys) markDirty(k)
    }

    /**
     * Sets the stroke preview. Pass [layerId] null to clear it; [replace] drops all previously
     * uploaded buffer tiles first. [blend] and [blendStrength] are the brush blend mode of the buffered
     * stroke (spec/tools/m2-brush.md В§1.7): `out = lerp(source-over, blend(mode), strength)`;
     * [BlendMode.NORMAL] or a zero strength keeps the plain source-over preview.
     */
    fun applyPreview(
        layerId: String?,
        erase: Boolean,
        opacity: Float,
        replace: Boolean,
        uploads: List<TileUpload>,
        dirtyKeys: LongArray,
        blend: BlendMode = BlendMode.NORMAL,
        blendStrength: Float = 0f,
    ) {
        if (replace || layerId == null || layerId != previewLayerId) {
            for (i in previewTex.indices) if (previewTex[i] != 0) markDirtyIndex(i)
            clearPreviewTextures()
        }
        previewLayerId = layerId
        previewErase = erase
        previewOpacity = opacity
        previewStrokeMode = modeIndex(blend)
        previewStrokeStrength = blendStrength.coerceIn(0f, 1f)
        updatePreview(uploads, dirtyKeys)
    }

    /** Uploads changed stroke-buffer tiles. */
    fun updatePreview(uploads: List<TileUpload>, dirtyKeys: LongArray) {
        for (u in uploads) uploadTile(previewTex, u)
        for (k in dirtyKeys) markDirty(k)
    }

    private fun uploadTile(arr: IntArray, u: TileUpload) {
        if (u.tx !in 0 until tilesX || u.ty !in 0 until tilesY) {
            u.data?.let(pool::release)
            return
        }
        val i = u.ty * tilesX + u.tx
        val data = u.data
        if (data == null) {
            if (arr[i] != 0) { deleteTexture(arr[i]); arr[i] = 0 }
            return
        }
        if (arr[i] == 0) arr[i] = createSampledTexture()
        val buf = upload ?: ByteBuffer.allocateDirect(StagingPool.TILE_FLOATS * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().also { upload = it }
        buf.clear()
        buf.put(data)
        buf.flip()
        pool.release(data)
        GL.glBindTexture(GL.GL_TEXTURE_2D, arr[i])
        GL.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, Tile.SIZE, Tile.SIZE, GL.GL_RGBA, GL.GL_HALF_FLOAT, buf)
    }

    private fun uploadMaskTile(arr: IntArray, u: MaskUpload) {
        val i = u.ty * tilesX + u.tx
        val data = u.data
        if (data == null) {
            if (arr[i] != 0) { deleteTexture(arr[i]); arr[i] = 0 }
            return
        }
        if (arr[i] == 0) arr[i] = createMaskTexture()
        val buf = maskUpload ?: ByteBuffer.allocateDirect(MASK_BYTES)
            .order(ByteOrder.nativeOrder()).also { maskUpload = it }
        buf.clear()
        buf.put(data)
        buf.flip()
        GL.glBindTexture(GL.GL_TEXTURE_2D, arr[i])
        GL.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, Tile.SIZE, Tile.SIZE, GL.GL_RED, GL.GL_UNSIGNED_BYTE, buf)
    }

    private fun resolve(s: SnapNode): GNode = GNode(
        snap = s,
        tex = if (s.isGroup || s.adjust != null) null else layerTex.getOrPut(s.id) { IntArray(tilesX * tilesY) },
        lut = s.adjust?.let { lutOf(s.id, it) } ?: 0,
        children = Array(s.children.size) { resolve(s.children[it]) },
    )

    /** LUT texture of adjustment node [id], re-uploaded only when [a]'s table changed. */
    private fun lutOf(id: String, a: SnapAdjust): Int {
        val lut = a.lut ?: return 0
        lutSeen.add(id)
        val ref = adjustLut[id]
        if (ref == null) return adjustShader.createLut(lut).also { adjustLut[id] = LutRef(lut, it) }
        if (!ref.lut.contentEquals(lut)) {
            adjustShader.uploadLut(ref.tex, lut)
            adjustLut[id] = LutRef(lut, ref.tex)
        }
        return ref.tex
    }

    /** Frees the LUT textures of adjustment nodes that are gone from the resolved scene. */
    private fun pruneLuts() {
        val it = adjustLut.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key in lutSeen) continue
            adjustShader.deleteLut(e.value.tex)
            it.remove()
        }
        lutSeen.clear()
    }

    private fun markAllDirty() {
        dirty.fill(true)
        dirtyCount = dirty.size
        overviewFull = true
    }

    private fun markDirty(packed: Long) {
        val tx = (packed shr 32).toInt()
        val ty = packed.toInt()
        if (tx in 0 until tilesX && ty in 0 until tilesY) markDirtyIndex(ty * tilesX + tx)
    }

    private fun markDirtyIndex(i: Int) {
        if (!dirty[i]) { dirty[i] = true; dirtyCount++ }
        // The overview follows the document too; too many separate changes are cheaper as one rebuild.
        if (overviewTiles.size < OVERVIEW_INCREMENTAL_MAX) overviewTiles.add(i) else overviewFull = true
    }

    // ---------------------------------------------------------------- compositing

    private fun beginComposite() {
        GL.glViewport(0, 0, Tile.SIZE, Tile.SIZE)
        GL.glUseProgram(blendProg)
        GL.glBindVertexArray(vao)
    }

    /**
     * Composites tile ([tx], [ty]) of [r] into [dst]. Clears the tile's dirty flag unless [clearDirty] is false:
     * the overview pass composes into a scratch target and leaves the flag for the tile cache.
     */
    private fun compositeTileInto(dst: Target, r: GNode, tx: Int, ty: Int, clearDirty: Boolean = true) {
        val i = ty * tilesX + tx
        if (clearDirty && dirty[i]) { dirty[i] = false; dirtyCount-- }
        val sc = scene ?: return
        beginComposite()
        var cur = acquire()
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, cur.fbo)
        if (sc.paperVisible) GL.glClearColor(sc.paperR, sc.paperG, sc.paperB, sc.paperA)
        else GL.glClearColor(0f, 0f, 0f, 0f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        cur = renderChildren(r, cur, i)
        blit(cur, dst)
        release(cur)
    }

    /** Composites [g]'s children onto [backdrop] for tile index [i]; returns the result target. */
    private fun renderChildren(g: GNode, backdrop: Target, i: Int): Target {
        var dst = backdrop
        var baseTex = 0
        var baseMask: MaskRef? = null
        var baseVisible = true
        var baseOwned: Target? = null
        for ((index, c) in g.children.withIndex()) {
            val s = c.snap
            if (!s.clipped) {
                baseOwned?.let(::release)
                baseOwned = null
                baseTex = 0
                baseMask = null
                baseVisible = s.visible
            }
            if (!s.visible) continue
            if (s.clipped && (!baseVisible || baseTex == 0)) continue
            val clipTex = if (s.clipped) baseTex else 0
            val clipMask = if (s.clipped) baseMask else null
            val mask = maskOf(s, i)
            if (s.isFilter && g.snap.id == "root") {
                // The filter cache already contains the paper and every sibling below it.
                GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, dst.fbo)
                GL.glClearColor(0f, 0f, 0f, 0f)
                GL.glClear(GL.GL_COLOR_BUFFER_BIT)
            }
            if (s.adjust != null) {
                // Acts on the accumulator of its parent and is never a clip base (as on the CPU).
                dst = adjustPass(dst, c, mask, clipTex, clipMask)
                continue
            }
            if (!s.isGroup) {
                val tex = c.tex!![i]
                if (!s.clipped) { baseTex = tex; baseMask = mask }
                val preview = if (s.id == previewLayerId && previewTex[i] != 0) {
                    if (previewErase) Shaders.PREVIEW_ERASE else Shaders.PREVIEW_PAINT
                } else {
                    Shaders.PREVIEW_NONE
                }
                if (tex == 0 && preview != Shaders.PREVIEW_PAINT) continue
                dst = blendPass(dst, tex, previewTex[i], clipTex, modeIndex(s.mode), s.opacity, preview, s.alphaLock, mask, clipMask)
                continue
            }
            if (s.mode == BlendMode.PASSTHROUGH) {
                // As on the CPU: a passthrough group used as clip base clips by its isolated render.
                val isBase = !s.clipped && g.children.getOrNull(index + 1)?.snap?.clipped == true
                if (isBase) {
                    val iso = renderIsolated(c, i)
                    baseOwned = iso
                    baseTex = iso.tex
                    baseMask = mask
                }
                if (s.opacity >= 1f && mask == null && !s.clipped) {
                    dst = renderChildren(c, dst, i)
                } else {
                    val copy = acquire()
                    blit(dst, copy)
                    val res = renderChildren(c, copy, i)
                    dst = blendPass(dst, res.tex, 0, clipTex, Shaders.MODE_LERP, s.opacity, Shaders.PREVIEW_NONE, false, mask, clipMask)
                    release(res)
                }
                continue
            }
            val grp = renderIsolated(c, i)
            dst = blendPass(dst, grp.tex, 0, clipTex, modeIndex(s.mode), s.opacity, Shaders.PREVIEW_NONE, false, mask, clipMask)
            if (!s.clipped) {
                baseOwned = grp
                baseTex = grp.tex
                baseMask = mask
            } else {
                release(grp)
            }
        }
        baseOwned?.let(::release)
        return dst
    }

    /** [c]'s children rendered onto transparent for tile [i]. */
    private fun renderIsolated(c: GNode, i: Int): Target {
        val grp = acquire()
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, grp.fbo)
        GL.glClearColor(0f, 0f, 0f, 0f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        return renderChildren(c, grp, i)
    }

    /** Effective mask of [s] at tile [i], or null when it has no enabled mask. */
    private fun maskOf(s: SnapNode, i: Int): MaskRef? {
        if (!s.maskPresent || !s.maskEnabled) return null
        val tex = maskTex[s.id]?.getOrNull(i) ?: 0
        return MaskRef(tex, s.maskDefault / 255f, s.maskDensity)
    }

    private fun modeIndex(m: BlendMode): Int = if (m == BlendMode.PASSTHROUGH) 0 else m.shaderIndex

    /** Runs adjustment node [c] over the accumulated tile [dst] (times its mask and clip base). */
    private fun adjustPass(dst: Target, c: GNode, mask: MaskRef?, clip: Int, clipMask: MaskRef?): Target {
        val s = c.snap
        val a = s.adjust ?: return dst
        val out = acquire()
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, out.fbo)
        GL.glViewport(0, 0, Tile.SIZE, Tile.SIZE)
        GL.glUseProgram(adjustShader.program)
        GL.glBindVertexArray(vao)
        adjustShader.draw(
            kind = a.kindIndex,
            uniforms = a.uniforms,
            useLut = a.lut != null,
            lutTex = if (c.lut != 0) c.lut else emptyTex,
            // 0 = absent (flags off in the shader); emptyTex here would read as a zero mask/clip.
            dst = dst.tex,
            mode = modeIndex(s.mode),
            opacity = s.opacity,
            maskTex = mask?.tex ?: 0,
            maskDefault = mask?.default ?: 1f,
            maskDensity = mask?.density ?: 1f,
            clipTex = clip,
            clipMaskTex = clipMask?.tex ?: 0,
            clipMaskDefault = clipMask?.default ?: 1f,
            clipMaskDensity = clipMask?.density ?: 1f,
            gamma = scene?.gamma == true,
        )
        release(dst)
        return out
    }

    private fun blendPass(
        dst: Target,
        src: Int,
        buf: Int,
        clip: Int,
        mode: Int,
        opacity: Float,
        preview: Int,
        alphaLock: Boolean,
        mask: MaskRef? = null,
        clipMask: MaskRef? = null,
    ): Target {
        val out = acquire()
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, out.fbo)
        GL.glViewport(0, 0, Tile.SIZE, Tile.SIZE)
        GL.glUseProgram(blendProg)
        GL.glBindVertexArray(vao)
        bindUnit(0, dst.tex)
        bindUnit(1, if (src != 0) src else emptyTex)
        bindUnit(2, if (buf != 0 && preview != Shaders.PREVIEW_NONE) buf else emptyTex)
        bindUnit(3, if (clip != 0) clip else emptyTex)
        bindUnit(4, mask?.tex?.takeIf { it != 0 } ?: emptyTex)
        bindUnit(5, clipMask?.tex?.takeIf { it != 0 } ?: emptyTex)
        GL.glUniform1i(uUseMask, if (mask != null && mask.tex != 0) 1 else 0)
        GL.glUniform1f(uMaskDefault, mask?.default ?: 1f)
        GL.glUniform1f(uMaskDensity, mask?.density ?: 1f)
        GL.glUniform1i(uUseClipMask, if (clipMask != null && clipMask.tex != 0) 1 else 0)
        GL.glUniform1f(uClipMaskDefault, clipMask?.default ?: 1f)
        GL.glUniform1f(uClipMaskDensity, clipMask?.density ?: 1f)
        GL.glUniform1i(uMode, mode)
        GL.glUniform1f(uOpacity, opacity)
        GL.glUniform1i(uPreview, preview)
        GL.glUniform1f(uPreviewOpacity, previewOpacity)
        GL.glUniform1i(uStrokeMode, previewStrokeMode)
        GL.glUniform1f(uStrokeStrength, previewStrokeStrength)
        GL.glUniform1i(uAlphaLock, if (alphaLock) 1 else 0)
        GL.glUniform1i(uUseClip, if (clip != 0) 1 else 0)
        // Document toggle "gamma-correct": the same linear-light blend as BlendMath's gamma path.
        GL.glUniform1i(uGamma, if (scene?.gamma == true) 1 else 0)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        release(dst)
        return out
    }

    private fun bindUnit(unit: Int, tex: Int) {
        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
    }

    private fun blit(src: Target, dst: Target) {
        GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER, src.fbo)
        GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER, dst.fbo)
        GL.glBlitFramebuffer(
            0, 0, Tile.SIZE, Tile.SIZE, 0, 0, Tile.SIZE, Tile.SIZE,
            GL.GL_COLOR_BUFFER_BIT, GL.GL_NEAREST,
        )
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
    }

    private fun acquire(): Target =
        if (freeTargets.isEmpty()) createTarget(1) else freeTargets.removeAt(freeTargets.size - 1)

    private fun release(t: Target) {
        freeTargets.add(t)
    }

    // ---------------------------------------------------------------- GL objects

    private fun initGl() {
        val version = GL.glGetString(GL.GL_VERSION) ?: ""
        val ext = GL.glGetString(GL.GL_EXTENSIONS) ?: ""
        val floatRt = version.contains("OpenGL ES 3.2") ||
            ext.contains("GL_EXT_color_buffer_half_float") ||
            ext.contains("GL_EXT_color_buffer_float")
        targetInternal = if (floatRt) GL.GL_RGBA16F else GL.GL_RGBA8
        if (floatRt) {
            val probe = createTarget(1)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, probe.fbo)
            val ok = GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
            deleteTarget(probe)
            if (!ok) {
                targetInternal = GL.GL_RGBA8
            }
        }
        Log.i(TAG, "GL: $version, composite format ${if (targetInternal == GL.GL_RGBA16F) "RGBA16F" else "RGBA8"}")

        blendProg = link(Shaders.QUAD_VS, Shaders.BLEND_FS)
        uDst = GL.glGetUniformLocation(blendProg, "u_dst")
        uSrc = GL.glGetUniformLocation(blendProg, "u_src")
        uBuf = GL.glGetUniformLocation(blendProg, "u_buf")
        uClip = GL.glGetUniformLocation(blendProg, "u_clip")
        uMode = GL.glGetUniformLocation(blendProg, "u_mode")
        uOpacity = GL.glGetUniformLocation(blendProg, "u_opacity")
        uPreview = GL.glGetUniformLocation(blendProg, "u_preview")
        uPreviewOpacity = GL.glGetUniformLocation(blendProg, "u_previewOpacity")
        uStrokeMode = GL.glGetUniformLocation(blendProg, "u_strokeMode")
        uStrokeStrength = GL.glGetUniformLocation(blendProg, "u_strokeStrength")
        uAlphaLock = GL.glGetUniformLocation(blendProg, "u_alphaLock")
        uUseClip = GL.glGetUniformLocation(blendProg, "u_useClip")
        uMask = GL.glGetUniformLocation(blendProg, "u_mask")
        uClipMask = GL.glGetUniformLocation(blendProg, "u_clipMask")
        uUseMask = GL.glGetUniformLocation(blendProg, "u_useMask")
        uMaskDefault = GL.glGetUniformLocation(blendProg, "u_maskDefault")
        uMaskDensity = GL.glGetUniformLocation(blendProg, "u_maskDensity")
        uUseClipMask = GL.glGetUniformLocation(blendProg, "u_useClipMask")
        uClipMaskDefault = GL.glGetUniformLocation(blendProg, "u_clipMaskDefault")
        uClipMaskDensity = GL.glGetUniformLocation(blendProg, "u_clipMaskDensity")
        uGamma = GL.glGetUniformLocation(blendProg, "u_gamma")
        GL.glUseProgram(blendProg)
        GL.glUniform1i(uDst, 0)
        GL.glUniform1i(uSrc, 1)
        GL.glUniform1i(uBuf, 2)
        GL.glUniform1i(uClip, 3)
        GL.glUniform1i(uMask, 4)
        GL.glUniform1i(uClipMask, 5)

        displayProg = link(Shaders.DISPLAY_VS, Shaders.DISPLAY_FS)
        uMvp = GL.glGetUniformLocation(displayProg, "u_mvp")
        uOrigin = GL.glGetUniformLocation(displayProg, "u_origin")
        uSize = GL.glGetUniformLocation(displayProg, "u_size")
        uTex = GL.glGetUniformLocation(displayProg, "u_tex")
        uUvScale = GL.glGetUniformLocation(displayProg, "u_uvScale")
        adjustShader.create()
        GL.glUseProgram(0)

        GL.glGenVertexArrays(1, ints, 0)
        vao = ints[0]
        GL.glGenBuffers(1, ints, 0)
        val vbo = ints[0]
        GL.glBindVertexArray(vao)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo)
        val quad = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val qb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        qb.put(quad).flip()
        GL.glBufferData(GL.GL_ARRAY_BUFFER, quad.size * 4, qb, GL.GL_STATIC_DRAW)
        GL.glEnableVertexAttribArray(0)
        GL.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 8, 0)
        GL.glBindVertexArray(0)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)

        samplerNearest = createSampler(GL.GL_NEAREST, GL.GL_NEAREST)
        samplerLinear = createSampler(GL.GL_LINEAR, GL.GL_LINEAR)
        samplerMip = createSampler(GL.GL_LINEAR_MIPMAP_LINEAR, GL.GL_LINEAR)

        GL.glGenTextures(1, ints, 0)
        emptyTex = ints[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, emptyTex)
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL.GL_RGBA8, Tile.SIZE, Tile.SIZE)
        val zero = ByteBuffer.allocateDirect(Tile.SIZE * Tile.SIZE * 4).order(ByteOrder.nativeOrder())
        GL.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, Tile.SIZE, Tile.SIZE, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, zero)
        setNearest()
        GL.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4)
    }

    private fun createSampler(min: Int, mag: Int): Int {
        GL.glGenSamplers(1, ints, 0)
        val s = ints[0]
        GL.glSamplerParameteri(s, GL.GL_TEXTURE_MIN_FILTER, min)
        GL.glSamplerParameteri(s, GL.GL_TEXTURE_MAG_FILTER, mag)
        GL.glSamplerParameteri(s, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glSamplerParameteri(s, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        return s
    }

    private fun setNearest() {
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
    }

    /** Layer/buffer tile texture: RGBA16F (always sampleable in ES 3.0), filled with GL_HALF_FLOAT data. */
    /** Mask tile texture: R8, nearest, filled with GL_UNSIGNED_BYTE coverage. */
    private fun createMaskTexture(): Int {
        GL.glGenTextures(1, ints, 0)
        val t = ints[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, t)
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL.GL_R8, Tile.SIZE, Tile.SIZE)
        setNearest()
        return t
    }

    private fun createSampledTexture(): Int {
        GL.glGenTextures(1, ints, 0)
        val t = ints[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, t)
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GL.GL_RGBA16F, Tile.SIZE, Tile.SIZE)
        setNearest()
        return t
    }

    private fun createTarget(levels: Int): Target {
        GL.glGenTextures(1, ints, 0)
        val tex = ints[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
        GL.glTexStorage2D(GL.GL_TEXTURE_2D, levels, targetInternal, Tile.SIZE, Tile.SIZE)
        setNearest()
        GL.glGenFramebuffers(1, ints, 0)
        val fbo = ints[0]
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, tex, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        return Target(tex, fbo)
    }

    private fun deleteTarget(t: Target) {
        ints[0] = t.fbo
        GL.glDeleteFramebuffers(1, ints, 0)
        deleteTexture(t.tex)
    }

    private fun deleteTexture(tex: Int) {
        ints[0] = tex
        GL.glDeleteTextures(1, ints, 0)
    }

    private fun deleteTextures(arr: IntArray) {
        for (i in arr.indices) if (arr[i] != 0) { deleteTexture(arr[i]); arr[i] = 0 }
    }

    private fun deleteAllLayerTextures() {
        for (arr in layerTex.values) deleteTextures(arr)
        layerTex.clear()
    }

    private fun deleteAllMaskTextures() {
        for (arr in maskTex.values) deleteTextures(arr)
        maskTex.clear()
    }

    private fun deleteAllLuts() {
        for (ref in adjustLut.values) adjustShader.deleteLut(ref.tex)
        adjustLut.clear()
        lutSeen.clear()
    }

    private fun clearPreviewTextures() = deleteTextures(previewTex)

    private fun link(vs: String, fs: String): Int {
        val v = compile(GL.GL_VERTEX_SHADER, vs)
        val f = compile(GL.GL_FRAGMENT_SHADER, fs)
        val p = GL.glCreateProgram()
        GL.glAttachShader(p, v)
        GL.glAttachShader(p, f)
        GL.glLinkProgram(p)
        GL.glGetProgramiv(p, GL.GL_LINK_STATUS, ints, 0)
        if (ints[0] == 0) {
            val log = GL.glGetProgramInfoLog(p)
            GL.glDeleteProgram(p)
            throw IllegalStateException("Program link failed: $log")
        }
        GL.glDeleteShader(v)
        GL.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GL.glCreateShader(type)
        GL.glShaderSource(s, src)
        GL.glCompileShader(s)
        GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ints, 0)
        if (ints[0] == 0) {
            val log = GL.glGetShaderInfoLog(s)
            GL.glDeleteShader(s)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return s
    }

    companion object {
        private const val TAG = "CanvasRenderer"
        /** Backdrop around the canvas: #2B2B2B (dark) and #D0D0D0 (light), spec/tools/m0-m1-tools.md T1. */
        private const val BG_DARK = 0x2B / 255f
        private const val BG_LIGHT = 0xD0 / 255f
        /** Bytes of one composite tile in RGBA16F: 256ВІ Г— 8. */
        private const val TILE_BYTES = Tile.SIZE * Tile.SIZE * 8L
        /** Composite tile cache budget (debt 6); the cache never holds more than the document's tile count. */
        private const val COMPOSITE_BUDGET = 32L * 1024 * 1024
        /** Longest side of the mipmapped overview drawn at zoom < 1: 1:1 for the common tablet canvases. */
        private const val OVERVIEW_MAX = 2560
        /** Up to this many changed tiles the overview is updated in place; more trigger a full rebuild. */
        private const val OVERVIEW_INCREMENTAL_MAX = 64
        /** Below this zoom the overview is sampled with mips (and the chain is regenerated after changes). */
        private const val MIP_ZOOM = 0.5f
        /** Shortest interval between two mip regenerations of the overview, ms. */
        private const val MIP_MIN_INTERVAL_MS = 200L
        private const val MASK_BYTES = Tile.SIZE * Tile.SIZE
    }
}
