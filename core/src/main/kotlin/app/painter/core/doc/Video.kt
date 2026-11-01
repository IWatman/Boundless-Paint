package app.painter.core.doc

import app.painter.core.video.AudioTimeline

/**
 * Video timeline is always 60 ticks per second; in a 60 fps export one tick is one output frame.
 * See spec/VIDEO-MODE.md §4.
 */
const val VIDEO_TICKS_PER_SECOND = 60

/**
 * Video block of a document; null on a document that has not been converted. Tracks are ordered as the
 * timeline renders them. The block is immutable: an edit produces a new tree, so snapshots and background
 * reads never race the editor. Like [AnimationMeta] it is not undoable state.
 */
data class VideoMeta(
    val tracks: List<VideoTrack> = emptyList(),
    val audio: VideoAudio? = null,
)

/** One drawing track: an ordered, non-overlapping sequence of clips that references one frame group. */
data class VideoTrack(
    val id: String,
    val name: String,
    /** Group whose raster children are the frames of this track. */
    val groupId: String,
    val visible: Boolean = true,
    val clips: List<VideoClip> = emptyList(),
)

/**
 * A timed reference to a frame layer, or a deliberate blank/pause when [frameId] is null. Clips of a track
 * are sorted by [startTick] and do not overlap after [Video.normalize].
 */
data class VideoClip(
    val id: String,
    /** Frame layer id, or null for a deliberate blank/pause clip. */
    val frameId: String?,
    val startTick: Long,
    val durationTicks: Long,
)

/** Audio track of the video. Audio never extends the video duration. */
data class VideoAudio(
    val clips: List<AudioClip> = emptyList(),
    /** Track volume, 0..1, applied after every clip gain. */
    val volume: Float = 1f,
    /** Master volume, 0..1, applied after the track volume. */
    val masterVolume: Float = 1f,
    val muted: Boolean = false,
)

/** A timed reference to a project-owned audio file; clips may overlap. */
data class AudioClip(
    val id: String,
    /** Path relative to the project directory, e.g. "audio/8f3c….m4a". */
    val file: String,
    val name: String,
    val startTick: Long,
    val durationTicks: Long,
    val sourceOffsetTicks: Long = 0,
    /** 0..1. */
    val volume: Float = 1f,
)

/**
 * Pure helpers of the video timeline. The source frames stay the animation frames; a clip is a timed
 * reference, not a copy of the pixels. See spec/VIDEO-MODE.md §4.2–4.5.
 */
object Video {
    /**
     * Builds the initial block from [doc]'s animation: one drawing track and one clip per playback step.
     * Ping-pong is expanded into a linear sequence; [AnimationMeta] is left untouched. Returns null when the
     * document has no animation or no frames. [trackName] is localized by the caller.
     */
    fun fromAnimation(doc: Document, trackName: String = "Drawing"): VideoMeta? {
        val meta = doc.animation ?: return null
        val frames = Animation.frames(doc)
        if (frames.isEmpty()) return null
        val steps = sequence(frames.size, meta.playback)
        val clips = ArrayList<VideoClip>(steps.size)
        for ((step, index) in steps.withIndex()) {
            val frame = frames.getOrNull(index) ?: continue
            val start = tickAt(step, meta.fps)
            val end = tickAt(step + 1, meta.fps)
            val duration = end - start
            if (duration <= 0L) continue
            clips += VideoClip("vclip-$step-${frame.id}", frame.id, start, duration)
        }
        if (clips.isEmpty()) return null
        return VideoMeta(
            tracks = listOf(
                VideoTrack(
                    id = "vtrack-${meta.frameGroupId}",
                    name = trackName,
                    groupId = meta.frameGroupId,
                    clips = clips,
                ),
            ),
        )
    }

    /** Frames of [track] in group order (bottom to top), empty when the group is gone. */
    fun frames(doc: Document, track: VideoTrack): List<RasterLayer> =
        (doc.findNode(track.groupId) as? GroupNode)?.children?.filterIsInstance<RasterLayer>() ?: emptyList()

    /** Last tick covered by any drawing clip. Audio never extends the video. */
    fun durationTicks(meta: VideoMeta): Long {
        var end = 0L
        for (track in meta.tracks) {
            for (clip in track.clips) {
                end = maxOf(end, clip.startTick + clip.durationTicks)
            }
        }
        return end
    }

