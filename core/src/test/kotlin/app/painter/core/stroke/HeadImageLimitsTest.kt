package app.painter.core.stroke

import app.painter.core.stroke.StrokeTestKit.gray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadImageLimitsTest {
    private fun hugeDisk() = HeadImage(
        gray(4096, 4096) { x, y ->
            if ((x - 2048) * (x - 2048) + (y - 2048) * (y - 2048) < 1500 * 1500) 1f else 0f
        },
    )

    @Test
    fun hugeSourceIsLimitedTo1024px() {
        val head = hugeDisk()
        assertTrue(head.width <= 1024 && head.height <= 1024, "${head.width}x${head.height}")
        assertEquals(1024, head.width)
        assertEquals(1024, head.height)
        assertEquals(11, head.sharp.levelCount)
        assertEquals(1f, head.sample(0, 0.5f, 0.5f))
        assertEquals(0f, head.sample(0, 0.01f, 0.01f))
    }

    @Test
    fun softPyramidOfHugeSourceKeepsTheSameMipChain() {
        val head = hugeDisk()
        val soft = head.pyramid(0f)
        assertEquals(head.sharp.levelCount, soft.levelCount)
        assertTrue(soft.sample(0, 0.5f, 0.5f) > 0.9f, "soft centre ${soft.sample(0, 0.5f, 0.5f)}")
    }
}
