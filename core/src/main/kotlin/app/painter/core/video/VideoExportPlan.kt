package app.painter.core.video

import app.painter.core.doc.VIDEO_TICKS_PER_SECOND
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Capabilities of one H.264 encoder, as far as the pure export plan needs them. The Android adapter answers
 * from `MediaCodecList`; tests use a fake. See spec/VIDEO-MODE.md §6.4 and §6.8.
 */
interface VideoEncoderCaps {
    /** Width alignment reported by the encoder; values below 2 are treated as 2. */
    val widthAlignment: Int

    /** Height alignment reported by the encoder; values below 2 are treated as 2. */
    val heightAlignment: Int

    /** True when this encoder accepts [width] × [height] at [fps] frames per second. */
    fun supports(width: Int, height: Int, fps: Int): Boolean
}

/**
 * Pure decisions of the video export: output size from the canvas aspect and the preset, the 8,294,400-pixel
 * cap, encoder alignment, bitrates and capability outcomes. Sizes never depend on device state, so the export
 * dialog can decide what to offer before the file picker opens (spec/VIDEO-MODE.md §6.7–6.8).
 */
object VideoExportPlan {

    const val FPS = 60

    /** Maximum output area, 3840 × 2160. */
    const val MAX_PIXELS = 8_294_400

    /** Rejects sizes that no useful encoder preset can produce. */
    const val MIN_SIDE = 64

    const val MAX_SIDE = 4096

    const val DEFAULT_LONG_SIDE = 1920

    /** Presets of S3; 4K arrives with S5. Values are the long side, as in spec §6.8. */
    val LONG_SIDES = intArrayOf(1280, 1920, 2560)

    /** One encoder-aligned output size. Both sides are at least [MIN_SIDE] and at most [MAX_SIDE]. */
    data class Size(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height
    }

    /** A ready-to-encode size and rate. */
    data class Plan(
        val longSide: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitRate: Int,
    )

    /** Why a preset cannot be used. */
    enum class Refusal {
        /** The canvas aspect or the pixel cap cannot produce a size within the limits. */
        SIZE,

        /** This encoder does not support the exact size at the requested rate. */
        ENCODER,
    }

    /** One resolution chip: [plan] when the encoder accepts it, otherwise the reason it is disabled. */
    data class Choice(
        val longSide: Int,
        val plan: Plan?,
        val refusal: Refusal?,
    ) {
        val supported: Boolean get() = plan != null
    }

    /**
     * H.264 bitrate of a preset. Design constants from spec §6.8, not measurements: 8/16/24/40 Mbps for
     * 720p/1080p/1440p/4K. Values between presets use the next lower bucket.
     */
    fun bitRate(longSide: Int): Int = when {
        longSide <= 1280 -> 8_000_000
        longSide <= 1920 -> 16_000_000
        longSide <= 2560 -> 24_000_000
        else -> 40_000_000
    }

    /**
     * Output size for a canvas and a preset. The long canvas side maps to [longSide], the aspect follows the
     * canvas, and the result is reduced to [MAX_PIXELS]. Width and height are then rounded down to the encoder
     * alignment, so at most a few pixels of the requested size are lost. Null when the result would fall outside
     * [MIN_SIDE]..[MAX_SIDE].
     */
    fun sizeFor(
        canvasWidth: Int,
        canvasHeight: Int,
        longSide: Int,
        widthAlignment: Int = 2,
        heightAlignment: Int = 2,
    ): Size? {
        if (canvasWidth <= 0 || canvasHeight <= 0 || longSide < MIN_SIDE) return null
        val canvasLong = max(canvasWidth, canvasHeight)
        val target = longSide.coerceAtMost(MAX_SIDE)
        val scale = target.toDouble() / canvasLong
        var width = (canvasWidth * scale).roundToInt().coerceAtLeast(1)
        var height = (canvasHeight * scale).roundToInt().coerceAtLeast(1)
        val area = width.toLong() * height.toLong()
        if (area > MAX_PIXELS) {
            val factor = sqrt(MAX_PIXELS.toDouble() / area)
            width = floor(width * factor).toInt().coerceAtLeast(1)
            height = floor(height * factor).toInt().coerceAtLeast(1)
        }
        val alignW = max(2, widthAlignment)
        val alignH = max(2, heightAlignment)
        width = width / alignW * alignW
        height = height / alignH * alignH
        if (width < MIN_SIDE || height < MIN_SIDE) return null
        if (width > MAX_SIDE || height > MAX_SIDE) return null
        return Size(width, height)
    }

    /** Decides one preset for [caps]. */
    fun choice(
        canvasWidth: Int,
        canvasHeight: Int,
        longSide: Int,
        fps: Int = FPS,
        caps: VideoEncoderCaps,
    ): Choice {
        val size = sizeFor(canvasWidth, canvasHeight, longSide, caps.widthAlignment, caps.heightAlignment)
            ?: return Choice(longSide, null, Refusal.SIZE)
        if (fps <= 0 || !caps.supports(size.width, size.height, fps)) {
            return Choice(longSide, null, Refusal.ENCODER)
        }
        return Choice(
            longSide = longSide,
            plan = Plan(longSide, size.width, size.height, fps, bitRate(longSide)),
            refusal = null,
        )
    }

    /** Every S3 preset in display order; unsupported ones stay in the list so the dialog can explain them. */
    fun choices(
        canvasWidth: Int,
        canvasHeight: Int,
        fps: Int = FPS,
        caps: VideoEncoderCaps,
    ): List<Choice> = LONG_SIDES.map { choice(canvasWidth, canvasHeight, it, fps, caps) }

    /** 1080p when supported, otherwise the largest supported preset; null when nothing is supported. */
    fun defaultLongSide(choices: List<Choice>): Int? =
        choices.firstOrNull { it.longSide == DEFAULT_LONG_SIDE && it.supported }?.longSide
            ?: choices.lastOrNull { it.supported }?.longSide

    /**
     * Number of output frames of a timeline. At 60 fps one tick is one frame; at 30 fps every second tick is
     * emitted, and a final odd tick still gets a frame.
     */
    fun frameCount(durationTicks: Long, fps: Int = FPS): Long {
        if (durationTicks <= 0L || fps <= 0) return 0L
        return (durationTicks * fps + VIDEO_TICKS_PER_SECOND - 1) / VIDEO_TICKS_PER_SECOND
    }

    /** Timeline tick shown by output frame [frame]. */
    fun tickAtFrame(frame: Long, fps: Int = FPS): Long {
        if (frame <= 0L || fps <= 0) return 0L
        return frame * VIDEO_TICKS_PER_SECOND / fps
    }

    /** Presentation timestamp in microseconds; integer division keeps every timestamp within 1 µs of exact. */
    fun timestampUs(frame: Long, fps: Int = FPS): Long {
        if (frame <= 0L || fps <= 0) return 0L
        return frame * 1_000_000L / fps
    }

    /** Rough encoded size at [Plan.bitRate] for the timeline, labelled "estimate" in the dialog. */
    fun estimateBytes(plan: Plan, durationTicks: Long): Long {
        if (durationTicks <= 0L) return 0L
        return plan.bitRate.toLong() * durationTicks / (8L * VIDEO_TICKS_PER_SECOND)
    }
}
