package app.painter.core.stroke

import app.painter.core.geom.Affine2
import app.painter.core.raster.MaskGrid

/**
 * Shared stroke-pipeline contracts (spec/PLAN-M2.md §2). Point stages sit between the stabilizer and the
 * resampler (lazy rope, snapping to guides, hatching); symmetry copies are applied to dabs.
 */

/** Output of a point stage. [lift] ends the current sub-stroke; the next [point] starts a new one. */
interface PointSink {
    fun point(p: InputPoint)
    fun lift()
}

interface PointStage {
    fun begin(p: InputPoint, out: PointSink)
    fun add(p: InputPoint, out: PointSink)
    fun end(out: PointSink)
    fun cancel() {}
}

enum class Interpolation { CATMULL_ROM, LINEAR }

/**
 * Dab-level symmetry: every dab is also drawn through each of [transforms] (identity is implicit and not
 * listed). [domain], when set, drops copies whose dab centre (in source space) is outside the start region.
 */
class SymmetryCopies(val transforms: List<Affine2>, val domain: ((Float, Float) -> Boolean)? = null) {
    companion object {
        val NONE = SymmetryCopies(emptyList())
    }
}

/** Closed-shape fill painted together with the outline (spec/tools/m2-guides-shapes.md G3.3). */
data class FillSpec(val opacity: Float = 1f)

/** Immutable per-stroke options. Defaults reproduce M1 behaviour. */
data class StrokeOptions(
    val stabilize: Boolean = true,
    /** Run after the stabilizer, in order. Stages are stateful: create new instances per stroke. */
    val stages: List<PointStage> = emptyList(),
    val interpolation: Interpolation = Interpolation.CATMULL_ROM,
    /** Closed shape: no taper, no double dab at the seam. */
    val closed: Boolean = false,
    val copies: SymmetryCopies = SymmetryCopies.NONE,
    val fill: FillSpec? = null,
    /** Selection coverage limiting the stroke (null = whole canvas). */
    val clip: MaskGrid? = null,
)

/** What a stroke edits. */
sealed interface StrokeTarget {
    /** Pixels of a raster layer. */
    data class Layer(val id: String) : StrokeTarget
    /** Grayscale mask of a node: stroke coverage × colour luminance writes the mask value. */
    data class Mask(val nodeId: String) : StrokeTarget
    /** The document selection (selection brush / eraser). */
    data object Selection : StrokeTarget
}
