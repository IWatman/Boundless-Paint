package app.painter.core.vector

import app.painter.core.color.Rgba
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.InputPoint
import app.painter.core.stroke.StrokeMode
import app.painter.core.symmetry.SymmetryConfig

/**
 * One sample of a [VectorStroke] in document pixels. [pressure] is already calibrated (the stroke engine
 * applies device calibration before recording), so replaying the path needs no calibration state.
 */
data class VectorPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val tilt: Float = 0f,
    val azimuth: Float = 0f,
    val twist: Float = 0f,
    val timeMs: Long = 0L,
) {
    /** Converts the recorded sample back to the stroke engine input. It is a stylus sample with no calibration. */
    fun toInputPoint(): InputPoint = InputPoint(
        x = x,
        y = y,
        pressure = pressure.coerceIn(0f, 1f),
        tilt = tilt,
        azimuth = azimuth,
        timeMs = timeMs,
        isStylus = true,
        twist = twist,
    )

    companion object {
        /** Records a post-stage engine point (pressure already calibrated, see [toInputPoint]). */
        fun from(p: InputPoint): VectorPoint =
            VectorPoint(p.x, p.y, p.pressure, p.tilt, p.azimuth, p.twist, p.timeMs)
    }
}

/**
 * One editable brush stroke of a [app.painter.core.doc.VectorLayer]: the recorded path plus everything that
 * the replay with [app.painter.core.stroke.StrokeEngine] needs. Sub-strokes produced by a lift are flattened
 * into [points]; ordinary brush strokes have exactly one sub-stroke.
 *
 * [zoom] and [viewRotation] are the per-stroke view values, [seed] is the deterministic random seed, and
 * [symmetry] is the symmetry snapshot taken at stroke start. [mode] is [StrokeMode.PAINT] or
 * [StrokeMode.ERASE]; other modes can change pixels in ways the vector path cannot reproduce.
 */
data class VectorStroke(
    val points: List<VectorPoint>,
    val brush: BrushParams = BrushParams(),
    val color: Rgba = Rgba.BLACK,
    val mode: StrokeMode = StrokeMode.PAINT,
    val seed: Long = 0L,
    val zoom: Float = 1f,
    val viewRotation: Float = 0f,
    val symmetry: SymmetryConfig? = null,
)
