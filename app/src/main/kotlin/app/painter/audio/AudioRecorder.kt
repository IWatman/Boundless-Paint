package app.painter.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Microphone recorder of the video audio track (spec/VIDEO-MODE.md §5.6): AAC-LC in an MP4 container,
 * 48 kHz stereo at 128 kbps, written directly into the project's `audio/`. The caller owns the
 * `RECORD_AUDIO` permission; a failure leaves no file behind.
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null

    /** True between a successful [start] and [stop]/[release]. */
    val isRecording: Boolean get() = recorder != null

    /** Starts recording into [file]; returns false when the microphone or the encoder cannot start. */
    fun start(file: File): Boolean {
        release()
        file.parentFile?.mkdirs()
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioSamplingRate(48_000)
            mediaRecorder.setAudioChannels(2)
            mediaRecorder.setAudioEncodingBitRate(128_000)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            output = file
            true
        } catch (t: Throwable) {
            runCatching { mediaRecorder.release() }
            file.delete()
            false
        }
    }

    /**
     * Stops and finalizes the recording. Returns the file when it was written successfully, otherwise null;
     * an empty or missing file is deleted.
     */
    fun stop(): File? {
        val mediaRecorder = recorder ?: return null
        recorder = null
        val file = output
        output = null
        val ok = runCatching { mediaRecorder.stop() }.isSuccess
        runCatching { mediaRecorder.release() }
        if (!ok || file == null || !file.isFile || file.length() <= 0L) {
            file?.delete()
            return null
        }
        return file
    }

    /** Stops without keeping the file; used when the document is left during a recording. */
    fun discard() {
        val mediaRecorder = recorder
        recorder = null
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        output?.delete()
        output = null
    }

    /** Releases the recorder without using it again; any pending file is discarded. */
    fun release() {
        discard()
    }
}
