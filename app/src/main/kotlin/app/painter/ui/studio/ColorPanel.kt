package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.color.Rgba
import app.painter.ui.components.ColorSwatch
import app.painter.ui.components.HsvUi
import app.painter.ui.components.LinearSlider
import app.painter.ui.components.PainterIconButton
import app.painter.ui.components.PanelSurface
import app.painter.ui.components.hueColor
import app.painter.ui.components.parseHexColor
import app.painter.ui.components.sameAs
import app.painter.ui.components.toColor
import app.painter.ui.components.toHexString
import app.painter.ui.components.toHsvUi
import app.painter.ui.components.toRgba
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val RECENT_LIMIT = 10
private const val PALETTE_COLUMNS = 8

@Composable
internal fun ColorPanel(state: StudioUiState, actions: StudioActions, modifier: Modifier = Modifier) {
    var hsv by remember { mutableStateOf(state.color.toHsvUi()) }
    val emitted = remember { ArrayDeque<Rgba>() }
    remember(state.color) {
        val external = state.color
        if (!hsv.toRgba(external.a).sameAs(external) && emitted.none { it.sameAs(external) }) {
            val n = external.toHsvUi()
            hsv = when {
                n.v == 0f -> n.copy(h = hsv.h, s = hsv.s)
                n.s == 0f -> n.copy(h = hsv.h)
                else -> n
            }
        }
        external
    }
    val alpha = state.color.a
    val update: (HsvUi, Boolean) -> Unit = { c, commit ->
        hsv = c
        val rgba = c.toRgba(alpha)
        emitted.addLast(rgba)
        if (emitted.size > 8) emitted.removeFirst()
        actions.setColor(rgba, commit)
    }
    val setRgba: (Rgba, Boolean) -> Unit = { c, commit -> actions.setColor(c, commit) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    PanelSurface(modifier.width(320.dp)) {
        Column(Modifier.padding(bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.clip(RoundedCornerShape(8.dp))) {
                    Box(Modifier.clickable { setRgba(state.previousColor, true) }) {
                        ColorSwatch(
                            state.previousColor,
                            size = 40.dp,
                            shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                        )
                    }
                    ColorSwatch(
                        state.color,
                        size = 40.dp,
                        shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "#" + state.color.toHexString(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                PainterIconButton(
                    Icons.Outlined.Add,
                    stringResource(R.string.add_to_palette),
                    onClick = { actions.addColorToPalette(state.color) },
                )
            }
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                listOf(R.string.tab_wheel, R.string.tab_sliders, R.string.tab_palette).forEachIndexed { i, res ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(stringResource(res)) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
                when (tab) {
                    0 -> HueRingSquare(hsv, update, Modifier.align(Alignment.Center))
                    1 -> ColorSliders(hsv, state.color, update, setRgba)
                    else -> PaletteTab(state, setRgba)
                }
            }
        }
    }
}

@Composable
private fun HueRingSquare(hsv: HsvUi, onChange: (HsvUi, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val currentHsv by rememberUpdatedState(hsv)
    val currentOnChange by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .size(260.dp)
            .pointerInput(Unit) {
                val ringW = 26.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val outer = minOf(size.width, size.height) / 2f
                    val inner = outer - ringW - 6.dp.toPx()
                    val side = inner * sqrt(2f)
                    val onRing = hypot(down.position.x - c.x, down.position.y - c.y) >= inner
                    fun apply(p: Offset, commit: Boolean) {
                        val h0 = currentHsv
                        val next = if (onRing) {
                            var deg = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()
                            if (deg < 0f) deg += 360f
                            h0.copy(h = deg)
                        } else {
                            val s = ((p.x - (c.x - side / 2)) / side).coerceIn(0f, 1f)
                            val v = 1f - ((p.y - (c.y - side / 2)) / side).coerceIn(0f, 1f)
                            h0.copy(s = s, v = v)
                        }
                        currentOnChange(next, commit)
                    }
                    apply(down.position, false)
                    down.consume()
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        last = change.position
                        apply(last, false)
                        change.consume()
                    }
                    apply(last, true)
                }
            },
    ) {
        val ringW = 26.dp.toPx()
        val outer = size.minDimension / 2f
        val ringR = outer - ringW / 2f
        val hues = (0..6).map { hueColor(it * 60f) }
        drawCircle(Brush.sweepGradient(hues, center), ringR, center, style = Stroke(ringW))

        val inner = outer - ringW - 6.dp.toPx()
        val side = inner * sqrt(2f)
        val tl = Offset(center.x - side / 2f, center.y - side / 2f)
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor(hsv.h)), tl.x, tl.x + side), tl, Size(side, side))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), tl.y, tl.y + side), tl, Size(side, side))

        val rad = Math.toRadians(hsv.h.toDouble())
        val hp = Offset(center.x + ringR * cos(rad).toFloat(), center.y + ringR * sin(rad).toFloat())
        val mr = ringW / 2f - 2.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.5f), mr + 1.dp.toPx(), hp, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White, mr, hp, style = Stroke(2.dp.toPx()))

        val sp = Offset(tl.x + hsv.s * side, tl.y + (1f - hsv.v) * side)
        drawCircle(hsv.toRgba().toColor(), 8.dp.toPx(), sp)
        drawCircle(Color.White, 8.dp.toPx(), sp, style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.5f), 9.dp.toPx(), sp, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun ColorSliders(
    hsv: HsvUi,
    color: Rgba,
    onHsv: (HsvUi, Boolean) -> Unit,
    onRgba: (Rgba, Boolean) -> Unit,
) {
    var rgbMode by rememberSaveable { mutableStateOf(false) }
    Column {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !rgbMode,
                onClick = { rgbMode = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text(stringResource(R.string.hsb)) }
            SegmentedButton(
                selected = rgbMode,
                onClick = { rgbMode = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text(stringResource(R.string.rgb)) }
        }
        Spacer(Modifier.height(8.dp))
        if (!rgbMode) {
            LinearSlider(
                stringResource(R.string.hue), hsv.h, { onHsv(hsv.copy(h = it), false) },
                valueRange = 0f..360f, step = 1f, unit = "°",
                onValueChangeFinished = { onHsv(hsv, true) },
            )
            LinearSlider(
                stringResource(R.string.saturation), hsv.s, { onHsv(hsv.copy(s = it), false) },
                unit = "%", displayScale = 100f,
                onValueChangeFinished = { onHsv(hsv, true) },
            )
            LinearSlider(
                stringResource(R.string.brightness), hsv.v, { onHsv(hsv.copy(v = it), false) },
                unit = "%", displayScale = 100f,
                onValueChangeFinished = { onHsv(hsv, true) },
            )
        } else {
            val step = 1f / 255f
            LinearSlider(
                stringResource(R.string.red), color.r, { onRgba(color.copy(r = it), false) },
                step = step, displayScale = 255f, onValueChangeFinished = { onRgba(color, true) },
            )
            LinearSlider(
                stringResource(R.string.green), color.g, { onRgba(color.copy(g = it), false) },
                step = step, displayScale = 255f, onValueChangeFinished = { onRgba(color, true) },
            )
            LinearSlider(
                stringResource(R.string.blue), color.b, { onRgba(color.copy(b = it), false) },
                step = step, displayScale = 255f, onValueChangeFinished = { onRgba(color, true) },
            )
        }
        val hex = color.toHexString()
        var text by remember(hex) { mutableStateOf(hex) }
        val parsed = parseHexColor(text, color.a)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(7) },
            singleLine = true,
            isError = parsed == null,
            prefix = { Text("#") },
            label = { Text(stringResource(R.string.hex)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { parsed?.let { onRgba(it, true) } }),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun PaletteTab(state: StudioUiState, onRgba: (Rgba, Boolean) -> Unit) {
    Column(Modifier.height(260.dp).verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.palette),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        if (state.palette.isEmpty()) {
            Text(
                stringResource(R.string.palette_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SwatchGrid(state.palette, state.color, onRgba)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.recent_colors),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SwatchGrid(state.recentColors.take(RECENT_LIMIT), state.color, onRgba)
    }
}

@Composable
private fun SwatchGrid(colors: List<Rgba>, current: Rgba, onRgba: (Rgba, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.chunked(PALETTE_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onRgba(c, true) },
                    ) {
                        ColorSwatch(c, size = 30.dp, shape = RoundedCornerShape(6.dp), selected = c.sameAs(current))
                    }
                }
            }
        }
    }
}
