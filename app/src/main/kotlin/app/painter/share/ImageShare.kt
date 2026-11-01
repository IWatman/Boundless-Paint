package app.painter.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.painter.R
import java.io.File

/**
 * Sends the flattened drawing to another app through the system share chooser.
 *
 * The PNG is written to `cacheDir/share` and exposed through [FileProvider], so the receiving app gets a
 * temporary read grant on a content URI and needs no storage permission. The folder is cleared on every share:
 * one image is enough for the chooser and the cache never grows with old drawings.
 */
object ImageShare {

    /** Writes [bytes] as the only file in the share cache and returns it, ready for [send]. */
    fun write(cacheDir: File, name: String, bytes: ByteArray): File {
        val dir = File(cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(name))
        file.writeBytes(bytes)
        return file
    }

    /**
     * A name every Android filesystem and receiving app accepts: path separators, reserved characters and
     * control characters become `_`, a blank name falls back to `Drawing`, and the whole base stays short
     * enough for apps that copy the attachment into their own storage.
     */
    fun fileName(name: String): String {
        val base = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim('.', ' ')
            .ifBlank { "Drawing" }
            .take(MAX_NAME)
        return "$base.png"
    }

    /** Opens the system share sheet for [file] with a read-only grant for the app the user picks. */
    fun send(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("drawing", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
    }

    private const val DIR = "share"
    private const val MAX_NAME = 80
}
