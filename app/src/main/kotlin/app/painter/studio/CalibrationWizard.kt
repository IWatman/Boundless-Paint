package app.painter.studio

/**
 * Pressure calibration wizard of the stylus settings (spec/tools/m2-brush.md §4.3: "press 3 times lightly
 * and 3 times hard → p_min and p_max are fixed, auto is turned off").
 *
 * The wizard only counts presses: the pad of [app.painter.ui.studio.StylusSettingsPanel] turns one down…up of
 * the pen into its peak raw pressure and feeds it here. Three [Step.LIGHT] presses give the bottom of the
 * range ([LIGHT_SCALE] of the softest of them, so the pen starts drawing just above the lightest touch), three
 * [Step.HARD] presses give its top (their mean). A hard mean that is not clearly above the light one — the
 * user pressed the same way twice — is [Outcome.Rejected] and the wizard starts over. Pure Kotlin, so the
 * whole state machine is unit-tested on the JVM.
 */
class CalibrationWizard {

    /** Step of the wizard: the light presses measure the bottom of the range, the hard ones its top. */
    enum class Step { LIGHT, HARD }

    /** What the wizard says after a press. */
    sealed interface Outcome {
        /** Press taken; [step] and [presses] say what the pad asks for next. */
        data object Collecting : Outcome

        /** Range found: [min] and [max] go to `InputCalibration.setManual`. The wizard restarted. */
        data class Ready(val min: Float, val max: Float) : Outcome

        /** The hard presses were not clearly above the light ones; the wizard restarted. */
        data object Rejected : Outcome
    }

    /** Current step, [Step.LIGHT] until the third light press. */
    var step: Step = Step.LIGHT
        private set

    /** Presses taken in the current [step], 0..[PRESSES]. */
    var presses: Int = 0
        private set

    private val light = ArrayList<Float>(PRESSES)
    private val hard = ArrayList<Float>(PRESSES)

    /**
     * Feeds the peak raw pressure [peakRaw] of one finished press (down…up) and returns what to do next.
     * [Outcome.Ready] and [Outcome.Rejected] both restart the wizard, so a run can follow a run at once.
     */
    fun feed(peakRaw: Float): Outcome {
        val peak = peakRaw.coerceIn(0f, 1f)
        return when (step) {
            Step.LIGHT -> {
                light.add(peak)
                presses = light.size
                if (light.size < PRESSES) {
                    Outcome.Collecting
                } else {
                    step = Step.HARD
                    presses = 0
                    Outcome.Collecting
                }
            }
            Step.HARD -> {
                hard.add(peak)
                presses = hard.size
                if (hard.size < PRESSES) Outcome.Collecting else finish()
            }
        }
    }

    /** Drops the presses and starts again from the light step. */
    fun restart() {
        light.clear()
        hard.clear()
        step = Step.LIGHT
        presses = 0
    }

    /** Builds the range of the three light and three hard presses and restarts the wizard. */
    private fun finish(): Outcome {
        val min = (light.min() * LIGHT_SCALE).coerceIn(0f, MAX_MIN)
        val max = hard.average().toFloat().coerceIn(MIN_MAX, 1f)
        restart()
        return if (max > min + MIN_SPAN) Outcome.Ready(min, max) else Outcome.Rejected
    }

    private companion object {
        /** Presses per step: light, then hard (spec §4.3). */
        const val PRESSES = 3

        /** How far under the softest light press the range starts. */
        const val LIGHT_SCALE = 0.8f

        /** A light press may not push the bottom of the range above this: the pen would skip its soft end. */
        const val MAX_MIN = 0.3f

        /** The top of the range never drops below this, so a hard press still reaches a full dab. */
        const val MIN_MAX = 0.4f

        /** The range a wizard run must find: anything narrower means both steps were pressed the same way. */
        const val MIN_SPAN = 0.2f
    }
}
