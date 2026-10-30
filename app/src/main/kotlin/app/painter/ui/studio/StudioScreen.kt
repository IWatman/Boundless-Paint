package app.painter.ui.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import app.painter.core.perspective.PerspectiveType
import app.painter.core.shape.ShapeTool
import app.painter.core.symmetry.MirrorAxes
import app.painter.core.symmetry.SymmetryType
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
        CloneOverlay(state = state, modifier = Modifier.fillMaxSize())
        ShapeOverlay(state = state, modifier = Modifier.fillMaxSize())
        SymmetryOverlay(state = state, actions = actions, modifier = Modifier.fillMaxSize())
        GuidesOverlay(state = state, actions = actions, modifier = Modifier.fillMaxSize())

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
            if (state.tool == ToolUi.CLONE && state.cloneSource == null) {
                PanelSurface(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp),
                ) {
                    Text(
                        stringResource(R.string.clone_hint),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
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
            val shapeUi = state.shape
            if (shapeUi != null) {
                ShapeBar(
                    shape = shapeUi,
                    actions = actions,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp),
                )
            }
            val ruler = state.ruler
            val ellipse = state.ellipse
            val lazy = state.lazy
            val perspective = state.perspective
            if (ruler != null || ellipse != null || lazy != null || perspective != null) {
                GuidesBar(
                    ruler = ruler,
                    ellipse = ellipse,
                    lazy = lazy,
                    perspective = perspective,
                    actions = actions,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = if (state.symmetry != null) 128.dp else 64.dp, end = 16.dp),
                )
            }
            val symmetry = state.symmetry
            if (symmetry != null) {
                SymmetryBar(
                    symmetry = symmetry,
                    actions = actions,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 64.dp, end = 16.dp),
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

            if (state.brushBrowserOpen) {
                val brushOffsetX = with(LocalDensity.current) { 112.dp.roundToPx() }
                Popup(
                    alignment = Alignment.CenterStart,
                    offset = IntOffset(brushOffsetX, 0),
                    onDismissRequest = { actions.toggleBrushBrowser() },
                    properties = PopupProperties(focusable = true),
                ) {
                    BrushBrowser(state = state, actions = actions)
                }
            }

            if (state.stylusPanelOpen) {
                Popup(
                    alignment = Alignment.TopEnd,
                    onDismissRequest = { actions.toggleStylusPanel() },
                    properties = PopupProperties(focusable = true),
                ) {
                    StylusSettingsPanel(
                        settings = state.stylusSettings,
                        calibration = state.calibration,
                        actions = actions,
                        onClose = { actions.toggleStylusPanel() },
                        modifier = Modifier.padding(top = 56.dp, end = 8.dp),
                    )
                }
            }

            if (state.editorParams != null) {
                Popup(
                    alignment = Alignment.TopEnd,
                    onDismissRequest = { actions.cancelEditor() },
                    properties = PopupProperties(focusable = true),
                ) {
                    BrushEditor(
                        state = state,
                        actions = actions,
                        modifier = Modifier.padding(top = 56.dp, end = 8.dp, bottom = 8.dp),
                    )
                }
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

/** Leading icon of a checkable menu item: a check mark while [on], nothing otherwise. */
private fun menuCheck(on: Boolean): (@Composable () -> Unit)? =
    if (on) {
        { Icon(Icons.Outlined.Check, contentDescription = null) }
    } else null

/** [type] to pass to [StudioActions.setPerspective]: null when it is already the active grid (turns it off). */
private fun perspectiveToggle(current: PerspectiveUi?, type: PerspectiveType): PerspectiveType? =
    if (current?.type == type) null else type

/** Items of the shape section of the creative tools menu: the shape a drag creates and its label. */
private val SHAPE_MENU = listOf(
    ShapeTool.LINE to R.string.shape_line,
    ShapeTool.RECT to R.string.shape_rect,
    ShapeTool.ELLIPSE to R.string.shape_ellipse,
    ShapeTool.POLYGON to R.string.shape_polygon,
    ShapeTool.ARC to R.string.shape_arc,
)

/** Checkable creative-tools item of perspective [type]; [active] draws the check mark. */
@Composable
private fun PerspectiveMenuItem(type: PerspectiveType, label: String, active: Boolean, onPick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = menuCheck(active),
        onClick = onPick,
    )
}

/** Groups of the creative tools menu; tapping a row opens the items of the group. */
private enum class CreativeGroup(val titleRes: Int) {
    SYMMETRY(R.string.symmetry_title),
    GUIDES(R.string.menu_guides),
    PERSPECTIVE(R.string.persp_title),
    SHAPES(R.string.menu_shapes),
}

/** Separator of the names in the summary line of a group. */
private const val SUMMARY_SEPARATOR = " · "

/** Label of the symmetry that is on, or null while symmetry is off. */
@Composable
private fun symmetryMenuLabel(symmetry: SymmetryUi?): String? {
    val current = symmetry ?: return null
    return when (current.type) {
        SymmetryType.MIRROR -> when (current.axes) {
            MirrorAxes.V -> stringResource(R.string.symmetry_mirror_v)
            MirrorAxes.H -> stringResource(R.string.symmetry_mirror_h)
            MirrorAxes.VH -> stringResource(R.string.symmetry_mirror_vh)
        }
        SymmetryType.RADIAL -> stringResource(R.string.symmetry_radial)
        SymmetryType.KALEIDO -> stringResource(R.string.symmetry_kaleido)
    }
}

/** Label of the perspective grid [type]. */
@Composable
private fun perspectiveMenuLabel(type: PerspectiveType): String = when (type) {
    PerspectiveType.ONE_POINT -> stringResource(R.string.persp_1pt)
    PerspectiveType.TWO_POINT -> stringResource(R.string.persp_2pt)
    PerspectiveType.THREE_POINT -> stringResource(R.string.persp_3pt)
    PerspectiveType.ISO -> stringResource(R.string.persp_iso)
    PerspectiveType.GRID_2D -> stringResource(R.string.persp_grid)
}

/** Names of the guides that are on, joined for the summary line of the guides group. */
@Composable
private fun guidesMenuLabel(state: StudioUiState): String? {
    val on = listOfNotNull(
        if (state.ruler != null) stringResource(R.string.guide_ruler) else null,
        if (state.ellipse != null) stringResource(R.string.guide_ellipse) else null,
        if (state.lazy != null) stringResource(R.string.guide_lazy) else null,
    )
    return if (on.isEmpty()) null else on.joinToString(SUMMARY_SEPARATOR)
}

/** Summary line of a group: the name of the item that is on, or null when everything in it is off. */
@Composable
private fun CreativeGroup.summary(state: StudioUiState): String? = when (this) {
    CreativeGroup.SYMMETRY -> symmetryMenuLabel(state.symmetry)
    CreativeGroup.GUIDES -> guidesMenuLabel(state)
    CreativeGroup.PERSPECTIVE -> state.perspective?.let { perspectiveMenuLabel(it.type) }
    CreativeGroup.SHAPES -> null
}

/** Top-level row of the creative tools menu: opens [group] and summarises what is on in it. */
@Composable
private fun CreativeGroupItem(group: CreativeGroup, state: StudioUiState, onOpen: () -> Unit) {
    val summary = group.summary(state)
    DropdownMenuItem(
        text = {
            Column {
                Text(stringResource(group.titleRes))
                if (summary != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .padding(end = 6.dp)
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                        Text(
                            summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        trailingIcon = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        onClick = onOpen,
    )
}

/** First row of an opened group: goes back to the top level of the menu. */
@Composable
private fun CreativeBackItem(group: CreativeGroup, onBack: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(group.titleRes)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null) },
        onClick = onBack,
    )
    HorizontalDivider()
}

/** Symmetry items: off, the three mirrors, Radial and Kaleido. */
@Composable
private fun SymmetryMenuItems(actions: StudioActions, close: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.symmetry_off)) },
        onClick = {
            close()
            actions.setSymmetry(null)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.symmetry_mirror_v)) },
        onClick = {
            close()
            actions.setSymmetry(SymmetryType.MIRROR, MirrorAxes.V)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.symmetry_mirror_h)) },
        onClick = {
            close()
            actions.setSymmetry(SymmetryType.MIRROR, MirrorAxes.H)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.symmetry_mirror_vh)) },
        onClick = {
            close()
            actions.setSymmetry(SymmetryType.MIRROR, MirrorAxes.VH)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.symmetry_radial)) },
        onClick = {
            close()
            actions.setSymmetry(SymmetryType.RADIAL)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.symmetry_kaleido)) },
        onClick = {
            close()
            actions.setSymmetry(SymmetryType.KALEIDO)
        },
    )
}

