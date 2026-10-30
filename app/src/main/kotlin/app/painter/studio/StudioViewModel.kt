package app.painter.studio

import android.app.ActivityManager
import android.os.SystemClock
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import app.painter.core.fill.BucketFill
import app.painter.core.fill.FillCommand
import app.painter.core.fill.Gradient
import app.painter.core.fill.GradientSpec
import app.painter.core.fill.GradientStop
import app.painter.core.fill.GradientType
import app.painter.core.fill.RefSource
import app.painter.core.fill.Region
import app.painter.core.fill.RegionParams
import app.painter.core.fill.Spread
import app.painter.core.guide.DocRect
import app.painter.core.guide.EllipseConfig
import app.painter.core.guide.GuideJson
import app.painter.core.guide.GuideState
import app.painter.core.guide.LazyConfig
import app.painter.core.guide.LinesConfig
import app.painter.core.guide.SnapContext
import app.painter.core.history.AddAdjustmentLayer
import app.painter.core.history.AddLayer
import app.painter.core.history.AddMask
import app.painter.core.history.ApplyMask
import app.painter.core.history.ClearLayer
import app.painter.core.history.CompoundCommand
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
import app.painter.core.perspective.PerspectiveConfig
import app.painter.core.perspective.PerspectiveOverlay
import app.painter.core.perspective.PerspectiveType
import app.painter.core.pick.Eyedropper
import app.painter.core.pick.PickSource
import app.painter.core.shape.Handle as ShapeHandle
import app.painter.core.shape.Shape
import app.painter.core.shape.ShapeFactory
import app.painter.core.shape.ShapeHandles
import app.painter.core.shape.ShapeHit
import app.painter.core.shape.ShapeStroke
import app.painter.core.shape.ShapeTool
import app.painter.core.symmetry.MirrorAxes
import app.painter.core.symmetry.SymmetryConfig
import app.painter.core.symmetry.SymmetryType
import app.painter.core.stroke.Brush
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.BrushResources
import app.painter.core.stroke.CloneSource
import app.painter.core.stroke.FillSpec
import app.painter.core.stroke.HashRng
import app.painter.core.stroke.InputCalibration
import app.painter.core.stroke.InputPoint
import app.painter.core.stroke.StrokeCommit
import app.painter.core.stroke.StrokeContext
import app.painter.core.stroke.StrokeEngine
import app.painter.core.stroke.PaintParams
import app.painter.core.stroke.StrokeMode
import app.painter.core.stroke.StrokeOptions
import app.painter.core.stroke.SymmetryCopies
import app.painter.core.raster.MaskGrid
import app.painter.core.stroke.StrokeTarget
import app.painter.core.geom.Vec2
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch
import app.painter.core.raster.applyTiles
import app.painter.core.transform.Affine
import app.painter.core.transform.CageMath
import app.painter.core.transform.FloatRect
import app.painter.core.transform.Handle
import app.painter.core.transform.Resample
import app.painter.core.transform.Snap
import app.painter.core.transform.TransformSession
import app.painter.core.select.EllipseShape
import app.painter.core.select.MaskOps
import app.painter.core.select.PolyShape
import app.painter.core.select.RectShape
import app.painter.core.select.SelectMode
import app.painter.core.select.SelectionEdits
import app.painter.render.CanvasInputListener
import app.painter.render.CanvasView
import app.painter.ui.home.ProjectCardUi
import app.painter.ui.studio.AdjustEditorUi
import app.painter.ui.studio.BrushCategoryUi
import app.painter.ui.studio.BrushItemUi
import app.painter.ui.studio.BrushModeUi
import app.painter.ui.studio.CalibrationUi
import app.painter.ui.studio.CanvasTransformUi
import app.painter.ui.studio.EllipseUi
import app.painter.ui.studio.GradientStopUi
import app.painter.ui.studio.GradientUi
import app.painter.ui.studio.HudUi
import app.painter.ui.studio.PointUi
import app.painter.ui.studio.RectUi
import app.painter.ui.studio.RulerUi
import app.painter.ui.studio.ToolUi
import app.painter.ui.studio.isSelectionTool
import app.painter.ui.studio.adjustTitleRes
import app.painter.ui.studio.LayerUi
import app.painter.ui.studio.LazyUi
import app.painter.ui.studio.OverlayLineUi
import app.painter.ui.studio.PerspectiveUi
import app.painter.ui.studio.StudioActions
import app.painter.ui.studio.StudioUiState
import app.painter.ui.studio.ShapeUi
import app.painter.ui.studio.SymmetryUi
import app.painter.ui.studio.TransformUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Owns the open document and wires UI actions, the canvas view and the core engines. [prefs] carries the
 * tool settings that outlive a session (color, palette, per-mode brush preset with its size and opacity), and
 * [input] the per-device stylus calibration and the global stylus settings (spec/tools/m2-brush.md §4.3–4.4).
 * Null keeps either of them in memory only, which is what tests want.
 */
