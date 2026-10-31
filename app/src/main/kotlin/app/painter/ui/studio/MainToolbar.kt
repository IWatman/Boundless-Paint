package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.painter.R
import app.painter.ui.components.ColorSwatch
import app.painter.ui.components.HsvUi
import app.painter.ui.components.IconToggle
import app.painter.ui.components.LinearSlider
import app.painter.ui.components.PanelColumn
import app.painter.ui.components.PanelSurface
import app.painter.ui.components.TouchTarget
import app.painter.ui.components.toHsvUi
import app.painter.ui.components.toRgba
import app.painter.ui.components.virtualSlider
import app.painter.ui.theme.AccentFill
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Parameter driven by the opacity button's virtual slider. */
enum class OpacityParam { OPACITY, FLOW, HARDNESS }

private const val MIN_BRUSH_SIZE = 1f

/** Size slider uses a square-root curve so small sizes get more travel. */
internal fun sizeToNorm(size: Float, max: Float): Float {
    val span = (max - MIN_BRUSH_SIZE).coerceAtLeast(1f)
    return sqrt(((size - MIN_BRUSH_SIZE) / span).coerceIn(0f, 1f))
}

internal fun normToSize(n: Float, max: Float): Float {
    val span = (max - MIN_BRUSH_SIZE).coerceAtLeast(1f)
    return MIN_BRUSH_SIZE + n * n * span
}

private fun pct(v: Float) = "${(v * 100f).roundToInt()}%"

