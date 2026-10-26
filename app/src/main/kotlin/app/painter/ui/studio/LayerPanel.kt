package app.painter.ui.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.painter.R
import app.painter.core.blend.BlendMode
import app.painter.core.color.Rgba
import app.painter.ui.components.ColorSwatch
import app.painter.ui.components.IconToggle
import app.painter.ui.components.LinearSlider
import app.painter.ui.components.PainterIconButton
import app.painter.ui.components.PanelSurface
import app.painter.ui.components.RenameDialog
import app.painter.ui.components.checkerboard
import app.painter.ui.theme.AccentFill
import app.painter.ui.theme.PainterTheme
import kotlin.math.roundToInt

private val ExpandedRowHeight = 60.dp
private val CollapsedRowHeight = 64.dp
private val GroupArrowSize = 18.dp

private val PaperPresets = listOf(
    Rgba.WHITE,
    Rgba(0.96f, 0.94f, 0.88f),
    Rgba(0.85f, 0.85f, 0.85f),
    Rgba(0.5f, 0.5f, 0.5f),
    Rgba(0.17f, 0.17f, 0.17f),
    Rgba.BLACK,
)

/** Localized name of a blend mode. */
@Composable
fun blendModeLabel(mode: BlendMode): String = when (mode) {
    BlendMode.NORMAL -> stringResource(R.string.blend_normal)
    BlendMode.MULTIPLY -> stringResource(R.string.blend_multiply)
    BlendMode.SCREEN -> stringResource(R.string.blend_screen)
    BlendMode.OVERLAY -> stringResource(R.string.blend_overlay)
    BlendMode.DARKEN -> stringResource(R.string.blend_darken)
    BlendMode.LIGHTEN -> stringResource(R.string.blend_lighten)
    BlendMode.COLOR_DODGE -> stringResource(R.string.blend_color_dodge)
    BlendMode.COLOR_BURN -> stringResource(R.string.blend_color_burn)
    BlendMode.SOFT_LIGHT -> stringResource(R.string.blend_soft_light)
    BlendMode.HARD_LIGHT -> stringResource(R.string.blend_hard_light)
    BlendMode.ADD -> stringResource(R.string.blend_add)
    BlendMode.DIFFERENCE -> stringResource(R.string.blend_difference)
    else -> mode.id.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
internal fun LayerPanel(state: StudioUiState, actions: StudioActions, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val active = state.layers.firstOrNull { it.id == state.activeLayerId }
    PanelSurface(modifier.width(if (expanded) 296.dp else 80.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PainterIconButton(
                    if (expanded) Icons.AutoMirrored.Outlined.KeyboardArrowRight else Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    stringResource(if (expanded) R.string.collapse else R.string.expand),
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(36.dp),
                )
                if (expanded) {
                    Text(
                        stringResource(R.string.layers),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    Text(
                        "${state.layers.size}/${state.layerLimit}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                PainterIconButton(
                    Icons.Outlined.Add,
                    stringResource(R.string.add_layer),
                    onClick = actions::addLayer,
                    enabled = state.layers.size < state.layerLimit,
                    modifier = Modifier.size(36.dp),
                )
            }
            if (expanded && active != null) {
                LayerProperties(active, actions)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LayerList(
                state = state,
                actions = actions,
                expanded = expanded,
                modifier = Modifier.weight(1f, fill = false),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PaperRow(state, actions, expanded)
        }
    }
}

@Composable
private fun LayerProperties(layer: LayerUi, actions: StudioActions) {
    Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 4.dp)) {
        LinearSlider(
            label = stringResource(R.string.opacity),
            value = layer.opacity,
            onValueChange = { actions.setLayerOpacity(layer.id, it) },
            unit = "%",
            displayScale = 100f,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            var blendMenu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { blendMenu = true }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        blendModeLabel(layer.blendMode),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(Icons.Outlined.ArrowDropDown, stringResource(R.string.blend_mode))
                }
                DropdownMenu(expanded = blendMenu, onDismissRequest = { blendMenu = false }) {
                    BlendMode.M1.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    blendModeLabel(mode),
                                    fontWeight = if (mode == layer.blendMode) FontWeight.Bold else FontWeight.Normal,
                                    color = if (mode == layer.blendMode) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                )
                            },
                            onClick = {
                                blendMenu = false
                                actions.setLayerBlend(layer.id, mode)
                            },
                        )
                    }
                }
            }
            IconToggle(
                Icons.Outlined.SubdirectoryArrowRight,
                stringResource(R.string.clip_to_below),
                checked = layer.clipped,
                onCheckedChange = { actions.setLayerClipped(layer.id, it) },
            )
            IconToggle(
                Icons.Outlined.Lock,
                stringResource(R.string.alpha_lock),
                checked = layer.alphaLock,
                onCheckedChange = { actions.setLayerAlphaLock(layer.id, it) },
            )
        }
    }
}

