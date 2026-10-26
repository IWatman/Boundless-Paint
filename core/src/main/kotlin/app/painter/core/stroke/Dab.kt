package app.painter.core.stroke

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * One stamp, fully resolved by [DabGenerator] (all randomness included) before rendering.
 *
 * Geometry: centre ([x], [y]), diameter [d], [angle] radians, axis scales [sx]/[sy] (aspect), [flipX] mirrors
 * the head horizontally in its own frame. Colour: straight [r], [g], [b] and colour alpha [a]. [flow] is the
 * per-dab alpha, [opacity] a per-dab ceiling the pixel alpha approaches (1 = plain source-over).
 * [texDepth], [mixIn], [dilution], [blur] carry dynamics results for later kernels. [index] is the dab's RNG
 * index (`pathIndex·16 + k`).
 */
class Dab {
    var x = 0f
    var y = 0f
    var d = 0f
    var angle = 0f
    var sx = 1f
    var sy = 1f
    var flipX = false
    var flow = 1f
    var opacity = 1f
    var hardness = 0.8f
    var pressure = 1f
    var r = 0f
    var g = 0f
    var b = 0f
    var a = 1f
    var texDepth = 0f
    var mixIn = 0f
    var dilution = 0f
    var blur = 0f
    var index = 0

    fun set(o: Dab) {
        x = o.x; y = o.y; d = o.d; angle = o.angle; sx = o.sx; sy = o.sy; flipX = o.flipX
        flow = o.flow; opacity = o.opacity; hardness = o.hardness; pressure = o.pressure
        r = o.r; g = o.g; b = o.b; a = o.a
        texDepth = o.texDepth; mixIn = o.mixIn; dilution = o.dilution; blur = o.blur; index = o.index
    }

    /**
     * Half extent of the axis-aligned bounding box, including the 0.5 px AA band: of the (rotated) ellipse, or
     * of the rotated head square when [square].
     */
    fun halfExtent(square: Boolean = false): Float {
        val rx = max(d * 0.5f * sx, 0.5f)
        val ry = max(d * 0.5f * sy, 0.5f)
        if (rx == ry && !square) return rx + 0.5f
        val c = abs(cos(angle))
        val s = abs(sin(angle))
        return max(rx * c + ry * s, rx * s + ry * c) + 0.5f
    }
}

/** Reusable ordered list of dabs; [add] hands out pooled instances. */
class DabBatch(capacity: Int = DEFAULT_CAPACITY) {
    private var items = Array(capacity) { Dab() }
    var size = 0
        private set

    val capacity: Int get() = items.size
    val isFull: Boolean get() = size >= items.size

    operator fun get(i: Int): Dab = items[i]

    /** Next slot (grows past [capacity] if needed; callers flush at [isFull]). */
    fun add(): Dab {
        if (size == items.size) items = Array(items.size * 2) { if (it < items.size) items[it] else Dab() }
        return items[size++]
    }

    /** Drops the last added dab. */
    fun removeLast() {
        if (size > 0) size--
    }

    fun clear() {
        size = 0
    }

    companion object {
        /** Per-frame dab budget (spec/tools/m2-brush.md §1.3). */
        const val DEFAULT_CAPACITY = 4096
    }
}
