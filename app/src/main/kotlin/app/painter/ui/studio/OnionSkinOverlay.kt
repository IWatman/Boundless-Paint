package app.painter.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** Previous frames are tinted red, following ones blue, like traditional onion-skin paper. */
private val OnionBeforeTint = Color(0xFFFF5252)
private val OnionAfterTint = Color(0xFF448AFF)

/**
 * Compose layer above the canvas: paints the bitmap of the neighbouring frames under the active one. It uses
 * [StudioUiState.canvasTransform] so the pictures follow pan, zoom and rotation exactly like the selection
 * overlay. The bitmaps themselves are built by the studio from the frame pixel grids.
 */
@Composable
fun OnionSkinOverlay(state: StudioUiState, modifier: Modifier = Modifier) {
    val animation = state.animation ?: return
    if (animation.onion.isEmpty()) return
    val transform = state.canvasTransform
    val docWidth = state.canvasWidth
    val docHeight = state.canvasHeight
    Canvas(modifier) {
        for (frame in animation.onion) {
            val alpha = (0.3f / frame.distance).coerceIn(0.08f, 0.3f)
            withTransform({
                translate(transform.tx, transform.ty)
                rotate(transform.rotationDeg, pivot = Offset.Zero)
                scale(transform.zoom, transform.zoom, pivot = Offset.Zero)
            }) {
                drawImage(
                    image = frame.image,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(docWidth, docHeight),
                    alpha = alpha,
                    colorFilter = ColorFilter.tint(if (frame.before) OnionBeforeTint else OnionAfterTint),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
    }
}
