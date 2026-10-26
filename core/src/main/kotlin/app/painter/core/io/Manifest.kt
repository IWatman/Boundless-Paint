package app.painter.core.io

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal const val MANIFEST_SCHEMA = 2

internal val manifestJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

@Serializable
internal data class ManifestDto(
    val schema: Int = MANIFEST_SCHEMA,
    val app: String = "painter",
    val id: String,
    val name: String,
    val type: String = "regular",
    val width: Int,
    val height: Int,
    val dpi: Int = 300,
    val colorMode: String = "rgb",
    val created: Long = 0,
    val modified: Long = 0,
    val paper: PaperDto = PaperDto(),
    val activeId: String? = null,
    val activeTarget: String = "layer",
    val nodes: List<NodeDto> = emptyList(),
    /** Present only when the document has a non-empty selection; tiles live in `selection/`. */
    val selection: SelectionDto? = null,
    /** Opaque guides block (schema owned by core/guide). */
    val guides: JsonObject? = null,
)

@Serializable
internal data class SelectionDto(val active: Boolean = true)

@Serializable
internal data class MaskDto(
    val enabled: Boolean = true,
    val linked: Boolean = true,
    val density: Float = 1f,
    /** Value of missing tiles, 0..255. */
    val default: Int = 255,
)

@Serializable
internal data class PaperDto(
    val color: String = "#FFFFFFFF",
    val visible: Boolean = true,
    val textureId: String? = null,
    val texDepth: Float = 0.5f,
    val texOpacity: Float = 0.5f,
    val texScale: Float = 1f,
)

@Serializable
internal data class NodeDto(
    val id: String,
    val name: String,
    val kind: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val blendMode: String = "normal",
    val clipped: Boolean = false,
    val hideInRecording: Boolean = false,
    val locked: Boolean = false,
    val alphaLock: Boolean = false,
    val expanded: Boolean = true,
    val colorTag: String = "none",
    val reference: Boolean = false,
    val mask: MaskDto? = null,
    /** Adjustment parameters (kind = adjustment), see [app.painter.core.adjust.AdjustCodec]. */
    val adjust: JsonObject? = null,
    val children: List<NodeDto> = emptyList(),
)

/** Header subset of the manifest, used for fast project listing. */
@Serializable
internal data class ManifestHeaderDto(
    val id: String = "",
    val name: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val modified: Long = 0,
)

internal object NodeKind {
    const val RASTER = "raster"
    const val GROUP = "group"
    const val ADJUSTMENT = "adjustment"
}
