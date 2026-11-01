package app.painter.core.doc

/**
 * Pure, copy-on-write timeline edits of the single drawing track (spec/VIDEO-MODE.md §5.4).
 *
 * Every operation returns a new [VideoMeta] and never mutates [meta]. Clips of a track stay sorted by
 * [VideoClip.startTick] and non-overlapping; operations ripple the following clips by the change so a
 * butt-jointed track stays butt-jointed. Existing gaps are preserved but no operation creates a new one in a
 * butt-jointed track. [Video.normalize] remains the final authority and is expected to be called by the studio.
 */
object VideoEdit {
    /** Duration used when the timeline has no selected clip to copy the length from: one second. */
    const val DEFAULT_CLIP_TICKS = VIDEO_TICKS_PER_SECOND.toLong()

    /**
     * Inserts a clip after [afterClipId], or at [playheadTick] when nothing is selected.
     *
     * With a selection the new clip starts at the selected clip's end and has [durationTicks]; following
     * clips move right. Without a selection the clip is inserted before the first clip that starts at or after
     * the playhead, so the insertion point is the end of the previous clip (or 0). This keeps a butt-jointed
     * track butt-jointed. [frameId] null inserts a deliberate blank clip.
     */
    fun add(
        meta: VideoMeta,
        trackId: String,
        afterClipId: String?,
        clipId: String,
        frameId: String?,
        durationTicks: Long,
        playheadTick: Long,
    ): VideoMeta = updateTrack(meta, trackId) { track ->
        val clips = track.clips.sortedBy { it.startTick }
        val duration = durationTicks.coerceAtLeast(1L)
        val selected = afterClipId?.let { id -> clips.indexOfFirst { it.id == id } } ?: -1
        val insertIndex: Int
        val start: Long
        if (selected >= 0) {
            insertIndex = selected + 1
            start = clips[selected].startTick + clips[selected].durationTicks
        } else {
            val firstAtOrAfter = clips.indexOfFirst { it.startTick >= playheadTick }
            insertIndex = if (firstAtOrAfter < 0) clips.size else firstAtOrAfter
            start = clips.getOrNull(insertIndex - 1)?.let { it.startTick + it.durationTicks } ?: 0L
        }
        val shifted = clips.mapIndexed { index, clip ->
            if (index >= insertIndex) clip.copy(startTick = clip.startTick + duration) else clip
        }.toMutableList()
        shifted.add(insertIndex, VideoClip(clipId, frameId, start, duration))
        track.copy(clips = shifted)
    }

    /** Removes [clipId] and ripples the following clips left, so no gap replaces the clip. */
    fun delete(meta: VideoMeta, trackId: String, clipId: String): VideoMeta = updateTrack(meta, trackId) { track ->
        val clips = track.clips.sortedBy { it.startTick }
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return@updateTrack track
        val removed = clips[index]
        val out = ArrayList<VideoClip>(clips.size - 1)
        for (i in clips.indices) {
            if (i == index) continue
            val clip = clips[i]
            out += if (i > index) {
                clip.copy(startTick = (clip.startTick - removed.durationTicks).coerceAtLeast(0L))
            } else {
                clip
            }
        }
        track.copy(clips = out)
    }

    /**
     * Changes the duration of [clipId] by its right edge and ripples the following clips by the difference.
     * A duration of zero or less deletes the clip (spec: "a clip with duration 0 is deleted").
     */
    fun trim(meta: VideoMeta, trackId: String, clipId: String, durationTicks: Long): VideoMeta {
        if (durationTicks <= 0L) return delete(meta, trackId, clipId)
        return updateTrack(meta, trackId) { track ->
            val clips = track.clips.sortedBy { it.startTick }
            val index = clips.indexOfFirst { it.id == clipId }
            if (index < 0) return@updateTrack track
            val clip = clips[index]
            val delta = durationTicks - clip.durationTicks
            val newEnd = clip.startTick + durationTicks
            val out = ArrayList<VideoClip>(clips.size)
            for (i in clips.indices) {
                val c = clips[i]
                out += if (i == index) {
                    clip.copy(durationTicks = durationTicks)
                } else if (i > index) {
                    c.copy(startTick = (c.startTick + delta).coerceAtLeast(newEnd))
                } else {
                    c
                }
            }
            track.copy(clips = out)
        }
    }

    /**
     * Moves [clipId] to the slot nearest [targetTick]. The clip is removed (closing its hole), the insertion
     * slot is chosen by the other clips' midpoints, and clips from that slot on ripple right by the moved
     * clip's duration. The clip keeps its id, frame and duration.
     */
    fun move(meta: VideoMeta, trackId: String, clipId: String, targetTick: Long): VideoMeta =
        updateTrack(meta, trackId) { track ->
            val clips = track.clips.sortedBy { it.startTick }
            val from = clips.indexOfFirst { it.id == clipId }
            if (from < 0) return@updateTrack track
            val moved = clips[from]
            val base = ArrayList<VideoClip>(clips.size - 1)
            for (i in clips.indices) {
                if (i == from) continue
                val clip = clips[i]
                base += if (i > from) {
                    clip.copy(startTick = (clip.startTick - moved.durationTicks).coerceAtLeast(0L))
                } else {
                    clip
                }
            }
            var insertIndex = 0
            while (insertIndex < base.size) {
                val clip = base[insertIndex]
                val middle = clip.startTick + clip.durationTicks / 2
                if (targetTick < middle) break
                insertIndex++
            }
            val start = base.getOrNull(insertIndex)?.startTick
                ?: base.lastOrNull()?.let { it.startTick + it.durationTicks }
                ?: 0L
            val shifted = base.mapIndexed { index, clip ->
                if (index >= insertIndex) clip.copy(startTick = clip.startTick + moved.durationTicks) else clip
            }.toMutableList()
            shifted.add(insertIndex, moved.copy(startTick = start))
            track.copy(clips = shifted)
        }

    /** Shows or hides one drawing track. */
    fun setTrackVisible(meta: VideoMeta, trackId: String, visible: Boolean): VideoMeta =
        updateTrack(meta, trackId) { it.copy(visible = visible) }

    /** All clip edges of [track], useful as snap targets; [excludeId] omits the clip being dragged. */
    fun edges(track: VideoTrack, excludeId: String? = null): List<Long> {
        val out = ArrayList<Long>(track.clips.size * 2)
        for (clip in track.clips) {
            if (clip.id == excludeId) continue
            out += clip.startTick
            out += clip.startTick + clip.durationTicks
        }
        return out
    }

    private inline fun updateTrack(
        meta: VideoMeta,
        trackId: String,
        edit: (VideoTrack) -> VideoTrack,
    ): VideoMeta {
        val index = meta.tracks.indexOfFirst { it.id == trackId }
        if (index < 0) return meta
        val track = edit(meta.tracks[index])
        if (track == meta.tracks[index]) return meta
        val tracks = meta.tracks.toMutableList()
        tracks[index] = track
        return meta.copy(tracks = tracks)
    }
}
