package app.painter.render

import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
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

/**
 * GL-thread side of [CanvasView]. Keeps per-layer tile textures, composites the layer stack
 * tile-by-tile into per-tile composite textures (only dirty tiles), and draws them with the
 * view transform. All public methods except [post]/[setView] must be called on the GL thread.
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

    private var blendProg = 0
    private var uDst = 0; private var uSrc = 0; private var uBuf = 0; private var uClip = 0
    private var uMode = 0; private var uOpacity = 0; private var uPreview = 0
    private var uPreviewOpacity = 0; private var uStrokeMode = 0; private var uStrokeStrength = 0
    private var uAlphaLock = 0; private var uUseClip = 0
    private var uMask = 0; private var uClipMask = 0
    private var uUseMask = 0; private var uMaskDefault = 0; private var uMaskDensity = 0
    private var uUseClipMask = 0; private var uClipMaskDefault = 0; private var uClipMaskDensity = 0
    private var displayProg = 0
    private val adjustShader = AdjustShader(::link)
    private var uMvp = 0; private var uOrigin = 0; private var uSize = 0; private var uTex = 0
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
    private var composite: Array<Target?> = emptyArray()
    private var mipValid = BooleanArray(0)
    private var dirty = BooleanArray(0)
    private var dirtyCount = 0

    private var previewLayerId: String? = null
    private var previewErase = false
    private var previewOpacity = 1f
    private var previewStrokeMode = 0
    private var previewStrokeStrength = 0f
    private var previewTex = IntArray(0)

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
        composite = arrayOfNulls(composite.size)
        mipValid.fill(false)
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

        if (dirtyCount > 0) {
            beginComposite()
            if (visible) {
                for (ty in ty0..ty1) for (tx in tx0..tx1) {
                    val i = ty * tilesX + tx
                    if (dirty[i]) compositeTile(r, tx, ty)
                }
            }
            var budget = OFFSCREEN_TILES_PER_FRAME
            var i = 0
            while (dirtyCount > 0 && budget > 0 && i < dirty.size) {
                if (dirty[i]) { compositeTile(r, i % tilesX, i / tilesX); budget-- }
                i++
            }
            if (dirtyCount > 0) callbacks.requestRender()
        }

        clearScreen()
        if (!visible) return
        drawTiles(tx0, ty0, tx1, ty1)
    }

    private fun clearScreen() {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, viewW, viewH)
        GL.glClearColor(BG, BG, BG, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
    }

    private fun drawTiles(tx0: Int, ty0: Int, tx1: Int, ty1: Int) {
        val zoom = view.zoom
        val sampler = when {
            zoom >= 4f -> samplerNearest
            zoom < 1f -> samplerMip
            else -> samplerLinear
        }
        val needMips = zoom < 1f
        view.toNdcMatrix(viewW, viewH, mvp)
        GL.glUseProgram(displayProg)
        GL.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GL.glUniform1i(uTex, 0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindSampler(0, sampler)
        GL.glBindVertexArray(vao)
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val i = ty * tilesX + tx
            val t = composite[i] ?: continue
            GL.glBindTexture(GL.GL_TEXTURE_2D, t.tex)
            if (needMips && !mipValid[i]) {
                GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
                mipValid[i] = true
            }
            val ox = tx * Tile.SIZE
            val oy = ty * Tile.SIZE
            GL.glUniform2f(uOrigin, ox.toFloat(), oy.toFloat())
            GL.glUniform2f(uSize, minOf(Tile.SIZE, docW - ox).toFloat(), minOf(Tile.SIZE, docH - oy).toFloat())
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        }
        GL.glBindSampler(0, 0)
        GL.glBindVertexArray(0)
    }

    // ---------------------------------------------------------------- ops (GL thread)

    /** Re-initializes all document state for a [w]×[h] document (or none if 0). */
    fun resetDocument(w: Int, h: Int) {
        deleteAllLayerTextures()
        deleteAllMaskTextures()
        deleteAllLuts()
        for (t in composite) if (t != null) deleteTarget(t)
        clearPreviewTextures()
        previewLayerId = null
        docW = w; docH = h
        tilesX = (w + Tile.MASK) shr Tile.SHIFT
        tilesY = (h + Tile.MASK) shr Tile.SHIFT
        val n = tilesX * tilesY
        composite = arrayOfNulls(n)
        mipValid = BooleanArray(n)
        dirty = BooleanArray(n)
        dirtyCount = 0
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
     * stroke (spec/tools/m2-brush.md §1.7): `out = lerp(source-over, blend(mode), strength)`;
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
    }

    private fun markDirty(packed: Long) {
        val tx = (packed shr 32).toInt()
        val ty = packed.toInt()
        if (tx in 0 until tilesX && ty in 0 until tilesY) markDirtyIndex(ty * tilesX + tx)
    }

    private fun markDirtyIndex(i: Int) {
        if (!dirty[i]) { dirty[i] = true; dirtyCount++ }
    }

    // ---------------------------------------------------------------- compositing

    private fun beginComposite() {
        GL.glViewport(0, 0, Tile.SIZE, Tile.SIZE)
        GL.glUseProgram(blendProg)
        GL.glBindVertexArray(vao)
    }

    private fun compositeTile(r: GNode, tx: Int, ty: Int) {
        val i = ty * tilesX + tx
        dirty[i] = false
        dirtyCount--
        val sc = scene ?: return
        var cur = acquire()
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, cur.fbo)
        if (sc.paperVisible) GL.glClearColor(sc.paperR, sc.paperG, sc.paperB, sc.paperA)
        else GL.glClearColor(0f, 0f, 0f, 0f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        cur = renderChildren(r, cur, i)
        val dst = composite[i] ?: createTarget(COMPOSITE_LEVELS).also { composite[i] = it }
        blit(cur, dst)
        release(cur)
        mipValid[i] = false
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
        private const val BG = 0x2B / 255f
        private const val COMPOSITE_LEVELS = 9
        private const val MASK_BYTES = Tile.SIZE * Tile.SIZE
        private const val OFFSCREEN_TILES_PER_FRAME = 4
    }
}
