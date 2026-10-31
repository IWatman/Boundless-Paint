package app.painter.core.doc

/** How playback moves from one frame to the next. */
enum class AnimationPlayback {
    /** The last frame is followed by the first one. */
    LOOP,

    /** Playback reverses direction when it reaches either end. */
    PINGPONG,

    /** Playback stops on the last frame. */
    ONCE,
}

/**
 * Animation settings of a document. Frames themselves are ordinary [RasterLayer]s inside the [GroupNode]
 * [frameGroupId]; only the active frame is visible, so the existing compositor, history and project writer
 * need no special case.
 *
 * Values are clamped on construction and by [normalize]: [fps] to 1..60 and the onion-skin distances to
 * 0..[MAX_ONION_FRAMES]. The class is not undoable state; frame edits are undoable, the settings are not.
 */
data class AnimationMeta(
    /** Playback rate, 1..60 frames per second. */
    var fps: Int = DEFAULT_FPS,
    var playback: AnimationPlayback = AnimationPlayback.LOOP,
    /** Draw the neighbouring frames under the active one. */
    var onionSkin: Boolean = false,
    /** Number of previous frames drawn by the onion skin. */
    var onionBefore: Int = 1,
    /** Number of following frames drawn by the onion skin. */
    var onionAfter: Int = 1,
    /** Id of the group whose raster children are the frames, in bottom-to-top paint order. */
    var frameGroupId: String = "",
) {
    init {
        normalize()
    }

    fun normalize() {
        fps = fps.coerceIn(MIN_FPS, MAX_FPS)
        onionBefore = onionBefore.coerceIn(0, MAX_ONION_FRAMES)
        onionAfter = onionAfter.coerceIn(0, MAX_ONION_FRAMES)
    }

    companion object {
        const val DEFAULT_FPS = 12
        const val MIN_FPS = 1
        const val MAX_FPS = 60
        const val MAX_ONION_FRAMES = 4
    }
}

/** One move of a playback cursor. */
data class AnimationStep(val index: Int, val forward: Boolean)

/**
 * Frame helpers shared by the studio and the project reader. A document is an animation when it carries an
 * [AnimationMeta]; its frames are the raster children of the referenced group in [GroupNode.children] order,
 * which is also the timeline order.
 */
object Animation {
    /** Group holding the frames, or null when the document is not an animation (or the group is gone). */
    fun group(doc: Document): GroupNode? =
        doc.animation?.let { doc.findNode(it.frameGroupId) as? GroupNode }

    /** Frames in timeline order (bottom to top inside the frame group). */
    fun frames(doc: Document): List<RasterLayer> =
        group(doc)?.children?.filterIsInstance<RasterLayer>() ?: emptyList()

    /**
     * Index of the active frame. When [Document.activeId] is not one of the frames (for example the user is
     * editing a static background layer), [fallbackFrameId] is used; when that is unknown too, frame 0.
     */
    fun activeIndex(doc: Document, fallbackFrameId: String? = null): Int {
        val frames = frames(doc)
        if (frames.isEmpty()) return 0
        val active = doc.activeId
        val id = if (frames.any { it.id == active }) active else fallbackFrameId
        val index = frames.indexOfFirst { it.id == id }
        return if (index >= 0) index else 0
    }

    /**
     * Restores the document invariant of an animation: the frame group is visible, exactly the active frame is
     * visible and [Document.activeId] points at a frame. Returns false when the frame group or every frame is
     * gone; [clearOrphaned] decides whether the metadata is dropped as well (the studio keeps it while an undo
     * that removed the group can still be redone).
     */
    fun normalize(doc: Document, clearOrphaned: Boolean = true): Boolean {
        val meta = doc.animation ?: return false
        val group = group(doc)
        if (group == null) {
            if (clearOrphaned) doc.animation = null
            return false
        }
        val frames = group.children.filterIsInstance<RasterLayer>()
        if (frames.isEmpty()) {
            if (clearOrphaned) doc.animation = null
            return false
        }
        meta.normalize()
        group.visible = true
        val active = frames.firstOrNull { it.id == doc.activeId } ?: frames.first()
        for (frame in frames) frame.visible = frame.id == active.id
        if (frames.none { it.id == doc.activeId }) doc.activeId = active.id
        return true
    }

    /** Manual Previous/Next: always wraps around, independent of the playback mode. */
    fun step(index: Int, delta: Int, count: Int): Int {
        if (count <= 0) return 0
        val i = index.coerceIn(0, count - 1)
        return ((i + delta) % count + count) % count
    }

    /**
     * Next frame of playback, or null when playback has to stop (the last frame of [AnimationPlayback.ONCE],
     * or a one-frame animation). [forward] is the ping-pong direction of the current cursor.
     */
    fun nextStep(
        index: Int,
        count: Int,
        playback: AnimationPlayback,
        forward: Boolean = true,
    ): AnimationStep? {
        if (count <= 1) return null
        val i = index.coerceIn(0, count - 1)
        return when (playback) {
            AnimationPlayback.LOOP -> AnimationStep((i + 1) % count, true)
            AnimationPlayback.ONCE -> if (i + 1 < count) AnimationStep(i + 1, true) else null
            AnimationPlayback.PINGPONG -> when {
                forward && i >= count - 1 -> AnimationStep(i - 1, false)
                !forward && i <= 0 -> AnimationStep(i + 1, true)
                forward -> AnimationStep(i + 1, true)
                else -> AnimationStep(i - 1, false)
            }
        }
    }
}
