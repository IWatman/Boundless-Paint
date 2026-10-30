package app.painter.studio

import app.painter.core.stroke.Curve
import app.painter.core.stroke.InputCalibration
import app.painter.render.PenAction
import kotlin.math.pow

/**
 * Stylus and input settings of the studio (spec/tools/m2-brush.md §4.3), stored in the `stylus` object of
 * `files/settings/input.json` next to the per-device calibration ([InputSettings]).
 *
 * Everything here is global to the app except [curvePreset]: the pressure curve corrects the pen, so the
 * preset is written into the [InputCalibration.pressureCurve] of every input device rather than kept beside
 * it. The defaults are the spec's defaults: Normal curve, primary button = eyedropper (hold), secondary =
 * undo (click), the finger only navigates once the pen was seen, palm = stylus only.
 */
data class StylusSettings(
    val curvePreset: CurvePreset = CurvePreset.NORMAL,
    val primaryAction: PenAction = PenAction.EYEDROPPER,
    val secondaryAction: PenAction = PenAction.UNDO,
    val fingerWithStylus: FingerWithStylus = FingerWithStylus.NAVIGATE_ONLY,
    val palm: PalmRejection = PalmRejection.STYLUS_ONLY,
) {
    /** Pressure curve of [curvePreset], see [CurvePreset.curve]. */
    val curve: Curve get() = curvePreset.curve()

    /** Gives [calibration] the curve of [curvePreset]; the range and the auto flag of the device stay. */
    fun applyTo(calibration: InputCalibration) {
        calibration.pressureCurve = curve
    }

    /** [applyTo] for every device the studio has seen. */
    fun applyTo(calibrations: Collection<InputCalibration>) = calibrations.forEach(::applyTo)
}

/**
 * Pressure curve preset on top of the calibrated pressure (spec/tools/m2-brush.md §4.3): Soft γ 0.6, Normal
 * (linear, default), Firm γ 1.6. A [Curve] is a point list, so the preset is sampled from y = x^γ at
 * [SAMPLES] points; the spline through those points follows the gamma.
 */
enum class CurvePreset(val gamma: Float) {
    /** γ 0.6: light pressure already gives a wide dab. */
    SOFT(0.6f),

    /** γ 1: the calibrated pressure as it is. */
    NORMAL(1f),

    /** γ 1.6: the stroke stays thin until the pen is pressed hard. */
    FIRM(1.6f),
    ;

    /** Curve of the preset; [NORMAL] is exactly [Curve.LINEAR]. */
    fun curve(): Curve =
        if (gamma == 1f) {
            Curve.LINEAR
        } else {
            Curve(
                List(SAMPLES) { i ->
                    val x = i / (SAMPLES - 1).toFloat()
                    x to x.pow(gamma)
                },
            )
        }

    private companion object {
        /** Points of the sampled curve: the more there are, the closer the spline is to y = x^γ. */
        const val SAMPLES = 9
    }
}

/**
 * What a finger does while a stylus is active (spec/tools/m2-brush.md §4.3 "Finger with an active stylus"),
 * as decided by [app.painter.render.FingerGate].
 */
enum class FingerWithStylus {
    /** The finger keeps drawing strokes. */
    DRAW,

    /** Default after the first pen touch: the finger never draws, it only pans, zooms and taps undo/redo. */
    NAVIGATE_ONLY,
}

/** Palm rejection (spec/tools/m2-brush.md §4.3), see [app.painter.render.FingerGate]. */
enum class PalmRejection {
    /** No rejection: the finger follows [StylusSettings.fingerWithStylus] alone. */
    OFF,

    /** Default: the finger does not draw once a pen was seen in the session ("stylus only"). */
    STYLUS_ONLY,

    /** [STYLUS_ONLY], and a finger does not draw while the pen hovers and [app.painter.render.FingerGate.HOVER_REJECT_MS] after. */
    HOVER_REJECT,
}
