package app.painter.studio

import android.content.Context
import android.content.SharedPreferences
import app.painter.core.color.Rgba
import app.painter.core.material.MaterialKind
import app.painter.core.material.MaterialMode
import app.painter.core.material.MaterialPrefsCodec
import app.painter.core.material.MaterialSpec
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

    /** Studio backdrop around the canvas: light #D0D0D0 instead of the default dark #2B2B2B. */
    var lightBackground: Boolean
        get() = prefs.getBoolean(KEY_LIGHT_BG, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LIGHT_BG, value).apply()
        }

    /** True when a finger double tap fits the canvas (default on, spec/tools/m0-m1-tools.md T1). */
    var doubleTapFits: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP_FIT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DOUBLE_TAP_FIT, value).apply()
        }

    /** Records a timelapse frame after each change (M3, off by default). */
    var timelapseEnabled: Boolean
        get() = prefs.getBoolean(KEY_TIMELAPSE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TIMELAPSE, value).apply()
        }

    /**
     * Material of the material tool as last confirmed in its dialog: null until the user picks one, so a
     * fresh install keeps the dialog defaults and the tool stays out of the menu. Values are clamped on read.
     */
    var materialSpec: MaterialSpec?
        get() = MaterialPrefsCodec.decodeSpec(
            kind = prefs.getString(KEY_MATERIAL_KIND, null),
            size = prefs.floatOrNull(KEY_MATERIAL_SIZE),
            rotationDeg = prefs.floatOrNull(KEY_MATERIAL_ROTATION),
            opacity = prefs.floatOrNull(KEY_MATERIAL_OPACITY),
            spacing = prefs.floatOrNull(KEY_MATERIAL_SPACING),
        )
        set(value) {
            val edit = prefs.edit()
            if (value == null) {
                edit.remove(KEY_MATERIAL_KIND)
                    .remove(KEY_MATERIAL_SIZE)
                    .remove(KEY_MATERIAL_ROTATION)
                    .remove(KEY_MATERIAL_OPACITY)
                    .remove(KEY_MATERIAL_SPACING)
            } else {
                val spec = MaterialPrefsCodec.clamp(value)
                edit.putString(KEY_MATERIAL_KIND, MaterialPrefsCodec.encodeKind(spec.kind))
                    .putFloat(KEY_MATERIAL_SIZE, spec.size)
                    .putFloat(KEY_MATERIAL_ROTATION, spec.rotationDeg)
                    .putFloat(KEY_MATERIAL_OPACITY, spec.opacity)
                    .putFloat(KEY_MATERIAL_SPACING, spec.spacing)
            }
            edit.apply()
        }

    /** Paint or stamp mode last confirmed in the material dialog. */
    var materialMode: MaterialMode
        get() = MaterialPrefsCodec.decodeMode(prefs.getString(KEY_MATERIAL_MODE, null))
            ?: MaterialMode.PAINT
        set(value) {
            prefs.edit().putString(KEY_MATERIAL_MODE, MaterialPrefsCodec.encodeMode(value)).apply()
        }

    /** Materials starred in the material dialog. */
    var materialFavourites: Set<MaterialKind>
        get() = MaterialPrefsCodec.decodeKinds(
            prefs.getString(KEY_MATERIAL_FAVOURITES, null),
            MaterialKind.entries.size,
        ).toSet()
        set(value) {
            val ordered = MaterialKind.entries.filter { it in value }
            prefs.edit()
                .putString(KEY_MATERIAL_FAVOURITES, MaterialPrefsCodec.encodeKinds(ordered))
                .apply()
        }

    /** Last used materials, newest first, at most [MaterialPrefsCodec.MAX_RECENT]. */
    var materialRecent: List<MaterialKind>
        get() = MaterialPrefsCodec.decodeKinds(
            prefs.getString(KEY_MATERIAL_RECENT, null),
            MaterialPrefsCodec.MAX_RECENT,
        )
        set(value) {
            prefs.edit()
                .putString(KEY_MATERIAL_RECENT, MaterialPrefsCodec.encodeKinds(value.take(MaterialPrefsCodec.MAX_RECENT)))
                .apply()
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
        const val KEY_LIGHT_BG = "view.light_background"
        const val KEY_DOUBLE_TAP_FIT = "view.double_tap_fit"
        const val KEY_TIMELAPSE = "timelapse.enabled"
        const val KEY_MATERIAL_KIND = "material.kind"
        const val KEY_MATERIAL_SIZE = "material.size"
        const val KEY_MATERIAL_ROTATION = "material.rotation"
        const val KEY_MATERIAL_OPACITY = "material.opacity"
        const val KEY_MATERIAL_SPACING = "material.spacing"
        const val KEY_MATERIAL_MODE = "material.mode"
        const val KEY_MATERIAL_FAVOURITES = "material.favourites"
        const val KEY_MATERIAL_RECENT = "material.recent"
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
