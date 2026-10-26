package app.painter.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.painter.R
import app.painter.ui.components.RenameDialog
import app.painter.ui.components.checkerboard
import app.painter.ui.theme.PainterTheme
import java.text.DateFormat
import java.util.Date

/** Project card data. [modified] is epoch millis. */
@Immutable
data class ProjectCardUi(
    val id: String,
    val name: String,
    val modified: Long,
    val thumbnail: ImageBitmap?,
)

private fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNewCanvas: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenUpdates: () -> Unit,
    recent: List<ProjectCardUi>,
    onOpenProject: (id: String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenUpdates) {
                        Icon(
                            Icons.Outlined.SystemUpdate,
                            contentDescription = stringResource(R.string.updates),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                HomeActionCard(
                    icon = Icons.Outlined.Add,
                    title = stringResource(R.string.new_canvas),
                    onClick = onNewCanvas,
                    primary = true,
                    modifier = Modifier.weight(1f),
                )
                HomeActionCard(
                    icon = Icons.Outlined.PhotoLibrary,
                    title = stringResource(R.string.gallery),
                    onClick = onOpenGallery,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.recent), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (recent.isEmpty()) {
                Text(
                    stringResource(R.string.no_recent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { it.id }) { p ->
                        ProjectCard(p, onClick = { onOpenProject(p.id) }, modifier = Modifier.width(160.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeActionCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(112.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, modifier = Modifier.size(32.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: ProjectCardUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .checkerboard(PainterTheme.colors.checkerLight, PainterTheme.colors.checkerDark, 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = project.thumbnail
            if (thumb != null) {
                Image(thumb, project.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(project.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            formatDate(project.modified),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    projects: List<ProjectCardUi>,
    onOpen: (id: String) -> Unit,
    onRename: (id: String, name: String) -> Unit,
    onDuplicate: (id: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onNew: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var renaming by remember { mutableStateOf<ProjectCardUi?>(null) }
    var deleting by remember { mutableStateOf<ProjectCardUi?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(stringResource(R.string.new_canvas)) },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.gallery_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(projects, key = { it.id }) { p ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        ProjectCard(p, onClick = { onOpen(p.id) }, onLongClick = { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.open)) }, onClick = { menu = false; onOpen(p.id) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = p })
                            DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { menu = false; onDuplicate(p.id) })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; deleting = p },
                            )
                        }
                    }
                }
            }
        }
    }
    renaming?.let { p ->
        RenameDialog(
            title = stringResource(R.string.rename_project),
            initial = p.name,
            onDismiss = { renaming = null },
            onConfirm = { renaming = null; onRename(p.id, it) },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_project_title)) },
            text = { Text(stringResource(R.string.delete_project_message, p.name)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDelete(p.id) }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
