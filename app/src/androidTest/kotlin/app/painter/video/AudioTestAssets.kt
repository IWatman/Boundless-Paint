package app.painter.video

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tiny RIFF/WAVE writer for the instrumented audio tests: 48 kHz stereo 16-bit PCM, the format the platform
 * raw decoder accepts. Keeping the fixture generated avoids a binary asset that no other test uses.
 */
internal object AudioTestAssets {
    fun writeWav(file: File, seconds: Double = 1.0, frequencyHz: Double = 440.0) {
        file.parentFile?.mkdirs()
        val rate = 48_000
        val channels = 2
        val frames = (rate * seconds).toInt()
        val dataBytes = frames * channels * 2
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.writeBytes("RIFF")
            le32(out, 36 + dataBytes)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            le32(out, 16)
            le16(out, 1)
            le16(out, channels)
            le32(out, rate)
            le32(out, rate * channels * 2)
            le16(out, channels * 2)
            le16(out, 16)
            out.writeBytes("data")
            le32(out, dataBytes)
            for (i in 0 until frames) {
                val value = (sin(2.0 * PI * frequencyHz * i / rate) * 0.5 * 32767.0).toInt()
                le16(out, value)
                le16(out, value)
            }
        }
    }

    private fun le16(out: DataOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
    }

    private fun le32(out: DataOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
        out.write((value shr 16) and 0xff)
        out.write((value shr 24) and 0xff)
    }
}
