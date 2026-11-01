package app.painter.core.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoExportPlanTest {

    private class FakeCaps(
        override val widthAlignment: Int = 2,
        override val heightAlignment: Int = 2,
        private val supported: (Int, Int, Int) -> Boolean = { _, _, _ -> true },
    ) : VideoEncoderCaps {
        override fun supports(width: Int, height: Int, fps: Int): Boolean =
            supported(width, height, fps)
    }

    @Test
    fun sizesFollowTheCanvasAspect() {
        assertEquals(
            VideoExportPlan.Size(1920, 1080),
            VideoExportPlan.sizeFor(3840, 2160, 1920),
        )
        assertEquals(
            VideoExportPlan.Size(1920, 1440),
            VideoExportPlan.sizeFor(4000, 3000, 1920),
        )
        assertEquals(
            VideoExportPlan.Size(1920, 1920),
            VideoExportPlan.sizeFor(2000, 2000, 1920),
        )
        assertEquals(
            VideoExportPlan.Size(1080, 1920),
            VideoExportPlan.sizeFor(1080, 1920, 1920),
        )
    }

    @Test
    fun smallCanvasesAreScaledUpToThePreset() {
        assertEquals(
            VideoExportPlan.Size(1280, 720),
            VideoExportPlan.sizeFor(640, 360, 1280),
        )
    }

    @Test
    fun pixelCapKeepsAtMost3840By2160() {
        val size = assertNotNull(VideoExportPlan.sizeFor(5000, 5000, 4096))
        assertEquals(VideoExportPlan.MAX_PIXELS.toLong(), size.pixels)
        assertEquals(2880, size.width)
        assertEquals(2880, size.height)
        assertTrue(size.width <= VideoExportPlan.MAX_SIDE && size.height <= VideoExportPlan.MAX_SIDE)
    }

    @Test
    fun sizesAreRoundedDownToEncoderAlignment() {
        val size = assertNotNull(VideoExportPlan.sizeFor(2000, 1000, 1000, widthAlignment = 16, heightAlignment = 8))
        assertEquals(992, size.width)
        assertEquals(496, size.height)
        assertEquals(0, size.width % 16)
        assertEquals(0, size.height % 8)
    }

    @Test
    fun alignmentBelowTwoIsTreatedAsTwo() {
        val size = assertNotNull(VideoExportPlan.sizeFor(1001, 501, 1000, widthAlignment = 0, heightAlignment = 1))
        assertEquals(0, size.width % 2)
        assertEquals(0, size.height % 2)
    }

    @Test
    fun degenerateSizesAreRejected() {
        assertNull(VideoExportPlan.sizeFor(100, 5, 720))
        assertNull(VideoExportPlan.sizeFor(0, 100, 720))
        assertNull(VideoExportPlan.sizeFor(100, 100, 63))
    }

    @Test
    fun bitrateTableMatchesTheSpec() {
        assertEquals(8_000_000, VideoExportPlan.bitRate(1280))
        assertEquals(16_000_000, VideoExportPlan.bitRate(1920))
        assertEquals(24_000_000, VideoExportPlan.bitRate(2560))
        assertEquals(40_000_000, VideoExportPlan.bitRate(3840))
    }

    @Test
    fun capabilityDecisionsKeepUnsupportedPresetsInTheList() {
        val caps = FakeCaps { width, _, _ -> width <= 1280 }
        val choices = VideoExportPlan.choices(1920, 1080, caps = caps)

        assertEquals(listOf(1280, 1920, 2560), choices.map { it.longSide })
        assertEquals(VideoExportPlan.Refusal.ENCODER, choices[1].refusal)
        assertEquals(VideoExportPlan.Refusal.ENCODER, choices[2].refusal)
        assertEquals(1280, choices[0].plan?.width)
        assertEquals(1280, VideoExportPlan.defaultLongSide(choices))
    }

    @Test
    fun defaultIs1080WhenSupported() {
        val choices = VideoExportPlan.choices(1920, 1080, caps = FakeCaps())
        assertEquals(1920, VideoExportPlan.defaultLongSide(choices))
        assertEquals(1920, choices[1].plan?.width)
        assertEquals(1080, choices[1].plan?.height)
        assertEquals(16_000_000, choices[1].plan?.bitRate)
    }

    @Test
    fun sizeRefusalIsReportedBeforeTheEncoderDecision() {
        val choices = VideoExportPlan.choices(100, 2, caps = FakeCaps())
        assertTrue(choices.all { it.refusal == VideoExportPlan.Refusal.SIZE })
        assertNull(VideoExportPlan.defaultLongSide(choices))
    }

    @Test
    fun encoderRateFailureDisablesEverything() {
        val choices = VideoExportPlan.choices(1920, 1080, caps = FakeCaps { _, _, fps -> fps == 30 })
        assertTrue(choices.all { it.refusal == VideoExportPlan.Refusal.ENCODER })
        assertNull(VideoExportPlan.defaultLongSide(choices))
    }

    @Test
    fun frameMathUsesTheTickClock() {
        assertEquals(300, VideoExportPlan.frameCount(300))
        assertEquals(301, VideoExportPlan.frameCount(301))
        assertEquals(150, VideoExportPlan.frameCount(300, fps = 30))
        assertEquals(151, VideoExportPlan.frameCount(301, fps = 30))
        assertEquals(0, VideoExportPlan.frameCount(0))
        assertEquals(300, VideoExportPlan.tickAtFrame(150, fps = 30))
        assertEquals(61, VideoExportPlan.tickAtFrame(61))
        assertEquals(1_000_000, VideoExportPlan.timestampUs(60))
        assertEquals(1_016_666, VideoExportPlan.timestampUs(61))
        assertEquals(1_000_000, VideoExportPlan.timestampUs(30, fps = 30))
    }

    @Test
    fun sizeEstimateUsesBitrateTimesDuration() {
        val plan = VideoExportPlan.Plan(1080, 1920, 1080, 60, 16_000_000)
        assertEquals(10_000_000, VideoExportPlan.estimateBytes(plan, 300))
        assertEquals(0, VideoExportPlan.estimateBytes(plan, 0))
    }
}
