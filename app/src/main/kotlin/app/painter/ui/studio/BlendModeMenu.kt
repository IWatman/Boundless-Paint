package app.painter.ui.studio

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.blend.BlendMode

/** Localized name of a blend mode. */
@Composable
fun blendModeLabel(mode: BlendMode): String = when (mode) {
    BlendMode.NORMAL -> stringResource(R.string.blend_normal)
    BlendMode.PASSTHROUGH -> stringResource(R.string.blend_passthrough)
    BlendMode.DARKEN -> stringResource(R.string.blend_darken)
    BlendMode.MULTIPLY -> stringResource(R.string.blend_multiply)
    BlendMode.COLOR_BURN -> stringResource(R.string.blend_color_burn)
    BlendMode.LINEAR_BURN -> stringResource(R.string.blend_linear_burn)
    BlendMode.DARKER_COLOR -> stringResource(R.string.blend_darker_color)
    BlendMode.LIGHTEN -> stringResource(R.string.blend_lighten)
    BlendMode.SCREEN -> stringResource(R.string.blend_screen)
    BlendMode.COLOR_DODGE -> stringResource(R.string.blend_color_dodge)
    BlendMode.LINEAR_DODGE -> stringResource(R.string.blend_linear_dodge)
    BlendMode.ADD -> stringResource(R.string.blend_add)
    BlendMode.LIGHTER_COLOR -> stringResource(R.string.blend_lighter_color)
    BlendMode.OVERLAY -> stringResource(R.string.blend_overlay)
    BlendMode.SOFT_LIGHT -> stringResource(R.string.blend_soft_light)
    BlendMode.HARD_LIGHT -> stringResource(R.string.blend_hard_light)
    BlendMode.VIVID_LIGHT -> stringResource(R.string.blend_vivid_light)
    BlendMode.LINEAR_LIGHT -> stringResource(R.string.blend_linear_light)
    BlendMode.PIN_LIGHT -> stringResource(R.string.blend_pin_light)
    BlendMode.HARD_MIX -> stringResource(R.string.blend_hard_mix)
    BlendMode.DIFFERENCE -> stringResource(R.string.blend_difference)
    BlendMode.EXCLUSION -> stringResource(R.string.blend_exclusion)
    BlendMode.NEGATIVE -> stringResource(R.string.blend_negative)
    BlendMode.INVERT -> stringResource(R.string.blend_invert)
    BlendMode.SUBTRACT -> stringResource(R.string.blend_subtract)
    BlendMode.DIVIDE -> stringResource(R.string.blend_divide)
    BlendMode.HUE -> stringResource(R.string.blend_hue)
    BlendMode.SATURATION -> stringResource(R.string.blend_saturation)
    BlendMode.COLOR -> stringResource(R.string.blend_color)
    BlendMode.LUMINOSITY -> stringResource(R.string.blend_luminosity)
    BlendMode.ERASE -> stringResource(R.string.blend_erase)
    BlendMode.MASK -> stringResource(R.string.blend_mask)
    BlendMode.LINE_ART -> stringResource(R.string.blend_line_art)
}

/** Localized name of a blend mode group (menu section header). */
@Composable
fun blendGroupLabel(group: BlendMode.Group): String = when (group) {
    BlendMode.Group.NORMAL -> stringResource(R.string.blend_group_normal)
    BlendMode.Group.DARKEN -> stringResource(R.string.blend_group_darken)
    BlendMode.Group.LIGHTEN -> stringResource(R.string.blend_group_lighten)
    BlendMode.Group.CONTRAST -> stringResource(R.string.blend_group_contrast)
    BlendMode.Group.COMPARATIVE -> stringResource(R.string.blend_group_comparative)
    BlendMode.Group.HSL -> stringResource(R.string.blend_group_hsl)
    BlendMode.Group.MASKING -> stringResource(R.string.blend_group_masking)
}

/**
 * Content of the blend mode dropdown: every mode, grouped by [BlendMode.Group].
 * [includePassthrough] is true only for group rows.
 */
@Composable
fun BlendModeMenuContent(
    current: BlendMode,
    includePassthrough: Boolean,
    onPick: (BlendMode) -> Unit,
) {
    BlendMode.Group.entries.forEach { group ->
        Text(
            blendGroupLabel(group),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        )
        BlendMode.inGroup(group).forEach { mode ->
            if (mode == BlendMode.PASSTHROUGH && !includePassthrough) return@forEach
            val selected = mode == current
            DropdownMenuItem(
                text = {
                    Text(
                        blendModeLabel(mode),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    )
                },
                onClick = { onPick(mode) },
            )
        }
    }
}
