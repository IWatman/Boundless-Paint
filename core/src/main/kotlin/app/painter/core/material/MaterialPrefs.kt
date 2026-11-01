package app.painter.core.material

/** How a material is applied when the material dialog is confirmed. */
enum class MaterialMode {
    /** A drag paints along the gesture and a tap stamps once at the pointer. */
    PAINT,

    /** One stamp at the centre of the canvas, or of the selection. */
    STAMP,
}

/**
 * Text form of the material settings kept in the tool preferences, plus the small list operations of the
 * material library. Free of Android APIs, so it is unit-testable on the JVM: unknown names are dropped, the
 * lists keep their order, and every restored [MaterialSpec] is clamped to the limits the tools accept.
 */
object MaterialPrefsCodec {

    /** How many recently used materials the dialog remembers. */
    const val MAX_RECENT = 4

    /** Kind encoded by [value], null when the string is not a [MaterialKind] name. */
    fun decodeKind(value: String?): MaterialKind? = decodeKinds(value, 1).firstOrNull()

    /** Name of [kind], the value [decodeKind] reads back. */
    fun encodeKind(kind: MaterialKind): String = kind.name

    /**
     * Kinds of a comma-separated [value], in the order they were stored: unknown names are dropped, a kind
     * that appears twice keeps its first place, and at most [limit] kinds are returned.
     */
    fun decodeKinds(value: String?, limit: Int): List<MaterialKind> {
        if (value.isNullOrBlank() || limit <= 0) return emptyList()
        val out = LinkedHashSet<MaterialKind>()
        for (part in value.split(',')) {
            val name = part.trim()
            val kind = MaterialKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: continue
            out.add(kind)
            if (out.size >= limit) break
        }
        return out.toList()
    }

    /** [kinds] as a comma-separated list of enum names, in iteration order. */
    fun encodeKinds(kinds: Iterable<MaterialKind>): String = kinds.joinToString(",") { it.name }

    /** [favourites] with [kind] added, or without it when it is already there. */
    fun toggleFavourite(favourites: Set<MaterialKind>, kind: MaterialKind): Set<MaterialKind> =
        if (kind in favourites) favourites - kind else favourites + kind

    /**
     * [recent] with [kind] moved to the front and duplicates removed, capped at [limit]. The last used
     * material is always the first entry.
     */
    fun addRecent(
        recent: List<MaterialKind>,
        kind: MaterialKind,
        limit: Int = MAX_RECENT,
    ): List<MaterialKind> {
        if (limit <= 0) return emptyList()
        return (listOf(kind) + recent.filter { it != kind }).take(limit)
    }

    /** Stored name of [mode]. */
    fun encodeMode(mode: MaterialMode): String = mode.name

    /** Mode encoded by [value], null when it is not a known mode name. */
    fun decodeMode(value: String?): MaterialMode? {
        val name = value?.trim() ?: return null
        return MaterialMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Spec stored as separate fields: null when [kind] is missing or unknown. Missing or non-finite numeric
     * fields keep the [MaterialSpec] defaults; every value is clamped to the range the tools accept.
     */
    fun decodeSpec(
        kind: String?,
        size: Float? = null,
        rotationDeg: Float? = null,
        opacity: Float? = null,
        spacing: Float? = null,
    ): MaterialSpec? {
        val decoded = decodeKind(kind) ?: return null
        return clamp(
            MaterialSpec(
                kind = decoded,
                size = size ?: MaterialSpec.DEFAULT_SIZE,
                rotationDeg = rotationDeg ?: MaterialSpec.DEFAULT_ROTATION,
                opacity = opacity ?: MaterialSpec.DEFAULT_OPACITY,
                spacing = spacing ?: MaterialSpec.DEFAULT_SPACING,
            ),
        )
    }

    /** [spec] with its numeric fields inside the ranges [MaterialSpec] documents and the angle wrapped to 0..360. */
    fun clamp(spec: MaterialSpec): MaterialSpec = MaterialSpec(
        kind = spec.kind,
        size = finite(spec.size, MaterialSpec.DEFAULT_SIZE)
            .coerceIn(MaterialSpec.MIN_SIZE, MaterialSpec.MAX_SIZE),
        rotationDeg = normalizeAngle(finite(spec.rotationDeg, MaterialSpec.DEFAULT_ROTATION)),
        opacity = finite(spec.opacity, MaterialSpec.DEFAULT_OPACITY).coerceIn(0f, 1f),
        spacing = finite(spec.spacing, MaterialSpec.DEFAULT_SPACING)
            .coerceIn(MaterialSpec.MIN_SPACING, MaterialSpec.MAX_SPACING),
    )

    private fun finite(value: Float, fallback: Float): Float = if (value.isFinite()) value else fallback

    private fun normalizeAngle(deg: Float): Float = ((deg % 360f) + 360f) % 360f
}
