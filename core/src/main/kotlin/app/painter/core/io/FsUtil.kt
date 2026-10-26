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

    /** Moves [src] to [dst] (which must not exist): atomic if possible, else plain move, else copy+delete. */
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
        if (!src.copyRecursively(dst, overwrite = false)) throw IOException("cannot move $src to $dst")
        if (!deleteRecursively(src)) throw IOException("moved but cannot delete $src")
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
