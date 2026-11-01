package app.painter.video

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import app.painter.core.blend.Compositor
import app.painter.core.doc.Document
import app.painter.core.doc.VIDEO_TICKS_PER_SECOND
import app.painter.core.doc.Video
import app.painter.core.doc.VideoMeta
import app.painter.core.video.VideoExportPlan
import app.painter.core.video.Yuv
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

/** The user cancelled the export between frames or during the SAF copy. */
class VideoExportCancelled : Exception("video export cancelled")

/** A device, encoder or storage failure with a short reason the progress dialog can show. */
class VideoExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * H.264 MP4 exporter of the video timeline (spec/VIDEO-MODE.md §6.3–6.6). Each unique tick is flattened with
 * the compositor's visibility override, so the live document is never modified; the encoded file is written to
 * a cache temp file by the caller and streamed to the user's SAF URI after a successful encode.
 *
 * The encoder loop checks cancellation between frames and inside every 10 ms codec timeout, so Cancel takes
 * effect at the next frame boundary. On failure or cancellation [output] is deleted.
 */
object VideoExporter {

    private const val TIMEOUT_US = 10_000L
    private const val REPORT_INTERVAL_NS = 250_000_000L

    class Result(
        val width: Int,
        val height: Int,
        val frames: Long,
        val bytes: Long,
    )

