package app.painter.core.adjust

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * JSON form of [AdjustParams]: one object with a `kind` discriminator (manifest `adjust` block). Unknown kinds and
 * unreadable known kinds decode to [UnknownAdjust] and encode back unchanged.
 */
object AdjustCodec {
    private val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun toJson(p: AdjustParams): JsonObject =
        if (p is UnknownAdjust) p.raw else json.encodeToJsonElement(AdjustParams.serializer(), p).jsonObject

    fun fromJson(o: JsonObject): AdjustParams {
        val kind = (o["kind"] as? JsonPrimitive)?.content
        if (kind == null || AdjustKind.index(kind) < 0) return UnknownAdjust(o)
        return runCatching { json.decodeFromJsonElement(AdjustParams.serializer(), o) }.getOrElse { UnknownAdjust(o) }
    }

    fun encode(p: AdjustParams): String = json.encodeToString(JsonObject.serializer(), toJson(p))

    fun decode(s: String): AdjustParams = fromJson(json.parseToJsonElement(s).jsonObject)
}
