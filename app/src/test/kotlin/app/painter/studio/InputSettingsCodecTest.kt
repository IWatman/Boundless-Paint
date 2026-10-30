package app.painter.studio

import app.painter.core.stroke.Curve
import app.painter.core.stroke.InputCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputSettingsCodecTest {

    @Test
    fun roundTripKeepsEveryDevice() {
        val pen = InputCalibration()
        repeat(5) { pen.observeStrokePeak(0.78f) }
        pen.pressureCurve = Curve(listOf(0f to 0f, 0.5f to 0.3f, 1f to 1f))
        pen.weakThreshold = 0.02f
        val finger = InputCalibration()
        finger.setManual(0.1f, 0.6f)

        val decoded = InputSettingsCodec.decode(InputSettingsCodec.encode(mapOf("xp-pen" to pen, "default" to finger)))

        assertEquals(setOf("xp-pen", "default"), decoded.keys)
        assertEquals(pen.snapshot(), decoded.getValue("xp-pen").snapshot())
        assertEquals(pen.pressure(0.78f), decoded.getValue("xp-pen").pressure(0.78f))
        assertEquals(finger.snapshot(), decoded.getValue("default").snapshot())
    }

    @Test
    fun damagedEntriesFallBackToDefaults() {
        assertTrue(InputSettingsCodec.decode("not json").isEmpty())
        assertTrue(InputSettingsCodec.decode("""{"devices":42}""").isEmpty())

        val devices = InputSettingsCodec.decode(
            """{"devices":{"pen":{"maxPressure":"broken","minPressure":0,"auto":false,"peaks":[0.5,"x"],"pressureCurve":"nope"}}}""",
        )
        val pen = devices.getValue("pen")
        val s = pen.snapshot()
        assertEquals(1f, s.maxPressure)
        assertEquals(0f, s.minPressure)
        assertEquals(false, s.auto)
        assertEquals(listOf(0.5f), s.peaks)
        assertEquals(Curve.LINEAR, s.pressureCurve)
    }

    @Test
    fun emptyFileEncodesNoDevices() {
        val text = InputSettingsCodec.encode(emptyMap())
        assertTrue(InputSettingsCodec.decode(text).isEmpty())
    }
}
