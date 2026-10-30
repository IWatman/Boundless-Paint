package app.painter.studio

import app.painter.core.stroke.InputPoint
import kotlin.math.PI
import kotlin.math.sin

/**
 * Reference stroke of the brush editor preview pad (spec/tools/m2-brush.md §4.2): a left-to-right S-curve with a
 * rising and falling pressure envelope, the same shape the preset sheet and the browser previews use. The geometry
 * is pure pad pixels, so the UI renderer and a JVM test share one definition.
 */
object PreviewStroke {
    /** Horizontal margin of the curve from the pad edges, as a fraction of the width. */
    private const val MARGIN = 0.06f

    /** Vertical amplitude of the curve from its centre line, as a fraction of the height. */
    private const val AMPLITUDE = 0.28f

    /** Number of segments: smooth at the preview size and still cheap enough to re-render live. */
    private const val SEGMENTS = 120

    /** Pressure at both ends of the stroke; the middle reaches [PEAK_PRESSURE]. */
    private const val END_PRESSURE = 0.15f

    /** Pressure at the middle of the stroke. */
    private const val PEAK_PRESSURE = 1f

    /**
     * Points of the reference S-curve fitting a [width]×[height] pad: x grows from the left margin to the right
     * one, y swings once through the centre line, pressure follows a half sine from [END_PRESSURE] up to
     * [PEAK_PRESSURE] and back. Every sample is a stylus sample, so pressure-driven brushes show their dynamics.
     */
    fun points(width: Int, height: Int): List<InputPoint> {
        val w = width.toFloat()
        val h = height.toFloat()
        val margin = w * MARGIN
        val amplitude = h * AMPLITUDE
        val centre = h * 0.5f
        val points = ArrayList<InputPoint>(SEGMENTS + 1)
        var time = 0L
        for (i in 0..SEGMENTS) {
            val f = i.toFloat() / SEGMENTS
            val x = margin + (w - 2f * margin) * f
            val y = centre + amplitude * sin(2.0 * PI * f).toFloat()
            val pressure =
                (END_PRESSURE + (PEAK_PRESSURE - END_PRESSURE) * sin(PI * f).toFloat()).coerceIn(0.02f, 1f)
            points += InputPoint(x = x, y = y, pressure = pressure, timeMs = time, isStylus = true)
            time += (4 + i % 11).toLong()
        }
        return points
    }
}
