package app.painter.core.video

import app.painter.core.doc.AudioClip
import app.painter.core.doc.VIDEO_TICKS_PER_SECOND
import app.painter.core.doc.VideoAudio
import java.security.MessageDigest

/**
 * Stereo sample source of one project-owned audio file, already resampled to [AudioMix.SAMPLE_RATE].
 * The mixer reads the file in chunks, so a long recording is never held in memory as a whole.
 */
interface AudioSource {
    /** Length of the source in 48 kHz frames. A clip longer than this wraps at the end. */
    val frameCount: Long

    /**
     * Fills [out] at [outOffset] with [count] interleaved stereo frames starting at [startFrame].
     * Returns the number of frames written; 0 when the source cannot provide the requested range.
     */
    fun read(startFrame: Long, count: Int, out: FloatArray, outOffset: Int): Int
}

/**
 * Pure sample math of the video audio mix (spec/VIDEO-MODE.md §4.4 and §6.5). It maps timeline ticks to
 * 48 kHz frames, resolves a clip's source frame with looping and offset, applies the `clip × track × master`
 * gain and hard-clamps the result to [-1, 1]. The Android [AudioMixer] only decodes and encodes around it.
 */
object AudioMix {
    /** Mixing rate of the video project; every source is resampled to it. */
    const val SAMPLE_RATE = 48_000

    /** Stereo output; mono sources are duplicated by their [AudioSource]. */
    const val CHANNELS = 2

    /** AAC-LC bitrate of the mixed file used by both preview and export. */
    const val BIT_RATE = 192_000

    /**
     * Highest gain the mixer accepts before clamping. Volumes of the model are 0..1 (Video.normalize), so a
     * well-formed project never exceeds 1; the wider bound is a safety net for loaded or edited values.
     */
    const val MAX_GAIN = 2f

    /** Frames of [tick] at [sampleRate]; 60 ticks per second and 48 kHz give exactly 800 frames per tick. */
    fun framesAtTick(tick: Long, sampleRate: Int = SAMPLE_RATE): Long {
        if (tick <= 0L || sampleRate <= 0) return 0L
        return tick * sampleRate / VIDEO_TICKS_PER_SECOND
    }

    /** Length of a video timeline in audio frames; audio beyond it is truncated. */
    fun totalFrames(durationTicks: Long, sampleRate: Int = SAMPLE_RATE): Long {
        if (durationTicks <= 0L || sampleRate <= 0) return 0L
        return durationTicks * sampleRate / VIDEO_TICKS_PER_SECOND
    }

    /** Final linear gain of a clip: `clip × track × master`, clamped to 0..[MAX_GAIN]. */
    fun gain(clipVolume: Float, trackVolume: Float, masterVolume: Float): Float {
        val product = clipVolume.coerceIn(0f, MAX_GAIN) *
            trackVolume.coerceIn(0f, MAX_GAIN) *
            masterVolume.coerceIn(0f, MAX_GAIN)
        return product.coerceIn(0f, MAX_GAIN)
    }

    /**
     * Source frame of [clip] for the absolute output [outputFrame], or -1 when the frame is outside the clip.
     * The source wraps at [sourceFrames], so a clip longer than its file loops (spec §4.4).
     */
    fun sourceFrame(clip: AudioClip, outputFrame: Long, sourceFrames: Long, sampleRate: Int = SAMPLE_RATE): Long {
        if (sourceFrames <= 0L) return -1L
        val start = framesAtTick(clip.startTick, sampleRate)
        val end = start + framesAtTick(clip.durationTicks, sampleRate)
        if (outputFrame < start || outputFrame >= end) return -1L
        val offset = framesAtTick(clip.sourceOffsetTicks, sampleRate)
        return (offset + (outputFrame - start)) % sourceFrames
    }

    /**
     * Mixes [frameCount] output frames starting at [startFrame] into [out] (interleaved stereo, cleared first).
     * [sources] maps `AudioClip.file` to its decoded [AudioSource]. Clips that overlap are summed, gains are
     * applied per clip, and every output sample is hard-clamped. Missing sources and muted audio produce
     * silence, so the file length is always the video length.
     */
    fun mixChunk(
        startFrame: Long,
        frameCount: Int,
        clips: List<AudioClip>,
        sources: Map<String, AudioSource>,
        trackVolume: Float,
        masterVolume: Float,
        muted: Boolean,
        out: FloatArray,
        sampleRate: Int = SAMPLE_RATE,
    ) {
        val samples = frameCount * CHANNELS
        require(out.size >= samples) { "output buffer is too small" }
        java.util.Arrays.fill(out, 0, samples, 0f)
        if (muted || frameCount <= 0) return
        val endFrame = startFrame + frameCount
        var scratch: FloatArray? = null
        for (clip in clips) {
            val source = sources[clip.file] ?: continue
            if (source.frameCount <= 0L) continue
            val gain = gain(clip.volume, trackVolume, masterVolume)
            if (gain <= 0f) continue
            val clipStart = framesAtTick(clip.startTick, sampleRate)
            val clipEnd = clipStart + framesAtTick(clip.durationTicks, sampleRate)
            val from = maxOf(startFrame, clipStart)
            val to = minOf(endFrame, clipEnd)
            if (from >= to) continue
            val offset = framesAtTick(clip.sourceOffsetTicks, sampleRate)
            var destination = (from - startFrame).toInt()
            var sourceFrame = (offset + (from - clipStart)) % source.frameCount
            var remaining = (to - from).toInt()
            while (remaining > 0) {
                val available = (source.frameCount - sourceFrame).toInt()
                val count = minOf(remaining, available)
                if (count <= 0) break
                val buffer = scratch ?: FloatArray(frameCount * CHANNELS).also { scratch = it }
                val read = source.read(sourceFrame, count, buffer, 0)
                if (read <= 0) break
                val base = destination * CHANNELS
                for (i in 0 until read * CHANNELS) {
                    out[base + i] += buffer[i] * gain
                }
                destination += read
                remaining -= read
                sourceFrame = (sourceFrame + read) % source.frameCount
            }
        }
        for (i in 0 until samples) {
            out[i] = out[i].coerceIn(-1f, 1f)
        }
    }

    /** File facts that make a cached mix stale when the file changes on disk. */
    data class FileFacts(val size: Long, val modified: Long)

    /**
     * SHA-256 cache key of an audio timeline (spec §6.5). It covers every field that changes the samples:
     * file path/size/mtime, clip placement and gain, track/master volume, mute and the video duration. Any audio
     * edit therefore produces a new key and the previous cached M4A is never reused.
     */
    fun cacheKey(
        audio: VideoAudio,
        durationTicks: Long,
        facts: Map<String, FileFacts>,
        sampleRate: Int = SAMPLE_RATE,
        bitRate: Int = BIT_RATE,
    ): String {
        val text = buildString {
            append("v1|").append(durationTicks).append('|').append(sampleRate).append('|').append(bitRate)
            append("|track:").append(audio.volume).append(',').append(audio.masterVolume).append(',')
                .append(audio.muted)
            val ordered = audio.clips.sortedWith(compareBy({ it.startTick }, { it.id }))
            for (clip in ordered) {
                val file = facts[clip.file]
                append("|c:").append(clip.file)
                    .append(',').append(file?.size ?: -1L)
                    .append(',').append(file?.modified ?: -1L)
                    .append(',').append(clip.startTick)
                    .append(',').append(clip.durationTicks)
                    .append(',').append(clip.sourceOffsetTicks)
                    .append(',').append(clip.volume)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
