package app.painter

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import app.painter.render.CanvasView
import app.painter.studio.StudioViewModel
import app.painter.ui.home.GalleryScreen
import app.painter.ui.home.HomeScreen
import app.painter.ui.home.NewCanvasScreen
import app.painter.ui.home.UpdateDialog
import app.painter.ui.studio.StudioScreen
import app.painter.ui.theme.PainterTheme
import app.painter.update.UpdateViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: StudioViewModel by viewModels()
    private val updateVm: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PainterTheme { App(vm, updateVm) } }
    }

    override fun onResume() {
        super.onResume()
        updateVm.onResume()
    }

    override fun onStop() {
        super.onStop()
        vm.save()
    }
}

private enum class Screen { HOME, GALLERY, NEW, STUDIO }

@Composable
private fun App(vm: StudioViewModel, updateVm: UpdateViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var showUpdates by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val projects by vm.projects.collectAsState()
    val homeRequest by vm.homeRequest.collectAsState()
    val updateState by updateVm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(homeRequest) {
        if (homeRequest) {
            vm.homeRequest.value = false
            screen = Screen.HOME
        }
    }

    BackHandler(enabled = screen != Screen.HOME) {
        if (screen == Screen.STUDIO) vm.openHome() else screen = Screen.HOME
    }

    val open: (String) -> Unit = { id ->
        scope.launch {
            runCatching { vm.openDocument(id) }
                .onSuccess { screen = Screen.STUDIO }
                .onFailure { Toast.makeText(context, "Cannot open project: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onNewCanvas = { screen = Screen.NEW },
            onOpenGallery = { vm.refreshProjects(); screen = Screen.GALLERY },
            onOpenUpdates = { showUpdates = true },
            recent = projects.take(8),
            onOpenProject = open,
        )
        Screen.GALLERY -> GalleryScreen(
            projects = projects,
            onOpen = open,
            onRename = vm::renameProject,
            onDuplicate = vm::duplicateProject,
            onDelete = vm::deleteProject,
            onNew = { screen = Screen.NEW },
            onBack = { screen = Screen.HOME },
        )
        Screen.NEW -> {
            val cfg = LocalConfiguration.current
            val density = LocalDensity.current.density
            NewCanvasScreen(
                onCreate = { name, w, h ->
                    vm.newDocument(name, w, h)
                    screen = Screen.STUDIO
                },
                screenW = (cfg.screenWidthDp * density).toInt(),
                screenH = (cfg.screenHeightDp * density).toInt(),
                layerLimitFor = vm::layerLimitFor,
                onBack = { screen = Screen.HOME },
            )
        }
        Screen.STUDIO -> Studio(vm)
    }

    if (showUpdates) {
        UpdateDialog(
            state = updateState,
            currentVersion = updateVm.currentVersion,
            onCheck = updateVm::check,
            onUpdate = updateVm::update,
            onDismiss = {
                showUpdates = false
                updateVm.dismiss()
            },
        )
    }
}

@Composable
private fun Studio(vm: StudioViewModel) {
    val state by vm.state.collectAsState()
    val exportRequest by vm.exportRequest.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val bytes = vm.encodeComposite(Bitmap.CompressFormat.PNG, 100, transparent = false)
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }.isSuccess
            Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(exportRequest) {
        if (exportRequest) {
            vm.exportRequest.value = false
            exportLauncher.launch("${vm.documentName}.png")
        }
    }

    StudioScreen(state = state, actions = vm) {
        AndroidView(
            factory = { ctx -> CanvasView(ctx).also(vm::attachCanvas) },
            onRelease = vm::detachCanvas,
        )
    }
}
