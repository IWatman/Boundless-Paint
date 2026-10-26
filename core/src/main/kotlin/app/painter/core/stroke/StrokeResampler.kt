package app.painter.core.stroke

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Receives dabs from [StrokeResampler]; called once per dab, no allocation. */
fun interface DabSink {
    /** One dab at ([x],[y]) with diameter [size] and per-dab [flow]. */
    fun dab(x: Float, y: Float, size: Float, flow: Float, pressure: Float, tilt: Float)
}

/**
 * Turns smoothed input points into evenly spaced dabs.
 *
 * The path is a uniform Catmull-Rom spline through the points, tessellated into ≈1 px pieces;
 * dabs are placed by arc length with step `max(0.5, spacing·currentSize)`. Pressure and tilt are
 * interpolated linearly per segment. The spline lags one input point because a segment needs
 * its following control point; [end] closes the last segment.
 *
 * Start taper scales size by distance from the start. End taper is resolved by holding back
 * the last `taperEnd` px of path until [end], then placing the tail with tapered sizes (and
 * therefore tapered spacing).
 */
class StrokeResampler(private val params: BrushParams, private val sink: DabSink) {
    private val wx = FloatArray(4)
    private val wy = FloatArray(4)
    private val wp = FloatArray(4)
    private val wt = FloatArray(4)
    private var ctrlTotal = 0

    private var vx = FloatArray(INITIAL_CAP)
    private var vy = FloatArray(INITIAL_CAP)
    private var vp = FloatArray(INITIAL_CAP)
    private var vt = FloatArray(INITIAL_CAP)
    private var vd = FloatArray(INITIAL_CAP)
    private var vCount = 0
    private var walkIdx = 0

    private var totalLen = 0f
    private var nextDab = 0f
    private var lastDab = 0f
    private var lastStep = 0f
    private var ending = false
    private var dabCount = 0
    private var maxSize = 0f

    /** Number of dabs emitted in the current stroke. */
    val emittedDabs: Int get() = dabCount

    /** Starts a new stroke at [p]. */
    fun begin(p: InputPoint) {
        ctrlTotal = 0
        vCount = 0
        walkIdx = 0
        totalLen = 0f
        nextDab = 0f
        lastDab = 0f
        lastStep = 0f
        ending = false
        dabCount = 0
        maxSize = 0f
        pushCtrl(p)
        addVertex(p.x, p.y, p.pressure, p.tilt, 0f)
        advance(streamingLimit())
    }

    /** Appends a smoothed point; emits all dabs that are now final. */
    fun add(p: InputPoint) {
        if (ctrlTotal == 0) {
            begin(p); return
        }
        val dx = p.x - wx[3]
        val dy = p.y - wy[3]
        if (dx * dx + dy * dy < MIN_POINT_DIST * MIN_POINT_DIST) {
            wp[3] = p.pressure; wt[3] = p.tilt
            return
        }
        pushCtrl(p)
        if (ctrlTotal >= 3) {
            val i0 = if (ctrlTotal >= 4) 0 else 1
            segment(i0, 1, 2, 3)
            advance(streamingLimit())
        }
    }

    /** Closes the path, applies the end taper and emits the remaining dabs. */
    fun end() {
        if (ctrlTotal >= 2) {
            val i0 = if (ctrlTotal >= 3) 1 else 2
            segment(i0, 2, 3, 3)
        }
        ending = true
        advance(totalLen)
        val last = vCount - 1
        if (totalLen < 1f) {
            val full = params.sizeAt(vp[last])
            if (maxSize < full * 0.5f) emit(vx[last], vy[last], full, vp[last], vt[last])
        } else if (lastStep > 0f && totalLen - lastDab >= TAIL_FRACTION * lastStep) {
            emit(vx[last], vy[last], sizeAt(totalLen, vp[last]), vp[last], vt[last])
        }
        ctrlTotal = 0
    }

    private fun streamingLimit(): Float =
        if (params.taperEnd > 0f) totalLen - params.taperEnd else totalLen

    private fun pushCtrl(p: InputPoint) {
        for (i in 0 until 3) {
            wx[i] = wx[i + 1]; wy[i] = wy[i + 1]; wp[i] = wp[i + 1]; wt[i] = wt[i + 1]
        }
        wx[3] = p.x; wy[3] = p.y; wp[3] = p.pressure; wt[3] = p.tilt
        ctrlTotal++
    }

