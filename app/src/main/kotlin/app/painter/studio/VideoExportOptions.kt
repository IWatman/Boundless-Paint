package app.painter.studio

import app.painter.core.video.VideoExportPlan

/**
 * Chosen in the video export dialog (spec/VIDEO-MODE.md §5.9). [longSide] is the preset's long side, not the
 * shorter side: 1280 is 720p, 1920 is 1080p, 2560 is 1440p and 3840 is 4K. The timeline always stays 60 ticks
 * per second; [outputFps] is 60 except for the explicit 4K/30 fallback, and the dialog never changes it
 * silently. [includeAudio] adds the AAC-LC track of the offline mix; the dialog disables it when there is no
 * audio, the track is muted or a file is missing.
 */
data class VideoExportOptions(
    val longSide: Int = VideoExportPlan.DEFAULT_LONG_SIDE,
    val outputFps: Int = VideoExportPlan.FPS,
    val includeAudio: Boolean = true,
)

/** UI label of a preset; the long side 1920 is called 1080p, its shorter side. */
fun videoExportPresetLabel(longSide: Int): String = when (longSide) {
    1280 -> "720p"
    1920 -> "1080p"
    2560 -> "1440p"
    3840 -> "4K"
    else -> "${longSide}px"
}
