package app.painter.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import app.painter.core.blend.BlendMode
import app.painter.core.doc.Document
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OpenGL ES 3 canvas: displays a [Document] with pan/zoom/rotate navigation and routes touch
 * input to [inputListener] in document pixel coordinates.
 *
 * All methods must be called on the main thread. The view never reads live tiles from the GL
 * thread: [invalidate] and [invalidateStroke] copy the affected tiles before returning.
 */
class CanvasView(context: Context) : GLSurfaceView(context) {

    /** Receives strokes and canvas gestures. */
    var inputListener: CanvasInputListener? = null

    private val _viewState = MutableStateFlow(ViewState(1f, 0f))

    /** Current zoom and rotation. */
    val viewState: StateFlow<ViewState> = _viewState.asStateFlow()

    private val transform = ViewTransform()
    private val pool = StagingPool()
    private val sync = DocumentSync(pool)
    private val renderer: CanvasRenderer
    private val router: GestureRouter
    private var fitPending = false

    private var previewLayerId: String? = null
    private var previewBuffer: TileGrid? = null
    private var previewSource: ((TileKey) -> Tile?)? = null
    private var previewErase = false
    private var previewOpacity = 1f
    private var previewBlend = BlendMode.NORMAL
    private var previewBlendStrength = 0f
    private val previewSent = HashSet<TileKey>()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        renderer = CanvasRenderer(pool, object : CanvasRenderer.Callbacks {
            override fun onContextLost() { post { resyncAll() } }
            override fun requestRender() = this@CanvasView.requestRender()
        })
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        router = GestureRouter(this, object : GestureRouter.Host {
            override val listener: CanvasInputListener? get() = inputListener
            override val transform: ViewTransform get() = this@CanvasView.transform
            override fun onTransformChanged() = publishTransform()
            override fun requestFit() = fitToScreen()
        })
    }

    /** Shows [doc]; resets the preview and fits it to the view. */
    fun setDocument(doc: Document) {
        router.reset()
        sync.reset(doc)
        clearPreviewState()
        val w = doc.width
        val h = doc.height
        renderer.post { it.resetDocument(w, h) }
        postBatch(sync.collect(null, null))
        fitToScreen()
    }

    /**
     * Re-reads tiles of layer [layerId] (null = all layers) at [keys] (null = all tiles) and
     * node properties/structure. Changed tiles are copied immediately.
     */
    fun invalidate(layerId: String?, keys: Collection<TileKey>?) {
        postBatch(sync.collect(layerId, keys))
    }

    /**
     * Displays [buffer] (premultiplied stroke buffer) over layer [layerId] at [opacity], or as
     * an eraser mask when [erase]. [blend] with [blendStrength] is the brush blend mode of the buffered
     * stroke (spec/tools/m2-brush.md §1.7), so the preview matches the composite on pen-up. Pass
     * `buffer = null` to remove the preview.
     *
     * [tileSource] returns the pixels of one uploaded buffer tile: the engine's processed preview
     * ([StrokeEngine.previewTile], §1.7 wet edges and adhesion), so the rim and the build-up show live. It is
     * called on the main thread for every tile that is read, like the raw path; null uploads [buffer] as is.
     */
    fun setStrokePreview(
        layerId: String,
        buffer: TileGrid?,
        erase: Boolean,
        opacity: Float,
        blend: BlendMode = BlendMode.NORMAL,
        blendStrength: Float = 0f,
        tileSource: ((TileKey) -> Tile?)? = null,
    ) {
        if (buffer == null) {
            if (previewBuffer != null) {
                val dirty = keysToLongs(previewSent)
                clearPreviewState()
                renderer.post { it.applyPreview(null, false, 1f, true, emptyList(), dirty) }
                requestRender()
            }
            return
        }
        val replace = buffer !== previewBuffer || layerId != previewLayerId || tileSource !== previewSource
        previewLayerId = layerId
        previewErase = erase
        previewOpacity = opacity
        previewBlend = blend
        previewBlendStrength = blendStrength
        previewSource = tileSource
        val uploads = ArrayList<TileUpload>()
        val dirty: LongArray
        if (replace) {
            val old = keysToLongs(previewSent)
            previewSent.clear()
            previewBuffer = buffer
            for ((k, t) in buffer.entries()) {
                if (!buffer.isInBounds(k.tx, k.ty)) continue
                uploads.add(TileUpload(layerId, k.tx, k.ty, sync.copyTile(tileSource?.invoke(k) ?: t)))
                previewSent.add(k)
            }
            dirty = old + keysToLongs(previewSent)
        } else {
            dirty = keysToLongs(previewSent)
        }
        renderer.post { it.applyPreview(layerId, erase, opacity, replace, uploads, dirty, blend, blendStrength) }
        requestRender()
    }

    /**
     * Re-reads stroke-buffer tiles at [keys] after the stroke engine changed them. With a [previewSource] the
     * processed tiles are uploaded (§1.7 wet edges/adhesion) together with the eight neighbours of every key,
     * because the wet edge blur of a tile reads the buffer ring around it.
     */
    fun invalidateStroke(keys: Collection<TileKey>) {
        val buffer = previewBuffer ?: return
        val id = previewLayerId ?: return
        val source = previewSource
        val pending = if (source == null) keys else withNeighbours(buffer, keys)
        val uploads = ArrayList<TileUpload>(pending.size)
        val dirty = LongArray(pending.size)
        var n = 0
        for (k in pending) {
            if (!buffer.isInBounds(k.tx, k.ty)) continue
            val t = if (source == null) buffer.get(k) else source.invoke(k)
            if (t != null) {
                uploads.add(TileUpload(id, k.tx, k.ty, sync.copyTile(t)))
                previewSent.add(k)
            } else if (previewSent.remove(k)) {
                uploads.add(TileUpload(id, k.tx, k.ty, null))
            }
            dirty[n++] = k.packed
        }
        val d = if (n == dirty.size) dirty else dirty.copyOf(n)
        renderer.post { it.updatePreview(uploads, d) }
        requestRender()
    }

    /** Fits the document into the view (rotation reset to 0). */
    fun fitToScreen() {
        val d = sync.doc ?: return
        if (width == 0 || height == 0) {
            fitPending = true
            return
        }
        fitPending = false
        transform.fit(d.width, d.height, width, height)
        publishTransform()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = router.onTouchEvent(event)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (fitPending || oldw == 0 || oldh == 0) {
            fitToScreen()
        } else {
            transform.panBy((w - oldw) / 2f, (h - oldh) / 2f)
            publishTransform()
        }
    }

    private fun publishTransform() {
        renderer.setView(transform)
        _viewState.value = transform.state
        requestRender()
    }

    private fun postBatch(b: RenderBatch?) {
        if (b == null) return
        renderer.post { it.applyBatch(b) }
        requestRender()
    }

    private fun resyncAll() {
        val d = sync.doc ?: return
        sync.forgetSent()
        renderer.post { it.resetDocument(d.width, d.height) }
        postBatch(sync.collect(null, null))
        val id = previewLayerId
        val buf = previewBuffer
        if (id != null && buf != null) {
            previewBuffer = null
            setStrokePreview(id, buf, previewErase, previewOpacity, previewBlend, previewBlendStrength, previewSource)
        }
        renderer.setView(transform)
        requestRender()
    }

    private fun clearPreviewState() {
        previewLayerId = null
        previewBuffer = null
        previewSource = null
        previewSent.clear()
    }

    private fun keysToLongs(keys: Collection<TileKey>): LongArray {
        val out = LongArray(keys.size)
        var i = 0
        for (k in keys) out[i++] = k.packed
        return out
    }

    /**
     * [keys] plus the neighbours of every key that exist in [buffer]: the wet edge blur (§1.7) of a tile reads the
     * ring of buffer tiles around it, so a changed tile also changes the processed preview of its eight neighbours.
     */
    private fun withNeighbours(buffer: TileGrid, keys: Collection<TileKey>): Collection<TileKey> {
        val out = LinkedHashSet<TileKey>(keys.size * 4)
        for (k in keys) {
            out.add(k)
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (buffer.get(k.tx + dx, k.ty + dy) != null) out.add(TileKey(k.tx + dx, k.ty + dy))
                }
            }
        }
        return out
    }
}
