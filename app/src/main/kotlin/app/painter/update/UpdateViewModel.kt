package app.painter.update

import app.painter.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.painter.BuildConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the in-app update flow. */
sealed interface UpdateUiState {
    /** Nothing requested yet. */
    data object Idle : UpdateUiState

    /** A GitHub Releases request is in flight. */
    data object Checking : UpdateUiState

    /** The running version is the newest release. */
    data object UpToDate : UpdateUiState

    /** The update was installed; this process already runs [version]. */
    data class Updated(val version: String) : UpdateUiState

    /** A newer release is available. */
    data class Available(val info: UpdateInfo) : UpdateUiState

    /** [progress] is `null` while the total size is unknown. */
    data class Downloading(val progress: Float?) : UpdateUiState

    /** The APK was handed to the package installer. */
    data object Installing : UpdateUiState

    /** Android asks the user to allow installing unknown apps. */
    data object NeedsPermission : UpdateUiState

    /** A step failed; [message] is shown with a retry action. */
    data class Error(val message: String) : UpdateUiState
}

/** Checks GitHub Releases, downloads the APK and starts the system installer. */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val checker = UpdateChecker(BuildConfig.UPDATE_REPO)
    private val installer = UpdateInstaller(app)
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)

    /** Current state of the update flow. */
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** Version name of the running build. */
    val currentVersion: String = BuildConfig.VERSION_NAME

    private var available: UpdateInfo? = null
    private var apk: File? = null

    init {
        viewModelScope.launch {
            UpdateInstallBus.status.collect { status ->
                when (status) {
                    null -> Unit
                    // Delivered to the freshly installed version, so the install is already done.
                    InstallStatus.Success -> _state.value = UpdateUiState.Updated(currentVersion)
                    is InstallStatus.Failure -> _state.value = UpdateUiState.Error(
                        if ("UPDATE_INCOMPATIBLE" in status.message) {
                            getApplication<Application>().getString(R.string.update_signature_mismatch)
                        } else {
                            status.message
                        },
                    )
                }
                if (status != null) UpdateInstallBus.consume()
            }
        }
    }

    /** Fetches the newest release and updates [state]. */
    fun check() {
        if (_state.value is UpdateUiState.Checking) return
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            runCatching { checker.check(currentVersion) }
                .onSuccess { info ->
                    available = info
                    _state.value = if (info == null) UpdateUiState.UpToDate else UpdateUiState.Available(info)
                }
                .onFailure { _state.value = UpdateUiState.Error(it.readableMessage()) }
        }
    }

    /** Downloads the pending update and opens the installer, asking for permission first. */
    fun update() {
        val info = available ?: return
        if (_state.value is UpdateUiState.Downloading || _state.value is UpdateUiState.Installing) return
        if (!installer.canInstall()) {
            runCatching { installer.requestInstallPermission() }
            _state.value = UpdateUiState.NeedsPermission
            return
        }
        viewModelScope.launch {
            try {
                val file = apk?.takeIf { it.length() == info.apkSize } ?: download(info)
                apk = file
                _state.value = UpdateUiState.Installing
                installer.install(file)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                apk = null
                _state.value = UpdateUiState.Error(t.readableMessage())
            }
        }
    }

    /** Continues the update when the user comes back from granting the install permission. */
    fun onResume() {
        if (_state.value is UpdateUiState.NeedsPermission && installer.canInstall()) update()
    }

    /** Resets the dialog; an already downloaded APK is kept for a later install. */
    fun dismiss() {
        val current = _state.value
        if (current is UpdateUiState.Downloading || current is UpdateUiState.Installing) return
        _state.value = UpdateUiState.Idle
    }

    private suspend fun download(info: UpdateInfo): File {
        _state.value = UpdateUiState.Downloading(null)
        return installer.download(info) { done, total ->
            _state.value = UpdateUiState.Downloading(
                if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null,
            )
        }
    }

    private fun Throwable.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Unknown error"
}