    fun encode(
        doc: Document,
        meta: VideoMeta,
        plan: VideoExportPlan.Plan,
        encoder: AvcEncoder,
        output: File,
        audio: File? = null,
        onProgress: (rendered: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result {
        val durationTicks = Video.durationTicks(meta)
        val totalFrames = VideoExportPlan.frameCount(durationTicks, plan.fps)
        if (durationTicks <= 0L || totalFrames <= 0L) {
            throw VideoExportException("The timeline has no frames")
        }
        val videoEndUs = durationTicks * 1_000_000L / VIDEO_TICKS_PER_SECOND
        output.parentFile?.mkdirs()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var audioReader: AudioSampleReader? = null
        var audioTrackIndex = -1
        var success = false
        val info = MediaCodec.BufferInfo()
        try {
            val c = createConfiguredEncoder(encoder, plan)
            codec = c
            val m = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            if (audio != null) {
                val reader = AudioSampleReader(audio)
                audioReader = reader
                audioTrackIndex = m.addTrack(reader.format)
            }
            c.start()
            val rasterizer = VideoRasterizer(
                canvasWidth = doc.width,
                canvasHeight = doc.height,
                width = plan.width,
                height = plan.height,
                paper = if (doc.paper.visible) doc.paper.color else null,
            )
            var cachedVisibility: Map<String, Boolean>? = null
            var trackIndex = -1
            var frameIndex = 0L
            var inputDone = false
            var outputDone = false
            var lastReport = 0L

            fun drainOutputs() {
                while (!outputDone) {
                    if (isCancelled()) throw VideoExportCancelled()
                    val outIndex = c.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            throw VideoExportException("The encoder changed its output format twice")
                        }
                        trackIndex = m.addTrack(c.outputFormat)
                        m.start()
                        muxerStarted = true
                        continue
                    }
                    if (outIndex < 0) continue
                    // Keep the tracks interleaved by PTS: all audio before this video sample is written first.
                    if (muxerStarted && audioReader != null) {
                        audioReader.writeBefore(m, audioTrackIndex, info.presentationTimeUs, videoEndUs)
                    }
                    val buffer = c.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0 && muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        m.writeSampleData(trackIndex, buffer, info)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }

            while (!outputDone) {
                if (isCancelled()) throw VideoExportCancelled()
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (frameIndex < totalFrames) {
                            // The playhead never reaches the exclusive end, so the last tick keeps its frame.
                            val tick = min(VideoExportPlan.tickAtFrame(frameIndex, plan.fps), durationTicks - 1L)
                            val visibility = Video.visibleFrames(doc, meta, tick)
                            if (visibility != cachedVisibility) {
                                val grid = Compositor.flatten(doc, includePaper = false, visible = visibility)
                                rasterizer.render(grid)
                                cachedVisibility = visibility
                            }
                            val ptsUs = VideoExportPlan.timestampUs(frameIndex, plan.fps)
                            val size = writeInputImage(c, inIndex, rasterizer.frame)
                            c.queueInputBuffer(inIndex, 0, size, ptsUs, 0)
                            frameIndex++
                            val now = System.nanoTime()
                            if (frameIndex == totalFrames || now - lastReport >= REPORT_INTERVAL_NS) {
                                lastReport = now
                                onProgress(frameIndex, totalFrames)
                            }
                        } else {
                            val endUs = durationTicks * 1_000_000L / VIDEO_TICKS_PER_SECOND
                            c.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                endUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }
                drainOutputs()
            }

            if (!muxerStarted) throw VideoExportException("The encoder produced no video track")
            if (isCancelled()) throw VideoExportCancelled()
            // Audio that starts after the last video sample still belongs to the file, up to the video end.
            if (audioReader != null) audioReader.flushBefore(m, audioTrackIndex, videoEndUs)
            m.stop()
            muxerStarted = false
            success = true
            return Result(plan.width, plan.height, totalFrames, output.length())
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            muxer?.let {
                if (muxerStarted) runCatching { it.stop() }
                it.release()
            }
            audioReader?.release()
            if (!success) output.delete()
        }
    }

    /**
     * Creates the encoder and configures the requested size/rate. Some encoders reject the BT.709 color keys,
     * so the configuration is retried without them and finally without an explicit color format.
     */
    private fun createConfiguredEncoder(encoder: AvcEncoder, plan: VideoExportPlan.Plan): MediaCodec {
        var last: Throwable? = null
        for (variant in 0..2) {
            val codec = runCatching { MediaCodec.createByCodecName(encoder.name) }
                .getOrElse { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, plan.width, plan.height)
                format.setInteger(MediaFormat.KEY_BIT_RATE, plan.bitRate)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, plan.fps)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (variant < 2) {
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, encoder.colorFormat)
                }
                if (variant == 0) {
                    format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec
            } catch (t: Throwable) {
                last = t
                runCatching { codec.release() }
            }
        }
        throw VideoExportException(
            "The video encoder cannot open ${plan.width}×${plan.height}",
            last,
        )
    }

    /** Writes one I420 frame into the codec input and returns the queued byte count. */
    private fun writeInputImage(codec: MediaCodec, index: Int, frame: Yuv.I420Frame): Int {
        val image = codec.getInputImage(index)
        if (image != null) {
            copyPlane(image.planes[0], frame.y, frame.width, frame.height, frame.yRowStride)
            copyPlane(image.planes[1], frame.u, frame.chromaWidth, frame.chromaHeight, frame.uvRowStride)
            copyPlane(image.planes[2], frame.v, frame.chromaWidth, frame.chromaHeight, frame.uvRowStride)
            return frame.width * frame.height * 3 / 2
        }
        val bytes = frame.toI420()
        val buffer = codec.getInputBuffer(index)
            ?: throw VideoExportException("The video encoder does not accept buffer input on this device")
        if (buffer.capacity() < bytes.size) {
            throw VideoExportException("The video encoder input buffer is too small")
        }
        buffer.clear()
        buffer.put(bytes)
        return bytes.size
    }

    /** Copies [source] with [sourceStride] into one MediaCodec YUV plane, honoring its row/pixel strides. */
    private fun copyPlane(
        plane: Image.Plane,
        source: ByteArray,
        width: Int,
        height: Int,
        sourceStride: Int,
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) {
            throw VideoExportException("The video encoder reported an invalid YUV plane")
        }
        if (pixelStride == 1) {
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.put(source, row * sourceStride, width)
            }
        } else {
            for (row in 0 until height) {
                var dst = row * rowStride
                val src = row * sourceStride
                for (column in 0 until width) {
                    buffer.put(dst, source[src + column])
                    dst += pixelStride
                }
            }
        }
    }

    /**
     * Reads compressed AAC samples of the mixed M4A and writes them into the MP4 muxer by PTS. The sample
     * after [writeBefore]'s limit is kept as a one-sample lookahead, because [MediaExtractor] cannot unread it.
     */
    private class AudioSampleReader(audio: File) {
        val format: MediaFormat
        private val extractor = MediaExtractor()
        private val buffer: ByteBuffer = ByteBuffer.allocateDirect(256 * 1024)
        private val info = MediaCodec.BufferInfo()
        private var pending = false
        private var done = false

        init {
            try {
                extractor.setDataSource(audio.absolutePath)
                var index = -1
                var found: MediaFormat? = null
                for (track in 0 until extractor.trackCount) {
                    val candidate = extractor.getTrackFormat(track)
                    val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        index = track
                        found = candidate
                        break
                    }
                }
                if (index < 0 || found == null) {
                    throw VideoExportException("The audio file has no audio track")
                }
                extractor.selectTrack(index)
                format = found
            } catch (t: Throwable) {
                runCatching { extractor.release() }
                throw t
            }
        }

        /** Writes every pending sample with `pts < limitUs` and `pts < endUs`. */
        fun writeBefore(muxer: MediaMuxer, trackIndex: Int, limitUs: Long, endUs: Long) {
            while (true) {
                if (!pending && !readNext()) return
                if (info.presentationTimeUs >= limitUs || info.presentationTimeUs >= endUs) return
                buffer.position(0)
                buffer.limit(info.size)
                muxer.writeSampleData(trackIndex, buffer, info)
                pending = false
                extractor.advance()
            }
        }

        /** Writes the remaining samples up to [endUs]; used after the last video sample. */
        fun flushBefore(muxer: MediaMuxer, trackIndex: Int, endUs: Long) {
            writeBefore(muxer, trackIndex, Long.MAX_VALUE, endUs)
        }

        private fun readNext(): Boolean {
            if (done) return false
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                done = true
                return false
            }
            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            info.set(0, size, extractor.sampleTime, flags)
            pending = true
            return true
        }

        fun release() {
            runCatching { extractor.release() }
        }
    }
}
