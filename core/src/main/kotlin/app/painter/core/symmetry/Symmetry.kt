package app.painter.core.symmetry

import app.painter.core.geom.Affine2
import app.painter.core.geom.Vec2
import app.painter.core.stroke.SymmetryCopies
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor

enum class SymmetryType { MIRROR, RADIAL, KALEIDO }

/** Mirror axes: [V] = the axis at [SymmetryConfig.angleDeg], [H] = perpendicular to it, [VH] = both. */
enum class MirrorAxes { V, H, VH }

/**
 * Symmetry settings (spec G4). [angleDeg] is the main axis angle (90 = vertical); for Radial it only
 * orients the overlay. [planes] = N, [fanDeg] = Radial sector, [crop] = crop on axis (Mirror, Kaleido).
 */
data class SymmetryConfig(
    val on: Boolean = false,
    val type: SymmetryType = SymmetryType.MIRROR,
    val center: Vec2 = Vec2(0f, 0f),
    val angleDeg: Float = 90f,
    val axes: MirrorAxes = MirrorAxes.V,
    val planes: Int = DEFAULT_PLANES,
    val fanDeg: Float = 360f,
    val crop: Boolean = false,
    val locked: Boolean = false,
) {
    /** N clamped to the type's range (Radial 2–32, Kaleido 2–16). */
    val effectivePlanes: Int
        get() = when (type) {
            SymmetryType.RADIAL -> planes.coerceIn(2, MAX_COPIES)
            SymmetryType.KALEIDO -> planes.coerceIn(2, MAX_COPIES / 2)
            SymmetryType.MIRROR -> 1
        }

    /** Fan clamped to 10–360°. */
    val effectiveFanDeg: Float get() = fanDeg.coerceIn(10f, 360f)

    companion object {
        const val DEFAULT_PLANES = 6

        /** Total copies including the original never exceed this (spec G4). */
        const val MAX_COPIES = 32

        /** Default config centred on a [width]×[height] canvas. */
        fun centred(width: Int, height: Int) = SymmetryConfig(center = Vec2(width / 2f, height / 2f))
    }
}

/** Builds dab-level symmetry transforms (spec G4). */
object SymmetryMath {

    /** Copies (identity excluded) and the crop domain for a stroke starting at [strokeStart]. */
    fun copies(cfg: SymmetryConfig, strokeStart: Vec2): SymmetryCopies {
        if (!cfg.on) return SymmetryCopies.NONE
        val transforms = transforms(cfg, strokeStart)
        val domain = if (cfg.crop && cfg.type != SymmetryType.RADIAL) cropDomain(cfg, strokeStart) else null
        return SymmetryCopies(transforms, domain)
    }

    /** All transforms except identity, in copy order. */
    fun transforms(cfg: SymmetryConfig, strokeStart: Vec2): List<Affine2> {
        val c = cfg.center
        val theta = rad(cfg.angleDeg)
        return when (cfg.type) {
            SymmetryType.MIRROR -> when (cfg.axes) {
                MirrorAxes.V -> listOf(Affine2.reflection(c, theta))
                MirrorAxes.H -> listOf(Affine2.reflection(c, theta - HALF_PI))
                MirrorAxes.VH -> listOf(
                    Affine2.reflection(c, theta),
                    Affine2.reflection(c, theta + HALF_PI),
                    Affine2.rotation(c, PI.toFloat()),
                )
            }
            SymmetryType.RADIAL -> {
                val n = cfg.effectivePlanes
                val fan = cfg.effectiveFanDeg
                val step = if (fan >= 360f) 360f / n else fan / (n - 1)
                (1 until n).map { k -> Affine2.rotation(c, rad(k * step)) }
            }
            SymmetryType.KALEIDO -> {
                val n = cfg.effectivePlanes
                val refl = Affine2.reflection(c, theta)
                val out = ArrayList<Affine2>(2 * n - 1)
                for (k in 0 until n) {
                    val rot = Affine2.rotation(c, rad(k * 360f / n))
                    if (k > 0) out.add(rot)
                    out.add(refl.then(rot))
                }
                out
            }
        }
    }

    /**
     * Fundamental region containing [strokeStart]: the wedge of `180/m` degrees bounded by mirror lines,
     * where m = 1 (single mirror), 2 (VH) or N (Kaleido). Points on the centre are inside.
     */
    fun cropDomain(cfg: SymmetryConfig, strokeStart: Vec2): (Float, Float) -> Boolean {
        val m = when (cfg.type) {
            SymmetryType.MIRROR -> if (cfg.axes == MirrorAxes.VH) 2 else 1
            SymmetryType.KALEIDO -> cfg.effectivePlanes
            SymmetryType.RADIAL -> return { _, _ -> true }
        }
        val axis = when {
            cfg.type == SymmetryType.MIRROR && cfg.axes == MirrorAxes.H -> rad(cfg.angleDeg) - HALF_PI
            else -> rad(cfg.angleDeg)
        }
        val wedge = PI / m
        val cx = cfg.center.x; val cy = cfg.center.y
        fun index(x: Float, y: Float): Int {
            val rel = atan2((y - cy).toDouble(), (x - cx).toDouble()) - axis
            val norm = ((rel % (2 * PI)) + 2 * PI) % (2 * PI)
            return floor(norm / wedge).toInt() % (2 * m)
        }
        val home = index(strokeStart.x, strokeStart.y)
        return { x, y ->
            val dx = x - cx; val dy = y - cy
            dx * dx + dy * dy < 1e-6f || index(x, y) == home
        }
    }

    private const val HALF_PI = (PI / 2).toFloat()
    private fun rad(deg: Float) = (deg * PI / 180.0).toFloat()
}