    /** Clip of [track] covering [tick] (`startTick <= tick < end`), or null in a gap. */
    fun clipAt(track: VideoTrack, tick: Long): VideoClip? =
        track.clips.firstOrNull { tick >= it.startTick && tick < it.startTick + it.durationTicks }

    /**
     * Visibility override for the compositor at [tick]: every frame of every track is hidden first, then the
     * frame of the visible tracks' current clip is shown. Nodes with `hideInRecording` are always hidden.
     * Returns only the ids that the timeline controls; other nodes keep their own visibility.
     */
    fun visibleFrames(doc: Document, meta: VideoMeta, tick: Long): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        for (track in meta.tracks) {
            val frames = frames(doc, track)
            for (frame in frames) out[frame.id] = false
            if (track.visible) {
                out[track.groupId] = true
                val frameId = clipAt(track, tick)?.frameId
                if (frameId != null) out[frameId] = true
            } else {
                out[track.groupId] = false
            }
        }
        doc.root.walk { if (it.hideInRecording) out[it.id] = false }
        return out
    }

    /**
     * Returns a normalized copy of [meta] (or null when no track remains). Clips are clamped to the
     * non-negative timeline and zero-length clips are dropped; drawing clips are sorted and overlapping ones
     * are shortened, so a track is always butt-jointed. Audio clips may overlap and are only sorted.
     *
     * [clearOrphaned] true (load) drops clips whose frame is gone and tracks whose group is missing or has no
     * raster children. False (studio and save) keeps them, because an autosave can run between a frame
     * deletion and its undo.
     */
    fun normalize(meta: VideoMeta, doc: Document, clearOrphaned: Boolean): VideoMeta? {
        val tracks = ArrayList<VideoTrack>(meta.tracks.size)
        for (track in meta.tracks) {
            val group = doc.findNode(track.groupId) as? GroupNode
            val frames = group?.children?.filterIsInstance<RasterLayer>() ?: emptyList()
            if (clearOrphaned && (group == null || frames.isEmpty())) continue
            val clamped = track.clips.mapNotNull { clip ->
                val start = maxOf(0L, clip.startTick)
                val end = maxOf(0L, clip.startTick + clip.durationTicks)
                val duration = end - start
                if (duration <= 0L) null else clip.copy(startTick = start, durationTicks = duration)
            }
            var previousEnd = 0L
            val clips = ArrayList<VideoClip>(clamped.size)
            for (clip in clamped.sortedBy { it.startTick }) {
                if (clearOrphaned && clip.frameId != null && frames.none { it.id == clip.frameId }) continue
                val start = maxOf(clip.startTick, previousEnd)
                val duration = clip.durationTicks - (start - clip.startTick)
                if (duration <= 0L) continue
                clips += clip.copy(startTick = start, durationTicks = duration)
                previousEnd = start + duration
            }
            tracks += track.copy(clips = clips)
        }
        if (tracks.isEmpty()) return null
        val audio = meta.audio?.let(AudioTimeline::normalize)
        return VideoMeta(tracks, audio)
    }

    /**
     * Tick boundary after [step] source frames of an animation at [fps], rounded half up:
     * `(step * 60 + fps / 2) / fps`. The timestamp of a tick is derived from its index, never accumulated,
     * so a long video does not drift.
     */
    fun tickAt(step: Int, fps: Int): Long {
        val rate = fps.coerceIn(AnimationMeta.MIN_FPS, AnimationMeta.MAX_FPS)
        val index = step.coerceAtLeast(0)
        return (index.toLong() * VIDEO_TICKS_PER_SECOND + rate / 2) / rate
    }

    /**
     * Frame indices of one playback cycle: `LOOP` and `ONCE` are one pass; `PINGPONG` runs up to the last
     * frame and back without repeating frame 0 (`[0, 1, 2, 1]` for three frames).
     */
    fun sequence(frames: Int, playback: AnimationPlayback): List<Int> {
        if (frames <= 0) return emptyList()
        return when (playback) {
            AnimationPlayback.LOOP, AnimationPlayback.ONCE -> (0 until frames).toList()
            AnimationPlayback.PINGPONG -> when (frames) {
                1 -> listOf(0)
                else -> (0 until frames).toList() + (frames - 2 downTo 1).toList()
            }
        }
    }
}
