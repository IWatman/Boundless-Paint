package app.painter.drive

import app.painter.core.io.ProjectIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Project backups and drawing exports through the visible `Boundless Paint` folder in My Drive.
 *
 * Access tokens come from [DriveTokenRequester] on demand: Google Play services caches the grant, so repeated
 * operations do not show UI. The folder ids are cached locally and refreshed if Google reports that a folder
 * was deleted. Each project is one `.pnt` archive tagged with the project name in Drive app properties, so
 * renaming a project updates the same remote file instead of creating a duplicate.
 */
class DriveRepository(
    private val tokens: DriveTokenRequester,
    private val preferences: DrivePreferences,
    private val cacheDir: File,
    private val gateway: DriveGateway = DriveApi(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    val account: DriveAccount? get() = preferences.account
    val lastSyncMs: Long get() = preferences.lastSyncMs

    /** Authorizes Drive, records the account and creates `Boundless Paint/Projects` and `Drawings`. */
    suspend fun connect(): DriveAccount {
        val linked = withToken { gateway.about(it) }
        preferences.account = linked
        preferences.folders = null
        withFolders { } // create the visible tree immediately
        return linked
    }

    suspend fun listBackups(): List<DriveBackup> = withFolders { folders ->
        withToken { gateway.listBackups(it, folders.projectsId) }
    }

    /** Packs [projectDir] into `Boundless Paint/Projects`. The temporary archive is removed afterwards. */
    suspend fun backup(projectId: String, projectName: String, projectDir: File): DriveBackup {
        if (!projectDir.isDirectory) throw DriveException(0, "The project directory is missing")
        cacheDir.mkdirs()
        val archive = File.createTempFile("boundless-", ".pnt", cacheDir)
        try {
            withContext(Dispatchers.IO) {
                FileOutputStream(archive).use { ProjectIO.exportPnt(projectDir, it) }
            }
            var folders = folders()
            return try {
                uploadProjectArchive(archive, projectId, projectName, folders)
            } catch (error: DriveException) {
                if (error.code != 404) throw error
                preferences.folders = null
                folders = folders()
                uploadProjectArchive(archive, projectId, projectName, folders)
            }
        } finally {
            archive.delete()
        }
    }

    /** Uploads one finished drawing to `Boundless Paint/Drawings`. */
    suspend fun uploadDrawing(name: String, mimeType: String, bytes: ByteArray): DriveFile =
        withFolders { folders ->
            val file = withToken { gateway.uploadDrawing(it, folders.drawingsId, name, mimeType, bytes) }
            preferences.lastSyncMs = now()
            file
        }

    /** Downloads a backup archive into [outFile]. */
    suspend fun download(backupId: String, outFile: File) {
        withToken { token ->
            outFile.outputStream().use { gateway.download(token, backupId, it) }
        }
    }

    /**
     * Clears the local link. The Google grant stays in Play services, so reconnecting does not need a new consent
     * sheet; to remove the app from the account entirely, the user can use Google Account -> Third-party apps.
     */
    suspend fun disconnect() {
        lastToken = null
        preferences.clear()
    }

    private var lastToken: String? = null

    private suspend fun uploadProjectArchive(
        archive: File,
        projectId: String,
        projectName: String,
        folders: DriveFolders,
    ): DriveBackup {
        val existing = withToken { gateway.listBackups(it, folders.projectsId) }
            .firstOrNull { it.projectId == projectId }
        val uploaded = withToken {
            gateway.uploadProject(it, folders.projectsId, projectId, projectName, archive, existing?.id)
        }
        preferences.lastSyncMs = now()
        return uploaded
    }

    private suspend fun folders(): DriveFolders {
        preferences.folders?.let { return it }
        val created = withToken { gateway.ensureFolders(it) }
        preferences.folders = created
        return created
    }

    /** Runs [block] with the folder tree and recreates it once if the user deleted it in Drive. */
    private suspend fun <T> withFolders(block: suspend (DriveFolders) -> T): T {
        val current = folders()
        return try {
            block(current)
        } catch (error: DriveException) {
            if (error.code != 404) throw error
            preferences.folders = null
            block(folders())
        }
    }

    /**
     * Runs [block] with an access token and retries once on HTTP 401, which is how an access token that expired
     * between two calls surfaces. Token acquisition stays on the caller's thread: [DriveAuthController] must be
     * asked from the main thread because it may launch an activity result.
     */
    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = tokens.requestToken()
        lastToken = token
        return try {
            withContext(Dispatchers.IO) { block(token) }
        } catch (error: DriveException) {
            if (error.code != 401) throw error
            tokens.invalidateToken(token)
            val fresh = tokens.requestToken()
            lastToken = fresh
            withContext(Dispatchers.IO) { block(fresh) }
        }
    }
}
