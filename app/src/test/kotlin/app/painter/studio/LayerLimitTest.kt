package app.painter.studio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Layer limit of spec/GAPS.md with the GPU term of debt 6. */
class LayerLimitTest {

    private val heap512 = 512L * 1024 * 1024 * 4 / 10 // 40% of a 512 MB heap

    @Test
    fun gpuBudgetCapsLargeCanvases() {
        // 2560×1432: 14.7 MB per layer at 50% fill -> 14 by the heap, 13 by the 192 MB GPU allowance.
        assertEquals(14, (heap512 / (2560L * 1432 * 4)).toInt())
        assertEquals(13, LayerLimit.maxLayers(2560, 1432, heap512))
    }

    @Test
    fun smallCanvasesHitTheHardCap() {
        assertEquals(999, LayerLimit.maxLayers(64, 64, heap512))
    }

    @Test
    fun hugeCanvasesStillAllowOneLayer() {
        assertEquals(1, LayerLimit.maxLayers(20000, 20000, heap512))
    }

    @Test
    fun degenerateSizesGiveOne() {
        assertEquals(1, LayerLimit.maxLayers(0, 100, heap512))
        assertEquals(1, LayerLimit.maxLayers(100, -5, heap512))
    }

    @Test
    fun tighterGpuBudgetWins() {
        assertEquals(1, LayerLimit.maxLayers(1024, 1024, heap512, gpuBudget = 4L * 1024 * 1024))
    }

    @Test
    fun tighterHeapWins() {
        // 8 MB heap fits two layers of 4 MB at 50% fill, fewer than the GPU allowance would allow.
        assertEquals(2, LayerLimit.maxLayers(1024, 1024, heapBudget = 8L * 1024 * 1024))
    }

    @Test
    fun videoPresetSizesDeriveTheExpectedLayerLimits() {
        // The export dialog shows these numbers for a 512 MB large heap: 6 layers at 4K, 24 at 1080p.
        assertEquals(6, LayerLimit.maxLayers(3840, 2160, heap512))
        assertEquals(24, LayerLimit.maxLayers(1920, 1080, heap512))
        val heap256 = 256L * 1024 * 1024 * 4 / 10
        assertEquals(3, LayerLimit.maxLayers(3840, 2160, heap256))
    }
}
