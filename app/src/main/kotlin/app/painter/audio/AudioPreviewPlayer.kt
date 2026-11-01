package app.painter.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * One [MediaPlayer] that plays the cached audio mix (spec/VIDEO-MODE.md §5.3). The mix starts at tick 0, so
 * seeking the player to `playheadTick / 60` seconds starts preview sound at the playhead; muting only changes
 * the player volume and is instant. The player is created lazily on the main thread and released by the owner.
 */
class AudioPreviewPlayer(private val onUnavailable: () -> Unit) {

    private var player: MediaPlayer? = null
    private var path: String? = null
    private var muted = false
    private var looping = false

    /** True while a prepared player exists for the current mix file. */
    val isPrepared: Boolean get() = player != null

    /**
     * Starts playing [file] at [startMs]. Returns false when the file cannot be prepared; the caller then
     * shows the explicit "preview unavailable" state instead of a control that does nothing.
     */
    fun play(file: File, startMs: Int, muted: Boolean, looping: Boolean): Boolean {
        this.muted = muted
        this.looping = looping
        val mediaPlayer = ensure(file) ?: return false
        return runCatching {
            mediaPlayer.isLooping = looping
            mediaPlayer.setVolume(volume(), volume())
            val position = startMs.coerceAtLeast(0)
            if (position > 0) {
                mediaPlayer.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                mediaPlayer.seekTo(0)
            }
            mediaPlayer.start()
            true
        }.getOrElse {
            release()
            false
        }
    }

    /** Pauses at the current position; the next [play] re-seeks to the playhead. */
    fun pause() {
        val mediaPlayer = player ?: return
        runCatching { if (mediaPlayer.isPlaying) mediaPlayer.pause() }
    }

    /** Stops and rewinds the player; used when the panel closes or the export starts. */
    fun stop() {
        val mediaPlayer = player ?: return
        runCatching {
            if (mediaPlayer.isPlaying) mediaPlayer.pause()
            mediaPlayer.seekTo(0)
        }
    }

    /** Follows a scrub or a stopped clock; no-op when no player has been prepared yet. */
    fun seekTo(milliseconds: Int) {
        val mediaPlayer = player ?: return
        runCatching { mediaPlayer.seekTo(milliseconds.coerceAtLeast(0).toLong(), MediaPlayer.SEEK_CLOSEST) }
    }

    /** Mute/unmute without restarting the mix. */
    fun setMuted(value: Boolean) {
        muted = value
        player?.let { runCatching { it.setVolume(volume(), volume()) } }
    }

    /** Keeps the player in sync with the preview-loop switch. */
    fun setLooping(value: Boolean) {
        looping = value
        player?.let { runCatching { it.isLooping = value } }
    }

    /** Current playback position in milliseconds, or -1 when no player exists. */
    fun positionMs(): Int {
        val mediaPlayer = player ?: return -1
        return runCatching { mediaPlayer.currentPosition }.getOrDefault(-1)
    }

    /** Stops, releases and forgets the current player. */
    fun release() {
        val mediaPlayer = player
        player = null
        path = null
        if (mediaPlayer == null) return
        runCatching { if (mediaPlayer.isPlaying) mediaPlayer.pause() }
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.release() }
    }

    private fun ensure(file: File): MediaPlayer? {
        val current = player
        if (current != null && path == file.absolutePath) return current
        release()
        val mediaPlayer = MediaPlayer()
        return try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mediaPlayer.setOnErrorListener { _, _, _ ->
                release()
                onUnavailable()
                true
            }
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.prepare()
            player = mediaPlayer
            path = file.absolutePath
            mediaPlayer.isLooping = looping
            mediaPlayer.setVolume(volume(), volume())
            mediaPlayer
        } catch (t: Throwable) {
            runCatching { mediaPlayer.release() }
            onUnavailable()
            null
        }
    }

    private fun volume(): Float = if (muted) 0f else 1f
}
