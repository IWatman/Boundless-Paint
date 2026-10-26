package app.painter.core.perspective

import app.painter.core.geom.Vec2
import app.painter.core.guide.DocRect
import app.painter.core.guide.OverlayGeom
import app.painter.core.guide.OverlayLine
import app.painter.core.guide.OverlayRole
import app.painter.core.perspective.PerspectiveGuide.Companion.HALF_PI
import app.painter.core.perspective.PerspectiveGuide.Companion.rad
import app.painter.core.perspective.PerspectiveGuide.Companion.unit
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Overlay geometry of a perspective grid (spec G5), document px clipped to the visible area. */
object PerspectiveOverlay {
    /** Rays drawn per vanishing point. */
    const val RAYS_PER_VP = 24

    /** Spacing of 2pt verticals along the horizon, document px. */
    const val VERTICAL_SPACING = 64f

    private val VP_ROLES = arrayOf(OverlayRole.VP1, OverlayRole.VP2, OverlayRole.VP3)

    /** Lines of [cfg] visible in [view]; empty when the grid is off or hidden. */
    fun lines(cfg: PerspectiveConfig, view: DocRect, screenToDoc: Float): List<OverlayLine> {
        val out = ArrayList<OverlayLine>()
        if (!cfg.on || !cfg.visible) return out
        val h = rad(cfg.horizonDeg)
        when (cfg.type) {
            PerspectiveType.ONE_POINT -> {
                cfg.vp.getOrNull(0)?.let { vp ->
                    rays(vp, view, OverlayRole.VP1, out)
                    OverlayGeom.clip(vp, unit(h), view)?.let { out.add(OverlayLine(it.first, it.second, OverlayRole.HORIZON)) }
                }
            }
            PerspectiveType.TWO_POINT, PerspectiveType.THREE_POINT -> {
                val count = if (cfg.type == PerspectiveType.TWO_POINT) 2 else 3
                for (i in 0 until min(count, cfg.vp.size)) rays(cfg.vp[i], view, VP_ROLES[i], out)
                val hd = PerspectiveGuide.horizonDir(cfg)
                if (hd != null) {
                    OverlayGeom.clip(cfg.vp[0], hd, view)?.let { out.add(OverlayLine(it.first, it.second, OverlayRole.HORIZON)) }
                    if (cfg.type == PerspectiveType.TWO_POINT) {
                        val step = OverlayGeom.thin(VERTICAL_SPACING, screenToDoc)
                        OverlayGeom.family(cfg.vp[0], Vec2(-hd.y, hd.x), step, view, OverlayRole.VERTICAL, out)
                    }
                }
            }
            PerspectiveType.ISO -> {
                val step = OverlayGeom.thin(cfg.spacing * SQRT3_2, screenToDoc)
                for (deg in PerspectiveGuide.ISO_DEGREES) {
                    OverlayGeom.family(cfg.origin, unit(h + rad(deg)), step, view, OverlayRole.GRID, out)
                }
            }
            PerspectiveType.GRID_2D -> {
                val sx = OverlayGeom.thin(cfg.spacing, screenToDoc)
                val sy = OverlayGeom.thin(cfg.spacing * cfg.ratio, screenToDoc)
                OverlayGeom.family(cfg.origin, unit(h), sy, view, OverlayRole.GRID, out)
                OverlayGeom.family(cfg.origin, unit(h + HALF_PI), sx, view, OverlayRole.GRID, out)
            }
        }
        return out
    }

    /** [RAYS_PER_VP] rays from [vp] spread over the angular extent of [view] (full circle when inside). */
    fun rays(vp: Vec2, view: DocRect, role: OverlayRole, out: MutableList<OverlayLine>) {
        val start: Double
        val span: Double
        val closedFan: Boolean
        if (view.contains(vp)) {
            start = 0.0; span = 2 * PI; closedFan = true
        } else {
            val cx = (view.x0 + view.x1) / 2 - vp.x; val cy = (view.y0 + view.y1) / 2 - vp.y
            val mid = atan2(cy.toDouble(), cx.toDouble())
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (c in view.corners) {
                var a = atan2((c.y - vp.y).toDouble(), (c.x - vp.x).toDouble()) - mid
                while (a <= -PI) a += 2 * PI
                while (a > PI) a -= 2 * PI
                lo = min(lo, a); hi = max(hi, a)
            }
            start = mid + lo; span = hi - lo; closedFan = false
        }
        val n = RAYS_PER_VP
        for (i in 0 until n) {
            val a = if (closedFan) start + span * i / n else start + span * i / (n - 1)
            val d = Vec2(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
            OverlayGeom.clip(vp, d, view, 0f)?.let { out.add(OverlayLine(it.first, it.second, role)) }
        }
    }

    private val SQRT3_2 = (sqrt(3.0) / 2).toFloat()
}
