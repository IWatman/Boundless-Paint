package app.painter.ui.studio

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import app.painter.core.adjust.AdjustParams
import app.painter.core.blend.BlendMode
import app.painter.core.color.Rgba
import app.painter.core.doc.ColorTag
import app.painter.core.doc.EditTarget
import app.painter.core.history.MaskInit

/** Brush mode of the main toolbar. */
/**
 * Active canvas tool. [BRUSH] paints with the current brush mode; [SELECT_RECT], [SELECT_ELLIPSE] and
 * [SELECT_LASSO] drag a selection (rectangle, ellipse or freehand outline); [SELECT_WAND] taps a region
 * of the layer and adds it to the selection.
 */
enum class ToolUi { BRUSH, SELECT_RECT, SELECT_ELLIPSE, SELECT_LASSO, SELECT_WAND }

/** True for the tools that build a selection instead of painting. */
val ToolUi.isSelectionTool: Boolean
    get() = this != ToolUi.BRUSH

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
    val hud: HudUi? = null,
    val projectName: String = "",
    val layerLimit: Int = 32,
    val paperColor: Rgba = Rgba.WHITE,
    val paperVisible: Boolean = true,
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
    /** Document→view mapping of the canvas, for overlays drawn by Compose. */
    val canvasTransform: CanvasTransformUi = CanvasTransformUi(),
    /** Open adjustment editor, null when closed. */
    val adjustmentEditor: AdjustEditorUi? = null,
)

/** All user intents raised by [StudioScreen]. Display indices refer to [StudioUiState.layers]. */
interface StudioActions {
    fun setMode(mode: BrushModeUi)

    /** Selects the active tool; switching away from a selection tool drops its draft. */
    fun selectTool(tool: ToolUi)

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
    fun setBrushSize(size: Float)
    fun setOpacity(opacity: Float)
    fun setFlow(flow: Float)
    fun setHardness(hardness: Float)

    /** Sets the brush color. [commit] is false for live updates during a drag. */
    fun setColor(color: Rgba, commit: Boolean = true)
    fun undo()
    fun redo()
    fun clearLayerLongPress()
    fun toggleLayerPanel()
    fun toggleColorPanel()
    fun addLayer()

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

    fun setPaperColor(color: Rgba) {}
    fun setPaperVisible(visible: Boolean) {}
    fun openHome()
    fun save()
    fun exportImage()
    fun openCreativeTools()
    fun openEditingTools()
    fun openOptions()
    fun openSettings() {}
    fun addColorToPalette(color: Rgba)
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
    override fun openCreativeTools() {}
    override fun openEditingTools() {}
    override fun openOptions() {}
    override fun addColorToPalette(color: Rgba) {}
}
