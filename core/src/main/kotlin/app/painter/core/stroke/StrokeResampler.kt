package app.painter.core.stroke

import kotlin.math.max

/** Receives dabs from [StrokeResampler]; called once per dab, no allocation. */
fun interface DabSink {
    /** One dab at ([x],[y]) with diameter [size] and per-dab [flow]. */
    fun dab(x: Float, y: Float, size: Float, flow: Float, pressure: Float, tilt: Float)
}

/**
 * M1 resampler: round dabs spaced by `max(0.5, spacing·size)` along a [PathWalker] path, size from
 * [BrushParams.sizeAt] × taper, flow from [BrushParams.flowAt]. The M2 engine uses [PathWalker] and
 * [DabGenerator] directly.
 */
class StrokeResampler(private val params: BrushParams, private val sink: DabSink) {
    private var dabCount = 0

    private val walker = PathWalker(params, PathSink { s ->
        val size = params.sizeAt(s.pressure) * s.taper
        dabCount++
        sink.dab(s.x, s.y, size, params.flowAt(s.pressure), s.pressure, s.tilt)
        max(MIN_STEP, params.spacing * size)
    }, holdBack = params.taperEnd)

    /** Number of dabs emitted in the current stroke. */
    val emittedDabs: Int get() = dabCount

    /** Starts a new stroke at [p]. */
    fun begin(p: InputPoint) {
        dabCount = 0
        walker.begin(p)
    }

    /** Appends a smoothed point; emits all dabs that are now final. */
    fun add(p: InputPoint) = walker.add(p)

    /** Closes the path, applies the end taper and emits the remaining dabs. */
    fun end() = walker.end()

    private companion object {
        const val MIN_STEP = 0.5f
    }
}
