package app.painter.core.shape

import app.painter.core.geom.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sign

/** Angle snapping used by constrain (Touch-Alt / ⊥ toggle) and by guide handles. */
object Constrain {
    /** Default constrain step, degrees. */
    const val STEP_DEG = 15f

    /** Rounds [rad] to a multiple of [stepDeg]. */
    fun snapAngle(rad: Float, stepDeg: Float = STEP_DEG): Float {
        val step = stepDeg.toRad()
        return round(rad / step) * step
    }

    /**
     * Snaps [rad] to the nearest multiple of [everyDeg] when within [tolDeg] (guide handles: 0/45/90° ±3°),
     * otherwise returns it unchanged.
     */
    fun magnetAngle(rad: Float, everyDeg: Float = 45f, tolDeg: Float = 3f): Float {
        val s = snapAngle(rad, everyDeg)
        return if (abs(s - rad) <= tolDeg.toRad()) s else rad
    }

    /** [p] moved so that the direction from [pivot] is a multiple of [stepDeg], keeping the distance. */
    fun snapDirection(pivot: Vec2, p: Vec2, stepDeg: Float = STEP_DEG): Vec2 {
        val d = p - pivot
        return polar(pivot, d.len, snapAngle(d.angle, stepDeg))
    }
}

/** Kinds of shapes that the Shape tool creates (spec G3.1). */
enum class ShapeTool { LINE, RECT, ELLIPSE, POLYGON, ARC }

/** Creates shapes from the initial drag (spec G3.1 table, column «Creation»). */
object ShapeFactory {
    /** Sag of a new arc's mid point, fraction of the chord. */
    const val ARC_SAG = 0.25f

    fun fromDrag(tool: ShapeTool, start: Vec2, end: Vec2, constrain: Boolean = false, sides: Int = 5): Shape = when (tool) {
        ShapeTool.LINE -> Shape.Line(start, if (constrain) Constrain.snapDirection(start, end) else end)
        ShapeTool.RECT -> {
            var dx = end.x - start.x; var dy = end.y - start.y
            if (constrain) {
                val m = max(abs(dx), abs(dy)); dx = sgn(dx) * m; dy = sgn(dy) * m
            }
            Shape.Rect(Vec2(start.x + dx / 2, start.y + dy / 2), abs(dx), abs(dy))
        }
        ShapeTool.ELLIPSE -> {
            val r = start.dist(end)
            Shape.Ellipse(start, r, r)
        }
        ShapeTool.POLYGON -> {
            val d = end - start
            val a = if (constrain) Constrain.snapAngle(d.angle) else d.angle
            Shape.Polygon(start, d.len, sides, a)
        }
        ShapeTool.ARC -> {
            val chord = end - start
            val mid = lerp(start, end, 0.5f) + chord.perp() * ARC_SAG
            Shape.Arc(start, end, mid)
        }
    }

    private fun sgn(v: Float) = if (v < 0f) -1f else 1f
}

/** What a handle does (spec G3.1 «Handles»). */
enum class HandleKind { MOVE, ROTATE, END_A, END_B, CORNER, SIDE, RX, RY, VERTEX, ARC_START, ARC_END, ARC_MID, NODE, IN, OUT }

/** A draggable handle at [pos]; [index] = corner/side/node number. */
data class Handle(val kind: HandleKind, val pos: Vec2, val index: Int = 0)

/** Result of [ShapeHandles.hitTest]: a handle, or the contour (drag = move). */
sealed interface ShapeHit {
    data class OnHandle(val handle: Handle) : ShapeHit
    data object OnContour : ShapeHit
}

/** Handle layout, hit-testing and drag math of draft shapes. */
object ShapeHandles {
    /** Rotate stem length above the top side, screen px (40 dp). */
    const val ROTATE_STEM_SCREEN_PX = 40f
    const val HANDLE_HIT_SCREEN_PX = 24f
    const val CONTOUR_HIT_SCREEN_PX = 16f