/** Guide items: the ruler, the ellipse and the lazy rope; the check mark shows which of them are on. */
@Composable
private fun GuideMenuItems(state: StudioUiState, actions: StudioActions, close: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.guide_ruler)) },
        leadingIcon = menuCheck(state.ruler != null),
        onClick = {
            close()
            actions.setRuler(state.ruler == null)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.guide_ellipse)) },
        leadingIcon = menuCheck(state.ellipse != null),
        onClick = {
            close()
            actions.setEllipseGuide(state.ellipse == null)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.guide_lazy)) },
        leadingIcon = menuCheck(state.lazy != null),
        onClick = {
            close()
            actions.setLazy(state.lazy == null)
        },
    )
}

/** Perspective items: picking the grid that is already on turns it off. */
@Composable
private fun PerspectiveMenuItems(state: StudioUiState, actions: StudioActions, close: () -> Unit) {
    PerspectiveMenuItem(
        PerspectiveType.ONE_POINT,
        stringResource(R.string.persp_1pt),
        state.perspective?.type == PerspectiveType.ONE_POINT,
    ) {
        close()
        actions.setPerspective(perspectiveToggle(state.perspective, PerspectiveType.ONE_POINT))
    }
    PerspectiveMenuItem(
        PerspectiveType.TWO_POINT,
        stringResource(R.string.persp_2pt),
        state.perspective?.type == PerspectiveType.TWO_POINT,
    ) {
        close()
        actions.setPerspective(perspectiveToggle(state.perspective, PerspectiveType.TWO_POINT))
    }
    PerspectiveMenuItem(
        PerspectiveType.THREE_POINT,
        stringResource(R.string.persp_3pt),
        state.perspective?.type == PerspectiveType.THREE_POINT,
    ) {
        close()
        actions.setPerspective(perspectiveToggle(state.perspective, PerspectiveType.THREE_POINT))
    }
    PerspectiveMenuItem(
        PerspectiveType.ISO,
        stringResource(R.string.persp_iso),
        state.perspective?.type == PerspectiveType.ISO,
    ) {
        close()
        actions.setPerspective(perspectiveToggle(state.perspective, PerspectiveType.ISO))
    }
    PerspectiveMenuItem(
        PerspectiveType.GRID_2D,
        stringResource(R.string.persp_grid),
        state.perspective?.type == PerspectiveType.GRID_2D,
    ) {
        close()
        actions.setPerspective(perspectiveToggle(state.perspective, PerspectiveType.GRID_2D))
    }
}

