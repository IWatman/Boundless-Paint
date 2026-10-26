package app.painter.core.blend

/**
 * All blend modes of the app (spec/blend-and-color.md). [id] is the stable string used in files;
 * [shaderIndex] is the stable integer used by GPU shaders. Never reorder or reuse indices.
 */
enum class BlendMode(val id: String, val shaderIndex: Int, val group: Group) {
    NORMAL("normal", 0, Group.NORMAL),
    PASSTHROUGH("passthrough", 1, Group.NORMAL),
    DARKEN("darken", 2, Group.DARKEN),
    MULTIPLY("multiply", 3, Group.DARKEN),
    COLOR_BURN("color_burn", 4, Group.DARKEN),
    LINEAR_BURN("linear_burn", 5, Group.DARKEN),
    DARKER_COLOR("darker_color", 6, Group.DARKEN),
    LIGHTEN("lighten", 7, Group.LIGHTEN),
    SCREEN("screen", 8, Group.LIGHTEN),
    COLOR_DODGE("color_dodge", 9, Group.LIGHTEN),
    LINEAR_DODGE("linear_dodge", 10, Group.LIGHTEN),
    ADD("add", 11, Group.LIGHTEN),
    LIGHTER_COLOR("lighter_color", 12, Group.LIGHTEN),
    OVERLAY("overlay", 13, Group.CONTRAST),
    SOFT_LIGHT("soft_light", 14, Group.CONTRAST),
    HARD_LIGHT("hard_light", 15, Group.CONTRAST),
    VIVID_LIGHT("vivid_light", 16, Group.CONTRAST),
    LINEAR_LIGHT("linear_light", 17, Group.CONTRAST),
    PIN_LIGHT("pin_light", 18, Group.CONTRAST),
    HARD_MIX("hard_mix", 19, Group.CONTRAST),
    DIFFERENCE("difference", 20, Group.COMPARATIVE),
    EXCLUSION("exclusion", 21, Group.COMPARATIVE),
    NEGATIVE("negative", 22, Group.COMPARATIVE),
    INVERT("invert", 23, Group.COMPARATIVE),
    SUBTRACT("subtract", 24, Group.COMPARATIVE),
    DIVIDE("divide", 25, Group.COMPARATIVE),
    HUE("hue", 26, Group.HSL),
    SATURATION("saturation", 27, Group.HSL),
    COLOR("color", 28, Group.HSL),
    LUMINOSITY("luminosity", 29, Group.HSL),
    ERASE("erase", 30, Group.MASKING),
    MASK("mask", 31, Group.MASKING),
    LINE_ART("line_art", 32, Group.MASKING);

    enum class Group { NORMAL, DARKEN, LIGHTEN, CONTRAST, COMPARATIVE, HSL, MASKING }

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): BlendMode = byId[id] ?: NORMAL

        /** Modes of [group] in shader-index order (the layer panel list). */
        fun inGroup(group: Group): List<BlendMode> = entries.filter { it.group == group }

        /** Modes exposed in the M1 layer panel. */
        @Deprecated("M2 lists every mode by group: use entries grouped by Group")
        val M1 = listOf(
            NORMAL, MULTIPLY, SCREEN, OVERLAY, DARKEN, LIGHTEN,
            COLOR_DODGE, COLOR_BURN, SOFT_LIGHT, HARD_LIGHT, ADD, DIFFERENCE,
        )
    }
}
