package app.painter.core.io

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Lightweight description of a stored project, read from its manifest only. */
data class ProjectInfo(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val modified: Long,
    val thumbFile: File?,
)

/** Manages the unpacked project directories under [rootDir] (one `<id>/` per project). */
class ProjectStore(val rootDir: File) {

    /**
     * Recovers leftovers of interrupted saves (`.tmp` / `.old` directories). Call once at startup, before
     * any save can be in flight: a concurrent save's `.tmp` would otherwise be treated as a leftover.
     */
    fun recoverInterrupted() {
        val entries = rootDir.listFiles()?.filter { it.isDirectory } ?: return
        for (f in entries) {
            val base = when {
                f.name.endsWith(FsUtil.TMP_SUFFIX) -> f.name.removeSuffix(FsUtil.TMP_SUFFIX)
                f.name.endsWith(FsUtil.OLD_SUFFIX) -> f.name.removeSuffix(FsUtil.OLD_SUFFIX)
                else -> null
            }
            if (base != null && base.isNotEmpty()) runCatching { ProjectIO.recover(File(rootDir, base)) }
        }
    }

    /** All valid projects, most recently modified first. Read-only: in-progress saves are skipped. */
    fun list(): List<ProjectInfo> {
        return (rootDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.endsWith(FsUtil.TMP_SUFFIX) && !it.name.endsWith(FsUtil.OLD_SUFFIX) }
            .mapNotNull(::readInfo)
            .sortedByDescending { it.modified }
    }

    /** Directory of project [id]; does not have to exist. */
    fun dirFor(id: String): File {
        require(id.isNotEmpty() && id.none { it == '/' || it == '\\' || it == ':' } && id != "." && id != "..") {
            "invalid project id: $id"
        }
        return File(rootDir, id)
    }

    /** Deletes project [id] together with any leftover temp directories. */
    fun delete(id: String) {
        val dir = dirFor(id)
        FsUtil.deleteRecursively(FsUtil.tmpOf(dir))
        FsUtil.deleteRecursively(FsUtil.oldOf(dir))
        if (!FsUtil.deleteRecursively(dir)) throw IOException("cannot delete $dir")
    }

    /** Copies project [id] under a new id, named `name + " copy"`; returns the new id. */
    fun duplicate(id: String): String {
        val src = dirFor(id)
        ProjectIO.recover(src)
        val manifest = readManifest(src)
        val newId = UUID.randomUUID().toString()
        val dst = dirFor(newId)
        val tmp = FsUtil.tmpOf(dst)
        FsUtil.deleteRecursively(tmp)
        try {
            if (!src.copyRecursively(tmp)) throw IOException("cannot copy $src")
            val name = (manifest["name"] as? JsonPrimitive)?.content ?: ""
            val now = System.currentTimeMillis()
            val updated = JsonObject(
                manifest + mapOf(
                    "id" to JsonPrimitive(newId),
                    "name" to JsonPrimitive("$name copy"),
                    "created" to JsonPrimitive(now),
                    "modified" to JsonPrimitive(now),
                ),
            )
            File(tmp, ProjectIO.MANIFEST).writeText(manifestJson.encodeToString(JsonObject.serializer(), updated))
            FsUtil.move(tmp, dst)
        } catch (e: Exception) {
            FsUtil.deleteRecursively(tmp)
            throw e
        }
        return newId
    }

    /**
     * Unpacks a `.pnt` archive as a new project. The archived id is kept when the gallery has no project with
     * that id (so a restored Drive backup keeps updating the same remote file); otherwise a fresh id is
     * generated, which turns a restore on the same device into a copy. [rename] receives the archived display
     * name (for example to mark a restored copy).
     */
    fun importArchive(input: InputStream, rename: (String) -> String = { it }): String {
        val staging = File(
            rootDir.parentFile ?: rootDir,
            ".restore-" + UUID.randomUUID().toString(),
        )
        try {
            ProjectIO.importPnt(input, staging)
            val manifest = readManifest(staging)
            val archivedId = (manifest["id"] as? JsonPrimitive)?.content
            val id = archivedId?.takeIf { isValidId(it) && !dirFor(it).exists() }
                ?: UUID.randomUUID().toString()
            val dst = dirFor(id)
            val name = (manifest["name"] as? JsonPrimitive)?.content?.ifBlank { "Restored" } ?: "Restored"
            val updated = JsonObject(
                manifest + mapOf(
                    "id" to JsonPrimitive(id),
                    "name" to JsonPrimitive(rename(name)),
                    "modified" to JsonPrimitive(System.currentTimeMillis()),
                ),
            )
            FsUtil.writeFileAtomic(
                File(staging, ProjectIO.MANIFEST),
                manifestJson.encodeToString(JsonObject.serializer(), updated).toByteArray(Charsets.UTF_8),
            )
            FsUtil.move(staging, dst)
            return id
        } catch (error: Exception) {
            FsUtil.deleteRecursively(staging)
            throw error
        }
    }

    private fun isValidId(id: String): Boolean =
        id.isNotEmpty() && id.none { it == '/' || it == '\\' || it == ':' } && id != "." && id != ".."

    /** Renames project [id] by rewriting only the `name` field of its manifest. */
    fun rename(id: String, name: String) {
        val dir = dirFor(id)
        ProjectIO.recover(dir)
        val updated = JsonObject(readManifest(dir) + ("name" to JsonPrimitive(name)))
        FsUtil.writeFileAtomic(
            File(dir, ProjectIO.MANIFEST),
            manifestJson.encodeToString(JsonObject.serializer(), updated).toByteArray(Charsets.UTF_8),
        )
    }

    private fun readManifest(dir: File): JsonObject {
        val f = File(dir, ProjectIO.MANIFEST)
        if (!f.isFile) throw IOException("no project at $dir")
        return manifestJson.parseToJsonElement(f.readText()).jsonObject
    }

    private fun readInfo(dir: File): ProjectInfo? {
        val f = File(dir, ProjectIO.MANIFEST)
        if (!f.isFile) return null
        val h = runCatching { manifestJson.decodeFromString(ManifestHeaderDto.serializer(), f.readText()) }
            .getOrNull() ?: return null
        val thumb = File(dir, ProjectIO.THUMB).takeIf { it.isFile }
        return ProjectInfo(dir.name, h.name, h.width, h.height, h.modified, thumb)
    }
}
