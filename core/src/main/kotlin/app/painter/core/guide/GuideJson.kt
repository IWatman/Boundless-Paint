package app.painter.core.guide

import app.painter.core.geom.Vec2
import app.painter.core.perspective.PerspectiveConfig
import app.painter.core.perspective.PerspectiveType
import app.painter.core.shape.NodeType
import app.painter.core.shape.PathNode
import app.painter.core.symmetry.MirrorAxes
import app.painter.core.symmetry.SymmetryConfig
import app.painter.core.symmetry.SymmetryType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Codec of the manifest `guides` block (spec G1.5). Missing block or fields give defaults, unknown
 * fields are ignored, unknown enum strings fall back to defaults. Angles are degrees.
 */
object GuideJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun encode(state: GuideState): String = json.encodeToString(GuidesDto.serializer(), state.toDto())

    /** Decodes [text]; null, blank or malformed input yields [GuideState] defaults. */
    fun decode(text: String?): GuideState {
        if (text.isNullOrBlank()) return GuideState()
        return try {
            json.decodeFromString(GuidesDto.serializer(), text).toState()
        } catch (e: SerializationException) {
            GuideState()
        } catch (e: IllegalArgumentException) {
            GuideState()
        }
    }

    /** The block as a JSON tree, for embedding into the manifest. */
    fun toElement(state: GuideState): JsonElement = json.encodeToJsonElement(GuidesDto.serializer(), state.toDto())

    /** Reads a block embedded in the manifest; null or malformed yields defaults. */
    fun fromElement(element: JsonElement?): GuideState {
        if (element == null) return GuideState()
        return try {
            json.decodeFromJsonElement(GuidesDto.serializer(), element).toState()
        } catch (e: SerializationException) {
            GuideState()
        } catch (e: IllegalArgumentException) {
            GuideState()
        }
    }
}

@Serializable
private data class GuidesDto(
    val v: Int = GuideState.VERSION,
    val lines: LinesDto = LinesDto(),
    val ellipse: EllipseDto = EllipseDto(),
    val pen: PenDto = PenDto(),
    val lazy: LazyDto = LazyDto(),
    val hatching: HatchingDto = HatchingDto(),
    val symmetry: SymmetryDto = SymmetryDto(),
    val perspective: PerspectiveDto = PerspectiveDto(),
    val grid: GridDto = GridDto(),
)

@Serializable
private data class LinesDto(
    val on: Boolean = false,
    val a: List<Float> = listOf(0f, 0f),
    val b: List<Float> = listOf(100f, 0f),
    val perpendicular: Boolean = true,
    val magnet: Boolean = false,
    val edge: Boolean = false,
    val locked: Boolean = false,
)

@Serializable
private data class EllipseDto(
    val on: Boolean = false,
    val c: List<Float> = listOf(0f, 0f),
    val rx: Float = 300f,
    val ratio: Float = 0.5f,
    val angle: Float = 0f,
    val magnet: Boolean = false,
    val edge: Boolean = false,
    val locked: Boolean = false,
)

@Serializable
private data class NodeDto(
    val p: List<Float> = listOf(0f, 0f),
    val t: String = "cusp",
    @SerialName("in") val inH: List<Float> = listOf(0f, 0f),
    val out: List<Float> = listOf(0f, 0f),
)

@Serializable
private data class PenDto(
    val on: Boolean = false,
    val nodes: List<NodeDto> = emptyList(),
    val closed: Boolean = false,
    val locked: Boolean = false,
)

@Serializable
private data class LazyDto(val on: Boolean = false, val radius: Float = 60f, val elastic: Float = 0f)

@Serializable
private data class HatchingDto(val on: Boolean = false)

@Serializable
private data class SymmetryDto(
    val on: Boolean = false,
    val type: String = "mirror",
    val c: List<Float> = listOf(0f, 0f),
    val angle: Float = 90f,
    val axes: String = "v",
    val planes: Int = SymmetryConfig.DEFAULT_PLANES,
    val fan: Float = 360f,
    val crop: Boolean = false,
    val locked: Boolean = false,
)

@Serializable
private data class PerspectiveDto(
    val on: Boolean = false,
    val type: String = "1pt",
    val vp: List<List<Float>> = emptyList(),
    val horizon: Float = 0f,
    val spacing: Float = PerspectiveConfig.DEFAULT_SPACING,
    val ratio: Float = 1f,
    val origin: List<Float> = listOf(0f, 0f),
    val snap: Boolean = true,
    val visible: Boolean = true,
    val locked: Boolean = false,
)

