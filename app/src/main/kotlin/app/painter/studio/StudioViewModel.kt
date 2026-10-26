package app.painter.studio

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.painter.R
import app.painter.core.blend.BlendMode
import app.painter.core.blend.Compositor
import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.GroupNode
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.history.AddLayer
import app.painter.core.history.ClearLayer
import app.painter.core.history.Command
import app.painter.core.history.DeleteNode
import app.painter.core.history.DuplicateNode
import app.painter.core.history.History
import app.painter.core.history.MergeDown
import app.painter.core.history.MoveNode
import app.painter.core.history.SetNodeProps
import app.painter.core.history.TilePatchCommand
import app.painter.core.io.ProjectIO
import app.painter.core.io.ProjectStore
import app.painter.core.pick.Eyedropper
import app.painter.core.pick.PickSource
import app.painter.core.stroke.Brush
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.BrushResources
import app.painter.core.stroke.HashRng
import app.painter.core.stroke.InputPoint
import app.painter.core.stroke.StrokeCommit
import app.painter.core.stroke.StrokeContext
import app.painter.core.stroke.StrokeEngine
import app.painter.core.stroke.StrokeMode
import app.painter.core.stroke.StrokeTarget
import app.painter.render.CanvasInputListener
import app.painter.render.CanvasView
import app.painter.ui.home.ProjectCardUi
import app.painter.ui.studio.BrushModeUi
import app.painter.ui.studio.HudUi
import app.painter.ui.studio.LayerUi
import app.painter.ui.studio.StudioActions
import app.painter.ui.studio.StudioUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Owns the open document and wires UI actions, the canvas view and the core engines. */
class StudioViewModel(app: Application) : AndroidViewModel(app), StudioActions, CanvasInputListener {

    private val store = ProjectStore(app.filesDir.resolve("projects").apply { mkdirs() })
    private val ioLock = Mutex()

    private var doc: Document = Document.create(1, 1)
    private val history = History()
    private var canvas: CanvasView? = null
    private var stroke: StrokeEngine? = null
    private var dirtySinceSave = false
    private var autosaveJob: Job? = null
    private var strokeCounter = 0L

    private var paintParams = BrushParams()
    private var eraseParams = BrushParams(size = 40f)

    private val _state = MutableStateFlow(StudioUiState(palette = DefaultPalette.colors, layerPanelOpen = false))
    val state: StateFlow<StudioUiState> = _state

    private val _projects = MutableStateFlow<List<ProjectCardUi>>(emptyList())
    val projects: StateFlow<List<ProjectCardUi>> = _projects

    /** Set by the UI when the user picks Export; the activity launches the system file picker. */
    val exportRequest = MutableStateFlow(false)

    /** Set by [openHome]; the activity navigates back to Home and resets it. */
    val homeRequest = MutableStateFlow(false)

    init {
        history.onChanged = { markDirty(); refreshLayers() }
        syncBrushState()
        viewModelScope.launch(Dispatchers.IO) { ioLock.withLock { store.recoverInterrupted() } }
        refreshProjects()
    }

    // ---- projects ----

