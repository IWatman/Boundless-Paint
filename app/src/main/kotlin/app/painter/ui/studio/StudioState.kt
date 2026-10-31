package app.painter.ui.studio

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import app.painter.core.adjust.AdjustParams
import app.painter.core.blend.BlendMode
import app.painter.core.color.Rgba
import app.painter.core.doc.ColorTag
import app.painter.core.doc.EditTarget
import app.painter.core.fill.GradientType
import app.painter.core.filter.FilterParams
import app.painter.core.fill.Spread
import app.painter.core.guide.OverlayRole
import app.painter.core.history.MaskInit
import app.painter.core.perspective.PerspectiveType
import app.painter.core.shape.ShapeTool
import app.painter.core.stroke.BrushParams
import app.painter.core.symmetry.MirrorAxes
import app.painter.core.symmetry.SymmetryType
import app.painter.studio.ExportOptions
import app.painter.studio.StylusSettings

/** Brush mode of the main toolbar. */
/**
 * Active canvas tool. [BRUSH] paints with the current brush mode; [SELECT_RECT], [SELECT_ELLIPSE] and
 * [SELECT_LASSO] drag a selection (rectangle, ellipse or freehand outline); [SELECT_WAND] taps a region
 * of the layer and adds it to the selection. [TRANSFORM] moves, uniformly scales and rotates the active
 * layer pixels (or the selected pixels when a selection exists) with the cage overlay. [FILL] taps a region
 * of the canvas and fills it on the active layer with the current color. [GRADIENT] drags the axis of a
 * two-stop gradient and applies it to the active layer. [SHAPE] drags, edits and stamps geometric shapes
 * of the selected [app.painter.core.shape.ShapeTool] on the active layer.
 */
enum class ToolUi {
    BRUSH, SELECT_RECT, SELECT_ELLIPSE, SELECT_LASSO, SELECT_WAND, TRANSFORM, FILL, GRADIENT, SHAPE,
    /** Paints copies of the active layer from a source point picked by a long-press on the canvas. */
    CLONE,
    /** Liquify: dragging pushes the pixels of the active layer around (spec/ROADMAP.md M3). */
    LIQUIFY,
    /** Freehand lasso that fills the enclosed area immediately (M4). */
    LASSO_FILL,
    /** Freehand lasso that erases the enclosed area immediately (M4). */
    LASSO_ERASE,
}

/** True for the lasso fill/erase tools that record a freehand contour and apply it on release. */
val ToolUi.isLassoContent: Boolean
    get() = this == ToolUi.LASSO_FILL || this == ToolUi.LASSO_ERASE

/** True for the tools that build a selection instead of painting. */
val ToolUi.isSelectionTool: Boolean
    get() = this == ToolUi.SELECT_RECT || this == ToolUi.SELECT_ELLIPSE ||
        this == ToolUi.SELECT_LASSO || this == ToolUi.SELECT_WAND

/** A document-space point (selection draft vertices, in document pixels). */
@Immutable
data class PointUi(val x: Float, val y: Float)

/** Axis-aligned document-space rectangle (selection draft and selection bounds). */
@Immutable
data class RectUi(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        /** Normalized rect spanning two points; null when the drag is degenerate. */
        fun between(x0: Float, y0: Float, x1: Float, y1: Float): RectUi? {
            val r = RectUi(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
            return if (r.width < 1f || r.height < 1f) null else r
        }
    }
}

/**
 * Document→view mapping published by the canvas (offset, zoom, rotation in degrees) so Compose
 * layers above the GL surface can place document-space geometry.
 */
@Immutable
data class CanvasTransformUi(val zoom: Float = 1f, val rotationDeg: Float = 0f, val tx: Float = 0f, val ty: Float = 0f)

enum class BrushModeUi { PAINT, BLEND, ERASE }

/**
 * HUD content. [ringDiameterPx] is the brush diameter in screen pixels (already zoom-scaled),
 * [ringMaxDiameterPx] the brush maximum in screen pixels.
 */
@Immutable
data class HudUi(
    val label: String,
    val value: String,
    val ringDiameterPx: Float? = null,
    val ringMaxDiameterPx: Float? = null,
)

/** One preset row of the brush browser: [id] is the preset id (`<category>.<name>`) or a `user.<uuid>`. */
@Immutable
data class BrushItemUi(val id: String, val name: String, val user: Boolean = false)

