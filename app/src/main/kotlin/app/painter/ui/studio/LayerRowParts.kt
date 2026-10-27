package app.painter.ui.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.core.doc.ColorTag
import app.painter.ui.components.checkerboard
import app.painter.ui.theme.PainterTheme

/** Width of the colour tag stripe drawn on the left edge of a layer row. */
val ColorTagStripeWidth = 4.dp

/** Colour of a tag (7 distinct hues); [ColorTag.NONE] has none. */
fun colorTagColor(tag: ColorTag): Color? = when (tag) {
    ColorTag.NONE -> null
    ColorTag.RED -> Color(0xFFE53935)
    ColorTag.ORANGE -> Color(0xFFFB8C00)
    ColorTag.YELLOW -> Color(0xFFFDD835)
    ColorTag.GREEN -> Color(0xFF43A047)
    ColorTag.BLUE -> Color(0xFF1E88E5)
    ColorTag.VIOLET -> Color(0xFF8E24AA)
    ColorTag.GRAY -> Color(0xFF757575)
}

/** Left edge stripe of a tagged row, as tall as the row. */
@Composable
fun ColorTagStripe(tag: ColorTag, height: Dp) {
    val color = colorTagColor(tag) ?: return
    Box(Modifier.width(ColorTagStripeWidth).height(height).background(color))
}

/** Expand/collapse arrow of a group row, in place of the pixel thumbnail. */
@Composable
fun GroupExpandArrow(layer: LayerUi, size: Dp, actions: StudioActions) {
    val expanded = layer.expanded
    Box(
        Modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .clickable(
                onClickLabel = stringResource(if (expanded) R.string.group_collapse else R.string.group_expand),
            ) { actions.toggleGroupExpanded(layer.id) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (expanded) Icons.Outlined.ArrowDropDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            stringResource(if (expanded) R.string.group_collapse else R.string.group_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Badge shown instead of the pixel thumbnail of an adjustment layer: the kind letters
 * ([adjustBadge]) on the surface colour, since the node holds no pixels.
 */
@Composable
fun AdjustmentThumbnail(layer: LayerUi, size: Dp, active: Boolean) {
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                if (active) 2.dp else 1.dp,
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            adjustBadge(layer.adjustKind),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (!layer.visible) {
            Box(
                Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.VisibilityOff, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Layer pixel thumbnail; [active] draws the accent border when painting targets the layer. */
@Composable
fun LayerThumbnail(layer: LayerUi, size: Dp, active: Boolean) {
    if (layer.isAdjustment) {
        AdjustmentThumbnail(layer, size, active)
        return
    }
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

/** Mask thumbnail right of the layer thumbnail: white border when the mask is the edit target. */
@Composable
fun MaskThumbnail(layer: LayerUi, size: Dp, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    val enabled = layer.maskEnabled
    val target = layer.isActiveTargetMask
    val diagonal = size * 0.75f
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                if (target) 2.dp else 1.dp,
                if (target) Color.White else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .alpha(if (enabled) 1f else 0.4f)
            .drawWithContent {
                drawContent()
                if (!enabled) {
                    drawLine(
                        Color.Black.copy(alpha = 0.6f),
                        Offset(0f, diagonal.toPx()),
                        Offset(diagonal.toPx(), 0f),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            }
            .clickable(
                onClickLabel = stringResource(R.string.mask_target),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val thumb = layer.maskThumb
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = stringResource(R.string.mask_thumbnail),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size).padding(1.dp),
            )
        } else {
            Text(
                "M",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small "R" badge marking the document reference node. */
@Composable
fun ReferenceBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(14.dp)
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.shapes.extraSmall),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "R",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** One row of colour tag dots ([ColorTag.NONE] clears the tag). */
@Composable
fun ColorTagDots(selected: ColorTag, modifier: Modifier = Modifier, onPick: (ColorTag) -> Unit) {
    Row(
        modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorTag.entries.forEach { tag ->
            val color = colorTagColor(tag)
            val shape = MaterialTheme.shapes.extraSmall
            Box(
                Modifier
                    .size(24.dp)
                    .clip(shape)
                    .border(
                        if (tag == selected) 2.dp else 1.dp,
                        if (tag == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape,
                    )
                    .clickable { onPick(tag) },
                contentAlignment = Alignment.Center,
            ) {
                if (color != null) {
                    Box(Modifier.padding(4.dp).size(16.dp).background(color, shape))
                } else {
                    Text(
                        "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
