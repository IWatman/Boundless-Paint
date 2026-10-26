package app.painter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.painter.ui.theme.PainterTheme

/**
 * Top-center heads-up display: [label] and [value]; optionally a brush ring whose inner
 * circle is [ringDiameterPx] screen pixels and outer dashed circle is [ringMaxDiameterPx].
 */
@Composable
fun Hud(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    ringDiameterPx: Float? = null,
    ringMaxDiameterPx: Float? = null,
) {
    val colors = PainterTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .background(colors.hudBackground)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(label, color = colors.onHud.copy(alpha = 0.75f), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Text(value, color = colors.onHud, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (ringDiameterPx != null) {
            Spacer(Modifier.height(8.dp))
            val density = LocalDensity.current
            val outerPx = maxOf(ringMaxDiameterPx ?: 0f, ringDiameterPx).coerceIn(4f, 2400f)
            val boxDp = with(density) { (outerPx + 4f).toDp() }
            Canvas(Modifier.size(boxDp)) {
                val c = center
                val sw = 1.5.dp.toPx()
                if (ringMaxDiameterPx != null) {
                    drawCircle(
                        Color.White.copy(alpha = 0.5f),
                        ringMaxDiameterPx / 2,
                        c,
                        style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
                    )
                }
                val r = (ringDiameterPx / 2).coerceAtLeast(1f)
                drawCircle(Color.Black.copy(alpha = 0.6f), r + sw, c, style = Stroke(sw))
                drawCircle(Color.White, r, c, style = Stroke(sw))
            }
        }
    }
}