/** One category column of the brush browser with its presets in catalog order. */
@Immutable
data class BrushCategoryUi(val id: String, val name: String, val brushes: List<BrushItemUi>)

/**
 * Pressure calibration as the stylus panel shows it: [auto] follows the peaks of the last strokes, else the
 * range is the fixed [min]…[max] that the calibration wizard found (spec/tools/m2-brush.md §4.3).
 */
@Immutable
data class CalibrationUi(
    val auto: Boolean = true,
    val min: Float = 0f,
    val max: Float = 1f,
)

/** One row of the layer panel. [depth] is the group nesting level (0 = root). */
@Immutable
data class LayerUi(
    val id: String,
    val name: String,
    val visible: Boolean,
    val opacity: Float,
    val blendMode: BlendMode,
    val clipped: Boolean,
    val alphaLock: Boolean,
    val thumbnail: ImageBitmap?,
    val depth: Int = 0,
    val isGroup: Boolean = false,
    val expanded: Boolean = false,
    val hasMask: Boolean = false,
    val maskEnabled: Boolean = true,
    /** Mask thumbnail; null while masks have no preview renderer. */
    val maskThumb: ImageBitmap? = null,
    val colorTag: ColorTag = ColorTag.NONE,
    val locked: Boolean = false,
    val reference: Boolean = false,
    /** The active node is this row and painting edits its mask. */
    val isActiveTargetMask: Boolean = false,
    /** Node is an adjustment layer: it has no pixels, its thumbnail is a kind badge. */
    val isAdjustment: Boolean = false,
    /** [app.painter.core.adjust.AdjustKind] id when [isAdjustment]. */
    val adjustKind: String? = null,
    /** Node is an editable vector layer: the thumbnail shows a small "V" badge. */
    val isVector: Boolean = false,
)

/**
 * Complete state rendered by [StudioScreen]. [brushSize] is the diameter in document pixels,
 * opacity/flow/hardness are 0..1. [layers] are in display order (top first).
 * [viewZoom] scales the HUD brush ring to screen size.
 */
