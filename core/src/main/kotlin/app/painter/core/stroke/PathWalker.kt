package app.painter.core.stroke

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One position along the resampled path, reused between callbacks (copy what you keep).
 *
 * [distance] is the arc length from the start of the current sub-stroke, [dirX]/[dirY] the unit tangent
 * smoothed by an EMA (α = 0.35) over path steps, [velocity] the pen speed in screen px/ms (EMA α = 0.3),
 * [taper] the start/end taper factor 0..1 after [TaperShape]. [index] counts samples over the whole stroke
 * (it does not restart in a new sub-stroke). [ending] is true for samples placed by [PathWalker.end], which
 * also know the sub-stroke [total] length and the [remaining] distance; otherwise `total` is NaN and
 * `remaining` is +∞.
 */
class PathSample {
    var x = 0f
    var y = 0f
    var pressure = 1f
    var tilt = 0f
    var azimuth = 0f
    var twist = 0f
    var timeMs = 0L
    var distance = 0f
    var dirX = 1f
    var dirY = 0f
    var velocity = 0f
    var taper = 1f
    var index = 0
    var isStylus = false
    var ending = false
    var total = Float.NaN
    var remaining = Float.POSITIVE_INFINITY
}

/** Receives path samples; returns the distance to the next sample in px (clamped to ≥ 0.5). */
fun interface PathSink {
    fun onSample(s: PathSample): Float
}

/**
 * Turns smoothed input points into samples spaced by the step the [sink] asks for.
 *
 * [Interpolation.CATMULL_ROM]: uniform Catmull-Rom spline through the points, tessellated into ≈1 px pieces;
 * the spline lags one input point because a segment needs its following control point. [Interpolation.LINEAR]:
 * straight segments, no lag. Pressure, tilt, twist and time are interpolated linearly per segment, azimuth along
 * the shorter arc.
 *
 * Start taper scales by distance from the start. The last [holdBack] px (default: `taperEnd`, or the profile
 * end section if longer) are held until [end], then placed with the end taper. [closed] paths wrap to the
 * first point, have no taper and no second dab on the seam.
 *
 * With [deferForDirection] the first sample waits for the first segment, so it already has a tangent.
 */
