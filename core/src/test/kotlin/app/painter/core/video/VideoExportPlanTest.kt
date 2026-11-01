package app.painter.core.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private val heap256 = 256L * 1024 * 1024
    private val heap512 = 512L * 1024 * 1024

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
        assertEquals(
            VideoExportPlan.Size(2160, 3840),
            VideoExportPlan.sizeFor(2160, 3840, VideoExportPlan.FOUR_K_LONG_SIDE),
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
    fun nonWideFourKStaysUnderThePixelCap() {
        val plan = assertNotNull(
            VideoExportPlan.choice(3840, 2880, VideoExportPlan.FOUR_K_LONG_SIDE, caps = FakeCaps()).plan,
        )
        assertTrue(plan.width.toLong() * plan.height <= VideoExportPlan.MAX_PIXELS)
        assertTrue(plan.width < VideoExportPlan.FOUR_K_LONG_SIDE)
        assertEquals(40_000_000, plan.bitRate)
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
    fun capabilityMatrixOffersFourKSixtyOnlyWhenSupported() {
        val choices = VideoExportPlan.choices(1920, 1080, caps = FakeCaps { _, _, fps -> fps == 60 })

        assertEquals(listOf(1280, 1920, 2560, 3840), choices.map { it.longSide })
        assertEquals(listOf(60, 60, 60, 60), choices.map { it.fps })
        assertTrue(choices.all { it.supported })
        assertEquals(3840, choices[3].plan?.width)
        assertEquals(2160, choices[3].plan?.height)
        assertEquals(40_000_000, choices[3].plan?.bitRate)
    }

    @Test
    fun fourKThirtyIsAnExplicitFallback() {
        val caps = FakeCaps { width, _, fps -> if (width >= 3840) fps == 30 else fps == 60 }
        val choices = VideoExportPlan.choices(1920, 1080, caps = caps)

        assertEquals(4, choices.size)
        assertTrue(choices.take(3).all { it.supported && it.fps == 60 })
        assertEquals(30, choices[3].fps)
        assertTrue(choices[3].supported)
        assertEquals(3840, choices[3].plan?.width)
        assertEquals(30, choices[3].plan?.fps)
        assertEquals(40_000_000, choices[3].plan?.bitRate)
        assertEquals(1920, VideoExportPlan.defaultChoice(choices)?.longSide)
    }

    @Test
    fun fourKIsDisabledWhenNeitherRateIsSupported() {
        val caps = FakeCaps { width, _, fps -> width < 3840 && fps == 60 }
        val choices = VideoExportPlan.choices(1920, 1080, caps = caps)

        assertEquals(4, choices.size)
        assertEquals(60, choices[3].fps)
        assertEquals(VideoExportPlan.Refusal.ENCODER, choices[3].refusal)
        assertFalse(choices[3].supported)
        assertNull(choices[3].plan)
        assertNotNull(choices[3].size)
    }

    @Test
    fun memoryGuardRefusesFourKOnASmallHeap() {
        val small = VideoExportPlan.choices(3840, 2160, caps = FakeCaps(), largeHeapBytes = heap256)
        assertEquals(VideoExportPlan.Refusal.MEMORY, small[3].refusal)
        assertNull(small[3].plan)
        assertEquals(3840, small[3].size?.width)
        // Lower presets are not gated by the 4K guard.
        assertTrue(small.take(3).all { it.supported })

        val large = VideoExportPlan.choices(3840, 2160, caps = FakeCaps(), largeHeapBytes = heap512)
        assertTrue(large[3].supported)
        assertEquals(60, large[3].fps)
    }

    @Test
    fun memoryEstimateCountsTwoYuvBuffersGridAndHeadroom() {
        assertEquals(141_570_048L, VideoExportPlan.estimatedPeakBytes(3840, 2160))
        assertEquals(73_141_248L, VideoExportPlan.estimatedPeakBytes(1920, 1080))
        assertTrue(VideoExportPlan.memoryGuardAllows(3840, 2160, heap512))
        assertFalse(VideoExportPlan.memoryGuardAllows(3840, 2160, heap256))
        // Unknown heap: never block on a figure the caller does not have.
        assertTrue(VideoExportPlan.memoryGuardAllows(3840, 2160, 0L))
    }

    @Test
    fun fourKThirtyRefusalKeepsTheMemoryReason() {
        val caps = FakeCaps { width, _, fps -> width < 3840 && fps == 60 }
        val choices = VideoExportPlan.choices(1920, 1080, caps = caps, largeHeapBytes = heap256)
        assertEquals(VideoExportPlan.Refusal.ENCODER, choices[3].refusal)

        val thirty = FakeCaps { width, _, fps -> if (width >= 3840) fps == 30 else fps == 60 }
        val fallback = VideoExportPlan.choices(1920, 1080, caps = thirty, largeHeapBytes = heap256)
        assertEquals(VideoExportPlan.Refusal.MEMORY, fallback[3].refusal)
        assertEquals(30, fallback[3].fps)
    }

    @Test
    fun defaultIs1080WhenSupported() {
        val choices = VideoExportPlan.choices(1920, 1080, caps = FakeCaps())
        assertEquals(1920, VideoExportPlan.defaultLongSide(choices))
        assertEquals(1920, VideoExportPlan.defaultChoice(choices)?.plan?.width)
        assertEquals(1080, VideoExportPlan.defaultChoice(choices)?.plan?.height)
        assertEquals(16_000_000, VideoExportPlan.defaultChoice(choices)?.plan?.bitRate)
    }

    @Test
    fun sizeRefusalIsReportedBeforeTheEncoderDecision() {
        val choices = VideoExportPlan.choices(100, 1, caps = FakeCaps())
        assertTrue(choices.all { it.refusal == VideoExportPlan.Refusal.SIZE })
        assertNull(VideoExportPlan.defaultLongSide(choices))
    }

    @Test
    fun sixtyFpsFailureLeavesTheFourKThirtyFallback() {
        val choices = VideoExportPlan.choices(1920, 1080, caps = FakeCaps { _, _, fps -> fps == 30 })
        assertTrue(choices.take(3).all { it.refusal == VideoExportPlan.Refusal.ENCODER })
        assertTrue(choices[3].supported)
        assertEquals(30, choices[3].fps)
    }

    @Test
    fun frameMathUsesTheTickClock() {
        assertEquals(300, VideoExportPlan.frameCount(300))
        assertEquals(301, VideoExportPlan.frameCount(301))
        assertEquals(150, VideoExportPlan.frameCount(300, fps = 30))
        assertEquals(151, VideoExportPlan.frameCount(301, fps = 30))
        assertEquals(0, VideoExportPlan.frameCount(0))
        assertEquals(300, VideoExportPlan.tickAtFrame(150, fps = 30))
        assertEquals(120, VideoExportPlan.tickAtFrame(60, fps = 30))
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