@Immutable
data class StudioUiState(
    val mode: BrushModeUi = BrushModeUi.PAINT,
    val brushSize: Float = 20f,
    val brushSizeMax: Float = 500f,
    val opacity: Float = 1f,
    val flow: Float = 1f,
    val hardness: Float = 0.8f,
    val color: Rgba = Rgba.BLACK,
    val previousColor: Rgba = Rgba.WHITE,
    val recentColors: List<Rgba> = emptyList(),
    val palette: List<Rgba> = emptyList(),
    val layers: List<LayerUi> = emptyList(),
    val activeLayerId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val layerPanelOpen: Boolean = false,
    val colorPanelOpen: Boolean = false,
    /** Stylus settings the [StylusSettingsPanel] shows (spec/tools/m2-brush.md §4.3). */
    val stylusSettings: StylusSettings = StylusSettings(),
    /** Pressure calibration of the pen the [StylusSettingsPanel] reports (spec/tools/m2-brush.md §4.3). */
    val calibration: CalibrationUi = CalibrationUi(),
    /** The stylus settings panel is open; it is the only way to set [stylusSettings] from the UI. */
    val stylusPanelOpen: Boolean = false,
    val hud: HudUi? = null,
    val projectName: String = "",
    val layerLimit: Int = 32,
    val paperColor: Rgba = Rgba.WHITE,
    val paperVisible: Boolean = true,
    /** Document toggle "gamma-correct": blend in linear light (spec/blend-and-color.md). */
    val gammaCorrect: Boolean = false,
    /** Studio backdrop around the canvas: light #D0D0D0 instead of the dark #2B2B2B. */
    val lightBackground: Boolean = false,
    /** A finger double tap fits the canvas (spec/tools/m0-m1-tools.md T1). */
    val doubleTapFits: Boolean = true,
    /** A destructive filter is being computed; strokes are ignored until it is applied. */
    val filterBusy: Boolean = false,
    /** Liquify brush diameter in document pixels. */
    val liquifySize: Float = 160f,
    /** Liquify push strength 0..1. */
    val liquifyStrength: Float = 0.5f,
    /** Canvas size of the open document, shown by the crop/resize dialogs. */
    val canvasWidth: Int = 1,
    val canvasHeight: Int = 1,
    /** The full-screen settings sheet is open. */
    val settingsOpen: Boolean = false,
    /** Manuscript trim/bleed/safe guides are drawn over the canvas. */
    val manuscriptGuides: Boolean = false,
    /** Radial ruler overlay (M4). */
    val radialGuides: Boolean = false,
    /** Array/grid ruler overlay (M4). */
    val arrayGuides: Boolean = false,
    /** Timelapse recording is on for the open project. */
    val timelapseEnabled: Boolean = false,
    /** Recorded frames of the open project. */
    val timelapseFrames: Int = 0,
    val viewZoom: Float = 1f,
    /** Active tool; [ToolUi.BRUSH] paints, the SELECT_* tools drag a selection. */
    val tool: ToolUi = ToolUi.BRUSH,
    /** Live selection rectangle while dragging a rectangle selection (document pixels), null when idle. */
    val selectionDraft: RectUi? = null,
    /** Live outline of an ellipse (bounding box) or lasso (trail) selection draft, null when idle. */
    val selectionDraftPath: List<PointUi>? = null,
    /** A selection is in effect (non-empty and active mask). */
    val hasSelection: Boolean = false,
    /** Bounding box of the current selection in document pixels. */
    val selectionBounds: RectUi? = null,
    /**
     * Outline of the current selection in document pixels: one flat `x0, y0, x1, y1, ...` polyline per
     * connected contour run. Null while it is unknown (the bounding box is drawn instead).
     */
    val selectionContour: List<FloatArray>? = null,
    /** Document→view mapping of the canvas, for overlays drawn by Compose. */
    val canvasTransform: CanvasTransformUi = CanvasTransformUi(),
    /** Open adjustment editor, null when closed. */
    val adjustmentEditor: AdjustEditorUi? = null,
    /** Transform cage while the transform tool is active, null otherwise. */
    val transform: TransformUi? = null,
    /** Gradient ends and type while a gradient is open, null otherwise. */
    val gradient: GradientUi? = null,
    /** Draft of the shape tool (outline and handles), null while the tool is idle. */
    val shape: ShapeUi? = null,
    /** Clone source point in document pixels; null until the clone tool gets a long-press. */
    val cloneSource: PointUi? = null,
    /** Clone offset (stroke start − source) fixed by the first clone stroke, kept for later strokes. */
    val cloneOffset: PointUi? = null,
    /** Ruler guide of the open document, null while the ruler is off. */
    val ruler: RulerUi? = null,
    /** Ellipse guide of the open document, null while the ellipse is off. */
    val ellipse: EllipseUi? = null,
    /** Lazy rope stabilizer of the open document, null while it is off. */
    val lazy: LazyUi? = null,
    /** Perspective grid of the open document, null while it is off. */
    val perspective: PerspectiveUi? = null,
    /** Symmetry settings of the open document, null while symmetry is off. */
    val symmetry: SymmetryUi? = null,
    /** Built-in brush presets, empty until the brush library has been loaded. */
    val brushCategories: List<BrushCategoryUi> = emptyList(),
    /** Id of the preset the current mode paints with, null while the library is not loaded (or for Blend). */
    val currentBrushId: String? = null,
    /** The brush browser popup is open. */
    val brushBrowserOpen: Boolean = false,
    /** Working copy of the brush parameters while the brush editor is open, null when it is closed (§4.2). */
    val editorParams: BrushParams? = null,
    /** Preview of [editorParams] rendered by the stroke engine; null until the first render finishes. */
    val editorPreview: ImageBitmap? = null,
    /** Localized name of the brush the editor edits; empty for the Blend mode, which has no preset. */
    val editorBrushName: String = "",
    /** True when the edited brush references a texture image; without one the Texture tab cannot turn the grain on. */
    val editorHasTexture: Boolean = false,
)

/**
 * Transform tool state: the cage of the open [app.painter.core.transform.TransformSession] after the current
 * mapping, as 4 document-space corners clockwise from top-left. Non-null only while the tool is active.
 */
@Immutable
data class TransformUi(val corners: List<PointUi>)

/** One color stop of the gradient tool: [pos] 0..1 along the axis, [color] straight sRGB with alpha. */
@Immutable
data class GradientStopUi(val pos: Float, val color: Rgba)

