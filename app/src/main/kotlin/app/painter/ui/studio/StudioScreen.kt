package app.painter.ui.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.painter.R
import app.painter.ui.components.Hud
import app.painter.ui.components.IconToggle
import app.painter.ui.components.PainterIconButton
import app.painter.ui.components.PanelSurface
import app.painter.ui.theme.PainterTheme

/**
 * Main painting screen. [canvas] fills the whole screen underneath; all controls float above it.
 */
@Composable
fun StudioScreen(
    state: StudioUiState,
    actions: StudioActions,
    canvas: @Composable () -> Unit,
) {
    var localHud by remember { mutableStateOf<HudUi?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .background(PainterTheme.colors.canvasBackground),
    ) {
        Box(Modifier.fillMaxSize()) { canvas() }
        SelectionOverlay(state = state, modifier = Modifier.fillMaxSize())
        TransformOverlay(state = state, modifier = Modifier.fillMaxSize())
        GradientOverlay(state = state, modifier = Modifier.fillMaxSize())

        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            StudioTopBar(
                state = state,
                actions = actions,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(8.dp),
            )

            MainToolbar(
                state = state,
                actions = actions,
                onHud = { localHud = it },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp),
            )

            UndoRedoBar(
                state = state,
                actions = actions,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )

            val hud = localHud ?: state.hud
            if (hud != null) {
                Hud(
                    label = hud.label,
                    value = hud.value,
                    ringDiameterPx = hud.ringDiameterPx,
                    ringMaxDiameterPx = hud.ringMaxDiameterPx,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp),
                )
            }

            AnimatedVisibility(
                visible = state.layerPanelOpen,
                enter = fadeIn() + slideInHorizontally { it / 2 },
                exit = fadeOut() + slideOutHorizontally { it / 2 },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 8.dp, bottom = 8.dp),
            ) {
                LayerPanel(state = state, actions = actions)
            }

            val adjustmentEditor = state.adjustmentEditor
            val gradient = state.gradient
            if (gradient != null) {
                GradientBar(
                    gradient = gradient,
                    actions = actions,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp),
                )
            }
            if (state.transform != null) {
                TransformBar(
                    actions = actions,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp),
                )
            }
            if (adjustmentEditor != null) {
                AdjustmentEditor(
                    editor = adjustmentEditor,
                    actions = actions,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 8.dp, end = 8.dp, bottom = 64.dp),
                )
            }

            if (state.colorPanelOpen) {
                val offsetX = with(LocalDensity.current) { 72.dp.roundToPx() }
                Popup(
                    alignment = Alignment.CenterStart,
                    offset = IntOffset(offsetX, 0),
                    onDismissRequest = { actions.toggleColorPanel() },
                    properties = PopupProperties(focusable = true),
                ) {
                    ColorPanel(state = state, actions = actions)
                }
            }
        }
    }
}

@Composable
private fun StudioTopBar(state: StudioUiState, actions: StudioActions, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PanelSurface {
            Row(Modifier.padding(2.dp)) {
                PainterIconButton(Icons.Outlined.Home, stringResource(R.string.cd_home), actions::openHome)
                PainterIconButton(Icons.Outlined.AutoAwesome, stringResource(R.string.creative_tools), actions::openCreativeTools)
                var toolsMenu by remember { mutableStateOf(false) }
                Box {
                    PainterIconButton(
                        Icons.Outlined.Tune,
                        stringResource(R.string.editing_tools),
                        { toolsMenu = true },
                        tint = if (state.tool == ToolUi.BRUSH) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    )
                    DropdownMenu(expanded = toolsMenu, onDismissRequest = { toolsMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tool_rect_select)) },
                            onClick = {
                                toolsMenu = false
                                actions.selectTool(ToolUi.SELECT_RECT)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tool_ellipse_select)) },
                            onClick = {
                                toolsMenu = false
                                actions.selectTool(ToolUi.SELECT_ELLIPSE)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tool_lasso_select)) },
                            onClick = {
                                toolsMenu = false
                                actions.selectTool(ToolUi.SELECT_LASSO)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tool_magic_wand)) },
                            onClick = {
                                toolsMenu = false
                                actions.selectTool(ToolUi.SELECT_WAND)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tool_transform)) },
                            onClick = {
                                toolsMenu = false
                                actions.selectTool(ToolUi.TRANSFORM)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tool_fill)) },
                            onClick = {
                                toolsMenu = false
                                actions.selectTool(ToolUi.FILL)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tool_gradient)) },
                            onClick = {
                                toolsMenu = false
                                actions.selectTool(ToolUi.GRADIENT)
                            },
                        )
                        if (state.hasSelection) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.deselect)) },
                                onClick = {
                                    toolsMenu = false
                                    actions.deselect()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.select_all)) },
                            onClick = {
                                toolsMenu = false
                                actions.selectAll()
                            },
                        )
                        if (state.hasSelection) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.invert_selection)) },
                                onClick = {
                                    toolsMenu = false
                                    actions.invertSelection()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.expand_selection)) },
                                onClick = {
                                    toolsMenu = false
                                    actions.expandSelection(4f)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.contract_selection)) },
                                onClick = {
                                    toolsMenu = false
                                    actions.expandSelection(-4f)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.feather_selection)) },
                                onClick = {
                                    toolsMenu = false
                                    actions.featherSelection(4f)
                                },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        PanelSurface {
            Row(Modifier.padding(2.dp)) {
                IconToggle(
                    Icons.Outlined.Layers,
                    stringResource(R.string.layers),
                    checked = state.layerPanelOpen,
                    onCheckedChange = { actions.toggleLayerPanel() },
                )
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    PainterIconButton(Icons.Outlined.MoreVert, stringResource(R.string.options), {
                        menuOpen = true
                        actions.openOptions()
                    })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (state.projectName.isNotEmpty()) {
                            Text(
                                state.projectName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.save)) },
                            onClick = { menuOpen = false; actions.save() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_image)) },
                            onClick = { menuOpen = false; actions.exportImage() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings)) },
                            onClick = { menuOpen = false; actions.openSettings() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UndoRedoBar(state: StudioUiState, actions: StudioActions, modifier: Modifier = Modifier) {
    PanelSurface(modifier) {
        Row(Modifier.padding(2.dp)) {
            val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            PainterIconButton(
                Icons.AutoMirrored.Outlined.Undo,
                stringResource(R.string.undo),
                onClick = { if (state.canUndo) actions.undo() },
                onLongClick = actions::clearLayerLongPress,
                tint = if (state.canUndo) MaterialTheme.colorScheme.onSurface else dim,
            )
            AnimatedVisibility(visible = state.canRedo) {
                PainterIconButton(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.redo), actions::redo)
            }
        }
    }
}
