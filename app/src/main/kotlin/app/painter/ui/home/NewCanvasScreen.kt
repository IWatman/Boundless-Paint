package app.painter.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.ui.theme.AccentFill

/** Allowed canvas side length in pixels. */
val CanvasSideRange = 16..16384

private data class CanvasPreset(val nameRes: Int, val width: Int, val height: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewCanvasScreen(
    onCreate: (name: String, width: Int, height: Int) -> Unit,
    screenW: Int,
    screenH: Int,
    layerLimitFor: (w: Int, h: Int) -> Int,
    onBack: (() -> Unit)? = null,
) {
    val presets = listOf(
        CanvasPreset(R.string.preset_screen, screenW, screenH),
        CanvasPreset(R.string.preset_a4, 2480, 3508),
        CanvasPreset(R.string.preset_a5, 1748, 2480),
        CanvasPreset(R.string.preset_square, 2048, 2048),
        CanvasPreset(R.string.preset_1080p, 1920, 1080),
        CanvasPreset(R.string.preset_4k, 3840, 2160),
    )
    val defaultName = stringResource(R.string.untitled)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var widthText by rememberSaveable { mutableStateOf(screenW.toString()) }
    var heightText by rememberSaveable { mutableStateOf(screenH.toString()) }
    val w = widthText.toIntOrNull()
    val h = heightText.toIntOrNull()
    val wValid = w != null && w in CanvasSideRange
    val hValid = h != null && h in CanvasSideRange
    val valid = wValid && hValid && name.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_canvas)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.presets), style = MaterialTheme.typography.titleSmall)
            presets.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { p ->
                        val selected = w == p.width && h == p.height
                        OutlinedCard(
                            onClick = {
                                widthText = p.width.toString()
                                heightText = p.height.toString()
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
                                containerColor = if (selected) AccentFill else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stringResource(p.nameRes), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${p.width} × ${p.height} px",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text(stringResource(R.string.custom_size), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { widthText = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.width)) },
                    suffix = { Text("px") },
                    isError = !wValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val t = widthText
                    widthText = heightText
                    heightText = t
                }) {
                    Icon(Icons.Outlined.SwapHoriz, stringResource(R.string.swap))
                }
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.height)) },
                    suffix = { Text("px") },
                    isError = !hValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                if (wValid && hValid) {
                    stringResource(R.string.max_layers, layerLimitFor(w!!, h!!))
                } else {
                    stringResource(R.string.size_range, CanvasSideRange.first, CanvasSideRange.last)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (wValid && hValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onCreate(name.trim(), w!!, h!!) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(stringResource(R.string.create))
            }
        }
    }
}