/**
 * Gradient tool state: the two document-space ends [p0] → [p1], the gradient [type] to draw between them and
 * the editable session [stops] (position 0..1 along the axis; unsorted while a stop is dragged). [spread]
 * applies past the ends, [reverse] mirrors the stops, [opacity] is the opacity of the result 0..1 and
 * [repetitions] (1..100) repeats [GradientType.ANGULAR] gradients only.
 */
@Immutable
data class GradientUi(
    val p0: PointUi,
    val p1: PointUi,
    val type: GradientType,
    val stops: List<GradientStopUi> = emptyList(),
    val spread: Spread = Spread.PAD,
    val reverse: Boolean = false,
    val opacity: Float = 1f,
    val repetitions: Int = 1,
)

/**
 * Shape tool draft in document pixels: [outline] is the polyline of the shape (a [closed] one does not repeat
 * its first point), [handles] are the positions of its draggable handles, [outlineOn] draws the brush outline
 * and [fillOn] fills the shape (closed shapes only).
 */
@Immutable
data class ShapeUi(
    val outline: List<PointUi>,
    val handles: List<PointUi>,
    val closed: Boolean,
    val outlineOn: Boolean,
    val fillOn: Boolean,
)

/** Symmetry of the open document: [planes] is N of Radial/Kaleido, [centerX]/[centerY] are document pixels. */
@Immutable
data class SymmetryUi(
    val type: SymmetryType,
    val axes: MirrorAxes,
    val centerX: Float,
    val centerY: Float,
    val angleDeg: Float,
    val planes: Int,
    val crop: Boolean,
    val locked: Boolean,
)

/** Ruler guide: the segment ([ax], [ay])–([bx], [by]) in document pixels plus its snapping flags. */
@Immutable
data class RulerUi(
    val ax: Float,
    val ay: Float,
    val bx: Float,
    val by: Float,
    val perpendicular: Boolean,
    val magnet: Boolean,
    val locked: Boolean,
)

/**
 * Ellipse guide: centre ([cx], [cy]) and semi-axes [rx]/[ry] in document pixels, rotated by [angleDeg]
 * around the centre, plus its snapping flags.
 */
@Immutable
data class EllipseUi(
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val angleDeg: Float,
    val magnet: Boolean,
    val locked: Boolean,
)

/** Perspective grid: [type] with its vanishing points in document pixels and the flags of the guide. */
@Immutable
data class PerspectiveUi(
    val type: PerspectiveType,
    val vp: List<PointUi>,
    val snap: Boolean,
    val visible: Boolean,
    val locked: Boolean,
    val lines: List<OverlayLineUi>,
)

/** One perspective overlay segment in document pixels; [role] picks its colour. */
@Immutable
data class OverlayLineUi(val ax: Float, val ay: Float, val bx: Float, val by: Float, val role: OverlayRole)

/** Lazy rope stabilizer: [radius] in screen pixels. */
@Immutable
data class LazyUi(val radius: Float)

/** All user intents raised by [StudioScreen]. Display indices refer to [StudioUiState.layers]. */
interface StudioActions {
    fun setMode(mode: BrushModeUi)

    /** Selects the active tool; switching away from a selection tool drops its draft. */
    fun selectTool(tool: ToolUi)

    /** Applies the open transform session (undoable) and returns to [ToolUi.BRUSH]. */
    fun transformApply() {}

    /** Drops the open transform session, restoring the pixels, and returns to [ToolUi.BRUSH]. */
    fun transformCancel() {}

    /** Applies the open gradient as one undoable step; the tool stays on [ToolUi.GRADIENT]. */
    fun gradientApply() {}

    /** Drops the open gradient, restoring the pixels, without a history entry. */
    fun gradientCancel() {}

    /** Picks the [app.painter.core.fill.GradientType] of the gradient tool and redraws an open gradient. */
    fun setGradientType(type: GradientType) {}

    /** Spread of the gradient past its ends; kept for the next gradient of the session. */
    fun setGradientSpread(spread: Spread) {}

    /** Mirrors the gradient stops ([reverse] true keeps the first stop at the far end). */
    fun setGradientReverse(reverse: Boolean) {}

    /** Opacity of the gradient applied to the layer, 0..1. */
    fun setGradientOpacity(opacity: Float) {}

    /** Repetitions of an [app.painter.core.fill.GradientType.ANGULAR] gradient, clamped to 1..100. */
    fun setGradientRepetitions(n: Int) {}