@Composable
private fun LayerList(
    state: StudioUiState,
    actions: StudioActions,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val rowHeight = if (expanded) ExpandedRowHeight else CollapsedRowHeight
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val layers by rememberUpdatedState(state.layers)

    fun targetIndex(): Int {
        val from = layers.indexOfFirst { it.id == draggingId }
        if (from < 0) return -1
        return (from + (dragOffset / rowPx).roundToInt()).coerceIn(0, layers.lastIndex)
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        val from = layers.indexOfFirst { it.id == draggingId }
        val target = if (from >= 0) targetIndex() else -1
        state.layers.forEachIndexed { index, layer ->
            val isDragged = layer.id == draggingId
            val shift = when {
                isDragged || from < 0 -> 0f
                from < target && index in (from + 1)..target -> -rowPx
                from > target && index in target until from -> rowPx
                else -> 0f
            }
            val y = if (isDragged) dragOffset else shift
            Box(
                Modifier
                    .zIndex(if (isDragged) 1f else 0f)
                    .offset { IntOffset(0, y.roundToInt()) }
                    .pointerInput(layer.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingId = layer.id
                                dragOffset = 0f
                            },
                            onDragEnd = {
                                val t = targetIndex()
                                val id = draggingId
                                val f = layers.indexOfFirst { it.id == id }
                                draggingId = null
                                dragOffset = 0f
                                if (id != null && t >= 0 && t != f) actions.moveLayer(id, t)
                            },
                            onDragCancel = {
                                draggingId = null
                                dragOffset = 0f
                            },
                        ) { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                        }
                    },
            ) {
                val isActive = layer.id == state.activeLayerId
                if (expanded) {
                    ExpandedLayerRow(
                        layer = layer,
                        active = isActive,
                        lifted = isDragged,
                        canMergeDown = index < state.layers.lastIndex,
                        canDelete = state.layers.size > 1,
                        canDuplicate = state.layers.size < state.layerLimit,
                        actions = actions,
                        height = rowHeight,
                    )
                } else {
                    CollapsedLayerRow(layer, isActive, isDragged, actions, rowHeight)
                }
            }
        }
    }
}

