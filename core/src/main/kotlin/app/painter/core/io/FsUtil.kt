package app.painter.core.io

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File-system helpers that tolerate Windows/Android rename quirks. */
internal object FsUtil {
    const val TMP_SUFFIX = ".tmp"
    const val OLD_SUFFIX = ".old"
    const val COMPLETE_MARKER = ".complete"

    fun tmpOf(dir: File) = File(dir.absoluteFile.parentFile, dir.name + TMP_SUFFIX)
    fun oldOf(dir: File) = File(dir.absoluteFile.parentFile, dir.name + OLD_SUFFIX)

    /** Moves [src] to [dst] (which must not exist), falling back to copy+delete only for files. */
    fun move(src: File, dst: File) {
        if (dst.exists()) throw IOException("target exists: $dst")
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (_: AtomicMoveNotSupportedException) {
        } catch (_: IOException) {
        }
        try {
            Files.move(src.toPath(), dst.toPath())
            return
        } catch (_: IOException) {
        }
        if (src.renameTo(dst)) return
        copyAndDeleteForMove(src, dst)
    }

    internal fun copyAndDeleteForMove(src: File, dst: File, delete: (File) -> Boolean = { it.delete() }) {
        // A failed recursive delete can destroy part of the source while leaving both paths visible.
        // Project recovery cannot safely determine which tree is authoritative in that state.
        if (src.isDirectory) throw IOException("cannot safely move directory $src to $dst")
        copyForMove(src, dst)
        if (!delete(src)) throw IOException("copied to $dst but cannot delete $src; both paths remain")
    }

    /** Copy into a private sibling first so a failed copy cannot look like a completed move. */
    internal fun copyForMove(
        src: File,
        dst: File,
        copy: (File, File) -> Boolean = { from, to -> from.copyRecursively(to, overwrite = false) },
    ) {
        val parent = dst.absoluteFile.parentFile
        val staging = Files.createTempDirectory(parent.toPath(), ".painter-move-").toFile()
        try {
            val candidate = File(staging, "content")
            if (!copy(src, candidate)) throw IOException("cannot move $src to $dst")
            Files.move(candidate.toPath(), dst.toPath())
        } finally {
            deleteRecursively(staging)
        }
    }

    /** Writes a single file atomically by writing a sibling temp file and replacing. */
    fun writeFileAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.absoluteFile.parentFile, target.name + TMP_SUFFIX)
        tmp.writeBytes(bytes)
        try {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: IOException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun deleteRecursively(f: File): Boolean = !f.exists() || f.deleteRecursively()
}