    /** Picks the shape [tool] and switches to [ToolUi.SHAPE]. */
    fun selectShapeTool(tool: ShapeTool) {}

    /** Ends the open draft and records its pixels as one undoable step. */
    fun shapeCommit() {}

    /** Drops the open draft without touching the layer. */
    fun shapeCancel() {}

    /** Records a copy of the open draft as one undoable step and keeps the draft for further copies. */
    fun shapeStamp() {}

    /** Draws the brush outline of the draft ([on] false renders the fill alone). */
    fun setShapeOutline(on: Boolean) {}

    /** Fills closed drafts with the current color; kept for the next draft when it is open-shaped. */
    fun setShapeFill(on: Boolean) {}

    /** Clears the selection. */
    fun deselect()

    /** Selects the whole canvas (undoable). */
    fun selectAll()

    /** Inverts the selection (undoable). */
    fun invertSelection()

    /** Grows ([px] > 0) or shrinks ([px] < 0) the selection by pixels (undoable). */
    fun expandSelection(px: Float)

    /** Feathers the selection edges by [radius] pixels (undoable). */
    fun featherSelection(radius: Float)

    /** Fills ([erase] false) or clears the selected pixels on the active layer (one undo step). */
    fun fillSelection(erase: Boolean) {}

    /** Fills everything outside the selection; without a selection this does nothing. */
    fun fillOutsideSelection() {}
    fun setBrushSize(size: Float)
    fun setOpacity(opacity: Float)
    fun setFlow(flow: Float)
    fun setHardness(hardness: Float)

    /** Opens or closes the built-in brush browser. */
    fun toggleBrushBrowser() {}

    /** Picks the built-in preset [id] for the current mode and closes the browser. */
    fun selectBrush(id: String) {}
    /** Opens the brush editor on the current mode's brush (spec/tools/m2-brush.md §4.2). */
    fun openBrushEditor() {}
    /** Replaces the working copy of the brush editor; the preview re-renders 100 ms after the last change. */
    fun updateEditor(params: BrushParams) {}
    /** Restores the preset parameters in the editor. */
    fun resetEditor() {}
    /** Applies the working copy to the current mode's brush and closes the editor. */
    fun confirmEditor() {}
    /** Stores the working copy as a new user brush called [name], paints with it and closes the editor. */
    fun saveBrushAs(name: String) {}
    /** Renames the user brush [id]. */
    fun renameBrush(id: String, name: String) {}
    /** Deletes the user brush [id]; the modes that painted with it fall back to their default preset. */
    fun deleteBrush(id: String) {}
    /** Closes the editor without applying the working copy. */
    fun cancelEditor() {}

    /** Imports a brush file into the Imported folder; returns how many brushes were stored. */
    suspend fun importBrush(fileName: String, bytes: ByteArray, asTexture: Boolean = false): Int = 0

    /** Name of the file the current brush should be exported under. */
    fun exportBrushName(): String = "brush"

    /** The current mode's brush as a `.pbrush`, or null when there is nothing to export. */
    fun exportBrushBytes(): ByteArray? = null

    /** Every own/imported brush as a `.pbrushes` pack, or null when the folder is empty. */
    fun exportBrushPackBytes(): ByteArray? = null

    /** Sets the brush color. [commit] is false for live updates during a drag. */
    fun setColor(color: Rgba, commit: Boolean = true)
    fun undo()
    fun redo()
    fun clearLayerLongPress()
    fun toggleLayerPanel()
    fun toggleColorPanel()
    fun addLayer()

    /** Inserts an empty vector layer above the active node and makes it active. */
    fun addVectorLayer() {}

    /**
     * Inserts an adjustment layer of [kind] (one of [app.painter.core.adjust.AdjustKind.ALL]) above the
     * active node and opens its editor.
     */
    fun addAdjustmentLayer(kind: String)

    /** Opens the parameter editor of adjustment layer [id]. */
    fun editAdjustment(id: String)

    /**
     * Updates the parameters of adjustment layer [id]: live while dragging ([commit] false), and with
     * [commit] true records the whole session as one [app.painter.core.history.SetAdjustParams] step.
     */
    fun updateAdjustment(id: String, params: AdjustParams, commit: Boolean)

