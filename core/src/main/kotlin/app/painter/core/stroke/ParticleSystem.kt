package app.painter.core.stroke

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Particles (spec/tools/m2-brush.md §1.10): the dabs of the buffered path paint nothing of their own; each one is a
 * step of a particle system that stamps the stroke instead.
 *
 * The step is the path step (driven by the resampled dab stream), not time, so the result is deterministic: the
 * only randomness comes from [HashRng] seeded by the stroke seed, the step index and the particle index.
 *
 * | element | rule |
 * |---|---|
 * | attractors | `n_a` (1–64) at `path point + radius·d/2·dirₖ`, a fixed unit `dirₖ` per attractor |
 * | particles | `n_p` (1–400), particle `j` bound to attractor `j mod n_a` |
 * | dynamics | `v = v·damp + 0.3·(target − x)`, `damp = 0.5 + 0.24·overshoot` |
 * | impulse | a small random kick from the head's continuous scatter (jitter), px per step |
 * | drawing | every particle stamps the particle head (diameter `d·p_size`) along the segment from its previous |
 * |         | position, spacing 50% of that diameter |
 *
 * A higher `overshoot` lets the velocity survive the spring (`damp` closer to 1), so on a sharp turn the particles
 * fly past the attractor — that is the "splash outside the contour" the spec asks for. At `n_p > 100` the system
 * runs at most once per `d/4` of path ([MAX_FREE] below), which bounds the dab count.
 */