    private fun segment(i0: Int, i1: Int, i2: Int, i3: Int) {
        val x0 = wx[i0]; val y0 = wy[i0]
        val x1 = wx[i1]; val y1 = wy[i1]
        val x2 = wx[i2]; val y2 = wy[i2]
        val x3 = wx[i3]; val y3 = wy[i3]
        val cdx = x2 - x1
        val cdy = y2 - y1
        val chord = sqrt(cdx * cdx + cdy * cdy)
        val pieces = ceil(chord / PIECE_LEN).toInt().coerceIn(1, MAX_PIECES)
        val ax = -x0 + 3 * x1 - 3 * x2 + x3
        val bx = 2 * x0 - 5 * x1 + 4 * x2 - x3
        val ex = -x0 + x2
        val ay = -y0 + 3 * y1 - 3 * y2 + y3
        val by = 2 * y0 - 5 * y1 + 4 * y2 - y3
        val ey = -y0 + y2
        var px = vx[vCount - 1]
        var py = vy[vCount - 1]
        for (k in 1..pieces) {
            val t = k.toFloat() / pieces
            val nx: Float
            val ny: Float
            if (k == pieces) {
                nx = x2; ny = y2
            } else {
                nx = 0.5f * (2 * x1 + (ex + (bx + ax * t) * t) * t)
                ny = 0.5f * (2 * y1 + (ey + (by + ay * t) * t) * t)
            }
            val sdx = nx - px
            val sdy = ny - py
            totalLen += sqrt(sdx * sdx + sdy * sdy)
            addVertex(
                nx, ny,
                wp[i1] + (wp[i2] - wp[i1]) * t,
                wt[i1] + (wt[i2] - wt[i1]) * t,
                totalLen,
            )
            px = nx; py = ny
        }
    }

    private fun addVertex(x: Float, y: Float, p: Float, t: Float, d: Float) {
        if (vCount == vx.size) {
            if (walkIdx > 0) compact() else grow()
        }
        vx[vCount] = x; vy[vCount] = y; vp[vCount] = p; vt[vCount] = t; vd[vCount] = d
        vCount++
    }

    private fun compact() {
        val shift = walkIdx
        val n = vCount - shift
        System.arraycopy(vx, shift, vx, 0, n)
        System.arraycopy(vy, shift, vy, 0, n)
        System.arraycopy(vp, shift, vp, 0, n)
        System.arraycopy(vt, shift, vt, 0, n)
        System.arraycopy(vd, shift, vd, 0, n)
        vCount = n
        walkIdx = 0
        if (vCount == vx.size) grow()
    }

    private fun grow() {
        val cap = vx.size * 2
        vx = vx.copyOf(cap); vy = vy.copyOf(cap); vp = vp.copyOf(cap)
        vt = vt.copyOf(cap); vd = vd.copyOf(cap)
    }

    private fun advance(limit: Float) {
        while (nextDab <= limit + EPS) {
            while (walkIdx + 1 < vCount && vd[walkIdx + 1] < nextDab) walkIdx++
            val i = walkIdx
            val x: Float
            val y: Float
            val p: Float
            val t: Float
            if (i + 1 >= vCount) {
                x = vx[i]; y = vy[i]; p = vp[i]; t = vt[i]
            } else {
                val seg = vd[i + 1] - vd[i]
                val f = if (seg > 0f) ((nextDab - vd[i]) / seg).coerceIn(0f, 1f) else 0f
                x = vx[i] + (vx[i + 1] - vx[i]) * f
                y = vy[i] + (vy[i + 1] - vy[i]) * f
                p = vp[i] + (vp[i + 1] - vp[i]) * f
                t = vt[i] + (vt[i + 1] - vt[i]) * f
            }
            val size = sizeAt(nextDab, p)
            emit(x, y, size, p, t)
            val step = max(MIN_STEP, params.spacing * size)
            lastDab = nextDab
            lastStep = step
            nextDab += step
        }
    }

    private fun emit(x: Float, y: Float, size: Float, p: Float, t: Float) {
        dabCount++
        maxSize = max(maxSize, size)
        sink.dab(x, y, size, params.flowAt(p), p, t)
    }

    private fun sizeAt(d: Float, pressure: Float): Float {
        var f = 1f
        if (params.taperStart > 0f) f = min(f, ease(d / params.taperStart))
        if (ending && params.taperEnd > 0f) f = min(f, ease((totalLen - d) / params.taperEnd))
        return params.sizeAt(pressure) * f
    }

    private fun ease(t: Float): Float {
        val c = 1f - t.coerceIn(0f, 1f)
        return 1f - c * c
    }

    private companion object {
        const val INITIAL_CAP = 1024
        const val PIECE_LEN = 1f
        const val MAX_PIECES = 4096
        const val MIN_STEP = 0.5f
        const val MIN_POINT_DIST = 0.05f
        const val TAIL_FRACTION = 0.3f
        const val EPS = 1e-4f
    }
}
