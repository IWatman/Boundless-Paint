package app.painter.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.painter.core.doc.AudioClip
import app.painter.core.doc.VideoAudio
import app.painter.video.AudioTestAssets
import app.painter.video.VideoExportCancelled
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioMixerCancellationTest {
    @Test
    fun cancellationDuringDecodeIsNotWrapped() = checkCancellation(duringEncode = false)

    @Test
    fun cancellationDuringEncodeIsNotWrapped() = checkCancellation(duringEncode = true)

    private fun checkCancellation(duringEncode: Boolean) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val project = File(cache, "audio-cancel-${System.nanoTime()}")
        val output = File(project, "out.m4a")
        AudioTestAssets.writeWav(File(project, "audio/voice.wav"), seconds = 1.0)
        val audio = VideoAudio(clips = listOf(
            AudioClip("a", "audio/voice.wav", "Voice", startTick = 0, durationTicks = 60),
        ))
        var checks = 0
        var encoding = false
        try {
            try {
                AudioMixer.mix(project, audio, 60L, output,
                    checkCancelled = {
                        checks++
                        if ((!duringEncode && checks == 2) || (duringEncode && encoding)) {
                            throw VideoExportCancelled()
                        }
                    },
                    onProgress = { encoding = true },
                )
                fail("expected cancellation")
            } catch (cancelled: VideoExportCancelled) {
                assertFalse("cancelled mix left an output file", output.exists())
            } catch (failure: AudioMixFailure) {
                if (failure.kind == AudioMixFailure.Kind.UNSUPPORTED_CODEC) assumeNoException(failure)
                throw failure
            }
        } finally {
            project.deleteRecursively()
        }
    }
}