    /** Closes the editor, keeping the current parameters as one undone-able step. */
    fun closeAdjustmentEditor()
    fun deleteLayer(id: String)
    fun duplicateLayer(id: String)
    fun clearLayer(id: String)
    fun mergeDown(id: String)
    fun selectLayer(id: String)
    fun moveLayer(id: String, toDisplayIndex: Int)
    fun setLayerVisible(id: String, visible: Boolean)
    fun setLayerOpacity(id: String, opacity: Float)
    fun setLayerBlend(id: String, mode: BlendMode)
    fun setLayerClipped(id: String, clipped: Boolean)
    fun setLayerAlphaLock(id: String, locked: Boolean)
    fun renameLayer(id: String, name: String)

    /** Colour tag of the row ([ColorTag.NONE] clears it). */
    fun setLayerColorTag(id: String, tag: ColorTag)

    /** Layer lock: a locked layer (or group) cannot be painted or restructured. */
    fun setLayerLocked(id: String, locked: Boolean)

    /** Flags the node as the document reference ([app.painter.core.doc.Document.referenceNode]). */
    fun setLayerReference(id: String, reference: Boolean)

    /** Shows or hides the children of a group row. */
    fun toggleGroupExpanded(id: String)

    /** Wraps the layer into a new group. */
    fun groupLayer(id: String)

    /** Replaces the group by its children. */
    fun ungroupLayer(id: String)

    fun addMask(id: String, init: MaskInit)
    fun deleteMask(id: String)
    fun applyMask(id: String)
    fun setMaskEnabled(id: String, enabled: Boolean)
    fun invertMask(id: String)

    /** Makes [id] active and picks what painting edits: its pixels or its mask. */
    fun selectEditTarget(id: String, target: EditTarget)

    /** Turns symmetry off ([type] null) or on with [type]; turning it on keeps the previous centre and angle. */
    fun setSymmetry(type: SymmetryType?, axes: MirrorAxes = MirrorAxes.V) {}

    /** Planes N of Radial/Kaleido symmetry, clamped to the range of its type. */
    fun setSymmetryPlanes(n: Int) {}

    /** Locks the overlay: a locked symmetry cannot be moved or rotated by its handles. */
    fun setSymmetryLocked(locked: Boolean) {}

    /** Crops the copies at the axis (Mirror and Kaleido only). */
    fun setSymmetryCrop(crop: Boolean) {}

    /** Moves the symmetry centre to ([x], [y]) document pixels, clamped to the canvas. */
    fun moveSymmetryCenter(x: Float, y: Float) {}

    /** Turns the lazy rope stabilizer on or off; the radius is kept. */
    fun setLazy(on: Boolean) {}

    /** Lazy rope radius in screen pixels, clamped to 0..200. */
    fun setLazyRadius(radius: Float) {}

    /** Turns the ruler on or off; turning it on the first time places a default segment across the canvas. */
    fun setRuler(on: Boolean) {}

    /** Moves end [end] (0 = a, 1 = b) of the ruler to ([x], [y]) document pixels, clamped to the canvas. */
    fun moveRulerEnd(end: Int, x: Float, y: Float) {}

    /** Moves both ends of the ruler by ([dx], [dy]) document pixels. */
    fun moveRuler(dx: Float, dy: Float) {}

    /** Adds a track perpendicular to the ruler axis to every stroke. */
    fun setRulerPerpendicular(on: Boolean) {}

    /** Keeps strokes started within 32 screen px of the ruler axis on the axis. */
    fun setRulerMagnet(on: Boolean) {}

    /** Locks the ruler: a locked ruler cannot be moved by its handles. */
    fun setRulerLocked(on: Boolean) {}

    /** Turns the ellipse guide on or off; turning it on the first time places a circle in the canvas middle. */
    fun setEllipseGuide(on: Boolean) {}

    /** Moves the ellipse centre by ([dx], [dy]) document pixels. */
    fun moveEllipse(dx: Float, dy: Float) {}

    /** Sets the semi-axes: [rx] is kept at 4 px or more and the vertical/horizontal ratio follows [ry]/[rx]. */
    fun setEllipseRadii(rx: Float, ry: Float) {}

    /** Rotation of the ellipse in degrees, snapped to multiples of 45° within ±3°. */
    fun setEllipseAngle(deg: Float) {}

    /** Keeps strokes started near the ellipse outline on it. */
    fun setEllipseMagnet(on: Boolean) {}

