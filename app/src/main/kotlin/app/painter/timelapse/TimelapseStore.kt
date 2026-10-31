package app.painter.timelapse

import android.graphics.Bitmap
import app.painter.core.blend.Compositor
import app.painter.core.doc.Document
import app.painter.studio.gridToBitmap
import java.io.File

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

    /** Flattens [doc] into a 720 px JPEG frame; returns the new count, or the old one on failure. */
    fun capture(doc: Document): Int {
        val before = frames().size
        val grid = Compositor.flatten(doc, includePaper = true)
        val bmp = gridToBitmap(grid, doc.width, doc.height, maxSide = MAX_SIDE)
        framesDir.mkdirs()
        val file = File(framesDir, "%06d.jpg".format(before))
        val ok = runCatching {
            file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, out) }
        }.isSuccess
        bmp.recycle()
        return if (ok) before + 1 else before
    }

    /** Deletes every recorded frame. */
    fun clear() {
        framesDir.deleteRecursively()
    }

    private companion object {
        const val MAX_SIDE = 720
    }
}
