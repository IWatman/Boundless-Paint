package app.painter.core.stroke

/**
 * One input sample in document pixel coordinates.
 * [pressure] 0..1 (1 for finger/mouse), [tilt] radians from vertical (0 = upright),
 * [azimuth] radians in document space, [timeMs] monotonic event time.
 */
data class InputPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val tilt: Float = 0f,
    val azimuth: Float = 0f,
    val timeMs: Long = 0L,
    val isStylus: Boolean = false,
    /** True for motion-predicted points that will be replaced by real ones. */
    val predicted: Boolean = false,
)
