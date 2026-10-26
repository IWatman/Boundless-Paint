package app.painter.update

/**
 * Version of a published release, e.g. `0.1.0` or `1.2.3-beta.1`.
 *
 * A version with a pre-release suffix sorts below the same version without one, so
 * `1.0.0-beta.1 < 1.0.0`. Pre-release identifiers are compared numerically when they are
 * numbers and lexicographically otherwise.
 */
data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease != null) "-$preRelease" else ""

    companion object {
        private val PATTERN = Regex(
            """^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?(?:\+[0-9A-Za-z.\-]+)?${'$'}""",
        )

        /** Parses `v0.1.0`, `0.1.0` or `1.2.3-beta.1`; returns `null` for anything else. */
        fun parse(raw: String): Version? {
            val match = PATTERN.matchEntire(raw.trim()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val patch = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }
            return Version(major, minor, patch, pre)
        }

        private fun comparePreRelease(a: String?, b: String?): Int = when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> compareIdentifiers(a, b)
        }

        private fun compareIdentifiers(a: String, b: String): Int {
            val left = a.split('.')
            val right = b.split('.')
            for (i in 0 until minOf(left.size, right.size)) {
                val l = left[i]
                val r = right[i]
                val ln = l.toIntOrNull()
                val rn = r.toIntOrNull()
                val cmp = when {
                    ln != null && rn != null -> ln.compareTo(rn)
                    ln != null -> -1
                    rn != null -> 1
                    else -> l.compareTo(r)
                }
                if (cmp != 0) return cmp
            }
            return left.size.compareTo(right.size)
        }
    }
}
