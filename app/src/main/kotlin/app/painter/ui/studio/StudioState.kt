package app.painter.ui.studio

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import app.painter.core.blend.BlendMode
import app.painter.core.color.Rgba

/** Brush mode of the main toolbar. */
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
)

/** All user intents raised by [StudioScreen]. Display indices refer to [StudioUiState.layers]. */
interface StudioActions {
    fun setMode(mode: BrushModeUi)
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

/** No-op base for previews and partial implementations. */
open class NoOpStudioActions : StudioActions {
    override fun setMode(mode: BrushModeUi) {}
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
    override fun openHome() {}
    override fun save() {}
    override fun exportImage() {}
    override fun openCreativeTools() {}
    override fun openEditingTools() {}
    override fun openOptions() {}
    override fun addColorToPalette(color: Rgba) {}
}
