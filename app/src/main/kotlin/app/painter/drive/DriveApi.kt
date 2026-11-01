package app.painter.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/** Folder names inside My Drive; the app creates and owns this tree. */
const val DRIVE_ROOT_FOLDER = "Boundless Paint"
const val DRIVE_PROJECTS_FOLDER = "Projects"
const val DRIVE_DRAWINGS_FOLDER = "Drawings"

private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"
private const val FOLDER_MIME = "application/vnd.google-apps.folder"

/** A request body that can be streamed without loading a whole project archive into memory. */
sealed interface DriveBody {
    data class Bytes(val bytes: ByteArray, val contentType: String = "application/json") : DriveBody
    data class Multipart(val metadataJson: String, val media: File, val mediaType: String) : DriveBody
    data class MultipartBytes(val metadataJson: String, val bytes: ByteArray, val mediaType: String) : DriveBody
}

data class DriveRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: DriveBody? = null,
)

data class DriveResponse(val code: Int, val body: String)

/** HTTP seam of [DriveApi], so the Drive logic can be unit-tested without a network. */
interface DriveTransport {
    fun execute(request: DriveRequest): DriveResponse
    fun download(url: String, headers: Map<String, String>, out: OutputStream): DriveResponse
}

/** Drive operations used by [DriveRepository]. */
interface DriveGateway {
    fun about(accessToken: String): DriveAccount
    fun ensureFolders(accessToken: String): DriveFolders
    fun listBackups(accessToken: String, folderId: String): List<DriveBackup>
    fun uploadProject(
        accessToken: String,
        folderId: String,
        projectId: String,
        projectName: String,
        file: File,
        existingId: String?,
    ): DriveBackup
    fun uploadDrawing(
        accessToken: String,
        folderId: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
    ): DriveFile
    fun download(accessToken: String, fileId: String, out: OutputStream)
}

/**
 * Minimal Google Drive v3 client for the visible `Boundless Paint` folder tree. The `drive.file` scope only
 * exposes files this app created; the client still searches for its folders by name, so a renamed folder is
 * found as long as its id is cached in [DriveSettings].
 */
class DriveApi(private val transport: DriveTransport = HttpUrlConnectionTransport()) : DriveGateway {

    override fun about(accessToken: String): DriveAccount {
        val fields = encode("user(emailAddress,displayName)")
        val response = transport.execute(
            DriveRequest("GET", "$DRIVE_API/about?fields=$fields", authHeaders(accessToken)),
        )
        val json = jsonObject(response, "load the Google account")
        val user = json["user"]?.jsonObject ?: JsonObject(emptyMap())
        val email = user.string("emailAddress")
            ?: throw DriveException(0, "Google Drive did not return an account address")
        return DriveAccount(email, user.string("displayName"))
    }

    override fun ensureFolders(accessToken: String): DriveFolders {
        val root = findOrCreateFolder(accessToken, DRIVE_ROOT_FOLDER, parentId = null)
        val projects = findOrCreateFolder(accessToken, DRIVE_PROJECTS_FOLDER, root)
        val drawings = findOrCreateFolder(accessToken, DRIVE_DRAWINGS_FOLDER, root)
        return DriveFolders(root, projects, drawings)
    }

    override fun listBackups(accessToken: String, folderId: String): List<DriveBackup> {
        val query = "'${escapeQuery(folderId)}' in parents and trashed=false"
        val fields = encode("nextPageToken,files(id,name,modifiedTime,size,appProperties)")
        val backups = ArrayList<DriveBackup>()
        var pageToken: String? = null
        do {
            val page = pageToken?.let { "&pageToken=${encode(it)}" } ?: ""
            val url = "$DRIVE_API/files?spaces=drive&pageSize=100&orderBy=" +
                encode("modifiedTime desc") + "&fields=$fields&q=${encode(query)}$page"
            val response = transport.execute(DriveRequest("GET", url, authHeaders(accessToken)))
            val json = jsonObject(response, "list backups")
            for (element in json["files"]?.jsonArray.orEmpty()) {
                val file = element.jsonObject
                val id = file.string("id") ?: continue
                val properties = file["appProperties"]?.jsonObject
                backups += DriveBackup(
                    id = id,
                    projectId = properties?.string("projectId") ?: id,
                    name = properties?.string("projectName") ?: "Backup",
                    modifiedMs = file.string("modifiedTime")?.let(::parseInstant) ?: 0L,
                    sizeBytes = file.string("size")?.toLongOrNull() ?: 0L,
                )
            }
            pageToken = json.string("nextPageToken")
        } while (pageToken != null)
        return backups
    }