class StudioViewModel(
    app: Application,
    private val prefs: ToolPrefs? = null,
    private val input: InputSettings? = null,
) :
    AndroidViewModel(app), StudioActions, CanvasInputListener {

    companion object {
        /**
         * Factory for `by viewModels()`: the default `AndroidViewModelFactory` looks for an
         * `(Application)` constructor, which the defaulted [prefs] parameter takes away.
         */
        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                StudioViewModel(app, ToolPrefs(app), InputSettings(app)) as T
        }
    }

    private val store = ProjectStore(app.filesDir.resolve("projects").apply { mkdirs() })
    private val ioLock = Mutex()

    /** Pressure calibration of every input device seen so far, keyed by `InputDevice.descriptor`. */
    private val calibrations = HashMap<String, InputCalibration>()

    /** Stylus and input settings of the session (spec/tools/m2-brush.md §4.3); see [setStylusSettings]. */
    private val _stylusSettings = MutableStateFlow(StylusSettings())
    /** Pen buttons, finger rule, palm mode and pressure curve preset of the session. */
    val stylusSettings: StateFlow<StylusSettings> = _stylusSettings

    private var doc: Document = Document.create(1, 1)
    /** Guides, symmetry and grid settings of [doc]; symmetry is view state, so it is never undone. */
    private var guides: GuideState = GuideState()
    private val history = History()
    private var canvas: CanvasView? = null
    private var stroke: StrokeEngine? = null
    private var dirtySinceSave = false
    private var autosaveJob: Job? = null
    private var strokeCounter = 0L
    private var strokeOnMask = false
    /** Mode that was active before a stroke of the back of the pen switched to Erase; null outside such a stroke. */
    private var modeBeforePenEraser: BrushModeUi? = null
    /** Descriptor of the device of the running stylus stroke; saved when the stroke ends, null otherwise. */
    private var strokeDevice: String? = null
    /** In-flight marching-ants contour computation of the selection mask. */
    private var contourJob: Job? = null
    /** Bumped for every contour request so a stale result is never published later. */
    private var contourRev = 0L
    /** Fingerprint of the mask the published contour was computed from (tile versions + identity). */
    private var contourSig: List<Long>? = null

    private var paintParams = BrushParams()
    private var eraseParams = BrushParams(size = 40f)

    /** Blend (smudge) keeps its own soft, large tip with a long pull so the smear is visible (spec §1.8). */
    private var blendParams = BrushParams(
        size = 60f,
        hardness = 0.4f,
        spacing = 0.1f,
        paint = PaintParams(pull = 0.9f),
    )

    /** Built-in preset catalog; read off the main thread once at start-up. */
    private val brushes = BrushLibrary(app.assets)

    /** Preset the PAINT and ERASE modes paint with, null until the library has picked its favourites. */
    private var paintBrush: Brush? = null
    private var eraseBrush: Brush? = null

    private val _state = MutableStateFlow(StudioUiState(palette = DefaultPalette.colors, layerPanelOpen = false))
    val state: StateFlow<StudioUiState> = _state

    private val _projects = MutableStateFlow<List<ProjectCardUi>>(emptyList())
    val projects: StateFlow<List<ProjectCardUi>> = _projects

    /** Set by the UI when the user picks Export; the activity launches the system file picker. */
    val exportRequest = MutableStateFlow(false)

    /** Set by [openHome]; the activity navigates back to Home and resets it. */
    val homeRequest = MutableStateFlow(false)

    init {
        history.onChanged = { lastFill = null; markDirty(); refreshLayers() }
        restoreToolPrefs()
        restoreInputSettings()
        syncBrushState()
        viewModelScope.launch(Dispatchers.IO) { ioLock.withLock { store.recoverInterrupted() } }
        refreshProjects()
        loadBrushes()
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
        finishShape(commit = false)
        finishTransform(apply = false)
        finishGradient(apply = false)
        save(refreshList = false) // flush pending changes of the previous document
        doc = d
        guides = GuideJson.fromElement(d.guides)
        // A manifest without a centre (a new document) gets the symmetry centre of the canvas.
        if (guides.symmetry.center.x == 0f && guides.symmetry.center.y == 0f) {
            guides = guides.copy(
                symmetry = guides.symmetry.copy(center = SymmetryConfig.centred(d.width, d.height).center),
            )
        }
        history.clear()
        autosaveJob?.cancel()
        autosaveJob = null
        dirtySinceSave = false
        stroke = null
        canvas?.setDocument(doc)
        _state.update {
            it.copy(
                paperColor = d.paper.color,
                paperVisible = d.paper.visible,
                adjustmentEditor = null,
                cloneSource = null,
                cloneOffset = null,
                symmetry = symmetryUi(),
                ruler = rulerUi(),
                ellipse = ellipseUi(),
                lazy = lazyUi(),
                perspective = perspectiveUi(),
            )
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
        view.stylusSettings = _stylusSettings.value
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

    override fun onStrokeBegin(p: InputPoint, deviceDescriptor: String, penEraser: Boolean) {
        // The back of the pen (TOOL_TYPE_ERASER) erases with the erase brush for this stroke only (§4.3);
        // the previous mode comes back in onStrokeEnd/onStrokeCancel, also when the stroke is dropped.
        if (penEraser) enterPenEraserMode()
        val node = doc.activeNode ?: return
        if (!node.visible || node.effectivelyLocked) return
        val maskTarget = doc.activeTarget == EditTarget.MASK && node.mask != null
        if (!maskTarget && node !is RasterLayer) return // groups and adjustment layers have no pixels to paint
        val layer = if (maskTarget) null else doc.activeRaster ?: return
        val s = _state.value
        val clone = if (s.tool == ToolUi.CLONE) cloneStroke(layer, p.x, p.y) else null
        if (s.tool == ToolUi.CLONE && clone == null) return // no source yet: the hint asks for a long-press
        val mode = if (clone != null) StrokeMode.CLONE else when (s.mode) {
            BrushModeUi.ERASE -> StrokeMode.ERASE
            // Smudge reads and writes layer pixels; masks have none, so Blend on a mask paints as before.
            BrushModeUi.BLEND -> if (layer != null) StrokeMode.BLEND else StrokeMode.PAINT
            BrushModeUi.PAINT -> StrokeMode.PAINT
        }
        val brush = if (clone != null) paintStrokeBrush() else strokeBrush()
        val calibration = calibrationFor(deviceDescriptor)
        strokeDevice = if (p.isStylus) deviceDescriptor else null
        // The stylus panel reports the pen that drew last, so its state line follows the device in use.
        if (p.isStylus) {
            panelDevice = deviceDescriptor
            if (_state.value.stylusPanelOpen) publishCalibration()
        }
        val vs = canvas?.viewState?.value
        val zoom = vs?.zoom ?: 1f
        val ctx = StrokeContext(
            zoom = zoom,
            clone = clone,
            calibration = calibration,
            viewRotation = Math.toRadians((vs?.rotationDeg ?: 0f).toDouble()).toFloat(),
            seed = HashRng.strokeSeed(strokeCounter++, STUDIO_BRUSH_ID),
            threads = STROKE_THREADS,
        )
        val target = if (layer == null) StrokeTarget.Mask(node.id) else StrokeTarget.Layer(layer.id)
        // Symmetry copies are fixed at stroke start (spec G4); symmetry itself is view state.
        val copies = if (guides.symmetry.on) guides.copies(Vec2(p.x, p.y)) else SymmetryCopies.NONE
        // Guides and the lazy rope are snapshotted per stroke too (spec G1.1): they never change mid-stroke.
        val stages = guides.buildStages(SnapContext(screenToDoc = 1f / zoom, brushSize = s.brushSize))
        val engine = StrokeEngine(
            doc, target, brush, brushes.resources(brush), s.color, mode, ctx,
            StrokeOptions(stages = stages, copies = copies, clip = doc.selection.coverageOrNull()),
        )
        stroke = engine
        strokeOnMask = layer == null
        engine.begin(p)
        if (layer == null) return // no live mask preview yet: the canvas refreshes when the stroke ends
        if (engine.directToLayer) {
            canvas?.invalidate(layer.id, engine.takeDirty())
        } else {
            canvas?.setStrokePreview(
                layer.id, engine.buffer, mode == StrokeMode.ERASE, engine.previewOpacity,
                engine.previewBlend, engine.previewBlendStrength,
                // Wet edges and adhesion (§1.7) are per pixel: upload the processed preview, not the raw buffer.
                if (engine.previewNeedsProcessing) engine::previewTile else null,
            )
            canvas?.invalidateStroke(engine.takeDirty())
        }
    }

    /**
     * Clone source of a stroke started at ([x], [y]): null while no source point is picked or the target is a
     * mask (clone needs layer pixels). The first stroke after the long-press fixes the offset, later strokes
     * reuse it so the copy follows the brush ([CloneSource.aligned]).
     */
    private fun cloneStroke(layer: RasterLayer?, x: Float, y: Float): CloneSource? {
        val s = _state.value
        val source = s.cloneSource ?: return null
        if (layer == null) return null
        val offset = s.cloneOffset ?: PointUi(x - source.x, y - source.y).also { o ->
            _state.update { it.copy(cloneOffset = o) }
        }
        return CloneSource(layerId = layer.id, offsetX = offset.x, offsetY = offset.y, aligned = true)
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        val engine = stroke ?: return
        engine.add(points)
        flushStroke(engine)
    }

    override fun onStrokeEnd() {
        restorePenEraserMode()
        val engine = stroke ?: return
        stroke = null
        val layerId = doc.activeId ?: return
        val commit = engine.end()
        saveInputSettingsAfterStroke()
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
        if (_state.value.mode == BrushModeUi.PAINT && _state.value.tool != ToolUi.CLONE) rememberColor(_state.value.color)
    }

    override fun onStrokeCancel() {
        restorePenEraserMode()
        strokeDevice = null
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

    /** A pen button assigned to the brush browser was clicked (spec/tools/m2-brush.md §4.3). */
    override fun onBrushBrowserToggle() = toggleBrushBrowser()

    override fun onLongPress(docX: Float, docY: Float) {
        // In the clone tool the long press picks the clone source instead of an eyedropper sample.
        if (_state.value.tool == ToolUi.CLONE) {
            _state.update { it.copy(cloneSource = PointUi(docX, docY), cloneOffset = null) }
            return
        }
        val layerId = doc.activeId
        viewModelScope.launch {
            val picked = withContext(Dispatchers.Default) {
                Eyedropper(doc, PickSource.CANVAS, layerId, diameter = 1).sample(docX, docY)
            }
            picked?.let { setColor(it, commit = true) }
        }
    }

    /**
     * Eyedropper sampled by a pen touch with the primary button held (spec/tools/m2-brush.md §4.3): the same
     * sample as the finger long press, without its clone-source special case.
     */
    override fun onPickColor(x: Float, y: Float) {
        val layerId = doc.activeId
        viewModelScope.launch {
            val picked = withContext(Dispatchers.Default) {
                Eyedropper(doc, PickSource.CANVAS, layerId, diameter = 1).sample(x, y)
            }
            picked?.let { setColor(it, commit = true) }
        }
    }

    // ---- pen ----

    /** Switches to Erase for a stroke of the back of the pen; [restorePenEraserMode] brings the tip's mode back. */
    private fun enterPenEraserMode() {
        val mode = _state.value.mode
        if (mode == BrushModeUi.ERASE) return
        modeBeforePenEraser = mode
        _state.update { it.copy(mode = BrushModeUi.ERASE) }
        syncBrushState()
    }

    /** Restores the mode of the tip after a stroke of the back of the pen; no-op outside such a stroke. */
    private fun restorePenEraserMode() {
        val mode = modeBeforePenEraser ?: return
        modeBeforePenEraser = null
        _state.update { it.copy(mode = mode) }
        syncBrushState()
    }

    // ---- brush ----

    override fun setMode(mode: BrushModeUi) {
        finishTransform(apply = true)
        finishGradient(apply = true)
        finishShape(commit = true)
        _state.update { it.copy(mode = mode, tool = ToolUi.BRUSH, selectionDraft = null, selectionDraftPath = null) }
        syncBrushState()
    }

    override fun toggleBrushBrowser() = _state.update { it.copy(brushBrowserOpen = !it.brushBrowserOpen) }

    override fun toggleStylusPanel() {
        publishCalibration()
        _state.update { it.copy(stylusPanelOpen = !it.stylusPanelOpen) }
    }

    override fun selectBrush(id: String) {
        val brush = brushes.find(id) ?: return
        if (_state.value.mode == BrushModeUi.ERASE) {
            eraseBrush = brush
            eraseParams = brush.params
        } else {
            paintBrush = brush
            paintParams = brush.params
        }
        syncBrushState()
        _state.update { it.copy(brushBrowserOpen = false) }
        saveBrushPrefs()
    }

    /**
     * Reads the built-in brush catalog off the main thread, publishes its categories and seeds the quick
     * parameters of both modes from the favourite preset of that mode.
     */
    private fun loadBrushes() {
        viewModelScope.launch(Dispatchers.IO) {
            brushes.load()
            val lang = if (Locale.getDefault().language == "ru") "ru" else "en"
            val categories = brushes.categories.map { c ->
                BrushCategoryUi(c.id, c.name, c.brushes.map { BrushItemUi(it.id, it.name.get(lang)) })
            }
            val paint = brushes.favorites(FAVORITE_PAINT).firstOrNull()?.let { brushes.find(it) }
            val erase = brushes.favorites(FAVORITE_ERASE).firstOrNull()?.let { brushes.find(it) }
            if (paintBrush == null && paint != null) {
                paintBrush = paint
                paintParams = paint.params
            }
            if (eraseBrush == null && erase != null) {
                eraseBrush = erase
                eraseParams = erase.params
            }
            restoreBrushPrefs()
            // Decoding the images of the initial presets happens here, not on the first stroke.
            listOfNotNull(paintBrush, eraseBrush).forEach { brushes.resources(it) }
            _state.update { it.copy(brushCategories = categories) }
            syncBrushState()
        }
    }

    /** Preset of the current mode; null for [BrushModeUi.BLEND], which always paints with the studio brush. */
    private val currentBrush: Brush?
        get() = when (_state.value.mode) {
            BrushModeUi.PAINT -> paintBrush
            BrushModeUi.ERASE -> eraseBrush
            BrushModeUi.BLEND -> null
        }

    /**
     * Brush of the current mode with the quick size/opacity/flow/hardness edits applied on top of the preset;
     * the studio brush stands in while the library is not loaded and for [BrushModeUi.BLEND].
     */
    private fun strokeBrush(params: BrushParams = currentParams()): Brush =
        (currentBrush ?: Brush.of(params, STUDIO_BRUSH_ID)).copy(params = params)

    /** Paint preset with the quick PAINT params; the clone tool paints with these whatever the brush mode. */
    private fun paintStrokeBrush(): Brush {
        val params = paintParams
        return (paintBrush ?: Brush.of(params, STUDIO_BRUSH_ID)).copy(params = params)
    }

    // ---- selection tools ----

    /** Drag origin in document pixels (clamped to the canvas). */
    private var toolStartX = 0f
    private var toolStartY = 0f

    /** Lasso trail in document pixels while dragging (points closer than 2 px are merged). */
    private val lassoPoints = ArrayList<PointUi>()

    override val toolActive: Boolean
        get() = _state.value.tool == ToolUi.TRANSFORM || _state.value.tool == ToolUi.FILL ||
            _state.value.tool == ToolUi.GRADIENT || _state.value.tool == ToolUi.SHAPE ||
            _state.value.tool.isSelectionTool

    override fun selectTool(tool: ToolUi) {
        if (tool != ToolUi.TRANSFORM) finishTransform(apply = true)
        if (tool != ToolUi.GRADIENT) finishGradient(apply = true)
        if (tool != ToolUi.SHAPE) finishShape(commit = true)
        _state.update { it.copy(tool = tool, selectionDraft = null, selectionDraftPath = null) }
        if (tool == ToolUi.TRANSFORM) startTransform()
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
        if (_state.value.tool == ToolUi.SHAPE) {
            shapeDown(x, y)
            return
        }
        if (!toolActive) return
        if (_state.value.tool == ToolUi.TRANSFORM) {
            transformDown(x, y)
            return
        }
        if (_state.value.tool == ToolUi.GRADIENT) {
            gradientDown(x, y)
            return
        }
        if (_state.value.tool == ToolUi.FILL) return
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
        if (_state.value.tool == ToolUi.SHAPE) {
            shapeMove(x, y, force = false)
            return
        }
        if (!toolActive) return
        if (_state.value.tool == ToolUi.TRANSFORM) {
            transformMoveTo(x, y)
            return
        }
        if (_state.value.tool == ToolUi.GRADIENT) {
            gradientMove(x, y, force = false)
            return
        }
        if (_state.value.tool == ToolUi.FILL) return
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
        if (_state.value.tool == ToolUi.SHAPE) {
            shapeUp(x, y)
            return
        }
        if (!toolActive) return
        if (_state.value.tool == ToolUi.TRANSFORM) {
            transformMoveTo(x, y)
            transformUp()
            return
        }
        if (_state.value.tool == ToolUi.GRADIENT) {
            gradientUp(x, y)
            return
        }
        if (_state.value.tool == ToolUi.FILL) {
            bucketAt(clampX(x), clampY(y))
            return
        }
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
        // A cancelled shape gesture only stops the drag: the draft stays open.
        if (_state.value.tool == ToolUi.SHAPE) {
            shapeDrag = ShapeDrag.NONE
            shapeHandle = null
            return
        }
        if (_state.value.tool == ToolUi.TRANSFORM) {
            transformDrag = TransformDrag.NONE
            return
        }
        // A cancelled gesture only stops moving the end or the stop: the gradient session stays open.
        if (_state.value.tool == ToolUi.GRADIENT) {
            gradientDragStop = -1
            gradientTapCandidate = false
            gradientDragP1 = true
            return
        }
        lassoPoints.clear()
        _state.update { it.copy(selectionDraft = null, selectionDraftPath = null) }
    }

    // ---- transform tool ----

    /** Open transform session of the active layer, null while the tool is idle. */
    private var transformSession: TransformSession? = null

    /** Mapping shown by the session; always applied to the original floating pixels. */
    private var transformMap: Affine = Affine.IDENTITY

    /** Cage the session started from, in document pixels. */
    private var transformCage: FloatRect? = null

    private enum class TransformDrag { NONE, MOVE, SCALE, ROTATE }

    private var transformDrag = TransformDrag.NONE
    private var transformHandle = Handle.TOP_LEFT
    private var transformStartX = 0f
    private var transformStartY = 0f
    private var transformBase = Affine.IDENTITY

    /** Tiles of the layer the live preview overwrote, as they were before the preview wrote them. */
    private val previewRest = HashMap<TileKey, Tile?>()
    private val previewKeys = HashSet<TileKey>()
    private var lastPreviewMs = 0L

    private val cornerHandles = arrayOf(Handle.TOP_LEFT, Handle.TOP_RIGHT, Handle.BOTTOM_RIGHT, Handle.BOTTOM_LEFT)

    private val densityPx: Float get() = getApplication<Application>().resources.displayMetrics.density

    /** Pick radius of the handles in document pixels (about 22 dp on screen). */
    private fun handleTolerance(): Float = 22f * densityPx / _state.value.canvasTransform.zoom.coerceAtLeast(0.01f)

    /**
     * Starts a [TransformSession.Target.PIXELS] session on the active layer. The session itself lifts the
     * selection when there is one (otherwise the whole layer) and leaves the rest in the layer for the
     * preview. Falls back to [ToolUi.BRUSH] when there is nothing to transform.
     */
    private fun startTransform() {
        finishTransform(apply = false)
        val node = doc.activeId?.let { doc.findNode(it) } as? RasterLayer
        if (node == null || node.locked) {
            _state.update { it.copy(tool = ToolUi.BRUSH, transform = null) }
            return
        }
        val session = TransformSession(doc, listOf(node.id), TransformSession.Target.PIXELS)
        val cage = session.cage
        if (session.layers.isEmpty() || cage == null) {
            session.cancel()
            _state.update { it.copy(tool = ToolUi.BRUSH, transform = null) }
            return
        }
        transformSession = session
        transformMap = Affine.IDENTITY
        transformCage = FloatRect.of(cage)
        transformDrag = TransformDrag.NONE
        previewRest.clear()
        previewKeys.clear()
        publishTransformCage()
        previewTransform(force = true)
        canvas?.invalidate(node.id, null)
    }

    /** Publishes the 4 cage corners after [transformMap] for the overlay. */
    private fun publishTransformCage() {
        val cage = transformCage ?: return
        val q = CageMath.quad(transformMap, cage)
        val corners = List(4) { PointUi(q[it * 2], q[it * 2 + 1]) }
        _state.update { it.copy(transform = TransformUi(corners)) }
    }

    /**
     * Closes the session. [apply] commits the mapping (one undoable "Transform" step) and returns to
     * [ToolUi.BRUSH]; otherwise the pixels are restored without a history entry.
     */
    private fun finishTransform(apply: Boolean) {
        val session = transformSession ?: return
        transformSession = null
        if (apply) {
            val cmd = session.commit(transformMap, Resample.BICUBIC)
            if ((cmd as? CompoundCommand)?.commands?.isEmpty() != true) history.push(cmd)
        } else {
            session.cancel()
        }
        previewRest.clear()
        previewKeys.clear()
        transformDrag = TransformDrag.NONE
        transformMap = Affine.IDENTITY
        transformCage = null
        _state.update {
            it.copy(tool = ToolUi.BRUSH, transform = null, selectionDraft = null, selectionDraftPath = null)
        }
        canvas?.invalidate(null, null)
        refreshLayers()
    }

    override fun transformApply() = finishTransform(apply = true)

    override fun transformCancel() = finishTransform(apply = false)

    /** Axis-aligned bounds of the 4 corners [q]. */
    private fun quadRect(q: FloatArray) = FloatRect(
        minOf(q[0], q[2], q[4], q[6]),
        minOf(q[1], q[3], q[5], q[7]),
        maxOf(q[0], q[2], q[4], q[6]),
        maxOf(q[1], q[3], q[5], q[7]),
    )

    /** True when ([x], [y]) is inside the convex quad [q]. */
    private fun insideQuad(q: FloatArray, x: Float, y: Float): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = q[i * 2]
            val ay = q[i * 2 + 1]
            val bx = q[((i + 1) % 4) * 2]
            val by = q[((i + 1) % 4) * 2 + 1]
            val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            if (kotlin.math.abs(cross) < 1e-4f) continue
            val sg = if (cross > 0f) 1 else -1
            if (sign == 0) sign = sg else if (sg != sign) return false
        }
        return true
    }

    /** Document-space position of the rotate handle above the middle of the top edge of [q]. */
    private fun rotateHandlePos(q: FloatArray): Vec2 {
        val zoom = _state.value.canvasTransform.zoom.coerceAtLeast(0.01f)
        val mx = (q[0] + q[2]) / 2f
        val my = (q[1] + q[3]) / 2f
        val ex = q[2] - q[0]
        val ey = q[3] - q[1]
        val len = kotlin.math.hypot(ex, ey).coerceAtLeast(1e-4f)
        var nx = -ey / len
        var ny = ex / len
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        if ((mx - cx) * nx + (my - cy) * ny < 0f) {
            nx = -nx
            ny = -ny
        }
        val d = ROTATE_HANDLE_DP * densityPx / zoom
        return Vec2(mx + nx * d, my + ny * d)
    }

    private fun transformDown(x: Float, y: Float) {
        val cage = transformCage ?: return
        if (transformSession == null) return
        transformBase = transformMap
        transformStartX = x
        transformStartY = y
        val q = CageMath.quad(transformMap, cage)
        val tol = handleTolerance()
        val rot = rotateHandlePos(q)
        if (kotlin.math.hypot(x - rot.x, y - rot.y) <= tol) {
            transformDrag = TransformDrag.ROTATE
            return
        }
        var best = tol
        var picked: Handle? = null
        for (i in 0 until 4) {
            val d = kotlin.math.hypot(x - q[i * 2], y - q[i * 2 + 1])
            if (d <= best) {
                best = d
                picked = cornerHandles[i]
            }
        }
        if (picked != null) {
            transformHandle = picked
            transformDrag = TransformDrag.SCALE
            return
        }
        transformDrag = if (insideQuad(q, x, y)) TransformDrag.MOVE else TransformDrag.NONE
    }

    /**
     * Recomputes the mapping of the drag in progress: inside the cage moves (snapped to the canvas edges and
     * centre), a corner handle scales uniformly about the cage centre, the rotate handle turns about the cage
     * centre (snapped to multiples of 90°).
     */
    private fun transformMoveTo(x: Float, y: Float) {
        val cage = transformCage ?: return
        if (transformSession == null || transformDrag == TransformDrag.NONE) return
        val zoom = _state.value.canvasTransform.zoom.coerceAtLeast(0.01f)
        val next = when (transformDrag) {
            TransformDrag.MOVE -> {
                val dx = x - transformStartX
                val dy = y - transformStartY
                val fix = Snap.move(quadRect(CageMath.quad(CageMath.move(transformBase, dx, dy), cage)), doc.width, doc.height, 8f * densityPx / zoom)
                CageMath.move(transformBase, dx + fix.x, dy + fix.y)
            }
            TransformDrag.SCALE -> CageMath.scaleHandle(transformBase, cage, transformHandle, Vec2(x, y), uniform = true)
            TransformDrag.ROTATE -> {
                val p = FloatArray(2)
                transformBase.forward(cage.centerX, cage.centerY, p)
                val pivot = Vec2(p[0], p[1])
                val deg = Snap.angle(CageMath.dragAngle(pivot, Vec2(transformStartX, transformStartY), Vec2(x, y)))
                CageMath.rotate(transformBase, deg, pivot)
            }
            TransformDrag.NONE -> transformMap
        }
        if (!next.a.isFinite() || !next.d.isFinite() || kotlin.math.abs(next.det) < 1e-6f) return
        transformMap = next
        publishTransformCage()
        previewTransform(force = false)
    }

    private fun transformUp() {
        if (transformSession == null) return
        previewTransform(force = true)
        transformDrag = TransformDrag.NONE
    }

    /**
     * Live preview: the layer holds the "rest" pixels while the session is open, so every preview tile is
     * `rest OVER resampled floating`, written straight into the layer (outside history) and invalidated on
     * the canvas. Throttled to about 30 fps during the drag and always redrawn on finger lift.
     */
    private fun previewTransform(force: Boolean) {
        val session = transformSession ?: return
        val layerId = session.layers.firstOrNull() ?: return
        val layer = doc.findNode(layerId) as? RasterLayer ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPreviewMs < PREVIEW_FRAME_MS) return
        lastPreviewMs = now
        val res = session.render(session.floating.getValue(layerId), transformMap, Resample.BILINEAR, session.mips(layerId))
        val grid = layer.pixels
        val dirty = HashSet<TileKey>()
        for (k in previewKeys.toList()) {
            if (res.containsKey(k)) continue
            previewKeys.remove(k)
            grid.put(k, previewRest.remove(k))
            dirty.add(k)
        }
        for ((k, t) in res) {
            val base = if (k in previewKeys) {
                previewRest[k]
            } else {
                grid.get(k)?.copy().also { previewRest[k] = it }
            }
            previewKeys.add(k)
            grid.put(k, overTile(base, t))
            dirty.add(k)
        }
        if (dirty.isNotEmpty()) canvas?.invalidate(layerId, dirty)
    }

    /** [src] composited over [dst] (premultiplied) as a new tile. */
    private fun overTile(dst: Tile?, src: Tile?): Tile? {
        if (src == null) return dst?.copy()
        if (dst == null) return src.copy()
        val out = dst.copy()
        val s = FloatArray(4)
        val d = FloatArray(4)
        var i = 0
        while (i < Tile.CHANNELS) {
            src.read(i, s)
            if (s[3] > 0f) {
                dst.read(i, d)
                val k = 1f - s[3]
                out.write(i, s[0] + d[0] * k, s[1] + d[1] * k, s[2] + d[2] * k, s[3] + d[3] * k)
            }
            i += 4
        }
        return out
    }

    // ---- gradient tool ----

    /** Layer the open gradient is previewed on, null while the tool is idle. */
    private var gradientLayerId: String? = null

    /** Ends of the open gradient in document pixels; [gradientDragP1] tells which end the drag moves. */
    private var gradientP0 = PointUi(0f, 0f)
    private var gradientP1 = PointUi(0f, 0f)
    private var gradientDragP1 = true

    /** Stops of the open gradient in the order they were added; only core sorts them by position. */
    private val gradientStopList = ArrayList<GradientStopUi>()

    /** Index of the stop the current drag grabbed, -1 while it moves an end instead. */
    private var gradientDragStop = -1

    /** Finger position of the current gradient touch and whether the gesture may still become a tap. */
    private var gradientDownX = 0f
    private var gradientDownY = 0f
    private var gradientTapCandidate = false

    /** Patch the preview has already written into the layer (outside history), null before the first draw. */
    private var gradientPatch: TilePatch? = null

    /** Type of the open gradient and of the next one; the options below are kept for the next gradient too. */
    private var gradientType = GradientType.LINEAR
    private var gradientSpread = Spread.PAD
    private var gradientReverse = false
    private var gradientOpacity = 1f
    private var gradientRepetitions = 1
    private var gradientLastPreviewMs = 0L

    /** Active, unlocked raster layer the gradient can be drawn on. */
    private fun gradientTarget(): RasterLayer? =
        (doc.activeId?.let { doc.findNode(it) } as? RasterLayer)?.takeIf { !it.locked }

    /** Resets the stops of a new gradient: the current color, opaque at 0 and fully transparent at 1. */
    private fun resetGradientStops() {
        val c = _state.value.color
        gradientStopList.clear()
        gradientStopList.add(GradientStopUi(0f, c))
        gradientStopList.add(GradientStopUi(1f, c.copy(a = 0f)))
    }

    /** The session stops as core stops of the current spec. */
    private fun gradientStops(): List<GradientStop> = gradientStopList.map { GradientStop(it.pos, it.color) }

    /** Document point of the axis at [pos] (0..1). */
    private fun gradientPointAt(pos: Float): PointUi = PointUi(
        gradientP0.x + (gradientP1.x - gradientP0.x) * pos,
        gradientP0.y + (gradientP1.y - gradientP0.y) * pos,
    )

    /** Position 0..1 of ([x], [y]) projected on the axis and clamped to the segment. */
    private fun gradientProject(x: Float, y: Float): Float {
        val dx = gradientP1.x - gradientP0.x
        val dy = gradientP1.y - gradientP0.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6f) return 0f
        return (((x - gradientP0.x) * dx + (y - gradientP0.y) * dy) / len2).coerceIn(0f, 1f)
    }

    /** Distance of ([x], [y]) from the axis segment in document pixels. */
    private fun gradientAxisDistance(x: Float, y: Float): Float {
        val dx = gradientP1.x - gradientP0.x
        val dy = gradientP1.y - gradientP0.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-6f) 0f else
            (((x - gradientP0.x) * dx + (y - gradientP0.y) * dy) / len2).coerceIn(0f, 1f)
        return kotlin.math.hypot(x - (gradientP0.x + dx * t), y - (gradientP0.y + dy * t))
    }

    /** Index of the stop drawn under ([x], [y]) within [tol] document pixels, -1 when there is none. */
    private fun gradientStopAt(x: Float, y: Float, tol: Float): Int {
        var best = -1
        var bestD = tol
        for ((i, s) in gradientStopList.withIndex()) {
            val p = gradientPointAt(s.pos)
            val d = kotlin.math.hypot(x - p.x, y - p.y)
            if (d <= bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** Inserts a stop of the current color at [pos], keeping the list ordered, and returns its index. */
    private fun addGradientStop(pos: Float): Int {
        val index = gradientStopList.indexOfFirst { it.pos > pos }.let { if (it < 0) gradientStopList.size else it }
        gradientStopList.add(index, GradientStopUi(pos, _state.value.color))
        return index
    }

    /** Drop distance of a stop in document pixels (48 dp on screen, converted like [handleTolerance]). */
    private fun stopDropDistance(): Float = 48f * densityPx / _state.value.canvasTransform.zoom.coerceAtLeast(0.01f)

    /** Publishes the open gradient so the overlay can draw its axis and stops; null when the session is closed. */
    private fun publishGradient() {
        val layerId = gradientLayerId
        val ui = GradientUi(
            p0 = gradientP0,
            p1 = gradientP1,
            type = gradientType,
            stops = gradientStopList.toList(),
            spread = gradientSpread,
            reverse = gradientReverse,
            opacity = gradientOpacity,
            repetitions = gradientRepetitions,
        )
        _state.update { it.copy(gradient = if (layerId == null) null else ui) }
    }

    override fun setGradientType(type: GradientType) {
        val previous = gradientType
        gradientType = type
        // Spec §4.4: reflect is the default spread of angular gradients.
        if (type == GradientType.ANGULAR && previous != GradientType.ANGULAR) gradientSpread = Spread.REFLECT
        publishGradient()
        renderGradient(force = true)
    }

    override fun setGradientSpread(spread: Spread) {
        gradientSpread = spread
        publishGradient()
        renderGradient(force = true)
    }

    override fun setGradientReverse(reverse: Boolean) {
        gradientReverse = reverse
        publishGradient()
        renderGradient(force = true)
    }

    override fun setGradientOpacity(opacity: Float) {
        gradientOpacity = opacity.coerceIn(0f, 1f)
        publishGradient()
        renderGradient(force = true)
    }

    override fun setGradientRepetitions(n: Int) {
        gradientRepetitions = n.coerceIn(1, 100)
        publishGradient()
        renderGradient(force = true)
    }

    /**
     * Touch down of the gradient tool: the nearer end of the open gradient keeps priority, then one of its
     * stops, then the axis itself (which adds a stop there); anything else starts a new gradient on the active
     * raster layer. An uncommitted gradient is discarded (reverted), never applied.
     */
    /** True while the current gesture holds an end handle; a tap on an end recolors the stop there. */
    private var gradientEndGrab = false

    private fun gradientDown(x: Float, y: Float) {
        val layer = gradientTarget() ?: return
        gradientDownX = x
        gradientDownY = y
        gradientTapCandidate = true
        gradientDragStop = -1
        gradientEndGrab = false
        if (layer.id == gradientLayerId) {
            val tol = handleTolerance()
            val d0 = kotlin.math.hypot(x - gradientP0.x, y - gradientP0.y)
            val d1 = kotlin.math.hypot(x - gradientP1.x, y - gradientP1.y)
            if (minOf(d0, d1) <= tol) {
                gradientDragP1 = d1 <= d0
                gradientEndGrab = true
                return
            }
            val stop = gradientStopAt(x, y, tol)
            if (stop >= 0) {
                gradientDragStop = stop
                return
            }
            if (gradientAxisDistance(x, y) <= tol) {
                gradientDragStop = addGradientStop(gradientProject(x, y))
                publishGradient()
                renderGradient(force = true)
                return
            }
        }
        finishGradient(apply = false)
        gradientLayerId = layer.id
        gradientP0 = PointUi(clampX(x), clampY(y))
        gradientP1 = gradientP0
        gradientDragP1 = true
        resetGradientStops()
        gradientPatch = null
        gradientLastPreviewMs = 0L
        publishGradient()
    }

    /**
     * Moves the grabbed end of the open gradient to ([x], [y]) and redraws the preview. A grabbed stop slides
     * along the axis instead; how far the finger is from the axis only matters on lift in [gradientUp].
     */
    private fun gradientMove(x: Float, y: Float, force: Boolean) {
        if (gradientLayerId == null) return
        if (gradientTapCandidate && kotlin.math.hypot(x - gradientDownX, y - gradientDownY) > handleTolerance()) {
            gradientTapCandidate = false
        }
        val stop = gradientDragStop
        if (stop >= 0) {
            val s = gradientStopList.getOrNull(stop) ?: return
            gradientStopList[stop] = s.copy(pos = gradientProject(x, y))
            publishGradient()
            renderGradient(force)
            return
        }
        val p = PointUi(clampX(x), clampY(y))
        if (gradientDragP1) gradientP1 = p else gradientP0 = p
        publishGradient()
        renderGradient(force)
    }

    /**
     * Finger lift of the gradient tool: a tap on a stop takes the current color, and a stop that ends more than
     * 48 dp off the axis is dropped, but at least two of them always remain.
     */
    private fun gradientUp(x: Float, y: Float) {
        if (gradientLayerId == null) {
            gradientDragStop = -1
            gradientTapCandidate = false
            return
        }
        val stop = gradientDragStop
        val tap = gradientTapCandidate && kotlin.math.hypot(x - gradientDownX, y - gradientDownY) <= handleTolerance()
        gradientMove(x, y, force = true)
        if (stop in gradientStopList.indices) {
            val far = gradientAxisDistance(x, y) > stopDropDistance()
            when {
                tap -> gradientStopList[stop] = gradientStopList[stop].copy(color = _state.value.color)
                far && gradientStopList.size > 2 -> gradientStopList.removeAt(stop)
            }
        }
        if (tap && gradientEndGrab && gradientStopList.isNotEmpty()) {
            // A tap on an end recolors the outermost stop on that side.
            val i = gradientStopList.indices.let { ids ->
                if (gradientDragP1) ids.maxBy { gradientStopList[it].pos } else ids.minBy { gradientStopList[it].pos }
            }
            gradientStopList[i] = gradientStopList[i].copy(color = _state.value.color)
        }
        gradientEndGrab = false
        gradientDragStop = -1
        gradientTapCandidate = false
        gradientDragP1 = true
        publishGradient()
        renderGradient(force = true)
    }

    /**
     * Live preview: reverts the tiles of the previous preview and draws the gradient again with the session
     * stops, type and options straight into the layer (outside history). Throttled to about 30 fps while
     * dragging, and always redrawn on finger lift.
     */
    private fun renderGradient(force: Boolean) {
        val layerId = gradientLayerId ?: return
        val layer = doc.findNode(layerId) as? RasterLayer
        if (layer == null) {
            finishGradient(apply = false)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!force && now - gradientLastPreviewMs < PREVIEW_FRAME_MS) return
        gradientLastPreviewMs = now
        // A zero-length axis (a tap) has no direction: keep the layer untouched until the finger moves.
        if (kotlin.math.hypot(gradientP1.x - gradientP0.x, gradientP1.y - gradientP0.y) < 1f) return
        val old = gradientPatch
        if (old != null) applyTiles(layer.pixels, old.before)
        val spec = GradientSpec(
            type = gradientType,
            x0 = gradientP0.x,
            y0 = gradientP0.y,
            x1 = gradientP1.x,
            y1 = gradientP1.y,
            stops = gradientStops(),
            spread = gradientSpread,
            reverse = gradientReverse,
            repetitions = gradientRepetitions,
        )
        val patch = Gradient.apply(doc, layerId, spec, gradientOpacity, null)
        gradientPatch = patch
        val dirty = HashSet<TileKey>()
        old?.let { dirty.addAll(it.before.keys); dirty.addAll(it.after.keys) }
        patch?.let { dirty.addAll(it.after.keys) }
        canvas?.invalidate(layerId, dirty)
    }

    /**
     * Closes the gradient session. [apply] records the drawn patch as one undoable "Gradient" step (its pixels
     * already are in the layer); otherwise the tiles are restored without a history entry.
     */
    private fun finishGradient(apply: Boolean) {
        val layerId = gradientLayerId ?: return
        val patch = gradientPatch
        gradientLayerId = null
        gradientPatch = null
        gradientDragP1 = true
        gradientDragStop = -1
        gradientTapCandidate = false
        gradientStopList.clear()
        gradientLastPreviewMs = 0L
        if (patch != null) {
            if (apply) history.push(TilePatchCommand(patch, "Gradient"))
            else (doc.findNode(layerId) as? RasterLayer)?.let { applyTiles(it.pixels, patch.before) }
        }
        publishGradient()
        canvas?.invalidate(layerId, null)
    }

    override fun gradientApply() = finishGradient(apply = true)

    override fun gradientCancel() = finishGradient(apply = false)

    // ---- shape tool ----

    /** Type of shape a new draft creates; kept for the next draft of the tool. */
    private var shapeTool = ShapeTool.LINE

    /** Draws the brush outline of a draft; turned off, only the fill of a closed shape is painted. */
    private var shapeOutlineOn = true

    /** Fills closed drafts with the current color. */
    private var shapeFillOn = false

    /** Vertices of a new polygon draft (spec G3.1). */
    private val shapeSides = 5

    /** Draft the shape tool edits, null while the tool is idle. */
    private var shapeDraft: Shape? = null

    /** Layer the draft is painted on. */
    private var shapeLayerId: String? = null

    /** What the shape gesture in progress does. */
    private enum class ShapeDrag { NONE, CREATE, HANDLE, CONTOUR }

    private var shapeDrag = ShapeDrag.NONE

    /** Origin of the drag that creates a draft, document pixels. */
    private var shapeFrom = Vec2(0f, 0f)

    /** Handle grabbed by [ShapeDrag.HANDLE]; its position is re-read from the draft on every move. */
    private var shapeHandle: ShapeHandle? = null

    /** Finger position of the previous move, document pixels: a contour drag moves by its delta. */
    private var shapeLast = Vec2(0f, 0f)

    /** Time of the last preview of the draft; drags are throttled like the other live previews. */
    private var shapeLastPreviewMs = 0L

    /** Active, unlocked raster layer the shape tool paints on. */
    private fun shapeTarget(): RasterLayer? =
        (doc.activeId?.let { doc.findNode(it) } as? RasterLayer)?.takeIf { !it.locked }

    /** Document pixels per screen pixel at the current zoom. */
    private fun shapeScreenToDoc(): Float = 1f / _state.value.canvasTransform.zoom.coerceAtLeast(0.01f)

    /** The fill of [d] is on: the toggle is set and the shape is closed. */
    private fun shapeFilled(d: Shape): Boolean = shapeFillOn && d.closed

    /** The brush outline of [d] is drawn; the fill alone never hides it. */
    private fun shapeOutlined(d: Shape): Boolean = shapeOutlineOn || !shapeFilled(d)

    /** Publishes the draft (outline, handles and flags) to the overlay; null when the tool is idle. */
    private fun publishShape() {
        val d = shapeDraft
        val ui = if (d == null) null else ShapeUi(
            outline = d.outline().map { PointUi(it.x, it.y) },
            handles = ShapeHandles.handles(d, shapeScreenToDoc()).map { PointUi(it.pos.x, it.pos.y) },
            closed = d.closed,
            outlineOn = shapeOutlined(d),
            fillOn = shapeFilled(d),
        )
        _state.update { it.copy(shape = ui) }
    }

    /** Cancels the preview engine of the draft and takes its pixels off the canvas. */
    private fun cancelShapePreview() {
        val engine = stroke ?: return
        stroke = null
        engine.cancel()
        val layerId = shapeLayerId ?: return
        if (engine.directToLayer) canvas?.invalidate(layerId, null)
        else canvas?.setStrokePreview(layerId, null, false, 1f)
    }

    /**
     * Live preview of the draft: the engine of the previous frame is cancelled and the shape is painted again
     * with a fresh [StrokeEngine] (a buffered stroke shows its buffer over the layer, a build-up brush paints the
     * layer directly and is restored by cancel). Throttled to about 30 fps while dragging and always redrawn on
     * finger lift. The brush only outlines the shape — the fill is rasterised from the stroke path in `end()` —
     * so a draft with the outline turned off is painted with a zero-flow brush, whose dabs never reach the target.
     */
    private fun renderShape(force: Boolean) {
        val d = shapeDraft ?: return
        val layerId = shapeLayerId
        if (layerId == null) {
            publishShape()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!force && now - shapeLastPreviewMs < PREVIEW_FRAME_MS) return
        shapeLastPreviewMs = now
        publishShape()
        cancelShapePreview()
        val points = ShapeStroke.points(d)
        if (points.isEmpty()) return
        val s = _state.value
        val mode = if (s.mode == BrushModeUi.ERASE) StrokeMode.ERASE else StrokeMode.PAINT
        val params = if (shapeOutlined(d)) currentParams() else currentParams().copy(flow = 0f)
        val brush = strokeBrush(params)
        val vs = canvas?.viewState?.value
        val ctx = StrokeContext(
            zoom = vs?.zoom ?: 1f,
            viewRotation = Math.toRadians((vs?.rotationDeg ?: 0f).toDouble()).toFloat(),
            seed = HashRng.strokeSeed(strokeCounter++, STUDIO_BRUSH_ID),
            threads = STROKE_THREADS,
        )
        // Symmetry copies are fixed when the draft is drawn, like at the start of a stroke (spec G4).
        val first = points.first()
        val copies = if (guides.symmetry.on) guides.copies(Vec2(first.x, first.y)) else SymmetryCopies.NONE
        val fill = if (shapeFilled(d)) FillSpec(1f) else null
        val engine = StrokeEngine(
            doc, StrokeTarget.Layer(layerId), brush, brushes.resources(brush), s.color, mode, ctx,
            ShapeStroke.options(d, fill = fill, copies = copies),
        )
        stroke = engine
        engine.begin(points.first())
        engine.add(points.drop(1))
        if (engine.directToLayer) {
            canvas?.invalidate(layerId, engine.takeDirty())
        } else {
            canvas?.setStrokePreview(
                layerId, engine.buffer, mode == StrokeMode.ERASE, engine.previewOpacity,
                engine.previewBlend, engine.previewBlendStrength,
                // Wet edges and adhesion (§1.7) are per pixel: upload the processed preview, not the raw buffer.
                if (engine.previewNeedsProcessing) engine::previewTile else null,
            )
            canvas?.invalidateStroke(engine.takeDirty())
        }
    }

    /**
     * Ends the preview engine and records its patch as one undoable "Shape" step (the pixels already are in the
     * layer); returns false when nothing was drawn.
     */
    private fun endShapePreview(): Boolean {
        val engine = stroke ?: return false
        stroke = null
        val layerId = shapeLayerId
        val commit = engine.end()
        if (layerId != null && !engine.directToLayer) canvas?.setStrokePreview(layerId, null, false, 1f)
        val patch = (commit as? StrokeCommit.Pixels)?.patch
        if (patch == null) {
            if (engine.directToLayer) canvas?.invalidate(layerId, null)
            return false
        }
        history.push(TilePatchCommand(patch, "Shape"))
        canvas?.invalidate(patch.layerId, patch.after.keys)
        return true
    }

    /**
     * Closes the shape session: [commit] keeps the pixels of the draft as one undoable step, otherwise the
     * preview is rolled back without a history entry. The draft itself is always dropped.
     */
    private fun finishShape(commit: Boolean) {
        if (shapeDraft == null && shapeLayerId == null) return
        if (commit) endShapePreview() else cancelShapePreview()
        shapeDraft = null
        shapeLayerId = null
        shapeDrag = ShapeDrag.NONE
        shapeHandle = null
        shapeFrom = Vec2(0f, 0f)
        shapeLast = Vec2(0f, 0f)
        shapeLastPreviewMs = 0L
        publishShape()
    }

    /**
     * Touch down of the shape tool: a handle of the open draft is grabbed, a touch on its contour moves the whole
     * shape, and any other touch commits the draft and starts a new one from the touch point.
     */
    private fun shapeDown(x: Float, y: Float) {
        val layer = shapeTarget() ?: return
        val p = Vec2(clampX(x), clampY(y))
        shapeHandle = null
        shapeLast = p
        shapeDrag = ShapeDrag.NONE
        val d = shapeDraft
        if (d != null && layer.id == shapeLayerId) {
            when (val hit = ShapeHandles.hitTest(d, p, shapeScreenToDoc())) {
                is ShapeHit.OnHandle -> {
                    shapeHandle = hit.handle
                    shapeDrag = ShapeDrag.HANDLE
                    return
                }
                ShapeHit.OnContour -> {
                    shapeDrag = ShapeDrag.CONTOUR
                    return
                }
                null -> Unit
            }
        }
        // The touch starts a new draft; an open one is applied first, as one undo step of its own.
        if (shapeDraft != null) finishShape(commit = true)
        shapeLayerId = layer.id
        shapeFrom = p
        shapeDraft = ShapeFactory.fromDrag(shapeTool, p, p, sides = shapeSides)
        shapeDrag = ShapeDrag.CREATE
        renderShape(force = true)
    }

    /** Drags the grabbed handle, moves the draft over the canvas or rebuilds it from the origin of the drag. */
    private fun shapeMove(x: Float, y: Float, force: Boolean) {
        val d = shapeDraft ?: return
        val p = Vec2(clampX(x), clampY(y))
        val next = when (shapeDrag) {
            ShapeDrag.CREATE -> ShapeFactory.fromDrag(shapeTool, shapeFrom, p, sides = shapeSides)
            ShapeDrag.HANDLE -> {
                // The handle rides along with the shape, so its position comes from the draft of the last frame.
                val kind = shapeHandle?.kind ?: return
                val index = shapeHandle?.index ?: 0
                val all = ShapeHandles.handles(d, shapeScreenToDoc())
                val h = all.firstOrNull { it.kind == kind && it.index == index }
                if (h == null) d else ShapeHandles.drag(d, h, p)
            }
            ShapeDrag.CONTOUR -> ShapeHandles.move(d, Vec2(p.x - shapeLast.x, p.y - shapeLast.y))
            ShapeDrag.NONE -> return
        }
        shapeLast = p
        shapeDraft = next
        renderShape(force)
    }

    /** Finger lift: the draft stays open and is redrawn without the frame throttle. */
    private fun shapeUp(x: Float, y: Float) {
        if (shapeDraft == null) return
        shapeMove(x, y, force = true)
        shapeDrag = ShapeDrag.NONE
        shapeHandle = null
    }

    override fun selectShapeTool(tool: ShapeTool) {
        shapeTool = tool
        selectTool(ToolUi.SHAPE)
    }

    override fun shapeCommit() = finishShape(commit = true)

    override fun shapeCancel() = finishShape(commit = false)

    /** Stamps a copy of the draft and keeps the draft open for the next copy. */
    override fun shapeStamp() {
        if (shapeDraft == null) return
        endShapePreview()
        renderShape(force = true)
    }

    override fun setShapeOutline(on: Boolean) {
        shapeOutlineOn = on
        if (shapeDraft != null) renderShape(force = true) else publishShape()
    }

    override fun setShapeFill(on: Boolean) {
        shapeFillOn = on
        if (shapeDraft != null) renderShape(force = true) else publishShape()
    }

    /** Bucket settings (spec §4.3): 15 % tolerance over the canvas, gap closing 4 px, expansion 1.5 px. */
    private val bucketParams = RegionParams(tolerance = 0.15f, contiguous = true, gapPx = 4, expandPx = 1.5f)

    /** The latest fill while it is the top history entry; color changes recolor it in place (spec §4.3). */
    private var lastFill: FillCommand? = null

    /** Fills the region at ([x], [y]) of the canvas on the active layer; the region is computed off the main thread. */
    private fun bucketAt(x: Float, y: Float) {
        val d = doc
        val layerId = (d.activeId?.let { d.findNode(it) } as? RasterLayer)?.takeIf { !it.locked }?.id ?: return
        val px = x.toInt().coerceIn(0, d.width - 1)
        val py = y.toInt().coerceIn(0, d.height - 1)
        val color = _state.value.color
        viewModelScope.launch {
            val cov = withContext(Dispatchers.Default) { Region.compute(d, RefSource.Canvas, px, py, bucketParams) }
            if (d !== doc || cov == null || stroke != null) return@launch
            val cmd = BucketFill.command(d, layerId, cov, color, 1f, erase = false) ?: return@launch
            commitPendingOpacity()
            history.push(cmd)
            lastFill = cmd
            canvas?.invalidate(layerId, null)
        }
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
        saveBrushPrefs()
        val p = currentParams()
        val zoom = _state.value.viewZoom
        showHud(HudUi("Size", "${p.size.toInt()} px", p.size * zoom, p.sizeMax * zoom))
    }

    override fun setOpacity(opacity: Float) {
        updateParams { it.copy(opacity = opacity.coerceIn(0f, 1f)) }
        saveBrushPrefs()
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
        prefs?.color = color
        val fill = lastFill
        if (fill != null && _state.value.tool == ToolUi.FILL) {
            fill.recolor(doc, color)
            canvas?.invalidate(fill.layerId, null)
            markDirty()
        }
    }

    override fun addColorToPalette(color: Rgba) {
        _state.update { if (it.palette.contains(color)) it else it.copy(palette = it.palette + color) }
        prefs?.palette = _state.value.palette
    }

    // ---- history ----

    override fun undo() {
        // An open shape draft has pixels only in its preview: undo drops it instead of a history step.
        if (shapeDraft != null) {
            finishShape(commit = false)
            return
        }
        if (transformSession != null || gradientLayerId != null) return
        commitPendingOpacity()
        if (history.undo(doc)) canvas?.invalidate(null, null)
    }

    override fun redo() {
        if (shapeDraft != null) return
        if (transformSession != null || gradientLayerId != null) return
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
        finishShape(commit = true)
        finishTransform(apply = true)
        finishGradient(apply = true)
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

    // ---- guides ----

    /** Symmetry published to the overlay, null while it is off. */
    private fun symmetryUi(): SymmetryUi? = guides.symmetry.toUi()

    /** View state of a symmetry config; null when the symmetry is off. */
    private fun SymmetryConfig.toUi(): SymmetryUi? =
        if (!on) null else SymmetryUi(type, axes, center.x, center.y, angleDeg, effectivePlanes, crop, locked)

    /** Ruler published to the overlay, null while it is off. */
    private fun rulerUi(): RulerUi? = guides.lines.toUi()

    /** View state of a ruler config; null when the ruler is off. */
    private fun LinesConfig.toUi(): RulerUi? =
        if (!on) null else RulerUi(a.x, a.y, b.x, b.y, perpendicular, magnet, locked)

    /** Ellipse published to the overlay, null while it is off. */
    private fun ellipseUi(): EllipseUi? = guides.ellipse.toUi()

    /** View state of an ellipse config; null when the ellipse is off. */
    private fun EllipseConfig.toUi(): EllipseUi? =
        if (!on) null else EllipseUi(c.x, c.y, rx, rx * ratio, angleDeg, magnet, locked)

    /** Lazy rope published to the bar, null while it is off. */
    private fun lazyUi(): LazyUi? = guides.lazy.toUi()

    /** View state of a lazy rope config; null when the lazy rope is off. */
    private fun LazyConfig.toUi(): LazyUi? = if (!on) null else LazyUi(radius)

    /** Perspective grid published to the overlay, null while it is off. */
    private fun perspectiveUi(): PerspectiveUi? = guides.perspective.toUi()

    /**
     * View state of a perspective config; null when the grid is off. The overlay [PerspectiveUi.lines] are
     * generated for the canvas at the current zoom, so long line families are thinned like on screen.
     */
    private fun PerspectiveConfig.toUi(): PerspectiveUi? = if (!on) null else PerspectiveUi(
        type = type,
        vp = vp.map { PointUi(it.x, it.y) },
        snap = snap,
        visible = visible,
        locked = locked,
        lines = PerspectiveOverlay.lines(
            cfg = this,
            view = DocRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat()),
            screenToDoc = 1f / _state.value.canvasTransform.zoom,
        ).map { OverlayLineUi(it.a.x, it.a.y, it.b.x, it.b.y, it.role) },
    )

    /**
     * Applies [newGuides] as view state: guides are not undoable, so they go straight into [guides], the
     * manifest block of [doc] and the published state.
     */
    private fun applyGuides(newGuides: GuideState) {
        guides = newGuides
        doc.guides = GuideJson.toElement(guides) as JsonObject
        markDirty()
        _state.update {
            it.copy(
                symmetry = symmetryUi(),
                ruler = rulerUi(),
                ellipse = ellipseUi(),
                lazy = lazyUi(),
                perspective = perspectiveUi(),
            )
        }
    }

    override fun setSymmetry(type: SymmetryType?, axes: MirrorAxes) {
        val current = guides.symmetry
        applyGuides(guides.copy(symmetry = current.copy(on = type != null, type = type ?: current.type, axes = axes)))
    }

    override fun setSymmetryPlanes(n: Int) {
        val current = guides.symmetry
        if (current.type == SymmetryType.MIRROR) return
        val clamped = current.copy(planes = n.coerceIn(2, SymmetryConfig.MAX_COPIES))
        applyGuides(guides.copy(symmetry = clamped.copy(planes = clamped.effectivePlanes)))
    }

    override fun setSymmetryLocked(locked: Boolean) =
        applyGuides(guides.copy(symmetry = guides.symmetry.copy(locked = locked)))

    override fun setSymmetryCrop(crop: Boolean) = applyGuides(guides.copy(symmetry = guides.symmetry.copy(crop = crop)))

    override fun moveSymmetryCenter(x: Float, y: Float) {
        if (!guides.symmetry.on) return
        applyGuides(guides.copy(symmetry = guides.symmetry.copy(center = Vec2(clampX(x), clampY(y)))))
    }

    override fun setSymmetryAngle(deg: Float) {
        if (!guides.symmetry.on) return
        applyGuides(guides.copy(symmetry = guides.symmetry.copy(angleDeg = snapAngle(deg))))
    }

    /** Normalizes [deg] to 0–360 and snaps it to a multiple of 45° when it is within 3° of one. */
    private fun snapAngle(deg: Float): Float {
        val norm = ((deg % 360f) + 360f) % 360f
        val snapped = (Math.round(norm / 45f) * 45f).toFloat()
        return if (kotlin.math.abs(snapped - norm) <= 3f) snapped % 360f else norm
    }

    // ---- ruler and lazy rope ----

    override fun setLazy(on: Boolean) = applyGuides(guides.copy(lazy = guides.lazy.copy(on = on)))

    override fun setLazyRadius(radius: Float) =
        applyGuides(guides.copy(lazy = guides.lazy.copy(radius = radius.coerceIn(0f, MAX_LAZY_RADIUS))))

    override fun setRuler(on: Boolean) {
        var lines = guides.lines
        // A ruler that was never placed starts as a horizontal segment across the canvas (spec G2.1).
        if (on && lines.a == LinesConfig().a && lines.b == LinesConfig().b) {
            lines = LinesConfig.defaultFor(DocRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat()))
        }
        applyGuides(guides.copy(lines = lines.copy(on = on)))
    }

    override fun moveRulerEnd(end: Int, x: Float, y: Float) {
        val lines = guides.lines
        if (!lines.on) return
        val p = Vec2(clampX(x), clampY(y))
        applyGuides(guides.copy(lines = if (end == 0) lines.copy(a = p) else lines.copy(b = p)))
    }

    override fun moveRuler(dx: Float, dy: Float) {
        val lines = guides.lines
        if (!lines.on) return
        val moved = lines.copy(a = Vec2(lines.a.x + dx, lines.a.y + dy), b = Vec2(lines.b.x + dx, lines.b.y + dy))
        applyGuides(guides.copy(lines = moved))
    }

    override fun setRulerPerpendicular(on: Boolean) =
        applyGuides(guides.copy(lines = guides.lines.copy(perpendicular = on)))

    override fun setRulerMagnet(on: Boolean) = applyGuides(guides.copy(lines = guides.lines.copy(magnet = on)))

    override fun setRulerLocked(on: Boolean) = applyGuides(guides.copy(lines = guides.lines.copy(locked = on)))

    override fun setEllipseGuide(on: Boolean) {
        var ellipse = guides.ellipse
        // An ellipse that was never placed starts as a circle in the middle of the canvas (spec G2.2).
        if (on && ellipse.c == EllipseConfig().c && ellipse.rx == EllipseConfig().rx) {
            ellipse = EllipseConfig.defaultFor(DocRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat()))
        }
        applyGuides(guides.copy(ellipse = ellipse.copy(on = on)))
    }

    override fun moveEllipse(dx: Float, dy: Float) {
        val ellipse = guides.ellipse
        if (!ellipse.on) return
        applyGuides(guides.copy(ellipse = ellipse.copy(c = Vec2(ellipse.c.x + dx, ellipse.c.y + dy))))
    }

    override fun setEllipseRadii(rx: Float, ry: Float) {
        val ellipse = guides.ellipse
        if (!ellipse.on) return
        val r = maxOf(rx, 4f)
        // Kept above MIN_RATIO so the minor axis never collapses on the major one (spec G2.2).
        val ratio = (ry / r).coerceAtLeast(EllipseConfig.MIN_RATIO)
        applyGuides(guides.copy(ellipse = ellipse.copy(rx = r, ratio = ratio)))
    }

    override fun setEllipseAngle(deg: Float) {
        if (!guides.ellipse.on) return
        applyGuides(guides.copy(ellipse = guides.ellipse.copy(angleDeg = snapAngle(deg))))
    }

    override fun setEllipseMagnet(on: Boolean) = applyGuides(guides.copy(ellipse = guides.ellipse.copy(magnet = on)))

    override fun setEllipseLocked(on: Boolean) = applyGuides(guides.copy(ellipse = guides.ellipse.copy(locked = on)))

    // ---- perspective ----

    override fun setPerspective(type: PerspectiveType?) {
        if (type == null) {
            applyGuides(guides.copy(perspective = guides.perspective.copy(on = false)))
            return
        }
        // A grid is always placed from the defaults of the canvas (spec G5): the previous points are dropped.
        applyGuides(guides.copy(perspective = PerspectiveConfig.defaults(type, doc.width, doc.height)))
    }

    override fun moveVanishingPoint(index: Int, x: Float, y: Float) {
        val cfg = guides.perspective
        if (!cfg.on || index !in cfg.vp.indices) return
        // Vanishing points are not clamped: they may be dragged outside the canvas.
        val vp = cfg.vp.toMutableList()
        vp[index] = Vec2(x, y)
        applyGuides(guides.copy(perspective = cfg.copy(vp = vp)))
    }

    override fun setPerspectiveSnap(on: Boolean) =
        applyGuides(guides.copy(perspective = guides.perspective.copy(snap = on)))

    override fun setPerspectiveVisible(on: Boolean) =
        applyGuides(guides.copy(perspective = guides.perspective.copy(visible = on)))

    override fun setPerspectiveLocked(on: Boolean) =
        applyGuides(guides.copy(perspective = guides.perspective.copy(locked = on)))

    // ---- options ----

    override fun openHome() {
        save()
        homeRequest.value = true
    }

    override fun save() {
        finishShape(commit = true)
        finishTransform(apply = true)
        finishGradient(apply = true)
        save(refreshList = true)
    }

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
            // A stroke, a transform session or a gradient preview may already have touched layer tiles and
            // hold pixels outside history: save only once all of them are done.
            while (stroke != null || transformSession != null || gradientLayerId != null) delay(AUTOSAVE_DELAY_MS)
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

    override fun openEditingTools() {}
    override fun openOptions() {}

    // ---- helpers ----

    private fun exec(cmd: Command) {
        finishShape(commit = true)
        finishTransform(apply = true)
        finishGradient(apply = true)
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
        when (_state.value.mode) {
            BrushModeUi.ERASE -> eraseParams
            BrushModeUi.BLEND -> blendParams
            BrushModeUi.PAINT -> paintParams
        }

    private fun updateParams(f: (BrushParams) -> BrushParams) {
        when (_state.value.mode) {
            BrushModeUi.ERASE -> eraseParams = f(eraseParams)
            BrushModeUi.BLEND -> blendParams = f(blendParams)
            BrushModeUi.PAINT -> paintParams = f(paintParams)
        }
        syncBrushState()
    }

    private fun syncBrushState() {
        val p = currentParams()
        _state.update {
            it.copy(
                brushSize = p.size,
                brushSizeMax = p.sizeMax,
                opacity = p.opacity,
                flow = p.flow,
                hardness = p.hardness,
                currentBrushId = currentBrush?.id,
            )
        }
    }

    // ---- persisted tool settings ----

    // ---- stylus input: the calibration of every device and the stylus settings (spec/tools/m2-brush.md §4.3) ----
    /** Loads the per-device stylus calibration and the stylus settings saved by [saveInputSettings] at start-up. */
    private fun restoreInputSettings() {
        val s = input ?: return
        val stored = s.load()
        calibrations.putAll(stored.devices)
        _stylusSettings.value = stored.stylus
        _state.update { it.copy(stylusSettings = stored.stylus) }
        // The stored curve preset is the source of truth for the pressure curve of every device (§4.3).
        stored.stylus.applyTo(calibrations.values)
        panelDevice = stored.devices.keys.firstOrNull()
        publishCalibration()
    }

    /**
     * Replaces the stylus settings (spec/tools/m2-brush.md §4.3): the pen buttons, the finger rule and the palm
     * mode reach the router at once, the curve preset is written into the calibration of every known device and
     * the result is saved for the next session. Called by the stylus settings panel.
     */
    override fun setStylusSettings(settings: StylusSettings) {
        _stylusSettings.value = settings
        _state.update { it.copy(stylusSettings = settings) }
        settings.applyTo(calibrations.values)
        canvas?.stylusSettings = settings
        saveInputSettings()
    }

    /**
     * Device whose calibration the stylus panel reports: the last pen that drew or was calibrated. Null until
     * the first pen stroke, when the panel shows the defaults.
     */
    private var panelDevice: String? = null

    /**
     * Fixes the pressure range the calibration wizard found (spec/tools/m2-brush.md §4.3) and saves it: auto
     * off, so the range holds until [resetCalibration]. A null [descriptor] means the pad could not read an
     * `InputDevice`; the range then goes to every known pen, which is all that is left to identify it by.
     */
    override fun applyCalibration(descriptor: String?, min: Float, max: Float) {
        if (descriptor == null && calibrations.isEmpty()) calibrationFor(CanvasInputListener.DEVICE_DEFAULT)
        val targets: Collection<InputCalibration> =
            if (descriptor == null) calibrations.values else listOf(calibrationFor(descriptor))
        targets.forEach { it.setManual(min, max) }
        panelDevice = descriptor ?: panelDevice ?: calibrations.keys.firstOrNull()
        publishCalibration()
        saveInputSettings()
    }

    /** "Reset to auto" (spec §4.3): every known pen back to the full automatic range, then saved. */
    override fun resetCalibration() {
        calibrations.values.forEach { it.resetToAuto() }
        publishCalibration()
        saveInputSettings()
    }

    /** Publishes the calibration of [panelDevice] to the stylus panel; the defaults while no pen is known. */
    private fun publishCalibration() {
        val device = panelDevice?.let { calibrations[it] }
        val ui = if (device == null) {
            CalibrationUi()
        } else {
            CalibrationUi(device.auto, device.minPressure, device.maxPressure)
        }
        _state.update { it.copy(calibration = ui) }
    }

    /** Calibration of input device [descriptor]; a device seen for the first time starts from the defaults. */
    private fun calibrationFor(descriptor: String): InputCalibration =
        calibrations.getOrPut(descriptor) { InputCalibration() }

    /** Writes the calibration of the device that drew the finished stylus stroke (spec §4.4), off the main thread. */
    private fun saveInputSettingsAfterStroke() {
        if (strokeDevice == null) return
        strokeDevice = null
        saveInputSettings()
    }

    /** Writes the calibrations and the stylus settings of the session to `input.json` (spec §4.4). */
    private fun saveInputSettings() {
        val settings = input ?: return
        val stored = InputSettings.Stored(calibrations.toMap(), _stylusSettings.value)
        viewModelScope.launch(Dispatchers.IO) { ioLock.withLock { settings.save(stored) } }
    }

    /** Restores the color and the palette at start-up; the per-mode brushes follow in [loadBrushes]. */
    private fun restoreToolPrefs() {
        val p = prefs ?: return
        val saved = p.palette
        _state.update { it.copy(color = p.color, palette = saved.ifEmpty { it.palette }) }
    }

    /**
     * Applies the saved preset and the size/opacity the user set to every brush mode. A preset id the
     * catalog no longer has (the brush set changed between releases) is dropped silently, so the mode keeps
     * the default preset it was seeded with.
     */
    private fun restoreBrushPrefs() {
        val p = prefs ?: return
        p.brushId(BrushModeUi.PAINT)?.let { id -> brushes.find(id)?.let { paintBrush = it; paintParams = it.params } }
        p.brushId(BrushModeUi.ERASE)?.let { id -> brushes.find(id)?.let { eraseBrush = it; eraseParams = it.params } }
        paintParams = paintParams.withSaved(p.brushSize(BrushModeUi.PAINT), p.brushOpacity(BrushModeUi.PAINT))
        eraseParams = eraseParams.withSaved(p.brushSize(BrushModeUi.ERASE), p.brushOpacity(BrushModeUi.ERASE))
        blendParams = blendParams.withSaved(p.brushSize(BrushModeUi.BLEND), p.brushOpacity(BrushModeUi.BLEND))
    }

    /** Writes the preset, size and opacity of the current mode to [prefs]. */
    private fun saveBrushPrefs() {
        val p = prefs ?: return
        val params = currentParams()
        p.saveBrush(_state.value.mode, currentBrush?.id, params.size, params.opacity)
    }

    /** [BrushParams] with the user's saved size/opacity applied on top of the preset, clamped to its limits. */
    private fun BrushParams.withSaved(savedSize: Float?, savedOpacity: Float?): BrushParams = copy(
        size = (savedSize ?: size).coerceIn(sizeMin, sizeMax),
        opacity = (savedOpacity ?: opacity).coerceIn(0f, 1f),
    )

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
        publishSelectionContour(coverage)
        val b = coverage?.bounds()
        _state.update {
            it.copy(
                hasSelection = coverage != null,
                selectionBounds = b?.let { r -> RectUi(r.x0.toFloat(), r.y0.toFloat(), r.x1.toFloat(), r.y1.toFloat()) },
            )
        }
    }

    /**
     * Publishes the marching-ants outline of [coverage] (iso-0.5 contour segments per mask tile, document
     * pixels) so the overlay can draw the real selection shape. Cached by tile version/identity: the
     * contour is only recomputed when the mask content changed, and a newer pass cancels an older one.
     */
    private fun publishSelectionContour(coverage: MaskGrid?) {
        if (coverage == null) {
            contourJob?.cancel()
            contourJob = null
            contourSig = null
            contourRev++
            if (_state.value.selectionContour != null) _state.update { it.copy(selectionContour = null) }
            return
        }
        val sig = selectionFingerprint(coverage)
        if (sig == contourSig) return
        contourSig = sig
        contourRev++
        val rev = contourRev
        contourJob?.cancel()
        // The bounding-box fallback stays visible until the real outline is ready.
        if (_state.value.selectionContour != null) _state.update { it.copy(selectionContour = null) }
        contourJob = viewModelScope.launch {
            val contours = withContext(Dispatchers.Default) {
                val keys = coverage.keys().filter { coverage.isInBounds(it.tx, it.ty) }.sortedBy { it.packed }
                val parts = ArrayList<FloatArray>(keys.size)
                var total = 0
                for (k in keys) {
                    if (!isActive) return@withContext null
                    val c = MaskOps.contour(coverage, k)
                    if (c.isNotEmpty()) { parts.add(c); total += c.size }
                }
                if (parts.isEmpty()) return@withContext emptyList()
                val flat = FloatArray(total)
                var at = 0
                for (p in parts) { p.copyInto(flat, at); at += p.size }
                chainSegments(flat)
            } ?: return@launch
            if (rev != contourRev || contours.isEmpty()) return@launch
            _state.update { it.copy(selectionContour = contours) }
        }
    }

    /**
     * Joins marching-squares segments (`x0, y0, x1, y1` quadruples in [flat]) into open polylines so the
     * dashed outline can march along the edge instead of restarting on every one-pixel segment.
     */
    private fun chainSegments(flat: FloatArray): List<FloatArray> {
        val n = flat.size / 4
        if (n == 0) return emptyList()
        val ids = HashMap<Long, Int>(n * 2)
        val xs = ArrayList<Float>(n * 2)
        val ys = ArrayList<Float>(n * 2)
        fun pointId(x: Float, y: Float): Int {
            val key = (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xFFFF_FFFFL)
            ids[key]?.let { return it }
            xs.add(x)
            ys.add(y)
            val id = xs.size - 1
            ids[key] = id
            return id
        }
        val a = IntArray(n)
        val b = IntArray(n)
        val adj = HashMap<Int, MutableList<Int>>(n * 2)
        for (i in 0 until n) {
            a[i] = pointId(flat[i * 4], flat[i * 4 + 1])
            b[i] = pointId(flat[i * 4 + 2], flat[i * 4 + 3])
            adj.getOrPut(a[i]) { ArrayList(2) }.add(i)
            if (b[i] != a[i]) adj.getOrPut(b[i]) { ArrayList(2) }.add(i)
        }
        val used = BooleanArray(n)
        val out = ArrayList<FloatArray>()
        for (s in 0 until n) {
            if (used[s]) continue
            used[s] = true
            val fwd = ArrayList<Int>()
            fwd.add(a[s])
            fwd.add(b[s])
            while (true) {
                val tail = fwd[fwd.size - 1]
                val next = adj[tail].orEmpty().firstOrNull { !used[it] } ?: break
                used[next] = true
                fwd.add(if (a[next] == tail) b[next] else a[next])
            }
            val back = ArrayList<Int>()
            while (true) {
                val head = if (back.isEmpty()) fwd[0] else back[back.size - 1]
                val next = adj[head].orEmpty().firstOrNull { !used[it] } ?: break
                used[next] = true
                back.add(if (a[next] == head) b[next] else a[next])
            }
            val poly = FloatArray((fwd.size + back.size) * 2)
            var at = 0
            for (i in back.indices.reversed()) {
                poly[at++] = xs[back[i]]
                poly[at++] = ys[back[i]]
            }
            for (p in fwd) {
                poly[at++] = xs[p]
                poly[at++] = ys[p]
            }
            out.add(poly)
        }
        return out
    }

    /** Cheap content fingerprint of [m]: per-tile key, tile version and tile identity. */
    private fun selectionFingerprint(m: MaskGrid): List<Long> {
        val keys = m.keys().sortedBy { it.packed }
        val sig = ArrayList<Long>(keys.size * 2)
        for (k in keys) {
            val t = m.get(k) ?: continue
            sig.add((k.packed.toLong() shl 32) or (t.version and 0xFFFF_FFFFL))
            sig.add(System.identityHashCode(t).toLong())
        }
        return sig
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

/** Keys of the mode favourites in `presets/index.json`: the initial preset of PAINT and of ERASE. */
private const val FAVORITE_PAINT = "PAINT"
private const val FAVORITE_ERASE = "ERASE"

/** Longest lazy rope radius, in screen px (spec G2.4). */
private const val MAX_LAZY_RADIUS = 200f

/** Minimum delay between two live transform previews (about 30 fps). */
private const val PREVIEW_FRAME_MS = 33L

/** Distance of the transform rotate handle from the top edge, in dp on screen. */
private const val ROTATE_HANDLE_DP = 26f

/** Stroke worker threads: leave cores for UI and GL. */
private val STROKE_THREADS = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
