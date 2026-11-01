package app.painter.core.video

import app.painter.core.doc.AudioClip
import app.painter.core.doc.VideoAudio
import app.painter.core.doc.VideoMeta

/**
 * Pure, copy-on-write edits of the audio track (spec/VIDEO-MODE.md §4.4 and §5.4). Audio clips may overlap,
 * unlike drawing clips, so move and trim never ripple other clips. Every operation returns a new [VideoMeta]
 * and leaves [meta] untouched.
 */
object AudioTimeline {
    /** The audio track of [meta], or an empty one when the document has no audio yet. */
    fun audioOf(meta: VideoMeta): VideoAudio = meta.audio ?: VideoAudio()

    /** Clips of [meta] in start order; empty when the document has no audio. */
    fun clipsOf(meta: VideoMeta): List<AudioClip> = meta.audio?.clips ?: emptyList()

    /** Adds [clip] and sorts the track; the first clip creates the audio track. */
    fun insert(meta: VideoMeta, clip: AudioClip): VideoMeta {
        val audio = audioOf(meta)
        val normalized = normalizeClip(clip) ?: return meta
        return meta.copy(audio = normalize(audio.copy(clips = audio.clips + normalized)))
    }

    /** Removes [clipId]; the audio track stays even when it becomes empty (its volumes remain meaningful). */
    fun delete(meta: VideoMeta, clipId: String): VideoMeta = update(meta) { audio ->
        audio.copy(clips = audio.clips.filterNot { it.id == clipId })
    }

    /** Moves [clipId] to [startTick]; overlapping clips are allowed and nothing ripples. */
    fun move(meta: VideoMeta, clipId: String, startTick: Long): VideoMeta = updateClip(meta, clipId) { clip ->
        clip.copy(startTick = startTick.coerceAtLeast(0L))
    }

    /** Sets the duration of [clipId]; zero or less deletes the clip. */
    fun trim(meta: VideoMeta, clipId: String, durationTicks: Long): VideoMeta {
        if (durationTicks <= 0L) return delete(meta, clipId)
        return updateClip(meta, clipId) { it.copy(durationTicks = durationTicks) }
    }

    /** Clip gain, clamped to 0..1 like [VideoAudio.volume]. */
    fun setClipVolume(meta: VideoMeta, clipId: String, volume: Float): VideoMeta =
        updateClip(meta, clipId) { it.copy(volume = volume.coerceIn(0f, 1f)) }

    /** Track volume, applied after every clip gain. */
    fun setTrackVolume(meta: VideoMeta, volume: Float): VideoMeta = update(meta) {
        it.copy(volume = volume.coerceIn(0f, 1f))
    }

    /** Master volume, applied after the track volume. */
    fun setMasterVolume(meta: VideoMeta, volume: Float): VideoMeta = update(meta) {
        it.copy(masterVolume = volume.coerceIn(0f, 1f))
    }

    /** Track mute; a muted track is omitted from the MP4 and makes no sound in the preview. */
    fun setMuted(meta: VideoMeta, muted: Boolean): VideoMeta = update(meta) { it.copy(muted = muted) }

    /** Last tick covered by an audio clip. Audio never extends the video duration. */
    fun endTick(audio: VideoAudio): Long =
        audio.clips.maxOfOrNull { it.startTick + it.durationTicks } ?: 0L

    /** Clips whose file is not present in the project, according to [exists] (a project-relative path). */
    fun missingClips(audio: VideoAudio, exists: (String) -> Boolean): List<AudioClip> =
        audio.clips.filterNot { exists(it.file) }

    /**
     * Drops clips that end at or before the video end and truncates a clip that crosses it. The model keeps
     * the full clip, so extending the video later restores the sound; the exporter and the mixer use this
     * helper when they need the audible range (spec §4.3: audio never extends the video).
     */
    fun clampToVideo(audio: VideoAudio, durationTicks: Long): VideoAudio {
        if (durationTicks <= 0L) return audio.copy(clips = emptyList())
        val clips = audio.clips.mapNotNull { clip ->
            val start = maxOf(0L, clip.startTick)
            if (start >= durationTicks) return@mapNotNull null
            val end = minOf(clip.startTick + clip.durationTicks, durationTicks)
            val duration = end - start
            if (duration <= 0L) null else clip.copy(startTick = start, durationTicks = duration)
        }.sortedWith(compareBy({ it.startTick }, { it.id }))
        return audio.copy(clips = clips)
    }

    /**
     * Normalized audio track: non-negative starts/offsets, positive durations, volumes in 0..1 and clips
     * sorted by start (ties by id). Overlaps are preserved on purpose.
     */
    fun normalize(audio: VideoAudio): VideoAudio {
        val clips = audio.clips.mapNotNull(::normalizeClip)
            .sortedWith(compareBy({ it.startTick }, { it.id }))
        return audio.copy(
            clips = clips,
            volume = audio.volume.coerceIn(0f, 1f),
            masterVolume = audio.masterVolume.coerceIn(0f, 1f),
        )
    }

    /** Replaces the audio block of [meta] with its normalized form; a missing block stays missing. */
    fun normalize(meta: VideoMeta): VideoMeta {
        val audio = meta.audio ?: return meta
        return meta.copy(audio = normalize(audio))
    }

    private fun normalizeClip(clip: AudioClip): AudioClip? {
        val start = maxOf(0L, clip.startTick)
        val end = maxOf(0L, clip.startTick + clip.durationTicks)
        val duration = end - start
        if (duration <= 0L) return null
        return clip.copy(
            startTick = start,
            durationTicks = duration,
            sourceOffsetTicks = maxOf(0L, clip.sourceOffsetTicks),
            volume = clip.volume.coerceIn(0f, 1f),
        )
    }

    private inline fun update(meta: VideoMeta, edit: (VideoAudio) -> VideoAudio): VideoMeta {
        val audio = meta.audio ?: return meta
        val updated = edit(audio)
        if (updated == audio) return meta
        return meta.copy(audio = normalize(updated))
    }

    private inline fun updateClip(
        meta: VideoMeta,
        clipId: String,
        edit: (AudioClip) -> AudioClip,
    ): VideoMeta = update(meta) { audio ->
        val index = audio.clips.indexOfFirst { it.id == clipId }
        if (index < 0) return@update audio
        val clips = audio.clips.toMutableList()
        clips[index] = edit(clips[index])
        audio.copy(clips = clips)
    }
}
