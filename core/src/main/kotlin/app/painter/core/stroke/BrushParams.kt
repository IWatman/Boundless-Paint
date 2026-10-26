package app.painter.core.stroke

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Dynamics curve on [0,1] → [0,1] defined by 2..N control points and interpolated with a
 * monotone cubic (Fritsch–Carlson) spline. The spline output is remapped to `min + (1 − min)·y`,
 * so [min] is the floor the target never drops below.
 *
 * Points must have strictly increasing x within [0,1]; inputs outside the covered range are
 * clamped to the end points.
 *
 * JSON form: `{"points": [[x, y], ...], "min": m}`; the M1 form with `{"first": x, "second": y}` points is
 * still accepted when reading.
 */
@Serializable(with = CurveSerializer::class)
data class Curve(
    val points: List<Pair<Float, Float>>,
    val min: Float = 0f,
) {
    @Transient
    private val xs: FloatArray = FloatArray(points.size) { points[it].first }

    @Transient
    private val ys: FloatArray = FloatArray(points.size) { points[it].second }

    @Transient
    private val tangents: FloatArray = computeTangents(xs, ys)

    init {
        require(points.size >= 2) { "curve needs at least 2 points" }
        for (i in 1 until points.size) require(points[i].first > points[i - 1].first) { "x must increase" }
    }

    /** Evaluates the curve at [x] (clamped to [0,1]); result is in [min, 1]. */
    fun eval(x: Float): Float {
        val n = xs.size
        val y = when {
            x <= xs[0] -> ys[0]
            x >= xs[n - 1] -> ys[n - 1]
            else -> {
                var i = 0
                while (i < n - 2 && x > xs[i + 1]) i++
                val h = xs[i + 1] - xs[i]
                val t = (x - xs[i]) / h
                val t2 = t * t
                val t3 = t2 * t
                (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * tangents[i] +
                    (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * tangents[i + 1]
            }
        }
        val m = min.coerceIn(0f, 1f)
        return (m + (1f - m) * y.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    /** True when the curve is constant on [x0, x1] (sampled every 1/64 of the range). */
    fun isConstantOn(x0: Float, x1: Float): Boolean {
        val y0 = eval(x0)
        for (k in 1..64) if (kotlin.math.abs(eval(x0 + (x1 - x0) * k / 64f) - y0) > 1e-4f) return false
        return true
    }

    companion object {
        /** Identity curve y = x without a floor. */
        val LINEAR = Curve(listOf(0f to 0f, 1f to 1f))

        /** Linear curve with the given floor. */
        fun linear(min: Float) = Curve(listOf(0f to 0f, 1f to 1f), min)

        private fun computeTangents(xs: FloatArray, ys: FloatArray): FloatArray {
            val n = xs.size
            val m = FloatArray(n)
            if (n < 2) return m
            val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) * 0.5f
            for (i in 0 until n - 1) {
                if (d[i] == 0f) {
                    m[i] = 0f
                    m[i + 1] = 0f
                    continue
                }
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val tau = 3f / kotlin.math.sqrt(s)
                    m[i] = tau * a * d[i]
                    m[i + 1] = tau * b * d[i]
                }
            }
            return m
        }
    }
}

/** JSON-only serializer of [Curve]; see the KDoc there for the format. */
object CurveSerializer : KSerializer<Curve> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.painter.core.stroke.Curve")

    override fun serialize(encoder: Encoder, value: Curve) {
        val out = encoder as? JsonEncoder ?: throw SerializationException("Curve supports JSON only")
        out.encodeJsonElement(
            buildJsonObject {
                put("points", JsonArray(value.points.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) }))
                put("min", value.min)
            },
        )
    }

    override fun deserialize(decoder: Decoder): Curve {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Curve supports JSON only")
        val obj = input.decodeJsonElement().jsonObject
        val pts = obj["points"]?.jsonArray ?: throw SerializationException("curve without points")
        val points = pts.map { p ->
            if (p is JsonArray) {
                p[0].jsonPrimitive.float to p[1].jsonPrimitive.float
            } else {
                val o: JsonObject = p.jsonObject
                o.getValue("first").jsonPrimitive.float to o.getValue("second").jsonPrimitive.float
            }
        }
        return Curve(points, (obj["min"] as? JsonPrimitive)?.float ?: 0f)
    }
}

