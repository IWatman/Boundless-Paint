package app.painter.studio

import android.content.Context
import app.painter.core.stroke.Curve
import app.painter.core.stroke.CurveSerializer
import app.painter.core.stroke.InputCalibration
import app.painter.render.PenAction
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
 * The file holds the pressure calibration of every input device, keyed by `InputDevice.descriptor`
 * (§4.3): an XP-Pen whose tip peaks at ≈0.78 keeps its corrected range across sessions, and the stored
 * peak history drives the automatic range from the first stroke on. The global [StylusSettings] (curve
 * preset, pen buttons, finger and palm rules) sit next to them in the same file.
 *
 * The file is a few hundred bytes, so [load] runs on the thread that creates the studio [StudioViewModel];
 * [save] belongs off the main thread and runs after a stylus stroke ended or a stylus setting changed.
 */
class InputSettings(context: Context) {

    /** Everything the file holds: the calibration of every device plus the global stylus settings. */
    data class Stored(
        val devices: Map<String, InputCalibration> = emptyMap(),
        val stylus: StylusSettings = StylusSettings(),
    )

    private val file = File(File(context.applicationContext.filesDir, DIR), FILE_NAME)

    /** Content of the file; the defaults when it is missing or unreadable. */
    fun load(): Stored {
        val text = runCatching { file.readText() }.getOrNull() ?: return Stored()
        return InputSettingsCodec.decode(text)
    }

    /** Replaces the stored content; a crash leaves the previous file rather than a truncated one. */
    fun save(stored: Stored) {
        val dir = file.parentFile ?: return
        dir.mkdirs()
        val tmp = File(dir, FILE_NAME + TMP_SUFFIX)
        tmp.writeText(InputSettingsCodec.encode(stored))
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
 * JSON form of [InputSettings]:
 * `{"schema":1,"stylus":{...},"devices":{"<descriptor>":{...}}}`. A device holds the calibration range, the
 * auto flag, the peak history and the pressure curve (in the core's curve format); the `stylus` object holds
 * the global [StylusSettings] with its enums by name. Free of Android APIs, so it is unit-tested on the JVM;
 * anything malformed falls back to a default instead of failing the whole file, and a file written before the
 * `stylus` object existed (M2 part 1) reads as the defaults.
 */
internal object InputSettingsCodec {

    private val json = Json { prettyPrint = true }

    /** JSON text of [stored]. */
    fun encode(stored: InputSettings.Stored): String =
        json.encodeToString(JsonObject.serializer(), toJson(stored))

    /** [encode] before printing, e.g. to embed the content into a larger document. */
    fun toJson(stored: InputSettings.Stored): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        putJsonObject("stylus") {
            val s = stored.stylus
            put(KEY_CURVE_PRESET, s.curvePreset.name)
            put(KEY_PRIMARY, s.primaryAction.name)
            put(KEY_SECONDARY, s.secondaryAction.name)
            put(KEY_FINGER, s.fingerWithStylus.name)
            put(KEY_PALM, s.palm.name)
        }
        putJsonObject("devices") {
            for ((descriptor, calibration) in stored.devices) {
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

    /** Content of [text]; a damaged device entry keeps the default calibration, a damaged file yields defaults. */
    fun decode(text: String): InputSettings.Stored {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return InputSettings.Stored()
        val out = LinkedHashMap<String, InputCalibration>()
        (root["devices"] as? JsonObject)?.forEach { (descriptor, element) -> out[descriptor] = device(element) }
        return InputSettings.Stored(out, stylus(root["stylus"]))
    }

    private fun stylus(element: JsonElement?): StylusSettings {
        val o = element as? JsonObject ?: return StylusSettings()
        return StylusSettings(
            curvePreset = o.enum(KEY_CURVE_PRESET, CurvePreset.NORMAL),
            primaryAction = o.enum(KEY_PRIMARY, PenAction.EYEDROPPER),
            secondaryAction = o.enum(KEY_SECONDARY, PenAction.UNDO),
            fingerWithStylus = o.enum(KEY_FINGER, FingerWithStylus.NAVIGATE_ONLY),
            palm = o.enum(KEY_PALM, PalmRejection.STYLUS_ONLY),
        )
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

    private inline fun <reified E : Enum<E>> JsonObject.enum(key: String, fallback: E): E {
        val name = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return fallback
        return enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }

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
    private const val KEY_CURVE_PRESET = "curvePreset"
    private const val KEY_PRIMARY = "primaryAction"
    private const val KEY_SECONDARY = "secondaryAction"
    private const val KEY_FINGER = "fingerWithStylus"
    private const val KEY_PALM = "palm"
}
