package app.painter.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.ui.components.PanelSurface
import app.painter.ui.theme.AccentFill

private val BrowserWidth = 360.dp
private val BrowserHeight = 560.dp
private val CategoryWidth = 110.dp
private val BrushRowHeight = 56.dp

/**
 * Built-in brush browser: a category column on the left and the presets of the picked category on the right.
 * It opens on the category of the current brush with that category and the brush scrolled into view. Tapping a
 * preset calls [StudioActions.selectBrush], which closes the popup.
 */
@Composable
internal fun BrushBrowser(state: StudioUiState, actions: StudioActions, modifier: Modifier = Modifier) {
    val categories = state.brushCategories
    if (categories.isEmpty()) return
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

/** One preset row; the preset the current mode paints with is tinted with the primary color. */
@Composable
private fun BrushRow(brush: BrushItemUi, selected: Boolean, onClick: () -> Unit) {
    val fill = if (selected) AccentFill else Color.Transparent
    Box(
        Modifier
            .fillMaxWidth()
            .height(BrushRowHeight)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .clip(MaterialTheme.shapes.small)
            .background(fill)
            .clickable(onClick = onClick)
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
}
