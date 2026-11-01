package app.painter.ui.drive

import androidx.compose.runtime.Immutable
import app.painter.drive.DriveAccount
import app.painter.drive.DriveBackup

/** Account shown in the Drive panel. */
@Immutable
data class DriveAccountUi(val email: String, val displayName: String?)

/** One remote backup row. [modifiedMs] is epoch millis. */
@Immutable
data class DriveBackupUi(
    val id: String,
    val name: String,
    val modifiedMs: Long,
    val sizeBytes: Long,
)

/** State of the Google Drive sheet. It is separate from [app.painter.ui.studio.StudioUiState] so a sync does
 * not recompose the painting screen. */
data class DriveUiState(
    val open: Boolean = false,
    val account: DriveAccountUi? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
    val backups: List<DriveBackupUi> = emptyList(),
    val loaded: Boolean = false,
    val lastSyncMs: Long = 0L,
    /** False on a cold start before any project is opened. */
    val canSyncCurrent: Boolean = false,
)

fun DriveAccount.toUi(): DriveAccountUi = DriveAccountUi(email, displayName)

fun DriveBackup.toUi(): DriveBackupUi = DriveBackupUi(id, name, modifiedMs, sizeBytes)
