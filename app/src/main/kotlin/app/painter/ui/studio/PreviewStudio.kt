package app.painter.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.painter.core.blend.BlendMode
import app.painter.core.color.Rgba
import app.painter.ui.components.HsvUi
import app.painter.ui.components.sameAs
import app.painter.ui.components.toRgba
import app.painter.ui.theme.PainterTheme

/** In-memory fake of the studio for previews and the temporary demo. */
class FakeStudioController(projectName: String = "Demo") {
    private var nextId = 3

    var state by mutableStateOf(
        StudioUiState(
            brushSize = 24f,
            color = Rgba(0.31f, 0.55f, 1f),
            previousColor = Rgba(0.9f, 0.3f, 0.25f),
            palette = defaultPalette(),
            recentColors = listOf(Rgba(0.9f, 0.3f, 0.25f), Rgba.BLACK, Rgba.WHITE),
            layers = listOf(
                LayerUi("2", "Layer 2", true, 1f, BlendMode.NORMAL, false, false, null),
                LayerUi("1", "Layer 1", true, 1f, BlendMode.NORMAL, false, false, null),
            ),
            activeLayerId = "2",
            projectName = projectName,
            layerPanelOpen = true,
        ),
    )
        private set

    private fun update(f: (StudioUiState) -> StudioUiState) {
        state = f(state)
    }

    private fun layer(id: String, f: (LayerUi) -> LayerUi) = update { s ->
        s.copy(layers = s.layers.map { if (it.id == id) f(it) else it })
    }

    val actions: StudioActions = object : NoOpStudioActions() {
        override fun setMode(mode: BrushModeUi) = update { it.copy(mode = mode) }
        override fun setBrushSize(size: Float) = update { it.copy(brushSize = size) }
        override fun setOpacity(opacity: Float) = update { it.copy(opacity = opacity) }
        override fun setFlow(flow: Float) = update { it.copy(flow = flow) }
        override fun setHardness(hardness: Float) = update { it.copy(hardness = hardness) }
        override fun setColor(color: Rgba, commit: Boolean) = update { s ->
            val recent = if (commit && !s.color.sameAs(color)) {
                (listOf(color) + s.recentColors.filterNot { it.sameAs(color) }).take(10)
            } else {
                s.recentColors
            }
            s.copy(
                color = color,
                previousColor = if (commit && !s.color.sameAs(color)) s.color else s.previousColor,
                recentColors = recent,
            )
        }
        override fun undo() = update { it.copy(canUndo = false, canRedo = true) }
        override fun redo() = update { it.copy(canUndo = true, canRedo = false) }
        override fun toggleLayerPanel() = update { it.copy(layerPanelOpen = !it.layerPanelOpen) }
        override fun toggleColorPanel() = update { it.copy(colorPanelOpen = !it.colorPanelOpen) }
        override fun addLayer() = update { s ->
            val id = (nextId++).toString()
            val idx = s.layers.indexOfFirst { it.id == s.activeLayerId }.coerceAtLeast(0)
            val l = LayerUi(id, "Layer $id", true, 1f, BlendMode.NORMAL, false, false, null)
            s.copy(layers = s.layers.toMutableList().apply { add(idx, l) }, activeLayerId = id, canUndo = true)
        }
        override fun deleteLayer(id: String) = update { s ->
            val rest = s.layers.filterNot { it.id == id }
            s.copy(layers = rest, activeLayerId = if (s.activeLayerId == id) rest.firstOrNull()?.id else s.activeLayerId)
        }
        override fun duplicateLayer(id: String) = update { s ->
            val i = s.layers.indexOfFirst { it.id == id }
            if (i < 0) return@update s
            val nid = (nextId++).toString()
            val copy = s.layers[i].copy(id = nid, name = s.layers[i].name + " copy")
            s.copy(layers = s.layers.toMutableList().apply { add(i, copy) }, activeLayerId = nid)
        }
        override fun mergeDown(id: String) = deleteLayer(id)
        override fun selectLayer(id: String) = update { it.copy(activeLayerId = id) }
        override fun moveLayer(id: String, toDisplayIndex: Int) = update { s ->
            val list = s.layers.toMutableList()
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) return@update s
            val l = list.removeAt(i)
            list.add(toDisplayIndex.coerceIn(0, list.size), l)
            s.copy(layers = list)
        }
        override fun setLayerVisible(id: String, visible: Boolean) = layer(id) { it.copy(visible = visible) }
        override fun setLayerOpacity(id: String, opacity: Float) = layer(id) { it.copy(opacity = opacity) }
        override fun setLayerBlend(id: String, mode: BlendMode) = layer(id) { it.copy(blendMode = mode) }
        override fun setLayerClipped(id: String, clipped: Boolean) = layer(id) { it.copy(clipped = clipped) }
        override fun setLayerAlphaLock(id: String, locked: Boolean) = layer(id) { it.copy(alphaLock = locked) }
        override fun renameLayer(id: String, name: String) = layer(id) { it.copy(name = name) }
        override fun setPaperColor(color: Rgba) = update { it.copy(paperColor = color) }
        override fun setPaperVisible(visible: Boolean) = update { it.copy(paperVisible = visible) }
        override fun addColorToPalette(color: Rgba) = update { s ->
            if (s.palette.any { it.sameAs(color) }) s else s.copy(palette = s.palette + color)
        }
    }

    companion object {
        /** A simple default palette: grays plus 12 hues in two brightness rows. */
        fun defaultPalette(): List<Rgba> {
            val grays = (0..7).map { val v = it / 7f; Rgba(v, v, v) }
            val vivid = (0 until 8).map { HsvUi(it * 45f, 0.85f, 0.95f).toRgba() }
            val deep = (0 until 8).map { HsvUi(it * 45f, 0.75f, 0.55f).toRgba() }
            val pastel = (0 until 8).map { HsvUi(it * 45f, 0.35f, 1f).toRgba() }
            return grays + vivid + deep + pastel
        }
    }
}

/** Studio with fake state; [canvas] defaults to a gray placeholder. */
@Composable
fun PreviewStudio(
    controller: FakeStudioController = remember { FakeStudioController() },
    onHome: () -> Unit = {},
    canvas: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().padding(48.dp).background(Color(0xFF8A8A8A)))
    },
) {
    val actions = remember(controller, onHome) {
        object : StudioActions by controller.actions {
            override fun openHome() = onHome()
        }
    }
    StudioScreen(state = controller.state, actions = actions, canvas = canvas)
}

@Preview(widthDp = 800, heightDp = 480)
@Composable
private fun StudioPreview() {
    PainterTheme { PreviewStudio() }
}
