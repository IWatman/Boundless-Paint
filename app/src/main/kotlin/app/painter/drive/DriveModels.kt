package app.painter.drive

/** Google account linked to Drive, as reported by the Drive API for the current token. */
data class DriveAccount(val email: String, val displayName: String?)

/** The visible folder tree the app maintains in My Drive. */
data class DriveFolders(val rootId: String, val projectsId: String, val drawingsId: String)

/** One project archive in the `Boundless Paint/Projects` folder. */
data class DriveBackup(
    val id: String,
    val projectId: String,
    val name: String,
    val modifiedMs: Long,
    val sizeBytes: Long,
)

/** A drawing uploaded to `Boundless Paint/Drawings`. */
data class DriveFile(val id: String, val name: String)

/** A Google authorization or Drive request failed. [code] is the HTTP status, or 0 for a local failure. */
class DriveException(val code: Int, message: String) : Exception(message)

/** The user dismissed the Google account/consent sheet. */
class DriveAuthCancelledException : Exception("Google sign-in was cancelled")

/** Supplies a short-lived OAuth access token for [DRIVE_SCOPE], showing Google's sheet when necessary. */
fun interface DriveTokenRequester {
    suspend fun requestToken(): String

    /** Drops [token] from the Play services cache after Google rejected it with HTTP 401. */
    suspend fun invalidateToken(token: String) {}
}

/** Local Drive link: the account shown in the UI and the last successful sync. No OAuth token is stored. */
interface DrivePreferences {
    var account: DriveAccount?
    var folders: DriveFolders?
    var lastSyncMs: Long

    /** True when an explicit project save should also back the project up to Drive. */
    var autoSync: Boolean
    fun clear()
}

/** `drive.file` exposes only files this app created; the rest of the user's Drive stays private. */
const val DRIVE_SCOPE: String = "https://www.googleapis.com/auth/drive.file"
