package app.painter.core.guide

import app.painter.core.geom.Vec2
import app.painter.core.perspective.PerspectiveConfig
import app.painter.core.perspective.PerspectiveGuide
import app.painter.core.shape.PathNode
import app.painter.core.stroke.PointStage
import app.painter.core.symmetry.SymmetryConfig
import app.painter.core.symmetry.SymmetryMath
import app.painter.core.stroke.SymmetryCopies

/** Ruler segment [a]–[b] (spec G2.1). */
data class LinesConfig(
    val on: Boolean = false,
    val a: Vec2 = Vec2(0f, 0f),
    val b: Vec2 = Vec2(100f, 0f),
    val perpendicular: Boolean = true,
    val magnet: Boolean = false,
    val edge: Boolean = false,
    val locked: Boolean = false,
) {
    companion object {
        /** Horizontal ruler through the centre of [view], half its width long. */
        fun defaultFor(view: DocRect): LinesConfig {
            val cx = (view.x0 + view.x1) / 2; val cy = (view.y0 + view.y1) / 2
            val half = (view.x1 - view.x0) / 4
            return LinesConfig(on = true, a = Vec2(cx - half, cy), b = Vec2(cx + half, cy))
        }
    }
}

/** Ellipse ruler (spec G2.2): centre [c], semi-axis [rx], `ry = rx·ratio`, rotation [angleDeg]. */
data class EllipseConfig(
    val on: Boolean = false,
    val c: Vec2 = Vec2(0f, 0f),
    val rx: Float = 300f,
    val ratio: Float = 0.5f,
    val angleDeg: Float = 0f,
    val magnet: Boolean = false,
    val edge: Boolean = false,
    val locked: Boolean = false,
) {
    companion object {
        const val MIN_RATIO = 0.02f

        /** Circle at the centre of [view] with radius 25% of its short side. */
        fun defaultFor(view: DocRect): EllipseConfig {
            val w = view.x1 - view.x0; val h = view.y1 - view.y0
            return EllipseConfig(
                on = true, c = Vec2((view.x0 + view.x1) / 2, (view.y0 + view.y1) / 2),
                rx = 0.25f * minOf(w, h), ratio = 1f,
            )
        }
    }
}

/** Pen guide path (spec G2.3). Snaps only when [on] and [locked]. */
data class PenConfig(
    val on: Boolean = false,
    val nodes: List<PathNode> = emptyList(),
    val closed: Boolean = false,
    val locked: Boolean = false,
)

/** Lazy rope: [radius] in screen px (0–200, 0 = off), [elastic] in percent 0–100. */
data class LazyConfig(val on: Boolean = false, val radius: Float = 60f, val elastic: Float = 0f)

data class HatchingConfig(val on: Boolean = false)

/**
 * Immutable snapshot of all guide, symmetry and grid settings (manifest `guides`, spec G1.5).
 * Edits go through `copy`; the stroke pipeline is built from a snapshot at stroke start.
 */
data class GuideState(
    val lines: LinesConfig = LinesConfig(),
    val ellipse: EllipseConfig = EllipseConfig(),
    val pen: PenConfig = PenConfig(),
    val lazy: LazyConfig = LazyConfig(),
    val hatching: HatchingConfig = HatchingConfig(),
    val symmetry: SymmetryConfig = SymmetryConfig(),
    val perspective: PerspectiveConfig = PerspectiveConfig(),
    val grid: GridConfig = GridConfig(),
) {
    /** Active snapping guides in priority-independent order. */
    fun snapGuides(): List<SnapGuide> = buildList {
        if (pen.on && pen.locked && pen.nodes.size >= 2) add(PenGuide(pen))
        if (ellipse.on) add(EllipseGuide(ellipse))
        if (lines.on) add(LinesGuide(lines))
        if (perspective.on && perspective.snap) add(PerspectiveGuide(perspective))
    }

    /** True when some guide snaps strokes (shape recognition is then off, spec G2). */
    val hasActiveSnap: Boolean get() = snapGuides().isNotEmpty()

    /**
     * Fresh point stages for one freehand stroke, in pipeline order: lazy → snap → hatching
     * (spec G1.1). Shapes must not use these.
     */
    fun buildStages(ctx: SnapContext): List<PointStage> = buildList {
        if (lazy.on && lazy.radius > 0f) {
            add(LazyRopeStage(lazy.radius * ctx.screenToDoc, lazy.elastic / 100f, ctx.screenToDoc))
        }
        val guides = snapGuides()
        val snap = if (guides.isNotEmpty()) SnapStage(guides, ctx).also { add(it) } else null
        if (hatching.on) add(HatchingStage(snap))
    }

    /** Symmetry copies for a stroke or shape starting at [start]. */
    fun copies(start: Vec2): SymmetryCopies = SymmetryMath.copies(symmetry, start)

    companion object {
        /** Schema version of the `guides` block. */
        const val VERSION = 1
    }
}
