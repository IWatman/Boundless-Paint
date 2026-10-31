package app.painter.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.ui.components.ParamSlider
import kotlin.math.roundToInt

/**
 * Text tool of M3: the string (multi-line) and the em size, stamped with the current colour at the centre of
 * the canvas as one undo step. The Transform tool moves or scales it afterwards.
 */
@Composable
fun TextDialog(onDismiss: () -> Unit, onConfirm: (String, Float) -> Unit) {
    var text by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(96f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.text_hint)) },
                    minLines = 2,
                )
                ParamSlider(
                    label = stringResource(R.string.size),
                    value = size,
                    range = 12f..400f,
                    format = { it.roundToInt().toString() },
                    onChange = { size = it },
                    step = 1f,
                    unit = "px",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text, size) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
