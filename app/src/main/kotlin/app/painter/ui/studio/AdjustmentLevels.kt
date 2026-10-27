package app.painter.ui.studio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.adjust.AdjustParams
import app.painter.core.adjust.LevelsChannel
import app.painter.core.adjust.LevelsParams

/**
 * Levels: one channel selected by tabs (RGB master, then R/G/B), with the input black/gray/white points
 * and the output black/white points of that channel.
 */
@Composable
internal fun LevelsControls(p: LevelsParams, onChange: (AdjustParams) -> Unit, onCommit: () -> Unit) {
    var channel by rememberSaveable { mutableIntStateOf(0) }
    val level = when (channel) {
        0 -> p.rgb
        1 -> p.r
        2 -> p.g
        else -> p.b
    }
    val set: (LevelsChannel) -> Unit = { c ->
        onChange(
            when (channel) {
                0 -> p.copy(rgb = c)
                1 -> p.copy(r = c)
                2 -> p.copy(g = c)
                else -> p.copy(b = c)
            },
        )
    }
    Column(Modifier.fillMaxWidth()) {
        AdjustTabs(
            labels = listOf(
                stringResource(R.string.adjust_channel_rgb),
                stringResource(R.string.adjust_channel_r),
                stringResource(R.string.adjust_channel_g),
                stringResource(R.string.adjust_channel_b),
            ),
            selected = channel,
            onSelect = { channel = it },
        )
        AdjustSlider(
            stringResource(R.string.adjust_input_black), level.inBlack, 0f..253f,
            { set(level.copy(inBlack = it.coerceAtMost(level.inWhite - 2f))) },
            onCommit,
            modifier = Modifier.padding(top = 4.dp),
        )
        AdjustSlider(
            stringResource(R.string.adjust_input_gamma), level.gamma, 0.1f..9.99f,
            { set(level.copy(gamma = it)) }, onCommit, step = 0.05f, decimals = 2,
        )
        AdjustSlider(
            stringResource(R.string.adjust_input_white), level.inWhite, 2f..255f,
            { set(level.copy(inWhite = it.coerceAtLeast(level.inBlack + 2f))) }, onCommit,
        )
        AdjustSlider(
            stringResource(R.string.adjust_output_black), level.outBlack, 0f..255f,
            { set(level.copy(outBlack = it)) }, onCommit,
        )
        AdjustSlider(
            stringResource(R.string.adjust_output_white), level.outWhite, 0f..255f,
            { set(level.copy(outWhite = it)) }, onCommit,
        )
    }
}
