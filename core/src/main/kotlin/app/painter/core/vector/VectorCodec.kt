package app.painter.core.vector

import app.painter.core.color.Rgba
import app.painter.core.stroke.BrushParams
import app.painter.core.stroke.StrokeMode
import app.painter.core.symmetry.MirrorAxes
import app.painter.core.symmetry.SymmetryConfig
import app.painter.core.symmetry.SymmetryType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * JSON form of the vector paths stored in the `vector` field of a `kind: "vector"` manifest node.
 *
 * Schema 1: `{"version":1,"strokes":[{"points":[{"x":..,"y":..,...}],"brush":{...},"color":"#RRGGBBAA",
 * "mode":"paint","seed":0,"zoom":1,"rotation":0,"symmetry":{...}}]}`. Unknown keys are ignored so later
 * versions can add fields without breaking older readers.
 */
object VectorCodec {
    private const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Serializable
    private data class RootDto(val version: Int = VERSION, val strokes: List<StrokeDto> = emptyList())

    @Serializable
    private data class StrokeDto(
        val points: List<PointDto> = emptyList(),
        val brush: BrushParams = BrushParams(),
        val color: String = "#000000FF",
        val mode: String = "paint",
        val seed: Long = 0,
        val zoom: Float = 1f,
        val rotation: Float = 0f,
        val symmetry: SymmetryDto? = null,
    )

    @Serializable
    private data class PointDto(
        val x: Float,
        val y: Float,
        val pressure: Float = 1f,
        val tilt: Float = 0f,
        val azimuth: Float = 0f,
        val twist: Float = 0f,
        val timeMs: Long = 0,
    )

    @Serializable
    private data class SymmetryDto(
        val on: Boolean = true,
        val type: String = "mirror",
        val cx: Float = 0f,
        val cy: Float = 0f,
        val angle: Float = 90f,
        val axes: String = "v",
        val planes: Int = SymmetryConfig.DEFAULT_PLANES,
        val fan: Float = 360f,
        val crop: Boolean = false,
    )

    fun toJson(strokes: List<VectorStroke>): JsonObject =
        json.encodeToJsonElement(RootDto.serializer(), RootDto(strokes = strokes.map(::toDto))) as JsonObject

    fun fromJson(obj: JsonObject): List<VectorStroke> {
        val root = json.decodeFromJsonElement(RootDto.serializer(), obj)
        require(root.version == VERSION) { "unsupported vector schema ${root.version}" }
        return root.strokes.map(::fromDto)
    }

    private fun toDto(s: VectorStroke) = StrokeDto(
        points = s.points.map { PointDto(it.x, it.y, it.pressure, it.tilt, it.azimuth, it.twist, it.timeMs) },
        brush = s.brush,
        color = toHex(s.color),
        mode = if (s.mode == StrokeMode.ERASE) "erase" else "paint",
        seed = s.seed,
        zoom = s.zoom,
        rotation = s.viewRotation,
        symmetry = s.symmetry?.let(::toDto),
    )

    private fun fromDto(d: StrokeDto) = VectorStroke(
        points = d.points.map { VectorPoint(it.x, it.y, it.pressure, it.tilt, it.azimuth, it.twist, it.timeMs) },
        brush = d.brush,
        color = parseHex(d.color) ?: Rgba.BLACK,
        mode = if (d.mode.equals("erase", ignoreCase = true)) StrokeMode.ERASE else StrokeMode.PAINT,
        seed = d.seed,
        zoom = if (d.zoom > 0f) d.zoom else 1f,
        viewRotation = d.rotation,
        symmetry = d.symmetry?.let(::fromDto),
    )

    private fun toDto(c: SymmetryConfig) = SymmetryDto(
        on = c.on,
        type = when (c.type) {
            SymmetryType.MIRROR -> "mirror"
            SymmetryType.RADIAL -> "radial"
            SymmetryType.KALEIDO -> "kaleido"
        },
        cx = c.center.x,
        cy = c.center.y,
        angle = c.angleDeg,
        axes = when (c.axes) {
            MirrorAxes.V -> "v"
            MirrorAxes.H -> "h"
            MirrorAxes.VH -> "vh"
        },
        planes = c.planes,
        fan = c.fanDeg,
        crop = c.crop,
    )

    private fun fromDto(d: SymmetryDto) = SymmetryConfig(
        on = d.on,
        type = when (d.type.lowercase()) {
            "radial" -> SymmetryType.RADIAL
            "kaleido" -> SymmetryType.KALEIDO
            else -> SymmetryType.MIRROR
        },
        center = app.painter.core.geom.Vec2(d.cx, d.cy),
        angleDeg = d.angle,
        axes = when (d.axes.lowercase()) {
            "h" -> MirrorAxes.H
            "vh" -> MirrorAxes.VH
            else -> MirrorAxes.V
        },
        planes = d.planes,
        fanDeg = d.fan,
        crop = d.crop,
    )

    private fun toHex(c: Rgba): String {
        fun b(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
        return "#%02X%02X%02X%02X".format(b(c.r), b(c.g), b(c.b), b(c.a))
    }

    private fun parseHex(s: String): Rgba? {
        val h = s.removePrefix("#")
        if (h.length != 6 && h.length != 8) return null
        val v = h.toLongOrNull(16) ?: return null
        val full = if (h.length == 6) (v shl 8) or 0xFF else v
        fun c(shift: Int) = ((full shr shift) and 0xFF) / 255f
        return Rgba(c(24), c(16), c(8), c(0))
    }
}
