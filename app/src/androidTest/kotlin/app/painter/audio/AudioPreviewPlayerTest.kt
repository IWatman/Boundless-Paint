package app.painter.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.painter.core.doc.AudioClip
import app.painter.core.doc.VideoAudio
import app.painter.video.AudioTestAssets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Smoke test of the preview player on a real MediaPlayer (spec/VIDEO-MODE.md §5.3): the cached mix plays,
 * pauses, seeks, loops and mutes without releasing itself. Timing is not asserted, only the control path.
 */
@RunWith(AndroidJUnit4::class)
class AudioPreviewPlayerTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun playsPausesSeeksLoopsAndMutesWithoutErrors() {
        val project = File(context.cacheDir, "audio-preview-${System.nanoTime()}")
        AudioTestAssets.writeWav(File(project, "audio/voice.wav"), seconds = 1.0)
        val mix = File(context.cacheDir, "audio-preview-${System.nanoTime()}.m4a")
        try {
            try {
                AudioMixer.mix(
                    projectDir = project,
                    audio = VideoAudio(
                        clips = listOf(
                            AudioClip("a", "audio/voice.wav", "Voice", 0L, 60L, 0L, 1f),
                        ),
                    ),
                    durationTicks = 60L,
                    output = mix,
                )
            } catch (failure: AudioMixFailure) {
                if (failure.kind == AudioMixFailure.Kind.UNSUPPORTED_CODEC) {
                    Assume.assumeNoException("no usable audio decoder on this device", failure)
                }
                throw failure
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            var unavailable = false
            var player: AudioPreviewPlayer? = null
            instrumentation.runOnMainSync {
                player = AudioPreviewPlayer { unavailable = true }
                assertTrue(player!!.play(mix, startMs = 250, muted = false, looping = false))
                assertTrue(player!!.isPrepared)
                assertTrue(player!!.positionMs() >= 0)
                player!!.pause()
                player!!.seekTo(500)
                player!!.setMuted(true)
                player!!.setMuted(false)
                player!!.setLooping(true)
            }
            instrumentation.runOnMainSync { player?.release() }
            assertFalse("the preview player reported an error", unavailable)
        } finally {
            mix.delete()
        }
    }
}
