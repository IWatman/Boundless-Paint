package app.painter.core.perspective

import app.painter.core.geom.Vec2
import app.painter.core.guide.LineTrack
import app.painter.core.guide.SnapContext
import app.painter.core.guide.SnapGuide
import app.painter.core.guide.SnapPriority
import app.painter.core.guide.SnapTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

enum class PerspectiveType { ONE_POINT, TWO_POINT, THREE_POINT, ISO, GRID_2D }

/**
 * Perspective grid (spec G5). [vp] holds 1–3 vanishing points (may lie outside the canvas).
 * [horizonDeg] rotates the 1pt horizon and the Iso / 2D grids. [spacing] and [ratio] (cell height /
 * width) size Iso and 2D cells from [origin].
 */
data class PerspectiveConfig(
    val on: Boolean = false,
    val type: PerspectiveType = PerspectiveType.ONE_POINT,
    val vp: List<Vec2> = emptyList(),
    val horizonDeg: Float = 0f,
    val spacing: Float = DEFAULT_SPACING,
    val ratio: Float = 1f,
    val origin: Vec2 = Vec2(0f, 0f),
    val snap: Boolean = true,
    val visible: Boolean = true,
    val locked: Boolean = false,
) {
    companion object {
        const val DEFAULT_SPACING = 64f

        /** Spec G5 defaults of [type] for a [width]×[height] canvas (the grid is switched on). */
        fun defaults(type: PerspectiveType, width: Int, height: Int): PerspectiveConfig {
            val w = width.toFloat(); val h = height.toFloat()
            val vps = when (type) {
                PerspectiveType.ONE_POINT -> listOf(Vec2(w / 2, h / 2))
                PerspectiveType.TWO_POINT -> listOf(Vec2(-0.25f * w, 0.45f * h), Vec2(1.25f * w, 0.45f * h))
                PerspectiveType.THREE_POINT ->
                    listOf(Vec2(-0.25f * w, 0.45f * h), Vec2(1.25f * w, 0.45f * h), Vec2(0.5f * w, 2f * h))
                else -> emptyList()
            }
            return PerspectiveConfig(on = true, type = type, vp = vps)
        }
    }
}

/** Below this distance from a VP its line family is skipped, document px. */
const val VP_SKIP_DIST = 4f

/**
 * Perspective grid as a [SnapGuide]: direction families only, strokes never stick to grid lines
 * (spec G5). Produces nothing when the grid is off or [PerspectiveConfig.snap] is false.
 */
class PerspectiveGuide(val cfg: PerspectiveConfig) : SnapGuide {
    override fun tracks(p0: Vec2, ctx: SnapContext, out: MutableList<SnapTrack>) {
        if (!cfg.on || !cfg.snap) return
        for (d in directions(cfg, p0)) out.add(LineTrack(p0, d, SnapPriority.PERSPECTIVE))
    }

    companion object {
        /** Unit directions of every family through [p0]. */
        fun directions(cfg: PerspectiveConfig, p0: Vec2): List<Vec2> {
            val out = ArrayList<Vec2>(3)
            fun toVp(i: Int) {
                val v = cfg.vp.getOrNull(i) ?: return
                val dx = v.x - p0.x; val dy = v.y - p0.y
                val d = sqrt(dx * dx + dy * dy)
                if (d >= VP_SKIP_DIST) out.add(Vec2(dx / d, dy / d))
            }
            val h = rad(cfg.horizonDeg)
            when (cfg.type) {
                PerspectiveType.ONE_POINT -> {
                    toVp(0); out.add(unit(h)); out.add(unit(h + HALF_PI))
                }
                PerspectiveType.TWO_POINT -> {
                    toVp(0); toVp(1)
                    horizonDir(cfg)?.let { out.add(Vec2(-it.y, it.x)) }
                }
                PerspectiveType.THREE_POINT -> {
                    toVp(0); toVp(1); toVp(2)
                }
                PerspectiveType.ISO -> for (deg in ISO_DEGREES) out.add(unit(h + rad(deg)))
                PerspectiveType.GRID_2D -> {
                    out.add(unit(h)); out.add(unit(h + HALF_PI))
                }
            }
            return out
        }

        /** Unit direction VP1→VP2 (the 2pt / 3pt horizon), or null when undefined. */
        fun horizonDir(cfg: PerspectiveConfig): Vec2? {
            val a = cfg.vp.getOrNull(0) ?: return null
            val b = cfg.vp.getOrNull(1) ?: return null
            val dx = b.x - a.x; val dy = b.y - a.y
            val d = sqrt(dx * dx + dy * dy)
            return if (d < 1e-4f) null else Vec2(dx / d, dy / d)
        }

        /**
         * Nearest 2D-grid intersection within [radiusDoc] of [p], for snapping shape handles
         * (spec G3.1, 12 screen px). Null for other grid types or when none is close.
         */
        fun nearestGridNode(cfg: PerspectiveConfig, p: Vec2, radiusDoc: Float): Vec2? {
            if (!cfg.on || cfg.type != PerspectiveType.GRID_2D || cfg.spacing <= 0f) return null
            val h = rad(cfg.horizonDeg)
            val ux = unit(h); val uy = unit(h + HALF_PI)
            val sx = cfg.spacing; val sy = cfg.spacing * cfg.ratio
            if (sy <= 0f) return null
            val dx = p.x - cfg.origin.x; val dy = p.y - cfg.origin.y
            val i = round((dx * ux.x + dy * ux.y) / sx); val j = round((dx * uy.x + dy * uy.y) / sy)
            val q = Vec2(cfg.origin.x + ux.x * i * sx + uy.x * j * sy, cfg.origin.y + ux.y * i * sx + uy.y * j * sy)
            val ex = q.x - p.x; val ey = q.y - p.y
            return if (ex * ex + ey * ey <= radiusDoc * radiusDoc) q else null
        }

        internal val ISO_DEGREES = floatArrayOf(30f, 150f, 90f)
        internal const val HALF_PI = (PI / 2).toFloat()
        internal fun rad(deg: Float) = (deg * PI / 180.0).toFloat()
        internal fun unit(r: Float) = Vec2(cos(r), sin(r))
    }
}
