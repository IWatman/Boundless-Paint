package app.painter

import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
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
import app.painter.drive.DriveAuthController
import app.painter.render.CanvasView
import app.painter.share.ImageShare
import app.painter.studio.ExportFormat
import app.painter.studio.ExportOptions
import app.painter.studio.StudioViewModel
import app.painter.ui.drive.DriveDialog
import app.painter.ui.drive.DriveUiState
import app.painter.ui.home.GalleryScreen
import app.painter.ui.home.HomeScreen
import app.painter.ui.home.NewCanvasScreen
import app.painter.ui.home.UpdateDialog
import app.painter.ui.studio.ExportDialog
import app.painter.ui.studio.StudioScreen
import app.painter.ui.studio.BrushModeUi
import app.painter.ui.theme.PainterTheme
import app.painter.update.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** Registers its activity-result launcher in the constructor, before the activity is started. */
    private val driveAuth = DriveAuthController(this)
    private val vm: StudioViewModel by viewModels { StudioViewModel.factory(application, driveAuth) }
    private val updateVm: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PainterTheme { App(vm, updateVm) } }
    }

    /** Pen hover and pen buttons go to the canvas before Compose, which filters them (see CanvasView.onPenInAir). */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        CanvasView.active?.onPenInAir(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        updateVm.onResume()
    }

    /** Hardware-keyboard shortcuts of the studio (M3): undo/redo, brush modes, brush size and fit. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> {
                    if (event.isShiftPressed) vm.redo() else vm.undo()
                    return true
                }
                KeyEvent.KEYCODE_Y -> {
                    vm.redo()
                    return true
                }
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_B -> {
                vm.setMode(BrushModeUi.PAINT)
                return true
            }
            KeyEvent.KEYCODE_E -> {
                vm.setMode(BrushModeUi.ERASE)
                return true
            }
            KeyEvent.KEYCODE_LEFT_BRACKET -> {
                vm.setBrushSize(vm.state.value.brushSize / 1.15f)
                return true
            }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> {
                vm.setBrushSize(vm.state.value.brushSize * 1.15f)
                return true
            }
            KeyEvent.KEYCODE_F -> {
                vm.fitView()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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
    val drive by vm.driveState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val importPsd = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                Toast.makeText(context, R.string.psd_import_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported.psd"
            runCatching { vm.importPsd(name, bytes) }
                .onSuccess { id ->
                    Toast.makeText(context, R.string.psd_imported, Toast.LENGTH_SHORT).show()
                    runCatching { vm.openDocument(id) }.onSuccess { screen = Screen.STUDIO }
                }
                .onFailure {
                    Toast.makeText(context, R.string.psd_import_failed, Toast.LENGTH_LONG).show()
                }
        }
    }

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
            onOpenDrive = vm::openDrivePanel,
            onImportPsd = { importPsd.launch(arrayOf("*/*")) },
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
        Screen.STUDIO -> Studio(vm, drive)
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

    if (drive.open) {
        DriveDialog(state = drive, actions = vm, onDismiss = vm::closeDrivePanel)
    }
}

@Composable
private fun Studio(vm: StudioViewModel, drive: DriveUiState) {
    val state by vm.state.collectAsState()
    val exportRequest by vm.exportRequest.collectAsState()
    val shareRequest by vm.shareRequest.collectAsState()
    val exportOptions by vm.exportOptions.collectAsState()
    val psdRequest by vm.psdRequest.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // The dialog picks format/quality/transparency (spec T10); the result is written when the picker returns.
    val writeExport: (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) scope.launch {
            val opts = vm.pendingExport ?: ExportOptions()
            val ok = runCatching {
                val bytes = if (opts.format == ExportFormat.CMYK_TIFF) {
                    vm.encodeCmyk()
                } else {
                    val format = when (opts.format) {
                        ExportFormat.JPG -> Bitmap.CompressFormat.JPEG
                        else -> Bitmap.CompressFormat.PNG
                    }
                    vm.encodeComposite(format, opts.quality, opts.transparentBackground)
                }
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }.isSuccess
            Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }
    val pngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.PNG.mime),
        writeExport,
    )
    val jpgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.JPG.mime),
        writeExport,
    )
    val cmykLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.CMYK_TIFF.mime),
        writeExport,
    )
    val psdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/vnd.adobe.photoshop"),
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val bytes = vm.encodePsd()
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }.isSuccess
            Toast.makeText(context, if (ok) R.string.psd_exported else R.string.psd_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(psdRequest) {
        if (psdRequest) {
            vm.psdRequest.value = false
            psdLauncher.launch("${vm.documentName}.psd")
        }
    }

    LaunchedEffect(exportRequest) {
        if (exportRequest) {
            vm.exportRequest.value = false
            val opts = vm.pendingExport ?: ExportOptions()
            val name = "${vm.documentName}.${opts.format.extension}"
            when (opts.format) {
                ExportFormat.JPG -> jpgLauncher.launch(name)
                ExportFormat.CMYK_TIFF -> cmykLauncher.launch(name)
                ExportFormat.PNG -> pngLauncher.launch(name)
            }
        }
    }

    LaunchedEffect(shareRequest) {
        if (!shareRequest) return@LaunchedEffect
        val ok = runCatching {
            // Share what the studio shows: the paper belongs to the image, so a drawing on a light canvas
            // does not turn into white-on-transparent in the receiving app.
            val bytes = vm.encodeComposite(Bitmap.CompressFormat.PNG, 100, transparent = false)
            val file = withContext(Dispatchers.IO) {
                ImageShare.write(context.cacheDir, vm.documentName, bytes)
            }
            ImageShare.send(context, file)
        }.isSuccess
        if (!ok) Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
        // Reset last: clearing the flag restarts this effect, which would cancel the encode still in flight.
        vm.shareRequest.value = false
    }

    StudioScreen(state = state, actions = vm, drive = drive) {
        AndroidView(
            factory = { ctx -> CanvasView(ctx).also(vm::attachCanvas) },
            onRelease = vm::detachCanvas,
        )
    }

    exportOptions?.let { opts ->
        ExportDialog(
            options = opts,
            onChange = { vm.exportOptions.value = it },
            onDismiss = vm::dismissExport,
            onConfirm = { vm.startExport(opts) },
        )
    }
}
