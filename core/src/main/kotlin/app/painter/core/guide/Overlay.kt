package app.painter.core.guide

import app.painter.core.geom.Vec2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Axis-aligned document-space rectangle (the visible area for overlay generation). */
data class DocRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    fun contains(p: Vec2) = p.x in x0..x1 && p.y in y0..y1
    val corners: List<Vec2> get() = listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1))
}

/**
 * What an overlay line shows; the UI maps roles to colours (spec G5: VP1 `#FF7A59`, VP2 `#3DDC97`,
 * VP3 `#4F8CFF`, horizon `#FFD166`, all 50%; grid overlay colours come from [GridConfig]).
 */
enum class OverlayRole { VP1, VP2, VP3, HORIZON, VERTICAL, GRID, MINOR, MAJOR }

/** One overlay segment in document px, already clipped to the visible area. */
data class OverlayLine(val a: Vec2, val b: Vec2, val role: OverlayRole)

/** Line clipping and line families shared by overlays. */
object OverlayGeom {
    /** Upper bound on lines per family, guards against degenerate zoom levels. */
    const val MAX_FAMILY_LINES = 2048

    /** Clips `p + t·d` for t in [[tMin], [tMax]] to [r] (Liang–Barsky); null when it misses. */
    fun clip(
        p: Vec2,
        d: Vec2,
        r: DocRect,
        tMin: Float = Float.NEGATIVE_INFINITY,
        tMax: Float = Float.POSITIVE_INFINITY,
    ): Pair<Vec2, Vec2>? {
        var t0 = tMin; var t1 = tMax
        fun edge(pp: Float, q: Float): Boolean {
            if (pp == 0f) return q >= 0f
            val t = q / pp
            if (pp < 0f) { if (t > t1) return false; if (t > t0) t0 = t } else { if (t < t0) return false; if (t < t1) t1 = t }
            return true
        }
        if (!edge(-d.x, p.x - r.x0) || !edge(d.x, r.x1 - p.x) || !edge(-d.y, p.y - r.y0) || !edge(d.y, r.y1 - p.y)) {
            return null
        }
        if (t0.isInfinite() || t1.isInfinite() || t0 > t1) return null
        return Vec2(p.x + d.x * t0, p.y + d.y * t0) to Vec2(p.x + d.x * t1, p.y + d.y * t1)
    }

    /** Parallel lines along unit [dir] through `origin + k·spacing·normal`, clipped to [r]. */
    fun family(origin: Vec2, dir: Vec2, spacing: Float, r: DocRect, role: OverlayRole, out: MutableList<OverlayLine>) {
        if (spacing <= 0f) return
        val n = Vec2(-dir.y, dir.x)
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (c in r.corners) {
            val o = (c.x - origin.x) * n.x + (c.y - origin.y) * n.y
            lo = min(lo, o); hi = max(hi, o)
        }
        val k0 = ceil(lo / spacing).toLong(); val k1 = floor(hi / spacing).toLong()
        if (k1 - k0 + 1 > MAX_FAMILY_LINES) return
        for (k in k0..k1) {
            val p = Vec2(origin.x + n.x * k * spacing, origin.y + n.y * k * spacing)
            clip(p, dir, r)?.let { out.add(OverlayLine(it.first, it.second, role)) }
        }
    }

    /** Doubles [step] until it is at least [minScreenPx] on screen. */
    fun thin(step: Float, screenToDoc: Float, minScreenPx: Float = MIN_STEP_SCREEN_PX): Float {
        var s = step
        var guard = 0
        while (s / screenToDoc < minScreenPx && guard++ < 30) s *= 2f
        return s
    }

    /** Overlay lines closer than this on screen are thinned or hidden. */
    const val MIN_STEP_SCREEN_PX = 6f

    internal fun len(v: Vec2) = sqrt(v.x * v.x + v.y * v.y)
}

/** View grid overlay (spec G2.6): display only, no snapping. Colours are `#RRGGBBAA`. */
data class GridConfig(
    val on: Boolean = false,
    val minor: Float = 32f,
    val majorEvery: Int = 4,
    val origin: Vec2 = Vec2(0f, 0f),
    val minorColor: String = "#FFFFFF26",
    val majorColor: String = "#FFFFFF4D",
) {
    /**
     * Lines visible in [view]. Minor lines are skipped when their step is under 6 screen px; major
     * lines are thinned ×2 in that case.
     */
    fun lines(view: DocRect, screenToDoc: Float): List<OverlayLine> {
        val out = ArrayList<OverlayLine>()
        if (!on || minor <= 0f) return out
        val every = majorEvery.coerceAtLeast(1)
        val major = OverlayGeom.thin(minor * every, screenToDoc)
        val showMinor = minor / screenToDoc >= OverlayGeom.MIN_STEP_SCREEN_PX && every > 1
        if (showMinor) {
            addAxis(view, minor, OverlayRole.MINOR, out) { k -> k % every != 0L }
        }
        addAxis(view, major, OverlayRole.MAJOR, out) { true }
        return out
    }

    private inline fun addAxis(v: DocRect, step: Float, role: OverlayRole, out: MutableList<OverlayLine>, keep: (Long) -> Boolean) {
        val kx0 = ceil((v.x0 - origin.x) / step).toLong(); val kx1 = floor((v.x1 - origin.x) / step).toLong()
        val ky0 = ceil((v.y0 - origin.y) / step).toLong(); val ky1 = floor((v.y1 - origin.y) / step).toLong()
        if (kx1 - kx0 > OverlayGeom.MAX_FAMILY_LINES || ky1 - ky0 > OverlayGeom.MAX_FAMILY_LINES) return
        for (k in kx0..kx1) if (keep(k)) {
            val x = origin.x + k * step
            out.add(OverlayLine(Vec2(x, v.y0), Vec2(x, v.y1), role))
        }
        for (k in ky0..ky1) if (keep(k)) {
            val y = origin.y + k * step
            out.add(OverlayLine(Vec2(v.x0, y), Vec2(v.x1, y), role))
        }
    }
}