class PathWalker(
    private val params: BrushParams,
    private val sink: PathSink,
    private val interpolation: Interpolation = Interpolation.CATMULL_ROM,
    private val closed: Boolean = false,
    private val zoom: Float = 1f,
    holdBack: Float = -1f,
    private val deferForDirection: Boolean = false,
) {
    private val taperStart = if (closed) 0f else params.taperStart
    private val taperEnd = if (closed) 0f else params.taperEnd
    private val holdBack = if (holdBack >= 0f) holdBack else max(taperEnd, params.profiles?.endPx ?: 0f)
    private val shape = params.taperShape

    private val w = arrayOfNulls<InputPoint>(4)
    private var first: InputPoint? = null
    private var second: InputPoint? = null
    private var ctrlTotal = 0
    private var t0 = 0L

    private var vx = FloatArray(INITIAL_CAP)
    private var vy = FloatArray(INITIAL_CAP)
    private var vp = FloatArray(INITIAL_CAP)
    private var vt = FloatArray(INITIAL_CAP)
    private var va = FloatArray(INITIAL_CAP)
    private var vw = FloatArray(INITIAL_CAP)
    private var vm = FloatArray(INITIAL_CAP)
    private var vd = FloatArray(INITIAL_CAP)
    private var vCount = 0
    private var walkIdx = 0

    private var totalLen = 0f
    private var nextDab = 0f
    private var lastDab = 0f
    private var lastStep = 0f
    private var ending = false
    private var subCount = 0
    private var maxTaper = 0f
    private var stylus = false

    private var hasDir = false
    private var dirX = 1f
    private var dirY = 0f
    private var vel = 0f
    private var prevD = 0f
    private var prevT = 0f
    private var sampleIndex = 0

    private val s = PathSample()

    /** Samples emitted in the current sub-stroke. */
    val emittedSamples: Int get() = subCount

    /** Samples emitted since construction (across sub-strokes). */
    val totalSamples: Int get() = sampleIndex

    /** Starts a new (sub-)stroke at [p]. */
    fun begin(p: InputPoint) {
        ctrlTotal = 0
        vCount = 0
        walkIdx = 0
        totalLen = 0f
        nextDab = 0f
        lastDab = 0f
        lastStep = 0f
        ending = false
        subCount = 0
        maxTaper = 0f
        hasDir = false
        dirX = 1f; dirY = 0f
        vel = 0f
        prevD = 0f
        prevT = 0f
        t0 = p.timeMs
        stylus = p.isStylus
        first = p
        second = null
        pushCtrl(p)
        addVertex(p.x, p.y, p.pressure, p.tilt, p.azimuth, p.twist, 0f, 0f)
        advance(streamingLimit())
    }

    /** Appends a smoothed point; emits all samples that are now final. */
    fun add(p: InputPoint) {
        if (ctrlTotal == 0) {
            begin(p); return
        }
        val last = w[3]!!
        val dx = p.x - last.x
        val dy = p.y - last.y
        if (dx * dx + dy * dy < MIN_POINT_DIST * MIN_POINT_DIST) {
            w[3] = last.copy(pressure = p.pressure, tilt = p.tilt, azimuth = p.azimuth, twist = p.twist)
            return
        }
        pushCtrl(p)
        if (ctrlTotal == 2) second = p
        if (interpolation == Interpolation.LINEAR) {
            line(w[2]!!, w[3]!!)
            advance(streamingLimit())
        } else if (ctrlTotal >= 3) {
            segment(if (ctrlTotal >= 4) w[0]!! else w[1]!!, w[1]!!, w[2]!!, w[3]!!)
            advance(streamingLimit())
        }
    }

    /** Closes the path, applies the end taper and emits the remaining samples. */
    fun end() {
        if (ctrlTotal == 0) return
        val f = first!!
        val last = w[3]!!
        val seamOpen = closed && ctrlTotal >= 2 && dist2(f, last) >= MIN_POINT_DIST * MIN_POINT_DIST
        if (interpolation == Interpolation.LINEAR) {
            if (seamOpen) line(last, f)
        } else if (ctrlTotal >= 2) {
            val prev = if (ctrlTotal >= 3) w[1]!! else w[2]!!
            if (closed && ctrlTotal >= 3) {
                if (seamOpen) {
                    segment(prev, w[2]!!, last, f)
                    segment(w[2]!!, last, f, second ?: f)
                } else {
                    segment(prev, w[2]!!, last, second ?: last)
                }
            } else {
                segment(prev, w[2]!!, last, last)
            }
        }
        ending = true
        advance(totalLen)
        val li = vCount - 1
        if (totalLen < 1f) {
            if (maxTaper < 0.5f) emitAt(vx[li], vy[li], vp[li], vt[li], va[li], vw[li], vm[li], totalLen, li, 1f)
        } else if (!closed && lastStep > 0f && totalLen - lastDab >= TAIL_FRACTION * lastStep) {
            emitAt(vx[li], vy[li], vp[li], vt[li], va[li], vw[li], vm[li], totalLen, li, taperAt(totalLen))
        }
        ctrlTotal = 0
    }

    private fun dist2(a: InputPoint, b: InputPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private fun streamingLimit(): Float = if (holdBack > 0f) totalLen - holdBack else totalLen

    private fun pushCtrl(p: InputPoint) {
        w[0] = w[1]; w[1] = w[2]; w[2] = w[3]; w[3] = p
        ctrlTotal++
    }

    private fun line(a: InputPoint, b: InputPoint) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        totalLen += sqrt(dx * dx + dy * dy)
        addVertex(b.x, b.y, b.pressure, b.tilt, b.azimuth, b.twist, (b.timeMs - t0).toFloat(), totalLen)
    }

    private fun segment(p0: InputPoint, p1: InputPoint, p2: InputPoint, p3: InputPoint) {
        val x0 = p0.x; val y0 = p0.y
        val x1 = p1.x; val y1 = p1.y
        val x2 = p2.x; val y2 = p2.y
        val x3 = p3.x; val y3 = p3.y
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
        val da = angleDiff(p1.azimuth, p2.azimuth)
        val tm1 = (p1.timeMs - t0).toFloat()
        val tm2 = (p2.timeMs - t0).toFloat()
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
                p1.pressure + (p2.pressure - p1.pressure) * t,
                p1.tilt + (p2.tilt - p1.tilt) * t,
                p1.azimuth + da * t,
                p1.twist + (p2.twist - p1.twist) * t,
                tm1 + (tm2 - tm1) * t,
                totalLen,
            )
            px = nx; py = ny
        }
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = (b - a) % TWO_PI
        if (d > PI) d -= TWO_PI else if (d < -PI) d += TWO_PI
        return d
    }

    private fun addVertex(x: Float, y: Float, p: Float, t: Float, a: Float, tw: Float, tm: Float, d: Float) {
        if (vCount == vx.size) {
            if (walkIdx > 0) compact() else grow()
        }
        vx[vCount] = x; vy[vCount] = y; vp[vCount] = p; vt[vCount] = t
        va[vCount] = a; vw[vCount] = tw; vm[vCount] = tm; vd[vCount] = d
        vCount++
    }

    private fun arrays() = listOf(vx, vy, vp, vt, va, vw, vm, vd)

    private fun compact() {
        // Keep one vertex before the walk position for the tangent.
        val shift = walkIdx
        val n = vCount - shift
        for (a in arrays()) System.arraycopy(a, shift, a, 0, n)
        vCount = n
        walkIdx = 0
        if (vCount == vx.size) grow()
    }

    private fun grow() {
        val cap = vx.size * 2
        vx = vx.copyOf(cap); vy = vy.copyOf(cap); vp = vp.copyOf(cap); vt = vt.copyOf(cap)
        va = va.copyOf(cap); vw = vw.copyOf(cap); vm = vm.copyOf(cap); vd = vd.copyOf(cap)
    }

    private fun advance(limit: Float) {
        if (deferForDirection && vCount < 2 && !ending) return
        while (nextDab <= limit + EPS) {
            if (closed && ending && subCount > 0 && nextDab > totalLen - SEAM_FRACTION * lastStep) break
            while (walkIdx + 1 < vCount && vd[walkIdx + 1] < nextDab) walkIdx++
            val i = walkIdx
            val step: Float
            if (i + 1 >= vCount) {
                step = emitAt(vx[i], vy[i], vp[i], vt[i], va[i], vw[i], vm[i], nextDab, i, taperAt(nextDab))
            } else {
                val seg = vd[i + 1] - vd[i]
                val f = if (seg > 0f) ((nextDab - vd[i]) / seg).coerceIn(0f, 1f) else 0f
                step = emitAt(
                    vx[i] + (vx[i + 1] - vx[i]) * f,
                    vy[i] + (vy[i + 1] - vy[i]) * f,
                    vp[i] + (vp[i + 1] - vp[i]) * f,
                    vt[i] + (vt[i + 1] - vt[i]) * f,
                    va[i] + (va[i + 1] - va[i]) * f,
                    vw[i] + (vw[i + 1] - vw[i]) * f,
                    vm[i] + (vm[i + 1] - vm[i]) * f,
                    nextDab, i, taperAt(nextDab),
                )
            }
            lastDab = nextDab
            lastStep = step
            nextDab += step
        }
    }

    private fun emitAt(
        x: Float, y: Float, p: Float, t: Float, a: Float, tw: Float, tm: Float, d: Float, vi: Int, taper: Float,
    ): Float {
        updateDirection(vi)
        val dd = d - prevD
        val dt = tm - prevT
        if (subCount > 0 && dt > 0f) vel += VEL_ALPHA * (dd * zoom / dt - vel)
        prevD = d
        prevT = tm
        subCount++
        maxTaper = max(maxTaper, taper)
        s.x = x; s.y = y; s.pressure = p; s.tilt = t
        var az = a % TWO_PI
        if (az < 0f) az += TWO_PI
        s.azimuth = az
        s.twist = tw
        s.timeMs = t0 + tm.toLong()
        s.distance = d
        s.dirX = dirX; s.dirY = dirY
        s.velocity = vel
        s.taper = taper
        s.index = sampleIndex++
        s.isStylus = stylus
        s.ending = ending
        s.total = if (ending) totalLen else Float.NaN
        s.remaining = if (ending) max(0f, totalLen - d) else Float.POSITIVE_INFINITY
        return max(MIN_STEP, sink.onSample(s))
    }

    private fun updateDirection(vi: Int) {
        val i = if (vi + 1 < vCount) vi else vi - 1
        if (i < 0) return
        val tx = vx[i + 1] - vx[i]
        val ty = vy[i + 1] - vy[i]
        val len = sqrt(tx * tx + ty * ty)
        if (len < 1e-6f) return
        val ux = tx / len
        val uy = ty / len
        if (!hasDir) {
            dirX = ux; dirY = uy; hasDir = true
            return
        }
        val nx = dirX + DIR_ALPHA * (ux - dirX)
        val ny = dirY + DIR_ALPHA * (uy - dirY)
        val nl = sqrt(nx * nx + ny * ny)
        if (nl > 1e-6f) {
            dirX = nx / nl; dirY = ny / nl
        } else {
            dirX = ux; dirY = uy
        }
    }

    private fun taperAt(d: Float): Float {
        var f = 1f
        if (taperStart > 0f) f = min(f, shapeOf(d / taperStart))
        if (ending && taperEnd > 0f) f = min(f, shapeOf((totalLen - d) / taperEnd))
        return f
    }

    private fun shapeOf(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return if (shape == TaperShape.LINEAR) c else { val k = 1f - c; 1f - k * k }
    }

    private companion object {
        const val INITIAL_CAP = 1024
        const val PIECE_LEN = 1f
        const val MAX_PIECES = 4096
        const val MIN_STEP = 0.5f
        const val MIN_POINT_DIST = 0.05f
        const val TAIL_FRACTION = 0.3f
        const val SEAM_FRACTION = 0.5f
        const val EPS = 1e-4f
        const val DIR_ALPHA = 0.35f
        const val VEL_ALPHA = 0.3f
        const val TWO_PI = (2 * PI).toFloat()
    }
}