    fun handles(shape: Shape, screenToDoc: Float = 1f): List<Handle> = when (shape) {
        is Shape.Line -> listOf(
            Handle(HandleKind.END_A, shape.a), Handle(HandleKind.END_B, shape.b),
            Handle(HandleKind.MOVE, lerp(shape.a, shape.b, 0.5f)),
        )
        is Shape.Rect -> frameHandles(shape.center, shape.w, shape.h, shape.rot, screenToDoc)
        is Shape.Ellipse -> buildList {
            add(Handle(HandleKind.RX, toWorld(shape.center, shape.rot, Vec2(shape.rx, 0f))))
            add(Handle(HandleKind.RY, toWorld(shape.center, shape.rot, Vec2(0f, shape.ry))))
            addAll(frameHandles(shape.center, 2 * shape.rx, 2 * shape.ry, shape.rot, screenToDoc).filter { it.kind != HandleKind.SIDE })
        }
        is Shape.Polygon -> listOf(
            Handle(HandleKind.VERTEX, polar(shape.center, shape.radius, shape.rot)),
            Handle(HandleKind.MOVE, shape.center),
        )
        is Shape.Arc -> listOf(
            Handle(HandleKind.ARC_START, shape.start), Handle(HandleKind.ARC_END, shape.end),
            Handle(HandleKind.ARC_MID, shape.mid),
        )
        is Shape.Path -> buildList {
            shape.path.nodes.forEachIndexed { i, n ->
                add(Handle(HandleKind.NODE, n.p, i))
                if (n.type == NodeType.ALIGNED) {
                    add(Handle(HandleKind.IN, n.p + n.inHandle, i))
                    add(Handle(HandleKind.OUT, n.p + n.outHandle, i))
                }
            }
        }
    }

