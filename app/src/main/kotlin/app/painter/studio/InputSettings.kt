package app.painter.studio

import android.content.Context
import app.painter.core.stroke.Curve
import app.painter.core.stroke.CurveSerializer
import app.painter.core.stroke.InputCalibration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Stylus settings that outlive a session, in `files/settings/input.json` (spec/tools/m2-brush.md §4.4).
 *
 * Today the file holds the pressure calibration of every input device, keyed by `InputDevice.descriptor`
 * (§4.3): an XP-Pen whose tip peaks at ≈0.78 keeps its corrected range across sessions, and the stored
 * peak history drives the automatic range from the first stroke on. The stylus settings screen adds the
 * pen buttons, the palm mode and the curves to the same file later.
 *
 * The file is a few hundred bytes, so [load] runs on the thread that creates the studio [StudioViewModel];
 * [save] belongs off the main thread and runs after a stylus stroke ended.
 */
class InputSettings(context: Context) {

    private val file = File(File(context.applicationContext.filesDir, DIR), FILE_NAME)

    /** Calibration of every device in the file; empty when it is missing or unreadable. */
    fun load(): Map<String, InputCalibration> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyMap()
        return InputSettingsCodec.decode(text)
    }

    /** Replaces the stored calibrations; a crash leaves the previous file rather than a truncated one. */
    fun save(calibrations: Map<String, InputCalibration>) {
        val dir = file.parentFile ?: return
        dir.mkdirs()
        val tmp = File(dir, FILE_NAME + TMP_SUFFIX)
        tmp.writeText(InputSettingsCodec.encode(calibrations))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private companion object {
        const val DIR = "settings"
        const val FILE_NAME = "input.json"
        const val TMP_SUFFIX = ".tmp"
    }
}

/**
 * JSON form of [InputSettings]: `{"schema":1,"devices":{"<descriptor>":{...}}}`. A device holds the
 * calibration range, the auto flag, the peak history and the pressure curve (in the core's curve format).
 * Free of Android APIs, so it is unit-tested on the JVM; anything malformed falls back to a default
 * calibration instead of failing the whole file.
 */
internal object InputSettingsCodec {

    private val json = Json { prettyPrint = true }

    /** JSON text of [devices], one entry per device descriptor. */
    fun encode(devices: Map<String, InputCalibration>): String =
        json.encodeToString(JsonObject.serializer(), toJson(devices))

    /** [encode] before printing, e.g. to embed the devices into a larger document. */
    fun toJson(devices: Map<String, InputCalibration>): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        putJsonObject("devices") {
            for ((descriptor, calibration) in devices) {
                val s = calibration.snapshot()
                putJsonObject(descriptor) {
                    put(KEY_MAX, s.maxPressure)
                    put(KEY_MIN, s.minPressure)
                    put(KEY_AUTO, s.auto)
                    put(KEY_PEAKS, JsonArray(s.peaks.map { JsonPrimitive(it) }))
                    put(KEY_WEAK, s.weakThreshold)
                    put(KEY_CURVE, json.encodeToJsonElement(CurveSerializer, s.pressureCurve))
                }
            }
        }
    }

    /** Devices of [text]; a damaged device entry keeps the default calibration, a damaged file yields none. */
    fun decode(text: String): Map<String, InputCalibration> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
        val devices = root["devices"] as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, InputCalibration>(devices.size)
        for ((descriptor, element) in devices) out[descriptor] = device(element)
        return out
    }

    private fun device(element: JsonElement): InputCalibration = InputCalibration().apply {
        val o = element as? JsonObject ?: return@apply
        restore(
            InputCalibration.Snapshot(
                maxPressure = o.float(KEY_MAX) ?: 1f,
                minPressure = o.float(KEY_MIN) ?: 0f,
                auto = o.bool(KEY_AUTO) ?: true,
                peaks = (o[KEY_PEAKS] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.floatOrNull() },
                weakThreshold = o.float(KEY_WEAK) ?: 0f,
                pressureCurve = o.curve(KEY_CURVE) ?: Curve.LINEAR,
            ),
        )
    }

    private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull()

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.curve(key: String): Curve? =
        (this[key] as? JsonObject)?.let { runCatching { json.decodeFromJsonElement(CurveSerializer, it) }.getOrNull() }

    /** A JSON literal as a number; `true`/`false` and garbage are not numbers. */
    private fun JsonPrimitive.floatOrNull(): Float? =
        if (isString) null else content.toFloatOrNull()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private const val SCHEMA = 1
    private const val KEY_MAX = "maxPressure"
    private const val KEY_MIN = "minPressure"
    private const val KEY_AUTO = "auto"
    private const val KEY_PEAKS = "peaks"
    private const val KEY_WEAK = "weakThreshold"
    private const val KEY_CURVE = "pressureCurve"
}
