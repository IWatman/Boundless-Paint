package app.painter.core.stroke

/**
 * Stateless random numbers (spec/tools/m2-brush.md §0): `rnd(ch, i) = mix64(seed ⊕ i·φ ⊕ ch·K) → [0, 1)`.
 * The value of a dab depends only on (seed, channel, dab index), so results do not depend on thread count
 * or on how input is split into batches.
 */
object HashRng {
    private const val GOLDEN = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
    private const val CHANNEL_K = -0x3d4d51c2d82b14b1L // 0xC2B2AE3D27D4EB4F

    /** Stafford's "Mix13" 64-bit finalizer (bijective, full avalanche). */
    fun mix64(z0: Long): Long {
        var z = z0
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Uniform float in [0, 1) with 24 random bits. */
    fun rnd(seed: Long, ch: Int, i: Long): Float =
        (mix64(seed xor (i * GOLDEN) xor (ch.toLong() * CHANNEL_K)) ushr 40).toFloat() * (1f / (1 shl 24))

    /** Uniform in [−1, 1). */
    fun signed(seed: Long, ch: Int, i: Long): Float = 2f * rnd(seed, ch, i) - 1f

    /** Stroke seed from a stroke counter and the brush id (`seed = hash(strokeIndex, brushId)`). */
    fun strokeSeed(strokeIndex: Long, brushId: String): Long {
        var h = mix64(strokeIndex * GOLDEN + 1)
        for (c in brushId) h = mix64(h xor c.code.toLong())
        return h
    }
}

/** Random channel numbers. Never renumber: presets and tests depend on the streams. */
object RngChannel {
    const val SIZE = 1
    const val FLOW = 2
    const val OPACITY = 3
    const val ANGLE = 4
    const val SPACING = 5
    const val SCATTER_N = 6
    const val SCATTER_T = 7
    const val ROTATION_RANDOM = 8
    const val DYN = 9
    const val HUE = 10
    const val SAT = 11
    const val BRI = 12
    const val INITIAL_ANGLE = 13
    const val INITIAL_HUE = 14
    const val INITIAL_SAT = 15
    const val INITIAL_BRI = 16

    /** Per-stroke texture jitter (§1.4): the UV offset of u (index 0) and v (index 1), the scale factor and the angle. */
    const val TEX_POS = 17
    const val TEX_SCALE = 18
    const val TEX_ANGLE = 19
}
