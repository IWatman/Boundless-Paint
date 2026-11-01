package app.painter.drive

import app.painter.core.doc.Document
import app.painter.core.io.ProjectIO
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

class DriveRepositoryTest {

    private val root: File = Files.createTempDirectory("boundless-drive").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private class FakeTokens(vararg values: String) : DriveTokenRequester {
        private val queue = ArrayDeque(values.toList())
        var calls = 0

        override suspend fun requestToken(): String {
            calls++
            return queue.removeFirstOrNull() ?: "token"
        }
    }

    private class FakePreferences : DrivePreferences {
        override var account: DriveAccount? = null
        override var folders: DriveFolders? = null
        override var lastSyncMs: Long = 0L
        override fun clear() {
            account = null
            folders = null
            lastSyncMs = 0L
        }
    }

    private class Upload(
        val folderId: String,
        val projectId: String,
        val projectName: String,
        val file: File,
        val existingId: String?,
    )

    private class Drawing(
        val folderId: String,
        val name: String,
        val mimeType: String,
        val bytes: ByteArray,
    )

    private class FakeGateway : DriveGateway {
        val folders = DriveFolders("root-1", "projects-1", "drawings-1")
        var ensured = 0
        val uploads = ArrayList<Upload>()
        var drawings = ArrayList<Drawing>()
        var backups = ArrayList<DriveBackup>()
        val downloads = ArrayList<String>()
        var failListWith401 = false
        var failListWith404 = false
        var uploadedBytes = ByteArray(0)

        override fun about(accessToken: String) = DriveAccount("artist@example.com", "Artist")

        override fun ensureFolders(accessToken: String): DriveFolders {
            ensured++
            return folders
        }

        override fun listBackups(accessToken: String, folderId: String): List<DriveBackup> {
            if (failListWith401) {
                failListWith401 = false
                throw DriveException(401, "expired")
            }
            if (failListWith404) {
                failListWith404 = false
                throw DriveException(404, "folder deleted")
            }
            return backups.toList()
        }

        override fun uploadProject(
            accessToken: String,
            folderId: String,
            projectId: String,
            projectName: String,
            file: File,
            existingId: String?,
        ): DriveBackup {
            uploads += Upload(folderId, projectId, projectName, file, existingId)
            uploadedBytes = file.readBytes()
            val backup = DriveBackup(
                id = existingId ?: "remote-new",
                projectId = projectId,
                name = projectName,
                modifiedMs = 1_000L,
                sizeBytes = file.length(),
            )
            backups.removeAll { it.id == backup.id }
            backups += backup
            return backup
        }

        override fun uploadDrawing(
            accessToken: String,
            folderId: String,
            name: String,
            mimeType: String,
            bytes: ByteArray,
        ): DriveFile {
            drawings += Drawing(folderId, name, mimeType, bytes)
            return DriveFile("image-1", name)
        }

        override fun download(accessToken: String, fileId: String, out: OutputStream) {
            downloads += fileId
            out.write(byteArrayOf(7, 8, 9))
        }
    }

    private fun projectDir(): File {
        val dir = File(root, "projects/p1")
        ProjectIO.save(Document(32, 32, name = "Sketch"), dir, null)
        return dir
    }

    private fun repository(
        tokens: DriveTokenRequester,
        preferences: FakePreferences,
        gateway: FakeGateway,
    ) = DriveRepository(
        tokens = tokens,
        preferences = preferences,
        cacheDir = File(root, "cache").apply { mkdirs() },
        gateway = gateway,
        now = { 42L },
    )

    @Test
    fun connectRecordsTheAccountAndCreatesTheFolderTree() {
        val preferences = FakePreferences()
        val gateway = FakeGateway()
        val repository = repository(FakeTokens("token"), preferences, gateway)

        val account = runBlocking { repository.connect() }

        assertEquals("artist@example.com", account.email)
        assertEquals(account, preferences.account)
        assertEquals(gateway.folders, preferences.folders)
        assertEquals(1, gateway.ensured)
    }

