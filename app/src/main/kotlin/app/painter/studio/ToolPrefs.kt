package app.painter.studio

import android.content.Context
import android.content.SharedPreferences
import app.painter.core.color.Rgba
import app.painter.ui.studio.BrushModeUi
import java.util.Locale

/**
 * Tool settings that outlive a session: the color, the palette and, per brush mode, the preset the user
 * picked plus the size and opacity they dialled in on top of it.
 *
 * Backed by the `tool_prefs` SharedPreferences file; every write goes through
 * [android.content.SharedPreferences.Editor.apply], which is asynchronous, so a setter per slider tick
 * needs no debounce. Reads are cheap (the file is loaded by the first getter), so they run on the caller's
 * thread.
 */
class ToolPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Color selected in the color panel. */
    var color: Rgba
        get() = ToolPrefsCodec.decodeColor(prefs.getString(KEY_COLOR, null)) ?: Rgba.BLACK
        set(value) {
            prefs.edit().putString(KEY_COLOR, ToolPrefsCodec.encodeColor(value)).apply()
        }

    /** Swatches of the palette tab, empty when nothing has been saved yet. */
    var palette: List<Rgba>
        get() = ToolPrefsCodec.decodePalette(prefs.getString(KEY_PALETTE, null))
        set(value) {
            prefs.edit().putString(KEY_PALETTE, ToolPrefsCodec.encodePalette(value)).apply()
        }

    /** Preset id last selected in [mode]; null when never saved or when [mode] paints with no preset. */
    fun brushId(mode: BrushModeUi): String? = prefs.getString(key(mode, KEY_ID), null)?.takeIf { it.isNotEmpty() }

    /** Brush size in document pixels the user set in [mode]; null when never saved. */
    fun brushSize(mode: BrushModeUi): Float? = prefs.floatOrNull(key(mode, KEY_SIZE))

    /** Brush opacity the user set in [mode]; null when never saved. */
    fun brushOpacity(mode: BrushModeUi): Float? = prefs.floatOrNull(key(mode, KEY_OPACITY))

    /** Stores the preset (null for a mode without one) and the user's size/opacity of [mode]. */
    fun saveBrush(mode: BrushModeUi, id: String?, size: Float, opacity: Float) {
        val edit = prefs.edit()
            .putFloat(key(mode, KEY_SIZE), size)
            .putFloat(key(mode, KEY_OPACITY), opacity)
        if (id.isNullOrEmpty()) edit.remove(key(mode, KEY_ID)) else edit.putString(key(mode, KEY_ID), id)
        edit.apply()
    }

    private fun key(mode: BrushModeUi, field: String) = "brush.${mode.name.lowercase(Locale.ROOT)}.$field"

    private fun SharedPreferences.floatOrNull(key: String): Float? =
        if (contains(key)) getFloat(key, 0f) else null

    private companion object {
        const val NAME = "tool_prefs"
        const val KEY_COLOR = "color"
        const val KEY_PALETTE = "palette"
        const val KEY_ID = "id"
        const val KEY_SIZE = "size"
        const val KEY_OPACITY = "opacity"
    }
}

/**
 * Text form of the values kept in [ToolPrefs]: a color is `#AARRGGBB`, the palette is the same strings
 * joined with `,`. Free of Android APIs, so it can be unit-tested on the JVM; anything malformed decodes
 * to null/empty and the caller falls back to its default.
 */
internal object ToolPrefsCodec {

    /** `#AARRGGBB` of [c]; components outside 0..1 are clamped. */
    fun encodeColor(c: Rgba): String =
        "#" + hex(c.a) + hex(c.r) + hex(c.g) + hex(c.b)

    /** Color encoded by [s], null when it is not a `#AARRGGBB` (or `AARRGGBB`) string. */
    fun decodeColor(s: String?): Rgba? {
        val v = argb(s) ?: return null
        return Rgba(
            ((v shr 16) and 0xFF) / 255f,
            ((v shr 8) and 0xFF) / 255f,
            (v and 0xFF) / 255f,
            (v ushr 24) / 255f,
        )
    }

    /** [colors] as a comma-separated list of `#AARRGGBB` strings. */
    fun encodePalette(colors: List<Rgba>): String = colors.joinToString(",") { encodeColor(it) }

    /** Colors of [encodePalette] output; entries that do not decode are dropped. */
    fun decodePalette(s: String?): List<Rgba> =
        if (s.isNullOrEmpty()) emptyList() else s.split(',').mapNotNull { decodeColor(it) }

    private fun hex(v: Float): String =
        ((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)).toString(16).uppercase(Locale.ROOT).padStart(2, '0')

    private fun argb(s: String?): Int? {
        val text = s?.trim()?.removePrefix("#") ?: return null
        if (text.length != 8) return null
        return text.toLongOrNull(16)?.toInt()
    }
}