    override fun uploadProject(
        accessToken: String,
        folderId: String,
        projectId: String,
        projectName: String,
        file: File,
        existingId: String?,
    ): DriveBackup {
        val metadata = buildJsonObject {
            put("name", "boundless-paint-$projectId.pnt")
            putJsonObject("appProperties") {
                put("projectId", projectId)
                put("projectName", truncateUtf8(projectName, 120))
            }
            if (existingId == null) {
                putJsonArray("parents") { add(JsonPrimitive(folderId)) }
            }
        }.toString()
        val fields = encode("id,name,modifiedTime,size,appProperties")
        val url = if (existingId == null) {
            "$DRIVE_UPLOAD/files?uploadType=multipart&fields=$fields"
        } else {
            "$DRIVE_UPLOAD/files/${encode(existingId)}?uploadType=multipart&fields=$fields"
        }
        val response = transport.execute(
            DriveRequest(
                method = if (existingId == null) "POST" else "PATCH",
                url = url,
                headers = authHeaders(accessToken),
                body = DriveBody.Multipart(metadata, file, "application/octet-stream"),
            ),
        )
        val json = jsonObject(response, "upload the project")
        val properties = json["appProperties"]?.jsonObject
        return DriveBackup(
            id = json.string("id") ?: existingId ?: projectId,
            projectId = properties?.string("projectId") ?: projectId,
            name = properties?.string("projectName") ?: projectName,
            modifiedMs = json.string("modifiedTime")?.let(::parseInstant) ?: System.currentTimeMillis(),
            sizeBytes = json.string("size")?.toLongOrNull() ?: file.length(),
        )
    }

    override fun uploadDrawing(
        accessToken: String,
        folderId: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
    ): DriveFile {
        val metadata = buildJsonObject {
            put("name", name)
            putJsonArray("parents") { add(JsonPrimitive(folderId)) }
        }.toString()
        val fields = encode("id,name")
        val response = transport.execute(
            DriveRequest(
                method = "POST",
                url = "$DRIVE_UPLOAD/files?uploadType=multipart&fields=$fields",
                headers = authHeaders(accessToken),
                body = DriveBody.MultipartBytes(metadata, bytes, mimeType),
            ),
        )
        val json = jsonObject(response, "save the drawing")
        val id = json.string("id") ?: throw DriveException(0, "Google Drive saved the drawing without an id")
        return DriveFile(id, json.string("name") ?: name)
    }

    override fun download(accessToken: String, fileId: String, out: OutputStream) {
        val response = transport.download(
            "$DRIVE_API/files/${encode(fileId)}?alt=media",
            authHeaders(accessToken),
            out,
        )
        if (response.code !in 200..299) throw error(response, "download the project")
    }