@Serializable
private data class GridDto(
    val on: Boolean = false,
    val minor: Float = 32f,
    val majorEvery: Int = 4,
    val origin: List<Float> = listOf(0f, 0f),
    val minorColor: String = "#FFFFFF26",
    val majorColor: String = "#FFFFFF4D",
)

private fun Vec2.arr() = listOf(x, y)
private fun List<Float>.vec() = Vec2(getOrElse(0) { 0f }, getOrElse(1) { 0f })

private val SYM_TYPES = mapOf("mirror" to SymmetryType.MIRROR, "radial" to SymmetryType.RADIAL, "kaleido" to SymmetryType.KALEIDO)
private val AXES = mapOf("v" to MirrorAxes.V, "h" to MirrorAxes.H, "vh" to MirrorAxes.VH)
private val PERSP = mapOf(
    "1pt" to PerspectiveType.ONE_POINT, "2pt" to PerspectiveType.TWO_POINT, "3pt" to PerspectiveType.THREE_POINT,
    "iso" to PerspectiveType.ISO, "grid2d" to PerspectiveType.GRID_2D,
)
private val NODE_TYPES = mapOf("cusp" to NodeType.CUSP, "smooth" to NodeType.SMOOTH, "aligned" to NodeType.ALIGNED)

private fun <T> Map<String, T>.keyOf(v: T): String = entries.first { it.value == v }.key

private fun GuideState.toDto() = GuidesDto(
    lines = LinesDto(lines.on, lines.a.arr(), lines.b.arr(), lines.perpendicular, lines.magnet, lines.edge, lines.locked),
    ellipse = EllipseDto(
        ellipse.on, ellipse.c.arr(), ellipse.rx, ellipse.ratio, ellipse.angleDeg, ellipse.magnet, ellipse.edge, ellipse.locked,
    ),
    pen = PenDto(
        pen.on,
        pen.nodes.map { NodeDto(it.p.arr(), NODE_TYPES.keyOf(it.type), it.inHandle.arr(), it.outHandle.arr()) },
        pen.closed, pen.locked,
    ),
    lazy = LazyDto(lazy.on, lazy.radius, lazy.elastic),
    hatching = HatchingDto(hatching.on),
    symmetry = with(symmetry) {
        SymmetryDto(
            on, SYM_TYPES.keyOf(type), center.arr(), angleDeg, AXES.keyOf(axes), planes, fanDeg, crop, locked,
        )
    },
    perspective = with(perspective) {
        PerspectiveDto(
            on, PERSP.keyOf(type), vp.map { it.arr() }, horizonDeg, spacing, ratio, origin.arr(), snap, visible, locked,
        )
    },
    grid = with(grid) { GridDto(on, minor, majorEvery, origin.arr(), minorColor, majorColor) },
)

private fun GuidesDto.toState() = GuideState(
    lines = LinesConfig(lines.on, lines.a.vec(), lines.b.vec(), lines.perpendicular, lines.magnet, lines.edge, lines.locked),
    ellipse = EllipseConfig(
        ellipse.on, ellipse.c.vec(), ellipse.rx, ellipse.ratio.coerceIn(EllipseConfig.MIN_RATIO, 1f), ellipse.angle,
        ellipse.magnet, ellipse.edge, ellipse.locked,
    ),
    pen = PenConfig(
        pen.on,
        pen.nodes.map { PathNode(it.p.vec(), NODE_TYPES[it.t] ?: NodeType.CUSP, it.inH.vec(), it.out.vec()) },
        pen.closed, pen.locked,
    ),
    lazy = LazyConfig(lazy.on, lazy.radius.coerceIn(0f, 200f), lazy.elastic.coerceIn(0f, 100f)),
    hatching = HatchingConfig(hatching.on),
    symmetry = with(symmetry) {
        SymmetryConfig(
            on, SYM_TYPES[type] ?: SymmetryType.MIRROR, c.vec(), angle, AXES[axes] ?: MirrorAxes.V, planes, fan, crop, locked,
        )
    },
    perspective = with(perspective) {
        PerspectiveConfig(
            on, PERSP[type] ?: PerspectiveType.ONE_POINT, vp.map { it.vec() }, horizon, spacing, ratio, origin.vec(),
            snap, visible, locked,
        )
    },
    grid = with(grid) { GridConfig(on, minor, majorEvery, origin.vec(), minorColor, majorColor) },
)