    /** Locks the ellipse: a locked ellipse cannot be moved or resized by its handles. */
    fun setEllipseLocked(on: Boolean) {}

    /** Turns the perspective grid on with [type] (null turns it off); turning it on uses the canvas defaults. */
    fun setPerspective(type: PerspectiveType?) {}

    /**
     * Moves vanishing point [index] to ([x], [y]) document pixels. Not clamped: a vanishing point may be
     * dragged outside the canvas.
     */
    fun moveVanishingPoint(index: Int, x: Float, y: Float) {}

    /** Keeps strokes on the direction families of the perspective grid. */
    fun setPerspectiveSnap(on: Boolean) {}

    /** Shows or hides the perspective overlay lines (the grid keeps snapping while hidden). */
    fun setPerspectiveVisible(on: Boolean) {}

    /** Locks the perspective grid: a locked grid cannot be moved by its vanishing-point handles. */
    fun setPerspectiveLocked(on: Boolean) {}

    /** Sets the main axis angle in degrees, snapped to multiples of 45° within ±3°. */
    fun setSymmetryAngle(deg: Float) {}

    fun setPaperColor(color: Rgba) {}
    fun setPaperVisible(visible: Boolean) {}
    /** Light or dark backdrop around the canvas (view setting, kept across sessions). */
    fun setLightBackground(light: Boolean) {}
    /** Whether a finger double tap fits the canvas (view setting, kept across sessions). */
    fun setDoubleTapFits(enabled: Boolean) {}
    /** Document toggle "gamma-correct": blend layers in linear light (saved with the project). */
    fun setGammaCorrect(on: Boolean) {}
    /** Applies a destructive filter to the active layer; a selection clips it (spec/filters.md). */
    fun applyFilter(params: FilterParams) {}
    /** Adds a live filter layer at the top of the root: it filters everything below it (M3). */
    fun addFilterLayer(params: FilterParams) {}
    /** Liquify brush diameter in document pixels. */
    fun setLiquifySize(size: Float) {}
    /** Liquify push strength 0..1. */
    fun setLiquifyStrength(strength: Float) {}
    /** Ends the liquify session: keeps the pushed pixels as one undo step, or puts them back. */
    fun finishLiquify(commit: Boolean) {}
    /** Stamps raster text on the active layer at the centre of the canvas (M3 text tool). */
    fun stampText(text: String, sizePx: Float) {}
    /** Fits the canvas into the view (keyboard shortcut F). */
    fun fitView() {}

    /** Keeps the document-space [rect] and resizes every layer/mask (one undo step). */
    fun cropCanvas(rect: RectUi) {}

    /** Changes the canvas size, scaling the artwork or anchoring it at [anchorX]/[anchorY] (0..1). */
    fun resizeCanvas(width: Int, height: Int, scaleContent: Boolean, anchorX: Float, anchorY: Float) {}

    /** Rotates the whole artwork by [angleDeg] inside the canvas (one undo step). */
    fun straightenCanvas(angleDeg: Float) {}
    fun openHome()
    fun save()
    fun exportImage()
    /** Asks the activity for a PSD destination and writes the layered document there. */
    fun exportPsd() {}
    /** Starts the export with the options of the dialog; the activity opens the system file picker. */
    fun startExport(options: ExportOptions) {}
    /** Closes the export dialog without exporting. */
    fun dismissExport() {}
    fun openEditingTools()
    fun openOptions()
    fun openSettings() {}
    /** Closes the settings sheet. */
    fun closeSettings() {}
    /** Shows or hides the manuscript trim/bleed/safe guides. */
    fun setManuscriptGuides(on: Boolean) {}
    /** Shows or hides the radial ruler overlay. */
    fun setRadialGuides(on: Boolean) {}
    /** Shows or hides the array ruler overlay. */
    fun setArrayGuides(on: Boolean) {}
    /** Turns timelapse recording on/off (kept for the next session). */
    fun setTimelapseEnabled(enabled: Boolean) {}
    /** Encodes the recorded frames into an MP4; null when there is nothing to export. */
    suspend fun encodeTimelapse(durationSec: Int, maxSide: Int): ByteArray? = null
    fun addColorToPalette(color: Rgba)
    /** Replaces the stylus settings: the pen buttons, the finger rule, the palm mode and the pressure curve. */
    fun setStylusSettings(settings: StylusSettings) {}
    /**
     * Fixes the pressure range of device [descriptor] from the calibration wizard and turns its auto off
     * (spec/tools/m2-brush.md §4.3). A null [descriptor] (the pointer carried no `InputDevice`) applies the
     * range to every known pen.
     */
    fun applyCalibration(descriptor: String?, min: Float, max: Float) {}
    /** "Reset to auto": every known pen goes back to the automatic range, min 0, max 1. */
    fun resetCalibration() {}
    /** Opens or closes the stylus settings panel (spec/tools/m2-brush.md §4.3). */
    fun toggleStylusPanel() {}
}

