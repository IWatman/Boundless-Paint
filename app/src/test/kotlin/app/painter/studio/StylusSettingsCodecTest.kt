package app.painter.studio

import app.painter.core.stroke.Curve
import app.painter.core.stroke.InputCalibration
import app.painter.render.PenAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `stylus` object of `files/settings/input.json` (spec/tools/m2-brush.md §4.3–4.4). */
class StylusSettingsCodecTest {

    @Test
    fun roundTripKeepsEverySetting() {
        val settings = StylusSettings(
            curvePreset = CurvePreset.FIRM,
            primaryAction = PenAction.ERASE,
            secondaryAction = PenAction.BRUSH_BROWSER,
            fingerWithStylus = FingerWithStylus.DRAW,
            palm = PalmRejection.HOVER_REJECT,
        )
        val pen = InputCalibration()
        settings.applyTo(pen)

        val decoded = InputSettingsCodec.decode(InputSettingsCodec.encode(InputSettings.Stored(mapOf("pen" to pen), settings)))

        assertEquals(settings, decoded.stylus)
        // The curve travels with the device, so a session starts from the same pressure response.
        assertEquals(settings.curve, decoded.devices.getValue("pen").snapshot().pressureCurve)
    }

    @Test
    fun missingStylusObjectReadsAsTheDefaults() {
        // A file of M2 part 1 holds the devices only; it must keep loading.
        val decoded = InputSettingsCodec.decode("""{"devices":{"pen":{"maxPressure":0.8,"auto":true}}}""")
        assertEquals(StylusSettings(), decoded.stylus)
        assertEquals(1, decoded.devices.size)
        assertEquals(PenAction.EYEDROPPER, decoded.stylus.primaryAction)
        assertEquals(PenAction.UNDO, decoded.stylus.secondaryAction)
        assertEquals(FingerWithStylus.NAVIGATE_ONLY, decoded.stylus.fingerWithStylus)
        assertEquals(PalmRejection.STYLUS_ONLY, decoded.stylus.palm)
        assertEquals(CurvePreset.NORMAL, decoded.stylus.curvePreset)
    }

    @Test
    fun unknownNamesFallBackToTheDefaults() {
        val decoded = InputSettingsCodec.decode(
            """{"stylus":{"curvePreset":"SHARP","primaryAction":"PAN","secondaryAction":42,"fingerWithStylus":"BLEND","palm":"ALWAYS"}}""",
        )
        assertEquals(StylusSettings(), decoded.stylus)
    }

    @Test
    fun curvePresetsFollowTheirGamma() {
        assertEquals(Curve.LINEAR, CurvePreset.NORMAL.curve())
        // Soft lifts light pressures, Firm holds them back; both stay within the calibrated range.
        assertTrue(CurvePreset.SOFT.curve().eval(0.5f) > 0.6f)
        assertTrue(CurvePreset.FIRM.curve().eval(0.5f) < 0.4f)
        for (preset in CurvePreset.values()) {
            assertEquals(0f, preset.curve().eval(0f))
            assertEquals(1f, preset.curve().eval(1f))
        }
    }
}