@Composable
private fun LayerThumbnail(layer: LayerUi, size: Dp, active: Boolean) {
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .checkerboard(PainterTheme.colors.checkerLight, PainterTheme.colors.checkerDark, 5.dp)
            .border(
                if (active) 2.dp else 1.dp,
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            ),
    ) {
        val thumb = layer.thumbnail
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size).padding(1.dp),
            )
        }
        if (!layer.visible) {
            Box(Modifier.size(size).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.VisibilityOff, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CollapsedLayerRow(layer: LayerUi, active: Boolean, lifted: Boolean, actions: StudioActions, height: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(if (lifted) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable { actions.selectLayer(layer.id) },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (layer.clipped) {
                Icon(
                    Icons.Outlined.SubdirectoryArrowRight, null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LayerThumbnail(layer, 52.dp, active)
        }
    }
}

@Composable
private fun ExpandedLayerRow(
    layer: LayerUi,
    active: Boolean,
    lifted: Boolean,
    canMergeDown: Boolean,
    canDelete: Boolean,
    canDuplicate: Boolean,
    actions: StudioActions,
    height: Dp,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    val bg = when {
        lifted -> MaterialTheme.colorScheme.surfaceContainerHighest
        active -> AccentFill
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(bg)
            .clickable { actions.selectLayer(layer.id) }
            .padding(start = 8.dp + (layer.depth * 12).dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layer.clipped) {
            Icon(
                Icons.Outlined.SubdirectoryArrowRight, stringResource(R.string.clip_to_below),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
        }
        LayerThumbnail(layer, 46.dp, active)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                layer.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${blendModeLabel(layer.blendMode)} · ${(layer.opacity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (layer.alphaLock) {
                    Icon(
                        Icons.Outlined.Lock, stringResource(R.string.alpha_lock),
                        modifier = Modifier.padding(start = 4.dp).size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        PainterIconButton(
            if (layer.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            stringResource(R.string.visibility),
            onClick = { actions.setLayerVisible(layer.id, !layer.visible) },
            modifier = Modifier.size(40.dp).alpha(if (layer.visible) 1f else 0.6f),
        )
        Box {
            PainterIconButton(
                Icons.Outlined.MoreVert,
                stringResource(R.string.layer_menu),
                onClick = { menuOpen = true },
                modifier = Modifier.size(width = 32.dp, height = 40.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename)) },
                    onClick = { menuOpen = false; renameOpen = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.duplicate)) },
                    enabled = canDuplicate,
                    onClick = { menuOpen = false; actions.duplicateLayer(layer.id) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clear)) },
                    onClick = { menuOpen = false; actions.clearLayer(layer.id) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.merge_down)) },
                    enabled = canMergeDown,
                    onClick = { menuOpen = false; actions.mergeDown(layer.id) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    enabled = canDelete,
                    onClick = { menuOpen = false; actions.deleteLayer(layer.id) },
                )
            }
        }
    }
    if (renameOpen) {
        RenameDialog(
            title = stringResource(R.string.rename_layer),
            initial = layer.name,
            onDismiss = { renameOpen = false },
            onConfirm = {
                renameOpen = false
                actions.renameLayer(layer.id, it)
            },
        )
    }
}

@Composable
private fun PaperRow(state: StudioUiState, actions: StudioActions, expanded: Boolean) {
    var colorMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
    ) {
        Box {
            Box(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable { colorMenu = true }
                    .alpha(if (state.paperVisible) 1f else 0.4f),
            ) {
                ColorSwatch(state.paperColor, size = if (expanded) 36.dp else 44.dp, shape = MaterialTheme.shapes.small)
            }
            DropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }) {
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaperPresets.forEach { c ->
                        Box(
                            Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    colorMenu = false
                                    actions.setPaperColor(c)
                                },
                        ) {
                            ColorSwatch(c, size = 32.dp, shape = MaterialTheme.shapes.small)
                        }
                    }
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.paper_use_brush_color)) },
                    leadingIcon = { ColorSwatch(state.color, size = 20.dp) },
                    onClick = {
                        colorMenu = false
                        actions.setPaperColor(state.color.copy(a = 1f))
                    },
                )
                if (!expanded) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(if (state.paperVisible) R.string.paper_hide else R.string.paper_show))
                        },
                        onClick = {
                            colorMenu = false
                            actions.setPaperVisible(!state.paperVisible)
                        },
                    )
                }
            }
        }
        if (expanded) {
            Text(
                stringResource(R.string.paper),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            PainterIconButton(
                if (state.paperVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                stringResource(R.string.visibility),
                onClick = { actions.setPaperVisible(!state.paperVisible) },
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(32.dp))
        }
    }
}