    fun refreshProjects() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = ioLock.withLock { store.list() }
            _projects.value = list.map { info ->
                val thumb = info.thumbFile?.takeIf { it.exists() }?.let {
                    android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap()
                }
                ProjectCardUi(info.id, info.name, info.modified, thumb)
            }
        }
    }

    fun newDocument(name: String, width: Int, height: Int) {
        val d = Document.create(width, height, name.ifBlank { "Untitled" })
        d.root.children.firstOrNull()?.name = defaultLayerName(1)
        setDocument(d)
        _state.update { it.copy(layerPanelOpen = false) }
        dirtySinceSave = true
        save()
    }

    suspend fun openDocument(id: String) {
        val loaded = withContext(Dispatchers.IO) { ioLock.withLock { ProjectIO.load(store.dirFor(id)) } }
        setDocument(loaded)
    }

    private fun setDocument(d: Document) {
        save(refreshList = false) // flush pending changes of the previous document
        doc = d
        history.clear()
        autosaveJob?.cancel()
        autosaveJob = null
        dirtySinceSave = false
        stroke = null
        canvas?.setDocument(doc)
        _state.update { it.copy(paperColor = d.paper.color, paperVisible = d.paper.visible) }
        refreshLayers()
    }

    fun renameProject(id: String, name: String) = storeOp { store.rename(id, name) }
    fun duplicateProject(id: String) = storeOp { store.duplicate(id) }
    fun deleteProject(id: String) = storeOp { store.delete(id) }

    private fun storeOp(op: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { ioLock.withLock { op() }; refreshProjects() }
    }

    // ---- canvas ----

    fun attachCanvas(view: CanvasView) {
        canvas = view
        view.inputListener = this
        view.setDocument(doc)
        viewModelScope.launch {
            view.viewState.collect { vs -> _state.update { it.copy(viewZoom = vs.zoom) } }
        }
    }

    fun detachCanvas(view: CanvasView) {
        if (canvas === view) canvas = null
    }

    override fun onStrokeBegin(p: InputPoint) {
        val layer = doc.activeRaster ?: return
        if (!layer.visible || layer.locked) return
        val s = _state.value
        val mode = if (s.mode == BrushModeUi.ERASE) StrokeMode.ERASE else StrokeMode.PAINT
        val brush = Brush.of(currentParams(), STUDIO_BRUSH_ID)
        val vs = canvas?.viewState?.value
        val ctx = StrokeContext(
            zoom = vs?.zoom ?: 1f,
            viewRotation = Math.toRadians((vs?.rotationDeg ?: 0f).toDouble()).toFloat(),
            seed = HashRng.strokeSeed(strokeCounter++, STUDIO_BRUSH_ID),
            threads = STROKE_THREADS,
        )
        val engine = StrokeEngine(doc, StrokeTarget.Layer(layer.id), brush, BrushResources.EMPTY, s.color, mode, ctx)
        stroke = engine
        engine.begin(p)
        if (engine.directToLayer) {
            canvas?.invalidate(layer.id, engine.takeDirty())
        } else {
            canvas?.setStrokePreview(layer.id, engine.buffer, mode == StrokeMode.ERASE, engine.previewOpacity)
            canvas?.invalidateStroke(engine.takeDirty())
        }
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        val engine = stroke ?: return
        engine.add(points)
        flushStroke(engine)
    }

    override fun onStrokeEnd() {
        val engine = stroke ?: return
        stroke = null
        val layerId = doc.activeId ?: return
        val commit = engine.end()
        if (!engine.directToLayer) canvas?.setStrokePreview(layerId, null, false, 1f)
        val patch = (commit as? StrokeCommit.Pixels)?.patch
        if (patch != null) {
            history.push(TilePatchCommand(patch))
            canvas?.invalidate(patch.layerId, patch.after.keys)
        }
        if (_state.value.mode == BrushModeUi.PAINT) rememberColor(_state.value.color)
    }

    override fun onStrokeCancel() {
        val engine = stroke ?: return
        stroke = null
        engine.cancel()
        val layerId = doc.activeId ?: return
        if (engine.directToLayer) canvas?.invalidate(layerId, null)
        else canvas?.setStrokePreview(layerId, null, false, 1f)
    }

    private fun flushStroke(engine: StrokeEngine) {
        val dirty = engine.takeDirty()
        if (dirty.isEmpty()) return
        if (engine.directToLayer) canvas?.invalidate(doc.activeId, dirty) else canvas?.invalidateStroke(dirty)
    }

    override fun onUndoGesture() = undo()
    override fun onRedoGesture() = redo()

    override fun onLongPress(docX: Float, docY: Float) {
        val layerId = doc.activeId
        viewModelScope.launch {
            val picked = withContext(Dispatchers.Default) {
                Eyedropper(doc, PickSource.CANVAS, layerId, diameter = 1).sample(docX, docY)
            }
            picked?.let { setColor(it, commit = true) }
        }
    }

    // ---- brush ----

    override fun setMode(mode: BrushModeUi) {
        _state.update { it.copy(mode = mode) }
        syncBrushState()
    }

    override fun setBrushSize(size: Float) {
        updateParams { it.copy(size = size.coerceIn(it.sizeMin, it.sizeMax)) }
        val p = currentParams()
        val zoom = _state.value.viewZoom
        showHud(HudUi("Size", "${p.size.toInt()} px", p.size * zoom, p.sizeMax * zoom))
    }

    override fun setOpacity(opacity: Float) {
        updateParams { it.copy(opacity = opacity.coerceIn(0f, 1f)) }
        showHud(HudUi("Opacity", "${(opacity * 100).toInt()}%"))
    }

    override fun setFlow(flow: Float) {
        updateParams { it.copy(flow = flow.coerceIn(0f, 1f)) }
        showHud(HudUi("Flow", "${(flow * 100).toInt()}%"))
    }

    override fun setHardness(hardness: Float) {
        updateParams { it.copy(hardness = hardness.coerceIn(0f, 1f)) }
        showHud(HudUi("Hardness", "${(hardness * 100).toInt()}%"))
    }

    override fun setColor(color: Rgba, commit: Boolean) {
        _state.update { it.copy(color = color) }
    }

    override fun addColorToPalette(color: Rgba) = _state.update {
        if (it.palette.contains(color)) it else it.copy(palette = it.palette + color)
    }

    // ---- history ----

    override fun undo() {
        commitPendingOpacity()
        if (history.undo(doc)) canvas?.invalidate(null, null)
    }

    override fun redo() {
        commitPendingOpacity()
        if (history.redo(doc)) canvas?.invalidate(null, null)
    }

    // ---- layers ----

    override fun clearLayerLongPress() {
        doc.activeRaster?.let { clearLayer(it.id) }
    }

    override fun toggleLayerPanel() = _state.update { it.copy(layerPanelOpen = !it.layerPanelOpen) }

    override fun toggleColorPanel() = _state.update {
        it.copy(colorPanelOpen = !it.colorPanelOpen, previousColor = if (!it.colorPanelOpen) it.color else it.previousColor)
    }

    override fun addLayer() {
        if (doc.layerCount() >= _state.value.layerLimit) return
        val active = doc.activeNode
        val parent = active?.parent ?: doc.root
        val index = if (active != null) parent.indexOf(active) + 1 else parent.children.size
        val layer = doc.newRasterLayer(defaultLayerName(doc.layerCount() + 1))
        exec(AddLayer(layer, parent.id, index))
    }

    override fun deleteLayer(id: String) {
        if (doc.layerCount() <= 1) return
        exec(DeleteNode(id))
    }

    override fun duplicateLayer(id: String) {
        if (doc.layerCount() >= _state.value.layerLimit) return
        exec(DuplicateNode(id))
    }

    override fun clearLayer(id: String) {
        if (doc.findNode(id) !is RasterLayer) return
        exec(ClearLayer(id))
    }

    override fun mergeDown(id: String) {
        val node = doc.findNode(id) as? RasterLayer ?: return
        val parent = node.parent ?: return
        val below = parent.children.getOrNull(parent.indexOf(node) - 1)
        if (below !is RasterLayer) return
        exec(MergeDown(id, Compositor))
    }

    override fun selectLayer(id: String) {
        commitPendingOpacity()
        doc.activeId = id
        refreshLayers()
    }

    override fun moveLayer(id: String, toDisplayIndex: Int) {
        val node = doc.findNode(id) ?: return
        val parent = node.parent ?: return
        val count = parent.children.size
        val target = (count - 1 - toDisplayIndex).coerceIn(0, count - 1)
        if (target == parent.indexOf(node)) return
        exec(MoveNode(id, parent.id, target))
    }

    override fun setLayerVisible(id: String, visible: Boolean) = props(id, visible = visible)
    override fun setLayerBlend(id: String, mode: BlendMode) = props(id, blendMode = mode)
    override fun setLayerClipped(id: String, clipped: Boolean) = props(id, clipped = clipped)
    override fun setLayerAlphaLock(id: String, locked: Boolean) = props(id, alphaLock = locked)
    override fun renameLayer(id: String, name: String) = props(id, newName = name)

    /** Live drag: applied immediately, recorded as one undo step after the drag settles. */
    override fun setLayerOpacity(id: String, opacity: Float) {
        val node = doc.findNode(id) ?: return
        if (pendingOpacityId != id) {
            commitPendingOpacity()
            pendingOpacityId = id
            pendingOpacityStart = node.opacity
        }
        node.opacity = opacity.coerceIn(0f, 1f)
        canvas?.invalidate(null, null)
        refreshLayers()
        opacityJob?.cancel()
        opacityJob = viewModelScope.launch { delay(600); commitPendingOpacity() }
    }

    private var pendingOpacityId: String? = null
    private var pendingOpacityStart = 1f
    private var opacityJob: Job? = null

    private fun commitPendingOpacity() {
        val id = pendingOpacityId ?: return
        pendingOpacityId = null
        opacityJob?.cancel()
        val node = doc.findNode(id) ?: return
        val final = node.opacity
        if (final == pendingOpacityStart) return
        node.opacity = pendingOpacityStart
        history.execute(doc, SetNodeProps(id, opacity = final))
    }

    override fun setPaperColor(color: Rgba) {
        doc.paper.color = color
        markDirty()
        _state.update { it.copy(paperColor = color) }
        canvas?.invalidate(null, null)
    }

    override fun setPaperVisible(visible: Boolean) {
        doc.paper.visible = visible
        markDirty()
        _state.update { it.copy(paperVisible = visible) }
        canvas?.invalidate(null, null)
    }

    // ---- options ----

    override fun openHome() {
        save()
        homeRequest.value = true
    }

    override fun save() = save(refreshList = true)

    /**
     * Writes the document in the background if it changed. [refreshList] also reloads the project list
     * (thumbnails), which autosave skips: the list is only visible on Home.
     */
    private fun save(refreshList: Boolean) {
        autosaveJob?.cancel()
        autosaveJob = null
        commitPendingOpacity()
        if (!dirtySinceSave) return
        dirtySinceSave = false
        val snapshot = snapshotDocument(doc)
        viewModelScope.launch(Dispatchers.IO) {
            val thumb = renderThumbnailPng(snapshot)
            ioLock.withLock { ProjectIO.save(snapshot, store.dirFor(snapshot.id), thumb) }
            if (refreshList) refreshProjects()
        }
    }

    /**
     * Marks the document changed and (re)starts the autosave timer: the project is written
     * [AUTOSAVE_DELAY_MS] after the last change, so a killed process loses at most that much work.
     */
    private fun markDirty() {
        dirtySinceSave = true
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            // A stroke in progress may already have touched layer tiles; save after it ends.
            while (stroke != null) delay(AUTOSAVE_DELAY_MS)
            autosaveJob = null
            save(refreshList = false)
        }
    }

    override fun exportImage() {
        exportRequest.value = true
    }

    /** Encodes the flattened image; used by the activity after the user picked a destination. */
    suspend fun encodeComposite(format: Bitmap.CompressFormat, quality: Int, transparent: Boolean): ByteArray =
        withContext(Dispatchers.Default) {
            val grid = Compositor.flatten(doc, includePaper = !transparent)
            val bmp = gridToBitmap(grid, doc.width, doc.height)
            ByteArrayOutputStream().use { out -> bmp.compress(format, quality, out); out.toByteArray() }
        }

    val documentName: String get() = doc.name

    override fun openCreativeTools() {}
    override fun openEditingTools() {}
    override fun openOptions() {}

    // ---- helpers ----

    private fun exec(cmd: Command) {
        commitPendingOpacity()
        history.execute(doc, cmd)
        canvas?.invalidate(null, null)
    }

    private fun props(
        id: String,
        newName: String? = null,
        visible: Boolean? = null,
        blendMode: BlendMode? = null,
        clipped: Boolean? = null,
        alphaLock: Boolean? = null,
    ) = exec(SetNodeProps(id, newName, visible, null, blendMode, clipped, alphaLock))

    private fun currentParams(): BrushParams =
        if (_state.value.mode == BrushModeUi.ERASE) eraseParams else paintParams

    private fun updateParams(f: (BrushParams) -> BrushParams) {
        if (_state.value.mode == BrushModeUi.ERASE) eraseParams = f(eraseParams) else paintParams = f(paintParams)
        syncBrushState()
    }

    private fun syncBrushState() {
        val p = currentParams()
        _state.update {
            it.copy(brushSize = p.size, brushSizeMax = p.sizeMax, opacity = p.opacity, flow = p.flow, hardness = p.hardness)
        }
    }

    private var hudJob: Job? = null

    private fun showHud(hud: HudUi) {
        _state.update { it.copy(hud = hud) }
        hudJob?.cancel()
        hudJob = viewModelScope.launch {
            delay(900)
            _state.update { it.copy(hud = null) }
        }
    }

    private fun rememberColor(c: Rgba) = _state.update { s ->
        s.copy(recentColors = (listOf(c) + s.recentColors.filter { it != c }).take(10))
    }

    private val thumbCache = HashMap<String, Pair<Long, ImageBitmap?>>()

    private fun refreshLayers() {
        val rows = ArrayList<LayerUi>()
        fun visit(group: GroupNode, depth: Int) {
            for (n in group.children.asReversed()) {
                rows.add(n.toUi(depth))
                if (n is GroupNode && n.expanded) visit(n, depth + 1)
            }
        }
        visit(doc.root, 0)
        _state.update {
            it.copy(
                layers = rows,
                activeLayerId = doc.activeId,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                projectName = doc.name,
                layerLimit = layerLimitFor(doc.width, doc.height),
            )
        }
    }

    private fun Node.toUi(depth: Int) = LayerUi(
        id = id,
        name = name,
        visible = visible,
        opacity = opacity,
        blendMode = blendMode,
        clipped = clipped,
        alphaLock = (this as? RasterLayer)?.alphaLock ?: false,
        thumbnail = (this as? RasterLayer)?.let(::thumbnailOf),
        depth = depth,
    )

    private fun thumbnailOf(layer: RasterLayer): ImageBitmap? {
        val grid = layer.pixels
        val stamp = grid.entries().sumOf { it.value.version + it.key.packed } + grid.keys().size
        thumbCache[layer.id]?.let { (s, bmp) -> if (s == stamp) return bmp }
        val bmp = if (grid.keys().isEmpty()) null
        else gridToBitmap(grid, grid.width, grid.height, maxSide = 96).asImageBitmap()
        thumbCache[layer.id] = stamp to bmp
        return bmp
    }

    /** Layer limit per spec/GAPS.md: 40% of the large heap budget over 8 bytes/px at 50% fill. */
    fun layerLimitFor(w: Int, h: Int): Int {
        val am = getApplication<Application>().getSystemService(ActivityManager::class.java)
        val budget = am.largeMemoryClass.toLong() * 1024 * 1024 * 4 / 10
        val perLayer = (w.toLong() * h * 8 / 2).coerceAtLeast(1)
        return (budget / perLayer).toInt().coerceIn(1, 999)
    }

    private fun renderThumbnailPng(d: Document): ByteArray {
        val grid = Compositor.flatten(d, includePaper = true)
        val bmp = gridToBitmap(grid, d.width, d.height, maxSide = 512)
        return ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }
}

/** Delay after the last change before the project is written. */
private const val AUTOSAVE_DELAY_MS = 2_000L

private fun StudioViewModel.defaultLayerName(n: Int): String =
    getApplication<android.app.Application>().getString(R.string.layer_default_name, n)

private const val STUDIO_BRUSH_ID = "studio.round"

/** Stroke worker threads: leave cores for UI and GL. */
private val STROKE_THREADS = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
