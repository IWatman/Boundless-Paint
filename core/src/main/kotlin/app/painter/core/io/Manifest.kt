package app.painter.core.io

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val MANIFEST_SCHEMA = 1

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
    val nodes: List<NodeDto> = emptyList(),
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
}