/** Adjustment editor session: node [nodeId] of [kind] showing [params]. */
@Immutable
data class AdjustEditorUi(
    val nodeId: String,
    val kind: String,
    val params: AdjustParams,
)

/** No-op base for previews and partial implementations. */
open class NoOpStudioActions : StudioActions {
    override fun setMode(mode: BrushModeUi) {}
    override fun selectTool(tool: ToolUi) {}
    override fun deselect() {}
    override fun selectAll() {}
    override fun invertSelection() {}
    override fun expandSelection(px: Float) {}
    override fun featherSelection(radius: Float) {}
    override fun setBrushSize(size: Float) {}
    override fun setOpacity(opacity: Float) {}
    override fun setFlow(flow: Float) {}
    override fun setHardness(hardness: Float) {}
    override fun setColor(color: Rgba, commit: Boolean) {}
    override fun undo() {}
    override fun redo() {}
    override fun clearLayerLongPress() {}
    override fun toggleLayerPanel() {}
    override fun toggleColorPanel() {}
    override fun addLayer() {}
    override fun addAdjustmentLayer(kind: String) {}
    override fun editAdjustment(id: String) {}
    override fun updateAdjustment(id: String, params: AdjustParams, commit: Boolean) {}
    override fun closeAdjustmentEditor() {}
    override fun deleteLayer(id: String) {}
    override fun duplicateLayer(id: String) {}
    override fun clearLayer(id: String) {}
    override fun mergeDown(id: String) {}
    override fun selectLayer(id: String) {}
    override fun moveLayer(id: String, toDisplayIndex: Int) {}
    override fun setLayerVisible(id: String, visible: Boolean) {}
    override fun setLayerOpacity(id: String, opacity: Float) {}
    override fun setLayerBlend(id: String, mode: BlendMode) {}
    override fun setLayerClipped(id: String, clipped: Boolean) {}
    override fun setLayerAlphaLock(id: String, locked: Boolean) {}
    override fun renameLayer(id: String, name: String) {}
    override fun setLayerColorTag(id: String, tag: ColorTag) {}
    override fun setLayerLocked(id: String, locked: Boolean) {}
    override fun setLayerReference(id: String, reference: Boolean) {}
    override fun toggleGroupExpanded(id: String) {}
    override fun groupLayer(id: String) {}
    override fun ungroupLayer(id: String) {}
    override fun addMask(id: String, init: MaskInit) {}
    override fun deleteMask(id: String) {}
    override fun applyMask(id: String) {}
    override fun setMaskEnabled(id: String, enabled: Boolean) {}
    override fun invertMask(id: String) {}
    override fun selectEditTarget(id: String, target: EditTarget) {}
    override fun openHome() {}
    override fun save() {}
    override fun exportImage() {}
    override fun startExport(options: ExportOptions) {}
    override fun dismissExport() {}
    override fun setLightBackground(light: Boolean) {}
    override fun setDoubleTapFits(enabled: Boolean) {}
    override fun setGammaCorrect(on: Boolean) {}
    override fun applyFilter(params: FilterParams) {}
    override fun setLiquifySize(size: Float) {}
    override fun setLiquifyStrength(strength: Float) {}
    override fun finishLiquify(commit: Boolean) {}
    override fun stampText(text: String, sizePx: Float) {}
    override fun fitView() {}
    override fun openEditingTools() {}
    override fun openOptions() {}
    override fun openSettings() {}
    override fun closeSettings() {}
    override fun addColorToPalette(color: Rgba) {}
    override fun applyCalibration(descriptor: String?, min: Float, max: Float) {}
    override fun resetCalibration() {}
    override fun setStylusSettings(settings: StylusSettings) {}
    override fun toggleStylusPanel() {}
}
