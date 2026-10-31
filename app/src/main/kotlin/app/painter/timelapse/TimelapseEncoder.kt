package app.painter.timelapse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.max

/**
 * Encodes recorded JPEG frames into an H.264 MP4 with MediaCodec (spec/GAPS.md "Timelapse", M3).
 *
 * The caller picks [maxSide] (720/1080/2160) and [durationSec] (0 = every frame once). Frames are repeated or
 * sampled evenly to fill the requested duration at 30 fps. Conversion is RGB -> YUV 4:2:0; the encoder's
 * flexible input image is used when the device offers one, with a planar fallback.
 */
object TimelapseEncoder {

    const val FPS = 30

    class Result(val width: Int, val height: Int, val frames: Int)

    fun encode(frames: List<File>, out: File, maxSide: Int, durationSec: Int): Result {
        require(frames.isNotEmpty()) { "no timelapse frames" }
        val first = BitmapFactory.decodeFile(frames.first().path) ?: error("cannot decode the first frame")
        val scale = maxSide.toFloat() / max(first.width, first.height)
        var width = (first.width * minOf(scale, 1f)).toInt()
        var height = (first.height * minOf(scale, 1f)).toInt()
        first.recycle()
        width = (width / 2 * 2).coerceAtLeast(2)
        height = (height / 2 * 2).coerceAtLeast(2)
        val targetFrames = if (durationSec > 0) durationSec * FPS else frames.size
        val sequence = buildSequence(frames.size, targetFrames)
        val bitRate = when {
            maxSide >= 2160 -> 20_000_000
            maxSide >= 1080 -> 8_000_000
            else -> 4_000_000
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val yuv = YuvFrame(width, height)
            var frameIndex = 0
            var eos = false
            while (!eos) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    if (frameIndex < sequence.size) {
                        val file = frames[sequence[frameIndex]]
                        val src = BitmapFactory.decodeFile(file.path)
                        if (src == null) {
                            codec.queueInputBuffer(inIndex, 0, 0, frameIndex * 1_000_000L / FPS, 0)
                        } else {
                            val scaled = if (src.width == width && src.height == height) src
                            else Bitmap.createScaledBitmap(src, width, height, true).also { if (it !== src) src.recycle() }
                            yuv.fill(codec, inIndex, scaled)
                            scaled.recycle()
                            codec.queueInputBuffer(inIndex, 0, yuv.byteCount(width, height), frameIndex * 1_000_000L / FPS, 0)
                        }
                        frameIndex++
                    } else {
                        codec.queueInputBuffer(inIndex, 0, 0, frameIndex * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    }
                }
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        continue
                    }
                    if (outIndex < 0) continue
                    val buf = codec.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return Result(width, height, sequence.size)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /** Indices into [count] frames that fill [target] output frames (evenly sampled, or looped). */
    internal fun buildSequence(count: Int, target: Int): IntArray {
        if (count <= 0 || target <= 0) return IntArray(0)
        if (count >= target) return IntArray(target) { (it.toLong() * count / target).toInt().coerceIn(0, count - 1) }
        return IntArray(target) { it % count }
    }

    /** Reusable YUV 4:2:0 buffer writer for the encoder's input image. */
    private class YuvFrame(val width: Int, val height: Int) {
        fun byteCount(w: Int, h: Int): Int = w * h * 3 / 2

        fun fill(codec: MediaCodec, index: Int, bmp: Bitmap) {
            val image = codec.getInputImage(index)
            if (image != null) {
                val y = image.planes[0]
                val u = image.planes[1]
                val v = image.planes[2]
                val row = IntArray(width)
                for (py in 0 until height) {
                    bmp.getPixels(row, 0, width, 0, py, width, 1)
                    for (px in 0 until width) {
                        val c = row[px]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                        y.buffer.put(py * y.rowStride + px * y.pixelStride, yy.toByte())
                        if (px % 2 == 0 && py % 2 == 0) {
                            val uu = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                            val vv = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                            val ci = (py / 2) * u.rowStride + (px / 2) * u.pixelStride
                            val cj = (py / 2) * v.rowStride + (px / 2) * v.pixelStride
                            u.buffer.put(ci, uu.toByte())
                            v.buffer.put(cj, vv.toByte())
                        }
                    }
                }
                return
            }
            // Fallback: the input buffer is the I420 plane in order.
            val buffer = codec.getInputBuffer(index) ?: return
            val row = IntArray(width)
            var yPos = 0
            val uStart = width * height
            val vStart = uStart + width * height / 4
            var uvPos = 0
            buffer.clear()
            for (py in 0 until height) {
                bmp.getPixels(row, 0, width, 0, py, width, 1)
                for (px in 0 until width) {
                    val c = row[px]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    buffer.put(yPos++, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                    if (px % 2 == 0 && py % 2 == 0) {
                        buffer.put(uStart + uvPos, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                        buffer.put(vStart + uvPos, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                        uvPos++
                    }
                }
            }
        }
    }
}
