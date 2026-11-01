package app.painter.ui.studio

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.ui.components.PanelSurface
import app.painter.ui.components.RenameDialog
import app.painter.ui.theme.AccentFill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BrowserWidth = 360.dp
private val BrowserHeight = 560.dp
private val CategoryWidth = 110.dp
private val BrushRowHeight = 56.dp

private val IMPORT_MIME = arrayOf(
    "application/zip",
    "application/octet-stream",
    "image/png",
    "image/jpeg",
    "application/x-myPaint-brush",
)
private val IMAGE_MIME = arrayOf("image/png", "image/jpeg")

/** Display name of a SAF document, for the name of an imported brush. */
private fun displayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

/**
 * Brush browser: a category column on the left and the presets of the picked category on the right.
 * It opens on the category of the current brush with that category and the brush scrolled into view. Tapping a
 * preset calls [StudioActions.selectBrush], which closes the popup; a long press on a user brush (the
 * "Created" category of spec/tools/m2-brush.md §4.2) opens Rename and Delete.
 */
@Composable
internal fun BrushBrowser(state: StudioUiState, actions: StudioActions, modifier: Modifier = Modifier) {
    val categories = state.brushCategories
    if (categories.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importAsTexture by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = displayName(context, uri) ?: "brush"
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                Toast.makeText(context, R.string.brush_import_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val count = runCatching { actions.importBrush(name, bytes, importAsTexture) }.getOrElse {
                Toast.makeText(context, it.message ?: context.getString(R.string.brush_import_failed), Toast.LENGTH_LONG).show()
                return@launch
            }
            val message = if (count > 0) {
                context.resources.getQuantityString(R.plurals.brush_imported, count, count)
            } else {
                context.getString(R.string.brush_import_empty)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val exportBrush = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.Default) { actions.exportBrushBytes() }
            val ok = bytes != null && withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess
            }
            Toast.makeText(context, if (ok) R.string.brush_exported else R.string.brush_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val exportPack = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.Default) { actions.exportBrushPackBytes() }
            val ok = bytes != null && withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess
            }
            Toast.makeText(context, if (ok) R.string.brush_exported else R.string.brush_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val home = categories.indexOfFirst { c -> c.brushes.any { it.id == state.currentBrushId } }.coerceAtLeast(0)
    var picked by rememberSaveable { mutableIntStateOf(home) }
    val index = picked.coerceIn(0, categories.lastIndex)
    val current = categories[index]
    val categoryList = rememberLazyListState(initialFirstVisibleItemIndex = (home - 2).coerceAtLeast(0))
    PanelSurface(modifier.width(BrowserWidth).height(BrowserHeight)) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.brush_browser),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                // Edits the brush of the current mode; the editor closes the browser and opens on the right (§4.2).
                TextButton(onClick = { actions.openBrushEditor() }) {
                    Text(stringResource(R.string.brush_edit))
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.brush_import)) },
                            onClick = {
                                menu = false
                                importAsTexture = false
                                importLauncher.launch(IMPORT_MIME)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.brush_import_texture)) },
                            onClick = {
                                menu = false
                                importAsTexture = true
                                importLauncher.launch(IMAGE_MIME)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.brush_export)) },
                            onClick = {
                                menu = false
                                exportBrush.launch(actions.exportBrushName() + ".pbrush")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.brush_export_pack)) },
                            onClick = {
                                menu = false
                                exportPack.launch("Painter brushes.pbrushes")
                            },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.width(CategoryWidth), state = categoryList) {
                    itemsIndexed(categories) { i, category ->
                        CategoryRow(
                            name = category.name,
                            selected = i == index,
                            onClick = { picked = i },
                        )
                    }
                }
                // A fresh list per category, so another category starts at its top and the current one at its brush.
                key(index) {
                    val selectedRow = current.brushes.indexOfFirst { it.id == state.currentBrushId }
                    val brushList = rememberLazyListState(initialFirstVisibleItemIndex = (selectedRow - 2).coerceAtLeast(0))
                    LazyColumn(Modifier.weight(1f), state = brushList) {
                        items(current.brushes, key = { it.id }) { brush ->
                            BrushRow(
                                brush = brush,
                                selected = brush.id == state.currentBrushId,
                                onClick = { actions.selectBrush(brush.id) },
                                actions = actions,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One category name of the left column; the picked one is filled with the accent color. */
@Composable
private fun CategoryRow(name: String, selected: Boolean, onClick: () -> Unit) {
    val fill = if (selected) AccentFill else Color.Transparent
    Text(
        name,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/**
 * One preset row; the preset the current mode paints with is tinted with the primary color. A long press on a
 * user brush opens the Rename/Delete menu and the delete confirmation (spec/tools/m2-brush.md §4.2 "Brush
 * Options"); built-in presets have no menu, they are shared by every project of the app.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrushRow(brush: BrushItemUi, selected: Boolean, onClick: () -> Unit, actions: StudioActions) {
    val fill = if (selected) AccentFill else Color.Transparent
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .fillMaxWidth()
                .height(BrushRowHeight)
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .clip(MaterialTheme.shapes.small)
                .background(fill)
                .combinedClickable(onClick = onClick, onLongClick = { if (brush.user) menu = true })
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                brush.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                onClick = { menu = false; renaming = true },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; deleting = true },
            )
        }
    }
    if (renaming) {
        RenameDialog(
            title = stringResource(R.string.rename_brush),
            initial = brush.name,
            onDismiss = { renaming = false },
            onConfirm = {
                renaming = false
                actions.renameBrush(brush.id, it)
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_brush_title)) },
            text = { Text(stringResource(R.string.delete_brush_message, brush.name)) },
            confirmButton = {
                TextButton(onClick = { deleting = false; actions.deleteBrush(brush.id) }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
