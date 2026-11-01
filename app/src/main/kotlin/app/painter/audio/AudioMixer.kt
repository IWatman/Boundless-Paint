package app.painter.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import app.painter.core.doc.VideoAudio
import app.painter.core.video.AudioMix
import app.painter.core.video.AudioSource
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/** An audio file that the mixer cannot use; [clipName] lets the UI name the clip that failed. */
class AudioMixFailure(
    val clipName: String?,
    val kind: Kind,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception("audio mix failed: $kind ${detail.orEmpty()}", cause) {
    enum class Kind {
        NO_CLIPS,
        MISSING_FILE,
        UNSUPPORTED_CODEC,
        DECODE_FAILED,
        ENCODE_FAILED,
    }
}

/**
 * Offline audio mix of the video timeline (spec/VIDEO-MODE.md §6.5). Each project-owned file is decoded to a
 * temporary 48 kHz PCM file, then the timeline is mixed in small chunks with [AudioMix], encoded to AAC-LC and
 * muxed into an M4A of exactly the video duration. The file is cached by [AudioMix.cacheKey], so preview and
 * export play the same bytes and any audio edit produces a new file.
 */
object AudioMixer {

    /** Encoder input is fed in small PCM chunks so even a small codec input buffer is enough. */
    private const val CHUNK_FRAMES = 1024

    private const val TIMEOUT_US = 10_000L

    private const val PCM_HEADER_BYTES = 16L

    /** Returns the cached mix file of [audio], or mixes it into `cacheDir/video-audio/<key>.m4a`. */
    fun prepare(
        projectDir: File,
        audio: VideoAudio,
        durationTicks: Long,
        cacheDir: File,
        checkCancelled: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): File {
        val facts = HashMap<String, AudioMix.FileFacts>()
        for (clip in audio.clips) {
            val file = File(projectDir, clip.file)
            facts[clip.file] = AudioMix.FileFacts(
                size = if (file.isFile) file.length() else -1L,
                modified = if (file.isFile) file.lastModified() else -1L,
            )
        }
        val key = AudioMix.cacheKey(audio, durationTicks, facts)
        val dir = File(cacheDir, CACHE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw AudioMixFailure(null, AudioMixFailure.Kind.ENCODE_FAILED, "cache directory")
        }
        val target = File(dir, "$key.m4a")
        if (target.isFile && target.length() > 0L) return target
        val temp = File(dir, "$key-${System.nanoTime()}.tmp")
        try {
            mix(projectDir, audio, durationTicks, temp, checkCancelled, onProgress)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            temp.delete()
        }
        prune(dir, keep = target.name)
        return target
    }

    /**
     * Mixes [audio] into [output] (an MPEG-4 M4A with one AAC-LC track). Audio beyond [durationTicks] is cut,
     * silence is padded at the end, and every sample is clamped by [AudioMix]. [checkCancelled] is called
     * between chunks; it may throw to abort and must leave no output file behind.
     */
    fun mix(
        projectDir: File,
        audio: VideoAudio,
        durationTicks: Long,
        output: File,
        checkCancelled: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ) {
        if (audio.clips.isEmpty()) {
            throw AudioMixFailure(null, AudioMixFailure.Kind.NO_CLIPS)
        }
        val totalFrames = AudioMix.totalFrames(durationTicks)
        if (totalFrames <= 0L) throw AudioMixFailure(null, AudioMixFailure.Kind.NO_CLIPS)
        output.parentFile?.mkdirs()
        val tempDir = File(output.parentFile ?: projectDir, "video-audio-pcm")
        if (!tempDir.isDirectory && !tempDir.mkdirs()) {
            throw AudioMixFailure(null, AudioMixFailure.Kind.ENCODE_FAILED, "temporary directory")
        }
        tempDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".pcm") &&
                System.currentTimeMillis() - it.lastModified() > STALE_TEMP_MS
        }?.forEach { it.delete() }
        val decoded = HashMap<String, PcmFile>()
        var success = false
        try {
            for (clip in audio.clips) {
                if (decoded.containsKey(clip.file)) continue
                checkCancelled()
                val file = File(projectDir, clip.file)
                if (!file.isFile) {
                    throw AudioMixFailure(clip.name, AudioMixFailure.Kind.MISSING_FILE, clip.file)
                }
                decoded[clip.file] = decode(file, clip.name, tempDir, checkCancelled)
            }
            val sources = HashMap<String, AudioSource>(decoded.size)
            for ((path, pcm) in decoded) sources[path] = PcmFileSource(pcm)
            encode(audio, totalFrames, sources, output, checkCancelled, onProgress)
            success = true
        } finally {
            for (pcm in decoded.values) pcm.file.delete()
            decoded.values.firstOrNull()?.file?.parentFile?.delete()
            if (!success) output.delete()
        }
    }

    private class PcmFile(
        val file: File,
        val sampleRate: Int,
        val channels: Int,
        val frames: Long,
    )

    /**
     * Decodes one project file to a raw 16-bit interleaved PCM file. The header stores sample rate, channels
     * and frame count so [PcmFileSource] can resample and loop without loading the recording into memory.
     */
    private fun decode(
        file: File,
        clipName: String,
        tempDir: File,
        checkCancelled: () -> Unit,
    ): PcmFile {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val temp = File(tempDir, "${file.nameWithoutExtension}-${System.nanoTime()}.pcm")
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getStringOrNull(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            val audioFormat = format ?: throw AudioMixFailure(
                clipName,
                AudioMixFailure.Kind.DECODE_FAILED,
                "no audio track",
            )
            extractor.selectTrack(trackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            decoder = runCatching { MediaCodec.createDecoderByType(mime) }.getOrElse {
                throw AudioMixFailure(
                    clipName,
                    AudioMixFailure.Kind.UNSUPPORTED_CODEC,
                    ProjectAudio.codecName(mime),
                    it,
                )
            }
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            var sampleRate = audioFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: AudioMix.SAMPLE_RATE
            var channels = audioFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var frames = 0L

            // The header is patched after decoding; the body is streamed 16-bit little-endian.
            RandomAccessFile(temp, "rw").use { header ->
                header.setLength(0)
                header.writeInt(sampleRate)
                header.writeInt(channels)
                header.writeLong(0L)
            }
            BufferedOutputStream(FileOutputStream(temp, true), 1 shl 16).use { out ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    checkCancelled()
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index)
                            val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val result = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat
                            sampleRate = outputFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                            channels = outputFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                            encoding = outputFormat.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING) ?: encoding
                        }
                        else -> if (result >= 0) {
                            val buffer = decoder.getOutputBuffer(result)
                            if (buffer != null && info.size > 0) {
                                frames += writePcm(out, buffer, info.offset, info.size, channels, encoding)
                            }
                            decoder.releaseOutputBuffer(result, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
            if (channels !in 1..2) {
                throw AudioMixFailure(
                    clipName,
                    AudioMixFailure.Kind.UNSUPPORTED_CODEC,
                    "$channels channels",
                )
            }
            if (frames <= 0L) throw AudioMixFailure(clipName, AudioMixFailure.Kind.DECODE_FAILED, file.name)
            RandomAccessFile(temp, "rw").use { header ->
                header.seek(0L)
                header.writeInt(sampleRate)
                header.writeInt(channels)
                header.writeLong(frames)
            }
            return PcmFile(temp, sampleRate, channels, frames)
        } catch (failure: AudioMixFailure) {
            temp.delete()
            throw failure
        } catch (t: Throwable) {
            temp.delete()
            throw AudioMixFailure(clipName, AudioMixFailure.Kind.DECODE_FAILED, file.name, t)
        } finally {
            decoder?.let {
                runCatching { it.stop() }
                it.release()
            }
            runCatching { extractor.release() }
        }
    }

    /** Writes decoder PCM as interleaved 16-bit little-endian; returns the number of frames written. */
    private fun writePcm(
        out: BufferedOutputStream,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        channels: Int,
        encoding: Int,
    ): Long {
        if (channels <= 0) return 0L
        val source = buffer.duplicate()
        source.position(offset)
        source.limit(offset + size)
        source.order(ByteOrder.LITTLE_ENDIAN)
        val samples = size / bytesPerSample(encoding)
        if (samples <= 0) return 0L
        val bytes = ByteArray(samples * 2)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = source.asFloatBuffer()
                for (i in 0 until samples) {
                    val value = (floats.get(i).coerceIn(-1f, 1f) * 32767f).roundToInt()
                    bytes[i * 2] = (value and 0xff).toByte()
                    bytes[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                for (i in 0 until samples) {
                    val value = ((source.get(offset + i).toInt() and 0xff) - 128) shl 8
                    bytes[i * 2] = (value and 0xff).toByte()
                    bytes[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val ints = source.asIntBuffer()
                for (i in 0 until samples) {
                    val value = ints.get(i) shr 16
                    bytes[i * 2] = (value and 0xff).toByte()
                    bytes[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
                }
            }
            else -> {
                val shorts = source.asShortBuffer()
                for (i in 0 until samples) {
                    val value = shorts.get(i).toInt()
                    bytes[i * 2] = (value and 0xff).toByte()
                    bytes[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
                }
            }
        }
        out.write(bytes)
        return samples.toLong() / channels
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        else -> 2
    }

    /**
     * 48 kHz stereo view of one PCM file. Mono is duplicated and a source at another rate is linearly
     * resampled; reads are block-sized, so a long recording is never held in memory.
     */
    private class PcmFileSource(private val pcm: PcmFile) : AudioSource {
        override val frameCount: Long =
            if (pcm.sampleRate == AudioMix.SAMPLE_RATE) {
                pcm.frames
            } else {
                pcm.frames * AudioMix.SAMPLE_RATE / pcm.sampleRate
            }

        private var samples: ShortArray = ShortArray(0)

        override fun read(startFrame: Long, count: Int, out: FloatArray, outOffset: Int): Int {
            if (frameCount <= 0L || count <= 0) return 0
            val sourceStart = (startFrame * pcm.sampleRate / AudioMix.SAMPLE_RATE)
            val sourceFrames = min(
                pcm.frames - sourceStart,
                ((count.toLong() * pcm.sampleRate + AudioMix.SAMPLE_RATE - 1) / AudioMix.SAMPLE_RATE) + 2L,
            )
            if (sourceFrames <= 0L) return 0
            val needed = (sourceFrames * pcm.channels).toInt()
            if (samples.size < needed) samples = ShortArray(needed)
            RandomAccessFile(pcm.file, "r").use { input ->
                input.seek(PCM_HEADER_BYTES + sourceStart * pcm.channels * 2L)
                val bytes = ByteArray(needed * 2)
                var read = 0
                while (read < bytes.size) {
                    val n = input.read(bytes, read, bytes.size - read)
                    if (n < 0) break
                    read += n
                }
                val available = read / 2
                for (i in 0 until available) {
                    samples[i] = ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                }
                if (available < needed) java.util.Arrays.fill(samples, available, needed, 0)
            }
            val step = pcm.sampleRate.toDouble() / AudioMix.SAMPLE_RATE
            val baseFrame = sourceStart.toDouble()
            for (i in 0 until count) {
                val position = (startFrame + i) * step - baseFrame
                val index = position.toInt()
                val fraction = (position - index).toFloat()
                val a = index.coerceIn(0, sourceFrames.toInt() - 1)
                val b = (a + 1).coerceAtMost(sourceFrames.toInt() - 1)
                val left = if (pcm.channels == 1) {
                    lerp(samples[a], samples[b], fraction)
                } else {
                    lerp(samples[a * 2], samples[b * 2], fraction)
                }
                val right = if (pcm.channels == 1) {
                    left
                } else {
                    lerp(samples[a * 2 + 1], samples[b * 2 + 1], fraction)
                }
                out[outOffset + i * AudioMix.CHANNELS] = left
                out[outOffset + i * AudioMix.CHANNELS + 1] = right
            }
            return count
        }

        private fun lerp(a: Short, b: Short, fraction: Float): Float =
            ((a / 32768f) * (1f - fraction) + (b / 32768f) * fraction).coerceIn(-1f, 1f)
    }

    /** Encodes the mixed chunks to AAC-LC and muxes them into [output]. */
    private fun encode(
        audio: VideoAudio,
        totalFrames: Long,
        sources: Map<String, AudioSource>,
        output: File,
        checkCancelled: () -> Unit,
        onProgress: (Float) -> Unit,
    ) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioMix.SAMPLE_RATE, 2)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, AudioMix.BIT_RATE)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        val mixed = FloatArray(CHUNK_FRAMES * AudioMix.CHANNELS)
        val pcm = ByteArray(CHUNK_FRAMES * AudioMix.CHANNELS * 2)
        try {
            encoder = if (codecName != null) {
                MediaCodec.createByCodecName(codecName)
            } else {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            encoder.start()
            var trackIndex = -1
            var inputDone = false
            var outputDone = false
            var frame = 0L

            fun drain() {
                while (!outputDone) {
                    val result = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (result == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            throw AudioMixFailure(null, AudioMixFailure.Kind.ENCODE_FAILED, "format changed twice")
                        }
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        continue
                    }
                    if (result < 0) continue
                    val buffer = encoder.getOutputBuffer(result)
                    if (buffer != null && info.size > 0 && muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, buffer, info)
                    }
                    encoder.releaseOutputBuffer(result, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }

            while (!outputDone) {
                checkCancelled()
                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        if (frame < totalFrames) {
                            val count = min(CHUNK_FRAMES.toLong(), totalFrames - frame).toInt()
                            AudioMix.mixChunk(
                                startFrame = frame,
                                frameCount = count,
                                clips = audio.clips,
                                sources = sources,
                                trackVolume = audio.volume,
                                masterVolume = audio.masterVolume,
                                muted = audio.muted,
                                out = mixed,
                            )
                            for (i in 0 until count * AudioMix.CHANNELS) {
                                val value = (mixed[i] * 32767f).roundToInt().coerceIn(-32768, 32767)
                                pcm[i * 2] = (value and 0xff).toByte()
                                pcm[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
                            }
                            val buffer = encoder.getInputBuffer(index)
                                ?: throw AudioMixFailure(null, AudioMixFailure.Kind.ENCODE_FAILED, "input buffer")
                            buffer.clear()
                            buffer.put(pcm, 0, count * AudioMix.CHANNELS * 2)
                            encoder.queueInputBuffer(
                                index,
                                0,
                                count * AudioMix.CHANNELS * 2,
                                frame * 1_000_000L / AudioMix.SAMPLE_RATE,
                                0,
                            )
                            frame += count
                            onProgress(frame.toFloat() / totalFrames.toFloat())
                        } else {
                            encoder.queueInputBuffer(
                                index,
                                0,
                                0,
                                totalFrames * 1_000_000L / AudioMix.SAMPLE_RATE,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }
                drain()
            }
            if (!muxerStarted) throw AudioMixFailure(null, AudioMixFailure.Kind.ENCODE_FAILED, "no audio track")
            muxer.stop()
            muxerStarted = false
        } catch (failure: AudioMixFailure) {
            throw failure
        } catch (t: Throwable) {
            throw AudioMixFailure(null, AudioMixFailure.Kind.ENCODE_FAILED, "AAC-LC", t)
        } finally {
            encoder?.let {
                runCatching { it.stop() }
                it.release()
            }
            muxer?.let {
                if (muxerStarted) runCatching { it.stop() }
                it.release()
            }
        }
    }

    /** Keeps the cache small without deleting the file the caller is about to use. */
    private fun prune(dir: File, keep: String) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".m4a") } ?: return
        val sorted = files.sortedByDescending { it.lastModified() }
        for ((index, file) in sorted.withIndex()) {
            if (index >= CACHE_KEEP || file.name == keep) continue
            file.delete()
        }
        dir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".tmp") &&
                System.currentTimeMillis() - it.lastModified() > STALE_TEMP_MS
        }?.forEach { it.delete() }
    }

    private const val CACHE_DIR = "video-audio"
    private const val CACHE_KEEP = 4
    private const val STALE_TEMP_MS = 3_600_000L

    private fun MediaFormat.getStringOrNull(key: String): String? =
        if (containsKey(key)) getString(key) else null

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null
}
