package app.painter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.painter.core.color.Rgba
import app.painter.ui.theme.AccentFill
import app.painter.ui.theme.PainterTheme

/** Minimum touch target of toolbar buttons. */
val TouchTarget = 44.dp

/** Floating panel container: 12 dp corners, translucent panel color, hairline border. */
@Composable
fun PanelSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = PainterTheme.colors.panel,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, PainterTheme.colors.panelBorder),
        shadowElevation = 6.dp,
        content = content,
    )
}

/** Vertical floating panel container. */
@Composable
fun PanelColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    PanelSurface(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** Draws a transparency checkerboard behind the content. */
fun Modifier.checkerboard(light: Color, dark: Color, cell: Dp = 6.dp): Modifier = drawBehind {
    val c = cell.toPx()
    drawRect(light)
    val cols = (size.width / c).toInt() + 1
    val rows = (size.height / c).toInt() + 1
    for (y in 0 until rows) {
        for (x in 0 until cols) {
            if ((x + y) % 2 == 1) {
                // drawBehind does not clip: the last row and column are cut to the bounds, or they bleed out.
                val w = minOf(c, size.width - x * c)
                val h = minOf(c, size.height - y * c)
                if (w > 0f && h > 0f) drawRect(dark, Offset(x * c, y * c), Size(w, h))
            }
        }
    }
}

/** Plain tappable icon button with optional long-press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PainterIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = modifier
            .size(TouchTarget)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Icon toggle: active state uses accent tint with a 16% accent fill. */
@Composable
fun IconToggle(
    icon: ImageVector,
    contentDescription: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dimmed: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(TouchTarget)
            .clip(MaterialTheme.shapes.small)
            .background(if (checked) AccentFill else Color.Transparent)
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .alpha(if (!enabled || dimmed) 0.45f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            tint = if (checked) accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Round color swatch on a checkerboard (to show alpha). */
@Composable
fun ColorSwatch(
    color: Rgba,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    shape: Shape = CircleShape,
    selected: Boolean = false,
) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier
            .size(size)
            .clip(shape)
            .checkerboard(PainterTheme.colors.checkerLight, PainterTheme.colors.checkerDark, 4.dp)
            .background(color.toColor())
            .border(if (selected) 2.dp else 1.dp, outline, shape),
    )
}
