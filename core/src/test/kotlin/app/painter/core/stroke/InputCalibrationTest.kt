package app.painter.core.stroke

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputCalibrationTest {
    @Test
    fun snapshotRoundTripKeepsRangeAndPeaks() {
        // An XP-Pen peaking around 0.78 must reach the full range after a few strong strokes (spec §4.3).
        val cal = InputCalibration()
        repeat(5) { cal.observeStrokePeak(0.78f) }
        val restored = InputCalibration().apply { restore(cal.snapshot()) }
        assertEquals(cal.maxPressure, restored.maxPressure)
        assertEquals(cal.minPressure, restored.minPressure)
        assertEquals(cal.auto, restored.auto)
        assertEquals(cal.snapshot().peaks, restored.snapshot().peaks)
        assertEquals(cal.snapshot().pressureCurve, restored.snapshot().pressureCurve)
        // The calibration itself survives the round trip: raw 0.78 is a full-pressure dab again.
        assertEquals(cal.pressure(0.78f), restored.pressure(0.78f))
        assertTrue(restored.pressure(0.78f) > 0.95f)
    }

    @Test
    fun snapshotRoundTripKeepsManualRangeAndCurve() {
        val cal = InputCalibration()
        cal.setManual(0.1f, 0.7f)
        cal.pressureCurve = Curve(listOf(0f to 0f, 0.5f to 0.3f, 1f to 1f))
        cal.weakThreshold = 0.02f
        val restored = InputCalibration().apply { restore(cal.snapshot()) }
        assertEquals(0.1f, restored.minPressure)
        assertEquals(0.7f, restored.maxPressure)
        assertEquals(false, restored.auto)
        assertEquals(0.02f, restored.weakThreshold)
        assertEquals(cal.pressure(0.5f), restored.pressure(0.5f))
    }

    @Test
    fun restoreKeepsAutoBehaviour() {
        // Peaks restored from disk keep driving the automatic range the same way as before the restart.
        val cal = InputCalibration()
        repeat(5) { cal.observeStrokePeak(0.6f) }
        val restored = InputCalibration().apply { restore(cal.snapshot()) }
        cal.observeStrokePeak(0.9f)
        restored.observeStrokePeak(0.9f)
        assertEquals(cal.maxPressure, restored.maxPressure)
        // Manual calibration never follows the strokes.
        val manual = InputCalibration()
        manual.setManual(0f, 0.5f)
        val restoredManual = InputCalibration().apply { restore(manual.snapshot()) }
        restoredManual.observeStrokePeak(1f)
        assertEquals(0.5f, restoredManual.maxPressure)
    }

    @Test
    fun restoreClampsForeignValues() {
        // A hand-edited or corrupted input.json must not produce a broken calibration.
        val s = InputCalibration.Snapshot(
            maxPressure = 5f,
            minPressure = 3f,
            auto = true,
            peaks = listOf(-1f, 2f, 0.5f),
            weakThreshold = 2f,
            pressureCurve = Curve.LINEAR,
        )
        val cal = InputCalibration().apply { restore(s) }
        assertTrue(cal.maxPressure in 0f..1f)
        assertTrue(cal.minPressure in 0f..cal.maxPressure)
        assertEquals(1f, cal.weakThreshold)
        // Peaks keep their observation order, out-of-range values are clamped, non-positive ones dropped.
        assertEquals(listOf(1f, 0.5f), cal.snapshot().peaks)
    }
}