internal class ParticleSystem(
    private val params: ParticlesParams,
    private val seed: Long,
    scatter: Float,
) {
    /** Amplitude factor of the random impulse (the head's continuous scatter). */
    // Brush files may come from outside (import), so every parameter is made finite and bounded here.
    private val impulse = finite(scatter).coerceIn(0f, 1f)

    /** Attractors (1–64) and particles (1–400) of the system (spec §1.10). */
    private val attractors = params.attractors.coerceIn(1, MAX_ATTRACTORS)
    private val count = params.particles.coerceIn(1, MAX_PARTICLES)

    /** Spring damping `0.5 + 0.24·overshoot`; closer to 1 lets the particles overshoot the attractor. */
    private val damp = (0.5f + 0.24f * finite(params.overshoot)).coerceIn(0f, 0.99f)

    /** Attractor offset as a multiple of `d/2` (spec `radius`). */
    private val offset = finite(params.radius).coerceIn(0f, MAX_RADIUS)

    /** Diameter of the stamped head as a fraction of the path diameter (spec `size`). */
    private val headScale = finite(params.size).coerceIn(0f, MAX_HEAD_SCALE)

    /** Fixed unit direction of every attractor, drawn once from the stroke seed. */
    private val dirX = FloatArray(attractors)
    private val dirY = FloatArray(attractors)

    /** Attractor targets of the current step. */
    private val targetX = FloatArray(attractors)
    private val targetY = FloatArray(attractors)

    /** Particle positions and velocities. */
    private val xs = FloatArray(count)
    private val ys = FloatArray(count)
    private val vxs = FloatArray(count)
    private val vys = FloatArray(count)

    private var started = false
    private var lastX = 0f
    private var lastY = 0f
    private var pending = 0f
    private var step = 0L

    init {
        for (k in 0 until attractors) {
            val a = TWO_PI * HashRng.rnd(seed, CH_DIR, k.toLong())
            dirX[k] = cos(a)
            dirY[k] = sin(a)
        }
    }

    /** Forgets the system so a re-rendered stroke replays it from the first point. */
    fun reset() {
        started = false
        pending = 0f
        step = 0L
    }

    /**
     * Feeds the path dabs of [batch] and appends the stamped particle dabs to [out]; the path dabs themselves are
     * not drawn. A dab closer than the step budget to the last one is skipped, so the result does not depend on how
     * the input is split into batches.
     */
    fun add(batch: DabBatch, out: DabBatch) {
        for (i in 0 until batch.size) {
            val dab = batch[i]
            val d = dab.d
            if (d <= 0f) continue
            val x = dab.x
            val y = dab.y
            if (!started) {
                started = true
                lastX = x
                lastY = y
                pending = 0f
                java.util.Arrays.fill(xs, x)
                java.util.Arrays.fill(ys, y)
                java.util.Arrays.fill(vxs, 0f)
                java.util.Arrays.fill(vys, 0f)
                advance(x, y, d, dab, out)
                continue
            }
            val dx = x - lastX
            val dy = y - lastY
            pending += sqrt(dx * dx + dy * dy)
            lastX = x
            lastY = y
            // At n_p > MAX_FREE the system runs at most once per d/4 of path (spec §1.10); a repeated dab is skipped.
            val minStep = if (count > MAX_FREE) d * 0.25f else 0f
            if (pending <= 1e-4f || pending < minStep) continue
            advance(x, y, d, dab, out)
            pending = 0f
        }
    }

    /** One system step at the path point `(x, y)` with path diameter [d]; appends the stamps to [out]. */
    private fun advance(x: Float, y: Float, d: Float, src: Dab, out: DabBatch) {
        val half = offset * d * 0.5f
        for (k in 0 until attractors) {
            targetX[k] = x + dirX[k] * half
            targetY[k] = y + dirY[k] * half
        }
        val pd = (d * headScale).coerceAtLeast(MIN_DIAMETER)
        val spacing = pd * 0.5f
        val amp = impulse * d * 0.5f
        val base = step * count
        for (j in 0 until count) {
            val a = j % attractors
            val key = base + j
            val ix = if (amp > 0f) amp * HashRng.signed(seed, CH_IMPULSE, key * 2) else 0f
            val iy = if (amp > 0f) amp * HashRng.signed(seed, CH_IMPULSE, key * 2 + 1) else 0f
            val vx = vxs[j] * damp + 0.3f * (targetX[a] - xs[j]) + ix
            val vy = vys[j] * damp + 0.3f * (targetY[a] - ys[j]) + iy
            val ox = xs[j]
            val oy = ys[j]
            val nx = ox + vx
            val ny = oy + vy
            xs[j] = nx
            ys[j] = ny
            vxs[j] = vx
            vys[j] = vy
            if (params.trail) {
                stamp(ox, oy, nx, ny, pd, spacing, src, out)
            } else if (HashRng.rnd(seed, CH_EMIT, key) < EMIT_CHANCE) {
                // Without a trail a particle drops a stamp only now and then, so dots scatter instead of lining up
                // along each particle's track; a small random offset breaks up the parallel tracks of the
                // particles bound to one attractor.
                val jx = d * EMIT_JITTER * HashRng.signed(seed, CH_EMIT_OFFSET, key * 2)
                val jy = d * EMIT_JITTER * HashRng.signed(seed, CH_EMIT_OFFSET, key * 2 + 1)
                emit(nx + jx, ny + jy, pd, 0f, src, out)
            }
        }
        step++
    }

    /** Stamps [src]'s head along `(ox, oy)→(nx, ny)` every [spacing] px, plus a dab at the new position. */
    private fun stamp(ox: Float, oy: Float, nx: Float, ny: Float, pd: Float, spacing: Float, src: Dab, out: DabBatch) {
        val dx = nx - ox
        val dy = ny - oy
        val len = sqrt(dx * dx + dy * dy)
        if (!len.isFinite() || !nx.isFinite() || !ny.isFinite()) return
        val angle = if (len > 1e-5f) atan2(dy, dx) else 0f
        // An integer, capped stamp count: a float accumulator could stall on rounding, and a runaway particle
        // must not flood the batch.
        val n = if (spacing > 0f) ceil(len / spacing).toInt().coerceIn(0, MAX_STAMPS) else 0
        for (i in 0 until n) {
            val t = i.toFloat() / n
            emit(ox + dx * t, oy + dy * t, pd, angle, src, out)
        }
        emit(nx, ny, pd, angle, src, out)
    }

    /** Appends one particle dab of diameter [d] at `(x, y)`, taking colour, flow and opacity from [src]. */
    private fun emit(x: Float, y: Float, d: Float, angle: Float, src: Dab, out: DabBatch) {
        val dab = out.add()
        dab.set(src)
        dab.x = x
        dab.y = y
        dab.d = d
        dab.angle = angle
        dab.sx = 1f
        dab.sy = 1f
        dab.flipX = false
    }

    private companion object {
        /** Bounds of the system (spec §1.10). */
        const val MAX_ATTRACTORS = 64
        const val MAX_PARTICLES = 400

        /** Below this particle count the system takes every path step; above it, one step per `d/4`. */
        const val MAX_FREE = 100

        /** Upper bounds for untrusted brush values: attractor offset (×d/2), head scale and stamps per segment. */
        const val MAX_RADIUS = 16f
        const val MAX_HEAD_SCALE = 4f
        const val MAX_STAMPS = 64

        /** Chance per step that a trail-less particle drops a stamp. */
        const val EMIT_CHANCE = 0.12f

        /** Offset of a trail-less stamp from its particle, as a fraction of the path diameter. */
        const val EMIT_JITTER = 0.2f

        /** Random channels of the trail-less emission: whether to stamp, and the stamp offset. */
        const val CH_EMIT = 22
        const val CH_EMIT_OFFSET = 23

        /** [v], or 0 when it is NaN or infinite. */
        fun finite(v: Float): Float = if (v.isFinite()) v else 0f

        /** Smallest stamped diameter, so a tiny particle still deposits a pixel. */
        const val MIN_DIAMETER = 0.5f

        const val TWO_PI = (2 * PI).toFloat()

        /** Random channels of the particle engine; a private stream of this engine, not an [RngChannel]. */
        const val CH_DIR = 18
        const val CH_IMPULSE = 19
    }
}
