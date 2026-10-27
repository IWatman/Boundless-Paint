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
import app.painter.core.adjust.AdjustKind
import app.painter.core.adjust.AdjustParams
import app.painter.core.blend.Compositor
import app.painter.core.color.Rgba
import app.painter.core.doc.Document
import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.ColorTag
import app.painter.core.doc.EditTarget
import app.painter.core.doc.GroupNode
import app.painter.core.doc.Node
import app.painter.core.doc.RasterLayer
import app.painter.core.doc.effectivelyLocked
import app.painter.core.fill.RefSource
import app.painter.core.fill.RegionParams
import app.painter.core.history.AddAdjustmentLayer
import app.painter.core.history.AddLayer
import app.painter.core.history.AddMask
import app.painter.core.history.ApplyMask
import app.painter.core.history.ClearLayer
import app.painter.core.history.Command
import app.painter.core.history.DeleteMask
import app.painter.core.history.DeleteNode
import app.painter.core.history.DuplicateNode
import app.painter.core.history.GroupNodes
import app.painter.core.history.History
import app.painter.core.history.InvertMask
import app.painter.core.history.MaskInit
import app.painter.core.history.MaskPatchCommand
import app.painter.core.history.MergeDown
import app.painter.core.history.MoveNode
import app.painter.core.history.SetAdjustParams
import app.painter.core.history.SetMaskProps
import app.painter.core.history.SetNodeProps
import app.painter.core.history.TilePatchCommand
import app.painter.core.history.Ungroup
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
import app.painter.core.stroke.StrokeOptions
import app.painter.core.stroke.StrokeTarget
import app.painter.core.select.EllipseShape
import app.painter.core.select.PolyShape
import app.painter.core.select.RectShape
import app.painter.core.select.SelectMode
import app.painter.core.select.SelectionEdits
import app.painter.render.CanvasInputListener
import app.painter.render.CanvasView
import app.painter.ui.home.ProjectCardUi
import app.painter.ui.studio.AdjustEditorUi
import app.painter.ui.studio.BrushModeUi
import app.painter.ui.studio.CanvasTransformUi
import app.painter.ui.studio.HudUi
import app.painter.ui.studio.PointUi
import app.painter.ui.studio.RectUi
import app.painter.ui.studio.ToolUi
import app.painter.ui.studio.isSelectionTool
import app.painter.ui.studio.adjustTitleRes
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
    private var strokeOnMask = false

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
        _state.update {
            it.copy(paperColor = d.paper.color, paperVisible = d.paper.visible, adjustmentEditor = null)
        }
        adjustSession = null
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
            view.viewState.collect { vs ->
                _state.update {
                    it.copy(
                        viewZoom = vs.zoom,
                        canvasTransform = CanvasTransformUi(vs.zoom, vs.rotationDeg, vs.tx, vs.ty),
                    )
                }
            }
        }
    }

    fun detachCanvas(view: CanvasView) {
        if (canvas === view) canvas = null
    }

    override fun onStrokeBegin(p: InputPoint) {
        val node = doc.activeNode ?: return
        if (!node.visible || node.effectivelyLocked) return
        val maskTarget = doc.activeTarget == EditTarget.MASK && node.mask != null
        if (!maskTarget && node !is RasterLayer) return // groups and adjustment layers have no pixels to paint
        val layer = if (maskTarget) null else doc.activeRaster ?: return
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
        val target = if (layer == null) StrokeTarget.Mask(node.id) else StrokeTarget.Layer(layer.id)
        val engine = StrokeEngine(doc, target, brush, BrushResources.EMPTY, s.color, mode, ctx, StrokeOptions(clip = doc.selection.coverageOrNull()))
        stroke = engine
        strokeOnMask = layer == null
        engine.begin(p)
        if (layer == null) return // no live mask preview yet: the canvas refreshes when the stroke ends
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
        if (strokeOnMask) {
            (commit as? StrokeCommit.Mask)?.let { history.push(MaskPatchCommand(it.patch)) }
            canvas?.invalidate(null, null)
            return
        }
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
        if (strokeOnMask) return
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
        _state.update { it.copy(mode = mode, tool = ToolUi.BRUSH, selectionDraft = null, selectionDraftPath = null) }
        syncBrushState()
    }

    // ---- selection tools ----

    /** Drag origin in document pixels (clamped to the canvas). */
    private var toolStartX = 0f
    private var toolStartY = 0f

    /** Lasso trail in document pixels while dragging (points closer than 2 px are merged). */
    private val lassoPoints = ArrayList<PointUi>()

    override val toolActive: Boolean get() = _state.value.tool.isSelectionTool

    override fun selectTool(tool: ToolUi) = _state.update {
        it.copy(tool = tool, selectionDraft = null, selectionDraftPath = null)
    }

    private fun clampX(x: Float) = x.coerceIn(0f, doc.width.toFloat())
    private fun clampY(y: Float) = y.coerceIn(0f, doc.height.toFloat())

    /** Outline of the ellipse inscribed in the bounding box of two document-space corners. */
    private fun ellipseOutline(x0: Float, y0: Float, x1: Float, y1: Float): List<PointUi> {
        val poly = EllipseShape(
            (x0 + x1) / 2f,
            (y0 + y1) / 2f,
            if (x1 > x0) (x1 - x0) / 2f else (x0 - x1) / 2f,
            if (y1 > y0) (y1 - y0) / 2f else (y0 - y1) / 2f,
        ).toPolygon()
        return List(poly.size / 2) { PointUi(poly[it * 2], poly[it * 2 + 1]) }
    }

    override fun onToolDown(x: Float, y: Float) {
        if (!toolActive) return
        toolStartX = clampX(x)
        toolStartY = clampY(y)
        lassoPoints.clear()
        when (_state.value.tool) {
            ToolUi.SELECT_ELLIPSE -> {
                val outline = ellipseOutline(toolStartX, toolStartY, toolStartX, toolStartY)
                _state.update { it.copy(selectionDraftPath = outline) }
            }
            ToolUi.SELECT_LASSO -> {
                lassoPoints.add(PointUi(toolStartX, toolStartY))
                val trail = lassoPoints.toList()
                _state.update { it.copy(selectionDraftPath = trail) }
            }
            ToolUi.SELECT_WAND -> Unit
            else -> _state.update { it.copy(selectionDraft = RectUi(toolStartX, toolStartY, toolStartX, toolStartY)) }
        }
    }

    override fun onToolMove(x: Float, y: Float) {
        if (!toolActive) return
        val cx = clampX(x)
        val cy = clampY(y)
        if (_state.value.tool == ToolUi.SELECT_WAND) return
        when (_state.value.tool) {
            ToolUi.SELECT_ELLIPSE -> {
                val outline = ellipseOutline(toolStartX, toolStartY, cx, cy)
                _state.update { it.copy(selectionDraftPath = outline) }
            }
            ToolUi.SELECT_LASSO -> {
                val last = lassoPoints.lastOrNull()
                if (last == null || (cx - last.x) * (cx - last.x) + (cy - last.y) * (cy - last.y) >= 4f) {
                    lassoPoints.add(PointUi(cx, cy))
                    val trail = lassoPoints.toList()
                    _state.update { it.copy(selectionDraftPath = trail) }
                }
            }
            else -> _state.update {
                it.copy(
                    selectionDraft = RectUi(minOf(toolStartX, cx), minOf(toolStartY, cy), maxOf(toolStartX, cx), maxOf(toolStartY, cy)),
                )
            }
        }
    }

    override fun onToolUp(x: Float, y: Float) {
        if (!toolActive) return
        val tool = _state.value.tool
        onToolMove(x, y)
        val draft = _state.value.selectionDraft
        val trail = lassoPoints.toList()
        _state.update { it.copy(selectionDraft = null, selectionDraftPath = null) }
        lassoPoints.clear()
        when (tool) {
            ToolUi.SELECT_WAND -> {
                wandAt(clampX(x), clampY(y))
                return
            }
            ToolUi.SELECT_ELLIPSE -> {
                val rect = RectUi.between(toolStartX, toolStartY, clampX(x), clampY(y)) ?: return
                val shape = EllipseShape(rect.left + rect.width / 2f, rect.top + rect.height / 2f, rect.width / 2f, rect.height / 2f)
                SelectionEdits.shape(doc, shape, SelectMode.NEW, aa = true)?.let { exec(it) }
            }
            ToolUi.SELECT_LASSO -> {
                if (trail.size < 3) return
                val poly = FloatArray(trail.size * 2)
                trail.forEachIndexed { i, p -> poly[i * 2] = p.x; poly[i * 2 + 1] = p.y }
                SelectionEdits.shape(doc, PolyShape(poly), SelectMode.NEW, aa = true)?.let { exec(it) }
            }
            else -> {
                val rect = draft?.let { RectUi.between(it.left, it.top, it.right, it.bottom) } ?: return
                val shape = RectShape(rect.left + rect.width / 2f, rect.top + rect.height / 2f, rect.width, rect.height)
                SelectionEdits.shape(doc, shape, SelectMode.NEW, aa = true)?.let { exec(it) }
            }
        }
    }

    override fun onToolCancel() {
        lassoPoints.clear()
        _state.update { it.copy(selectionDraft = null, selectionDraftPath = null) }
    }

    /** Magic wand settings (spec §2.2): 15 % tolerance, contiguous, over the active layer. */
    private val wandParams = RegionParams(tolerance = 0.15f, contiguous = true)

    /** Runs the wand at ([x], [y]) of the active layer; the region is computed off the main thread. */
    private fun wandAt(x: Float, y: Float) {
        val d = doc
        val px = x.toInt().coerceIn(0, d.width - 1)
        val py = y.toInt().coerceIn(0, d.height - 1)
        viewModelScope.launch {
            val cmd = withContext(Dispatchers.Default) {
                SelectionEdits.wand(d, px, py, wandParams, RefSource.Current, SelectMode.NEW)
            }
            if (d === doc) cmd?.let { exec(it) }
        }
    }

    override fun selectAll() {
        SelectionEdits.selectAll(doc)?.let { exec(it) }
    }

    override fun invertSelection() {
        SelectionEdits.invert(doc)?.let { exec(it) }
    }

    override fun expandSelection(px: Float) {
        SelectionEdits.expand(doc, px.coerceIn(-100f, 100f), aa = true)?.let { exec(it) }
    }

    override fun featherSelection(radius: Float) {
        SelectionEdits.feather(doc, radius.coerceIn(0f, 250f))?.let { exec(it) }
    }

    override fun deselect() {
        SelectionEdits.deselect(doc)?.let { exec(it) }
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

    // ---- adjustment layers ----

    /** Adjustment node being edited and the parameters it had when the editor opened. */
    private var adjustSession: Pair<String, AdjustParams>? = null

    override fun addAdjustmentLayer(kind: String) {
        val params = AdjustKind.defaults(kind) ?: return
        val active = doc.activeNode
        val parent = active?.parent ?: doc.root
        val index = if (active != null) parent.indexOf(active) + 1 else parent.children.size
        val created = AddAdjustmentLayer(params, parent.id, index, layerName = adjustTitle(kind))
        exec(created)
        editAdjustment(created.nodeId)
    }

    override fun editAdjustment(id: String) {
        val node = doc.findNode(id) as? AdjustmentLayer ?: return
        commitPendingOpacity()
        adjustSession?.let { if (it.first != id) commitAdjust(it.first) }
        doc.activeId = node.id
        doc.activeTarget = EditTarget.LAYER
        adjustSession = id to node.params
        _state.update { it.copy(adjustmentEditor = AdjustEditorUi(id, node.params.kind, node.params)) }
        refreshLayers()
    }

    override fun updateAdjustment(id: String, params: AdjustParams, commit: Boolean) {
        val node = doc.findNode(id) as? AdjustmentLayer ?: return
        if (commit) {
            // Record what the node holds (live edits already reached it); take the given value only when the
            // caller changed nothing live since the session started (e.g. Reset from a fresh editor).
            val start = adjustSession?.takeIf { it.first == id }?.second
            if (start != null && node.params == start) node.params = params
            commitAdjust(id)
            return
        }
        // Live preview: the node is the single source of truth, the canvas re-reads it on invalidate.
        node.params = params
        canvas?.invalidate(null, null)
        _state.update { s ->
            val ed = s.adjustmentEditor
            if (ed == null || ed.nodeId != id) s
            else s.copy(adjustmentEditor = ed.copy(kind = params.kind, params = params))
        }
    }

    override fun closeAdjustmentEditor() {
        adjustSession?.let { commitAdjust(it.first) }
        adjustSession = null
        _state.update { it.copy(adjustmentEditor = null) }
    }

    /**
     * Records the parameters currently on the node as one [SetAdjustParams] step from the value the node had
     * when the editor was opened (or since the last commit of this session).
     */
    private fun commitAdjust(id: String) {
        val session = adjustSession ?: return
        if (session.first != id) return
        val node = doc.findNode(id) as? AdjustmentLayer ?: return
        val before = session.second
        val after = node.params
        adjustSession = id to after
        if (after == before) return
        commitPendingOpacity()
        node.params = before
        history.execute(doc, SetAdjustParams(id, before, after))
        canvas?.invalidate(null, null)
    }

    private fun adjustTitle(kind: String): String =
        getApplication<Application>().getString(adjustTitleRes(kind))

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

    override fun setLayerColorTag(id: String, tag: ColorTag) = exec(SetNodeProps(id, colorTag = tag))
    override fun setLayerLocked(id: String, locked: Boolean) = exec(SetNodeProps(id, locked = locked))
    override fun setLayerReference(id: String, reference: Boolean) =
        // Adjustment layers cannot be the document reference (core rejects it).
        if (reference && doc.findNode(id) is AdjustmentLayer) Unit
        else exec(SetNodeProps(id, reference = reference))

    override fun toggleGroupExpanded(id: String) {
        val g = doc.findNode(id) as? GroupNode ?: return
        g.expanded = !g.expanded
        markDirty()
        refreshLayers()
    }

    override fun groupLayer(id: String) {
        val n = doc.findNode(id) ?: return
        exec(GroupNodes(listOf(n.id), getApplication<Application>().getString(R.string.group_default_name, groupCount() + 1)))
    }

    private fun groupCount(): Int { var n = 0; doc.root.walk { if (it is GroupNode) n++ }; return n }

    override fun ungroupLayer(id: String) {
        if (doc.findNode(id) is GroupNode) exec(Ungroup(id))
    }

    override fun addMask(id: String, init: MaskInit) {
        if (doc.findNode(id)?.mask != null) return
        exec(AddMask(id, init, Compositor))
        selectEditTarget(id, EditTarget.MASK)
    }

    override fun deleteMask(id: String) {
        if (doc.findNode(id)?.mask == null) return
        exec(DeleteMask(id))
        if (doc.activeId == id) doc.activeTarget = EditTarget.LAYER
        refreshLayers()
    }

    override fun applyMask(id: String) {
        if (doc.findNode(id)?.mask == null) return
        exec(ApplyMask(id))
        if (doc.activeId == id) doc.activeTarget = EditTarget.LAYER
        refreshLayers()
    }

    override fun setMaskEnabled(id: String, enabled: Boolean) = exec(SetMaskProps(id, enabled = enabled))
    override fun invertMask(id: String) = exec(InvertMask(id))

    override fun selectEditTarget(id: String, target: EditTarget) {
        val n = doc.findNode(id) ?: return
        doc.activeId = n.id
        doc.activeTarget = if (target == EditTarget.MASK && n.mask != null) EditTarget.MASK else EditTarget.LAYER
        refreshLayers()
    }

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
        if (!dirtySinceSave) {
            // Autosave may already have written the latest state; Home still needs fresh thumbnails.
            if (refreshList) refreshProjects()
            return
        }
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

    /** Publishes the selection presence and bounds from the document mask. */
    private fun refreshSelection() {
        val coverage = doc.selection.coverageOrNull()
        val b = coverage?.bounds()
        _state.update {
            it.copy(
                hasSelection = coverage != null,
                selectionBounds = b?.let { r -> RectUi(r.x0.toFloat(), r.y0.toFloat(), r.x1.toFloat(), r.y1.toFloat()) },
            )
        }
    }

    private fun refreshLayers() {
        refreshSelection()
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
                // Drop the editor when its node was deleted or undone away; otherwise keep it in sync.
                adjustmentEditor = it.adjustmentEditor?.let { ed ->
                    (doc.findNode(ed.nodeId) as? AdjustmentLayer)?.let { n ->
                        ed.copy(kind = n.params.kind, params = n.params)
                    }
                },
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
        isGroup = this is GroupNode,
        expanded = (this as? GroupNode)?.expanded ?: false,
        hasMask = mask != null,
        maskEnabled = mask?.enabled ?: true,
        colorTag = colorTag,
        locked = locked,
        reference = reference,
        isActiveTargetMask = id == doc.activeId && doc.activeTarget == EditTarget.MASK && mask != null,
        // Adjustment layers have no pixels: the row shows a kind badge instead of a thumbnail.
        isAdjustment = this is AdjustmentLayer,
        adjustKind = (this as? AdjustmentLayer)?.params?.kind,
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
