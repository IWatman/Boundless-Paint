package app.painter.drive

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class DriveApiTest {

    private class FakeTransport : DriveTransport {
        val requests = ArrayList<DriveRequest>()
        val responses = ArrayDeque<DriveResponse>()
        var fallback = DriveResponse(200, "{}")
        var downloadResponse = DriveResponse(200, "")
        var downloadBytes = ByteArray(0)

        override fun execute(request: DriveRequest): DriveResponse {
            requests += request
            return responses.removeFirstOrNull() ?: fallback
        }

        override fun download(url: String, headers: Map<String, String>, out: java.io.OutputStream): DriveResponse {
            requests += DriveRequest("GET", url, headers)
            if (downloadResponse.code in 200..299) out.write(downloadBytes)
            return downloadResponse
        }
    }

    @Test
    fun aboutParsesTheGoogleAccount() {
        val transport = FakeTransport().apply {
            responses += DriveResponse(
                200,
                """{"user":{"emailAddress":"artist@example.com","displayName":"Artist"}}""",
            )
        }
        val account = DriveApi(transport).about("token")
        assertEquals("artist@example.com", account.email)
        assertEquals("Artist", account.displayName)
        assertTrue(transport.requests.single().url.contains("fields=user"))
    }

    @Test
    fun ensureFoldersCreatesTheVisibleTree() {
        val transport = FakeTransport().apply {
            responses += DriveResponse(200, """{"files":[]}""")
            responses += DriveResponse(200, """{"id":"root-1","name":"Boundless Paint"}""")
            responses += DriveResponse(200, """{"files":[]}""")
            responses += DriveResponse(200, """{"id":"projects-1","name":"Projects"}""")
            responses += DriveResponse(200, """{"files":[]}""")
            responses += DriveResponse(200, """{"id":"drawings-1","name":"Drawings"}""")
        }
        val folders = DriveApi(transport).ensureFolders("token")
        assertEquals(DriveFolders("root-1", "projects-1", "drawings-1"), folders)
        assertEquals(6, transport.requests.size)
        assertTrue(transport.requests[0].url.contains("Boundless"))
        assertEquals("POST", transport.requests[1].method)
        val rootMetadata = (transport.requests[1].body as DriveBody.Bytes).bytes.decodeToString()
        assertTrue(rootMetadata.contains("\"mimeType\":\"application/vnd.google-apps.folder\""))
        assertTrue(!rootMetadata.contains("\"parents\""))
        val projectsMetadata = (transport.requests[3].body as DriveBody.Bytes).bytes.decodeToString()
        assertTrue(projectsMetadata.contains("\"parents\":[\"root-1\"]"))
        val drawingsMetadata = (transport.requests[5].body as DriveBody.Bytes).bytes.decodeToString()
        assertTrue(drawingsMetadata.contains("\"parents\":[\"root-1\"]"))
    }

    @Test
    fun ensureFoldersReusesExistingFolders() {
        val transport = FakeTransport().apply {
            responses += DriveResponse(200, """{"files":[{"id":"root-1","name":"Boundless Paint"}]}""")
            responses += DriveResponse(200, """{"files":[{"id":"projects-1","name":"Projects"}]}""")
            responses += DriveResponse(200, """{"files":[{"id":"drawings-1","name":"Drawings"}]}""")
        }
        val folders = DriveApi(transport).ensureFolders("token")
        assertEquals(DriveFolders("root-1", "projects-1", "drawings-1"), folders)
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests.all { it.method == "GET" })
        assertTrue(transport.requests.all { it.url.contains("orderBy=createdTime") })
    }

    @Test
    fun ensureFoldersFindsTheSameTreeAgainAfterReconnect() {
        val transport = FakeTransport().apply {
            repeat(2) {
                responses += DriveResponse(200, """{"files":[{"id":"root-1","name":"Boundless Paint"}]}""")
                responses += DriveResponse(200, """{"files":[{"id":"projects-1","name":"Projects"}]}""")
                responses += DriveResponse(200, """{"files":[{"id":"drawings-1","name":"Drawings"}]}""")
            }
        }
        val api = DriveApi(transport)

        val first = api.ensureFolders("token")
        val second = api.ensureFolders("token")

        assertEquals(first, second)
        assertEquals(6, transport.requests.size)
        assertTrue(transport.requests.none { it.method == "POST" })
    }

    @Test
    fun listBackupsReadsAppPropertiesAndDates() {
        val transport = FakeTransport().apply {
            responses += DriveResponse(
                200,
                """{"files":[
                    {"id":"remote-1","name":"boundless-paint-p1.pnt","modifiedTime":"2026-10-31T20:00:00.000Z",
                     "size":"1234","appProperties":{"projectId":"p1","projectName":"Sketch"}},
                    {"id":"remote-2","name":"orphan.pnt","modifiedTime":"2026-10-30T20:00:00.000Z"}
                ]}""",
            )
        }
        val backups = DriveApi(transport).listBackups("token", "folder-1")
        assertEquals(2, backups.size)
        assertEquals("remote-1", backups[0].id)
        assertEquals("p1", backups[0].projectId)
        assertEquals("Sketch", backups[0].name)
        assertEquals(1234L, backups[0].sizeBytes)
        assertTrue(backups[0].modifiedMs > 0L)
        assertEquals("remote-2", backups[1].projectId)
        val url = transport.requests.single().url
        assertTrue(url.contains("spaces=drive"))
        assertTrue(url.contains("folder-1"))
    }

    @Test
    fun listBackupsFollowsPagination() {
        val transport = FakeTransport().apply {
            responses += DriveResponse(
                200,
                """{"nextPageToken":"p2","files":[{"id":"remote-1"}]}""",
            )
            responses += DriveResponse(200, """{"files":[{"id":"remote-2"}]}""")
        }
        val backups = DriveApi(transport).listBackups("token", "folder-1")
        assertEquals(listOf("remote-1", "remote-2"), backups.map { it.id })
        assertTrue(transport.requests[1].url.contains("pageToken=p2"))
    }

    @Test
    fun uploadProjectCreatesAMultipartFileInTheFolder() {
        val transport = FakeTransport().apply {
            responses += DriveResponse(
                200,
                """{"id":"remote-1","name":"boundless-paint-p1.pnt","modifiedTime":"2026-10-31T20:00:00Z",
                    "size":"4","appProperties":{"projectId":"p1","projectName":"Sketch"}}""",
            )
        }
        val archive = File.createTempFile("drive-test", ".pnt").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            val backup = DriveApi(transport).uploadProject("token", "folder-1", "p1", "Sketch", archive, null)
            val request = transport.requests.single()
            assertEquals("POST", request.method)
            assertTrue(request.url.contains("uploadType=multipart"))
            val body = request.body as DriveBody.Multipart
            assertTrue(body.metadataJson.contains("\"parents\":[\"folder-1\"]"))
            assertTrue(body.metadataJson.contains("\"projectId\":\"p1\""))
            assertEquals(archive, body.media)
            assertEquals("remote-1", backup.id)
            assertEquals("Sketch", backup.name)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun uploadProjectUpdatesAnExistingFileWithoutParents() {
        val transport = FakeTransport().apply {
            responses += DriveResponse(
                200,
                """{"id":"remote-1","modifiedTime":"2026-10-31T20:00:00Z",
                    "appProperties":{"projectId":"p1","projectName":"Sketch"}}""",
            )
        }
        val archive = File.createTempFile("drive-test", ".pnt").apply { writeBytes(byteArrayOf(9)) }
        try {
            DriveApi(transport).uploadProject("token", "folder-1", "p1", "Sketch", archive, "remote-1")
            val request = transport.requests.single()
            assertEquals("PATCH", request.method)
            assertTrue(request.url.contains("files/remote-1?uploadType=multipart"))
            val body = request.body as DriveBody.Multipart
            assertTrue(!body.metadataJson.contains("\"parents\""))
        } finally {
            archive.delete()
        }
    }

    @Test
    fun uploadDrawingSendsPngBytesToTheDrawingsFolder() {
        val transport = FakeTransport().apply {
            responses += DriveResponse(200, """{"id":"image-1","name":"Sketch 2026-11-01.png"}""")
        }
        val file = DriveApi(transport).uploadDrawing(
            "token",
            "drawings-1",
            "Sketch 2026-11-01.png",
            "image/png",
            byteArrayOf(1, 2, 3),
        )
        assertEquals("image-1", file.id)
        assertEquals("Sketch 2026-11-01.png", file.name)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.contains("uploadType=multipart"))
        val body = request.body as DriveBody.MultipartBytes
        assertTrue(body.metadataJson.contains("\"parents\":[\"drawings-1\"]"))
        assertEquals("image/png", body.mediaType)
        assertTrue(body.bytes.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun driveErrorsCarryTheGoogleMessage() {
        val transport = FakeTransport().apply {
            responses += DriveResponse(403, """{"error":{"message":"The user has not granted access."}}""")
        }
        val error = runCatching { DriveApi(transport).listBackups("token", "folder-1") }.exceptionOrNull()
        assertTrue(error is DriveException)
        assertEquals(403, (error as DriveException).code)
        assertEquals("The user has not granted access.", error.message)
    }

    @Test
    fun downloadWritesTheArchiveBody() {
        val transport = FakeTransport().apply {
            downloadBytes = byteArrayOf(4, 3, 2, 1)
        }
        val out = ByteArrayOutputStream()
        DriveApi(transport).download("token", "remote-1", out)
        assertTrue(out.toByteArray().contentEquals(byteArrayOf(4, 3, 2, 1)))
        assertTrue(transport.requests.single().url.endsWith("files/remote-1?alt=media"))
    }

    @Test
    fun appPropertyNameFitsTheDriveByteLimit() {
        val transport = FakeTransport().apply {
            responses += DriveResponse(200, """{"id":"remote-1","modifiedTime":"2026-10-31T20:00:00Z"}""")
        }
        val archive = File.createTempFile("drive-test", ".pnt").apply { writeBytes(byteArrayOf(1)) }
        try {
            DriveApi(transport).uploadProject("token", "folder-1", "p1", "Ж".repeat(200), archive, null)
            val body = transport.requests.single().body as DriveBody.Multipart
            val stored = kotlinx.serialization.json.Json.parseToJsonElement(body.metadataJson)
                .jsonObject["appProperties"]?.jsonObject?.get("projectName")?.jsonPrimitive?.contentOrNull
            assertTrue(stored != null)
            assertTrue(stored!!.toByteArray(Charsets.UTF_8).size <= 120)
        } finally {
            archive.delete()
        }
    }
}
