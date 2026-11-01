package app.painter.core.video

import kotlin.test.Test
import kotlin.test.assertEquals

class YuvTest {

    @Test
    fun lumaUsesLimitedRangeBt709() {
        assertEquals(16, Yuv.luma(0, 0, 0))
        assertEquals(235, Yuv.luma(255, 255, 255))
        assertEquals(63, Yuv.luma(255, 0, 0))
        assertEquals(172, Yuv.luma(0, 255, 0))
        assertEquals(32, Yuv.luma(0, 0, 255))
    }

    @Test
    fun chromaUsesBt709Primaries() {
        assertEquals(128, Yuv.chromaU(0, 0, 0), "black is neutral")
        assertEquals(128, Yuv.chromaU(255, 255, 255), "white is neutral")
        assertEquals(128, Yuv.chromaU(128, 128, 128), "grey is neutral")
        assertEquals(240, Yuv.chromaU(0, 0, 255), "blue is the positive Cb extreme")
        assertEquals(16, Yuv.chromaU(255, 255, 0), "yellow is the negative Cb extreme")
        assertEquals(102, Yuv.chromaU(255, 0, 0))
    }

    @Test
    fun chromaVUsesBt709Primaries() {
        assertEquals(128, Yuv.chromaV(0, 0, 0))
        assertEquals(128, Yuv.chromaV(255, 255, 255))
        assertEquals(240, Yuv.chromaV(255, 0, 0), "red is the positive Cr extreme")
        assertEquals(16, Yuv.chromaV(0, 255, 255), "cyan is the negative Cr extreme")
        assertEquals(118, Yuv.chromaV(0, 0, 255))
    }

    @Test
    fun allInputsStayInsideTheLimitedRange() {
        val values = intArrayOf(0, 1, 63, 127, 128, 192, 254, 255)
        for (r in values) for (g in values) for (b in values) {
            val y = Yuv.luma(r, g, b)
            val u = Yuv.chromaU(r, g, b)
            val v = Yuv.chromaV(r, g, b)
            assertEquals(true, y in Yuv.Y_MIN..Yuv.Y_MAX, "Y($r,$g,$b) = $y")
            assertEquals(true, u in Yuv.CHROMA_MIN..Yuv.CHROMA_MAX, "U($r,$g,$b) = $u")
            assertEquals(true, v in Yuv.CHROMA_MIN..Yuv.CHROMA_MAX, "V($r,$g,$b) = $v")
        }
    }

    @Test
    fun frameUsesRowStridesAndLeavesPaddingAlone() {
        val frame = Yuv.I420Frame(6, 4, yRowStride = 10, uvRowStride = 5)

        frame.setLuma(5, 3, 235)
        frame.setChroma(2, 1, 240, 16)

        assertEquals(235, frame.y[3 * 10 + 5].toInt() and 0xFF)
        assertEquals(0, frame.y[3 * 10 + 6].toInt() and 0xFF, "row padding stays untouched")
        assertEquals(4 * 10, frame.y.size)
        assertEquals(240, frame.u[1 * 5 + 2].toInt() and 0xFF)
        assertEquals(16, frame.v[1 * 5 + 2].toInt() and 0xFF)
        assertEquals(0, frame.u[1 * 5 + 3].toInt() and 0xFF, "chroma padding stays untouched")
        assertEquals(2 * 5, frame.u.size)
        assertEquals(2 * 5, frame.v.size)
    }

    @Test
    fun setRgbWritesChromaOnBlockBoundaries() {
        val frame = Yuv.I420Frame(2, 2)
        frame.setRgb(0, 0, 255, 0, 0)
        frame.setRgb(1, 0, 255, 0, 0)
        frame.setRgb(0, 1, 255, 0, 0)
        frame.setRgb(1, 1, 255, 0, 0)

        assertEquals(63, frame.y[0].toInt() and 0xFF)
        assertEquals(102, frame.u[0].toInt() and 0xFF)
        assertEquals(240, frame.v[0].toInt() and 0xFF)
    }

    @Test
    fun compactI420KeepsLumaThenUThenV() {
        val frame = Yuv.I420Frame(2, 2)
        frame.setRgb(0, 0, 0, 0, 0)
        frame.setRgb(1, 0, 255, 255, 255)
        frame.setRgb(0, 1, 255, 255, 255)
        frame.setRgb(1, 1, 0, 0, 0)

        val bytes = frame.toI420()

        assertEquals(6, bytes.size)
        assertEquals(16, bytes[0].toInt() and 0xFF)
        assertEquals(235, bytes[1].toInt() and 0xFF)
        assertEquals(235, bytes[2].toInt() and 0xFF)
        assertEquals(16, bytes[3].toInt() and 0xFF)
        assertEquals(128, bytes[4].toInt() and 0xFF)
        assertEquals(128, bytes[5].toInt() and 0xFF)
    }

    @Test
    fun oddDimensionsUseCeilChromaPlanes() {
        val frame = Yuv.I420Frame(3, 3)
        assertEquals(2, frame.chromaWidth)
        assertEquals(2, frame.chromaHeight)
        assertEquals(3 * 3 + 2 * 2 * 2, frame.toI420().size)

        frame.setChroma(1, 1, 200, 60)
        val bytes = frame.toI420()
        assertEquals(200, bytes[9 + 2 + 1].toInt() and 0xFF)
        assertEquals(60, bytes[9 + 4 + 2 + 1].toInt() and 0xFF)
    }
}