    @Test
    fun backupUpdatesTheExistingRemoteFileAndCleansTheArchive() {
        val preferences = FakePreferences().apply { folders = DriveFolders("root-1", "projects-1", "drawings-1") }
        val gateway = FakeGateway().apply {
            backups += DriveBackup("remote-1", "p1", "Sketch", 0L, 0L)
        }
        val repository = repository(FakeTokens("token"), preferences, gateway)

        val backup = runBlocking { repository.backup("p1", "Sketch", projectDir()) }

        val upload = gateway.uploads.single()
        assertEquals("projects-1", upload.folderId)
        assertEquals("p1", upload.projectId)
        assertEquals("remote-1", upload.existingId)
        assertTrue(gateway.uploadedBytes.size > 2)
        assertEquals('P'.code.toByte(), gateway.uploadedBytes[0])
        assertEquals('K'.code.toByte(), gateway.uploadedBytes[1])
        assertEquals("remote-1", backup.id)
        assertEquals(42L, preferences.lastSyncMs)
        assertTrue(File(root, "cache").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun listBackupsRetriesOnceAfter401() {
        val preferences = FakePreferences().apply { folders = DriveFolders("root-1", "projects-1", "drawings-1") }
        val tokens = FakeTokens("old", "new")
        val gateway = FakeGateway().apply {
            backups += DriveBackup("remote-1", "p1", "Sketch", 0L, 0L)
            failListWith401 = true
        }
        val repository = repository(tokens, preferences, gateway)

        val backups = runBlocking { repository.listBackups() }

        assertEquals(2, tokens.calls)
        assertEquals("remote-1", backups.single().id)
    }

    @Test
    fun listBackupsRecreatesADeletedFolderTreeOnce() {
        val preferences = FakePreferences().apply {
            folders = DriveFolders("deleted-root", "deleted-projects", "deleted-drawings")
        }
        val gateway = FakeGateway().apply {
            backups += DriveBackup("remote-1", "p1", "Sketch", 0L, 0L)
            failListWith404 = true
        }
        val repository = repository(FakeTokens("token"), preferences, gateway)

        val backups = runBlocking { repository.listBackups() }

        assertEquals("remote-1", backups.single().id)
        assertEquals(1, gateway.ensured)
        assertEquals(gateway.folders, preferences.folders)
    }

    @Test
    fun disconnectClearsTheLocalLink() {
        val preferences = FakePreferences().apply {
            account = DriveAccount("artist@example.com", null)
            folders = DriveFolders("root-1", "projects-1", "drawings-1")
        }
        val gateway = FakeGateway()
        val repository = repository(FakeTokens("token"), preferences, gateway)
        runBlocking { repository.listBackups() }

        runBlocking { repository.disconnect() }

        assertNull(preferences.account)
        assertNull(preferences.folders)
    }

    @Test
    fun downloadCopiesTheRemoteArchive() {
        val repository = repository(FakeTokens("token"), FakePreferences(), FakeGateway())
        val target = File(root, "restore.pnt")

        runBlocking { repository.download("remote-1", target) }

        assertTrue(target.readBytes().contentEquals(byteArrayOf(7, 8, 9)))
    }

    @Test
    fun uploadDrawingGoesToTheDrawingsFolder() {
        val preferences = FakePreferences().apply {
            account = DriveAccount("artist@example.com", null)
            folders = DriveFolders("root-1", "projects-1", "drawings-1")
        }
        val gateway = FakeGateway()
        val repository = repository(FakeTokens("token"), preferences, gateway)

        val file = runBlocking {
            repository.uploadDrawing("Sketch.png", "image/png", byteArrayOf(1, 2, 3))
        }

        assertEquals("image-1", file.id)
        val drawing = gateway.drawings.single()
        assertEquals("drawings-1", drawing.folderId)
        assertEquals("Sketch.png", drawing.name)
        assertEquals("image/png", drawing.mimeType)
        assertTrue(drawing.bytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(42L, preferences.lastSyncMs)
    }
}