    private fun frameHandles(c: Vec2, w: Float, h: Float, rot: Float, screenToDoc: Float): List<Handle> = buildList {
        val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
        corners.forEachIndexed { i, (sx, sy) -> add(Handle(HandleKind.CORNER, toWorld(c, rot, Vec2(sx * w / 2, sy * h / 2)), i)) }
        val sides = listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f)
        sides.forEachIndexed { i, (sx, sy) -> add(Handle(HandleKind.SIDE, toWorld(c, rot, Vec2(sx * w / 2, sy * h / 2)), i)) }
        add(Handle(HandleKind.MOVE, c))
        add(Handle(HandleKind.ROTATE, toWorld(c, rot, Vec2(0f, -h / 2 - ROTATE_STEM_SCREEN_PX * screenToDoc))))
    }

    /** Handle within 24 screen px (nearest wins), else contour within 16 px, else null. */
    fun hitTest(shape: Shape, p: Vec2, screenToDoc: Float = 1f): ShapeHit? {
        val h = handles(shape, screenToDoc).minByOrNull { it.pos.dist(p) }
        if (h != null && h.pos.dist(p) <= HANDLE_HIT_SCREEN_PX * screenToDoc) return ShapeHit.OnHandle(h)
        val d = distToPolyline(p, shape.outline(), shape.closed)
        return if (d <= CONTOUR_HIT_SCREEN_PX * screenToDoc) ShapeHit.OnContour else null
    }

    /** Shape translated by [delta] (contour drag or MOVE handle). */
    fun move(shape: Shape, delta: Vec2): Shape = when (shape) {
        is Shape.Line -> Shape.Line(shape.a + delta, shape.b + delta)
        is Shape.Rect -> shape.copy(center = shape.center + delta)
        is Shape.Ellipse -> shape.copy(center = shape.center + delta)
        is Shape.Polygon -> shape.copy(center = shape.center + delta)
        is Shape.Arc -> Shape.Arc(shape.start + delta, shape.end + delta, shape.mid + delta)
        is Shape.Path -> Shape.Path(shape.path.copy(nodes = shape.path.nodes.map { it.copy(p = it.p + delta) }))
    }

    /**
     * Shape after dragging [handle] (as returned by [handles] for this shape) to [to]. [constrain]:
     * Line 15° steps, Rect square / rotate 15°, Ellipse circle / rotate 15°, Polygon rotate 15°,
     * Arc mid on the perpendicular bisector, Path tangents 15°.
     */
    fun drag(shape: Shape, handle: Handle, to: Vec2, constrain: Boolean = false): Shape {
        if (handle.kind == HandleKind.MOVE) return move(shape, to - handle.pos)
        return when (shape) {
            is Shape.Line -> when (handle.kind) {
                HandleKind.END_A -> shape.copy(a = if (constrain) Constrain.snapDirection(shape.b, to) else to)
                HandleKind.END_B -> shape.copy(b = if (constrain) Constrain.snapDirection(shape.a, to) else to)
                else -> shape
            }
            is Shape.Rect -> when (handle.kind) {
                HandleKind.ROTATE -> shape.copy(rot = rotateAngle(shape.center, to, constrain))
                HandleKind.CORNER, HandleKind.SIDE -> {
                    val f = dragFrame(shape.center, shape.w, shape.h, shape.rot, handle, to, constrain)
                    Shape.Rect(f.first, f.second.x, f.second.y, shape.rot)
                }
                else -> shape
            }
            is Shape.Ellipse -> when (handle.kind) {
                HandleKind.ROTATE -> shape.copy(rot = rotateAngle(shape.center, to, constrain))
                HandleKind.RX -> {
                    val rx = abs(toLocal(shape.center, shape.rot, to).x)
                    shape.copy(rx = rx, ry = if (constrain) rx else shape.ry)
                }
                HandleKind.RY -> {
                    val ry = abs(toLocal(shape.center, shape.rot, to).y)
                    shape.copy(ry = ry, rx = if (constrain) ry else shape.rx)
                }
                HandleKind.CORNER -> {
                    val f = dragFrame(shape.center, 2 * shape.rx, 2 * shape.ry, shape.rot, handle, to, constrain)
                    Shape.Ellipse(f.first, f.second.x / 2, f.second.y / 2, shape.rot)
                }
                else -> shape
            }
            is Shape.Polygon -> when (handle.kind) {
                HandleKind.VERTEX -> {
                    val d = to - shape.center
                    shape.copy(radius = d.len, rot = if (constrain) Constrain.snapAngle(d.angle) else d.angle)
                }
                else -> shape
            }
            is Shape.Arc -> dragArc(shape, handle.kind, to, constrain)
            is Shape.Path -> Shape.Path(PathEdit.dragHandle(shape.path, handle, to, constrain))
        }
    }

    private fun rotateAngle(c: Vec2, to: Vec2, constrain: Boolean): Float {
        val a = (to - c).angle + (PI / 2).toFloat()
        return if (constrain) Constrain.snapAngle(a) else a
    }

    /** New (centre, size) of a rotated frame after dragging a corner or side; the opposite one stays. */
    private fun dragFrame(c: Vec2, w: Float, h: Float, rot: Float, handle: Handle, to: Vec2, constrain: Boolean): Pair<Vec2, Vec2> {
        val local = toLocal(c, rot, to)
        return if (handle.kind == HandleKind.CORNER) {
            val sx = if (handle.index == 1 || handle.index == 2) 1f else -1f
            val sy = if (handle.index >= 2) 1f else -1f
            val ox = -sx * w / 2; val oy = -sy * h / 2
            var vx = local.x - ox; var vy = local.y - oy
            if (constrain) {
                val m = max(abs(vx), abs(vy))
                vx = (if (vx == 0f) sx else sign(vx)) * m; vy = (if (vy == 0f) sy else sign(vy)) * m
            }
            toWorld(c, rot, Vec2(ox + vx / 2, oy + vy / 2)) to Vec2(abs(vx), abs(vy))
        } else {
            when (handle.index) {
                0, 2 -> {
                    val sy = if (handle.index == 2) 1f else -1f
                    val oy = -sy * h / 2
                    val vy = local.y - oy
                    toWorld(c, rot, Vec2(0f, oy + vy / 2)) to Vec2(w, abs(vy))
                }
                else -> {
                    val sx = if (handle.index == 1) 1f else -1f
                    val ox = -sx * w / 2
                    val vx = local.x - ox
                    toWorld(c, rot, Vec2(ox + vx / 2, 0f)) to Vec2(abs(vx), h)
                }
            }
        }
    }

    private fun dragArc(a: Shape.Arc, kind: HandleKind, to: Vec2, constrain: Boolean): Shape.Arc = when (kind) {
        HandleKind.ARC_MID -> {
            if (!constrain) a.copy(mid = to) else {
                val chord = a.end - a.start
                val n = chord.perp().normalized()
                val m = lerp(a.start, a.end, 0.5f)
                a.copy(mid = m + n * ((to - m) dot n))
            }
        }
        HandleKind.ARC_START, HandleKind.ARC_END -> {
            val (u, v) = chordCoords(a.start, a.end, a.mid)
            val s = if (kind == HandleKind.ARC_START) to else a.start
            val e = if (kind == HandleKind.ARC_END) to else a.end
            val chord = e - s
            a.copy(start = s, end = e, mid = s + chord * u + chord.perp() * v)
        }
        else -> a
    }

    /** Coordinates of [p] in the chord frame of [s]→[e], scaled by the chord length. */
    private fun chordCoords(s: Vec2, e: Vec2, p: Vec2): Pair<Float, Float> {
        val chord = e - s
        val l2 = chord dot chord
        if (l2 < 1e-12f) return 0.5f to 0f
        val d = p - s
        return (d dot chord) / l2 to (d dot chord.perp()) / l2
    }
}