/** Shape items: the shape a drag creates. */
@Composable
private fun ShapeMenuItems(actions: StudioActions, close: () -> Unit) {
    for ((shapeTool, label) in SHAPE_MENU) {
        DropdownMenuItem(
            text = { Text(stringResource(label)) },
            onClick = {
                close()
                actions.selectShapeTool(shapeTool)
            },
        )
    }
}

/** Whether any tool of the creative menu is on; tints its icon in the primary color. */
private fun creativeToolsActive(state: StudioUiState): Boolean =
    state.symmetry != null || state.ruler != null || state.ellipse != null ||
        state.lazy != null || state.perspective != null

/**
 * "Creative tools" icon of the top bar with its two-level menu: the list of groups, then the items of the
 * opened group. Dismissing the menu returns it to the list of groups.
 */
@Composable
private fun CreativeToolsButton(state: StudioUiState, actions: StudioActions) {
    var open by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf<CreativeGroup?>(null) }
    val close = { open = false }
    Box {
        PainterIconButton(
            Icons.Outlined.AutoAwesome,
            stringResource(R.string.creative_tools),
            {
                group = null
                open = true
            },
            tint = if (creativeToolsActive(state)) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = {
                close()
                group = null
            },
        ) {
            val current = group
            if (current == null) {
                for (entry in CreativeGroup.entries) {
                    CreativeGroupItem(entry, state) { group = entry }
                }
            } else {
                CreativeBackItem(current) { group = null }
                when (current) {
                    CreativeGroup.SYMMETRY -> SymmetryMenuItems(actions, close)
                    CreativeGroup.GUIDES -> GuideMenuItems(state, actions, close)
                    CreativeGroup.PERSPECTIVE -> PerspectiveMenuItems(state, actions, close)
                    CreativeGroup.SHAPES -> ShapeMenuItems(actions, close)
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
                CreativeToolsButton(state = state, actions = actions)
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
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tool_clone)) },
                            onClick = {
                                toolsMenu = false
                                actions.selectTool(ToolUi.CLONE)
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
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.stylus)) },
                            onClick = { menuOpen = false; actions.toggleStylusPanel() },
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
