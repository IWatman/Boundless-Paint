package app.painter.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.ui.components.PanelSurface
import kotlin.math.roundToInt

/** Longest lazy rope radius, in screen px (spec G2.4). */
private const val MAX_LAZY_RADIUS = 200f

/**
 * Contextual bar of the guide overlays, shown at the top end while [StudioUiState.ruler],
 * [StudioUiState.ellipse] or [StudioUiState.lazy] is on: one row per active guide. The ruler row toggles
 * Perpendicular, Magnet and Lock and turns the ruler off; the ellipse row toggles Magnet and Lock and turns
 * the ellipse off; the lazy row sets the rope radius and turns the lazy rope off.
 */
@Composable
fun GuidesBar(
    ruler: RulerUi?,
    ellipse: EllipseUi?,
    lazy: LazyUi?,
    actions: StudioActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ruler != null) {
            PanelSurface {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.guide_ruler),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                    )
                    GuideToggle(stringResource(R.string.guide_perpendicular), ruler.perpendicular) {
                        actions.setRulerPerpendicular(!ruler.perpendicular)
                    }
                    GuideToggle(stringResource(R.string.guide_magnet), ruler.magnet) {
                        actions.setRulerMagnet(!ruler.magnet)
                    }
                    GuideToggle(stringResource(R.string.guide_lock), ruler.locked) {
                        actions.setRulerLocked(!ruler.locked)
                    }
                    TextButton(onClick = { actions.setRuler(false) }) { Text("×") }
                }
            }
        }
        if (ellipse != null) {
            PanelSurface {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.guide_ellipse),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                    )
                    GuideToggle(stringResource(R.string.guide_magnet), ellipse.magnet) {
                        actions.setEllipseMagnet(!ellipse.magnet)
                    }
                    GuideToggle(stringResource(R.string.guide_lock), ellipse.locked) {
                        actions.setEllipseLocked(!ellipse.locked)
                    }
                    TextButton(onClick = { actions.setEllipseGuide(false) }) { Text("×") }
                }
            }
        }
        if (lazy != null) {
            PanelSurface {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.guide_lazy),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                    )
                    Text(
                        stringResource(R.string.guide_lazy_radius),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = lazy.radius,
                        onValueChange = actions::setLazyRadius,
                        valueRange = 0f..MAX_LAZY_RADIUS,
                        modifier = Modifier.width(120.dp),
                    )
                    Text(
                        "${lazy.radius.roundToInt()}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { actions.setLazy(false) }) { Text("×") }
                }
            }
        }
    }
}

/** Text button of a bar toggle, drawn in the primary color while it is on. */
@Composable
private fun GuideToggle(label: String, on: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
