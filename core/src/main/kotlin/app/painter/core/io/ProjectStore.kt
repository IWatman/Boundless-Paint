package app.painter.core.io

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
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

    /** All valid projects, most recently modified first. Leftovers of interrupted saves are recovered. */
    fun list(): List<ProjectInfo> {
        val entries = rootDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        for (f in entries) {
            val base = when {
                f.name.endsWith(FsUtil.TMP_SUFFIX) -> f.name.removeSuffix(FsUtil.TMP_SUFFIX)
                f.name.endsWith(FsUtil.OLD_SUFFIX) -> f.name.removeSuffix(FsUtil.OLD_SUFFIX)
                else -> null
            }
            if (base != null && base.isNotEmpty()) runCatching { ProjectIO.recover(File(rootDir, base)) }
        }
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
