package app.painter.studio

import app.painter.core.video.VideoExportPlan

/**
 * Chosen in the video export dialog (spec/VIDEO-MODE.md §5.9). [longSide] is the preset's long side, not the
 * shorter side: 1280 is 720p, 1920 is 1080p and 2560 is 1440p. The timeline itself always stays 60 ticks per
 * second. S3 exports video only; [includeAudio] is kept for the audio slice and is not offered by the dialog yet.
 */
data class VideoExportOptions(
    val longSide: Int = VideoExportPlan.DEFAULT_LONG_SIDE,
    val outputFps: Int = VideoExportPlan.FPS,
    val includeAudio: Boolean = false,
)

/** UI label of a preset; the long side 1920 is called 1080p, its shorter side. */
fun videoExportPresetLabel(longSide: Int): String = when (longSide) {
    1280 -> "720p"
    1920 -> "1080p"
    2560 -> "1440p"
    3840 -> "4K"
    else -> "${longSide}px"
}