    private fun findOrCreateFolder(accessToken: String, name: String, parentId: String?): String {
        val query = buildString {
            append("name='").append(escapeQuery(name)).append("'")
            append(" and mimeType='").append(FOLDER_MIME).append("'")
            append(" and trashed=false")
            if (parentId != null) append(" and '").append(escapeQuery(parentId)).append("' in parents")
        }
        val fields = encode("files(id,name)")
        // Oldest first: if an account somehow holds more than one folder with this name, every reconnect keeps
        // using the original tree instead of picking a random duplicate and splitting the backups.
        val url = "$DRIVE_API/files?spaces=drive&pageSize=10&orderBy=createdTime" +
            "&fields=$fields&q=${encode(query)}"
        val response = transport.execute(DriveRequest("GET", url, authHeaders(accessToken)))
        val json = jsonObject(response, "find the Boundless Paint folder")
        val existing = json["files"]?.jsonArray?.firstOrNull()?.jsonObject?.string("id")
        if (existing != null) return existing

        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            if (parentId != null) putJsonArray("parents") { add(JsonPrimitive(parentId)) }
        }.toString()
        val create = transport.execute(
            DriveRequest(
                method = "POST",
                url = "$DRIVE_API/files?fields=${encode("id,name")}",
                headers = authHeaders(accessToken),
                body = DriveBody.Bytes(metadata.toByteArray(Charsets.UTF_8)),
            ),
        )
        return jsonObject(create, "create the Boundless Paint folder").string("id")
            ?: throw DriveException(0, "Google Drive created a folder without an id")
    }

    private fun authHeaders(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/json",
    )

    private fun jsonObject(response: DriveResponse, action: String): JsonObject {
        if (response.code !in 200..299) throw error(response, action)
        return runCatching { Json.parseToJsonElement(response.body).jsonObject }
            .getOrElse { throw DriveException(response.code, "Google returned an invalid response while trying to $action") }
    }

    private fun error(response: DriveResponse, action: String): DriveException {
        val detail = runCatching {
            Json.parseToJsonElement(response.body)
                .jsonObject["error"]
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()
        return DriveException(
            response.code,
            detail ?: "Google Drive could not $action (HTTP ${response.code})",
        )
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun parseInstant(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun escapeQuery(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    /** Drive app-property values are limited to 124 bytes; this never splits a multi-byte character. */
    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val out = StringBuilder()
        var used = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val size = text.toByteArray(Charsets.UTF_8).size
            if (used + size > maxBytes) break
            out.append(text)
            used += size
            index += Character.charCount(codePoint)
        }
        return out.toString()
    }
}

/** [DriveTransport] over [HttpURLConnection], streaming archives from disk and downloads into a file. */
class HttpUrlConnectionTransport : DriveTransport {

    override fun execute(request: DriveRequest): DriveResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.doInput = true
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                when (body) {
                    is DriveBody.Bytes -> {
                        connection.setRequestProperty("Content-Type", body.contentType)
                        connection.setFixedLengthStreamingMode(body.bytes.size)
                        connection.outputStream.use { it.write(body.bytes) }
                    }
                    is DriveBody.Multipart -> {
                        val parts = multipartParts(body.metadataJson, body.mediaType)
                        connection.setRequestProperty("Content-Type", "multipart/related; boundary=${parts.boundary}")
                        connection.setFixedLengthStreamingMode(
                            parts.prefix.size.toLong() + body.media.length() + parts.suffix.size,
                        )
                        connection.outputStream.use { out ->
                            out.write(parts.prefix)
                            body.media.inputStream().use { it.copyTo(out) }
                            out.write(parts.suffix)
                        }
                    }
                    is DriveBody.MultipartBytes -> {
                        val parts = multipartParts(body.metadataJson, body.mediaType)
                        connection.setRequestProperty("Content-Type", "multipart/related; boundary=${parts.boundary}")
                        connection.setFixedLengthStreamingMode(
                            parts.prefix.size.toLong() + body.bytes.size + parts.suffix.size,
                        )
                        connection.outputStream.use { out ->
                            out.write(parts.prefix)
                            out.write(body.bytes)
                            out.write(parts.suffix)
                        }
                    }
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return DriveResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }

    override fun download(url: String, headers: Map<String, String>, out: OutputStream): DriveResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            if (code in 200..299) {
                connection.inputStream.use { it.copyTo(out) }
                return DriveResponse(code, "")
            }
            val text = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return DriveResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private class MultipartParts(
        val boundary: String,
        val prefix: ByteArray,
        val suffix: ByteArray,
    )

    private fun multipartParts(metadataJson: String, mediaType: String): MultipartParts {
        val boundary = "boundless-" + System.nanoTime().toString(16)
        val prefix = (
            "--$boundary\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                metadataJson + "\r\n" +
                "--$boundary\r\n" +
                "Content-Type: $mediaType\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return MultipartParts(boundary, prefix, suffix)
    }
}
