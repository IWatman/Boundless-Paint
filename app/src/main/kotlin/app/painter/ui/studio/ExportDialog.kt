package app.painter.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.studio.ExportFormat
import app.painter.studio.ExportOptions
import app.painter.ui.components.ParamSlider
import kotlin.math.roundToInt

/**
 * Export options of spec/tools/m0-m1-tools.md T10: PNG or JPG, the JPG quality and, for PNG, a transparent
 * background instead of the paper. The activity opens the system file picker once the user confirms.
 */
@Composable
fun ExportDialog(
    options: ExportOptions,
    onChange: (ExportOptions) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_image)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.entries.forEach { format ->
                        ToggleChip(
                            label = format.name,
                            checked = options.format == format,
                            onToggle = { onChange(options.copy(format = format)) },
                        )
                    }
                }
                if (options.format == ExportFormat.JPG) {
                    ParamSlider(
                        label = stringResource(R.string.quality),
                        value = options.quality.toFloat(),
                        range = 1f..100f,
                        format = { it.roundToInt().toString() },
                        onChange = { onChange(options.copy(quality = it.roundToInt())) },
                        step = 1f,
                        unit = "%",
                    )
                } else {
                    ToggleChip(
                        label = stringResource(R.string.transparent_background),
                        checked = options.transparent,
                        onToggle = { onChange(options.copy(transparent = !options.transparent)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.export_image)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
