package app.painter.core.stroke

import app.painter.core.color.ColorConv
import app.painter.core.color.Hsb
import app.painter.core.color.Rgba
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Builds the dabs of one path sample (spec/tools/m2-brush.md §1.1–§1.6): size and flow from dynamics, taper and
 * profiles; angle from the rotation mode, jitter and dynamics; aspect; scatter; count; colour jitter; tilt
 * offset; pixel snapping when antialiasing is off. Every random value comes from [HashRng] keyed by the dab
 * index, so the output is a pure function of the sample sequence.
 *
 * Expects pressure already normalised (finger = 1) and calibrated. Per-dab `opacity` is a ceiling: the pixel
 * alpha approaches it instead of 1 (an approximation of IP's per-dab opacity inside the stroke buffer).
 */
class DabGenerator(brush: Brush, private val res: BrushResources, private val ctx: StrokeContext, color: Rgba) {
    private val p = brush.params
    private val head = p.head
    private val jit = p.jitter
    private val seed = ctx.seed
    private val baseSize = p.size.coerceAtLeast(0f)
    private val baseFlow = p.flow.coerceIn(0f, 1f)
    private val spacing = if (head.dots) max(p.spacing, 1f) else p.spacing
    private val count = jit.count.coerceIn(1, MAX_COUNT)
    private val links: Array<List<DynamicsLink>> = Array(DynTarget.entries.size) { t ->
        p.dynamics.filter { it.target.ordinal == t }
    }
    private val hasRandomSource = p.dynamics.any { it.source == DynSource.RANDOM }

    private val sx: Float
    private val sy: Float

    private val cr = color.r
    private val cg = color.g
    private val cb = color.b
    private val ca = color.a
    private val colorJitter = jit.hue > 0f || jit.saturation > 0f || jit.brightness > 0f ||
        jit.initialHue > 0f || jit.initialSaturation > 0f || jit.initialBrightness > 0f
    private val baseHsb: Hsb = ColorConv.toHsb(color)
    private val initHue = jit.initialHue * 180f * HashRng.signed(seed, RngChannel.INITIAL_HUE, 0)
    private val initSat = jit.initialSaturation * HashRng.signed(seed, RngChannel.INITIAL_SAT, 0)
    private val initBri = jit.initialBrightness * HashRng.signed(seed, RngChannel.INITIAL_BRI, 0)
    private val initAngle = jit.initialAngle * PI_F * HashRng.signed(seed, RngChannel.INITIAL_ANGLE, 0)

    private var fingerDirX = Float.NaN
    private var fingerDirY = 0f
    private var lastDistance = -1f

    /** True when the rotation needs the path direction at the first sample. */
    val needsDirection: Boolean = head.rotationMode == RotationMode.TANGENT || head.rotationMode == RotationMode.REVERSE ||
        head.rotationMode == RotationMode.STYLUS_AZIMUTH || jit.scatter > 0f

    init {
        val a = head.aspect.coerceIn(-1f, 1f)
        sx = if (a < 0f) 1f + 0.95f * a else 1f
        sy = if (a > 0f) 1f - 0.95f * a else 1f
    }

    /** Appends the dabs of [s] to [out] and returns the distance to the next sample. */
    fun emit(s: PathSample, out: DabBatch): Float {
        val pr = if (ctx.pressureEnabled) s.pressure else 1f
        val tiltN = if (ctx.tiltEnabled) (s.tilt / HALF_PI).coerceIn(0f, 1f) else 0f
        if (s.distance < lastDistance) fingerDirX = Float.NaN
        lastDistance = s.distance
        if (fingerDirX.isNaN() && s.distance >= FINGER_DIR_PX) {
            fingerDirX = s.dirX; fingerDirY = s.dirY
        }

        val px = if (p.profiles != null) profileX(s) else 0f
        var stepSize = baseSize * p.pressureToSize.eval(pr)
        stepSize = applyLinks(DynTarget.SIZE, stepSize, s, pr, tiltN, 0L)
        stepSize = mulTaper(stepSize, s.taper, p.taperSize)
        p.profiles?.size?.let { stepSize *= it.eval(px) }

        var step = spacing * stepSize
        if (jit.spacing > 0f) step *= 1f + 0.5f * jit.spacing * HashRng.signed(seed, RngChannel.SPACING, s.index.toLong())

        val weak = ctx.calibration?.weakThreshold ?: 0f
        if (s.isStylus && weak > 0f && s.pressure < weak) return step

        for (k in 0 until count) {
            val di = s.index.toLong() * MAX_COUNT + k
            val dab = out.add()
            dab.index = di.toInt()
            var size = stepSize
            if (hasRandomSource) size = applyRandomLinks(DynTarget.SIZE, size, di)
            if (jit.size > 0f) size *= 1f - jit.size * HashRng.rnd(seed, RngChannel.SIZE, di)

            var flow = baseFlow * (p.pressureToFlow?.eval(pr) ?: 1f)
            flow = applyLinks(DynTarget.FLOW, flow, s, pr, tiltN, di)
            p.profiles?.flow?.let { flow *= it.eval(px) }
            if (jit.flow > 0f) flow *= 1f - jit.flow * HashRng.rnd(seed, RngChannel.FLOW, di)
            flow = mulTaper(flow, s.taper, p.taperOpacity)

            var opacity = applyLinks(DynTarget.OPACITY, 1f, s, pr, tiltN, di)
            if (jit.opacity > 0f) opacity *= 1f - jit.opacity * HashRng.rnd(seed, RngChannel.OPACITY, di)

            dab.d = size
            dab.flow = flow
            dab.opacity = opacity.coerceIn(0f, 1f)
            dab.hardness = applyLinks(DynTarget.HARDNESS, p.hardness, s, pr, tiltN, di).coerceIn(0f, 1f)
            dab.pressure = pr
            dab.sx = sx
            dab.sy = sy
            dab.flipX = false
            dab.angle = angleOf(s, pr, tiltN, di)
            dab.s = s.distance

            var x = s.x
            var y = s.y
            val scatter = jit.scatter * linkProduct(DynTarget.SCATTER, s, pr, tiltN, di)
            if (scatter > 0f) {
                val n = scatter * size * HashRng.signed(seed, RngChannel.SCATTER_N, di)
                val t = 0.25f * scatter * size * HashRng.signed(seed, RngChannel.SCATTER_T, di)
                x += -s.dirY * n + s.dirX * t
                y += s.dirX * n + s.dirY * t
            }
            if (p.tiltOffset != 0f && tiltN > 0f) {
                val off = tiltN * p.tiltOffset * size * 0.5f
                x += cos(s.azimuth) * off
                y += sin(s.azimuth) * off
            }
            if (!head.antialias) {
                x = floor(x) + 0.5f
                y = floor(y) + 0.5f
            }
            dab.x = x
            dab.y = y

            if (colorJitter) {
                val amp = linkProduct(DynTarget.COLOR_JITTER, s, pr, tiltN, di)
                val h = baseHsb.h + initHue + jit.hue * amp * 180f * HashRng.signed(seed, RngChannel.HUE, di)
                val sat = baseHsb.s + initSat + jit.saturation * amp * HashRng.signed(seed, RngChannel.SAT, di)
                val bri = baseHsb.v + initBri + jit.brightness * amp * HashRng.signed(seed, RngChannel.BRI, di)
                val c = ColorConv.fromHsb(Hsb(h, sat.coerceIn(0f, 1f), bri.coerceIn(0f, 1f)), ca)
                dab.r = c.r; dab.g = c.g; dab.b = c.b
            } else {
                dab.r = cr; dab.g = cg; dab.b = cb
            }
            dab.a = ca
            dab.texDepth = applyLinks(DynTarget.TEX_DEPTH, p.texture?.depth ?: 0f, s, pr, tiltN, di)
            dab.mixIn = applyLinks(DynTarget.MIX_IN, p.paint.mixIn, s, pr, tiltN, di)
            dab.dilution = applyLinks(DynTarget.DILUTION, p.paint.dilution, s, pr, tiltN, di)
            dab.blur = applyLinks(DynTarget.BLUR, p.paint.blur, s, pr, tiltN, di)
            if (size <= 0f || flow <= 0f) out.removeLast()
        }
        return step
    }

    /**
     * True when a stroke of [total] px must be re-rendered at pen-up because of the profiles: it is shorter
     * than both profile ends, or the middle section of a curve is not constant (streamed with x = ½).
     */
    fun profileNeedsRerender(total: Float): Boolean {
        val pf = p.profiles ?: return false
        if (pf.size == null && pf.flow == null) return false
        if (total < pf.startPx + pf.endPx) return true
        return pf.size?.isConstantOn(THIRD, 2 * THIRD) == false || pf.flow?.isConstantOn(THIRD, 2 * THIRD) == false
    }

    private fun profileX(s: PathSample): Float {
        val pf = p.profiles!!
        var ls = pf.startPx
        var le = pf.endPx
        if (s.ending && s.total < ls + le) {
            ls = s.total / 3f
            le = ls
        }
        val d = s.distance
        if (ls > 0f && d < ls) return d / ls * THIRD
        if (le > 0f && s.remaining < le) return 1f - s.remaining / le * THIRD
        if (!s.ending) return 0.5f
        val mid = s.total - ls - le
        return if (mid > 0f) THIRD + ((d - ls) / mid).coerceIn(0f, 1f) * THIRD else 0.5f
    }

    private fun angleOf(s: PathSample, pr: Float, tiltN: Float, di: Long): Float {
        var a = head.angle * DEG + initAngle
        val theta = atan2(s.dirY, s.dirX)
        val f = if (head.rotationFollow == 0f) 1f else kotlin.math.abs(head.rotationFollow.coerceIn(-1f, 1f))
        a += when (head.rotationMode) {
            RotationMode.FIXED -> 0f
            RotationMode.TANGENT -> f * theta
            RotationMode.REVERSE -> -f * theta
            RotationMode.STYLUS_AZIMUTH -> if (s.isStylus) s.azimuth else fingerAngle(s)
            RotationMode.STYLUS_TILT -> if (s.isStylus) s.azimuth * tiltN else 0f
            RotationMode.TWIST -> s.twist
            RotationMode.RANDOM -> TWO_PI * HashRng.rnd(seed, RngChannel.ROTATION_RANDOM, di)
        }
        if (jit.angle > 0f) a += jit.angle * PI_F * HashRng.signed(seed, RngChannel.ANGLE, di)
        for (l in links[DynTarget.ANGLE.ordinal]) {
            a += TWO_PI * (l.curve.eval(source(l.source, s, pr, tiltN, di)) - l.curve.eval(0f))
        }
        if (head.orientScreen) a += ctx.viewRotation
        return a
    }

    private fun fingerAngle(s: PathSample): Float =
        if (fingerDirX.isNaN()) atan2(s.dirY, s.dirX) else atan2(fingerDirY, fingerDirX)

    private fun source(src: DynSource, s: PathSample, pr: Float, tiltN: Float, di: Long): Float = when (src) {
        DynSource.PRESSURE -> pr
        DynSource.VELOCITY -> if (ctx.velocityEnabled) (s.velocity / 5f).coerceIn(0f, 1f) else 0f
        DynSource.TILT -> tiltN
        DynSource.AZIMUTH -> s.azimuth / TWO_PI
        DynSource.TWIST -> { val t = (s.twist / TWO_PI) % 1f; if (t < 0f) t + 1f else t }
        DynSource.RANDOM -> HashRng.rnd(seed, RngChannel.DYN, di)
        DynSource.PROGRESS -> (s.distance / PROGRESS_PX).coerceIn(0f, 1f)
    }

    /** Applies the product links of [target]; random-source links of SIZE are applied per dab instead. */
    private fun applyLinks(target: DynTarget, v0: Float, s: PathSample, pr: Float, tiltN: Float, di: Long): Float {
        var v = v0
        val perDab = target == DynTarget.SIZE
        for (l in links[target.ordinal]) {
            if (perDab && l.source == DynSource.RANDOM) continue
            v *= l.curve.eval(source(l.source, s, pr, tiltN, di))
        }
        return v
    }

    private fun applyRandomLinks(target: DynTarget, v0: Float, di: Long): Float {
        var v = v0
        for (l in links[target.ordinal]) if (l.source == DynSource.RANDOM) v *= l.curve.eval(HashRng.rnd(seed, RngChannel.DYN, di))
        return v
    }

    private fun linkProduct(target: DynTarget, s: PathSample, pr: Float, tiltN: Float, di: Long): Float {
        var v = 1f
        for (l in links[target.ordinal]) v *= l.curve.eval(source(l.source, s, pr, tiltN, di))
        return v
    }

    /** `v · lerp(1, f, amount)`, exact for amount 0 and 1. */
    private fun mulTaper(v: Float, f: Float, amount: Float): Float = when {
        amount <= 0f || f >= 1f -> v
        amount >= 1f -> v * f
        else -> v * (1f + (f - 1f) * amount)
    }

    private companion object {
        const val MAX_COUNT = 16
        const val PI_F = PI.toFloat()
        const val TWO_PI = (2 * PI).toFloat()
        const val HALF_PI = (PI / 2).toFloat()
        const val DEG = (PI / 180).toFloat()
        const val THIRD = 1f / 3f
        const val FINGER_DIR_PX = 8f
        const val PROGRESS_PX = 300f
    }
}
