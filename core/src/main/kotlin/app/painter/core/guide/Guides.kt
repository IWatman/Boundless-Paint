package app.painter.core.guide

import app.painter.core.geom.Vec2
import app.painter.core.shape.ArcPolyline
import app.painter.core.shape.BezierPath
import app.painter.core.shape.dist
import app.painter.core.shape.len
import app.painter.core.shape.minus
import app.painter.core.shape.normalized
import app.painter.core.shape.toRad
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Magnet capture radius, screen px (spec G2.1). */
const val MAGNET_SCREEN_PX = 32f

/**
 * Ruler (spec G2.1). Free mode: a track parallel to the axis through p0, plus a perpendicular one when
 * [LinesConfig.perpendicular]. Magnet mode: a stroke started within 32 screen px of the axis gets only
 * the axis itself (shifted by brushSize/2 toward p0 when [LinesConfig.edge]); farther strokes are free.
 */
class LinesGuide(val cfg: LinesConfig) : SnapGuide {
    override fun tracks(p0: Vec2, ctx: SnapContext, out: MutableList<SnapTrack>) {
        val axis = cfg.b - cfg.a
        if (axis.len < 1e-4f) return
        val d = axis.normalized()
        if (cfg.magnet) {
            val sd = signedDist(p0, cfg.a, d)
            if (abs(sd) > MAGNET_SCREEN_PX * ctx.screenToDoc) return
            out.add(LineTrack(offsetLine(cfg.a, d, edgeOffset(sd, cfg.edge, ctx)), d, SnapPriority.LINES))
            return
        }
        out.add(LineTrack(p0, d, SnapPriority.LINES))
        if (cfg.perpendicular) out.add(LineTrack(p0, Vec2(-d.y, d.x), SnapPriority.LINES))
    }
}

internal fun edgeOffset(signedDistance: Float, edge: Boolean, ctx: SnapContext): Float =
    if (!edge) 0f else (if (signedDistance >= 0f) 1f else -1f) * ctx.brushSize / 2f

/**
 * Ellipse ruler (spec G2.2). Free mode: the concentric ellipse through p0 and the radial line through
 * the centre and p0. Magnet mode: within 32 screen px of the contour only the contour (±brushSize/2
 * along r with [EllipseConfig.edge]).
 */
class EllipseGuide(val cfg: EllipseConfig) : SnapGuide {
    private val ratio = cfg.ratio.coerceIn(EllipseConfig.MIN_RATIO, 1f)
    private val angle = cfg.angleDeg.toRad()

    /** Radius of [p] in the ellipse's unit frame (rx-scaled): `|E(p)|`. */
    fun unitRadius(p: Vec2): Float {
        val dx = p.x - cfg.c.x; val dy = p.y - cfg.c.y
        val cs = cos(angle); val sn = sin(angle)
        val u = dx * cs + dy * sn
        val v = (-dx * sn + dy * cs) / ratio
        return sqrt(u * u + v * v)
    }

    override fun tracks(p0: Vec2, ctx: SnapContext, out: MutableList<SnapTrack>) {
        if (cfg.rx <= 0f) return
        val r0 = unitRadius(p0)
        if (cfg.magnet) {
            val contour = EllipseTrack(cfg.c, cfg.rx, ratio, angle)
            val proj = Projection()
            contour.project(p0.x, p0.y, Float.NaN, proj)
            val dist = p0.dist(Vec2(proj.x, proj.y))
            if (dist > MAGNET_SCREEN_PX * ctx.screenToDoc) return
            val off = edgeOffset(r0 - cfg.rx, cfg.edge, ctx)
            out.add(EllipseTrack(cfg.c, (cfg.rx + off).coerceAtLeast(0.5f), ratio, angle))
            return
        }
        if (r0 > MIN_RADIUS) out.add(EllipseTrack(cfg.c, r0, ratio, angle))
        if (p0.dist(cfg.c) > MIN_RADIUS) out.add(LineTrack(cfg.c, p0 - cfg.c, SnapPriority.ELLIPSE))
    }

    private companion object {
        const val MIN_RADIUS = 1e-3f
    }
}

/** Pen guide (spec G2.3): once locked, every stroke follows the flattened path. */
class PenGuide(val cfg: PenConfig) : SnapGuide {
    private val line: ArcPolyline? = if (cfg.nodes.size >= 2) {
        ArcPolyline(BezierPath(cfg.nodes, cfg.closed).flatten(), cfg.closed)
    } else null

    override fun tracks(p0: Vec2, ctx: SnapContext, out: MutableList<SnapTrack>) {
        val l = line ?: return
        if (l.length <= 0f) return
        out.add(PathTrack(l))
    }
}
