package app.painter.audio

import android.content.ContentResolver
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import app.painter.core.doc.VIDEO_TICKS_PER_SECOND
import java.io.File
import java.util.UUID

/**
 * Why an audio file cannot be used by the video timeline. The ViewModel turns each case into a localized
 * message; the codec name is always preserved so the user can tell which file/codec was rejected.
 */
sealed class AudioImportFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No decoder in `MediaCodecList.REGULAR_CODECS` accepts the stream. */
    class UnsupportedCodec(val codec: String, cause: Throwable? = null) :
        AudioImportFailure("unsupported audio codec $codec", cause)

    /** M5 mixes mono or stereo only (spec/VIDEO-MODE.md §4.4). */
    class TooManyChannels(val codec: String, val channels: Int) :
        AudioImportFailure("$codec has $channels channels")

    /** The container has no audio track at all. */
    class NoAudioTrack : AudioImportFailure("the file has no audio track")

    /** The picker stream or the file cannot be read. */
    class Unreadable(cause: Throwable? = null) : AudioImportFailure("the audio file cannot be read", cause)
}

/** What the timeline needs to know about one project-owned audio file. */
data class AudioFileInfo(
    val codec: String,
    val mime: String,
    val channels: Int,
    val sampleRate: Int,
    val durationUs: Long,
) {
    /** Duration in 60 Hz timeline ticks, rounded to the nearest tick and never zero. */
    val durationTicks: Long
        get() = ((durationUs * VIDEO_TICKS_PER_SECOND + 500_000L) / 1_000_000L).coerceAtLeast(1L)
}

/** An audio file copied into the project, ready to become a clip. */
data class ImportedAudio(
    /** Path relative to the project directory, e.g. `audio/8f3c….m4a`. */
    val relativePath: String,
    /** Display name without the extension. */
    val name: String,
    val info: AudioFileInfo,
)

/**
 * SAF import and codec validation of project audio (spec/VIDEO-MODE.md §4.7 and §5.6). Files are copied into
 * `audio/` of the project so archive, duplicate, autosave and Drive backup keep them; `audio/` is not an owned
 * directory of [app.painter.core.io.ProjectIO], exactly like `timelapse/`, so the normal save path carries it.
 */
object ProjectAudio {

    /** Probes [file] and returns its audio format, or throws an [AudioImportFailure]. */
    fun probe(file: File): AudioFileInfo {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(file.absolutePath)
            } catch (t: Throwable) {
                throw AudioImportFailure.Unreadable(t)
            }
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getStringOrNull(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val codec = codecName(mime)
                val channels = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
                if (channels !in 1..2) {
                    throw AudioImportFailure.TooManyChannels(codec, channels)
                }
                val decoder = runCatching {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                }.getOrNull()
                if (decoder == null) {
                    throw AudioImportFailure.UnsupportedCodec(codec)
                }
                val durationUs = format.getLongOrNull(MediaFormat.KEY_DURATION) ?: 0L
                if (durationUs <= 0L) throw AudioImportFailure.Unreadable()
                return AudioFileInfo(
                    codec = codec,
                    mime = mime,
                    channels = channels,
                    sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0,
                    durationUs = durationUs,
                )
            }
            throw AudioImportFailure.NoAudioTrack()
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Copies [uri] into `audio/` of [projectDir], validates it and returns the relative path. A rejected or
     * unreadable file is deleted again, so a failed import never leaves an orphan in the project.
     */
    fun import(resolver: ContentResolver, uri: Uri, projectDir: File): ImportedAudio {
        val displayName = displayName(resolver, uri)
        val base = displayName.substringBeforeLast('.', displayName).ifBlank { "Audio" }
        val extension = extensionOf(displayName)
            ?: runCatching { extensionOfMime(resolver.getType(uri)) }.getOrNull()
            ?: "audio"
        val dir = File(projectDir, AUDIO_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw AudioImportFailure.Unreadable()
        val token = UUID.randomUUID().toString().replace("-", "")
        val temp = File(dir, ".import-$token.tmp")
        val target = File(dir, "$token.$extension")
        try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: throw AudioImportFailure.Unreadable()
            val info = probe(temp)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return ImportedAudio("$AUDIO_DIR/${target.name}", base, info)
        } catch (failure: AudioImportFailure) {
            temp.delete()
            target.delete()
            throw failure
        } catch (t: Throwable) {
            temp.delete()
            target.delete()
            throw AudioImportFailure.Unreadable(t)
        }
    }

    /** New empty `audio/rec-<uuid>.m4a` path for the microphone recorder. */
    fun recordingFile(projectDir: File): File {
        val dir = File(projectDir, AUDIO_DIR)
        dir.mkdirs()
        val token = UUID.randomUUID().toString().replace("-", "")
        return File(dir, "rec-$token.m4a")
    }

    /** Project-relative path of [file] inside [projectDir], with `/` separators. */
    fun relativePath(projectDir: File, file: File): String =
        file.relativeTo(projectDir).invariantSeparatorsPath

    /** Friendly codec name used in rejection messages. */
    fun codecName(mime: String): String = when (mime) {
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/flac" -> "FLAC"
        "audio/ogg" -> "Ogg"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/amr-wb" -> "AMR-WB"
        "audio/amr-nb" -> "AMR"
        "audio/3gpp" -> "AMR"
        "audio/raw" -> "PCM"
        else -> mime.substringAfter('/').uppercase()
    }

    const val AUDIO_DIR = "audio"

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "audio"
    }

    private fun extensionOf(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension.takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
    }

    private fun extensionOfMime(mime: String?): String? = when (mime) {
        "audio/mp4", "audio/aac", "audio/mp4a-latm" -> "m4a"
        "audio/mpeg" -> "mp3"
        "audio/wav", "audio/x-wav", "audio/raw" -> "wav"
        "audio/ogg" -> "ogg"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/opus" -> "opus"
        else -> null
    }

    private fun MediaFormat.getStringOrNull(key: String): String? =
        if (containsKey(key)) getString(key) else null

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.getLongOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null
}
