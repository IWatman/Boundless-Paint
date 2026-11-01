package app.painter.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import app.painter.core.video.VideoEncoderCaps

/**
 * One AVC encoder selected from `MediaCodecList` and the capability view the pure export plan consumes.
 * [colorFormat] is the buffer-input color format reported by the codec: flexible when available, otherwise a
 * planar I420 format.
 */
class AvcEncoder internal constructor(
    val name: String,
    val colorFormat: Int,
    override val widthAlignment: Int,
    override val heightAlignment: Int,
    private val video: MediaCodecInfo.VideoCapabilities,
) : VideoEncoderCaps {

    override fun supports(width: Int, height: Int, fps: Int): Boolean {
        if (width <= 0 || height <= 0 || fps <= 0) return false
        return runCatching { video.areSizeAndRateSupported(width, height, fps.toDouble()) }
            .getOrDefault(false)
    }
}

/** Device H.264 encoder discovery for the export dialog and the exporter (spec/VIDEO-MODE.md §6.4). */
object VideoEncoders {

    private class Candidate(
        val info: MediaCodecInfo,
        val video: MediaCodecInfo.VideoCapabilities,
        val colorFormat: Int,
        val flexible: Boolean,
    )

    /**
     * Best buffer-input AVC encoder: the largest supported area first (so a device with a software 1080p and a
     * hardware 720p encoder still offers 1080p), then hardware acceleration, then flexible YUV input.
     * Null when the device has no AVC encoder that accepts planar or flexible YUV.
     */
    @Suppress("DEPRECATION")
    fun best(): AvcEncoder? {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            .getOrDefault(emptyArray())
        val candidates = ArrayList<Candidate>()
        for (info in infos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: continue
            val flexible = caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val planar = caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
            if (!flexible && !planar) continue
            val video = caps.videoCapabilities ?: continue
            candidates += Candidate(
                info = info,
                video = video,
                colorFormat = if (flexible) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                } else {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                },
                flexible = flexible,
            )
        }
        val best = candidates.sortedWith(
            compareByDescending<Candidate> {
                it.video.supportedWidths.upper.toLong() * it.video.supportedHeights.upper.toLong()
            }
                .thenByDescending { it.info.isHardwareAccelerated }
                .thenByDescending { it.flexible }
                .thenBy { it.info.name },
        ).firstOrNull() ?: return null
        return AvcEncoder(
            name = best.info.name,
            colorFormat = best.colorFormat,
            widthAlignment = best.video.widthAlignment.coerceAtLeast(2),
            heightAlignment = best.video.heightAlignment.coerceAtLeast(2),
            video = best.video,
        )
    }
}
