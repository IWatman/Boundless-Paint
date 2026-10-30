package app.painter.core.stroke

import app.painter.core.raster.MaskPatch
import app.painter.core.raster.Tile
import app.painter.core.raster.TileKey
import app.painter.core.raster.TilePatch

/** What the stroke does to the target (spec/tools/m2-brush.md §1.19). */
enum class StrokeMode {
    PAINT,

    /** Smudge without depositing colour. Kernel arrives in S4: the engine rejects it for now. */
    BLEND,

    /** Destination-out. */
    ERASE,

    /** Copies pixels from a [CloneSource] through a begin-of-stroke snapshot (spec/tools/m2-brush.md §1.18). */
    CLONE,
}

/** Clone-mode source (S5). [layerId] null = active layer. */
data class CloneSource(
    val layerId: String? = null,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val aligned: Boolean = true,
    val allVisible: Boolean = false,
    val artistic: Boolean = false,
)

/**
 * Per-stroke environment of the brush engine.
 *
 * @property zoom screen pixels per document pixel (velocity and lazy radius are in screen units).
 * @property viewRotation view rotation, radians (added to the dab angle with `orientScreen`).
 * @property seed random seed; see [HashRng.strokeSeed].
 * @property lowerComposite composite of the layers below the target, per tile (S4 sample-lower).
 * @property paperTexture paper grain for watercolor (S8).
 * @property clone clone source (S5).
 * @property threads worker threads for tile-parallel rendering; results do not depend on it.
 * @property calibration global pressure calibration applied to stylus pressure before brush curves.
 * @property pressureEnabled global toggle; off replaces pressure by 1.
 * @property velocityEnabled global toggle; off replaces velocity by 0.
 * @property tiltEnabled global toggle; off replaces tilt (and the tilt-derived azimuth use) by 0.
 */
data class StrokeContext(
    val zoom: Float = 1f,
    val viewRotation: Float = 0f,
    val seed: Long = 0L,
    val lowerComposite: ((TileKey) -> Tile?)? = null,
    val paperTexture: TextureImage? = null,
    val clone: CloneSource? = null,
    val threads: Int = defaultThreads(),
    val calibration: InputCalibration? = null,
    val pressureEnabled: Boolean = true,
    val velocityEnabled: Boolean = true,
    val tiltEnabled: Boolean = true,
) {
    companion object {
        /** `clamp(cores − 2, 1, 4)` (spec/tools/m2-brush.md §0). */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
    }
}

/** Result of [StrokeEngine.end]; already applied to the document. */
sealed interface StrokeCommit {
    /** Layer pixels changed. */
    data class Pixels(val patch: TilePatch) : StrokeCommit

    /** A layer mask or the selection changed. */
    data class Mask(val patch: MaskPatch) : StrokeCommit
}
