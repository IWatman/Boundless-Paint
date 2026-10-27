package app.painter.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A published APK build that is newer than the running app. */
data class UpdateInfo(
    val version: Version,
    val tagName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
)

/** Reads GitHub Releases of [repo] (`owner/name`) and picks the newest release APK. */
class UpdateChecker(private val repo: String) {

    /**
     * Returns the newest release when it is newer than [currentVersion], `null` when the app
     * is up to date. Network errors are thrown as [IOException].
     */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        latestUpdate(fetchReleases(), currentVersion)
    }

    private fun fetchReleases(): String {
        val connection = URL("$API_URL/repos/$repo/releases?per_page=$MAX_RELEASES")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            // 404 means the repository has no releases yet, not a network problem.
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return "[]"
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("GitHub API HTTP $code ${detail.take(160)}".trim())
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val API_URL = "https://api.github.com"
        const val TIMEOUT_MS = 10_000
        const val MAX_RELEASES = 10
        const val USER_AGENT = "Painter-Android"
    }
}

/**
 * Picks the first non-draft release of the GitHub releases [json] array that has an `.apk`
 * asset and is newer than [currentVersion].
 */
internal fun latestUpdate(json: String, currentVersion: String): UpdateInfo? {
    val current = Version.parse(currentVersion) ?: return null
    val releases = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return null
    val release = releases.map { it.jsonObject }.firstOrNull { it["draft"]?.jsonPrimitive?.booleanOrNull != true }
        ?: return null
    val tagName = release.stringOrNull("tag_name")?.trim().orEmpty()
    val version = Version.parse(tagName) ?: return null
    if (version <= current) return null
    val asset = release["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        .firstOrNull { it.stringOrNull("name")?.endsWith(".apk", ignoreCase = true) == true }
        ?: return null
    val apkUrl = asset.stringOrNull("browser_download_url") ?: return null
    return UpdateInfo(
        version = version,
        tagName = tagName.ifEmpty { version.toString() },
        notes = release.stringOrNull("body").orEmpty(),
        apkUrl = apkUrl,
        apkSize = asset["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
    )
}

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
