package app.painter.core.filter

import app.painter.core.io.BrushIO
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** JSON form of the sealed [FilterParams] family, used by the project manifest for live filter layers. */
object FilterCodec {
    fun toJson(params: FilterParams): JsonObject =
        BrushIO.json.encodeToJsonElement(FilterParams.serializer(), params).jsonObject

    fun fromJson(obj: JsonObject): FilterParams =
        BrushIO.json.decodeFromJsonElement(FilterParams.serializer(), obj)
}
