package app.painter.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pressure calibration wizard (spec/tools/m2-brush.md §4.3): three light presses, then three hard ones. */
class CalibrationWizardTest {

    private val wizard = CalibrationWizard()

    /** Feeds three presses and returns the outcome of the third one. */
    private fun press(peaks: FloatArray): CalibrationWizard.Outcome {
        var outcome: CalibrationWizard.Outcome = CalibrationWizard.Outcome.Collecting
        for (p in peaks) outcome = wizard.feed(p)
        return outcome
    }

    @Test
    fun lightPressesSwitchToTheHardStepAfterThree() {
        assertEquals(CalibrationWizard.Step.LIGHT, wizard.step)
        assertEquals(CalibrationWizard.Outcome.Collecting, wizard.feed(0.2f))
        assertEquals(1, wizard.presses)
        assertEquals(CalibrationWizard.Outcome.Collecting, wizard.feed(0.21f))
        assertEquals(2, wizard.presses)
        assertEquals(CalibrationWizard.Outcome.Collecting, wizard.feed(0.19f))
        assertEquals(CalibrationWizard.Step.HARD, wizard.step)
        assertEquals(0, wizard.presses)
    }

    @Test
    fun rangeStartsJustBelowTheSoftestLightPress() {
        press(floatArrayOf(0.2f, 0.25f, 0.15f))
        val outcome = press(floatArrayOf(0.6f, 0.7f, 0.8f))

        val ready = outcome as CalibrationWizard.Outcome.Ready
        assertEquals(0.12f, ready.min, 1e-4f) // 0.8 × 0.15
        assertEquals(0.7f, ready.max, 1e-4f) // mean of the hard peaks
    }

    @Test
    fun bottomOfTheRangeIsCapped() {
        press(floatArrayOf(0.9f, 0.85f, 0.8f))
        val ready = press(floatArrayOf(0.95f, 1f, 1f)) as CalibrationWizard.Outcome.Ready

        assertEquals(0.3f, ready.min, 1e-4f)
        assertEquals(0.98333f, ready.max, 1e-4f)
    }

    @Test
    fun topOfTheRangeNeverDropsBelowItsFloor() {
        press(floatArrayOf(0.1f, 0.1f, 0.1f))
        val ready = press(floatArrayOf(0.1f, 0.1f, 0.1f)) as CalibrationWizard.Outcome.Ready

        assertEquals(0.08f, ready.min, 1e-4f)
        assertEquals(0.4f, ready.max, 1e-4f)
        assertTrue(ready.max > ready.min + 0.2f)
    }

    @Test
    fun samePressInBothStepsIsRejectedAndRestarts() {
        press(floatArrayOf(0.5f, 0.5f, 0.5f))
        val outcome = press(floatArrayOf(0.5f, 0.5f, 0.5f))

        assertEquals(CalibrationWizard.Outcome.Rejected, outcome)
        // The wizard is ready for a new run: the pad shows the light step again.
        assertEquals(CalibrationWizard.Step.LIGHT, wizard.step)
        assertEquals(0, wizard.presses)
    }

    @Test
    fun aFinishedRunRestartsTheWizard() {
        press(floatArrayOf(0.2f, 0.2f, 0.2f))
        press(floatArrayOf(0.7f, 0.7f, 0.7f))

        assertEquals(CalibrationWizard.Step.LIGHT, wizard.step)
        assertEquals(0, wizard.presses)
        assertEquals(CalibrationWizard.Outcome.Collecting, wizard.feed(0.2f))
        assertEquals(1, wizard.presses)
    }

    @Test
    fun peaksOutsideZeroToOneAreClamped() {
        press(floatArrayOf(2f, 2f, 2f))
        val ready = press(floatArrayOf(3f, 3f, 3f)) as CalibrationWizard.Outcome.Ready

        assertEquals(0.3f, ready.min, 1e-4f)
        assertEquals(1f, ready.max, 1e-4f)
    }
}