/** Editing operations of a [BezierPath] (Pen guide construction and the Path shape, spec G2.3). */
object PathEdit {
    /** A drag at node creation longer than this (screen px) makes an aligned node. */
    const val ALIGNED_DRAG_SCREEN_PX = 8f

    /** Dropping a node closer than this to a neighbour (screen px) deletes it. */
    const val MERGE_SCREEN_PX = 16f

    /** Appends a node at [p]; a drag to [dragTo] beyond 8 screen px makes an aligned node with that out-tangent. */
    fun append(path: BezierPath, p: Vec2, dragTo: Vec2? = null, screenToDoc: Float = 1f): BezierPath {
        val node = if (dragTo != null && p.dist(dragTo) > ALIGNED_DRAG_SCREEN_PX * screenToDoc) {
            val out = dragTo - p
            PathNode(p, NodeType.ALIGNED, -out, out)
        } else PathNode(p)
        return path.copy(nodes = path.nodes + node)
    }

    /** Index of the segment within [radiusDoc] of [p] (nearest), or −1. */
    fun hitSegment(path: BezierPath, p: Vec2, radiusDoc: Float): Int {
        var best = -1; var bestD = radiusDoc
        path.segments().forEachIndexed { i, c ->
            val pts = ArrayList<Vec2>().apply { add(c.p0) }
            BezierPath.flattenCubic(c, BezierPath.DEFAULT_TOLERANCE, pts, 0)
            val d = distToPolyline(p, pts, false)
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Inserts a cusp node at [p] after node [segment] (tap on a segment). */
    fun insert(path: BezierPath, segment: Int, p: Vec2): BezierPath {
        val nodes = path.nodes.toMutableList()
        nodes.add(segment + 1, PathNode(p))
        return path.copy(nodes = nodes)
    }

    /** Tap on a node: cusp ↔ smooth (aligned becomes cusp). */
    fun toggle(path: BezierPath, index: Int): BezierPath {
        val n = path.nodes[index]
        val t = if (n.type == NodeType.CUSP) NodeType.SMOOTH else NodeType.CUSP
        return path.copy(nodes = path.nodes.toMutableList().also { it[index] = n.copy(type = t) })
    }

    /** Moves node [index] to [to]; dropping it within 16 screen px of a neighbour deletes it. */
    fun moveNode(path: BezierPath, index: Int, to: Vec2, screenToDoc: Float = 1f): BezierPath {
        val nodes = path.nodes
        val r = MERGE_SCREEN_PX * screenToDoc
        val neighbours = listOfNotNull(
            nodes.getOrNull(index - 1) ?: if (path.closed) nodes.lastOrNull() else null,
            nodes.getOrNull(index + 1) ?: if (path.closed) nodes.firstOrNull() else null,
        ).filter { it !== nodes[index] }
        if (nodes.size > 2 && neighbours.any { it.p.dist(to) < r }) {
            return path.copy(nodes = nodes.filterIndexed { i, _ -> i != index })
        }
        return path.copy(nodes = nodes.toMutableList().also { it[index] = it[index].copy(p = to) })
    }

    /** Drag of a path handle (node, in or out tangent). Tangent drags keep the node aligned. */
    fun dragHandle(path: BezierPath, handle: Handle, to: Vec2, constrain: Boolean): BezierPath {
        val i = handle.index
        val n = path.nodes.getOrNull(i) ?: return path
        return when (handle.kind) {
            HandleKind.NODE -> path.copy(nodes = path.nodes.toMutableList().also { it[i] = n.copy(p = to) })
            HandleKind.IN, HandleKind.OUT -> {
                val target = if (constrain) Constrain.snapDirection(n.p, to) else to
                val v = target - n.p
                val other = if (handle.kind == HandleKind.IN) n.outHandle else n.inHandle
                val otherLen = if (other.len > 1e-6f) other.len else v.len
                val mirrored = (-v).normalized() * otherLen
                val updated = if (handle.kind == HandleKind.IN) {
                    n.copy(type = NodeType.ALIGNED, inHandle = v, outHandle = mirrored)
                } else {
                    n.copy(type = NodeType.ALIGNED, outHandle = v, inHandle = mirrored)
                }
                path.copy(nodes = path.nodes.toMutableList().also { it[i] = updated })
            }
            else -> path
        }
    }
}
