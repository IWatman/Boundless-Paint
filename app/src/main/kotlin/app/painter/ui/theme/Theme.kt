package app.painter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Brand accent color. */
val Accent = Color(0xFF4F8CFF)

/** Fill used behind active controls: accent at 16% alpha. */
val AccentFill = Accent.copy(alpha = 0.16f)

/** App-specific colors not covered by the Material color scheme. */
@Immutable
data class PainterColors(
    val canvasBackground: Color,
    val panel: Color,
    val panelBorder: Color,
    val checkerLight: Color,
    val checkerDark: Color,
    val hudBackground: Color,
    val onHud: Color,
)

private val DarkPainterColors = PainterColors(
    canvasBackground = Color(0xFF2B2B2B),
    panel = Color(0xF21E1F23),
    panelBorder = Color(0x33FFFFFF),
    checkerLight = Color(0xFF9A9A9A),
    checkerDark = Color(0xFF6A6A6A),
    hudBackground = Color(0xCC111214),
    onHud = Color(0xFFF2F3F5),
)

private val LightPainterColors = PainterColors(
    canvasBackground = Color(0xFFD0D0D0),
    panel = Color(0xF5FAFAFC),
    panelBorder = Color(0x22000000),
    checkerLight = Color(0xFFFFFFFF),
    checkerDark = Color(0xFFD6D6D6),
    hudBackground = Color(0xE6FFFFFF),
    onHud = Color(0xFF16171A),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1F3A6E),
    onPrimaryContainer = Color(0xFFD8E3FF),
    secondary = Color(0xFF9DB6E8),
    onSecondary = Color(0xFF0E1D3A),
    background = Color(0xFF141518),
    onBackground = Color(0xFFE6E7EA),
    surface = Color(0xFF1B1C20),
    onSurface = Color(0xFFE6E7EA),
    surfaceVariant = Color(0xFF2A2C31),
    onSurfaceVariant = Color(0xFFB8BBC2),
    surfaceContainer = Color(0xFF1F2024),
    surfaceContainerHigh = Color(0xFF26282C),
    surfaceContainerHighest = Color(0xFF2E3035),
    outline = Color(0xFF5A5D64),
    outlineVariant = Color(0xFF3A3C42),
    error = Color(0xFFFF6B6B),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF2F6FE8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E3FF),
    onPrimaryContainer = Color(0xFF0B2559),
    secondary = Color(0xFF4A5F86),
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF16171A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16171A),
    surfaceVariant = Color(0xFFE6E8EC),
    onSurfaceVariant = Color(0xFF4A4D55),
    surfaceContainer = Color(0xFFF0F1F4),
    surfaceContainerHigh = Color(0xFFE9EBEF),
    surfaceContainerHighest = Color(0xFFE2E4E9),
    outline = Color(0xFF8A8E96),
    outlineVariant = Color(0xFFCDD0D6),
    error = Color(0xFFD32F2F),
)

/** Shapes: 8 dp for buttons, 12 dp for panels. */
val PainterShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

val LocalPainterColors = staticCompositionLocalOf { DarkPainterColors }

/** Access to app-specific colors of the current theme. */
object PainterTheme {
    val colors: PainterColors
        @Composable get() = LocalPainterColors.current
}

/** Painter theme. Dark by default. */
@Composable
fun PainterTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val painterColors = if (darkTheme) DarkPainterColors else LightPainterColors
    CompositionLocalProvider(LocalPainterColors provides painterColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            shapes = PainterShapes,
            content = content,
        )
    }
}

/** Painter theme following the system dark mode setting. */
@Composable
fun PainterSystemTheme(content: @Composable () -> Unit) {
    PainterTheme(darkTheme = isSystemInDarkTheme(), content = content)
}