/**
 * Brush parameters (spec/brush-engine.md §2, spec/tools/m2-brush.md §3.1). The M1 fields stay at the top
 * level; M2 groups are nested. Percent parameters are 0..1 except [smoothing], [smoothingSpeed] and
 * [afterSmoothing], which use the UI scale 0..100. Angles are degrees.
 *
 * @property size dab diameter in document pixels at full pressure.
 * @property sizeMin lower bound of the size slider.
 * @property sizeMax upper bound of the size slider.
 * @property opacity stroke opacity cap 0..1.
 * @property flow per-dab opacity 0..1.
 * @property spacing distance between dabs as a fraction of the current size.
 * @property hardness 0 = fully soft edge, 1 = hard edge.
 * @property pressureToSize pressure → size factor (an implicit size link).
 * @property pressureToFlow pressure → flow factor; null disables the link.
 * @property smoothing real-time EMA stabilizer strength 0..100.
 * @property smoothingSpeed extra smoothing that grows with pen speed 0..100.
 * @property taperStart length of the start taper in pixels (0 = off).
 * @property taperEnd length of the end taper in pixels (0 = off).
 * @property buildup dabs accumulate directly in the layer without the opacity cap.
 * @property afterSmoothing pen-up re-fit of the whole stroke 0..100: Gaussian over arc length with
 *   σ = 0.5·value px, then the stroke is re-rendered.
 * @property lazyRadius lazy rope radius in screen pixels (0 = off).
 * @property lazyElastic 0..1: the rope radius grows with pen speed.
 * @property taperShape taper easing.
 * @property taperSize how much the taper scales the size.
 * @property taperOpacity how much the taper scales the dab alpha.
 * @property tiltOffset −1..1: the dab centre moves along the azimuth by `tilt·offset·d/2`.
 */
@Serializable
data class BrushParams(
    val size: Float = 20f,
    val sizeMin: Float = 1f,
    val sizeMax: Float = 500f,
    val opacity: Float = 1f,
    val flow: Float = 1f,
    val spacing: Float = 0.10f,
    val hardness: Float = 0.8f,
    val pressureToSize: Curve = Curve.linear(0.2f),
    val pressureToFlow: Curve? = null,
    val smoothing: Float = 10f,
    val smoothingSpeed: Float = 0f,
    val taperStart: Float = 0f,
    val taperEnd: Float = 0f,
    val buildup: Boolean = false,
    val afterSmoothing: Float = 0f,
    val lazyRadius: Float = 0f,
    val lazyElastic: Float = 0f,
    val taperShape: TaperShape = TaperShape.SMOOTH,
    val taperSize: Float = 1f,
    val taperOpacity: Float = 0f,
    val tiltOffset: Float = 0f,
    val head: HeadParams = HeadParams(),
    val jitter: JitterParams = JitterParams(),
    val texture: TextureParams? = null,
    val paint: PaintParams = PaintParams(),
    val effects: EffectParams = EffectParams(),
    val special: SpecialParams? = null,
    val dynamics: List<DynamicsLink> = emptyList(),
    val profiles: Profiles? = null,
) {
    /** Dab diameter for [pressure] before tapering. */
    fun sizeAt(pressure: Float): Float = size.coerceAtLeast(0f) * pressureToSize.eval(pressure)

    /** Dab flow for [pressure]. */
    fun flowAt(pressure: Float): Float =
        flow.coerceIn(0f, 1f) * (pressureToFlow?.eval(pressure) ?: 1f)
}

