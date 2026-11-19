package app.painter.timelapse

import android.graphics.Bitmap
import app.painter.core.blend.Compositor
import app.painter.core.doc.Document
import app.painter.studio.gridToBitmap
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Timelapse frame store of one project (spec/document-model.md "Timelapse", M3): a JPEG of the flattened
 * document in `timelapse/frames/000000.jpg`. The caller passes a snapshot, so frames are never encoded from a
 * document that is being edited on another thread.
 */
class TimelapseStore(projectDir: File) {

    val framesDir = File(projectDir, "timelapse/frames")

    /** Files in frame order. */
    fun frames(): List<File> =
        framesDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }.orEmpty().sortedBy { it.name }

    val frameCount: Int get() = frames().size

    /** Flattens [doc] into a JPEG frame up to 4K; returns the new count, or the old one on failure. */
    fun capture(doc: Document): Int {
        val grid = Compositor.flatten(doc, includePaper = true)
        val bmp = gridToBitmap(grid, doc.width, doc.height, maxSide = MAX_SIDE)
        return try {
            publishFrame { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, out) }
        } finally {
            bmp.recycle()
        }
    }

    /** Only completed frames get a .jpg name, so an export can list frames during a capture. */
    internal fun publishFrame(writeJpeg: (OutputStream) -> Boolean): Int {
        val existing = frames()
        val next = (existing.mapNotNull { it.nameWithoutExtension.toLongOrNull() }.maxOrNull() ?: -1L) + 1L
        val target = File(framesDir, String.format(Locale.ROOT, "%06d.jpg", next))
        val written = runCatching {
            framesDir.mkdirs()
            val tmp = Files.createTempFile(framesDir.toPath(), ".frame-", ".tmp")
            try {
                if (!tmp.toFile().outputStream().use(writeJpeg)) return@runCatching false
                Files.move(tmp, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                true
            } finally {
                Files.deleteIfExists(tmp)
            }
        }.getOrDefault(false)
        return existing.size + if (written) 1 else 0
    }

    /** Deletes every recorded frame. */
    fun clear() {
        framesDir.deleteRecursively()
    }

    private companion object {
        const val MAX_SIDE = 3840
    }
}