@Composable
internal fun MainToolbar(
    state: StudioUiState,
    actions: StudioActions,
    onHud: (HudUi?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sizePopup by remember { mutableStateOf(false) }
    var opacityPopup by remember { mutableStateOf(false) }
    var opacityParam by rememberSaveable { mutableStateOf(OpacityParam.OPACITY) }

    val sizeLabel = stringResource(R.string.size)
    val brightnessLabel = stringResource(R.string.brightness)
    val paramLabels = mapOf(
        OpacityParam.OPACITY to stringResource(R.string.opacity),
        OpacityParam.FLOW to stringResource(R.string.flow),
        OpacityParam.HARDNESS to stringResource(R.string.hardness),
    )
    val density = LocalDensity.current
    val popupOffset = with(density) { IntOffset(56.dp.roundToPx(), 0) }

    fun sizeHud(size: Float): HudUi {
        val ring = size * state.viewZoom
        val maxRing = (state.brushSizeMax * state.viewZoom).takeIf { it <= 1200f }
        return HudUi(sizeLabel, "${size.roundToInt()} px", ring, maxRing)
    }

    PanelColumn(modifier.width(52.dp).padding(vertical = 4.dp)) {
        // STATUS debt 19: the active tool sits above the brush modes; a tap returns to the brush.
        if (state.tool != ToolUi.BRUSH) {
            IconToggle(
                activeToolIcon(state.tool), activeToolName(state.tool),
                checked = true,
                onCheckedChange = { actions.selectTool(ToolUi.BRUSH) },
            )
            HorizontalDivider(
                Modifier.width(28.dp).padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        IconToggle(
            Icons.Outlined.Brush, stringResource(R.string.mode_paint),
            checked = state.mode == BrushModeUi.PAINT && state.tool == ToolUi.BRUSH,
            onCheckedChange = {
                // A tap on the active mode opens the brush browser instead of switching mode.
                if (state.mode == BrushModeUi.PAINT && state.tool == ToolUi.BRUSH) actions.toggleBrushBrowser() else actions.setMode(BrushModeUi.PAINT)
            },
        )
        IconToggle(
            Icons.Outlined.BlurOn, stringResource(R.string.mode_blend),
            checked = state.mode == BrushModeUi.BLEND,
            onCheckedChange = { actions.setMode(BrushModeUi.BLEND) },
            dimmed = true,
        )
        IconToggle(
            Icons.Outlined.AutoFixNormal, stringResource(R.string.mode_erase),
            checked = state.mode == BrushModeUi.ERASE,
            onCheckedChange = {
                if (state.mode == BrushModeUi.ERASE) actions.toggleBrushBrowser() else actions.setMode(BrushModeUi.ERASE)
            },
        )
        HorizontalDivider(
            Modifier.width(28.dp).padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        // Size
        Box {
            val norm = sizeToNorm(state.brushSize, state.brushSizeMax)
            ToolbarButton(
                selected = sizePopup,
                description = sizeLabel,
                modifier = Modifier.virtualSlider(
                    value = norm,
                    onValueChange = { n ->
                        val s = normToSize(n, state.brushSizeMax)
                        actions.setBrushSize(s)
                        onHud(sizeHud(s))
                    },
                    onDragStart = { onHud(sizeHud(state.brushSize)) },
                    onDragEnd = { onHud(null) },
                    onTap = { sizePopup = !sizePopup },
                ),
            ) {
                val dot = MaterialTheme.colorScheme.onSurface
                Canvas(Modifier.size(22.dp)) {
                    val r = (size.minDimension / 2) * (0.15f + 0.85f * norm)
                    drawCircle(dot, r)
                }
                Text(
                    "${state.brushSize.roundToInt()}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (sizePopup) {
                Popup(
                    alignment = Alignment.CenterStart,
                    offset = popupOffset,
                    onDismissRequest = { sizePopup = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    PanelSurface(Modifier.width(280.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            LinearSlider(
                                label = sizeLabel,
                                value = state.brushSize,
                                onValueChange = { actions.setBrushSize(it) },
                                valueRange = MIN_BRUSH_SIZE..state.brushSizeMax,
                                step = 1f,
                                unit = "px",
                            )
                            LinearSlider(
                                label = paramLabels.getValue(OpacityParam.HARDNESS),
                                value = state.hardness,
                                onValueChange = { actions.setHardness(it) },
                                unit = "%",
                                displayScale = 100f,
                            )
                        }
                    }
                }
            }
        }

        // Color
        var dragHsv by remember { mutableStateOf(HsvUi(0f, 0f, 0f)) }
        ToolbarButton(
            selected = state.colorPanelOpen,
            description = stringResource(R.string.color),
            modifier = Modifier.virtualSlider(
                value = state.color.toHsvUi().v,
                onValueChange = { v ->
                    dragHsv = dragHsv.copy(v = v)
                    actions.setColor(dragHsv.toRgba(state.color.a), commit = false)
                    onHud(HudUi(brightnessLabel, pct(v)))
                },
                onDragStart = {
                    val base = state.color.toHsvUi()
                    dragHsv = base
                    onHud(HudUi(brightnessLabel, pct(base.v)))
                },
                onDragEnd = {
                    actions.setColor(dragHsv.toRgba(state.color.a), commit = true)
                    onHud(null)
                },
                onTap = { actions.toggleColorPanel() },
            ),
        ) {
            ColorSwatch(state.color, size = 30.dp)
        }

        // Opacity / flow / hardness
        Box {
            val paramValue = when (opacityParam) {
                OpacityParam.OPACITY -> state.opacity
                OpacityParam.FLOW -> state.flow
                OpacityParam.HARDNESS -> state.hardness
            }
            val setParam: (OpacityParam, Float) -> Unit = { p, v ->
                when (p) {
                    OpacityParam.OPACITY -> actions.setOpacity(v)
                    OpacityParam.FLOW -> actions.setFlow(v)
                    OpacityParam.HARDNESS -> actions.setHardness(v)
                }
            }
            val label = paramLabels.getValue(opacityParam)
            ToolbarButton(
                selected = opacityPopup,
                description = label,
                modifier = Modifier.virtualSlider(
                    value = paramValue,
                    onValueChange = { v ->
                        setParam(opacityParam, v)
                        onHud(HudUi(label, pct(v)))
                    },
                    onDragStart = { onHud(HudUi(label, pct(paramValue))) },
                    onDragEnd = { onHud(null) },
                    onTap = { opacityPopup = !opacityPopup },
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${(paramValue * 100f).roundToInt()}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        label.take(1).uppercase(),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (opacityPopup) {
                Popup(
                    alignment = Alignment.CenterStart,
                    offset = popupOffset,
                    onDismissRequest = { opacityPopup = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    PanelSurface(Modifier.width(280.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            OpacityParam.entries.forEach { p ->
                                val v = when (p) {
                                    OpacityParam.OPACITY -> state.opacity
                                    OpacityParam.FLOW -> state.flow
                                    OpacityParam.HARDNESS -> state.hardness
                                }
                                LinearSlider(
                                    label = paramLabels.getValue(p),
                                    value = v,
                                    onValueChange = { setParam(p, it) },
                                    unit = "%",
                                    displayScale = 100f,
                                    labelHighlighted = p == opacityParam,
                                    onLabelClick = { opacityParam = p },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
/** Icon of the active tool indicator of the main toolbar (STATUS debt 19). */
private fun activeToolIcon(tool: ToolUi): ImageVector = when (tool) {
    ToolUi.BRUSH -> Icons.Outlined.Brush
    ToolUi.SELECT_RECT, ToolUi.SELECT_ELLIPSE, ToolUi.SELECT_LASSO, ToolUi.SELECT_WAND,
    ToolUi.LASSO_FILL, ToolUi.LASSO_ERASE -> Icons.Outlined.SelectAll
    ToolUi.TRANSFORM -> Icons.Outlined.OpenWith
    ToolUi.FILL -> Icons.Outlined.FormatColorFill
    ToolUi.GRADIENT -> Icons.Outlined.Gradient
    ToolUi.SHAPE -> Icons.Outlined.Category
    ToolUi.CLONE -> Icons.Outlined.ContentCopy
    ToolUi.LIQUIFY -> Icons.Outlined.Waves
}

/** Localized name of [tool], the content description of the active tool indicator. */
@Composable
private fun activeToolName(tool: ToolUi): String = stringResource(
    when (tool) {
        ToolUi.BRUSH -> R.string.mode_paint
        ToolUi.SELECT_RECT -> R.string.tool_rect_select
        ToolUi.SELECT_ELLIPSE -> R.string.tool_ellipse_select
        ToolUi.SELECT_LASSO -> R.string.tool_lasso_select
        ToolUi.SELECT_WAND -> R.string.tool_magic_wand
        ToolUi.TRANSFORM -> R.string.tool_transform
        ToolUi.FILL -> R.string.tool_fill
        ToolUi.GRADIENT -> R.string.tool_gradient
        ToolUi.SHAPE -> R.string.shape_title
        ToolUi.CLONE -> R.string.tool_clone
        ToolUi.LIQUIFY -> R.string.tool_liquify
        ToolUi.LASSO_FILL -> R.string.tool_lasso_fill
        ToolUi.LASSO_ERASE -> R.string.tool_lasso_erase
    },
)

/** Square toolbar cell with an accent fill while [selected]; [description] is its content description. */
@Composable
private fun ToolbarButton(
    selected: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .size(TouchTarget)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) AccentFill else androidx.compose.ui.graphics.Color.Transparent)
            .semantics { contentDescription = description }
            .then(modifier),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
